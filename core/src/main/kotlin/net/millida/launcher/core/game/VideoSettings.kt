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
    private const val MAX_FPS = "maxFps"

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
     * Puts the given options into options.txt, the player's own others left as they are.
     * Returns true when the file changed.
     */
    /** The game's `lang:` for a launcher language; the launcher's codes are not the game's. */
    fun gameLang(launcherLocale: String): String = when (launcherLocale) {
        "ru" -> "ru_ru"
        "en" -> "en_us"
        "uk" -> "uk_ua"
        "pt-BR" -> "pt_br"
        "es" -> "es_es"
        "de" -> "de_de"
        "tr" -> "tr_tr"
        "pl" -> "pl_pl"
        "zh" -> "zh_cn"
        "hi" -> "hi_in"
        "ar" -> "ar_sa"
        "fr" -> "fr_fr"
        "bn" -> "en_us"
        "id" -> "id_id"
        "ur" -> "en_us"
        "ja" -> "ja_jp"
        "vi" -> "vi_vn"
        "te" -> "en_us"
        "ko" -> "ko_kr"
        "sr" -> "sr_sp"
        "sk" -> "sk_sk"
        else -> "en_us"
    }

    /** First start of a build: the game speaks the launcher's language, unless options.txt already has a `lang:` the player may have chosen. */
    fun setLanguageIfUnset(gameDir: File, launcherLocale: String): Boolean {
        val file = File(gameDir, "options.txt")
        if (file.isFile && file.readLines().any { it.startsWith("lang:") }) return false
        return setOptions(gameDir, mapOf("lang" to gameLang(launcherLocale)))
    }

    fun setOptions(gameDir: File, values: Map<String, String>): Boolean {
        val file = File(gameDir, "options.txt")
        val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
        var changed = false
        values.forEach { (key, value) ->
            val index = lines.indexOfFirst { it.startsWith("$key:") }
            when {
                index < 0 -> {
                    lines += "$key:$value"
                    changed = true
                }
                lines[index] != "$key:$value" -> {
                    lines[index] = "$key:$value"
                    changed = true
                }
            }
        }
        if (!changed) return false
        gameDir.mkdirs()
        val temporary = File(gameDir, "options.txt.new")
        temporary.writeText(lines.joinToString(System.lineSeparator()))
        if (file.exists()) file.delete()
        return temporary.renameTo(file)
    }

    /**
     * With force the values are put in place even when the game has already
     * written its own: the heavy modes are exactly what the translation layer
     * draws wrong, and a player who turned them on by hand did not mean the
     * flicker that comes with them.
     */
    fun applySafeDefaults(gameDir: File, force: Boolean = false, frameCap: Int? = null): Boolean {
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
        // The player asked for the display's full rate: that wins over the safe sixty.
        val cap = frameCap?.toString()
        if (cap != null && merged[MAX_FPS] != cap) {
            merged[MAX_FPS] = cap
            changed = true
        }
        if (!changed) return false

        gameDir.mkdirs()
        val temporary = File(gameDir, "options.txt.new")
        temporary.writeText(merged.entries.joinToString(System.lineSeparator()) { "${it.key}:${it.value}" })
        if (file.exists()) file.delete()
        return temporary.renameTo(file)
    }
}
