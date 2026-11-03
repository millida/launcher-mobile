package net.millida.launcher.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.ui.RoomyFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WideLobbyFitTest {

    /** A landscape window the lobby must survive, in the phone's own dp. */
    private class Shape(val name: String, val window: GridWindow)

    private val shapes = listOf(
        Shape("узкий телефон 800x360", GridWindow(800f, 360f)),
        Shape("телефон владельца 1024x472", GridWindow(1024f, 472f)),
        Shape("2560x1179 при 460 dpi с вырезом 36 dp слева", GridWindow(890f, 410f, startInset = 36f)),
        Shape("2400x1080 с полосой навигации 16 dp", GridWindow(873f, 393f, bottomInset = 16f)),
        Shape("вырез справа: поворот на другой бок", GridWindow(890f, 410f, endInset = 36f)),
        Shape("планшет 1280x800", GridWindow(1280f, 800f)),
    )

    private val fonts = listOf(1f, 1.15f, 1.3f)

    /** The text blocks at [font] as the composables measure them with a linear font curve. */
    private fun typeAt(font: Float, banners: Int = 5): GridType {
        val title = bannerTitleSp(WideLobbyGrid.BANNER_WIDTH, font) * font
        val card = (13f * font + title * 2f + 47f).coerceAtLeast(164f)
        return GridType(
            dockRow = ((14f + 22f + 17f) * font + 27f).coerceAtLeast(76f),
            cornerRow = ((19f + 17f) * font + 12f).coerceAtLeast(52f),
            bannerHeight = card + if (banners > 1) 24f else 0f,
            recHeight = ((14f + 22f + 17f + 13f) * font + 50f).coerceAtLeast(118f),
        )
    }

    /** The grid in the lobby's own dp: a tablet is laid out on its window shrunk by the scale it is drawn at. */
    private fun gridOf(window: GridWindow, font: Float): WideLobbyGrid {
        val scale = WideLobbyGrid.uiScale(window.width, window.height)
        val scaled = GridWindow(
            window.width / scale,
            window.height / scale,
            window.startInset / scale,
            window.endInset / scale,
            window.topInset / scale,
            window.bottomInset / scale,
        )
        return WideLobbyGrid.of(
            scaled,
            typeAt(font),
            bubbleReach = { frame -> stageBubbleReach(frame.dp).value },
            bubbleLift = { frame -> stageBubbleLift(frame.dp).value },
            bubbleSpan = STAGE_BUBBLE_SPAN.value,
        )
    }

    private fun forEachCase(check: (String, WideLobbyGrid) -> Unit) {
        for (shape in shapes) for (font in fonts) check("${shape.name}, шрифт $font", gridOf(shape.window, font))
    }

    private fun WideLobbyGrid.drawnBanner(font: Float = 1f): GridBox =
        banner(WideLobbyGrid.BANNER_WIDTH, typeAt(font).bannerHeight, bannerScale)

    @Test
    fun `ни один блок не наезжает на другой`() {
        forEachCase { why, grid ->
            val figure = grid.figure
            val banner = grid.drawnBanner()
            val pairs = listOf(
                Triple("плитки и док", grid.tiles, grid.dock),
                Triple("плитки и фигура", grid.tiles, figure.body),
                Triple("плитки и кнопки в углу", grid.tiles, grid.corner),
                Triple("фигура и баннер", figure.body, banner),
                Triple("фигура и кнопки в углу", figure.body, grid.corner),
                Triple("пузырь и кнопки в углу", figure.bubble, grid.corner),
                Triple("пузырь и баннер", figure.bubble, banner),
                Triple("баннер и кнопки в углу", banner, grid.corner),
                Triple("баннер и док", banner, grid.dock),
            )
            for ((what, first, second) in pairs) {
                assertFalse("$why: $what наезжают друг на друга ($first / $second)", first.overlaps(second))
            }
            assertTrue("$why: голова не уходит за верхний край", figure.headTop >= grid.tiles.top - 0.01f)
            assertTrue("$why: тело с качанием не подходит к плиткам ближе 8 dp", figure.body.start >= grid.tiles.end + 8f - 0.01f)
            assertTrue("$why: тело с качанием не подходит к баннеру ближе 8 dp", figure.body.end <= grid.rail.start - 8f + 0.01f)
        }
    }

    @Test
    fun `кнопки и плитки не меньше пальца, тексты не режутся`() {
        forEachCase { why, grid ->
            assertTrue("$why: плитка ${grid.tileHeight} ниже 58 dp — подпись закроет персонажей", grid.tileHeight >= WideLobbyGrid.TILE_MIN)
            assertTrue("$why: «Играть» ${grid.playWidth} уже 208 dp — «2 часа в игре» уйдёт в многоточие", grid.playWidth >= 208f)
            assertTrue("$why: «Играть» ниже 76 dp — не попасть большим пальцем", grid.play.height >= 76f - 0.01f)
            assertTrue("$why: «Сегодня играем» ${grid.plateWidth} уже 200 dp — название сборки не влезет", grid.plateWidth >= 200f)
            assertTrue("$why: в углу ${grid.corner.width} dp — не встают даже три квадратные кнопки", grid.corner.width >= 52f * 3f + 8f * 2f)
            assertTrue("$why: кнопки в углу ниже 48 dp", grid.corner.height >= 52f)
            assertTrue("$why: фигура ${grid.figure.frame} мельче читаемого", grid.figure.frame >= WideLobbyGrid.FIGURE_MIN)
            assertTrue("$why: баннер ужат до ${grid.bannerScale} — мельче 0.62 его не показывают", grid.bannerFits)
        }
    }

    @Test
    fun `общие линии — верх, низ, земля и края правой колонки`() {
        forEachCase { why, grid ->
            assertEquals("$why: первая плитка и кнопки в углу на одной верхней линии", grid.corner.top, grid.tiles.top, 0.01f)
            if (grid.tilesReachDock && grid.tileHeight < WideLobbyGrid.TILE_MAX) {
                assertEquals("$why: низ последней плитки на линии низа «Играть»", grid.play.bottom, grid.tiles.bottom, 0.01f)
                assertEquals("$why: док начинается через 12 dp после плиток", grid.tiles.end + WideLobbyGrid.GAP, grid.dock.start, 0.01f)
            }
            assertEquals("$why: подошвы на верхней кромке дока — тень лежит на ней", grid.dock.top, grid.figure.body.bottom, 0.01f)
            assertEquals("$why: правая колонка начинается там же, где «Играть»", grid.play.start, grid.rail.start, 0.01f)
            assertEquals("$why: баннер правым краем по краю дока", grid.dock.end, grid.drawnBanner().end, 0.01f)
            assertEquals("$why: кнопки в углу правым краем по краю дока", grid.dock.end, grid.corner.end, 0.01f)
            assertEquals(
                "$why: фигура ровно посередине между плитками и баннером",
                (grid.tiles.end + grid.rail.start) / 2f,
                grid.figure.centre,
                0.01f,
            )
            val ground = grid.figure.feet + grid.figure.frame * RoomyFrame.FEET_SHARE
            assertEquals("$why: земля сцены (тень) — верх дока", grid.dock.height, ground, 0.01f)
        }
    }

    @Test
    fun `размеры на телефонах, которые держим`() {
        data class Case(
            val window: GridWindow,
            val frame: Float,
            val centre: Float,
            val tile: Float,
            val play: Float,
            val banner: Float,
            val why: String,
        )
        listOf(
            Case(GridWindow(1024f, 472f), 460f, 451.68f, 124f, 286.72f, 0.9824f, "телефон владельца: тело 368 dp от верхней линии до дока, баннер почти ПК-размера"),
            Case(GridWindow(800f, 360f), 320f, 358f, 86.67f, 224f, 0.7733f, "узкий телефон: всё на месте, баннер уменьшен целиком"),
        ).forEach { case ->
            val grid = gridOf(case.window, 1f)
            assertEquals(case.why, case.frame, grid.figure.frame, 0.01f)
            assertEquals(case.why, case.centre, grid.figure.centre, 0.01f)
            assertEquals(case.why, case.tile, grid.tileHeight, 0.01f)
            assertEquals(case.why, case.play, grid.playWidth, 0.01f)
            assertEquals(case.why, case.banner, grid.bannerScale, 0.001f)
        }
    }

    @Test
    fun `планшет рисует ту же раскладку крупнее, телефон не уменьшает`() {
        data class Case(val width: Float, val height: Float, val expected: Float, val why: String)
        listOf(
            Case(800f, 360f, 1f, "телефон меньше расчётного: не уменьшаем, иначе кнопки мельче пальца"),
            Case(1024f, 472f, 1f, "расчётный размер — как есть"),
            Case(1280f, 800f, 1.25f, "планшет: крупнее по ширине"),
            Case(2560f, 1600f, 1.3f, "большой планшет: не крупнее 1.3, как ступень ПК"),
        ).forEach { case ->
            assertEquals(case.why, case.expected, WideLobbyGrid.uiScale(case.width, case.height), 0.001f)
        }
        val tablet = gridOf(GridWindow(1280f, 800f), 1f)
        assertFalse("на планшете плитки кончаются выше дока", tablet.tilesReachDock)
        assertEquals("тогда док идёт от самого левого края, без пустой полосы", WideLobbyGrid.GUTTER, tablet.dock.start, 0.01f)
    }

    @Test
    fun `вырез отодвигает только свой край`() {
        val plain = gridOf(GridWindow(890f, 410f), 1f)
        val cut = gridOf(GridWindow(890f, 410f, startInset = 36f), 1f)
        assertEquals("без выреза — поле 16 dp", 16f, plain.tiles.start, 0.01f)
        assertEquals("вырез 36 dp: плитки за ним с зазором 8 dp", 44f, cut.tiles.start, 0.01f)
        assertEquals("правый край не трогается", plain.dock.end, cut.dock.end, 0.01f)
    }

    @Test
    fun `пузырь не залезает под кнопки в углу`() {
        assertEquals("пузырь ПК-размера тянется на 103 dp правее середины фигуры", 103.34f, stageBubbleReach(226.dp).value, 0.01f)
        assertEquals("у фигуры ПК-размера пузырь начинается на 27 dp выше рамки", 27f, stageBubbleLift(226.dp).value, 0.01f)
        forEachCase { why, grid ->
            assertTrue("$why: кнопки в углу начинаются за пузырём", grid.corner.start >= grid.figure.bubble.end + WideLobbyGrid.GAP - 0.01f)
        }
    }

    @Test
    fun `карточка справа уменьшается целиком, а не пропадает`() {
        data class Case(val room: Float, val sky: Float, val expected: Float, val why: String)
        listOf(
            Case(362f, 240f, 1f, "места хватает — размер ПК, крупнее не растёт"),
            Case(362f, 149f, 0.7926f, "низкий телефон: баннер 300x188 становится меньше, но виден целиком"),
            Case(200f, 300f, 0.6667f, "узкое окно: ужимается по ширине"),
            Case(-10f, 149f, 0f, "места нет — ноль, а не отрицательный масштаб"),
        ).forEach { case ->
            assertEquals(case.why, case.expected, WideLobbyGrid.cardScale(case.room, case.sky, 300f, 188f), 0.001f)
        }
    }

    @Test
    fun `заголовок баннера не переносится и не обрезается`() {
        data class Case(val width: Float, val scale: Float, val expected: Float, val why: String)
        listOf(
            Case(300f, 1f, 25.904762f, "ширина ПК: почти 26, самый длинный заголовок в строку"),
            Case(400f, 1f, 26f, "шире ПК заголовок не растёт"),
            Case(300f, 1.3f, 19.926739f, "крупный шрифт: в sp меньше, на экране тот же размер"),
            Case(28f, 1f, 0f, "нет места под текст — ноль, а не отрицательный размер"),
        ).forEach { case ->
            assertEquals(case.why, case.expected, bannerTitleSp(case.width, case.scale), 0.001f)
        }
    }

    @Test
    fun `слайды баннера из того, что знает лобби`() {
        val arcania = PremiumPack(id = "arcania", title = "Arcania")
        data class Case(
            val promoOn: Boolean,
            val own: Boolean,
            val arcania: PremiumPack?,
            val plusDays: Int?,
            val expected: List<String>,
            val why: String,
        )
        listOf(
            Case(true, true, arcania, 2, listOf("own", "pack:arcania", "invite"), "всё есть — круг без Discord и Telegram по приказу владельца"),
            Case(false, true, arcania, 2, emptyList(), "API снял баннер — на его месте «Попробуй сегодня»"),
            Case(true, false, null, null, emptyList(), "без каталога и аккаунта слайдов нет"),
            Case(true, true, null, 0, listOf("own"), "условия приглашения без дней PLUS — слайда нет"),
        ).forEach { case ->
            val keys = lobbyBanners(case.promoOn, case.own, ownOnline = null, arcania = case.arcania, invitePlusDays = case.plusDays).map { it.key }
            assertEquals(case.why, case.expected, keys)
        }
    }

    @Test
    fun `пиксели сцены как на ПК и одинаковые от запуска к запуску`() {
        assertEquals("на телефоне 873x393 dp — 13 квадратов, плотность ПК", 13, StagePixels.count(873f, 393f))
        assertEquals("пустое окно без квадратов", 0, StagePixels.count(0f, 393f))
        val first = StagePixels.layout(873f, 393f)
        assertArrayEquals("раскладка с постоянным зерном не прыгает между запусками", first, StagePixels.layout(873f, 393f), 0f)
        for (at in first.indices step StagePixels.STRIDE) {
            val side = first[at + 2]
            assertTrue("квадрат $at в окне по x", first[at] in 0f..873f)
            assertTrue("квадрат $at в окне по y", first[at + 1] in 0f..393f)
            assertTrue("сторона квадрата — 10, 17 или 24 dp, как на ПК: $side", side == 10f || side == 17f || side == 24f)
            assertTrue("квадраты едва видны: прозрачность ${first[at + 3]}", first[at + 3] in 0.02f..0.12f)
        }
    }

    @Test
    fun `сцена берёт цвет кнопок`() {
        val accent = Color(red = 0.5f, green = 0.25f, blue = 0.75f)
        val palette = stagePalette(accent)
        assertEquals("центр — цвет кнопок, высветленный на 32%", 0.66f, palette.core.red, 0.01f)
        assertEquals("край — цвет кнопок, утопленный в чёрный на 66%", 0.17f, palette.void.red, 0.01f)
        assertTrue(
            "от центра к краю цвет только темнеет: иначе кнопки по краям не читаются",
            palette.core.red > palette.mid.red && palette.mid.red > palette.edge.red &&
                palette.edge.red > palette.deep.red && palette.deep.red > palette.void.red,
        )
    }
}
