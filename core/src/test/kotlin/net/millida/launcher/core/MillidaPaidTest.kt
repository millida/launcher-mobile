package net.millida.launcher.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.catalog.MillidaCard
import net.millida.launcher.core.catalog.MillidaCatalog
import net.millida.launcher.core.catalog.MillidaFile
import net.millida.launcher.core.catalog.MillidaPaid
import net.millida.launcher.core.catalog.PaidVerdict
import net.millida.launcher.core.catalog.Pricing
import net.millida.launcher.core.mojang.MojangJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Paid packs: the price a player sees, where a refusal sends them and which
 * pages the launcher may open for payment. The answers come from the server
 * and the desktop launcher reads them the same way; drifting here means the
 * phone and the computer disagree about money.
 */
class MillidaPaidTest {

    private fun json(text: String): JsonObject = MojangJson.parseToJsonElement(text).jsonObject

    @Test
    fun `цена на строке каталога`() {
        val cases = listOf(
            Triple(Pricing.ONE_TIME, 30000L, "300 ₽"),
            Triple(Pricing.SUBSCRIPTION, 14900L, "149 ₽/мес"),
            Triple(Pricing.FREE, 30000L, ""),
            Triple(Pricing.ONE_TIME, 0L, ""),
            Triple(Pricing.ONE_TIME, null, ""),
        )
        cases.forEach { (pricing, kopecks, expected) ->
            assertEquals("$pricing $kopecks: бесплатное цену не показывает", expected, MillidaPaid.priceTag(pricing, kopecks))
        }
        assertEquals("копейки видны, когда они есть", true, MillidaPaid.rubles(12950).startsWith("129,5"))
    }

    @Test
    fun `отказ сервера ведёт к своему шагу`() {
        val cases = listOf(
            "Не хватает денег на балансе" to PaidVerdict.FUNDS,
            "INSUFFICIENT_FUNDS" to PaidVerdict.FUNDS,
            "Цена изменилась" to PaidVerdict.PRICE_CHANGED,
            "Доступ уже есть" to PaidVerdict.OWNED,
            "вход в Millida не выполнен" to PaidVerdict.LOGIN,
            "Нет доступа к сборке" to PaidVerdict.NO_ACCESS,
            "Баланс заморожен" to PaidVerdict.FROZEN,
            "Покупка уже идёт" to PaidVerdict.BUSY,
            "что-то сломалось" to PaidVerdict.ERROR,
        )
        cases.forEach { (message, expected) ->
            assertEquals("«$message»: окно покупки должно показать нужный шаг", expected, MillidaPaid.verdict(message))
        }
    }

    @Test
    fun `доступ и живая цена из ответа доступа`() {
        assertTrue(MillidaPaid.accessOpen(json("""{"hasAccess":true}""")))
        assertTrue("автор видит свою вещь открытой", MillidaPaid.accessOpen(json("""{"isOwner":true}""")))
        assertFalse(MillidaPaid.accessOpen(json("""{"hasAccess":"true"}""")))
        assertEquals(19900L, MillidaPaid.livePriceKopecks(json("""{"pricing":"ONE_TIME","priceKopecks":19900}""")))
        assertNull(MillidaPaid.livePriceKopecks(json("""{"pricing":"FREE","priceKopecks":19900}""")))
        assertEquals(100L, MillidaPaid.shortfallKopecks(300, 200))
        assertEquals(0L, MillidaPaid.shortfallKopecks(300, 500))
        assertNull("баланс неизвестен — решает сервер", MillidaPaid.shortfallKopecks(300, null))
    }

    @Test
    fun `куда можно отправить платить`() {
        val cases = listOf(
            "https://pay.antilopay.com/p/1" to true,
            "https://enot.io/pay/1" to true,
            "https://millida.net/profile?tab=wallet" to true,
            "http://enot.io/pay/1" to false,
            "https://enot.io.evil.example/pay" to false,
            "https://evil.example/enot.io" to false,
            "https://user@enot.io/pay" to false,
            "javascript:alert(1)" to false,
            "" to false,
        )
        cases.forEach { (url, expected) ->
            assertEquals("«$url»: подменённый ответ не должен увести на чужую страницу оплаты", expected, MillidaPaid.isPaymentUrl(url))
        }
    }

    @Test
    fun `мои покупки без возвратов, повторов и истёкших`() {
        val body = MojangJson.parseToJsonElement(
            """
            [
              {"id":"1","status":"PAID","kind":"ONE_TIME","amountKopecks":30000,"item":{"slug":"arcania","title":"Arcania","type":"MODPACK"}},
              {"id":"2","status":"PAID","kind":"ONE_TIME","item":{"slug":"arcania","title":"Arcania","type":"MODPACK"}},
              {"id":"3","status":"REFUNDED","item":{"slug":"refunded","title":"R","type":"MOD"}},
              {"id":"4","status":"PAID","kind":"SUBSCRIPTION","accessUntil":"2020-01-01T00:00:00Z","item":{"slug":"old","title":"O","type":"MODPACK"}},
              {"id":"5","status":"PAID","item":{"slug":"bundle","title":"B","type":"BUNDLE"}},
              {"id":"6","status":"PAID","kind":"SUBSCRIPTION","accessUntil":"2099-01-01T00:00:00Z","item":{"slug":"live","title":"L","type":"MODPACK"}}
            ]
            """.trimIndent(),
        )
        val rows = MillidaPaid.purchasesFrom(body, Instant.parse("2026-09-30T00:00:00Z"))
        assertEquals("одна строка на вещь, без возврата, подборки и истёкшей подписки", listOf("arcania", "live"), rows.map { it.slug })
        assertEquals("modpacks", rows.first().section)
    }

    @Test
    fun `ссылка продавца получает метку лаунчера`() {
        assertEquals(
            "https://shop.example/buy?x=1&utm_source=launcher&utm_medium=key_banner&utm_campaign=arcania",
            MillidaPaid.packBuyLink("https://shop.example/buy?x=1&utm_source=site", "arcania"),
        )
        assertNull("не https — не открываем", MillidaPaid.packBuyLink("http://shop.example/buy", "arcania"))
    }

    @Test
    fun `карточка сборки и её файл`() {
        assertEquals("Iris Shaders", MillidaCatalog.displayName("Iris Shaders — скачать мод на Fabric"))
        assertEquals("Zoomify", MillidaCatalog.displayName("Zoomify для Minecraft"))
        assertEquals("fabulously-optimized", MillidaCatalog.modrinthSlugOf("https://modrinth.com/modpack/fabulously-optimized"))
        assertNull("только Modrinth по https", MillidaCatalog.modrinthSlugOf("http://modrinth.com/modpack/fo"))
        assertNull(MillidaCatalog.modrinthSlugOf("https://modrinth.com.evil.example/modpack/fo"))
        val files = listOf(
            MillidaFile("a", gameVersions = listOf("1.21.4"), loaders = listOf("fabric")),
            MillidaFile("b", gameVersions = listOf("1.20.1"), loaders = listOf("forge"), mirrored = true),
            MillidaFile("c", gameVersions = listOf("1.20.1"), loaders = listOf("forge")),
        )
        assertEquals("ссылка сайта выбирает версию, зеркало — первым", "b", MillidaCatalog.modpackFile(files, listOf("1.20.1"), emptyList())?.id)
        assertEquals("без пожеланий — тот, что лежит у нас", "b", MillidaCatalog.modpackFile(files, emptyList(), emptyList())?.id)
    }

    @Test
    fun `витринная сборка встаёт первой`() {
        val page = listOf(MillidaCard("one", "One"), MillidaCard("two", "Two"))
        val premium = listOf(MillidaCard("arcania", "Arcania", premium = true), MillidaCard("two", "Two", premium = true))
        val pinned = MillidaCatalog.pinShowcase(page, premium, "")
        assertEquals(listOf("arcania", "one", "two"), pinned.map { it.slug })
        assertTrue("платная сборка на странице помечена", pinned.last().premium)
        assertEquals("поиск мимо витрины её не подмешивает", listOf("one", "two"), MillidaCatalog.pinShowcase(page, premium, "zzz").map { it.slug })
    }
}
