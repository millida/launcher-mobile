package net.millida.launcher.core

import net.millida.launcher.core.runtime.ExitHook
import net.millida.launcher.core.runtime.GameEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEndTest {
    private class Case(val why: String, val code: Int, val signal: Boolean, val crashReported: Boolean, val end: GameEnd)

    @Test
    fun `an intercepted end is reported with the code the dashboard already groups by`() {
        val cases = listOf(
            Case("«Quit Game» is the player leaving", 0, false, false, GameEnd(true, "exit_0")),
            Case("the game's own exit after a crash report", 255, false, false, GameEnd(false, "exit_255")),
            Case("a mod loader refusing to start", 1, false, false, GameEnd(false, "exit_1")),
            Case("an abort in the graphics layer used to leave a frozen picture with sound", 6, true, false, GameEnd(false, "signal_6")),
            Case("the fallback interceptor says 0 for every exit, the crash report says otherwise", 0, false, true, GameEnd(false, "exit_0")),
            Case("a signal is never a clean end, whatever its number", 0, true, false, GameEnd(false, "signal_0")),
        )
        cases.forEach { case ->
            assertEquals(
                "${case.why} — a wrong verdict hides a crash from the player and from the statistics",
                case.end,
                GameEnd.of(case.code, case.signal, case.crashReported),
            )
        }
    }

    @Test
    fun `the interceptor is on unless the manifest switches it off`() {
        assertTrue("no switch in the manifest must not disable it", ExitHook.enabled(emptyMap()))
        assertTrue("any other value keeps it on", ExitHook.enabled(mapOf(ExitHook.SWITCH to "1")))
        assertFalse("0 is the switch that needs no release", ExitHook.enabled(mapOf(ExitHook.SWITCH to "0")))
    }

    private class Mark(val why: String, val mark: String, val version: String, val unconfirmed: Int, val refused: Boolean)

    @Test
    fun `a phone the interceptor may have broken goes without it after two unproven starts`() {
        val marks = listOf(
            Mark("no mark yet: the first start installs it", "", "1.9.25", 0, false),
            Mark("one start that proved nothing can be a player who changed his mind", ExitHook.mark("1.9.25", 1), "1.9.25", 1, false),
            Mark("two in a row: starts that worked before must not be lost to the interceptor", ExitHook.mark("1.9.25", 2), "1.9.25", 2, true),
            Mark("a count above the limit stays refused", ExitHook.mark("1.9.25", 7), "1.9.25", 7, true),
            Mark("a new release tries again: it may carry the fix", ExitHook.mark("1.9.25", 2), "1.9.26", 0, false),
            Mark("a version that only starts the same way is another version", ExitHook.mark("1.9.2", 2), "1.9.25", 0, false),
            Mark("a damaged mark is no reason to refuse", "1.9.25:many", "1.9.25", 0, false),
            Mark("a negative count is a damaged mark too", "1.9.25:-3", "1.9.25", 0, false),
            Mark("a mark without a version is not ours", ":2", "1.9.25", 0, false),
        )
        marks.forEach { mark ->
            assertEquals("${mark.why} — the count decides whether the game still starts", mark.unconfirmed, ExitHook.unconfirmed(mark.mark, mark.version))
            assertEquals("${mark.why} — a wrong refusal loses the fix, a missed one loses the game", mark.refused, ExitHook.refused(mark.mark, mark.version))
        }
    }
}
