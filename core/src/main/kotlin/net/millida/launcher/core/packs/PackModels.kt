package net.millida.launcher.core.packs

import kotlinx.serialization.Serializable
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.catalog.ModrinthCatalog
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

/** Where a build came from, so an update knows what to ask for. */
enum class PackSource(val id: String) {
    MODRINTH("modrinth"),
    CURSEFORGE("curseforge"),
    MILLIDA_CATALOG("millida-catalog"),
    MILLIDA_CODE("millida-code"),
    MILLIDA_MIRROR("millida-mirror"),
    FILE("file"),
}

/**
 * One file of a build, in the shape both catalogues are reduced to. A mrpack
 * entry, a CurseForge manifest line and a shared manifest line all become this,
 * so there is one download path and one set of checks.
 */
data class PackFileEntry(
    val path: String,
    val urls: List<String>,
    val sha1: String? = null,
    val size: Long = 0,
    val required: Boolean = true,
    val projectId: String = "",
    val versionId: String = "",
)

/** What a build will be once installed, before anything touches the disk. */
data class PackPlan(
    val name: String,
    val gameVersion: String,
    val loader: LoaderKind,
    val loaderVersion: String = "",
    val versionId: String = "",
    val versionName: String = "",
    val summary: String = "",
    val source: PackSource = PackSource.FILE,
    val sourceId: String = "",
    val iconUrl: String = "",
    val files: List<PackFileEntry> = emptyList(),
)

/**
 * A plan plus the folders that get copied over the build as they are: mrpack
 * overrides, a CurseForge overrides folder, or a whole Millida archive.
 * [ownLaunch] is set only for a catalogue archive that describes its own launch:
 * such a build keeps that description and needs no loader installed.
 */
data class PackContent(
    val plan: PackPlan,
    val trees: List<File> = emptyList(),
    val workDir: File? = null,
    val ownLaunch: Boolean = false,
)

data class PackProgress(
    val stage: String,
    val done: Int = 0,
    val total: Int = 0,
    val file: String = "",
)

/**
 * Addresses a pack may be served from. A digest proves the bytes, it cannot stop
 * the launcher from being aimed at somebody's intranet, so the host is decided
 * here and not by whatever answered the API call.
 */
object PackHosts {
    val DEFAULT: Set<String> = ModrinthCatalog.ALLOWED_HOSTS + setOf(
        "garage.millida.net",
        "cdn.millida.net",
    )

    fun hostOf(url: String): Pair<String, String>? {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        val host = parsed.host?.lowercase() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        return scheme to host
    }
}

/**
 * JSON from a server or an archive is outside data: a wrong shape surfaces as
 * IllegalArgumentException (SerializationException included), while every
 * caller of the pack code handles IOException only.
 */
@Throws(IOException::class)
internal inline fun <T> packJson(message: String, block: () -> T): T = try {
    block()
} catch (malformed: IllegalArgumentException) {
    throw IOException(message, malformed)
}

/**
 * CurseForge hides `downloadUrl` when an author blocks third-party downloads,
 * yet the file stays on its CDN under a path built from the file id. Our
 * `download-url` proxy answers 502 for such files, so the address is built
 * here; it is only used with a known sha1, which proves the bytes.
 */
object ForgeCdn {
    private val BASES = listOf("https://edge.forgecdn.net", "https://mediafilez.forgecdn.net")

    fun urls(fileId: Long, fileName: String): List<String> {
        if (fileId <= 0 || fileName.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return BASES.map { base -> "$base/files/${fileId / 1000}/${fileId % 1000}/$encoded" }
    }
}

/**
 * The decision itself, in one object the caller passes down. Production always
 * uses the default; the plain-text form exists so a test can serve files from a
 * local server without loosening what the launcher ships with.
 */
data class PackHostPolicy(
    val hosts: Set<String> = PackHosts.DEFAULT,
    val requireHttps: Boolean = true,
) {
    fun allows(url: String): Boolean {
        val (scheme, host) = PackHosts.hostOf(url) ?: return false
        if (requireHttps && scheme != "https") return false
        if (scheme != "https" && scheme != "http") return false
        return host in hosts
    }

    @Throws(IOException::class)
    fun check(url: String) {
        if (!allows(url)) {
            throw IOException("сборка ведёт на посторонний адрес: $url — установка остановлена, сообщи автору сборки")
        }
    }
}

/**
 * Loader of a build, from the dependency keys of a mrpack index or from the
 * `modLoaders` id of a CurseForge manifest. An unknown loader becomes vanilla
 * only when the pack named none at all: a pack that named one the launcher does
 * not know must say so, because a silent fallback gives the player a build where
 * not a single mod loads.
 */
object PackLoaders {
    private val BY_ID = LoaderKind.entries.associateBy { it.id }

    @Throws(IOException::class)
    fun fromDependencies(dependencies: Map<String, String>): Pair<LoaderKind, String> {
        DEPENDENCY_KEYS.forEach { (key, kind) ->
            dependencies[key]?.let { return kind to it }
        }
        return LoaderKind.VANILLA to ""
    }

    /** CurseForge writes the loader and its build in one string: `fabric-0.16.5`. */
    @Throws(IOException::class)
    fun fromId(raw: String): Pair<LoaderKind, String> {
        val value = raw.trim().lowercase()
        if (value.isEmpty() || value == "vanilla" || value == "minecraft") return LoaderKind.VANILLA to ""
        val name = value.substringBefore('-')
        val version = value.substringAfter('-', "")
        val kind = BY_ID[name]
            ?: throw IOException("сборка собрана под загрузчик «$name», которого лаунчер не знает — обнови лаунчер")
        return kind to version
    }

    private val DEPENDENCY_KEYS = listOf(
        "neoforge" to LoaderKind.NEOFORGE,
        "forge" to LoaderKind.FORGE,
        "quilt-loader" to LoaderKind.QUILT,
        "fabric-loader" to LoaderKind.FABRIC,
    )
}

/**
 * What a build remembers about the pack it was installed from. It lives next to
 * the build instead of inside profiles.json so the launcher's own profile
 * format stays untouched.
 */
@Serializable
data class PackOrigin(
    val source: String = PackSource.FILE.id,
    val id: String = "",
    val versionId: String = "",
    val versionName: String = "",
    val name: String = "",
    val installedAt: Long = 0,
    val shareCode: String = "",
    val files: List<PackOriginFile> = emptyList(),
    /** The build this record was written for; a copied folder carries another build's id. */
    val buildId: String = "",
    /** The player's own answer to «update by itself»; null leaves it to [PackEdits]. */
    val autoUpdate: Boolean? = null,
)

@Serializable
data class PackOriginFile(
    val path: String,
    val url: String = "",
    val sha1: String = "",
    val size: Long = 0,
    val projectId: String = "",
    val versionId: String = "",
)

object PackOriginStore {
    const val FILE_NAME = "millida-pack.json"

    fun read(profileDir: File): PackOrigin? {
        val file = File(profileDir, FILE_NAME)
        if (!file.isFile) return null
        return try {
            MojangJson.decodeFromString(PackOrigin.serializer(), file.readText())
        } catch (broken: Exception) {
            null
        }
    }

    fun write(profileDir: File, origin: PackOrigin) {
        File(profileDir, FILE_NAME)
            .atomicWriteText(MojangJson.encodeToString(PackOrigin.serializer(), origin))
    }

    /**
     * A file installed one at a time from the catalogue is remembered the same
     * way a pack remembers its own: without this record the launcher can see
     * the jar but cannot say where it came from, and sharing, exporting and the
     * cloud all have nothing to name.
     */
    fun record(profileDir: File, file: PackOriginFile) {
        if (file.path.isEmpty()) return
        val current = read(profileDir) ?: PackOrigin(
            source = PackSource.FILE.id,
            installedAt = System.currentTimeMillis(),
        )
        val kept = current.files.filterNot { it.path.equals(file.path, ignoreCase = true) }
        write(profileDir, current.copy(files = kept + file))
    }

    /** The record goes away with the file, or the next share offers a ghost. */
    fun forget(profileDir: File, path: String) {
        val current = read(profileDir) ?: return
        val kept = current.files.filterNot { it.path.equals(path, ignoreCase = true) }
        if (kept.size == current.files.size) return
        write(profileDir, current.copy(files = kept))
    }

    fun files(profileDir: File): List<PackOriginFile> = read(profileDir)?.files.orEmpty()
}
