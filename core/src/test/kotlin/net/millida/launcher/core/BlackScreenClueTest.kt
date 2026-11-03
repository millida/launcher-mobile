package net.millida.launcher.core

import net.millida.launcher.core.telemetry.BlackScreenClue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackScreenClueTest {
    @Test
    fun `the last graphics error goes with the line the log stopped at`() {
        val log = """
            [12:00:01] [Render thread/INFO]: Setting user: Steve
            [12:00:03] [Render thread/ERROR]: Failed to compile shader minecraft:core/terrain: GLSL 1.50 is not supported
            [12:00:04] [Render thread/WARN]: Missing sound for event: minecraft:ui.click
            [12:00:05] [Worker-Main-2/ERROR]: Could not load recipe book category
            [12:00:06] [millida] кадра нет больше минуты
        """.trimIndent()
        assertEquals(
            "[12:00:03] [Render thread/ERROR]: Failed to compile shader minecraft:core/terrain: GLSL 1.50 is not supported" +
                " | [12:00:05] [Worker-Main-2/ERROR]: Could not load recipe book category",
            BlackScreenClue.of(log),
        )
    }

    @Test
    fun `without a graphics line the last error is the clue`() {
        val log = "[main/INFO]: Loading 12 mods\n[main/ERROR]: Mixin apply failed for class_310\n[main/INFO]: done"
        assertEquals("[main/ERROR]: Mixin apply failed for class_310 | [main/INFO]: done", BlackScreenClue.of(log))
    }

    @Test
    fun `a calm log gives its last line and an empty one gives nothing`() {
        assertEquals("[main/INFO]: Stopping!", BlackScreenClue.of("[main/INFO]: Loading\n\n[main/INFO]: Stopping!\n"))
        assertNull(BlackScreenClue.of("  \n \n"))
        assertNull(BlackScreenClue.of(null))
    }

    @Test
    fun `an error on the last line is not repeated`() {
        val log = "[main/INFO]: Loading\n[Render thread/ERROR]: OpenGL error 1282"
        assertEquals("[Render thread/ERROR]: OpenGL error 1282", BlackScreenClue.of(log))
    }

    // What the first hour of 1.9.23 reports consisted of: neither line says why nothing was drawn.
    @Test
    fun `the class path and the library hash remark are not clues`() {
        val log = """
            <app>/files/game/runtimes/bridge/lwjgl-glfw-classes.jar:<app>/files/game/libraries/com/github/oshi/oshi-core/6.4.10/oshi-core-6.4.10.jar
            [LWJGL] [ERROR] Incompatible Java and native library versions detected.
            [12:38:24] [main/INFO]: Loading 228 mods
        """.trimIndent()
        assertEquals("[12:38:24] [main/INFO]: Loading 228 mods", BlackScreenClue.of(log))
        assertNull(BlackScreenClue.of("[LWJGL] [ERROR] Incompatible Java and native library versions detected."))
    }

    @Test
    fun `long lines are cut to fit an event and both parts stay`() {
        val error = "[Render thread/ERROR]: OpenGL error " + "x".repeat(400)
        assertEquals(BlackScreenClue.MAX, BlackScreenClue.of(error)!!.length)
        val both = BlackScreenClue.of(error + "\n[main/INFO]: Preparing spawn area")!!
        assertTrue(both.length <= BlackScreenClue.MAX)
        assertTrue(both.endsWith(BlackScreenClue.SEPARATOR + "[main/INFO]: Preparing spawn area"))
    }
}
