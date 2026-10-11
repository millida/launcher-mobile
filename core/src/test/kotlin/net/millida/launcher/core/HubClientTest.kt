package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.hub.Hub
import net.millida.launcher.core.hub.HubClient
import net.millida.launcher.core.hub.PopularVersion
import net.millida.launcher.core.hub.PresetLoader
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class HubClientTest {

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

    private fun hub(signedIn: Boolean = false): HubClient {
        val vault = MemoryVault()
        if (signedIn) vault.write(MillidaAuth.KEY_ACCESS, "token")
        return HubClient(MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/')))
    }

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test
    fun `режимы рейтинга просятся без сессии игрока`() {
        runBlocking {
            reply("""{"modes":[{"code":"BEDWARS","total":4,"online":900,"slugs":["a"]},{"code":"ONEBLOCK","online":10,"slugs":["oneblock-7"]}]}""")
            val modes = hub(signedIn = true).modes()
            val request = server.takeRequest()
            assertEquals("/v2/rating/modes", request.path)
            assertNull("рейтинг публичный: токен туда не отправляется", request.getHeader("Authorization"))
            assertEquals("свой ивент первым и после сети", listOf("ONEBLOCK", "BEDWARS"), modes.map { it.code })
            assertEquals("без total число серверов берётся из списка", 1, modes.first().total)
        }
    }

    @Test
    fun `здоровье сборки спрашивается по нормализованному слагу`() {
        runBlocking {
            reply("""{"packs":[{"slug":"arcania","status":"ok","launches":40,"successPct":97}]}""")
            val health = hub(signedIn = true).packHealth(" Arcania ")
            val request = server.takeRequest()
            assertEquals("слаг приводится к нижнему регистру и без пробелов", "/v2/launcher/pack-health?slugs=arcania", request.path)
            assertEquals("вошедший игрок идёт со своей сессией", "Bearer token", request.getHeader("Authorization"))
            assertEquals(97, health?.successPct)
            assertEquals(40, health?.launches)

            assertNull("пустой слаг — без запроса и без строки здоровья", hub().packHealth("   "))
            assertEquals("пустой слаг не тратит запрос", 1, server.requestCount)

            reply("""{"packs":[{"slug":"my pack","status":"warn"}]}""")
            hub().packHealth("My Pack")
            assertEquals("пробел в слаге кодируется", "/v2/launcher/pack-health?slugs=my+pack", server.takeRequest().path)
        }
    }

    private data class OnlineCase(val body: String, val expected: Int?, val why: String)

    @Test
    fun `онлайн своего сервера читается только живым числом`() {
        runBlocking {
            val cases = listOf(
                OnlineCase("""{"online":57}""", 57, "обычный ответ"),
                OnlineCase("""{"online":0}""", 0, "ноль — тоже знание"),
                OnlineCase("""{"online":-1}""", null, "отрицательный онлайн — мусор, не показываем"),
                OnlineCase("""{"online":"57"}""", null, "строка вместо числа не угадывается"),
                OnlineCase("""{"online":12.5}""", null, "дробный онлайн — не число игроков"),
                OnlineCase("""{}""", null, "поля нет"),
                OnlineCase("""[]""", null, "список вместо карточки"),
                OnlineCase("", null, "пустой ответ"),
            )
            val api = hub()
            cases.forEach { case ->
                reply(case.body)
                assertEquals("«${case.body}»: ${case.why}", case.expected, api.serverOnline("oneblock-7"))
                assertEquals("/v2/rating/servers/oneblock-7", server.takeRequest().path)
            }
        }
    }

    @Test
    fun `слаг с путём наружу до сети не доходит`() {
        runBlocking {
            val error = runCatching { hub().serverOnline("../admin") }.exceptionOrNull()
            assertTrue("подставной слаг должен отклоняться, получено $error", error != null)
            assertEquals("сеть не трогали", 0, server.requestCount)
        }
    }

    @Test
    fun `популярные версии — топ при пустом ответе, ошибка при сбое`() {
        runBlocking {
            reply("""{"versions":[{"mc":"1.21.1","loader":"neoforge"}]}""")
            assertEquals(listOf(PopularVersion("1.21.1", PresetLoader.NEOFORGE)), hub().popularVersions())
            assertEquals("/v2/launcher/popular-versions", server.takeRequest().path)

            reply("")
            assertEquals("пустой ответ — зашитый топ, как на компьютере", Hub.POPULAR_FALLBACK, hub().popularVersions())

            reply("", code = 500)
            val error = runCatching { hub().popularVersions() }.exceptionOrNull()
            assertTrue("сбой сервера не маскируется под топ — экран сам решает, что показать: $error", error is IOException)
        }
    }

    @Test
    fun `сбой рейтинга доходит ошибкой, а не пустым списком`() {
        runBlocking {
            reply("""{"message":"Рейтинг обновляется"}""", code = 503)
            val error = runCatching { hub().modes() }.exceptionOrNull()
            assertTrue("ожидалась IOException, получено $error", error is IOException)
            assertEquals("текст сервера сохраняется", "Рейтинг обновляется", error?.message)
        }
    }
}
