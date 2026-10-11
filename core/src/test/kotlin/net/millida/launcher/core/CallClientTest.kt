package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.call.CallClient
import net.millida.launcher.core.call.CallSignalKind
import net.millida.launcher.core.call.IceConfigs
import net.millida.launcher.core.call.VoiceMember
import net.millida.launcher.core.friends.FriendsError
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallClientTest {

    private lateinit var server: MockWebServer

    private var clock = 0L

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun auth(signedIn: Boolean = true): MillidaAuth {
        val vault = MemoryVault()
        if (signedIn) vault.write(MillidaAuth.KEY_ACCESS, "token")
        return MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/'))
    }

    private fun client(signedIn: Boolean = true) = CallClient(auth(signedIn)) { clock }

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun bodyOf(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val iceAnswer =
        """{"iceServers":[{"urls":["turn:turn.example:3478"],"username":"u","credential":"p"}],"ttlSeconds":3600}"""

    @Test
    fun `опрос звонков держит курсор и умеет не ждать`() {
        runBlocking {
            val api = client()
            reply("""{"cursor":42,"events":[{"seq":41,"callId":"abcdefgh","from":"u","kind":"invite","data":{},"ts":3}]}""")
            val inbox = api.poll(after = -5, hold = false)
            val request = server.takeRequest()
            assertEquals("отрицательный курсор не уходит на сервер, а без ожидания добавляется wait=0", "/v2/friends/call/poll?after=0&wait=0", request.path)
            assertEquals("GET", request.method)
            assertEquals("курсор сервера — то, с чего спросим дальше", 42L, inbox.cursor)
            assertEquals(CallSignalKind.INVITE, inbox.events.single().kind)

            reply("""{"events":[]}""")
            api.poll(after = 42, hold = true)
            assertEquals("долгий опрос — без wait, сервер держит запрос до конверта", "/v2/friends/call/poll?after=42", server.takeRequest().path)
        }
    }

    @Test
    fun `сигнал уходит телом по DTO сервера`() {
        runBlocking {
            reply("{}")
            client().signal("abcdef012345", "peer", CallSignalKind.END, seconds = 90)
            val request = server.takeRequest()
            assertEquals("/v2/friends/call/signal", request.path)
            assertEquals("POST", request.method)
            val body = bodyOf(request.body.readUtf8())
            assertEquals("end", body["kind"]?.jsonPrimitive?.content)
            assertEquals("peer", body["peerId"]?.jsonPrimitive?.content)
            assertEquals("длительность пишет итог звонка в чат", "90", body["seconds"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `логины ретранслятора берутся один раз и обновляются до истечения`() {
        runBlocking {
            val api = client()
            reply(iceAnswer)
            val first = api.ice()
            assertEquals("turn:turn.example:3478", first.servers.single().urls.single())
            clock = 3_299_000L
            api.ice()
            assertEquals("в пределах часа логин переиспользуется, а не просится на каждый звонок", 1, server.requestCount)
            assertEquals("/v2/friends/call/ice", server.takeRequest().path)

            clock = 3_300_001L
            reply(iceAnswer)
            api.ice()
            assertEquals("за пять минут до конца срока логин берётся заново, иначе он истечёт посреди звонка", 2, server.requestCount)
        }
    }

    @Test
    fun `без ответа о ретрансляторах звонок идёт через STUN и не запоминает сбой`() {
        runBlocking {
            val api = client()
            reply("", code = 500)
            assertEquals("сбой сервера — запасной STUN, а не тихий звонок", IceConfigs.FALLBACK, api.ice())
            reply("""{"iceServers":[]}""")
            assertEquals("пустой список — тоже запасной вариант", IceConfigs.FALLBACK, api.ice())
            reply("[]")
            assertEquals("список вместо объекта — запасной вариант", IceConfigs.FALLBACK, api.ice())
            reply(iceAnswer)
            assertEquals("запасной вариант не кешируется: следующий звонок снова спрашивает сервер", "u", api.ice().servers.single().username)
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun `голос группы читает участников без мусора и повторов`() {
        runBlocking {
            reply(
                """{"me":"u0","members":[
                    {"userId":"u1","since":5,"muted":true},
                    {"userId":"u1","since":9},
                    {"userId":"   "},
                    {"since":3},
                    "junk",
                    {"userId":"u2","screen":"true","since":"7"}
                ]}""",
            )
            val voice = client().joinVoice("room 1")
            val request = server.takeRequest()
            assertEquals("пробел в id кодируется как %20, а не плюсом", "/v2/friends/rooms/room%201/voice/join", request.path)
            assertEquals("POST", request.method)
            assertEquals("u0", voice.me)
            assertEquals(
                "повтор, пустой id и не-объект отброшены; строка вместо флага или числа — не значение",
                listOf(VoiceMember("u1", 5L, muted = true, screen = false), VoiceMember("u2", 0L, muted = false, screen = false)),
                voice.members,
            )
        }
    }

    @Test
    fun `удар голоса передаёт микрофон и звук`() {
        runBlocking {
            reply("""{"me":"u0"}""")
            val answer = client().beatVoice("r1", muted = true, deafened = false)
            val body = bodyOf(server.takeRequest().body.readUtf8())
            assertEquals("true", body["muted"]?.jsonPrimitive?.content)
            assertEquals("false", body["deafened"]?.jsonPrimitive?.content)
            assertEquals("телефон не показывает экран", "false", body["screen"]?.jsonPrimitive?.content)
            assertTrue("без members — пустой состав, а не падение", answer.members.isEmpty())
        }
    }

    @Test
    fun `позвать группу возвращает число позванных`() {
        runBlocking {
            val api = client()
            reply("""{"called":3}""")
            assertEquals(3, api.ringRoom("r1"))
            assertEquals("/v2/friends/rooms/r1/voice/ring", server.takeRequest().path)
            reply("{}")
            assertEquals("нет поля — все уже внутри", 0, api.ringRoom("r1"))
            reply("""{"called":"3"}""")
            assertEquals("строка вместо числа не считается", 0, api.ringRoom("r1"))
        }
    }

    @Test
    fun `подставная группа до сети не доходит`() {
        runBlocking {
            val api = client()
            listOf("../admin", "a/b", "  ", "").forEach { id ->
                val error = runCatching { api.joinVoice(id) }.exceptionOrNull()
                assertTrue("группа «$id» должна отклоняться как FriendsError, получено $error", error is FriendsError)
                assertEquals("Неверная группа", error?.message)
            }
            assertEquals("сеть не трогали", 0, server.requestCount)
        }
    }

    private data class ErrorCase(val code: Int, val body: String, val expected: String, val why: String)

    @Test
    fun `отказы голоса доходят словами`() {
        runBlocking {
            val api = client()
            val cases = listOf(
                ErrorCase(500, "", "Сбой на стороне Millida — повтори через минуту", "голый 500 объясняется"),
                ErrorCase(404, """{"message":"Not Found"}""", "Не удалось выйти из разговора", "шум заменяется смыслом действия"),
                ErrorCase(403, """{"message":"Ты не в этой группе"}""", "Ты не в этой группе", "объяснение сервера показывается как есть"),
                ErrorCase(401, "", "Сессия Millida закончилась — войди в аккаунт заново", "истёкшая сессия зовёт войти"),
            )
            cases.forEach { case ->
                reply(case.body, code = case.code)
                val error = runCatching { api.leaveVoice("r1") }.exceptionOrNull()
                assertTrue("${case.code}: ожидалась FriendsError, получено $error", error is FriendsError)
                assertEquals("${case.code}: ${case.why}", case.expected, error?.message)
            }
        }
    }

    @Test
    fun `без входа звонки не опрашиваются`() {
        runBlocking {
            val error = runCatching { client(signedIn = false).poll(0, hold = false) }.exceptionOrNull()
            assertEquals("Сессия Millida закончилась — войди в аккаунт заново", error?.message)
            assertEquals("без токена запрос не уходит", 0, server.requestCount)
        }
    }
}
