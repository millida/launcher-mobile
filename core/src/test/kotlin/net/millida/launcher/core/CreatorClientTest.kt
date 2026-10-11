package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.rubies.CreatorClient
import net.millida.launcher.core.rubies.CreatorError
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CreatorClientTest {

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

    private fun creators(): CreatorClient {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, "test-token")
        return CreatorClient(MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/')))
    }

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test
    fun `карточка автора ищется открыто по нормализованному коду`() {
        runBlocking {
            reply("""{"code":"MILLI","name":"Милли","avatarUrl":"https://a/m.png"}""")
            reply("""{"code":"MY CODE","name":"Автор"}""")
            val client = creators()

            val milli = client.lookup("  milli ")
            client.lookup("my code")

            val first = server.takeRequest()
            assertEquals("код приводится к верхнему регистру до запроса", "/v2/creator-codes/public/MILLI", first.path)
            assertNull("публичная карточка не привязывается к сессии игрока", first.getHeader("Authorization"))
            assertEquals("Милли", milli.name)
            assertEquals("пробел кодируется %20, а не плюсом", "/v2/creator-codes/public/MY%20CODE", server.takeRequest().path)
        }
    }

    @Test
    fun `пустой код не уходит в сеть`() {
        runBlocking {
            val client = creators()

            val set = runCatching { client.set("   ", live = true) }.exceptionOrNull()
            val lookup = runCatching { client.lookup("") }.exceptionOrNull()
            val long = runCatching { client.lookup("A".repeat(CreatorClient.MAX_CODE + 1)) }.exceptionOrNull()

            for (error in listOf(set, lookup, long)) {
                assertTrue("пустой или длинный код — CreatorError: $error", error is CreatorError)
                assertEquals("Введи код автора", error!!.message)
                assertFalse("это ошибка ввода, а не отсутствие адреса", (error as CreatorError).missingRoute)
            }
            assertEquals("ни одного запроса", 0, server.requestCount)
        }
    }

    @Test
    fun `неизвестный код при живой службе — «Код не найден»`() {
        runBlocking {
            reply("""{"statusCode":404,"message":"Not Found"}""", 404)

            val error = runCatching { creators().lookup("NOPE") }.exceptionOrNull()

            assertTrue(error is CreatorError)
            assertEquals("служба отвечает — 404 значит нет такого кода", "Код не найден", error!!.message)
        }
    }

    @Test
    fun `сброс кода без адреса на службе отвечает «скоро»`() {
        runBlocking {
            reply("""{"statusCode":404,"message":"Cannot DELETE /v2/launcher/creator-code","error":"Not Found"}""", 404)

            val error = runCatching { creators().clear() }.exceptionOrNull()

            val request = server.takeRequest()
            assertEquals("/v2/launcher/creator-code", request.path)
            assertEquals("DELETE", request.method)
            assertTrue(error is CreatorError)
            assertEquals(CreatorClient.SOON, error!!.message)
            assertTrue("нет адреса — баннер переходит в режим «скоро»", (error as CreatorError).missingRoute)
        }
    }

    @Test
    fun `истёкшая сессия не маскируется под ошибку кода`() {
        runBlocking {
            reply("""{"message":"Unauthorized"}""", 401)
            reply("""{"message":"Unauthorized"}""", 401)
            val client = creators()

            val load = runCatching { client.load() }.exceptionOrNull()
            val set = runCatching { client.set("MILLI", live = true) }.exceptionOrNull()

            assertTrue("load: 401 — вход заново, а не «не получилось»: $load", load is Unauthorized)
            assertTrue("set: 401 — вход заново, а не «не получилось»: $set", set is Unauthorized)
        }
    }

    @Test
    fun `нечитаемый ответ службы — общий текст, без технических подробностей`() {
        runBlocking {
            reply("""{"code":{"nested":true}}""")
            reply("""{"code":"MILLI","bonus":"сундук"}""")
            val client = creators()

            val load = runCatching { client.load() }.exceptionOrNull()
            val set = runCatching { client.set("MILLI", live = true) }.exceptionOrNull()

            for (error in listOf(load, set)) {
                assertTrue("ответ не той формы — CreatorError: $error", error is CreatorError)
                assertEquals("Не получилось, попробуй позже", error!!.message)
                assertFalse((error as CreatorError).missingRoute)
            }
        }
    }

    @Test
    fun `поддерживаемый автор приходит целиком`() {
        runBlocking {
            reply("""{"code":"MILLI","name":"Милли","avatarUrl":null,"since":"2026-09-24T10:00:00Z"}""")

            val status = creators().load()

            assertTrue(status.live)
            assertEquals("MILLI", status.current?.code)
            assertEquals("2026-09-24T10:00:00Z", status.current?.since)
            assertNull(status.current?.avatarUrl)
        }
    }
}
