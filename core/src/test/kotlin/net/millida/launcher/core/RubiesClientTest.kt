package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.rubies.ChestGone
import net.millida.launcher.core.rubies.RubiesClient
import net.millida.launcher.core.rubies.RubiesError
import net.millida.launcher.core.rubies.ShopSource
import net.millida.launcher.core.rubies.TrackRow
import net.millida.launcher.core.rubies.WalletShort
import net.millida.launcher.core.rubies.isPaymentUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The shop spends real money and real rubies, so the pins are about what leaves
 * the phone and what the player is told.
 *
 * | вход                                          | вердикт                                             |
 * | --------------------------------------------- | --------------------------------------------------- |
 * | трек сезона с состояниями клеток и сундуками  | клетки, строки и «Мои сундуки» разобраны            |
 * | «Забрать» клетку 3 строки PLUS                | тело `{day:3,row:"plus"}` на /rubies/daily/claim    |
 * | открытие сундука                              | id закодирован, clientSeed ушёл, выпадение разобрано|
 * | 400 «Сундук уже открыт»                       | ChestGone: сундук убирается из списка               |
 * | покупка из примерки                           | source "wardrobe": цена базовая, а не витрины дня   |
 * | 400 insufficient_funds на пакете              | WalletShort: ведём пополнять счёт, а не «ошибка»    |
 * | докупка ровно: копейки дробные                | ответ разобран, баланс взят                         |
 * | ссылка оплаты на чужом хосте                  | отказ до браузера                                   |
 * | 401                                           | Unauthorized наружу: экран покажет вход             |
 */
class RubiesClientTest {

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

    @Test
    fun `трек сезона разбирается вместе с состояниями и сундуками`() = runBlocking {
        reply(
            """
            {"claimedToday":false,"streak":3,"cycleDay":3,"plus":false,
             "track":[
               {"day":1,"free":[{"kind":"SHARDS","amount":15}],"plus":[{"kind":"RUBIES","amount":100}],"freeState":"claimed","plusState":"locked"},
               {"day":2,"free":[{"kind":"CHEST","tier":"RARE"}],"plus":[{"kind":"FRAGMENTS","rarity":"RARE","amount":8,
                 "item":{"code":"HALO~gold","name":"Нимб","slot":"HAT","rarity":"RARE","preview":"https://cdn/p.png","variant":"gold","color":"f5c542","tintFrom":"8a55d8"}}],
                "freeState":"ready","plusState":"locked"}
             ],
             "season":{"id":3,"day":2,"cells":28,"endsAt":"2026-10-19T00:00:00.000Z","nextUnlockAt":"2026-09-26T00:00:00.000Z"},
             "chests":[{"id":"c1","tier":"EPIC","source":"DAILY","title":"Эпический","createdAt":"2026-09-24T10:00:00.000Z"}],
             "weekItem":null,"nextAt":"2026-09-26T00:00:00.000Z","unknownField":{"x":1}}
            """.trimIndent(),
        )

        val status = client().daily()

        assertEquals("все клетки дошли", 2, status.track.size)
        assertEquals("состояние бесплатной клетки взято у службы", "ready", status.track[1].freeState)
        assertEquals("закрытая клетка PLUS видна как locked", "locked", status.track[1].plusState)
        assertEquals("сундук клетки знает свой уровень", "RARE", status.track[1].free.single().tier)
        val frag = status.track[1].plus.single()
        assertEquals("цвет расцветки доезжает до перекраски превью", "f5c542", frag.item?.color)
        assertEquals("сезон: открыто клеток", 2, status.season?.day)
        assertEquals("«Мои сундуки» из ответа бонуса", "c1", status.chests.single().id)
        assertEquals("/v2/rubies/daily", server.takeRequest().path)
    }

    @Test
    fun `забор клетки PLUS уходит с днём и строкой`() = runBlocking {
        reply(
            """
            {"claimedToday":true,"streak":1,"track":[],"gained":0,"chestId":null,"balance":250,
             "granted":[{"kind":"CHEST","tier":"EPIC","source":"plus","day":3,"chestId":"c9"}]}
            """.trimIndent(),
        )

        val claim = client().claimDaily(3, TrackRow.PLUS)

        val request = server.takeRequest()
        assertEquals("/v2/rubies/daily/claim", request.path)
        assertEquals("POST", request.method)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("служба ждёт номер клетки числом", "3", body["day"]?.jsonPrimitive?.content)
        assertEquals("строка PLUS называется plus", "plus", body["row"]?.jsonPrimitive?.content)
        assertEquals("баланс после забора", 250L, claim.balance)
        assertEquals("сундук пришёл с id для «Открыть»", "c9", claim.granted.single().chestId)
    }

    @Test
    fun `открытие сундука отправляет сид и разбирает выпадение`() = runBlocking {
        reply(
            """
            {"chestId":"a b","tier":"EPIC","hits":5,"rarity":"EPIC","duplicate":false,"rubies":0,"shards":31,"pity":false,
             "pityLeft":{"epic":49,"legend":120},
             "drops":[{"kind":"SHARDS","amount":31},
                      {"kind":"FRAGMENTS","rarity":"EPIC","item":{"code":"WINGS","name":"Крылья","slot":"WINGS","previewUrl":"https://cdn/w.png"},
                       "variant":{"name":"gold","color":"f5c542","from":"8a55d8"},"amount":12,"have":40,"need":70,"completed":false,"shards":0}],
             "proof":{"serverSeedHash":"h","clientSeed":"seed-fixed","nonce":1,"rolledValue":0.5}}
            """.trimIndent(),
        )

        val result = client().openChest("a b")

        val request = server.takeRequest()
        assertEquals("id с пробелом кодируется, а не ломает путь", "/v2/rubies/chests/a%20b/open", request.path)
        assertTrue("сид игрока уходит в бросок", request.body.readUtf8().contains("seed-fixed"))
        assertEquals(2, result.drops.size)
        val slot = result.drops[1]
        assertEquals("превью слота берётся из previewUrl", "https://cdn/w.png", slot.item?.image)
        assertEquals("прогресс фрагментов после сундука", 40, slot.have)
        assertEquals("гарантия эпической", 49, result.pityLeft?.epic)
    }

    @Test
    fun `уже открытый сундук — ChestGone`() = runBlocking {
        reply("""{"statusCode":400,"message":"Сундук уже открыт","error":"Bad Request"}""", 400)

        try {
            client().openChest("c1")
            fail("открытый сундук не должен молча пройти")
        } catch (gone: ChestGone) {
            assertEquals("текст службы доходит до игрока", "Сундук уже открыт", gone.message)
        }
    }

    @Test
    fun `нехватка денег на пакете — WalletShort`() = runBlocking {
        reply(
            """{"statusCode":400,"status":"insufficient_funds","missingKopecks":5000,"message":"На балансе Millida не хватает денег — пополните кошелёк"}""",
            400,
        )

        try {
            client().buyPack("pouch")
            fail("нехватка денег должна вести к пополнению")
        } catch (short: WalletShort) {
            assertTrue(short.message.orEmpty().contains("пополни"))
        }
        val body = server.takeRequest().body.readUtf8()
        assertTrue("пакет называется кодом", body.contains("\"pack\":\"pouch\""))
    }

    @Test
    fun `докупка ровно разбирает дробные копейки`() = runBlocking {
        reply("""{"pack":"exact","rubies":10,"kopecks":142.85714285714286,"balance":510}""")

        val bought = client().topUp(10)

        assertEquals("баланс после докупки", 510L, bought.balance)
        assertEquals("начислено", 10L, bought.rubies)
    }

    @Test
    fun `покупка уходит с источником витрины`() = runBlocking {
        reply("""{"balance":300,"granted":[{"code":"HAT~red","name":"Шляпа","slot":"HAT","rarity":"RARE","preview":null}]}""")

        val result = client().buy("HAT~red", ShopSource.DEAL)

        val body = server.takeRequest().body.readUtf8()
        assertTrue("цена сверяется с полкой скидки", body.contains("\"source\":\"deal\""))
        assertEquals(300L, result.balance)
        assertEquals("HAT~red", result.granted.single().code)
    }

    @Test
    fun `покупка из примерки уходит с источником гардероба`() = runBlocking {
        reply("""{"balance":40,"granted":[{"code":"CAP~blue","name":"Кепка","slot":"HAT","rarity":"COMMON","preview":null}]}""")

        client().buy("CAP~blue", ShopSource.WARDROBE)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            "без источника wardrobe служба ищет вещь на витрине дня и отвечает «Этой вещи сейчас нет на витрине»",
            "wardrobe",
            body["source"]?.jsonPrimitive?.content,
        )
        assertEquals("код расцветки уходит целиком", "CAP~blue", body["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ссылка на оплату с чужим хостом не уходит в браузер`() = runBlocking {
        reply("""{"subscriptionId":"s1","paymentUrl":"https://antilopay.com.evil.io/pay/1"}""")

        try {
            client().subscribePlus()
            fail("поддельная ссылка оплаты не должна открываться")
        } catch (refused: RubiesError) {
            assertTrue(refused.message.orEmpty().contains("не прошла проверку"))
        }
    }

    @Test
    fun `проверка ссылки оплаты`() {
        val cases = listOf(
            "https://antilopay.com/pay/1" to true,
            "https://pay.antilopay.com/x" to true,
            "https://millida.net/profile#plus" to true,
            "http://antilopay.com/pay" to false,
            "https://antilopay.com.evil.io/pay" to false,
            "https://user@millida.net/" to false,
            "javascript:alert(1)" to false,
            "" to false,
        )
        for ((url, verdict) in cases) {
            assertEquals("ссылка $url", verdict, isPaymentUrl(url))
        }
        assertFalse(isPaymentUrl(null))
    }

    @Test
    fun `401 уходит наружу как Unauthorized`() = runBlocking {
        reply("""{"message":"Unauthorized"}""", 401)

        try {
            client().shop()
            fail("истёкший вход должен дойти до экрана")
        } catch (expired: Unauthorized) {
            assertTrue("экран отличает истёкший вход от ошибки витрины", expired.message.orEmpty().isNotEmpty())
        }
    }
}
