package net.millida.launcher.core

import net.millida.launcher.core.hud.HudAction
import net.millida.launcher.core.hud.HudForeign
import net.millida.launcher.core.hud.HudHistory
import net.millida.launcher.core.hud.HudKeys
import net.millida.launcher.core.hud.HudKind
import net.millida.launcher.core.hud.HudPresets
import net.millida.launcher.core.hud.HudProfiles
import net.millida.launcher.core.hud.HudSource
import net.millida.launcher.core.hud.HudTransfer
import net.millida.launcher.core.hud.HudVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Players come from FCL, Zalith and Pojav with layouts they tuned for months.
 * Each case pins one rule of the conversion: a key that lands on the wrong
 * code or a button placed off its spot is a layout the player has to redo.
 */
class HudForeignTest {

    private val pojav = """
        {"version":8,"scaledAt":100,
         "mControlDataList":[
           {"name":"Jump","keycodes":[32,0,0,0],"dynamicX":"${'$'}{right} - ${'$'}{margin}","dynamicY":"${'$'}{bottom}","width":50,"height":50,"isToggle":false,"displayInGame":true,"displayInMenu":false},
           {"name":"Keyboard","keycodes":[-1,0,0,0],"dynamicX":"0","dynamicY":"0","width":80,"height":30,"displayInGame":true,"displayInMenu":true},
           {"name":"Sprint","keycodes":[341,87,0,0],"dynamicX":"(${'$'}{screen_width} - ${'$'}{width}) / 2","dynamicY":"dp(10)","width":80,"height":30,"isToggle":true},
           {"name":"Broken","keycodes":[32,0,0,0],"dynamicX":"${'$'}{unknown}","dynamicY":"0","width":50,"height":50}
         ],
         "mJoystickDataList":[{"name":"stick","dynamicX":"${'$'}{margin}","dynamicY":"${'$'}{bottom} - ${'$'}{margin}","width":150,"height":150}],
         "mDrawerDataList":[{"properties":{},"buttonProperties":[]}]}
    """.trimIndent()

    private val fcl = """
        {"id":"x","name":"My FCL","controllerVersion":21,
         "viewGroups":[{"id":"g","name":"Main","visibility":"VISIBLE","viewData":{
           "buttonList":[
             {"id":"b1","text":"W","baseInfo":{"visibilityType":"IN_GAME","xPosition":0,"yPosition":1000,"sizeType":"ABSOLUTE","absoluteWidth":40,"absoluteHeight":40},
              "event":{"pressEvent":{"autoKeep":false,"outputKeycodes":[17]}}},
             {"id":"b2","text":"","baseInfo":{"visibilityType":"ALWAYS","xPosition":1000,"yPosition":0,"sizeType":"PERCENTAGE","percentageWidth":{"reference":"SCREEN_HEIGHT","size":100},"percentageHeight":{"reference":"SCREEN_HEIGHT","size":100}},
              "event":{"pressEvent":{"outputKeycodes":[1002]}}},
             {"id":"b3","text":"Menu","baseInfo":{},"event":{"clickEvent":{"openMenu":true}}},
             {"id":"b4","text":"Label only","baseInfo":{},"event":{}}
           ],
           "directionList":[{"id":"d","baseInfo":{"visibilityType":"IN_GAME","xPosition":0,"yPosition":1000,"sizeType":"ABSOLUTE","absoluteWidth":120,"absoluteHeight":120}}]
         }}]}
    """.trimIndent()

    private val zalith = """
        {"info":{"name":{"default":"default","matchQueue":[]}},"editorVersion":12,
         "layers":[
           {"name":"main","uuid":"a","hide":false,"visibilityType":"in_game","normalButtons":[
             {"text":{"default":"Esc"},"position":{"x":0,"y":0},"buttonSize":{"type":"percentage","widthPercentage":2000,"heightPercentage":1000,"widthReference":"screen_height","heightReference":"screen_height"},"visibilityType":"always","clickEvents":[{"type":"key","key":"GLFW_KEY_ESCAPE"}],"isToggleable":false},
             {"text":{"default":"Sneak"},"position":{"x":10000,"y":10000},"buttonSize":{"type":"dp","widthDp":60,"heightDp":60},"visibilityType":"always","clickEvents":[{"type":"key","key":"GLFW_KEY_LEFT_SHIFT"}],"isToggleable":true},
             {"text":{"default":"IME"},"position":{"x":5000,"y":5000},"buttonSize":{"type":"dp","widthDp":50,"heightDp":50},"clickEvents":[{"type":"launcher_event","key":"launcher.event.switch_ime"}]},
             {"text":{"default":"Tabs"},"position":{"x":5000,"y":5000},"buttonSize":{"type":"dp","widthDp":50,"heightDp":50},"clickEvents":[{"type":"switch_layer","key":"b"}]}
           ]},
           {"name":"hidden","uuid":"b","hide":true,"visibilityType":"always","normalButtons":[
             {"text":{"default":"Hit"},"position":{"x":0,"y":0},"buttonSize":{"type":"dp","widthDp":50,"heightDp":50},"clickEvents":[{"type":"key","key":"GLFW_MOUSE_BUTTON_LEFT"}]}
           ]}
         ]}
    """.trimIndent()

    private fun near(expected: Float, actual: Float, why: String) =
        assertEquals(why, expected, actual, 0.001f)

    @Test
    fun `a Pojav layout keeps keys, specials, toggles and places`() {
        val imported = HudForeign.read(pojav)
        assertNotNull("a Pojav file is recognised", imported)
        imported!!
        assertEquals(HudSource.POJAV, imported.source)
        val controls = imported.layout.controls
        val jump = controls.first { it.label == "Jump" }
        assertEquals("GLFW space stays space", HudKeys.SPACE, jump.code)
        near((640f - 50f - 2f + 25f) / 640f, jump.x, "right minus margin is measured from the right edge")
        near((360f - 50f + 25f) / 360f, jump.y, "bottom puts the button on the lower edge")
        val keyboard = controls.first { it.label == "Keyboard" }
        assertEquals("Pojav -1 is the on-screen keyboard", HudAction.KEYBOARD, keyboard.action)
        assertEquals("shown in game and in menus means always", HudVisibility.ALWAYS, keyboard.visibility)
        val sprint = controls.first { it.label == "Sprint" }
        assertEquals("both keys of a combination survive", listOf(HudKeys.LEFT_CONTROL, HudKeys.W), sprint.keys)
        assertTrue("isToggle becomes a toggle", sprint.toggle)
        near(0.5f, sprint.x, "an expression with brackets centres the button")
        assertEquals("one stick from the joystick list", 1, controls.count { it.kind == HudKind.STICK })
        assertEquals("an unknown variable and a drawer are skipped, not guessed", 2, imported.skipped)
    }

    @Test
    fun `an FCL layout maps Linux key codes, the mouse and the direction pad`() {
        val imported = HudForeign.read(fcl)!!
        assertEquals(HudSource.FCL, imported.source)
        assertEquals("the layout keeps its own name", "My FCL", imported.layout.name)
        val controls = imported.layout.controls
        val forward = controls.first { it.label == "W" }
        assertEquals("FCL 17 is W", HudKeys.W, forward.code)
        near(20f / 640f, forward.x, "xPosition 0 is the left edge")
        near((360f - 20f) / 360f, forward.y, "yPosition 1000 is the bottom edge")
        val use = controls.first { it.kind == HudKind.MOUSE }
        assertEquals("FCL 1002 is the right mouse button", HudKeys.MOUSE_RIGHT, use.code)
        near(36f, use.width, "a share of the screen height is counted on our nominal height")
        assertEquals("openMenu in a click event opens our menu", HudAction.MENU, controls.first { it.label == "Menu" }.action)
        assertEquals(1, controls.count { it.kind == HudKind.STICK })
        assertEquals("a button with no action is a label and is skipped", 1, imported.skipped)
    }

    @Test
    fun `a Zalith layout reads layers, sizes and launcher events`() {
        val imported = HudForeign.read(zalith)!!
        assertEquals(HudSource.ZALITH, imported.source)
        val controls = imported.layout.controls
        val escape = controls.first { it.label == "Esc" }
        assertEquals(HudKeys.ESCAPE, escape.code)
        near(72f, escape.width, "2000 of 10000 of the screen height")
        assertEquals("the layer shows in game only", HudVisibility.LOOK, escape.visibility)
        val sneak = controls.first { it.label == "Sneak" }
        assertTrue(sneak.toggle)
        near((640f - 30f) / 640f, sneak.x, "x 10000 is the right edge")
        assertEquals(HudAction.KEYBOARD, controls.first { it.label == "IME" }.action)
        assertTrue("a hidden layer arrives hidden, not lost", controls.first { it.label == "Hit" }.hidden)
        assertEquals("layer switching has no counterpart", 1, imported.skipped)
    }

    @Test
    fun `our own export and junk are told apart`() {
        val own = HudTransfer.encode(HudPresets.bedrock())
        assertEquals(HudSource.MILLIDA, HudForeign.read(own)?.source)
        assertNull("not a layout", HudForeign.read("""{"hello":1}"""))
        assertNull("not JSON", HudForeign.read("W A S D"))
        assertNull("a Pojav file with nothing usable", HudForeign.read("""{"mControlDataList":[{"keycodes":[-2]}]}"""))
    }

    @Test
    fun `undo coalesces one drag and keeps separate steps apart`() {
        val start = HudProfiles.initial()
        val moved1 = start.withLayout(start.layout.copy(opacity = 0.5f))
        val moved2 = start.withLayout(start.layout.copy(opacity = 0.6f))
        var history = HudHistory()
        assertFalse(history.canUndo)
        history = history.record(start, "move:jump")
        history = history.record(moved1, "move:jump")
        val (back, rest) = history.undo()!!
        assertEquals("a drag in many frames is one step back", start, back)
        assertFalse(rest.canUndo)

        history = HudHistory().record(start, "move:jump").seal().record(moved1, "move:jump")
        assertEquals("a second drag after the finger lifted is its own step", moved1, history.undo()!!.first)

        history = HudHistory().record(start, null).record(moved2, null)
        assertEquals("discrete changes never merge", moved2, history.undo()!!.first)
    }
}
