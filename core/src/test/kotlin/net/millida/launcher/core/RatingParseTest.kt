package net.millida.launcher.core

import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.rating.RatingPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingParseTest {

    /** Shape taken from the live answer that broke the screen: nulls in numbers and text. */
    private val payload = """
        {
          "total": 2,
          "servers": [
            {
              "id": "cmqax1",
              "name": "Живой сервер",
              "slug": "alive",
              "ip": "play.example.net",
              "online": 42,
              "isOnline": true,
              "avgOnline": 30,
              "shortDesc": "Выживание",
              "motd": "Привет",
              "license": "CRACKED",
              "logoUrl": "https://example.invalid/logo.png",
              "versionMajors": ["1.20", "1.21"],
              "categories": ["SURVIVAL"]
            },
            {
              "id": "cmqax2",
              "name": "Молчащий сервер",
              "slug": "silent",
              "ip": "quiet.example.net",
              "online": 0,
              "isOnline": false,
              "avgOnline": null,
              "motd": null,
              "shortDesc": null,
              "logoUrl": null,
              "versionMajors": [],
              "categories": []
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `страница рейтинга разбирается, когда числа и тексты пришли пустыми`() {
        val page = MojangJson.decodeFromString(RatingPage.serializer(), payload)

        assertEquals("оба сервера обязаны доехать", 2, page.servers.size)
        assertEquals("всего в рейтинге", 2, page.total)

        val alive = page.servers.first()
        assertTrue("живой сервер помечен онлайном", alive.up)
        assertEquals(42, alive.online)
        assertEquals(30, alive.avgOnline)
        assertEquals("play.example.net", alive.ip)
        assertEquals(listOf("1.20", "1.21"), alive.versionMajors)

        val silent = page.servers.last()
        assertFalse("молчащий сервер не онлайн", silent.up)
        assertEquals("null в числе не должен ронять разбор, а становится нулём", 0, silent.avgOnline)
        assertEquals("null в тексте превращается в пустую строку", "", silent.motd)
        assertEquals("", silent.shortDesc)
        assertEquals(null, silent.logoUrl)
    }
}
