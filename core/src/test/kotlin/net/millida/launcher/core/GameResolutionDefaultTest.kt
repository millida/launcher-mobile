package net.millida.launcher.core

import net.millida.launcher.core.game.GameResolution
import org.junit.Assert.assertEquals
import org.junit.Test

class GameResolutionDefaultTest {
    @Test
    fun weakGpusStartAtThreeQuarters() {
        listOf("Adreno (TM) 610", "Adreno (TM) 506", "Adreno (TM) 619", "Mali-G57 MC2", "Mali-G52 MC2", "Mali-T830", "PowerVR Rogue GE8320")
            .forEach { assertEquals(it, GameResolution.WEAK_GPU, GameResolution.defaultFor(it)) }
    }

    @Test
    fun strongOrUnknownGpusKeepFullResolution() {
        listOf("Adreno (TM) 642L", "Adreno (TM) 650", "Adreno (TM) 730", "Mali-G610 MC6", "Mali-G78 MP20", "Xclipse 920", null)
            .forEach { assertEquals(it, GameResolution.FULL, GameResolution.defaultFor(it)) }
    }
}
