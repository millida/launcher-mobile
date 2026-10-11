package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.portal.AccountSession
import net.millida.launcher.core.portal.PortalClient
import net.millida.launcher.core.portal.Privacy
import net.millida.launcher.core.portal.asRubles
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PortalClientTest {

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

    private fun portal(): PortalClient {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, "test-token")
        return PortalClient(MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/')))
    }

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test
    fun `профиль показывается и без кошелька`() {
        runBlocking {
            reply("""{"nickname":"Steve","avatarUrl":"https://a/1.png"}""")
            reply("""{"availableKopecks":123456}""")
            reply("""{"nickname":"Alex"}""")
            reply("""{"message":"кошелёк недоступен"}""", 503)
            val client = portal()

            val rich = client.profile()
            val poor = client.profile()

            assertEquals("/v2/users/me", server.takeRequest().path)
            assertEquals("/v2/core/wallet/me/display", server.takeRequest().path)
            assertEquals("Steve", rich.nickname)
            assertEquals("копейки кошелька доезжают целиком", 123456L, rich.balanceKopecks)
            assertEquals("упавший кошелёк не прячет профиль", "Alex", poor.nickname)
            assertEquals("баланс без кошелька — ноль, а не ошибка", 0L, poor.balanceKopecks)
        }
    }

    @Test
    fun `данные аккаунта переносятся все, включая Telegram под своими именами`() {
        runBlocking {
            reply(
                """{"nickname":"Steve","displayName":"Стив","email":"s@example.com","emailVerified":true,
                   "emailIsSynthetic":false,"twoFactorEnabled":false,"nicknameCanChange":false,
                   "nicknameNextChangeAt":"2026-11-01T00:00:00Z","nicknameCooldownDays":30,"publicSlug":"steve",
                   "avatarUrl":"https://a/1.png","discordLinked":true,"discordUsername":"steve#1",
                   "tgLinked":true,"tgUsername":"steve_tg","banned":false,"banReason":""}""",
            )

            val details = portal().details()

            assertEquals("Стив", details.displayName)
            assertTrue(details.emailVerified)
            assertFalse(details.emailIsSynthetic)
            assertFalse(details.twoFactorEnabled)
            assertFalse("запрет смены ника доходит до кнопки", details.nicknameCanChange)
            assertEquals("2026-11-01T00:00:00Z", details.nicknameNextChangeAt)
            assertEquals("срок до смены ника — число", 30, details.nicknameCooldownDays)
            assertTrue(details.discordLinked)
            assertEquals("steve#1", details.discordUsername)
            assertTrue("tgLinked сервера — это telegramLinked", details.telegramLinked)
            assertEquals("steve_tg", details.telegramUsername)
            assertFalse(details.banned)
        }
    }

    @Test
    fun `смена ника по умолчанию разрешена, а мусор в сроке — ноль`() {
        runBlocking {
            reply("""{"nickname":"Steve","nicknameCooldownDays":"скоро","banned":false}""")

            val details = portal().details()

            assertTrue("сервер молчит о запрете — кнопка смены ника доступна", details.nicknameCanChange)
            assertEquals("срок не числом — ноль, а не исключение", 0, details.nicknameCooldownDays)
        }
    }

    private data class FreeCase(val body: String, val free: Boolean, val why: String)

    @Test
    fun `проверка ника понимает все три формы ответа`() {
        runBlocking {
            val cases = listOf(
                FreeCase("""{"available":true}""", true, "форма available"),
                FreeCase("""{"available":false,"free":true}""", true, "форма free"),
                FreeCase("""{"available":false,"free":false,"status":"free"}""", true, "форма status"),
                FreeCase("""{"available":false,"free":false,"status":"taken"}""", false, "занятый ник не предлагается"),
            )
            val client = portal()
            for (case in cases) {
                reply(case.body)
                assertEquals("${case.body}: ${case.why}", case.free, client.nicknameFree("Мой ник"))
            }
            assertEquals(
                "ник уходит закодированным параметром, а не ломает адрес",
                "/v2/users/me/nickname/check?value=%D0%9C%D0%BE%D0%B9+%D0%BD%D0%B8%D0%BA",
                server.takeRequest().path,
            )
        }
    }

    @Test
    fun `смена ника отправляет ник и перечитывает аккаунт`() {
        runBlocking {
            reply("""{"ok":true}""")
            reply("""{"nickname":"NewSteve","banned":false}""")

            val details = portal().changeNickname("NewSteve")

            val change = server.takeRequest()
            assertEquals("/v2/users/me/nickname", change.path)
            assertEquals("NewSteve", Json.parseToJsonElement(change.body.readUtf8()).jsonObject["nickname"]?.jsonPrimitive?.content)
            assertEquals("после смены экран показывает ник с сервера", "/v2/users/me", server.takeRequest().path)
            assertEquals("NewSteve", details.nickname)
        }
    }

    @Test
    fun `ссылки привязки берутся из url или link`() {
        runBlocking {
            reply("""{"url":"https://discord.com/oauth"}""")
            reply("""{"link":"https://t.me/millida_bot?start=x"}""")
            val client = portal()

            assertEquals("https://discord.com/oauth", client.discordLinkUrl())
            assertEquals("старая форма ответа с link тоже открывается", "https://t.me/millida_bot?start=x", client.telegramLinkUrl())
            assertEquals("GET", server.takeRequest().method)
            val telegram = server.takeRequest()
            assertEquals("/v2/users/me/tg/link", telegram.path)
            assertEquals("ссылку Telegram выдаёт POST", "POST", telegram.method)
        }
    }

    private data class SessionsCase(val body: String, val ids: List<String>, val why: String)

    @Test
    fun `сессии читаются из любой обёртки ответа`() {
        runBlocking {
            val cases = listOf(
                SessionsCase("""[{"id":"a"}]""", listOf("a"), "голый список"),
                SessionsCase("""{"items":[{"id":"b"}]}""", listOf("b"), "обёртка items"),
                SessionsCase("""{"sessions":[{"id":"c"}]}""", listOf("c"), "обёртка sessions"),
                SessionsCase("""{"total":0}""", emptyList(), "ни одной обёртки — пусто, а не падение"),
            )
            val client = portal()
            for (case in cases) {
                reply(case.body)
                assertEquals("${case.body}: ${case.why}", case.ids, client.sessions().map { it.id })
            }
        }
    }

    @Test
    fun `сессия без своих полей берёт запасные`() {
        runBlocking {
            reply(
                """[{"id":"a","userAgent":"Android 14","ip":"1.2.3.4","updatedAt":"2026-10-01","current":true},
                   {"id":"b","device":"Pixel","place":"Москва","lastSeenAt":"2026-10-05","userAgent":"x","current":false}]""",
            )

            val sessions = portal().sessions()

            assertEquals(
                "без device/place/lastSeenAt показываются userAgent/ip/updatedAt; свои поля важнее запасных",
                listOf(
                    AccountSession("a", "Android 14", "1.2.3.4", "2026-10-01", current = true),
                    AccountSession("b", "Pixel", "Москва", "2026-10-05", current = false),
                ),
                sessions,
            )
        }
    }

    @Test
    fun `выход из сессий идёт нужным методом`() {
        runBlocking {
            reply("{}")
            reply("{}")
            val client = portal()

            client.revokeSession("s1")
            client.revokeOtherSessions()

            val one = server.takeRequest()
            assertEquals("/v2/users/me/sessions/s1", one.path)
            assertEquals("DELETE", one.method)
            val all = server.takeRequest()
            assertEquals("/v2/users/me/sessions/revoke-all", all.path)
            assertEquals("POST", all.method)
        }
    }

    private data class PrivacyCase(val body: String, val expected: Privacy, val why: String)

    @Test
    fun `приватность по умолчанию открыта, а приглашения только из списка`() {
        runBlocking {
            val cases = listOf(
                PrivacyCase("""{}""", Privacy(), "сервер молчит — всё видно, приглашения от всех"),
                PrivacyCase(
                    """{"showFriends":false,"showMarket":false,"duoInvites":"friends"}""",
                    Privacy(showFriends = false, showMarket = false, duoInvites = "friends"),
                    "закрытые пункты и приглашения от друзей",
                ),
                PrivacyCase("""{"duoInvites":"everyone"}""", Privacy(), "неизвестное значение приглашений — значение по умолчанию"),
                PrivacyCase("""{"showActivity":"нет"}""", Privacy(), "мусор в флаге не закрывает раздел"),
            )
            val client = portal()
            for (case in cases) {
                reply(case.body)
                assertEquals("${case.body}: ${case.why}", case.expected, client.privacy())
            }
        }
    }

    @Test
    fun `переключатель приватности отправляет одно поле PATCH`() {
        runBlocking {
            reply("""{"showPlaytime":false}""")

            val privacy = portal().updatePrivacy("showPlaytime", false)

            val request = server.takeRequest()
            assertEquals("/v2/users/me/privacy", request.path)
            assertEquals("PATCH", request.method)
            assertEquals("""{"showPlaytime":false}""", request.body.readUtf8())
            assertFalse("экран берёт состояние из ответа сервера", privacy.showPlaytime)
        }
    }

    @Test
    fun `неизвестное значение приглашений не уходит на сервер`() {
        runBlocking {
            val client = portal()

            val failed = runCatching { client.updateDuoInvites("everyone") }.exceptionOrNull()

            assertTrue("неверное значение — отказ до запроса", failed is IllegalArgumentException)
            assertEquals("ни одного запроса", 0, server.requestCount)

            reply("""{"duoInvites":"none"}""")
            assertEquals("none", client.updateDuoInvites("none").duoInvites)
            assertEquals("""{"duoInvites":"none"}""", server.takeRequest().body.readUtf8())
        }
    }

    @Test
    fun `список значений приглашений согласован с проверкой`() {
        for ((value, label) in Privacy.DUO_INVITES) {
            assertTrue("«$label» обязано проходить проверку", Privacy.duoInvitesValid(value))
        }
        assertTrue("значение по умолчанию — из списка", Privacy.duoInvitesValid(Privacy.DUO_INVITES_DEFAULT))
        assertFalse("регистр важен — сервер ждёт нижний", Privacy.duoInvitesValid("ALL"))
    }

    private data class RublesCase(val kopecks: Long, val text: String, val why: String)

    @Test
    fun `баланс в копейках пишется рублями`() {
        val cases = listOf(
            RublesCase(0, "0 ₽", "пустой кошелёк"),
            RublesCase(100, "1 ₽", "ровный рубль без копеек"),
            RublesCase(5, "0,05 ₽", "ведущий ноль копеек сохраняется"),
            RublesCase(123456, "1234,56 ₽", "копейки не теряются"),
        )
        for (case in cases) assertEquals("${case.kopecks} коп.: ${case.why}", case.text, case.kopecks.asRubles())
    }
}
