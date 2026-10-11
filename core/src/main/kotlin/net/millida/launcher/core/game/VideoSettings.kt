package net.millida.launcher.core.game

import java.io.File

/**
 * The game is drawn through a translation layer, and the heavy parts of its own
 * graphics are what the layer renders wrong: whole blocks drop out for a frame
 * or go dark. The settings below are written into the build once, so a new build
 * starts on a picture the layer can actually keep up with; anything the player
 * changes afterwards is left alone.
 */
object VideoSettings {

    // 1.7.10 names its graphics and clouds switches differently; each version ignores the other's keys.
    private val safe = mapOf(
        "graphicsMode" to "0",
        "fancyGraphics" to "false",
        "clouds" to "false",
        // Up to 1.12 the game draws into its own framebuffer and blits it with the fixed
        // pipeline, which GLES does not have: the screen keeps only the fog-coloured clear.
        "fboEnable" to "false",
        "mipmapLevels" to "0",
        "ao" to "false",
        "entityShadows" to "false",
        "renderClouds" to "false",
        "biomeBlendRadius" to "0",
        "renderDistance" to "6",
        "simulationDistance" to "6",
        "maxFps" to "60",
        "enableVsync" to "true",
        "prioritizeChunkUpdates" to "0",
    )

    /**
     * With force the values are put in place even when the game has already
     * written its own: the heavy modes are exactly what the translation layer
     * draws wrong, and a player who turned them on by hand did not mean the
     * flicker that comes with them.
     */
    fun applySafeDefaults(gameDir: File, force: Boolean = false): Boolean {
        val file = File(gameDir, "options.txt")
        val existing = if (file.isFile) {
            file.readLines().mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }.toMap()
        } else {
            emptyMap()
        }

        val merged = LinkedHashMap(existing)
        var changed = false
        safe.forEach { (key, value) ->
            if (force || !merged.containsKey(key)) {
                if (merged[key] != value) {
                    merged[key] = value
                    changed = true
                }
            }
        }
        if (!changed) return false

        gameDir.mkdirs()
        val temporary = File(gameDir, "options.txt.new")
        temporary.writeText(merged.entries.joinToString(System.lineSeparator()) { "${it.key}:${it.value}" })
        if (file.exists()) file.delete()
        return temporary.renameTo(file)
    }
}
