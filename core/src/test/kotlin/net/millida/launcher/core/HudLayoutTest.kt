package net.millida.launcher.core

import net.millida.launcher.core.hud.Hotbar
import net.millida.launcher.core.hud.HudKeys
import net.millida.launcher.core.hud.HudKind
import net.millida.launcher.core.hud.HudPresets
import net.millida.launcher.core.hud.HudStore
import net.millida.launcher.core.hud.HudVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The controls a player arranged are their work, and losing them is worse than
 * any bug in the game: the arrangement has to survive a restart, a broken file
 * and a preset applied by mistake.
 */
class HudLayoutTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun layout() = GameLayout(temp.newFolder()).also { it.ensure() }

    @Test
    fun `раскладка сохраняется, читается обратно и не теряется от битого файла`() {
        val game = layout()
        val store = HudStore(game)

        assertEquals(
            "без файла показывается раскладка как в Bedrock",
            HudPresets.BEDROCK,
            store.load().layout.name,
        )

        val moved = HudPresets.stick().let { preset ->
            val stick = preset.controls.first { it.kind == HudKind.STICK }
            preset.replace(stick.withPosition(0.4f, 0.3f).withSize(1.5f))
        }
        store.save(store.load().withLayout(moved))

        val read = HudStore(game).load().layout
        val stick = read.controls.first { it.kind == HudKind.STICK }
        assertEquals("место кнопки сохранилось", 0.4f, stick.x, 0.001f)
        assertEquals("размер кнопки сохранился", 1.5f, stick.scale, 0.01f)

        File(game.root, "hud.json").writeText("{ это не json")
        assertEquals(
            "битый файл не оставляет игрока без управления",
            HudPresets.BEDROCK,
            HudStore(game).load().layout.name,
        )
    }

    @Test
    fun `в каждой готовой раскладке есть чем ходить и чем открыть меню`() {
        HudPresets.all().forEach { preset ->
            val canMove = preset.controls.any {
                it.kind == HudKind.STICK || it.kind == HudKind.FLOAT_STICK || (it.kind == HudKind.KEY && it.code == 87)
            }
            assertTrue("в раскладке «${preset.name}» нет способа идти вперёд", canMove)
            assertNotNull(
                "из раскладки «${preset.name}» нельзя открыть меню лаунчера",
                preset.controls.firstOrNull { it.action.name == "MENU" },
            )
        }
    }

    @Test
    fun `в готовых раскладках кнопки не наезжают друг на друга`() {
        val width = HudPresets.NOMINAL_WIDTH
        val height = HudPresets.NOMINAL_HEIGHT
        val states = listOf(true to false, true to true, false to false)
        fun together(a: HudVisibility, b: HudVisibility) =
            states.any { (look, flying) -> a.shown(look, flying) && b.shown(look, flying) }

        HudPresets.all().forEach { preset ->
            val placed = preset.controls.filterNot { it.anchored || it.hidden }
            val boxes = placed.map { control ->
                val left = control.x * width - control.width / 2f
                val top = control.y * height - control.height / 2f
                control.id to listOf(left, top, left + control.width, top + control.height)
            }
            boxes.forEachIndexed { index, (id, box) ->
                assertTrue(
                    "кнопка «$id» в раскладке «${preset.name}» вылезает за экран",
                    box[0] >= -1f && box[1] >= -1f && box[2] <= width + 1f && box[3] <= height + 1f,
                )
                boxes.drop(index + 1).forEachIndexed { offset, (otherId, other) ->
                    val apart = box[2] <= other[0] || other[2] <= box[0] ||
                        box[3] <= other[1] || other[3] <= box[1]
                    val seenTogether = together(placed[index].visibility, placed[index + 1 + offset].visibility)
                    assertTrue(
                        "кнопки «$id» и «$otherId» в раскладке «${preset.name}» перекрывают друг друга",
                        apart || !seenTogether,
                    )
                }
            }
        }
    }

    @Test
    fun `кнопка не уезжает за край экрана и не становится крохой`() {
        val control = HudPresets.stick().controls.first()
        val pushed = control.withPosition(5f, -3f)
        assertTrue("место по горизонтали остаётся на экране", pushed.x in 0.02f..0.98f)
        assertTrue("место по вертикали остаётся на экране", pushed.y in 0.02f..0.98f)

        val tiny = control.withSize(0.01f)
        assertTrue("кнопку нельзя сжать до невидимой", tiny.scale >= 0.6f)
        val huge = control.withSize(9f)
        assertTrue("кнопку нельзя растянуть на весь экран", huge.scale <= 2.2f)
    }

    /**
     * Input -> verdict on a 2400x1080 phone in landscape. The strip under the
     * finger has to be exactly where the game draws the hotbar: a strip shifted
     * by a slot picks a slot the player did not touch.
     *
     * | guiScale | юникод | масштаб | левый край | верх |
     * | -------- | ------ | ------- | ---------- | ---- |
     * | 0 (авто) | нет    | 4       | 836        | 992  |
     * | 2        | нет    | 2       | 1018       | 1036 |
     * | 3        | нет    | 3       | 927        | 1014 |
     * | 6        | нет    | 4       | 836        | 992  |
     * | 3        | да     | 4       | 836        | 992  |
     */
    @Test
    fun `полоса хотбара на 2400x1080 стоит там же, где её рисует игра`() {
        data class Case(val why: String, val wanted: Int, val unicode: Boolean, val scale: Int, val left: Float, val top: Float)

        val width = 2400
        val height = 1080
        val cases = listOf(
            Case("авто берёт наибольший масштаб, при котором GUI не меньше 320x240: на 1080 по высоте это 4", 0, false, 4, 836f, 992f),
            Case("заданный 2 держится, хотя влез бы больший", 2, false, 2, 1018f, 1036f),
            Case("заданный 3 держится", 3, false, 3, 927f, 1014f),
            Case("заданный больше возможного упирается в предел экрана, как в игре", 6, false, 4, 836f, 992f),
            Case("юникод-шрифт требует чётный масштаб: игра поднимает 3 до 4", 3, true, 4, 836f, 992f),
        )

        for (case in cases) {
            val scale = Hotbar.scale(width, height, case.wanted, case.unicode)
            assertEquals("масштаб GUI: ${case.why}", case.scale, scale)
            val left = Hotbar.left(width, scale)
            val top = Hotbar.top(height, scale)
            assertEquals("левый край полосы: ${case.why}", case.left, left, 0.5f)
            assertEquals(
                "полоса ${Hotbar.WIDTH * scale} px должна стоять по центру, иначе касание уедет на соседний слот: ${case.why}",
                width.toFloat(),
                left * 2 + Hotbar.WIDTH * scale,
                0.5f,
            )
            assertEquals("верх полосы: ${case.why}", case.top, top, 0.5f)
            assertEquals(
                "полоса высотой ${Hotbar.HEIGHT * scale} px должна стоять на нижнем крае окна: ${case.why}",
                height.toFloat(),
                top + Hotbar.HEIGHT * scale,
                0.5f,
            )
        }
    }

    @Test
    fun `касание по полосе выбирает тот слот, под которым палец`() {
        data class Tap(val why: String, val x: Float, val y: Float, val slot: Int?)

        val width = 2400
        val height = 1080
        val scale = Hotbar.scale(width, height, 0)
        val taps = listOf(
            Tap("середина полосы — пятый слот", 1200f, 1060f, 4),
            Tap("у самого левого края — первый слот, а не мимо", 837f, 1040f, 0),
            Tap("у самого правого края — девятый слот, а не десятый", 1563f, 1040f, 8),
            Tap("чуть левее границы второго слота — ещё первый", 919f, 1040f, 0),
            Tap("чуть правее границы второго слота — уже второй", 921f, 1040f, 1),
            Tap("палец шире пикселя: запас над полосой ещё считается слотом", 1200f, 968f, 4),
            Tap("выше запаса — это обзор, камера не должна листать слоты", 1200f, 967f, null),
            Tap("левее полосы — обзор", 835f, 1060f, null),
            Tap("правее полосы — обзор", 1564f, 1060f, null),
        )

        for (tap in taps) {
            assertEquals(tap.why, tap.slot, Hotbar.slotAt(tap.x, tap.y, width, height, scale))
        }
        assertEquals("свайп на ширину слота листает ровно на один слот", 80f, Hotbar.slotPixels(scale), 0.01f)
    }

    @Test
    fun `масштаб читается из options txt, ноль значит авто`() {
        data class Case(val why: String, val text: String, val scale: Int, val unicode: Boolean)

        val cases = listOf(
            Case("пустой файл — авто и обычный шрифт", "", 0, false),
            Case("заданный масштаб и юникод читаются оба", "fov:0.0\nguiScale:3\nforceUnicodeFont:true\n", 3, true),
            Case("мусор в значении не ломает разбор, остаётся авто", "guiScale:abc", 0, false),
            Case("отрицательный масштаб игра не знает — авто", "guiScale:-2", 0, false),
        )

        for (case in cases) {
            val (scale, unicode) = Hotbar.readOptions(case.text)
            assertEquals("масштаб: ${case.why}", case.scale, scale)
            assertEquals("юникод: ${case.why}", case.unicode, unicode)
        }
    }

    @Test
    fun `в каждой готовой раскладке есть Esc, видимый и в меню`() {
        HudPresets.all().forEach { preset ->
            val escape = preset.controls.firstOrNull { it.kind == HudKind.KEY && it.code == HudKeys.ESCAPE }
            assertNotNull(
                "в раскладке «${preset.name}» нет Esc: без него не закрыть чат и инвентарь и не поставить паузу",
                escape,
            )
            assertTrue(
                "Esc в раскладке «${preset.name}» прячется в меню, а им как раз закрывают открытый экран",
                escape != null && !escape.hidden && escape.visibility.shown(lookMode = false, flying = false),
            )
        }
        assertNull(
            "Esc с автоповтором открывал бы и закрывал паузу по кругу, пока палец держит кнопку",
            HudKeys.ESCAPE.takeIf { HudKeys.repeats(it) },
        )
    }
}
