package net.millida.launcher.core

import net.millida.launcher.core.runtime.BootWatch
import net.millida.launcher.core.runtime.BootWatch.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class BootWatchTest {
    @Test
    fun `the first minute judges nothing, however quiet the log is`() {
        assertEquals(Verdict.WAITING, BootWatch.verdict(elapsedMs = 0, quietMs = 0))
        assertEquals(Verdict.WAITING, BootWatch.verdict(elapsedMs = 59_999, quietMs = 59_999))
    }

    // A pack of 228 mods has no window for minutes and writes its log all the while.
    @Test
    fun `a start that keeps writing its log is loading, not broken`() {
        assertEquals(Verdict.LOADING, BootWatch.verdict(elapsedMs = 60_000, quietMs = 300))
        assertEquals(Verdict.LOADING, BootWatch.verdict(elapsedMs = 240_000, quietMs = 59_999))
    }

    @Test
    fun `a start whose log went quiet is a black screen`() {
        assertEquals(Verdict.SILENT, BootWatch.verdict(elapsedMs = 60_000, quietMs = 60_000))
        assertEquals(Verdict.SILENT, BootWatch.verdict(elapsedMs = 200_000, quietMs = 120_000))
    }

    @Test
    fun `the far limit ends the wait even with a busy log`() {
        assertEquals(Verdict.OVERDUE, BootWatch.verdict(elapsedMs = BootWatch.CAP_MS, quietMs = 0))
        assertEquals(Verdict.OVERDUE, BootWatch.verdict(elapsedMs = BootWatch.CAP_MS + 1, quietMs = 90_000))
    }

    @Test
    fun `the clock reads as minutes and seconds`() {
        assertEquals("1:00", BootWatch.clock(60_000))
        assertEquals("2:05", BootWatch.clock(125_900))
        assertEquals("0:00", BootWatch.clock(-5))
    }
}
