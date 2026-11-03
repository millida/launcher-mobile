package net.millida.launcher.core

import net.millida.launcher.core.hud.ControlScheme
import net.millida.launcher.core.hud.HudKeys
import net.millida.launcher.core.hud.HudLayout
import net.millida.launcher.core.hud.HudPresets
import net.millida.launcher.core.hud.HudProfiles
import net.millida.launcher.core.hud.HudTransfer
import net.millida.launcher.core.input.Gesture
import net.millida.launcher.core.input.TouchGestures
import net.millida.launcher.core.mojang.MojangJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import net.millida.launcher.core.game.VideoSettings
import java.io.File

class TouchGesturesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `автопрыжок пишется в options txt и не трогает остальное`() {
        val dir = folder.newFolder()
        File(dir, "options.txt").writeText("guiScale:3\nautoJump:false\nfov:70.0")
        assertTrue(VideoSettings.setOptions(dir, mapOf("autoJump" to "true")))
        assertEquals(listOf("guiScale:3", "autoJump:true", "fov:70.0"), File(dir, "options.txt").readLines())
        assertFalse("то же значение второй раз файл не переписывает", VideoSettings.setOptions(dir, mapOf("autoJump" to "true")))
        val fresh = folder.newFolder()
        assertTrue(VideoSettings.setOptions(fresh, mapOf("autoJump" to "false")))
        assertEquals(listOf("autoJump:false"), File(fresh, "options.txt").readLines())
    }

    @Test
    fun `короткое касание на месте это тап, долгое это удержание, сдвиг это перетаскивание`() {
        assertEquals(Gesture.TAP, TouchGestures.classify(heldMs = 80, travel = 2f, slop = 9f, holdMs = 250))
        assertEquals(Gesture.HOLD, TouchGestures.classify(heldMs = 250, travel = 2f, slop = 9f, holdMs = 250))
        assertEquals(Gesture.DRAG, TouchGestures.classify(heldMs = 40, travel = 30f, slop = 9f, holdMs = 250))
        assertEquals("сдвиг важнее времени", Gesture.DRAG, TouchGestures.classify(heldMs = 900, travel = 10f, slop = 9f, holdMs = 250))
    }

    @Test
    fun `тап не засчитывается ни после сдвига, ни после долгого касания`() {
        assertTrue(TouchGestures.isTap(heldMs = 199, travel = 0f, slop = 9f))
        assertFalse("ровно на границе уже не тап", TouchGestures.isTap(heldMs = 200, travel = 0f, slop = 9f))
        assertFalse(TouchGestures.isTap(heldMs = 50, travel = 9.5f, slop = 9f))
        assertTrue("граница сдвига включена", TouchGestures.isTap(heldMs = 50, travel = 9f, slop = 9f))
        assertFalse("отрицательное время бессмысленно", TouchGestures.isTap(heldMs = -1, travel = 0f, slop = 9f))
    }

    @Test
    fun `двойное нажатие считается только внутри окна и только со второго раза`() {
        assertFalse("первого нажатия не было", TouchGestures.isDoubleTap(null, 1000))
        assertTrue(TouchGestures.isDoubleTap(1000, 1200))
        assertFalse(TouchGestures.isDoubleTap(1000, 1300))
        assertFalse("часы не идут назад", TouchGestures.isDoubleTap(1000, 900))
    }

    @Test
    fun `инверсия меняет только знак`() {
        assertEquals(-4f, TouchGestures.lookY(4f, invert = true), 0f)
        assertEquals(4f, TouchGestures.lookY(4f, invert = false), 0f)
    }

    @Test
    fun `свайп по хотбару отсчитывает слоты и помнит остаток`() {
        assertEquals(0 to 10f, TouchGestures.slotSteps(10f, 40f))
        assertEquals(2 to 5f, TouchGestures.slotSteps(85f, 40f))
        assertEquals(-2 to -5f, TouchGestures.slotSteps(-85f, 40f))
        assertEquals("нулевой шаг не зацикливает", 0 to 0f, TouchGestures.slotSteps(100f, 0f))
    }

    @Test
    fun `схемы приносят свой тап`() {
        assertEquals(HudKeys.MOUSE_RIGHT, HudPresets.forScheme(ControlScheme.TOUCH).lookTap)
        assertEquals("в прицеле тап бьёт", HudKeys.MOUSE_LEFT, HudPresets.forScheme(ControlScheme.CROSSHAIR).lookTap)
        assertTrue(
            "в прицеле есть кнопка поставить блок",
            HudPresets.crosshair().controls.any { it.code == HudKeys.MOUSE_RIGHT },
        )
        val touch = HudPresets.bedrock().withOpacity(0.5f).withSizeScale(1.3f).withInvertY(true)
        val switched = touch.withScheme(ControlScheme.CROSSHAIR)
        assertEquals(ControlScheme.CROSSHAIR, switched.shownScheme)
        assertEquals("прозрачность сохранилась", 0.5f, switched.opacity, 0f)
        assertEquals("размер сохранился", 1.3f, switched.sizeScale, 0f)
        assertTrue("инверсия сохранилась", switched.invertY)
    }

    @Test
    fun `старая раскладка без схемы читается и получает схему по содержимому`() {
        val oldBedrock = MojangJson.encodeToString(HudPresets.bedrock().copy(scheme = null))
        val layout = HudProfiles.parse("""{"version":2,"active":"a","profiles":{"a":$oldBedrock}}""")
        assertTrue("файл с пропущенными новыми полями читается", layout != null)
        val read = layout!!.layout
        assertEquals(ControlScheme.TOUCH, read.shownScheme)
        assertEquals(1f, read.shownSizeScale, 0f)
        assertFalse(read.invertY)
        assertEquals(ControlScheme.BUTTONS, HudPresets.stick().copy(scheme = null).shownScheme)
        assertEquals(ControlScheme.CROSSHAIR, HudPresets.bedrock().copy(scheme = null, lookTap = HudKeys.MOUSE_LEFT).shownScheme)
    }

    @Test
    fun `размер и схема из чужой раскладки зажимаются`() {
        val text = """{"name":"x","sizeScale":99,"invertY":true,"scheme":"CROSSHAIR","controls":[
            {"id":"a","label":"A","kind":"KEY","code":32}]}"""
        val layout = HudTransfer.decode(text)!!
        assertEquals(HudLayout.MAX_SIZE, layout.sizeScale, 0f)
        assertTrue(layout.invertY)
        assertEquals(ControlScheme.CROSSHAIR, layout.scheme)
    }
}
