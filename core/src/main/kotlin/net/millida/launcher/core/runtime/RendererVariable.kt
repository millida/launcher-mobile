package net.millida.launcher.core.runtime

import java.io.File
import java.util.zip.ZipFile

/**
 * Sodium refuses to start whenever POJAV_RENDERER is in the environment. The
 * runtime components read the renderer from MILLIDA_RENDER instead: the
 * component script renames the variable inside the window layer and inside
 * the GLFW bridge classes. A runtime unpacked before that rename, or one
 * imported by hand, still reads the old name and keeps getting it.
 */
object RendererVariable {
    const val OWN = "MILLIDA_RENDER"
    const val LEGACY = "POJAV_RENDERER"
    const val WINDOW_LAYER = "libpojavexec.so"
    const val BRIDGE_CLASS = "org/lwjgl/glfw/GLFW.class"

    /**
     * The SDL3 path never puts the bridge on the class path, so only the
     * window layer can still need the old name there.
     */
    fun names(windowLayerRenamed: Boolean, bridgeRenamed: Boolean, sdl: Boolean): List<String> = buildList {
        add(OWN)
        if (!windowLayerRenamed || (!sdl && !bridgeRenamed)) add(LEGACY)
    }

    fun renamed(bytes: ByteArray): Boolean = indexOf(bytes, OWN.toByteArray(Charsets.US_ASCII)) >= 0

    fun windowLayerRenamed(nativeDir: File): Boolean {
        val layer = File(nativeDir, WINDOW_LAYER)
        if (!layer.isFile) return false
        return runCatching { renamed(layer.readBytes()) }.getOrDefault(false)
    }

    /** The bridge classes that read the renderer variable. */
    val BRIDGE_CLASSES = listOf(BRIDGE_CLASS, "org/lwjgl/opengl/GL.class", "org/lwjgl/opengl/Display.class")

    /**
     * The old name is needed only while some bridge class still reads it. A jar that has its
     * own GLFW class without either name (millida-glfw.jar goes first on the class path) says
     * nothing about the others: judging by the first GLFW class found kept POJAV_RENDERER in
     * the environment on every start, and Sodium stopped the game for it.
     */
    fun bridgeRenamed(jars: List<File>): Boolean =
        jars.none { jar -> BRIDGE_CLASSES.any { entry -> entryBytes(jar, entry)?.let(::readsLegacy) == true } }

    fun readsLegacy(bytes: ByteArray): Boolean = indexOf(bytes, LEGACY.toByteArray(Charsets.US_ASCII)) >= 0

    private fun entryBytes(jar: File, entry: String): ByteArray? = runCatching {
        ZipFile(jar).use { zip -> zip.getEntry(entry)?.let { found -> zip.getInputStream(found).use { it.readBytes() } } }
    }.getOrNull()

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        for (start in 0..haystack.size - needle.size) {
            var index = 0
            while (index < needle.size && haystack[start + index] == needle[index]) index++
            if (index == needle.size) return start
        }
        return -1
    }
}
