package net.millida.launcher.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.MillidaAuth
import java.io.IOException

const val SYNC_PATH = "/launcher/sync"

/**
 * The desktop launcher writes exactly this number into every snapshot; a phone
 * that bumped it on its own would publish builds the computer refuses to read.
 */
const val SNAPSHOT_FORMAT = 1
const val PACK_FORMAT = 1

const val MAX_SNAPSHOT_BYTES = 1024 * 1024
const val MAX_PROFILES = 100
const val MAX_FILES = 1000
const val MAX_SUMMARY = 300

/**
 * Hosts a manifest may point at. Every file is verified by digest anyway, but a
 * digest cannot stop the launcher from being aimed at an intranet address.
 */
val FILE_HOSTS = setOf(
    "cdn.modrinth.com",
    "api.modrinth.com",
    "mediafilez.forgecdn.net",
    "edge.forgecdn.net",
    "media.forgecdn.net",
    "cdn.millida.net",
)

internal val SyncJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    // The desktop side has no serde default on formatVersion, name, game, loader
    // and url: dropping them because they equal a Kotlin default would produce a
    // snapshot the computer cannot deserialise at all.
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class PackFile(
    val path: String = "",
    val kind: String = "",
    val projectId: String = "",
    val versionId: String = "",
    val sha1: String = "",
    val size: Long = 0,
    val url: String = "",
)

@Serializable
data class ServerEntry(
    val name: String = "",
    val ip: String = "",
)

@Serializable
data class PackManifest(
    val formatVersion: Int = PACK_FORMAT,
    val name: String = "",
    val summary: String = "",
    val game: String = "",
    val loader: String = "",
    val loaderVersion: String = "",
    val icon: String = "",
    val files: List<PackFile> = emptyList(),
    val settings: JsonElement = JsonNull,
    val servers: List<ServerEntry> = emptyList(),
)

@Serializable
data class CloudProfile(
    val name: String = "",
    val group: String = "",
    val manifest: PackManifest = PackManifest(),
)

@Serializable
data class Snapshot(
    val formatVersion: Int = SNAPSHOT_FORMAT,
    val device: String = "",
    val profiles: List<CloudProfile> = emptyList(),
    val prefs: JsonElement = JsonNull,
    val themes: List<String> = emptyList(),
)

data class CloudStatus(
    val signedIn: Boolean = false,
    val hasRemote: Boolean = false,
    val updatedAt: String = "",
    val device: String = "",
    val remoteProfiles: Int = 0,
    val localProfiles: Int = 0,
    val sizeBytes: Long = 0,
    /** Builds the cloud has and this phone does not. */
    val missingHere: List<String> = emptyList(),
    /** Builds on this phone the cloud has never seen. */
    val missingThere: List<String> = emptyList(),
)

data class SkippedBuild(val name: String, val reason: String)

data class PullReport(
    val installed: List<String> = emptyList(),
    val updated: List<String> = emptyList(),
    val skipped: List<SkippedBuild> = emptyList(),
    val failed: List<String> = emptyList(),
    val prefsApplied: Int = 0,
    val themesMissing: List<String> = emptyList(),
)

/**
 * The local side of sync. The builds themselves live outside this package, so
 * restoring and describing are handed in instead of reached for.
 */
interface LocalBuilds {
    fun names(): List<String>

    /** Null means the build cannot be described and is left out of the snapshot. */
    fun describe(name: String): PackManifest?

    fun group(name: String): String = ""

    suspend fun install(name: String, manifest: PackManifest, group: String)
}

interface SyncPrefs {
    fun snapshot(): JsonObject = buildJsonObject { }

    fun apply(key: String, value: String): Boolean = false

    fun installedThemes(): List<String> = emptyList()
}

object NoPrefs : SyncPrefs

/**
 * Cloud profile: the same builds and the same settings on the phone and on the
 * computer. What travels is a description, not the files: every build is the
 * catalogue manifest the desktop launcher already shares, so a hundred builds
 * fit in a few hundred kilobytes and every mod is fetched from Modrinth or
 * CurseForge and checked by digest on arrival.
 */
class CloudSync(
    private val auth: MillidaAuth,
    private val builds: LocalBuilds,
    private val prefs: SyncPrefs = NoPrefs,
    deviceName: String = "Телефон",
) {
    private val device: String = deviceName.filter { !it.isISOControl() }.take(40)

    fun buildSnapshot(): Snapshot {
        val profiles = builds.names().take(MAX_PROFILES).mapNotNull { name ->
            // A build whose manifest cannot be built is skipped rather than
            // failing the whole sync.
            builds.describe(name)?.let { CloudProfile(name = name, group = builds.group(name), manifest = it) }
        }
        return Snapshot(
            formatVersion = SNAPSHOT_FORMAT,
            device = device,
            profiles = profiles,
            prefs = prefs.snapshot(),
            themes = prefs.installedThemes(),
        )
    }

    suspend fun status(): CloudStatus {
        val local = builds.names()
        if (!auth.signedIn()) return CloudStatus(localProfiles = local.size)
        val response = fetchRemote() ?: return CloudStatus(signedIn = true, localProfiles = local.size)
        val raw = response["snapshot"]?.takeIf { it != JsonNull }
            ?: return CloudStatus(signedIn = true, localProfiles = local.size)
        val snapshot = decode(raw)
        val names = snapshot.profiles.map { it.name }
        return CloudStatus(
            signedIn = true,
            hasRemote = true,
            updatedAt = response.text("updatedAt"),
            device = snapshot.device,
            remoteProfiles = snapshot.profiles.size,
            localProfiles = local.size,
            sizeBytes = response.text("sizeBytes").toLongOrNull() ?: 0,
            missingHere = names.filter { it !in local },
            missingThere = local.filter { it !in names },
        )
    }

    suspend fun push(): CloudStatus {
        val snapshot = buildSnapshot()
        val body = SyncJson.encodeToString(Snapshot.serializer(), snapshot)
        val size = body.toByteArray(Charsets.UTF_8).size
        if (size > MAX_SNAPSHOT_BYTES) {
            throw IOException(
                "Слепок сборок весит ${size / 1024} КБ — это больше предела в ${MAX_SNAPSHOT_BYTES / 1024} КБ. " +
                    "Убери часть сборок из синхронизации.",
            )
        }
        val payload = buildJsonObject { put("snapshot", SyncJson.parseToJsonElement(body)) }
        auth.authorizedElement(SYNC_PATH, "PUT", payload)
        return status()
    }

    /**
     * Restores builds from the cloud. A build that already exists here is left
     * alone unless it was named explicitly: pulling must never quietly overwrite
     * a build somebody has been playing on this phone.
     */
    suspend fun pull(only: List<String>? = null, applyPrefs: Boolean = false): PullReport {
        val response = auth.authorized(SYNC_PATH, "GET", null)
        val raw = response["snapshot"]?.takeIf { it != JsonNull } ?: throw IOException("В облаке ещё ничего нет")
        val snapshot = decode(raw)
        if (snapshot.formatVersion > SNAPSHOT_FORMAT) {
            throw IOException("Копию сделали в более новой версии лаунчера — обнови лаунчер")
        }
        val local = builds.names()
        val installed = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<SkippedBuild>()
        val failed = mutableListOf<String>()

        for (entry in snapshot.profiles.take(MAX_PROFILES)) {
            if (only != null && entry.name !in only) {
                skipped += SkippedBuild(entry.name, "не выбрана для загрузки")
                continue
            }
            val exists = entry.name in local
            if (exists && only == null) {
                skipped += SkippedBuild(entry.name, "уже есть на телефоне — выбери её отдельно, чтобы обновить")
                continue
            }
            val manifest = try {
                sanitiseManifest(entry.manifest)
            } catch (error: IOException) {
                failed += "${entry.name}: ${error.message}"
                continue
            }
            try {
                builds.install(entry.name, manifest, entry.group)
                if (exists) updated += entry.name else installed += entry.name
            } catch (error: Exception) {
                failed += "${entry.name}: ${error.message ?: "не установилась"}"
            }
        }

        var applied = 0
        if (applyPrefs) {
            (snapshot.prefs as? JsonObject)?.forEach { (key, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (text != null && prefs.apply(key, text)) applied++
            }
        }
        val here = prefs.installedThemes()
        return PullReport(
            installed = installed,
            updated = updated,
            skipped = skipped,
            failed = failed,
            prefsApplied = applied,
            themesMissing = snapshot.themes.filter { it !in here },
        )
    }

    suspend fun forget() {
        auth.authorizedElement(SYNC_PATH, "DELETE", null)
    }

    /**
     * Nothing uploaded yet is a normal state, not a failure. The shared client
     * reports an HTTP code inside the message, so that is what is matched here.
     */
    private suspend fun fetchRemote(): JsonObject? = try {
        auth.authorized(SYNC_PATH, "GET", null)
    } catch (error: IOException) {
        if (error.message.orEmpty().contains("404")) null else throw error
    }

    private fun decode(raw: JsonElement): Snapshot = try {
        SyncJson.decodeFromJsonElement(Snapshot.serializer(), raw)
    } catch (broken: Exception) {
        throw IOException("Облачная копия записана в неизвестном формате")
    }

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
}

fun urlAllowed(raw: String): Boolean {
    val url = try {
        java.net.URI(raw)
    } catch (broken: Exception) {
        return false
    }
    return url.scheme == "https" && url.host in FILE_HOSTS
}

/**
 * The same trimming the desktop launcher applies before installing a manifest:
 * a snapshot is data from the network even when it is your own.
 */
fun sanitiseManifest(manifest: PackManifest): PackManifest {
    if (manifest.formatVersion > PACK_FORMAT) {
        throw IOException("Сборку сделали в более новой версии лаунчера — обнови лаунчер")
    }
    if (manifest.game.isBlank()) throw IOException("В сборке не указана версия Minecraft")
    val files = manifest.files
        .take(MAX_FILES)
        .filter { file ->
            urlAllowed(file.url) &&
                file.sha1.length == 40 &&
                file.sha1.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } &&
                !file.path.contains("..")
        }
    return manifest.copy(
        name = manifest.name.filter { !it.isISOControl() }.take(64),
        summary = manifest.summary.filter { !it.isISOControl() || it == '\n' }.take(MAX_SUMMARY),
        files = files,
    )
}
