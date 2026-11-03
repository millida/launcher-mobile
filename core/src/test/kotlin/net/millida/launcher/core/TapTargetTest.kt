package net.millida.launcher.core

import net.millida.launcher.core.hud.HudKeys
import net.millida.launcher.core.input.TapRouting
import net.millida.launcher.core.input.TapTarget
import net.millida.launcher.core.input.TapTargetCodec
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class TapTargetTest {
    @Test
    fun roundTrip() {
        for (t in TapTarget.entries) {
            assertEquals(t, TapTargetCodec.decode(TapTargetCodec.encode(t, 1_000), 1_100))
        }
    }

    @Test
    fun staleOrBrokenIsUnknown() {
        val fresh = TapTargetCodec.encode(TapTarget.ENTITY, 1_000)
        assertNull("старше 250 мс", TapTargetCodec.decode(fresh, 1_300))
        assertNull(TapTargetCodec.decode(null, 1_000))
        assertNull(TapTargetCodec.decode(ByteArray(4), 1_000))
        assertNull("чужая подпись", TapTargetCodec.decode(fresh.also { it[0] = 'X'.code.toByte() }, 1_000))
        val torn = TapTargetCodec.encode(TapTarget.BLOCK, 1_000).also { it[5] = 0 }
        assertNull("обрыв записи", TapTargetCodec.decode(torn, 1_000))
        val unknown = TapTargetCodec.encode(TapTarget.BLOCK, 1_000).also { it[4] = 9; it[5] = (9 xor 0xFF).toByte() }
        assertNull(TapTargetCodec.decode(unknown, 1_000))
    }

    @Test
    fun entityTapStrikesOnlyInTouchScheme() {
        assertEquals(HudKeys.MOUSE_LEFT, TapRouting.button(HudKeys.MOUSE_RIGHT, TapTarget.ENTITY))
        assertEquals(HudKeys.MOUSE_RIGHT, TapRouting.button(HudKeys.MOUSE_RIGHT, TapTarget.BLOCK))
        assertEquals(HudKeys.MOUSE_RIGHT, TapRouting.button(HudKeys.MOUSE_RIGHT, TapTarget.NONE))
        assertEquals("нет мода: как раньше", HudKeys.MOUSE_RIGHT, TapRouting.button(HudKeys.MOUSE_RIGHT, null))
        assertEquals(HudKeys.MOUSE_LEFT, TapRouting.button(HudKeys.MOUSE_LEFT, TapTarget.BLOCK))
    }
}
