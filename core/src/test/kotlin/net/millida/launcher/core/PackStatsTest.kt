package net.millida.launcher.core

import net.millida.launcher.core.hub.PhoneBlocked
import net.millida.launcher.core.mojang.MojangJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackStatsTest {
    private fun parse(json: String) = PhoneBlocked.parseStats(MojangJson.parseToJsonElement(json))

    @Test
    fun `слабые телефоны важнее всех, без чисел строки нет`() {
        val stats = parse(
            """{"packs":[],"stats":[
              {"id":"Fabulously","bootSec":170,"fpsWeak":22.4,"fpsAll":40,"n":12},
              {"id":"b","bootSec":90,"fpsWeak":null,"fpsAll":31,"n":6},
              {"id":"c","fpsAll":31},
              {"id":"d","bootSec":60}]}""",
        )
        assertEquals(setOf("fabulously", "b", "d"), stats.keys)
        assertEquals(0, stats["d"]?.fps)
        assertEquals(22, stats["fabulously"]?.fps)
        assertEquals(3, stats["fabulously"]?.minutes)
        assertEquals(31, stats["b"]?.fps)
    }

    @Test
    fun `старт не короче минуты`() {
        assertEquals(1, parse("""{"stats":[{"id":"a","bootSec":20,"fpsAll":50,"n":5}]}""")["a"]?.minutes)
    }

    @Test
    fun `мусор вместо stats - пусто`() {
        assertEquals(emptyMap<String, Any>(), parse("""{"stats":"x"}"""))
        assertEquals(emptyMap<String, Any>(), PhoneBlocked.parseStats(null))
    }

    @Test
    fun `кэш туда и обратно`() {
        val stats = parse("""{"stats":[{"id":"a","bootSec":170,"fpsWeak":22,"n":9}]}""")
        assertEquals(stats, PhoneBlocked.decodeStats(PhoneBlocked.encodeStats(stats)))
        assertEquals(emptyMap<String, Any>(), PhoneBlocked.decodeStats(null))
    }

    @Test
    fun `statFor ищет по любому ключу`() {
        PhoneBlocked.setStats(parse("""{"stats":[{"id":"a","bootSec":170,"fpsWeak":22,"n":9}]}"""))
        try {
            assertEquals(22, PhoneBlocked.statFor(null, "A")?.fps)
            assertNull(PhoneBlocked.statFor("zzz"))
        } finally {
            PhoneBlocked.setStats(emptyMap())
        }
    }

    @Test
    fun `установка без запусков - время есть, кадров нет`() {
        val stats = parse("""{"stats":[{"id":"oneblock","installSec":227,"bootSec":null,"fpsWeak":null,"fpsAll":null,"n":0}]}""")
        assertEquals(4, stats["oneblock"]?.minutes)
        assertEquals(0, stats["oneblock"]?.fps)
        assertEquals(stats, PhoneBlocked.decodeStats(PhoneBlocked.encodeStats(stats)))
    }

    @Test
    fun `установка и старт складываются`() {
        assertEquals(5, parse("""{"stats":[{"id":"a","installSec":240,"bootSec":60,"fpsAll":30,"n":5}]}""")["a"]?.minutes)
    }

    @Test
    fun `старый кэш без установки читается`() {
        assertEquals(170, PhoneBlocked.decodeStats("a|170|22|9")["a"]?.bootSec)
    }
}
