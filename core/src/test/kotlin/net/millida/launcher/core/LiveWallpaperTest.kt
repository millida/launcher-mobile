package net.millida.launcher.core

import net.millida.launcher.core.media.LiveWallpaper
import net.millida.launcher.core.media.LiveWallpaperKind
import net.millida.launcher.core.media.SceneSpark
import net.millida.launcher.core.media.SceneWave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live wallpaper costs battery, so when it moves is pinned; the scene's steps
 * are pinned too, since a smooth fade would break the pixel look.
 *
 * | вход                                              | вердикт                                         |
 * | ------------------------------------------------- | ----------------------------------------------- |
 * | сохранён неизвестный id / пусто                   | статичный фон, как было до живых обоев          |
 * | обои выбраны, всё разрешено, приложение спереди   | движется                                        |
 * | выключена анимация / системное «меньше движения» / свёрнуто | стоит                                 |
 * | клетка волны до фронта / после вспышки            | 0 — не горит                                    |
 * | вспышка                                           | ровно ступени по 1/4                            |
 * | искра в начале / середине / после жизни           | гаснет, горит, 0                                |
 */
class LiveWallpaperTest {

    @Test
    fun `сохранённый выбор`() {
        data class Case(val stored: String?, val id: String, val why: String)

        val cases = listOf(
            Case(null, LiveWallpaper.OFF, "ничего не выбрано — фон, который был всегда"),
            Case("", LiveWallpaper.OFF, "пустая строка — то же самое"),
            Case("taiga", LiveWallpaper.OFF, "старые сцены рабочего стола сняты — не падаем, остаёмся на статике"),
            Case("pixels", "pixels", "сцена кодом"),
            Case("bg3", "bg3", "ролик"),
        )
        for (case in cases) {
            assertEquals(case.why, case.id, LiveWallpaper.known(case.stored))
        }
    }

    @Test
    fun `ролики лежат в ассетах рядом с постером`() {
        val videos = LiveWallpaper.ALL.filter { it.kind == LiveWallpaperKind.VIDEO }
        assertEquals("четыре ролика, как на рабочем столе", listOf("bg1", "bg2", "bg3", "bg4"), videos.map { it.id })
        for (video in videos) {
            assertEquals("путь ролика", "wallpapers/${video.id}.mp4", video.clip)
            assertEquals("постер держит кадр, пока ролик не пошёл", "wallpapers/${video.id}.jpg", video.poster)
        }
        val scene = LiveWallpaper.of("pixels")
        assertTrue("сцене файлы не нужны", scene != null && scene.clip.isEmpty() && scene.poster.isEmpty())
    }

    @Test
    fun `когда обои движутся`() {
        val scene = LiveWallpaper.of("pixels")
        data class Case(val chosen: LiveWallpaper?, val motion: Boolean, val reduced: Boolean, val resumed: Boolean, val moves: Boolean, val why: String)

        val cases = listOf(
            Case(scene, motion = true, reduced = false, resumed = true, moves = true, why = "всё разрешено"),
            Case(null, motion = true, reduced = false, resumed = true, moves = false, why = "обои не выбраны"),
            Case(scene, motion = false, reduced = false, resumed = true, moves = false, why = "игрок выключил «Анимацию»"),
            Case(scene, motion = true, reduced = true, resumed = true, moves = false, why = "телефон просит меньше движения"),
            Case(scene, motion = true, reduced = false, resumed = false, moves = false, why = "приложение не спереди — батарею не тратим"),
        )
        for (case in cases) {
            assertEquals(case.why, case.moves, LiveWallpaper.animates(case.chosen, case.motion, case.reduced, case.resumed))
        }
    }

    @Test
    fun `вспышка клетки волны`() {
        data class Case(val sinceFront: Float, val level: Float, val why: String)

        val cases = listOf(
            Case(-1f, 0f, "фронт ещё не дошёл"),
            Case(0f, 0f, "фронт ровно на клетке — ещё темно"),
            Case(30f, 0.5f, "быстрый вход: треть подъёма — ступень 1/2"),
            Case(SceneWave.HOT_MS, 1f, "пик"),
            Case(300f, 0.75f, "плавный выход ступенями"),
            Case(SceneWave.FLASH_MS, 0f, "вспышка кончилась — клетка восстановлена"),
        )
        for (case in cases) {
            assertEquals(case.why, case.level, SceneWave.level(case.sinceFront), 0.0001f)
        }
    }

    @Test
    fun `волна доживает до дальней клетки`() {
        val life = SceneWave.lifeMs(400f, 300f)
        val farthest = SceneWave.sinceFront(life - SceneWave.FLASH_MS, 500f, SceneWave.JITTER_CELLS)
        assertEquals("в конце жизни дальняя клетка только начинает вспышку — раньше волну убирать нельзя", 0f, farthest, 0.01f)
    }

    @Test
    fun `яркость искры`() {
        data class Case(val progress: Float, val twinkle: Float, val alpha: Float, val why: String)

        val cases = listOf(
            Case(-0.1f, 1f, 0f, "искра ещё не родилась"),
            Case(0.05f, 1f, 0.5f, "разгорается ступенью"),
            Case(0.5f, 1f, 1f, "середина жизни, пик мерцания"),
            Case(0.5f, -1f, 0.25f, "середина жизни, провал мерцания — тускло, но видно"),
            Case(0.97f, 1f, 0.25f, "почти погасла"),
            Case(1.1f, 1f, 0f, "жизнь кончилась"),
        )
        for (case in cases) {
            assertEquals(case.why, case.alpha, SceneSpark.alpha(case.progress, case.twinkle), 0.0001f)
        }
    }
}
