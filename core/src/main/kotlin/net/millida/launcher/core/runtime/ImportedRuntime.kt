package net.millida.launcher.core.runtime

import kotlinx.serialization.Serializable
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.SafeUnzip
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A runtime handed over as one file instead of a manifest on a server: the
 * bundle is unpacked once and remembered, so the game can be tested before the
 * distribution is up.
 */
@Serializable
data class JreSlot(
    val major: Int,
    val path: String,
)

@Serializable
data class RendererOption(
    val id: String,
    val label: String,
    val library: String,
    val eglLibrary: String = "",
    /** What the window layer itself calls this way of drawing. */
    val nativeId: String = "",
    val shaders: Boolean = false,
    val environment: Map<String, String> = emptyMap(),
)

@Serializable
data class BundleDescription(
    val name: String = "",
    val javaHome: String = "",
    val jres: List<JreSlot> = emptyList(),
    val bridge: List<String> = emptyList(),
    val rendererId: String = "local",
    val rendererLabel: String = "Рендерер из пакета",
    val rendererDir: String = "",
    val rendererLibrary: String = "",
    val eglLibrary: String = "",
    val shaders: Boolean = false,
    val lwjglVersion: String = "",
    val environment: Map<String, String> = emptyMap(),
    val renderers: List<RendererOption> = emptyList(),
)

object RuntimeImport {
    private const val FOLDER = "imported"
    private const val DESCRIPTION = "millida-runtime.json"
    private const val STAMP = ".millida-imported"
    private val UNZIP_LIMITS = SafeUnzip.Limits(
        maxEntries = 100_000,
        maxBytes = 4L * 1024 * 1024 * 1024,
        broken = { cause -> "файл рантайма повреждён ($cause) — скачай его заново" },
        tooMany = "в файле рантайма слишком много файлов — это не пакет рантайма",
        tooBig = "файл рантайма распаковывается в слишком большой объём — это не пакет рантайма",
    )

    /**
     * A build declares the Java it needs, and a runtime older than that fails
     * with an unreadable class file error deep inside the game. The nearest
     * newer runtime is picked, and the shortfall is named out loud. A ceiling
     * the pack cannot meet answers null as well: the caller then brings that
     * Java from the network next to the window layer of the pack.
     */
    fun installed(
        layout: GameLayout,
        javaMajor: Int = 0,
        rendererId: String = "",
        javaMaximum: Int? = null,
    ): InstalledRuntime? {
        val root = File(layout.runtimes, FOLDER)
        if (!File(root, STAMP).isFile) return null
        return runCatching { describe(root, javaMajor, rendererId, javaMaximum) }.getOrNull()
    }

    /** What the pack can draw with, for the choice in the settings. */
    fun renderers(layout: GameLayout): List<RendererOption> {
        val root = File(layout.runtimes, FOLDER)
        if (!root.isDirectory) return emptyList()
        val bundle = readDescription(root) ?: return emptyList()
        if (bundle.renderers.isNotEmpty()) {
            val rendererDir = bundle.rendererDir.takeIf { it.isNotEmpty() }?.let { SafePath.join(root, it) }
            return RendererCatalog.withDiscovered(bundle.renderers, rendererDir)
        }
        if (bundle.rendererLibrary.isEmpty()) return emptyList()
        return listOf(
            RendererOption(
                id = bundle.rendererId,
                label = bundle.rendererLabel,
                library = bundle.rendererLibrary,
                eglLibrary = bundle.eglLibrary,
                shaders = bundle.shaders,
                environment = bundle.environment,
            )
        )
    }

    fun owns(layout: GameLayout, dir: File): Boolean {
        val root = File(layout.runtimes, FOLDER).canonicalFile
        val candidate = dir.canonicalFile
        return candidate == root || candidate.path.startsWith(root.path + File.separator)
    }

    fun availableJava(layout: GameLayout): List<Int> {
        val root = File(layout.runtimes, FOLDER)
        if (!root.isDirectory) return emptyList()
        val bundle = readDescription(root)
        val slots = bundle?.jres?.filter { usableJavaHome(SafePath.join(root, it.path)) }
        if (!slots.isNullOrEmpty()) return slots.map { it.major }.sorted()
        return listOfNotNull(describeJavaMajor(root))
    }

    fun clear(layout: GameLayout) {
        File(layout.runtimes, FOLDER).deleteRecursively()
    }

    fun install(
        layout: GameLayout,
        source: InputStream,
        onEntry: (String) -> Unit = {},
    ): InstalledRuntime {
        layout.ensure()
        val staging = File(layout.runtimes, "$FOLDER.new")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        val spooled = File(layout.runtimes, "$FOLDER.zip")
        val entries = try {
            SafeUnzip.unpack(SafeUnzip.spool(source, spooled), staging, UNZIP_LIMITS, onEntry)
        } catch (failure: IOException) {
            staging.deleteRecursively()
            throw failure
        } finally {
            spooled.delete()
        }
        if (entries == 0) {
            staging.deleteRecursively()
            throw IOException("в файле нет ни одного файла рантайма")
        }

        markExecutables(staging)
        val runtime = describe(staging, 0)
        File(staging, STAMP).writeText(runtime.javaHome.absolutePath)

        val root = File(layout.runtimes, FOLDER)
        if (root.exists()) {
            val old = File(layout.runtimes, "$FOLDER.old")
            if (old.exists()) old.deleteRecursively()
            root.renameTo(old)
            if (!staging.renameTo(root)) {
                old.renameTo(root)
                throw IOException("не удалось заменить установленный рантайм")
            }
            old.deleteRecursively()
        } else if (!staging.renameTo(root)) {
            throw IOException("не удалось установить рантайм")
        }

        return describe(root, 0)
    }

    private fun readDescription(root: File): BundleDescription? =
        File(root, DESCRIPTION).takeIf { it.isFile }?.let { file ->
            runCatching {
                MojangJson.decodeFromString(BundleDescription.serializer(), file.readText())
            }.getOrNull()
        }

    private fun describe(
        root: File,
        javaMajor: Int,
        rendererId: String = "",
        javaMaximum: Int? = null,
    ): InstalledRuntime {
        val bundle = readDescription(root)

        val slots = bundle?.jres
            ?.map { it.copy() }
            ?.filter { usableJavaHome(SafePath.join(root, it.path)) }
            ?.sortedBy { it.major }
            ?: emptyList()

        val requirement = JavaRequirement(javaMajor, javaMaximum)
        val chosen = when {
            slots.isEmpty() -> null
            javaMajor <= 0 && javaMaximum == null -> slots.last()
            else -> requirement.pick(slots.map { it.major })?.let { major -> slots.first { it.major == major } }
                ?: slots.last()
        }

        val declared = bundle?.javaHome?.takeIf { it.isNotEmpty() }
            ?.let { SafePath.join(root, it) }
            ?.takeIf { usableJavaHome(it) }

        val javaHome = chosen?.let { SafePath.join(root, it.path) }
            ?: declared
            ?: findJavaHome(root)
            ?: throw IOException("в пакете не нашлась виртуальная машина Java (нет lib/server/libjvm.so)")

        if (chosen != null && (javaMajor > 0 || javaMaximum != null) && !requirement.accepts(chosen.major)) {
            throw IOException(
                "сборке нужна ${requirement.describe()}, а в пакете рантайма только " +
                    slots.joinToString(", ") { it.major.toString() },
            )
        }
        val javaHomeMajor = chosen?.major ?: describeJavaMajor(javaHome)
        if (chosen == null && javaMaximum != null && (javaHomeMajor == null || !requirement.accepts(javaHomeMajor))) {
            throw IOException("сборке нужна ${requirement.describe()}, а Java пакета рантайма не подходит")
        }

        val bridgeJars = bundle?.bridge
            ?.takeIf { it.isNotEmpty() }
            ?.map { SafePath.join(root, it) }
            ?: root.walkTopDown()
                .filter { it.isFile && it.extension == "jar" }
                .sortedBy { if (it.name.startsWith("millida")) 0 else 1 }
                .toList()
        if (bridgeJars.isEmpty()) throw IOException("в пакете нет ни одного jar для моста")

        val rendererDir = bundle?.rendererDir?.takeIf { it.isNotEmpty() }?.let { SafePath.join(root, it) }
            ?: findRendererDir(root)
            ?: javaHome

        val chosenRenderer = bundle?.renderers?.firstOrNull { it.id == rendererId }
            ?: bundle?.renderers?.firstOrNull()

        val rendererLibrary = chosenRenderer?.library
            ?: bundle?.rendererLibrary?.takeIf { it.isNotEmpty() }
            ?: rendererDir.listFiles { file -> file.isFile && file.name.startsWith("lib") && file.extension == "so" }
                ?.map { it.name }
                ?.minByOrNull { name -> if (name.contains("GL", ignoreCase = true)) 0 else 1 }
                ?: ""

        val renderer = RuntimeManifest.RendererEntry(
            id = chosenRenderer?.id ?: bundle?.rendererId ?: "local",
            label = chosenRenderer?.label ?: bundle?.rendererLabel ?: "Рендерер из пакета",
            url = "",
            sha256 = "",
            library = rendererLibrary,
            eglLibrary = chosenRenderer?.eglLibrary ?: bundle?.eglLibrary.orEmpty(),
            nativeId = chosenRenderer?.nativeId.orEmpty(),
            shaders = chosenRenderer?.shaders ?: bundle?.shaders ?: false,
            environment = chosenRenderer?.environment ?: bundle?.environment ?: emptyMap(),
        )

        return InstalledRuntime(
            javaHome = javaHome,
            bridgeClasspath = bridgeJars,
            renderer = renderer,
            rendererDir = rendererDir,
            lwjglVersion = bundle?.lwjglVersion.orEmpty(),
            javaMajor = javaHomeMajor ?: 0,
        )
    }

    private fun describeJavaMajor(root: File): Int? {
        val release = root.walkTopDown().firstOrNull { it.isFile && it.name == "release" } ?: return null
        val line = release.readLines().firstOrNull { it.startsWith("JAVA_VERSION=") } ?: return null
        return javaMajorOf(line.substringAfter('=').trim('"'))
    }

    /** Java 8 still reports itself as "1.8.0_482"; from 9 on the first number is the major. */
    fun javaMajorOf(version: String): Int? {
        val parts = version.split('.', '_', '-', '+')
        val first = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
    }

    private fun usableJavaHome(candidate: File): Boolean =
        File(File(File(candidate, "lib"), "server"), "libjvm.so").isFile ||
            File(File(candidate, "bin"), "java").isFile

    private fun findJavaHome(root: File): File? {
        val marker = root.walkTopDown().firstOrNull { it.isFile && it.name == "libjvm.so" }
            ?: root.walkTopDown().firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
            ?: return null
        var candidate: File? = marker.parentFile
        while (candidate != null && candidate != root.parentFile) {
            if (File(candidate, "lib").isDirectory && File(candidate, "bin").isDirectory) return candidate
            if (File(File(candidate, "lib"), "server").isDirectory) return candidate
            candidate = candidate.parentFile
        }
        return marker.parentFile?.parentFile?.parentFile
    }

    private fun findRendererDir(root: File): File? =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "so" && it.name.contains("gl", ignoreCase = true) }
            .map { it.parentFile }
            .firstOrNull()

    private fun markExecutables(root: File) {
        root.walkTopDown().forEach { file ->
            if (file.isFile && (file.extension == "so" || file.parentFile?.name == "bin")) {
                file.setExecutable(true, true)
                file.setReadable(true, true)
            }
        }
    }
}
