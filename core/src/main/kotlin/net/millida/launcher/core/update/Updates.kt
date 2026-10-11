package net.millida.launcher.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.Hashing
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.HttpError
import net.millida.launcher.core.http.NetworkFaults
import net.millida.launcher.core.http.RemoteFile
import net.millida.launcher.core.http.TransferProgress
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.io.IOException

const val UPDATE_MANIFEST_URL = "https://launcher-storage.millida.net/mobile/latest.json"

const val ANDROID_PLATFORM = "android"

val UPDATE_HOSTS: Set<String> = setOf("launcher-storage.millida.net")

@Serializable
data class UpdateManifest(
    val version: String = "",
    val notes: String = "",
    val releasedAt: String = "",
    val platforms: Map<String, AndroidBuild> = emptyMap(),
) {
    @Serializable
    data class AndroidBuild(
        val versionCode: Long = 0,
        val url: String = "",
        val size: Long = 0,
        val sha256: String = "",
        val minSdk: Int = 0,
    )

    fun buildFor(platform: String = ANDROID_PLATFORM): AndroidBuild? = platforms[platform]
}

data class UpdateRelease(
    val version: String,
    val versionCode: Long,
    val url: String,
    val size: Long,
    val sha256: String,
    val releasedAt: String,
    val notes: String,
    val minSdk: Int,
)

sealed interface UpdateVerdict {
    data class Available(val release: UpdateRelease) : UpdateVerdict

    data object UpToDate : UpdateVerdict

    data class Rejected(val reason: String) : UpdateVerdict
}

object UpdateCheck {

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    fun fromUpdateHost(url: String): Boolean {
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!parsed.scheme.equals("https", ignoreCase = true)) return false
        if (parsed.rawUserInfo != null) return false
        if (parsed.port != -1 && parsed.port != 443) return false
        val host = parsed.host?.lowercase() ?: return false
        return host in UPDATE_HOSTS
    }

    fun isNewer(remoteVersionCode: Long, currentVersionCode: Long): Boolean =
        remoteVersionCode > currentVersionCode

    /**
     * The whole decision is one pure function so the rule "only a higher
     * versionCode updates" can be pinned by tests instead of being spread over
     * the download path where a network is needed to reach it.
     */
    fun decide(
        manifest: UpdateManifest,
        currentVersionCode: Long,
        deviceSdk: Int = 0,
        platform: String = ANDROID_PLATFORM,
    ): UpdateVerdict {
        val version = manifest.version.trim().removePrefix("v")
        if (version.isEmpty()) return UpdateVerdict.Rejected("в манифесте обновления нет версии")

        val build = manifest.buildFor(platform)
            ?: return UpdateVerdict.Rejected("в манифесте нет сборки под Android")
        if (build.versionCode <= 0) {
            return UpdateVerdict.Rejected("в манифесте нет номера сборки — обновляться не по чему")
        }
        if (!isNewer(build.versionCode, currentVersionCode)) return UpdateVerdict.UpToDate

        val url = build.url.trim()
        if (!url.startsWith("https://")) {
            return UpdateVerdict.Rejected("адрес обновления должен начинаться с https://")
        }
        if (!fromUpdateHost(url)) {
            return UpdateVerdict.Rejected("обновление лежит не на нашей раздаче — такой файл мы не ставим")
        }

        val sha256 = build.sha256.trim()
        if (!SHA256.matches(sha256)) {
            return UpdateVerdict.Rejected(
                "у обновления нет контрольной суммы — такой файл мы не ставим",
            )
        }
        if (build.minSdk > 0 && deviceSdk > 0 && build.minSdk > deviceSdk) {
            return UpdateVerdict.Rejected(
                "обновление рассчитано на Android новее, чем на этом устройстве",
            )
        }

        return UpdateVerdict.Available(
            UpdateRelease(
                version = version,
                versionCode = build.versionCode,
                url = url,
                size = build.size,
                sha256 = sha256.lowercase(),
                releasedAt = manifest.releasedAt.trim(),
                notes = manifest.notes,
                minSdk = build.minSdk,
            )
        )
    }
}

object UpdateFaults {

    private const val MAX_CAUSE_DEPTH = 8

    fun describe(error: Throwable): String {
        if (NetworkFaults.unknownHost(error) != null) {
            return NetworkFaults.nameLookupAdvice("канала обновлений")
        }
        val status = causes(error).filterIsInstance<HttpError>().firstOrNull()
        if (status != null) {
            return if (status.code == 404) {
                "Канал обновлений ещё не открыт — новые версии пока раздаём файлом"
            } else {
                "Канал обновлений ответил ошибкой ${status.code} — повтори позже"
            }
        }
        val unreadable = causes(error).firstOrNull { it is IOException && it.message?.startsWith("манифест") == true }
        if (unreadable != null) {
            return "Канал обновлений отдал битый файл — повтори позже"
        }
        return "Канал обновлений не ответил — проверь сеть и повтори"
    }

    private fun causes(error: Throwable): List<Throwable> =
        generateSequence(error) { current -> current.cause.takeIf { it !== current } }
            .take(MAX_CAUSE_DEPTH)
            .toList()
}

data class ReleaseNotes(
    val intro: String = "",
    val sections: List<Section> = emptyList(),
) {
    data class Section(val title: String, val items: List<String>)

    val hasContent: Boolean
        get() = intro.isNotEmpty() || sections.any { it.items.isNotEmpty() }
}

object ReleaseNotesParser {

    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.+?)\\s*$")
    private val BULLET = Regex("^\\s*(?:[-*•])\\s+(.*)$")

    /** Canonical order of the changelog, so the window never depends on how the file was typed. */
    private val KNOWN = listOf("Добавлено", "Изменено", "Исправлено", "Убрано")

    /**
     * The release pipeline writes a stand-in line when a tag carries no message,
     * and a window whose whole content is that line tells the player nothing.
     */
    private val PLACEHOLDER = Regex("^обновление millida\\b.*", RegexOption.IGNORE_CASE)

    fun parse(markdown: String): ReleaseNotes {
        val intro = StringBuilder()
        val order = mutableListOf<String>()
        val buckets = linkedMapOf<String, MutableList<String>>()
        var current: String? = null

        for (raw in markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val heading = HEADING.matchEntire(line)?.groupValues?.get(1)?.trim()?.trimEnd(':')
            if (heading != null) {
                val title = canonical(heading)
                if (buckets[title] == null) {
                    buckets[title] = mutableListOf()
                    order += title
                }
                current = title
                continue
            }

            val bullet = BULLET.matchEntire(line)?.groupValues?.get(1)?.trim()
            val section = current
            if (section == null) {
                val text = bullet ?: line
                if (PLACEHOLDER.matches(text)) continue
                if (intro.isNotEmpty()) intro.append('\n')
                intro.append(text)
                continue
            }

            val items = buckets.getValue(section)
            if (bullet != null) {
                if (bullet.isNotEmpty()) items += bullet
            } else if (items.isEmpty()) {
                items += line
            } else {
                // a bullet wrapped over several lines in the source file is one
                // item for the player, not several
                items[items.lastIndex] = items[items.lastIndex] + " " + line
            }
        }

        val sorted = order.sortedWith(
            compareBy({ KNOWN.indexOf(it).let { i -> if (i < 0) KNOWN.size else i } }, { order.indexOf(it) }),
        )
        val sections = sorted
            .map { ReleaseNotes.Section(it, buckets.getValue(it).toList()) }
            .filter { it.items.isNotEmpty() }

        return ReleaseNotes(intro.toString(), sections)
    }

    private fun canonical(title: String): String =
        KNOWN.firstOrNull { it.equals(title, ignoreCase = true) } ?: title
}

data class WhatsNewDecision(val show: Boolean, val seenToStore: Long)

object WhatsNewGate {

    /**
     * Shown once per version, and only to somebody who was already running an
     * older build: on a first install there is nothing "new" to report yet. The
     * stamp is written either way, so the window does not surface later.
     */
    fun evaluate(
        currentVersionCode: Long,
        seenVersionCode: Long,
        notes: ReleaseNotes,
    ): WhatsNewDecision {
        val show = seenVersionCode > 0 &&
            currentVersionCode > seenVersionCode &&
            notes.hasContent
        return WhatsNewDecision(show, maxOf(currentVersionCode, seenVersionCode))
    }
}

class UpdateClient(
    private val layout: GameLayout,
    private val downloader: Downloader,
    private val manifestUrl: String = UPDATE_MANIFEST_URL,
) {

    suspend fun manifest(): UpdateManifest {
        if (!manifestUrl.startsWith("http://") && !manifestUrl.startsWith("https://")) {
            val local = File(manifestUrl.removePrefix("file://"))
            if (!local.isFile) throw IOException("файл манифеста не найден: ${local.absolutePath}")
            return decode(local.readText())
        }
        val cached = File(layout.caches, CACHED_MANIFEST)
        val body = try {
            downloader.text(manifestUrl).also {
                cached.parentFile?.mkdirs()
                cached.writeText(it)
            }
        } catch (error: IOException) {
            if (!cached.isFile) throw error
            cached.readText()
        }
        return decode(body)
    }

    suspend fun check(currentVersionCode: Long, deviceSdk: Int = 0): UpdateVerdict =
        UpdateCheck.decide(manifest(), currentVersionCode, deviceSdk)

    fun apkFile(release: UpdateRelease): File =
        File(File(layout.caches, UPDATES_DIR), "millida-${release.versionCode}.apk")

    /**
     * The second boundary: the file is checked again before anything is handed
     * to the package installer, because the one that saved it was a different
     * run of a different version.
     */
    fun verify(release: UpdateRelease, file: File = apkFile(release)): Boolean {
        if (release.sha256.isBlank() || !file.isFile) return false
        return Hashing.digest(file, "SHA-256").equals(release.sha256, ignoreCase = true)
    }

    /**
     * The package is written under a temporary name and only takes the final one
     * once its digest matched, so a half-finished file can never be handed to the
     * package installer. The digest is verified on both sides of the rename: the
     * downloader checks what it wrote, and this checks what is about to be run.
     */
    suspend fun download(
        release: UpdateRelease,
        onProgress: (TransferProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (release.sha256.isBlank()) {
            throw IOException("обновление пришло без контрольной суммы — установка отменена")
        }
        // A plain-http package is rewritten on the way and is refused here as
        // well as in `decide`. Everything that is not http(s) is the local-file
        // form of a local channel file, copied here because the downloader
        // takes network addresses only; a channel manifest from the server can
        // never produce one, because `decide` only lets https through.
        if (release.url.startsWith("http://")) {
            throw IOException("адрес обновления должен начинаться с https://")
        }

        val target = apkFile(release)
        target.parentFile?.mkdirs()
        if (verify(release, target)) {
            onProgress(TransferProgress(1, 1, target.length(), target.length(), target.name))
            return@withContext target
        }
        if (target.exists()) target.delete()

        val staging = File(target.parentFile, target.name + ".new")
        if (staging.exists()) staging.delete()
        try {
            if (release.url.startsWith("https://")) {
                downloader.fetchAll(
                    listOf(RemoteFile(release.url, staging, release.sha256, release.size)),
                    onProgress,
                )
            } else {
                File(release.url.removePrefix("file://")).takeIf { it.isFile }?.copyTo(staging, overwrite = true)
            }
            if (!staging.isFile) throw IOException("файл обновления не скачался")
            if (!verify(release, staging)) {
                throw IOException("контрольная сумма обновления не совпала — установка отменена")
            }
            if (!staging.renameTo(target)) {
                throw IOException("не удалось сохранить файл обновления")
            }
            target
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private fun decode(body: String): UpdateManifest {
        val text = body.trim()
        if (text.isEmpty()) throw IOException("манифест обновления пуст")
        return try {
            MojangJson.decodeFromString(UpdateManifest.serializer(), text)
        } catch (error: Exception) {
            throw IOException("манифест обновления не читается", error)
        }
    }

    companion object {
        private const val CACHED_MANIFEST = "update-manifest.json"
        private const val UPDATES_DIR = "updates"
    }
}
