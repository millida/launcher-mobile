package net.millida.launcher.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.hosting.ConsoleLine
import net.millida.launcher.core.hosting.HostingError
import net.millida.launcher.core.hosting.ServerConsole
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostingConsoleTest {

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

    private fun console(token: String? = "test-token"): ServerConsole {
        val vault = MemoryVault()
        token?.let { vault.write(MillidaAuth.KEY_ACCESS, it) }
        val http = OkHttpClient()
        val base = server.url("/v2").toString().trimEnd('/')
        return ServerConsole(MillidaAuth(http, vault, base), vault, http, base)
    }

    private fun failureOf(block: suspend () -> Unit): Throwable? = runBlocking {
        try {
            block()
            null
        } catch (error: Throwable) {
            error
        }
    }

    @Test
    fun `поток консоли отделяет историю от живых строк`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    listOf(
                        ": connected",
                        "data: [12:00:00] Server started",
                        "",
                        "data:[12:00:01] Done (3.2s)",
                        ":replay-end",
                        "event: line",
                        "data: [12:01:00] Steve joined the game\r",
                        "data: ",
                        "data:",
                        "id: 17",
                        "data: data: двойной префикс",
                        "",
                    ).joinToString("\n"),
                ),
        )

        val lines = runBlocking { console().stream("srv-1").toList() }

        assertEquals(
            "строки консоли разобраны неверно — игрок видит служебные поля SSE или теряет вывод сервера",
            listOf(
                ConsoleLine("[12:00:00] Server started", true),
                ConsoleLine("[12:00:01] Done (3.2s)", true),
                ConsoleLine("[12:01:00] Steve joined the game", false),
                ConsoleLine("data: двойной префикс", false),
            ),
            lines,
        )
        val request = server.takeRequest()
        assertEquals("поток открыт не на том адресе", "/v2/hosting/servers/srv-1/console/stream", request.path)
        assertEquals("без токена нода не отдаст консоль", "Bearer test-token", request.getHeader("Authorization"))
        assertEquals("нода отдаёт поток только по Accept", "text/event-stream", request.getHeader("Accept"))
    }

    @Test
    fun `отказ ноды в потоке приходит словами`() {
        val cases = listOf(
            403 to "не хватает прав",
            404 to "уже удалено",
            503 to "обновляется",
        )
        for ((code, part) in cases) {
            server.enqueue(MockResponse().setResponseCode(code))
            val error = failureOf { console().stream("srv-1").toList() }
            assertTrue("код $code: ожидалась HostingError, пришло $error", error is HostingError)
            val text = error?.message.orEmpty()
            assertTrue("код $code: не сказано, что консоль недоступна: $text", text.startsWith("Консоль недоступна"))
            assertTrue("код $code: нет совета «$part»: $text", text.contains(part))
        }
    }

    @Test
    fun `без входа и с плохим id консоль не идёт в сеть`() {
        val noToken = failureOf { console(token = null).stream("srv-1").toList() }
        assertTrue("без сессии — ошибка хостинга, а не пустая консоль: $noToken", noToken is HostingError)
        assertTrue("без сессии игроку не сказано войти заново: ${noToken?.message}", noToken?.message.orEmpty().contains("войди"))

        val badId = failureOf { console().stream("../admin").toList() }
        assertTrue("id с выходом наружу принят: $badId", badId is HostingError)

        val emptyCommand = failureOf { console().send("srv-1", "   ") }
        assertTrue("пустая команда отправлена: $emptyCommand", emptyCommand is HostingError)
        assertTrue("пустая команда не объяснена: ${emptyCommand?.message}", emptyCommand?.message.orEmpty().contains("пустая"))

        assertEquals("ни одна отклонённая операция не должна уйти на ноду", 0, server.requestCount)
    }

    @Test
    fun `отклонённая команда не выдаётся за отправленную`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"Сервер выключен — запустите его"}"""))
        val refused = failureOf { console().send("srv-1", "say hi") }
        assertTrue("сбой отправки проглочен: $refused", refused is HostingError)
        assertEquals(
            "текст службы не дошёл до игрока",
            "Команда не ушла: Сервер выключен — запустите его",
            refused?.message,
        )

        server.enqueue(MockResponse().setResponseCode(401))
        val expired = failureOf { console().send("srv-1", "say hi") }
        assertTrue("протухшая сессия не объяснена: ${expired?.message}", expired?.message.orEmpty().contains("войди в аккаунт заново"))

        server.enqueue(MockResponse().setBody("{}"))
        val sent = runBlocking { console().send("srv-1", "  op Steve ") }
        assertEquals("подтверждение отправки", "Команда отправлена", sent.message)
        assertEquals("в подтверждении показана сама команда без пробелов", "op Steve", sent.detail)
        server.takeRequest()
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("команда ушла не методом POST", "POST", request.method)
        assertEquals("команда ушла не на эндпоинт консоли", "/v2/hosting/servers/srv-1/console/command", request.path)
    }
}
