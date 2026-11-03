package net.millida.launcher.core.content

import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.profile.ContentFile
import java.io.File

/**
 * Shaders in one tap: Sodium for speed, Iris to load shader packs, and a pack
 * made for weak graphics. Iris exists for Fabric, Quilt and NeoForge; the
 * renderer's automatic pick already sends a build with Sodium to MobileGlues,
 * which compiles the shaders Krypton cannot.
 */
object ShaderKit {
    const val SODIUM = "AANobbMI"
    const val IRIS = "YL57xq9U"

    /** MakeUp Ultra Fast: the lightest widely used pack, with presets down to the phone's level. */
    const val LIGHT_PACK = "izsIPI7a"

    private const val IRIS_CONFIG = "config/iris.properties"
    private const val SHADER_PACK_KEY = "shaderPack"
    private const val ENABLED_KEY = "enableShaders"

    fun supports(loader: LoaderKind): Boolean =
        loader == LoaderKind.FABRIC || loader == LoaderKind.QUILT || loader == LoaderKind.NEOFORGE

    fun hasSodium(mods: List<ContentFile>): Boolean =
        mods.any { it.enabled && (it.modId.equals("sodium", true) || it.modId.equals("embeddium", true) || it.name.lowercase().startsWith("sodium")) }

    fun hasIris(mods: List<ContentFile>): Boolean =
        mods.any { it.enabled && (it.modId.equals("iris", true) || it.modId.equals("oculus", true) || it.name.lowercase().startsWith("iris")) }

    /**
     * Turns the pack on in Iris's own settings, keeping every other line the
     * player or Iris wrote there.
     */
    fun enable(gameDir: File, packFileName: String) {
        val file = File(gameDir, IRIS_CONFIG)
        val kept = if (file.isFile) {
            file.readLines().filterNot { line ->
                val key = line.substringBefore('=').trim()
                key == SHADER_PACK_KEY || key == ENABLED_KEY
            }
        } else {
            emptyList()
        }
        file.parentFile?.mkdirs()
        val lines = kept + "$SHADER_PACK_KEY=${escape(packFileName)}" + "$ENABLED_KEY=true"
        file.atomicWriteText(lines.joinToString("\n", postfix = "\n"))
    }

    /** Java properties treat these as syntax; a file name with them must be escaped to read back the same. */
    private fun escape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '\\', ':', '=', '#', '!' -> append('\\').append(c)
                else -> append(c)
            }
        }
    }
}
