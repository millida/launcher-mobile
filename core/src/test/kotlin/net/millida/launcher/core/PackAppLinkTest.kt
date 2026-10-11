package net.millida.launcher.core

import net.millida.launcher.core.links.MillidaLink
import net.millida.launcher.core.links.MillidaLinks
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The share window hands out `https://millida.net/p/<CODE>`, and the system
 * opens it in the launcher once the site vouches for the app. Only a pack
 * code of our own host means something; every other address stays a page.
 */
class PackAppLinkTest {

    @Test
    fun `ссылка на код сборки открывает её в лаунчере`() {
        val cases = listOf(
            "https://millida.net/p/K7M2QRT9" to MillidaLink.Pack("K7M2QRT9"),
            "https://millida.net/p/k7m2qrt9" to MillidaLink.Pack("K7M2QRT9"),
            "https://www.millida.net/p/K7M2QRT9" to MillidaLink.Pack("K7M2QRT9"),
            "https://millida.net/p/K7M2QRT9?utm_source=tg" to MillidaLink.Pack("K7M2QRT9"),
            "https://millida.net/p/K7M2QRT0" to null,
            "https://millida.net/p/K7M2QRT9/extra" to null,
            "https://millida.net/modpacks/arcania" to null,
            "https://millida.net.evil.io/p/K7M2QRT9" to null,
            "https://user@millida.net/p/K7M2QRT9" to null,
            "https://millida.net:8443/p/K7M2QRT9" to null,
            "http://millida.net/p/K7M2QRT9" to null,
        )
        val why = mapOf(
            "https://millida.net/p/K7M2QRT0" to "0 нет в алфавите кодов — такого кода не бывает",
            "https://millida.net/modpacks/arcania" to "остальные страницы сайта остаются в браузере",
            "https://millida.net.evil.io/p/K7M2QRT9" to "чужой хост с нашим именем внутри не наш",
            "http://millida.net/p/K7M2QRT9" to "без https адрес не подтверждён",
        )
        cases.forEach { (link, expected) ->
            assertEquals(why[link] ?: link, expected, MillidaLinks.parse(link))
        }
    }
}
