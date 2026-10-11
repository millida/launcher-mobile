package net.millida.launcher.core.loader

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.Hashing
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.atomicCopyFrom
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.HttpError
import net.millida.launcher.core.http.RemoteFile
import net.millida.launcher.core.http.TransferProgress
import net.millida.launcher.core.launch.LaunchRequest
import net.millida.launcher.core.launch.LaunchSpec
import net.millida.launcher.core.launch.LaunchSpecBuilder
import net.millida.launcher.core.mojang.Artifact
import net.millida.launcher.core.mojang.MOJANG_LIBRARIES_URL
import net.millida.launcher.core.mojang.Maven
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.mojang.VersionResolver
import net.millida.launcher.core.packs.PhoneFit
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.jar.JarFile
import java.util.zip.ZipFile

const val FORGE_MAVEN_BASE = "https://maven.minecraftforge.net/net/minecraftforge/forge"
const val FORGE_METADATA_URL = "https://files.minecraftforge.net/net/minecraftforge/forge/maven-metadata.json"
const val FORGE_PROMOTIONS_URL = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
const val FORGE_MAVEN_ROOT = "https://maven.minecraftforge.net/"

/**
 * The loader hosts are the first thing a mobile network drops: the connection to
 * them dies before the first byte, and the install never starts. The same files
 * come through our API, so every loader request keeps a second address. The
 * direct one is still tried first, so the traffic does not go through us without
 * a reason, and a plain "not found" is never retried - it is an answer, not a
 * failure.
 */
class LoaderMirror(
    private val downloader: Downloader,
    private val mirrorBase: String = "$MILLIDA_API/launcher/dl",
) {
    fun mirrored(url: String): String =
        if (url.startsWith(mirrorBase)) url else "$mirrorBase?url=" + URLEncoder.encode(url, "UTF-8")

    suspend fun text(url: String): String = try {
        downloader.text(url)
    } catch (absent: HttpError) {
        if (absent.isAbsent()) throw absent
        downloader.text(mirrored(url))
    } catch (offline: IOException) {
        downloader.text(mirrored(url))
    }

    suspend fun fetch(file: RemoteFile) {
        try {
            downloader.fetch(file)
        } catch (absent: HttpError) {
            if (absent.isAbsent()) throw absent
            downloader.fetch(file.copy(url = mirrored(file.url)))
        } catch (offline: IOException) {
            downloader.fetch(file.copy(url = mirrored(file.url)))
        }
    }

    suspend fun fetchAll(files: List<RemoteFile>, onProgress: (TransferProgress) -> Unit = {}) {
        try {
            downloader.fetchAll(files, onProgress)
        } catch (offline: IOException) {
            downloader.fetchAll(files.map { it.copy(url = mirrored(it.url)) }, onProgress)
        }
    }
}

private fun HttpError.isAbsent(): Boolean = code == 404 || code == 410

/** One installer file: its address and the profile name it lays out. */
data class ForgeInstallerRef(val url: String, val versionId: String)

/**
 * Where the builds of one loader of the Forge family are published. Forge and
 * NeoForge differ only in the lists they answer with, so the install itself has
 * one implementation.
 */
interface ForgeVersionSource {
    val kind: LoaderKind

    /** Builds for this Minecraft version, newest first. */
    suspend fun builds(gameVersion: String): List<LoaderVersion>

    /** The build the loader itself would pick, or null when there is none. */
    suspend fun recommended(gameVersion: String): String?

    /** Installer addresses to try, in order. */
    fun installers(gameVersion: String, build: String): List<ForgeInstallerRef>
}

/**
 * Forge lists builds as "<mc>-<build>", older ones with a trailing "-<mc>"
 * ("1.7.10-10.13.4.1614-1.7.10"). Oldest first in the source, newest first out.
 */
fun forgeBuilds(metadataJson: String, gameVersion: String): List<String> {
    val all = MojangJson.parseToJsonElement(metadataJson) as? JsonObject ?: return emptyList()
    val entries = (all[gameVersion] as? JsonArray) ?: return emptyList()
    return entries.mapNotNull { it.asText() }
        .mapNotNull { it.removePrefixOrNull("$gameVersion-") }
        .map { it.removeSuffix("-$gameVersion") }
        .asReversed()
}

/** The promoted build for this Minecraft version: recommended first, then latest. */
fun forgePromoted(promotionsJson: String, gameVersion: String): String? {
    val promos = (MojangJson.parseToJsonElement(promotionsJson) as? JsonObject)
        ?.get("promos") as? JsonObject ?: return null
    return promos["$gameVersion-recommended"]?.asText()
        ?: promos["$gameVersion-latest"]?.asText()
}

/**
 * Forge published its installer under two names: `forge-<mc>-<build>` and, up to
 * MC 1.9.4, `forge-<mc>-<build>-<mc>`. Only one of them exists per build, so
 * both are offered and the missing one is skipped on its 404.
 */
fun forgeInstallers(gameVersion: String, build: String, mavenBase: String = FORGE_MAVEN_BASE): List<ForgeInstallerRef> {
    val full = "$gameVersion-$build"
    val versionId = "$gameVersion-${LoaderKind.FORGE.id}-$build"
    return listOf(
        ForgeInstallerRef("$mavenBase/$full/forge-$full-installer.jar", versionId),
        ForgeInstallerRef("$mavenBase/$full-$gameVersion/forge-$full-$gameVersion-installer.jar", versionId),
    )
}

class ForgeVersions(
    private val mirror: LoaderMirror,
    private val metadataUrl: String = FORGE_METADATA_URL,
    private val promotionsUrl: String = FORGE_PROMOTIONS_URL,
    private val mavenBase: String = FORGE_MAVEN_BASE,
) : ForgeVersionSource {
    override val kind: LoaderKind = LoaderKind.FORGE

    // Forge publishes only releases in this list, so nothing in it is a prerelease.
    override suspend fun builds(gameVersion: String): List<LoaderVersion> =
        forgeBuilds(mirror.text(metadataUrl), gameVersion).map { LoaderVersion(it, stable = true) }

    override suspend fun recommended(gameVersion: String): String? =
        forgePromoted(mirror.text(promotionsUrl), gameVersion)
            ?: builds(gameVersion).firstOrNull()?.version

    override fun installers(gameVersion: String, build: String): List<ForgeInstallerRef> =
        forgeInstallers(gameVersion, build, mavenBase)
}

/** One step the loader's own installer still has to run on a Java machine. */
data class ForgeProcessorJob(
    val jar: File,
    val mainClass: String,
    val classpath: List<File>,
    val args: List<String>,
    val outputs: Map<String, String>,
)

data class ForgeProcessorPlan(val versionId: String, val jobs: List<ForgeProcessorJob>)

/**
 * Runs the installer's patch steps. There is no implementation inside the core
 * module on purpose: the steps are plain Java programs, and only the layer that
 * owns the imported Java runtime can start one.
 */
fun interface ForgeProcessorRunner {
    suspend fun run(plan: ForgeProcessorPlan, onStep: (done: Int, total: Int) -> Unit)
}

/** The vanilla files the patch steps read: the client jar and, since 1.17, its mappings. */
interface ForgeGameFiles {
    suspend fun clientJar(gameVersion: String): File

    suspend fun clientMappings(gameVersion: String): Artifact?
}

sealed interface ForgeInstallStep {
    data object Installer : ForgeInstallStep
    data class Libraries(val done: Int, val total: Int) : ForgeInstallStep
    data object Client : ForgeInstallStep
    data class Processors(val done: Int, val total: Int) : ForgeInstallStep
}

/**
 * Reading of `install_profile.json`. Kept as free functions because the same
 * shape is written by every Forge and NeoForge installer since the 1.13 spec,
 * and the values in it decide which files the patch steps read and write.
 * The token rules follow the installer's own `Util.replaceTokens`, so a profile
 * reads here exactly as it reads on a computer.
 */
object ForgeInstallProfile {

    fun isLegacy(profile: JsonObject): Boolean = profile["versionInfo"] is JsonObject

    /**
     * `data` values are of three kinds: `[maven]` coordinates, `'literals'` and
     * everything else, which is a path inside the installer jar.
     */
    fun dataValues(
        data: JsonObject?,
        side: String,
        library: (String) -> String,
        entry: (String) -> String,
    ): Map<String, String> {
        if (data == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((key, value) in data) {
            val raw = (value as? JsonObject)?.get(side)?.asText() ?: value.asText() ?: continue
            if (raw.isEmpty()) continue
            out[key] = when {
                raw.isWrappedIn('[', ']') -> library(raw.substring(1, raw.length - 1))
                raw.isWrappedIn('\'', '\'') -> raw.substring(1, raw.length - 1)
                else -> entry(raw)
            }
        }
        return out
    }

    /**
     * `{TOKEN}` anywhere in the string is a value, `'text'` is taken verbatim and
     * a backslash escapes the next character. An unknown token is not passed
     * through: a step that receives the literal `{MC_SRG}` writes a file under
     * that name and the next step then reads a file that never appears.
     */
    fun replaceTokens(value: String, values: Map<String, String>): String {
        val out = StringBuilder()
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                ch == '\\' -> {
                    if (index == value.length - 1) throw IOException("в профиле установщика обрыв после \\: $value")
                    out.append(value[index + 1])
                    index += 2
                }
                ch == '{' || ch == '\'' -> {
                    val close = if (ch == '{') '}' else '\''
                    val key = StringBuilder()
                    var cursor = index + 1
                    while (true) {
                        if (cursor >= value.length) throw IOException("в профиле установщика не закрыт $ch: $value")
                        val next = value[cursor]
                        if (next == '\\') {
                            if (cursor == value.length - 1) throw IOException("в профиле установщика обрыв после \\: $value")
                            key.append(value[cursor + 1])
                            cursor += 2
                            continue
                        }
                        if (next == close) break
                        key.append(next)
                        cursor += 1
                    }
                    if (ch == '\'') {
                        out.append(key)
                    } else {
                        out.append(values[key.toString()] ?: throw IOException("в профиле установщика нет значения {$key}"))
                    }
                    index = cursor + 1
                }
                else -> {
                    out.append(ch)
                    index += 1
                }
            }
        }
        return out.toString()
    }

    fun resolveArgument(raw: String, values: Map<String, String>, library: (String) -> String): String =
        if (raw.isWrappedIn('[', ']')) library(raw.substring(1, raw.length - 1)) else replaceTokens(raw, values)

    fun resolveArguments(
        args: List<String>,
        values: Map<String, String>,
        library: (String) -> String,
    ): List<String> = args.map { resolveArgument(it, values, library) }

    /**
     * Since 1.17 one step downloads the official mappings from Mojang itself.
     * The launcher fetches the same file through its own mirror instead: the
     * Java machine on a phone has no fallback when a mobile network drops the
     * host. Returns the output path when the step is that download.
     */
    fun mappingsDownloadTarget(args: List<String>): String? {
        val task = args.indexOf("--task")
        if (task < 0 || args.getOrNull(task + 1) != "DOWNLOAD_MOJMAPS") return null
        val side = args.indexOf("--side")
        if (side >= 0 && args.getOrNull(side + 1) != "client") return null
        val output = args.indexOf("--output")
        return args.getOrNull(output + 1)?.takeIf { output >= 0 }
    }

    private fun String.isWrappedIn(open: Char, close: Char): Boolean =
        length >= 2 && first() == open && last() == close
}

/**
 * The profile of a Forge installer written before the 1.13 spec: `install`
 * names the universal jar packed inside the installer, `versionInfo` is a
 * ready version json whose libraries come from the Forge maven with a list of
 * accepted checksums, or from Mojang's library host without any.
 */
object LegacyForgeProfile {

    data class Universal(val coordinate: String, val entry: String)

    data class LegacyLibrary(val name: String, val path: String, val url: String, val checksums: List<String>)

    private const val RETIRED_FORGE_MAVEN = "files.minecraftforge.net/maven"

    fun universal(profile: JsonObject): Universal? {
        val install = profile["install"] as? JsonObject ?: return null
        val coordinate = install["path"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val entry = install["filePath"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
        return Universal(coordinate, entry.removePrefix("/"))
    }

    /**
     * Every listed library is needed by the client: the official launcher reads
     * this list as a version json and ignores the installer's `clientreq`
     * flags. Desktop-only libraries are left out exactly as for any version.
     */
    fun libraries(versionInfo: JsonObject, universalCoordinate: String): List<LegacyLibrary> =
        (versionInfo["libraries"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val library = raw as? JsonObject ?: return@mapNotNull null
            val name = library["name"]?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (name == universalCoordinate) return@mapNotNull null
            if (library["natives"] != null || VersionResolver.isDesktopOnly(name)) return@mapNotNull null
            val path = Maven.pathOf(name)
            LegacyLibrary(
                name = name,
                path = path,
                url = repository(library["url"]?.asText()) + path,
                checksums = (library["checksums"] as? JsonArray).orEmpty()
                    .mapNotNull { it.asText()?.lowercase() }
                    .filter { it.isSha1() },
            )
        }

    /**
     * Old profiles name the retired Forge host over plain http. A checksum read
     * from the same host over http would protect nothing, so every address is
     * taken over https and the retired host is replaced by the current one.
     */
    fun repository(declared: String?): String {
        val base = declared?.trim().orEmpty()
        if (base.isEmpty()) return MOJANG_LIBRARIES_URL
        val secure = if (base.startsWith("http://")) "https://" + base.removePrefix("http://") else base
        val current = if (secure.removePrefix("https://").trimEnd('/') == RETIRED_FORGE_MAVEN) FORGE_MAVEN_ROOT else secure
        return current.trimEnd('/') + "/"
    }

    /** The maven copy of the universal jar, for when the file has to be fetched again. */
    fun universalUrl(coordinate: String): String =
        FORGE_MAVEN_ROOT + Maven.pathOf(coordinate).removeSuffix(".jar") + "-universal.jar"

    fun versionJson(
        versionInfo: JsonObject,
        versionId: String,
        gameVersion: String,
        artifacts: Map<String, Artifact>,
    ): JsonObject {
        val libraries = (versionInfo["libraries"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val name = (raw as? JsonObject)?.get("name")?.asText() ?: return@mapNotNull null
            val artifact = artifacts[name] ?: return@mapNotNull null
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put(
                    "downloads",
                    buildJsonObject {
                        put(
                            "artifact",
                            buildJsonObject {
                                put("path", JsonPrimitive(artifact.path))
                                put("url", JsonPrimitive(artifact.url))
                                artifact.sha1?.let { put("sha1", JsonPrimitive(it)) }
                                put("size", JsonPrimitive(artifact.size))
                            },
                        )
                    },
                )
            }
        }
        return buildJsonObject {
            versionInfo.forEach { (key, value) ->
                if (key !in REPLACED_KEYS) put(key, value)
            }
            put("id", JsonPrimitive(versionId))
            put("inheritsFrom", JsonPrimitive(versionInfo["inheritsFrom"]?.asText() ?: gameVersion))
            put("libraries", JsonArray(libraries))
        }
    }

    private val REPLACED_KEYS = setOf("libraries", "id", "inheritsFrom", "jar")
}

/**
 * Forge 1.7.10 to 1.12.2 draws its loading screen from a second thread through
 * a shared graphics context. The translation layers on a phone do not give
 * one reliably, and the game then stops on that screen; the mobile launchers
 * this runtime comes from switch the screen off the same way.
 */
object ForgeSplash {
    private val ENABLED_LINE = Regex("(?m)^\\s*enabled\\s*=.*$")

    fun patched(existing: String?): String? {
        if (existing == null) return "enabled=false\n"
        val line = ENABLED_LINE.find(existing)?.value
            ?: return existing.trimEnd() + (if (existing.isBlank()) "" else "\n") + "enabled=false\n"
        if (line.substringAfter('=').trim().equals("false", ignoreCase = true)) return null
        return existing.replace(ENABLED_LINE, "enabled=false")
    }

    fun disable(gameDir: File) {
        val file = File(File(gameDir, "config"), "splash.properties")
        val next = patched(file.takeIf { it.isFile }?.readText()) ?: return
        file.parentFile?.mkdirs()
        file.atomicWriteText(next)
    }
}

/** Minecraft before 1.13 wants the pre-1.13 Forge, which needs Java 8 and the old window library. */
fun forgeGameUnsupported(gameVersion: String): Boolean {
    val parts = gameVersion.trim().split('.')
    if (parts.size < 2 || parts[0] != "1") return false
    val minor = parts[1].takeWhile { it.isDigit() }.toIntOrNull() ?: return false
    return minor < 13
}

/**
 * Installs Forge and NeoForge. Both ship one installer jar per build carrying the
 * version json, the list of libraries and patch steps that build the modified
 * client jar.
 *
 * What the launcher does itself: picks the build, downloads the installer with
 * the checksum published next to it, unpacks the profile, downloads the
 * libraries and writes the version json. The patch steps are handed to
 * [ForgeProcessorRunner]; without one the install stops before anything is
 * marked as ready, because a half-patched profile looks installed and never
 * repairs itself.
 */
class ForgeFamilyInstaller(
    private val layout: GameLayout,
    private val downloader: Downloader,
    private val source: ForgeVersionSource,
    private val mirror: LoaderMirror = LoaderMirror(downloader),
    private val processors: ForgeProcessorRunner? = null,
    private val game: ForgeGameFiles? = null,
    /** Forge before 1.13 is installed only with the player's beta switch on, read at each install. */
    private val legacyForge: () -> Boolean = PhoneFit::legacyRuntime,
) : LoaderInstaller {

    override val kind: LoaderKind = source.kind

    override suspend fun versions(gameVersion: String): List<LoaderVersion> = source.builds(gameVersion)

    suspend fun recommended(gameVersion: String): String? = source.recommended(gameVersion)

    override suspend fun install(gameVersion: String, loaderVersion: String): String =
        install(gameVersion, loaderVersion) {}

    suspend fun install(
        gameVersion: String,
        loaderVersion: String,
        onStep: (ForgeInstallStep) -> Unit,
    ): String {
        if (kind == LoaderKind.FORGE && forgeGameUnsupported(gameVersion) && !legacyForge()) {
            throw legacyUnsupported(gameVersion)
        }
        val build = loaderVersion.ifBlank {
            source.recommended(gameVersion)
                ?: throw IOException("${kind.label} для $gameVersion не найден")
        }
        val refs = source.installers(gameVersion, build)
        val absent = ArrayList<String>()
        var failure: IOException? = null
        onStep(ForgeInstallStep.Installer)
        for (ref in refs) {
            val installer = File(File(layout.caches, "loaders"), "${ref.versionId}-installer.jar")
            try {
                download(ref.url, installer)
            } catch (missing: HttpError) {
                if (!missing.isAbsent()) throw missing
                absent += ref.url.substringAfterLast('/')
                continue
            } catch (error: IOException) {
                failure = error
                continue
            }
            try {
                return apply(installer, ref, gameVersion, onStep)
            } finally {
                installer.delete()
                File(File(layout.caches, "loaders"), "${ref.versionId}-data").deleteRecursively()
            }
        }
        failure?.let { throw it }
        throw IOException(
            if (absent.isEmpty()) "${kind.label} $build для $gameVersion не найден"
            else "${kind.label} $build: такой сборки нет в репозитории загрузчика"
        )
    }

    private fun legacyUnsupported(gameVersion: String) = IOException(
        "${kind.label} для Minecraft $gameVersion на телефоне не поддерживается. Выбери 1.13 или новее. ${PhoneFit.BETA_HINT}"
    )

    /**
     * The installer is executed by a Java machine, so it is taken only with the
     * checksum maven publishes next to it.
     */
    private suspend fun download(url: String, target: File) {
        val sum = publishedSha1(url) ?: throw IOException("контрольная сумма установщика ${kind.label} нечитаема")
        if (target.isFile) target.delete()
        mirror.fetch(RemoteFile(url = url, target = target, sha1 = sum))
    }

    private suspend fun publishedSha1(url: String): String? =
        mirror.text("$url.sha1").trim().split(WHITESPACE).firstOrNull().orEmpty().lowercase().takeIf { it.isSha1() }

    private suspend fun apply(
        installer: File,
        ref: ForgeInstallerRef,
        gameVersion: String,
        onStep: (ForgeInstallStep) -> Unit,
    ): String = ZipFile(installer).use { zip ->
        val profile = zip.json("install_profile.json")
            ?: throw IOException("в установщике ${kind.label} нет install_profile.json")
        if (ForgeInstallProfile.isLegacy(profile)) {
            if (!legacyForge()) throw legacyUnsupported(gameVersion)
            return@use applyLegacy(zip, profile, ref, gameVersion, onStep)
        }
        applyModern(zip, profile, ref, gameVersion, installer, onStep)
    }

    /**
     * The universal jar comes out of the installer, which was itself taken only
     * with its published checksum. Every other library is taken with the
     * checksums the profile lists, or with the one its repository publishes
     * next to it; a library without either is not taken at all. The version
     * json is written last and carries the checksums, so a later launch checks
     * the same files again.
     */
    private suspend fun applyLegacy(
        zip: ZipFile,
        profile: JsonObject,
        ref: ForgeInstallerRef,
        gameVersion: String,
        onStep: (ForgeInstallStep) -> Unit,
    ): String {
        val info = profile["versionInfo"] as? JsonObject
            ?: throw IOException("в установщике ${kind.label} нет описания версии")
        val universal = LegacyForgeProfile.universal(profile)
            ?: throw IOException("в установщике ${kind.label} не указан основной архив загрузчика")
        val universalFile = SafePath.join(layout.libraries, Maven.pathOf(universal.coordinate))
        extract(zip, universal.entry, universalFile)

        val libraries = LegacyForgeProfile.libraries(info, universal.coordinate)
        val artifacts = LinkedHashMap<String, Artifact>()
        onStep(ForgeInstallStep.Libraries(0, libraries.size))
        libraries.forEachIndexed { index, library ->
            artifacts[library.name] = fetchLegacy(library)
            onStep(ForgeInstallStep.Libraries(index + 1, libraries.size))
        }
        artifacts[universal.coordinate] = Artifact(
            path = Maven.pathOf(universal.coordinate),
            sha1 = Hashing.sha1(universalFile),
            size = universalFile.length(),
            url = LegacyForgeProfile.universalUrl(universal.coordinate),
        )

        val version = LegacyForgeProfile.versionJson(info, ref.versionId, gameVersion, artifacts)
        layout.versionJson(ref.versionId).atomicWriteText(MojangJson.encodeToString(JsonObject.serializer(), version))
        return ref.versionId
    }

    private suspend fun fetchLegacy(library: LegacyForgeProfile.LegacyLibrary): Artifact {
        val target = SafePath.join(layout.libraries, library.path)
        val expected = library.checksums.ifEmpty {
            listOfNotNull(publishedSha1(library.url)).ifEmpty {
                throw IOException("у библиотеки ${library.name} нет контрольной суммы — без неё ${kind.label} не ставим")
            }
        }
        if (expected.none { Hashing.matches(target, it) }) {
            // Downloaded beside the old copy and swapped in only once it checks out:
            // a failed download must not leave the build without the library.
            val fresh = File(target.parentFile, "${target.name}.download")
            fresh.delete()
            try {
                mirror.fetch(RemoteFile(url = library.url, target = fresh, sha1 = expected.singleOrNull()))
                if (expected.none { Hashing.matches(fresh, it) }) {
                    throw IOException("библиотека ${library.name} скачалась с ошибкой — повтори установку")
                }
                if (!fresh.renameTo(target)) target.atomicCopyFrom(fresh)
            } finally {
                fresh.delete()
            }
        }
        val sum = expected.firstOrNull { Hashing.matches(target, it) }
        if (sum == null) {
            target.delete()
            throw IOException("библиотека ${library.name} скачалась с ошибкой — повтори установку")
        }
        return Artifact(path = library.path, sha1 = sum, size = target.length(), url = library.url)
    }

    private suspend fun applyModern(
        zip: ZipFile,
        profile: JsonObject,
        ref: ForgeInstallerRef,
        gameVersion: String,
        installer: File,
        onStep: (ForgeInstallStep) -> Unit,
    ): String {
        val jsonPath = profile["json"]?.asText() ?: "/version.json"
        val version = zip.json(jsonPath.removePrefix("/"))
            ?: throw IOException("в установщике ${kind.label} нет описания версии")

        val steps = clientSteps(profile)
        val runner = if (steps.isEmpty()) null else processors ?: throw IOException(
            "${kind.label} для $gameVersion дособирает клиент своим установщиком " +
                "(${steps.size} шагов на Java), а среда Java к установке не подключена"
        )

        val tools = libraryFiles(zip, profile["libraries"] as? JsonArray)
        val gameLibraries = libraryFiles(zip, version["libraries"] as? JsonArray)
        fetchLibraries(tools + gameLibraries, onStep)
        unpackLibraries(zip, tools + gameLibraries)

        if (runner != null) {
            onStep(ForgeInstallStep.Client)
            val client = game?.clientJar(gameVersion) ?: layout.versionJar(gameVersion)
            if (!client.isFile) throw IOException("нет клиента Minecraft $gameVersion — без него ${kind.label} не собрать")
            val trusted = tools.mapNotNull { tool -> tool.remote.sha1?.let { tool.remote.target.canonicalPath to it } }.toMap()
            val plan = plan(zip, profile, steps, ref, gameVersion, installer, client, trusted)
            val prefetched = prefetchMappings(plan, gameVersion)
            onStep(ForgeInstallStep.Processors(0, prefetched.jobs.size))
            runner.run(prefetched) { done, total -> onStep(ForgeInstallStep.Processors(done, total)) }
            checkOutputs(prefetched)
        }
        checkProduced(gameLibraries)
        return writeVersion(version, ref.versionId, gameVersion)
    }

    private fun clientSteps(profile: JsonObject): List<JsonObject> =
        (profile["processors"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val step = entry as? JsonObject ?: return@mapNotNull null
            val sides = step["sides"] as? JsonArray
            step.takeIf { sides == null || sides.mapNotNull { it.asText() }.contains("client") }
        }

    /**
     * The steps are read out of the profile before any of them runs, so a profile
     * that asks for something the launcher cannot provide fails while nothing is
     * written yet. Every program a step runs has to be a library of the profile
     * with a checksum, and the file on disk has to match it right now.
     */
    private fun plan(
        zip: ZipFile,
        profile: JsonObject,
        steps: List<JsonObject>,
        ref: ForgeInstallerRef,
        gameVersion: String,
        installer: File,
        client: File,
        trusted: Map<String, String>,
    ): ForgeProcessorPlan {
        val work = File(File(layout.caches, "loaders"), "${ref.versionId}-data")
        val library: (String) -> String = { SafePath.join(layout.libraries, Maven.pathOf(it)).absolutePath }
        val entry: (String) -> String = { path ->
            val inside = path.removePrefix("/")
            val target = SafePath.join(work, inside)
            extract(zip, inside, target)
            target.absolutePath
        }
        val values = HashMap<String, String>()
        values += ForgeInstallProfile.dataValues(profile["data"] as? JsonObject, "client", library, entry)
        values["SIDE"] = "client"
        values["ROOT"] = layout.root.absolutePath
        values["INSTALLER"] = installer.absolutePath
        values["LIBRARY_DIR"] = layout.libraries.absolutePath
        values["MINECRAFT_JAR"] = client.absolutePath
        values["MINECRAFT_VERSION"] = gameVersion

        val jobs = steps.map { step ->
            val coordinate = step["jar"]?.asText() ?: throw IOException("в шаге установщика нет программы")
            val jar = SafePath.join(layout.libraries, Maven.pathOf(coordinate))
            val classpath = (step["classpath"] as? JsonArray).orEmpty().mapNotNull { it.asText() }
                .map { SafePath.join(layout.libraries, Maven.pathOf(it)) }
            (listOf(jar) + classpath).forEach { requireTrusted(it, trusted) }
            ForgeProcessorJob(
                jar = jar,
                mainClass = mainClassOf(jar) ?: throw IOException("у программы $coordinate нет Main-Class"),
                classpath = classpath,
                args = ForgeInstallProfile.resolveArguments(
                    (step["args"] as? JsonArray).orEmpty().mapNotNull { it.asText() },
                    values,
                    library,
                ),
                outputs = (step["outputs"] as? JsonObject)?.mapNotNull { (key, value) ->
                    val want = value.asText() ?: return@mapNotNull null
                    ForgeInstallProfile.resolveArgument(key, values, library) to
                        ForgeInstallProfile.replaceTokens(want, values)
                }?.toMap().orEmpty(),
            )
        }
        return ForgeProcessorPlan(ref.versionId, jobs)
    }

    private fun requireTrusted(file: File, trusted: Map<String, String>) {
        val sum = trusted[file.canonicalPath] ?: throw IOException(
            "у программы установщика ${file.name} нет контрольной суммы — такой установщик не запускаем"
        )
        if (!Hashing.matches(file, sum)) {
            file.delete()
            throw IOException("программа установщика ${file.name} повреждена — повтори установку")
        }
    }

    private suspend fun prefetchMappings(plan: ForgeProcessorPlan, gameVersion: String): ForgeProcessorPlan {
        val files = game ?: return plan
        val kept = ArrayList<ForgeProcessorJob>()
        for (job in plan.jobs) {
            val target = ForgeInstallProfile.mappingsDownloadTarget(job.args)
            val mappings = target?.let { files.clientMappings(gameVersion) }
            if (target == null || mappings == null || mappings.url.isEmpty() || mappings.sha1?.isSha1() != true) {
                kept += job
                continue
            }
            val file = File(target)
            if (!SafePath.isInside(layout.root, file)) throw IOException("шаг установщика пишет за пределы папки игры")
            mirror.fetch(RemoteFile(url = mappings.url, target = file, sha1 = mappings.sha1, size = mappings.size))
        }
        return plan.copy(jobs = kept)
    }

    private fun checkOutputs(plan: ForgeProcessorPlan) {
        for (job in plan.jobs) {
            for ((path, sum) in job.outputs) {
                val file = File(path)
                if (!ForgeProcessorOutput.accepted(file, sum.takeIf { it.isSha1() })) {
                    file.delete()
                    throw IOException("${kind.label}: шаг установщика собрал ${file.name} с ошибкой — повтори установку")
                }
            }
        }
    }

    private fun checkProduced(libraries: List<LibraryFile>) {
        val missing = libraries.filter { it.produced && !it.remote.target.isFile }
        if (missing.isNotEmpty()) {
            throw IOException(
                "${kind.label} не собрал ${missing.size} файл(ов): ${missing.first().remote.target.name} — повтори установку"
            )
        }
    }

    private class LibraryFile(val remote: RemoteFile, val packed: String?, val produced: Boolean)

    /**
     * A library without an address is either packed into the installer under
     * `maven/` or is what the patch steps produce.
     */
    private fun libraryFiles(zip: ZipFile, libraries: JsonArray?): List<LibraryFile> =
        libraries.orEmpty().mapNotNull { raw ->
            val library = raw as? JsonObject ?: return@mapNotNull null
            val artifact = (library["downloads"] as? JsonObject)?.get("artifact") as? JsonObject
            val name = library["name"]?.asText()
            val path = artifact?.get("path")?.asText()
                ?: name?.let { runCatching { Maven.pathOf(it) }.getOrNull() }
                ?: return@mapNotNull null
            val url = artifact?.get("url")?.asText()?.takeIf { it.isNotEmpty() }
                ?: library["url"]?.asText()?.takeIf { it.isNotEmpty() }?.let { it.trimEnd('/') + "/" + path }
            val packed = "maven/$path".takeIf { url == null && zip.getEntry(it) != null }
            LibraryFile(
                remote = RemoteFile(
                    url = url.orEmpty(),
                    target = SafePath.join(layout.libraries, path),
                    sha1 = artifact?.get("sha1")?.asText()?.takeIf { it.isSha1() },
                    size = artifact?.get("size")?.asLong() ?: 0,
                ),
                packed = packed,
                produced = url == null && packed == null,
            )
        }.distinctBy { it.remote.target.canonicalPath }

    private suspend fun fetchLibraries(libraries: List<LibraryFile>, onStep: (ForgeInstallStep) -> Unit) {
        val remote = libraries.filter { it.remote.url.isNotEmpty() }.map { it.remote }
        onStep(ForgeInstallStep.Libraries(0, remote.size))
        if (remote.isNotEmpty()) {
            mirror.fetchAll(remote) { progress -> onStep(ForgeInstallStep.Libraries(progress.doneFiles, remote.size)) }
            val missing = remote.filterNot { Hashing.matches(it.target, it.sha1) && it.target.length() > 0 }
            if (missing.isNotEmpty()) {
                throw IOException("не скачались библиотеки ${kind.label}: ${missing.size} шт.")
            }
        }
    }

    private fun unpackLibraries(zip: ZipFile, libraries: List<LibraryFile>) {
        for (library in libraries) {
            val packed = library.packed ?: continue
            val target = library.remote.target
            val sum = library.remote.sha1
            if (sum != null && Hashing.matches(target, sum)) continue
            extract(zip, packed, target)
            if (sum != null && !Hashing.matches(target, sum)) {
                target.delete()
                throw IOException("в установщике ${kind.label} повреждена библиотека ${target.name}")
            }
        }
    }

    /**
     * The version json is the mark that says the loader is installed, so it is
     * written after the libraries and the patch steps and never before them.
     */
    private fun writeVersion(version: JsonObject, versionId: String, gameVersion: String): String {
        val patched = buildJsonObject {
            version.forEach { (key, value) -> put(key, value) }
            put("id", JsonPrimitive(versionId))
            put("inheritsFrom", JsonPrimitive(version["inheritsFrom"]?.asText() ?: gameVersion))
        }
        layout.versionJson(versionId)
            .atomicWriteText(MojangJson.encodeToString(JsonObject.serializer(), patched))
        return versionId
    }

    private fun extract(zip: ZipFile, name: String, target: File) {
        val entry = zip.getEntry(name) ?: throw IOException("в установщике ${kind.label} нет $name")
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        try {
            zip.getInputStream(entry).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun mainClassOf(jar: File): String? =
        if (!jar.isFile) null
        else runCatching { JarFile(jar).use { it.manifest?.mainAttributes?.getValue("Main-Class") } }.getOrNull()

    private fun ZipFile.json(name: String): JsonObject? {
        val entry = getEntry(name) ?: return null
        val body = getInputStream(entry).use { it.readBytes().decodeToString() }
        return MojangJson.parseToJsonElement(body) as? JsonObject
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun forge(
            layout: GameLayout,
            downloader: Downloader,
            processors: ForgeProcessorRunner? = null,
            game: ForgeGameFiles? = null,
        ): ForgeFamilyInstaller {
            val mirror = LoaderMirror(downloader)
            return ForgeFamilyInstaller(layout, downloader, ForgeVersions(mirror), mirror, processors, game)
        }

        fun neoforge(
            layout: GameLayout,
            downloader: Downloader,
            processors: ForgeProcessorRunner? = null,
            game: ForgeGameFiles? = null,
        ): ForgeFamilyInstaller {
            val mirror = LoaderMirror(downloader)
            return ForgeFamilyInstaller(layout, downloader, NeoForgeVersions(mirror), mirror, processors, game)
        }
    }
}

/**
 * The patch plan as the processor driver inside the Java machine reads it: a
 * plain data stream, because the driver runs on bare Java 8 without any json
 * library on its class path.
 */
object ForgePlanFile {
    const val FORMAT = 1

    fun write(plan: ForgeProcessorPlan, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        DataOutputStream(temp.outputStream().buffered()).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(plan.jobs.size)
            for (job in plan.jobs) {
                out.writeUTF(job.jar.absolutePath)
                out.writeUTF(job.mainClass)
                out.writeStrings(job.classpath.map { it.absolutePath })
                out.writeStrings(job.args)
                out.writeInt(job.outputs.size)
                job.outputs.forEach { (path, sum) ->
                    out.writeUTF(path)
                    out.writeUTF(sum)
                }
            }
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun DataOutputStream.writeStrings(values: List<String>) {
        writeInt(values.size)
        values.forEach { writeUTF(it) }
    }
}

private fun String.isSha1(): Boolean = length == 40 && all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

/**
 * Launch of a Forge-family profile. It is the ordinary launch with one
 * correction: a pre-1.13 loader replaces the vanilla `minecraftArguments`
 * wholesale, while the version chain hands both strings over one after another.
 * The game then reads the vanilla `--version` and `--assetIndex` and ignores the
 * loader's, and the tweaker never sees the values it was given.
 */
object ForgeLaunch {

    private val REPEATABLE = setOf("--tweakClass")

    fun build(layout: GameLayout, request: LaunchRequest): LaunchSpec =
        LaunchSpecBuilder(layout).build(
            request.copy(
                version = request.version.copy(
                    gameArguments = mergeGameArguments(request.version.gameArguments),
                )
            )
        )

    /**
     * Keeps every flag once, at the place it first appeared, with the value the
     * loader gave it last. Flags that are meant to repeat keep all their values.
     */
    fun mergeGameArguments(raw: List<String>): List<String> {
        val order = ArrayList<String>()
        val values = HashMap<String, String>()
        val repeated = LinkedHashMap<String, MutableList<String>>()
        val bare = ArrayList<String>()
        var index = 0
        while (index < raw.size) {
            val token = raw[index]
            val next = raw.getOrNull(index + 1)
            if (token.startsWith("--") && next != null && !next.startsWith("--")) {
                if (token in REPEATABLE) {
                    val list = repeated.getOrPut(token) { ArrayList() }
                    if (list.isEmpty()) order += token
                    if (next !in list) list += next
                } else {
                    if (token !in values) order += token
                    values[token] = next
                }
                index += 2
                continue
            }
            if (token !in bare) bare += token
            index += 1
        }
        val out = ArrayList<String>()
        for (flag in order) {
            val repeats = repeated[flag]
            if (repeats != null) {
                repeats.forEach { out += listOf(flag, it) }
            } else {
                out += listOf(flag, values.getValue(flag))
            }
        }
        out += bare
        return out
    }
}

internal fun kotlinx.serialization.json.JsonElement.asText(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

internal fun kotlinx.serialization.json.JsonElement.asLong(): Long? =
    (this as? JsonPrimitive)?.content?.toLongOrNull()

private fun String.removePrefixOrNull(prefix: String): String? =
    if (startsWith(prefix)) removePrefix(prefix) else null

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

/**
 * Inputs of a patch step are checked by hash, but its output archive is
 * compressed by the system zlib, and some Android releases produce different
 * deflate bytes from the same content. An output whose hash differs is
 * accepted while it is a readable, non-empty archive.
 */
internal object ForgeProcessorOutput {
    fun accepted(file: File, sha1: String?): Boolean {
        if (Hashing.matches(file, sha1)) return true
        return sha1 != null && readableArchive(file)
    }

    fun readableArchive(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList()
                val buffer = ByteArray(1 shl 16)
                entries.filterNot { it.isDirectory }.forEach { entry ->
                    zip.getInputStream(entry).use { input -> while (input.read(buffer) >= 0) Unit }
                }
                entries.isNotEmpty()
            }
        }.getOrDefault(false)
    }
}
