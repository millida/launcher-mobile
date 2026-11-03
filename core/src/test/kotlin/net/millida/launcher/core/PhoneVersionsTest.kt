package net.millida.launcher.core

import net.millida.launcher.core.catalog.NewBuild
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.mojang.VersionManifest
import net.millida.launcher.core.runtime.PhoneVersionRules
import net.millida.launcher.core.runtime.PhoneVersions
import net.millida.launcher.core.runtime.RuntimeManifest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class PhoneVersionsTest {
    private fun entry(id: String, type: String = "release", released: String = "2026-01-01T00:00:00") =
        VersionManifest.ManifestEntry(id = id, type = type, url = "u", releaseTime = released)

    private val list = listOf(
        entry("26.4-snapshot-1", "snapshot", "2026-09-01T00:00:00"),
        entry("26.3"),
        entry("26.2"),
        entry("1.21.11"),
    )

    @Test
    fun `without server rules the newest non RenderPearl release is recommended`() {
        assertEquals("26.2", PhoneVersions().recommended(list)?.id)
        assertTrue(PhoneVersions().isRisky("26.3"))
        assertFalse(PhoneVersions().isRisky("26.2"))
    }

    @Test
    fun `snapshots are never recommended`() {
        assertEquals("1.21.11", PhoneVersions().recommended(list.filter { it.id != "26.2" && it.id != "26.3" })?.id)
    }

    @Test
    fun `server default wins when the version exists`() {
        val phone = PhoneVersions(PhoneVersionRules(default = "1.21.11"))
        assertEquals("1.21.11", phone.recommended(list)?.id)
        assertEquals("26.2", PhoneVersions(PhoneVersionRules(default = "9.9")).recommended(list)?.id)
    }

    @Test
    fun `server risky list replaces the built in rule and supports a prefix`() {
        val allowed = PhoneVersions(PhoneVersionRules(risky = emptyList()))
        assertEquals("26.3", allowed.recommended(list)?.id)
        val marked = PhoneVersions(PhoneVersionRules(risky = listOf("26.3", "26.4*")))
        assertTrue(marked.isRisky("26.4.1"))
        assertFalse(marked.isRisky("26.2"))
        assertEquals("26.2", marked.recommended(list)?.id)
    }

    @Test
    fun `only risky releases means the newest of them`() {
        assertEquals("26.3", PhoneVersions().recommended(listOf(entry("26.3")))?.id)
        assertNull(PhoneVersions().recommended(emptyList()))
    }

    @Test
    fun `catalogue item picks its newest safe release`() {
        val ids = listOf("26.3", "26.2", "1.21.11")
        assertEquals("26.2", NewBuild.recommendedRelease(ids, PhoneVersions()))
        assertEquals("26.3", NewBuild.recommendedRelease(listOf("26.3"), PhoneVersions()))
        assertEquals("26.3", NewBuild.recommendedRelease(ids, PhoneVersions(PhoneVersionRules(risky = emptyList()))))
        assertEquals("26.2", NewBuild.plan("resourcepack", ids, emptyList())?.version)
    }

    @Test
    fun `manifest field is optional and parsed when present`() {
        val without = MojangJson.decodeFromString(RuntimeManifest.serializer(), """{"revision":3}""")
        assertNull(without.phoneVersions)
        val with = MojangJson.decodeFromString(
            RuntimeManifest.serializer(),
            """{"revision":3,"phoneVersions":{"default":"26.2","risky":["26.3","26.4*"]}}""",
        )
        assertEquals("26.2", with.phoneVersions?.default)
        assertEquals(listOf("26.3", "26.4*"), with.phoneVersions?.risky)
        val onlyDefault = MojangJson.decodeFromString(RuntimeManifest.serializer(), """{"phoneVersions":{"default":"26.2"}}""")
        assertNull(onlyDefault.phoneVersions?.risky)
    }
}
