package net.millida.launcher.core.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.Hashing
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import net.millida.launcher.core.http.TransferProgress
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

const val RUNTIME_MANIFEST_URL = "https://launcher-storage.millida.net/mobile/runtime/manifest.json"

@Serializable
data class RuntimeManifest(
    val revision: Int = 1,
    val jre: List<JreEntry> = emptyList(),
    val bridge: List<BridgeEntry> = emptyList(),
    val renderers: List<RendererEntry> = emptyList(),
    val tools: List<ToolEntry> = emptyList(),
    /** SDL3 and the LWJGL build that talks to it, for packs that open their window through SDL3. */
    val sdl: List<SdlEntry> = emptyList(),
) {
    @Serializable
    data class SdlEntry(
        val id: String,
        val url: String,
        val sha256: String,
        val size: Long = 0,
        val sdlVersion: String = "",
        val lwjglVersion: String = "",
        val library: String = SDL_LIBRARY,
        val classpath: List<String> = emptyList(),
    )

    @Serializable
    data class JreEntry(
        val id: String,
        val major: Int,
        val abi: String,
        val url: String,
        val sha256: String,
        val size: Long = 0,
        val javaHome: String = "",
    )

    @Serializable
    data class BridgeEntry(
        val id: String,
        val url: String,
        val sha256: String,
        val size: Long = 0,
        val classpath: List<String> = emptyList(),
        val lwjglVersion: String = "",
    )

    @Serializable
    data class RendererEntry(
        val id: String,
        val label: String,
        val url: String,
        val sha256: String,
        val size: Long = 0,
        val glesMajor: Int = 3,
        val glesMinor: Int = 0,
        val library: String = "",
        val eglLibrary: String = "",
        val nativeId: String = "",
        val environment: Map<String, String> = emptyMap(),
        val shaders: Boolean = false,
        /**
         * Several renderers can live in one archive and differ only in their
         * environment; they then share one unpacked folder instead of
         * downloading the same archive once per renderer.
         */
        val component: String = "",
    ) {
        val folder: String get() = component.ifEmpty { id }
    }

    @Serializable
    data class ToolEntry(
        val id: String,
        val url: String,
        val sha256: String,
        val size: Long = 0,
    )

    fun jreFor(major: Int, abi: String, maximum: Int? = null): JreEntry? =
        jre.filter { it.abi == abi && (maximum == null || it.major <= maximum) }.minByOrNull { entry ->
            if (entry.major >= major) entry.major - major else 100 + (major - entry.major)
        }
}

data class InstalledRuntime(
    val javaHome: File,
    val bridgeClasspath: List<File>,
    val renderer: RuntimeManifest.RendererEntry,
    val rendererDir: File,
    /** Which build of the window and graphics library the pack carries. */
    val lwjglVersion: String = "",
    /** Major version of the chosen Java; 0 when the runtime could not tell. */
    val javaMajor: Int = 0,
)

data class InstalledJava(val home: File, val major: Int)

const val SDL_LIBRARY = "libSDL3.so"

/** The unpacked SDL3 component: the library, its LWJGL jars and natives of the same build. */
data class InstalledSdl(
    val dir: File,
    val library: File,
    val classpath: List<File>,
    val sdlVersion: String,
    val lwjglVersion: String,
)

/**
 * The window layer of the game is replaced by ours, and a version of the game
 * built for a newer library will look for methods this one does not have. The
 * refusal is spelled out instead of letting the game die on NoSuchMethodError.
 */
object LibraryCompatibility {

    fun problem(gameVersion: String, packVersion: String): String? {
        val game = minorOf(gameVersion) ?: return null
        val pack = minorOf(packVersion) ?: return null
        if (game <= pack) return null
        return "Эта версия игры собрана под графическую библиотеку $gameVersion, " +
            "а в пакете рантайма $packVersion. Возьми версию игры до 1.21.4 " +
            "или обнови пакет рантайма."
    }

    private fun minorOf(version: String): Int? {
        val parts = version.split('.', '-')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return major * 1000 + minor
    }
}

class RuntimeInstaller(
    private val layout: GameLayout,
    private val downloader: Downloader,
    private val manifestUrl: String = RUNTIME_MANIFEST_URL,
) {
    suspend fun manifest(): RuntimeManifest {
        val cached = File(layout.caches, "runtime-manifest.json")
        if (!manifestUrl.startsWith("http://") && !manifestUrl.startsWith("https://")) {
            val local = File(manifestUrl.removePrefix("file://"))
            if (!local.isFile) throw IOException("файл манифеста не найден: ${local.absolutePath}")
            return MojangJson.decodeFromString(RuntimeManifest.serializer(), local.readText())
        }
        val body = try {
            downloader.text(manifestUrl).also {
                cached.parentFile?.mkdirs()
                cached.writeText(it)
            }
        } catch (error: IOException) {
            if (!cached.isFile) throw error
            cached.readText()
        }
        return MojangJson.decodeFromString(RuntimeManifest.serializer(), body).also { sdlListed = it.sdl.isNotEmpty() }
    }

    @Volatile
    private var sdlListed: Boolean? = null

    /**
     * Asked by the catalogue on every card, so it never touches the network:
     * the last manifest this phone saw, or an SDL3 component already unpacked.
     */
    fun sdlAvailable(): Boolean {
        sdlListed?.let { listed -> return listed || sdlInstalled() }
        val cached = File(layout.caches, "runtime-manifest.json")
        val listed = cached.isFile && runCatching {
            MojangJson.decodeFromString(RuntimeManifest.serializer(), cached.readText()).sdl.isNotEmpty()
        }.getOrDefault(false)
        sdlListed = listed
        return listed || sdlInstalled()
    }

    private fun sdlInstalled(): Boolean = installedComponents().any { it.startsWith(SDL_COMPONENT_PREFIX) }

    suspend fun installSdl(onProgress: (TransferProgress) -> Unit = {}): InstalledSdl {
        val entry = manifest().sdl.firstOrNull()
            ?: throw IOException("в манифесте рантайма нет SDL3 — сборки с окном SDL3 на телефоне пока не запускаются")
        val dir = unpackOnce(entry.id, entry.url, entry.sha256, entry.size, onProgress)
        val library = SafePath.join(dir, entry.library)
        val classpath = entry.classpath.map { SafePath.join(dir, it) }
        val missing = (classpath + library).filterNot { it.isFile }
        if (classpath.isEmpty() || missing.isNotEmpty()) {
            throw IOException("SDL3 распакован не полностью: ${missing.firstOrNull()?.name ?: "нет библиотек LWJGL"}")
        }
        return InstalledSdl(dir, library, classpath, entry.sdlVersion, entry.lwjglVersion)
    }

    /**
     * Only the Java machine, for a runtime whose window layer and renderers
     * are already on the phone and which lacks the Java a version needs.
     */
    suspend fun installJava(
        javaMajor: Int,
        abi: String,
        javaMaximum: Int? = null,
        onProgress: (TransferProgress) -> Unit = {},
    ): InstalledJava = installJava(manifest(), javaMajor, abi, javaMaximum, onProgress)

    private suspend fun installJava(
        manifest: RuntimeManifest,
        javaMajor: Int,
        abi: String,
        javaMaximum: Int?,
        onProgress: (TransferProgress) -> Unit,
    ): InstalledJava {
        val jre = manifest.jreFor(javaMajor, abi, javaMaximum)
            ?: throw IOException(
                "для этой версии игры нужна ${JavaRequirement(javaMajor, javaMaximum).describe()} под $abi, " +
                    "а в манифесте рантайма её нет"
            )
        val home = unpackOnce(jre.id, jre.url, jre.sha256, jre.size, onProgress).let { dir ->
            if (jre.javaHome.isEmpty()) dir else SafePath.join(dir, jre.javaHome)
        }
        return InstalledJava(home, jre.major)
    }

    suspend fun install(
        javaMajor: Int,
        abi: String,
        rendererId: String?,
        javaMaximum: Int? = null,
        onProgress: (TransferProgress) -> Unit = {},
    ): InstalledRuntime {
        val manifest = manifest()
        val bridge = manifest.bridge.firstOrNull()
            ?: throw IOException("в манифесте нет игрового моста")
        val renderer = rendererId?.let { id -> manifest.renderers.firstOrNull { it.id == id } }
            ?: manifest.renderers.firstOrNull()
            ?: throw IOException("в манифесте нет ни одного рендерера")

        val java = installJava(manifest, javaMajor, abi, javaMaximum, onProgress)
        val bridgeDir = unpackOnce(bridge.id, bridge.url, bridge.sha256, bridge.size, onProgress)
        val rendererDir = unpackOnce(renderer.folder, renderer.url, renderer.sha256, renderer.size, onProgress)

        val classpath = if (bridge.classpath.isEmpty()) {
            bridgeDir.listFiles { f -> f.extension == "jar" }?.sortedBy { it.name } ?: emptyList()
        } else {
            bridge.classpath.map { SafePath.join(bridgeDir, it) }
        }
        val missing = classpath.filterNot { it.isFile }
        if (missing.isNotEmpty()) throw IOException("мост распакован не полностью: ${missing.first().name}")

        return InstalledRuntime(
            javaHome = java.home,
            bridgeClasspath = classpath,
            renderer = renderer,
            rendererDir = rendererDir,
            lwjglVersion = bridge.lwjglVersion,
            javaMajor = java.major,
        )
    }

    fun installedComponents(): List<String> =
        layout.runtimes.listFiles()?.filter { File(it, STAMP).isFile }?.map { it.name } ?: emptyList()

    private suspend fun unpackOnce(
        id: String,
        url: String,
        sha256: String,
        size: Long,
        onProgress: (TransferProgress) -> Unit,
    ): File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = SafePath.join(layout.runtimes, id)
        val stamp = File(dir, STAMP)
        if (stamp.isFile && stamp.readText().trim() == sha256) return@withContext dir

        val archive = File(layout.caches, "$id.zip")
        downloader.fetchAll(listOf(RemoteFile(url, archive, null, size)), onProgress)
        val actual = Hashing.digest(archive, "SHA-256")
        if (!actual.equals(sha256, ignoreCase = true)) {
            archive.delete()
            throw IOException("подпись компонента $id не совпала — установка остановлена")
        }

        val staging = File(layout.runtimes, "$id.new")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        extract(archive, staging)
        if (dir.exists()) dir.deleteRecursively()
        if (!staging.renameTo(dir)) throw IOException("не удалось установить компонент $id")
        File(dir, STAMP).writeText(sha256)
        archive.delete()
        markExecutables(dir)
        dir
    }

    private suspend fun extract(archive: File, target: File) {
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                val destination = SafePath.join(target, entry.name)
                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { out -> zip.copyTo(out, 1 shl 16) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun markExecutables(dir: File) {
        dir.walkTopDown().forEach { file ->
            if (file.isFile && (file.extension == "so" || file.parentFile?.name == "bin")) {
                file.setExecutable(true, true)
            }
        }
    }

    companion object {
        private const val STAMP = ".millida-sha256"
        private const val SDL_COMPONENT_PREFIX = "sdl"
    }
}
