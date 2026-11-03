package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.rubies.CaseMode
import net.millida.launcher.core.rubies.DiscordReward
import net.millida.launcher.core.rubies.PlusStatus
import net.millida.launcher.core.rubies.RubiesClient
import net.millida.launcher.core.rubies.RubiesError
import net.millida.launcher.core.rubies.SocialStep
import net.millida.launcher.core.rubies.TelegramReward
import net.millida.launcher.core.rubies.discordInvite
import net.millida.launcher.core.rubies.discordStep
import net.millida.launcher.core.rubies.freshRequestId
import net.millida.launcher.core.rubies.telegramLink
import net.millida.launcher.core.rubies.telegramStep
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The desktop shop feed's addresses as the phone reads them. Cases and sets
 * spend rubies, so the pins are about what leaves the phone and what is shown.
 *
 * | вход                                              | вердикт                                                      |
 * | ------------------------------------------------- | ------------------------------------------------------------ |
 * | /rubies/cases с ×10, гарантией и бесплатным       | ×10 и гарантия разобраны; бесплатный — на ящике дня          |
 * | открытие ×10 / бесплатно / одного                 | count:10, free:true или ничего; ключ запроса ушёл            |
 * | ответ открытия старой службы (без opens)          | один ящик собран из плоских полей, награда не теряется       |
 * | покупка набора                                    | цена, которую видел игрок, уходит как expect                 |
 * | сундуки за игру: nextInS null                     | «на сегодня всё», а не ноль секунд                           |
 * | /me/money чужой формы                             | RubiesError, а не нулевой баланс                             |
 * | PLUS: тариф DIAMOND                               | tier уходит в теле; неизвестный тариф не уходит вовсе        |
 * | предложение месяца и тариф в магазине             | разобраны из /rubies/shop                                    |
 * | ссылка привязки Discord на чужом хосте            | отказ до браузера                                            |
 * | лимиты Милли без строки PLUS                      | «не знаем» — экран PLUS не обещает чисел, которых нет        |
 */
class ShopFeedClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun client(): RubiesClient {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, "token")
        val auth = MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/'))
        return RubiesClient(auth, seed = { "seed-fixed" })
    }

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun sentBody() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test
    fun `ящики разбираются с десяткой, гарантией и бесплатным первым`() = runBlocking {
        reply(
            """
            {"cases":[
              {"id":"flame","title":"Пламя","price":690,"basePrice":690,"ofDay":false,"color":"FF6A1A","odds":[],"pityLeft":3,"pity":15,
               "pityFrom":"EPIC","left":12,"total":20,"cover":[],"sets":["Огонь"],"freeAvailable":true,
               "guarantee":{"minRarity":"RARE","label":"Редкая+ в каждом ящике"},
               "multi":{"count":10,"price":6210,"basePrice":6900,"floor":"EPIC"}},
              {"id":"dark","title":"Тьма","price":483,"basePrice":690,"ofDay":true,"color":"8A55D8","odds":[],"pityLeft":1,"pity":15,
               "pityFrom":"EPIC","left":0,"total":20,"cover":[],"sets":[],"freeAvailable":true,"multi":null,"currency":"shards"}
             ],
             "drops":[{"nick":"Steve","item":{"code":"WINGS","name":"Крылья","slot":"WINGS","rarity":"EPIC","preview":null},"caseId":"flame","at":"2026-10-23T10:00:00Z"}],
             "refreshAt":"2026-10-24T21:00:00Z","ofDayPct":30}
            """.trimIndent(),
        )

        val day = client().cases()

        val flame = day.cases[0]
        assertEquals("×10 — одно списание за девять цен", 6210L, flame.priceOf(CaseMode.TEN))
        assertEquals("бесплатно — ноль, а не цена ящика", 0L, flame.priceOf(CaseMode.FREE))
        assertEquals("гарантия ящика доезжает до чипа", "RARE", flame.guarantee?.minRarity)
        assertNull("у ящика без десятки кнопки ×10 нет", day.cases[1].multi)
        assertTrue("осколочный ящик платится осколками", day.cases[1].paidInShards)
        assertTrue("вещи темы кончились — ящик закрыт", day.cases[1].exhausted)
        assertEquals("бесплатный первый — на ящике дня, если он подходит", "dark", day.freeCaseId)
        assertEquals("лента выпадений разобрана", "Steve", day.drops.single().nick)
        assertEquals("/v2/rubies/cases", server.takeRequest().path)
    }

    @Test
    fun `открытие ящика уходит со способом и ключом`() = runBlocking {
        val cases = listOf(
            CaseMode.TEN to "count",
            CaseMode.FREE to "free",
            CaseMode.ONE to null,
        )
        for ((mode, flag) in cases) {
            reply("""{"balance":10,"top":false,"pityLeft":4}""")
            client().openCase("flame", "key-1", mode)
            val body = sentBody()
            assertEquals("ключ запроса один на открытие: повтор не спишет дважды ($mode)", "key-1", body["requestId"]?.jsonPrimitive?.content)
            assertEquals("ящик назван кодом ($mode)", "flame", body["caseId"]?.jsonPrimitive?.content)
            when (flag) {
                "count" -> assertEquals("×10 — count 10", "10", body["count"]?.jsonPrimitive?.content)
                "free" -> assertEquals("бесплатный — free:true", "true", body["free"]?.jsonPrimitive?.content)
                else -> assertTrue("обычное открытие без лишних полей ($mode)", "count" !in body && "free" !in body)
            }
        }
    }

    @Test
    fun `ответ старой службы без opens даёт один ящик`() = runBlocking {
        reply(
            """{"balance":300,"kind":"item","item":{"code":"HAT","name":"Шляпа","slot":"HAT","rarity":"RARE","preview":null},"top":true,"pityLeft":15}""",
        )

        val opened = client().openCase("flame", "k", CaseMode.ONE)

        val one = opened.openList.single()
        assertEquals("вещь из плоского ответа не теряется", "HAT", one.rewards.single().item?.code)
        assertEquals("редкость главной награды", "RARE", one.rarity)
        assertTrue("сброс гарантии доезжает", one.top)
    }

    @Test
    fun `покупка набора отправляет увиденную цену`() = runBlocking {
        reply(
            """
            {"sets":[{"id":"dragon","title":"Дракон","theme":"flame","ofDay":true,"colorways":[
              {"name":"","color":null,"items":[{"item":{"code":"W","name":"Крылья","slot":"WINGS","rarity":"EPIC","preview":null},"price":900,"owned":true},
                                                {"item":{"code":"H","name":"Шлем","slot":"HAT","rarity":"RARE","preview":null},"price":400,"owned":false}],
               "fullPrice":1300,"price":360,"discountPct":10,"have":1},
              {"name":"gold","color":"F5C542","items":[{"item":{"code":"W~gold","name":"Крылья","slot":"WINGS","rarity":"EPIC","preview":null},"price":900,"owned":false}],
               "fullPrice":900,"price":810,"discountPct":10,"have":0}]}],
             "refreshAt":"2026-10-24T21:00:00Z","plusTier":null}
            """.trimIndent(),
        )
        val sets = client().sets()
        server.takeRequest()
        val dragon = sets.sets.single()
        assertEquals("сразу показана расцветка, где у игрока больше вещей", "", dragon.defaultWay?.name)
        assertFalse("набор не весь свой — кнопка цены, не «Надеть»", dragon.colorways[0].complete)

        reply("""{"balance":40,"price":360,"granted":[]}""")
        client().buySet("dragon", "", 360)

        val body = sentBody()
        assertEquals("изменилась цена — служба откажет, а не спишет другую", "360", body["expect"]?.jsonPrimitive?.content)
        assertEquals("dragon", body["setId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `сундуки за игру без следующего на сегодня`() = runBlocking {
        reply("""{"earned":3,"claimed":3,"ready":0,"limit":3,"nextInS":null,"chest":"Обычный"}""")

        val play = client().playChests()

        assertNull("null — на сегодня всё, а не «через 0 минут»", play.nextInS)
        assertEquals(3, play.limit)

        reply("""{"earned":1,"claimed":1,"ready":0,"limit":3,"nextInS":1200,"chest":"Обычный","chestId":"c7"}""")
        val claim = client().claimPlayChest()
        assertEquals("забранный сундук открывается по своему id", "c7", claim.chestId)
        assertEquals(1200L, claim.view.nextInS)
    }

    @Test
    fun `кошелёк чужой формы — ошибка, а не ноль`() = runBlocking {
        reply("""{"balance":5}""")
        try {
            client().money()
            fail("непонятный ответ не должен стать нулевым балансом")
        } catch (refused: RubiesError) {
            assertTrue(refused.message.orEmpty().isNotEmpty())
        }

        reply(
            """{"region":"RU","displayCurrency":"RUB","balance":{"kopecks":12345,"displayMinor":12345,"displayCurrency":"RUB","rate":1},"rubPerUsd":92.5,"topup":{}}""",
        )
        assertEquals("копейки счёта", 12345L, client().money().balance.kopecks)
    }

    @Test
    fun `PLUS уходит с тарифом`() = runBlocking {
        reply("""{"subscriptionId":"s1","paymentUrl":"https://antilopay.com/pay/1"}""")

        client().subscribePlus("DIAMOND")

        assertEquals("тариф в теле: иначе служба оформит PLUS вместо Diamond", "DIAMOND", sentBody()["tier"]?.jsonPrimitive?.content)
        try {
            client().subscribePlus("GOLD")
            fail("неизвестный тариф не должен уходить")
        } catch (refused: RubiesError) {
            assertEquals("запроса не было", 1, server.requestCount)
        }
    }

    @Test
    fun `предложение месяца и тариф из магазина`() = runBlocking {
        reply(
            """
            {"balance":10,"shards":5,"plus":true,"day":{"featured":null,"deal":null,"items":[],"forYou":[],"refreshAt":""},
             "packs":[{"code":"starter","title":"Стартовый","rubies":500,"kopecks":9900,"oneTime":true}],
             "monthOffer":{"code":"month-2026-10","title":"Октябрь","rubies":3200,"kopecks":99000,"oneTime":true,"endsAt":"2026-10-31T21:00:00Z"},
             "plusTier":"DIAMOND","plusPct":15,"wishlist":[]}
            """.trimIndent(),
        )

        val day = client().shop()

        assertEquals("предложение месяца", 3200L, day.monthOffer?.rubies)
        assertEquals("тариф подписки виден магазину", "DIAMOND", day.plusTier)
        assertTrue("разовый пакет отмечен", day.packs.single().oneTime)
    }

    @Test
    fun `уровень подписки`() {
        val cases = listOf(
            PlusStatus(active = false, tier = "DIAMOND") to null,
            PlusStatus(active = true, tier = null) to "PLUS",
            PlusStatus(active = true, tier = "diamond") to "DIAMOND",
            PlusStatus(active = true, tier = " ") to "PLUS",
        )
        for ((status, verdict) in cases) {
            assertEquals("служба без tier — это PLUS; без подписки — ничего ($status)", verdict, status.paidTier)
        }
    }

    @Test
    fun `шаги наград Telegram и Discord`() {
        val telegram = listOf(
            null to SocialStep.HIDDEN,
            TelegramReward(available = true, amount = 50, claimed = true) to SocialStep.HIDDEN,
            TelegramReward(available = false, amount = 50) to SocialStep.HIDDEN,
            TelegramReward(available = true, amount = 0) to SocialStep.HIDDEN,
            TelegramReward(available = true, amount = 50, linked = false) to SocialStep.LINK,
            TelegramReward(available = true, amount = 50, linked = true) to SocialStep.JOIN,
        )
        for ((view, step) in telegram) assertEquals("Telegram: $view", step, telegramStep(view))
        assertEquals("забранная награда Discord прячется", SocialStep.HIDDEN, discordStep(DiscordReward(available = true, amount = 50, claimed = true)))
        assertEquals(SocialStep.LINK, discordStep(DiscordReward(available = true, amount = 50)))
    }

    @Test
    fun `ссылки наград только на свои хосты`() {
        val cases = listOf(
            telegramLink("https://t.me/millida") to "https://t.me/millida",
            telegramLink("http://t.me/millida") to null,
            telegramLink("https://t.me.evil.io/x") to null,
            telegramLink(null) to null,
            discordInvite("https://discord.gg/millida") to "https://discord.gg/millida",
            discordInvite("https://discord.com/invite/x") to "https://discord.com/invite/x",
            discordInvite("https://user@discord.gg/x") to null,
            discordInvite("javascript:alert(1)") to null,
        )
        for ((got, want) in cases) assertEquals("ссылка открывается в браузере только на своём хосте", want, got)
    }

    @Test
    fun `привязка Discord на чужом хосте не открывается`() = runBlocking {
        reply("""{"url":"https://discord.com.evil.io/oauth"}""")

        try {
            client().discordLinkUrl()
            fail("поддельная ссылка привязки не должна открываться")
        } catch (refused: RubiesError) {
            assertTrue(refused.message.orEmpty().contains("Discord"))
        }
    }

    @Test
    fun `ключ открытия каждый раз новый`() {
        val keys = List(20) { freshRequestId() }
        assertEquals("повторный ключ склеил бы два разных открытия в одно", keys.size, keys.toSet().size)
        assertTrue("ключ проходит проверку кода", keys.all { it.length == 32 })
    }

    @Test
    fun `лимиты Милли для экрана PLUS`() = runBlocking {
        reply("""{"free":{"day":3,"month":30},"plus":{"day":20,"month":300},"plusExtras":["shaders","server"],"plusModes":[]}""")
        val limits = client().milliLimits()
        assertTrue("строка PLUS пришла — числа показываются", limits.known)
        assertEquals(20, limits.plus.day)
        assertEquals("/v2/catalog/milli/limits", server.takeRequest().path)

        reply("""{"free":{"day":3,"month":30}}""")
        assertFalse("без строки PLUS экран не обещает чисел", client().milliLimits().known)
    }
}
