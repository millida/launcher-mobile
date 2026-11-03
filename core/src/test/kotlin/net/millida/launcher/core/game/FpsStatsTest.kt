package net.millida.launcher.core.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FpsStatsTest {
    @Test
    fun `мало секунд - процентилей нет`() {
        val stats = FpsStats()
        repeat(FpsStats.MIN_SAMPLES - 1) { stats.add(30) }
        assertNull(stats.percentile(50))
    }

    @Test
    fun `нули не считаются`() {
        val stats = FpsStats()
        repeat(100) { stats.add(0) }
        assertEquals(0, stats.n)
    }

    @Test
    fun `медиана и медленный хвост`() {
        val stats = FpsStats()
        repeat(10) { stats.add(10) }
        repeat(80) { stats.add(30) }
        repeat(10) { stats.add(60) }
        assertEquals(30, stats.percentile(50))
        assertEquals(10, stats.percentile(10))
    }
}
