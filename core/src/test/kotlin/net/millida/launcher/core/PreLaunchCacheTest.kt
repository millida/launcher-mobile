package net.millida.launcher.core

import net.millida.launcher.core.content.PreLaunchCache
import net.millida.launcher.core.content.PreLaunchCache.Entry
import net.millida.launcher.core.content.PreLaunchCache.LaunchActionName
import net.millida.launcher.core.content.PreLaunchCache.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreLaunchCacheTest {
    private val a = Entry("a.jar", 100, 1)
    private val b = Entry("b.jar.disabled", 200, 2)

    @Test
    fun `order of listing does not matter`() {
        assertEquals(PreLaunchCache.signature(listOf(a, b)), PreLaunchCache.signature(listOf(b, a)))
    }

    @Test
    fun `any change of the folder changes the signature`() {
        val base = PreLaunchCache.signature(listOf(a, b))
        assertNotEquals(base, PreLaunchCache.signature(listOf(a)))
        assertNotEquals(base, PreLaunchCache.signature(listOf(a, b.copy(length = 201))))
        assertNotEquals(base, PreLaunchCache.signature(listOf(a, b.copy(modified = 3))))
        assertNotEquals(base, PreLaunchCache.signature(listOf(a, b.copy(name = "b.jar"))))
        assertNotEquals(base, PreLaunchCache.signature(listOf(a, b), "other"))
    }

    @Test
    fun `clean only when stored equals current`() {
        val s = PreLaunchCache.signature(listOf(a))
        assertTrue(PreLaunchCache.cleanFor(s, s))
        assertFalse(PreLaunchCache.cleanFor(null, s))
        assertFalse(PreLaunchCache.cleanFor("", ""))
        assertFalse(PreLaunchCache.cleanFor("junk", s))
    }

    @Test
    fun `offer round trips and corrupt values are dropped`() {
        val offer = Offer(LaunchActionName.UPDATE, "2:ab", true, 123L)
        assertEquals(offer, PreLaunchCache.decodeOffer(PreLaunchCache.encodeOffer(offer)))
        assertNull(PreLaunchCache.decodeOffer(null))
        assertNull(PreLaunchCache.decodeOffer("garbage"))
        assertNull(PreLaunchCache.decodeOffer("BOGUS\ttrue\t1\tx"))
        assertNull(PreLaunchCache.decodeOffer("NONE\tmaybe\t1\tx"))
        assertNull(PreLaunchCache.decodeOffer("NONE\ttrue\tNaN\tx"))
    }

    @Test
    fun `offer is usable for six hours under the same folder and flag`() {
        val o = Offer(LaunchActionName.NONE, "s", false, 1_000L)
        val h6 = PreLaunchCache.OFFER_TTL_MS
        assertTrue(PreLaunchCache.offerUsable(o, "s", false, 1_000L + h6 - 1))
        assertFalse(PreLaunchCache.offerUsable(o, "s", false, 1_000L + h6))
        assertFalse(PreLaunchCache.offerUsable(o, "t", false, 2_000L))
        assertFalse(PreLaunchCache.offerUsable(o, "s", true, 2_000L))
        assertFalse(PreLaunchCache.offerUsable(o, "s", false, 500L))
        assertFalse(PreLaunchCache.offerUsable(null, "s", false, 2_000L))
    }
}
