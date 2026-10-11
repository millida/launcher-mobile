package net.millida.launcher.core.mojang

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.Hashing
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import net.millida.launcher.core.runtime.SdlLaunch
import java.io.File
import java.io.IOException

const val MOJANG_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
const val MOJANG_LIBRARIES_URL = "https://libraries.minecraft.net/"
const val MOJANG_RESOURCES_URL = "https://resources.download.minecraft.net/"

data class ResolvedLibrary(
    val name: String,
    val path: String,
    val url: String,
    val sha1: String?,
    val size: Long,
)

data class ResolvedVersion(
    val id: String,
    val jarVersionId: String,
    val mainClass: String,
    val assetsId: String,
    val assetIndex: AssetIndexRef?,
    val clientJar: Artifact?,
    val libraries: List<ResolvedLibrary>,
    val gameArguments: List<String>,
    val jvmArguments: List<String>,
    val javaMajor: Int,
    /** Which build of the window and graphics library the version was made for. */
    val lwjglVersion: String,
    val logging: LoggingConfig? = null,
    val releaseTime: String = "",
    /** The version opens its window through SDL3 and not GLFW. */
    val sdlWindow: Boolean = false,
)

class VersionResolver(
    private val layout: GameLayout,
    private val downloader: Downloader,
    private val host: HostProfile = HostProfile.ANDROID_ARM64,
    private val manifestUrl: String = MOJANG_MANIFEST_URL,
) {
    private var manifestCache: VersionManifest? = null

    /**
     * The answer is parsed before it reaches the cache: a captive portal page
     * saved as a manifest would otherwise break every later start, offline too.
     */
    suspend fun manifest(refresh: Boolean = false): VersionManifest {
        manifestCache?.let { if (!refresh) return it }
        val cached = File(layout.caches, "version_manifest_v2.json")
        val fresh = try {
            val body = downloader.text(manifestUrl)
            val parsed = MojangJson.decodeFromString(VersionManifest.serializer(), body)
            cached.atomicWriteText(body)
            parsed
        } catch (error: Exception) {
            if (!cached.isFile) throw asIo(error, "не удалось получить список версий")
            try {
                MojangJson.decodeFromString(VersionManifest.serializer(), cached.readText())
            } catch (broken: Exception) {
                cached.delete()
                throw asIo(broken, "сохранённый список версий повреждён — нужен интернет")
            }
        }
        return fresh.also { manifestCache = it }
    }

    suspend fun ensureVersionJson(id: String): File {
        val target = layout.versionJson(id)
        val entry = manifest().byId(id)
        if (target.isFile) {
            val expected = entry?.sha1
            if (expected == null || Hashing.matches(target, expected)) return target
            target.delete()
        }
        val resolved = entry ?: manifest(refresh = true).byId(id)
            ?: throw IOException("версия $id не найдена в манифесте")
        downloader.fetch(RemoteFile(resolved.url, target, resolved.sha1))
        return target
    }

    fun readLocal(id: String): VersionMeta {
        val file = layout.versionJson(id)
        if (!file.isFile) throw IOException("нет описания версии $id")
        return try {
            MojangJson.decodeFromString(VersionMeta.serializer(), file.readText())
        } catch (error: Exception) {
            file.delete()
            throw asIo(error, "описание версии $id повреждено — оно скачается заново")
        }
    }

    suspend fun resolve(id: String): ResolvedVersion {
        val chain = ArrayList<VersionMeta>()
        var current: String? = id
        val seen = HashSet<String>()
        while (current != null) {
            if (!seen.add(current)) throw IOException("цикл наследования версий на $current")
            ensureVersionJson(current)
            val meta = readLocal(current)
            chain.add(meta)
            current = meta.inheritsFrom
        }
        return merge(chain)
    }

    private fun merge(chain: List<VersionMeta>): ResolvedVersion {
        val root = chain.last()
        val leaf = chain.first()
        val parents = chain.asReversed()

        var lwjglVersion = ""
        val libraries = LinkedHashMap<String, ResolvedLibrary>()
        for (meta in parents) {
            for (library in meta.libraries) {
                if (!Rules.allowed(library.rules, host)) continue
                // Old format: one entry with a map of per-system native bundles.
                if (library.natives.isNotEmpty()) continue
                // New format (26.x): natives are separate entries carrying a
                // classifier. They hold desktop shared libraries, which are of no
                // use on a phone and used to overwrite the class jar.
                if (Maven.classifierOf(library.name).startsWith("natives")) continue
                if (Maven.groupArtifact(library.name).startsWith("org.lwjgl")) {
                    lwjglVersion = Maven.versionOf(library.name).ifEmpty { lwjglVersion }
                }
                if (isDesktopOnly(library.name)) continue
                val resolved = resolveLibrary(library) ?: continue
                libraries[Maven.coordinateWithoutVersion(library.name)] = resolved
            }
        }

        val gameArgs = ArrayList<String>()
        val jvmArgs = ArrayList<String>()
        for (meta in parents) {
            val arguments = meta.arguments
            if (arguments is JsonObject) {
                (arguments["jvm"] as? JsonArray)?.let { jvmArgs += flattenArguments(it) }
                (arguments["game"] as? JsonArray)?.let { gameArgs += flattenArguments(it) }
            } else if (meta.minecraftArguments != null) {
                gameArgs += meta.minecraftArguments.split(' ').filter { it.isNotBlank() }
            }
        }

        return ResolvedVersion(
            id = leaf.id,
            jarVersionId = root.id,
            mainClass = leaf.mainClass,
            assetsId = parents.mapNotNull { it.assets }.lastOrNull() ?: root.assets ?: "legacy",
            assetIndex = parents.mapNotNull { it.assetIndex }.lastOrNull(),
            clientJar = parents.mapNotNull { it.downloads["client"] }.lastOrNull(),
            libraries = libraries.values.toList(),
            gameArguments = gameArgs,
            jvmArguments = jvmArgs,
            javaMajor = parents.mapNotNull { it.javaVersion?.majorVersion }.lastOrNull() ?: 8,
            lwjglVersion = lwjglVersion,
            logging = parents.mapNotNull { it.logging["client"] }.lastOrNull { it.file != null },
            releaseTime = root.releaseTime,
            sdlWindow = SdlLaunch.versionNeedsSdl(parents.flatMap { meta -> meta.libraries.map { it.name } }),
        )
    }

    private fun flattenArguments(array: JsonArray): List<String> {
        val out = ArrayList<String>()
        for (element in array) {
            when (element) {
                is JsonPrimitive -> out += element.content
                is JsonObject -> {
                    val rules = element["rules"]?.jsonArray?.map {
                        MojangJson.decodeFromJsonElement(Rule.serializer(), it)
                    } ?: emptyList()
                    if (!Rules.allowed(rules, host)) continue
                    when (val value = element["value"]) {
                        is JsonPrimitive -> out += value.content
                        is JsonArray -> value.forEach { out += it.jsonPrimitive.content }
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    private fun resolveLibrary(library: Library): ResolvedLibrary? {
        val artifact = library.downloads?.artifact
        val path = artifact?.path ?: runCatching { Maven.pathOf(library.name) }.getOrNull() ?: return null
        val url = when {
            !artifact?.url.isNullOrEmpty() -> artifact.url
            !library.url.isNullOrEmpty() -> library.url.trimEnd('/') + "/" + path
            else -> MOJANG_LIBRARIES_URL + path
        }
        if (url.isEmpty()) return null
        return ResolvedLibrary(library.name, path, url, artifact?.sha1, artifact?.size ?: 0)
    }

    private fun asIo(error: Throwable, message: String): IOException =
        if (error is IOException) error else IOException(message, error)

    companion object {
        /**
         * Legacy desktop-only libraries. The whole `org.lwjgl` family is handled
         * separately: the phone brings its own build of it, and mixing two
         * versions of the same library on one class path is what makes it report
         * "incompatible Java and native library versions".
         */
        private val DESKTOP_ONLY = setOf(
            "org.lwjgl.lwjgl:lwjgl",
            "org.lwjgl.lwjgl:lwjgl_util",
            "net.java.jinput:jinput",
            "net.java.jinput:jinput-platform",
            "net.java.jutils:jutils",
            "ca.weblite:java-objc-bridge",
        )

        fun isDesktopOnly(name: String): Boolean {
            val coordinate = Maven.groupArtifact(name)
            if (coordinate.startsWith("org.lwjgl:")) return true
            return coordinate in DESKTOP_ONLY
        }
    }
}
