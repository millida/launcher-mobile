package net.millida.launcher.core.runtime

import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.profile.ContentFile
import java.io.File
import java.io.IOException

/**
 * Sodium and its forks draw terrain with their own desktop GLSL. Add-ons such
 * as Sodium Extra only ride on it, so they do not count by themselves.
 */
object SodiumFamily {
    private val IDS = setOf("sodium", "embeddium", "rubidium", "iris", "oculus", "nvidium")
    private val JAR_NAME = Regex(
        "^(?:sodium|embeddium|rubidium|iris|oculus|nvidium)(?:[-_+](?:fabric|forge|neoforge|quilt))*[-_+]?(?:mc)?v?\\d.*\\.jar$",
    )

    fun present(mods: List<ContentFile>): Boolean = mods.any { it.enabled && isMember(it.modId, it.name) }

    /** The id from the mod's own description decides; the file name is read only when the jar has none. */
    fun isMember(modId: String, fileName: String): Boolean {
        val id = modId.trim().lowercase()
        if (id.isNotEmpty()) return id in IDS
        return JAR_NAME.matches(fileName.trim().lowercase())
    }
}

/**
 * MobileGlues reads config.json only from a folder named by MG_DIR_PATH;
 * without it, it takes /sdcard/MG, which the game cannot write, and forces its
 * defaults. ANGLE is forced off: the runtime pack carries libGLESv2_angle.so on
 * the library path, and MobileGlues would pick it up instead of the phone's GLES.
 */
object MobileGluesSetup {
    const val FOLDER = ".mobileglues"
    const val DIR_VARIABLE = "MG_DIR_PATH"
    private const val CONFIG_FILE = "config.json"

    val config: String = """
        {
          "enableANGLE": 2,
          "enableNoError": 0,
          "enableExtTimerQuery": 0,
          "enableExtComputeShader": 0,
          "enableExtDirectStateAccess": 0,
          "maxGlslCacheSize": 32,
          "angleDepthClearFixMode": 0,
          "customGLVersion": 0,
          "fsr1Setting": 0
        }
    """.trimIndent() + "\n"

    fun folder(gameDir: File): File = File(gameDir, FOLDER)

    fun environment(folder: File): Map<String, String> = mapOf(DIR_VARIABLE to folder.absolutePath)

    /** Returns true when the file had to be written. */
    fun writeConfig(folder: File): Boolean {
        if (!folder.isDirectory && !folder.mkdirs()) {
            throw IOException("папка настроек MobileGlues не создалась: ${folder.absolutePath}")
        }
        val file = File(folder, CONFIG_FILE)
        if (file.isFile && file.readText() == config) return false
        file.atomicWriteText(config)
        return true
    }
}
