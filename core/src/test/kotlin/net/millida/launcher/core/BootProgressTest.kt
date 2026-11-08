package net.millida.launcher.core

import net.millida.launcher.core.runtime.BootProgress
import net.millida.launcher.core.runtime.BootProgress.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootProgressTest {
    @Test
    fun `the clock follows the last measured boot and stops at 95`() {
        val p = BootProgress(100_000)
        assertEquals(0, p.percent(0))
        assertEquals(47, p.percent(50_000))
        assertEquals(95, p.percent(100_000))
        assertEquals(95, p.percent(400_000))
    }

    @Test
    fun `no measurement uses the default`() {
        assertEquals(47, BootProgress(0).percent(BootProgress.DEFAULT_MS / 2))
    }

    @Test
    fun `log lines raise the floor and never go back`() {
        val p = BootProgress(100_000)
        p.feed("[12:38:24] [main/INFO]: Loading 228 mods")
        assertEquals(228, p.modCount)
        assertEquals(Phase.MODS, p.stage)
        assertEquals(12, p.percent(1_000))
        p.feed("[Render thread/INFO]: Reloading ResourceManager: vanilla")
        assertEquals(Phase.RESOURCES, p.stage)
        assertEquals(72, p.percent(2_000))
        assertTrue(p.percent(2_500) >= 72)
    }

    @Test
    fun `a stale phase is not lowered by an earlier line`() {
        val p = BootProgress(100_000)
        p.feed("Sound engine started")
        p.feed("Loading 5 mods")
        assertEquals(Phase.WINDOW, p.stage)
        assertEquals(93, p.percent(0))
    }
}
