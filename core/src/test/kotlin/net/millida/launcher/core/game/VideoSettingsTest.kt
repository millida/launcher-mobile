package net.millida.launcher.core.game

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun options(text: String, force: Boolean): Map<String, String> {
        val dir = folder.newFolder()
        java.io.File(dir, "options.txt").writeText(text)
        VideoSettings.applySafeDefaults(dir, force)
        return java.io.File(dir, "options.txt").readLines().associate { it.substringBefore(':') to it.substringAfter(':') }
    }

    @Test
    fun `тяжёлая графика сборки 1_7_10 гасится`() {
        data class Case(val key: String, val expected: String, val why: String)

        val forced = options("fancyGraphics:true\nclouds:true\nrenderDistance:16\nmipmapLevels:4\nfboEnable:true", force = true)
        val cases = listOf(
            Case("fancyGraphics", "false", "1.7.10 не знает graphicsMode — без своего ключа красивая графика оставалась"),
            Case("clouds", "false", "облака 1.7.10 живут под clouds, не renderClouds"),
            Case("renderDistance", "6", "дальность из options.txt сборки — главный расход памяти на чанки"),
            Case("mipmapLevels", "0", "мипмапы раздувают атлас текстур сотен модов"),
            Case("fboEnable", "false", "кадр из своего буфера на GLES не выводится — серый экран цвета тумана вместо мира"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, forced[case.key])
        }
    }

    @Test
    fun `без принуждения выбор игрока остаётся`() {
        val kept = options("fancyGraphics:true\nrenderDistance:12", force = false)
        assertEquals("игрок сам включил красивую графику — не трогаем", "true", kept["fancyGraphics"])
        assertEquals("дальность игрока не трогаем", "12", kept["renderDistance"])
        assertEquals("недостающий ключ дописан", "false", kept["clouds"])
    }
}
