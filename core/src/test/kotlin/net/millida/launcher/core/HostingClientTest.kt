package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.hosting.HostingClient
import net.millida.launcher.core.hosting.HostingError
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The hosting panel on a phone changes a real server, so the pins here are
 * about honesty rather than shape: a refusal has to arrive as a sentence the
 * player can act on, a path that escapes the server folder must never leave the
 * phone, and a console command must land on the console endpoint and nowhere
 * else.
 *
 * | вход                                  | вердикт                                   |
 * | ------------------------------------- | ----------------------------------------- |
 * | список серверов + состояние            | поля разобраны, RUNNING виден как running |
 * | 403 без тела                           | текст про отказ в правах, не «403»        |
 * | путь `../../etc/passwd`                | HostingError, запроса не было             |
 * | команда консоли                        | POST .../console/command с полем command  |
 * | скачать мир                            | export -> опрос job до ready -> download  |
 * | сборка упала / забыта / 409 на export  | слова игроку, download не запрашивается   |
 */
class HostingClientTest {

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

    private fun client(on: MockWebServer = server): HostingClient {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, "test-token")
        val http = OkHttpClient()
        val base = on.url("/v2").toString().trimEnd('/')
        return HostingClient(MillidaAuth(http, vault, base), vault, http, base, exportPollMs = 1)
    }

    private fun worldPiece(): MockResponse =
        MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-4/5").setBody("world")

    @Test
    fun `список серверов и состояние разбираются в карточки`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {"id":"srv-1","name":"Выживание","status":"RUNNING","address":"mc.millida.net:25571",
                   "planName":"Старт","planRamMb":4096,"ramMb":1700,"playersOnline":3,
                   "planMaxPlayers":20,"planPriceKopecks":29900,"expiresAt":"2026-10-01T00:00:00Z",
                   "pendingRestart":["motd"]},
                  {"id":"srv-2","name":"Тест","status":"STOPPED","ip":"mc.millida.net:25572"}
                ]
                """.trimIndent()
            )
        )

        val list = client().servers()

        assertEquals("серверов двое", 2, list.size)
        val first = list[0]
        assertEquals("id взят как есть", "srv-1", first.id)
        assertEquals("адрес с поля address", "mc.millida.net:25571", first.address)
        assertEquals("память тарифа разобрана", 4096, first.planRamMb)
        assertEquals("онлайн разобран", 3, first.playersOnline)
        assertEquals("цена в копейках разобрана", 29900L, first.planPriceKopecks)
        assertEquals("ждущие перезапуска настройки видны", listOf("motd"), first.pendingRestart)
        assertTrue("RUNNING означает работает", first.running)
        assertFalse("платный тариф не бесплатный", first.free)
        assertEquals("адрес берётся из ip, когда address пуст", "mc.millida.net:25572", list[1].address)
        assertFalse("STOPPED не работает", list[1].running)
    }

    @Test
    fun `отказ 403 доходит до игрока словами, а не кодом`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))

        val error = runCatching { client().stop("srv-1") }.exceptionOrNull()

        assertTrue("остановка обязана сообщить об ошибке, а не промолчать", error is HostingError)
        val text = error?.message.orEmpty()
        assertTrue("в тексте сказано, что именно не вышло: $text", text.contains("Не удалось остановить сервер"))
        assertTrue("в тексте сказано про отказ в правах: $text", text.contains("отказала"))
        assertFalse("голый код игроку ничего не говорит: $text", text.trim().endsWith("403"))
    }

    @Test
    fun `путь с двумя точками отклоняется до запроса`() = runBlocking {
        val hosting = client()

        val read = runCatching { hosting.readFile("srv-1", "../../etc/passwd") }.exceptionOrNull()
        val write = runCatching { hosting.writeFile("srv-1", "plugins/../../secrets.yml", "x") }.exceptionOrNull()

        assertTrue("чтение по пути наружу должно отказать", read is HostingError)
        assertTrue("запись по пути наружу должна отказать", write is HostingError)
        assertTrue("причина названа: ${read?.message}", read?.message.orEmpty().contains("отклонён"))
        assertEquals("ни один такой путь не должен уйти в сеть", 0, server.requestCount)
    }

    @Test
    fun `команда консоли уходит на эндпоинт консоли`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))

        val result = client().sendCommand("srv-1", "  say привет  ")

        val request = server.takeRequest()
        assertEquals("метод POST", "POST", request.method)
        assertEquals("адрес консоли", "/v2/hosting/servers/srv-1/console/command", request.path)
        val body = request.body.readUtf8()
        assertTrue("команда уходит в поле command: $body", body.contains("\"command\":\"say привет\""))
        assertEquals("игрок видит подтверждение", "Команда отправлена", result.message)
    }

    @Test
    fun `мир качается только после того, как нода собрала архив`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"state":"running"}"""))
        server.enqueue(MockResponse().setBody("""{"state":"running","error":"","sizeBytes":0}"""))
        server.enqueue(MockResponse().setBody("""{"state":"ready","error":"","sizeBytes":5}"""))
        server.enqueue(worldPiece())
        val sink = ByteArrayOutputStream()
        var packing = 0

        val received = client().downloadWorldTo("srv-1", sink, onPacking = { packing += 1 })

        val calls = List(4) { server.takeRequest() }
        assertEquals(
            "без POST export бэкенд отвечает 409 «Архив мира ещё не собран», поэтому порядок — сборка, опрос, скачивание",
            listOf(
                "POST /v2/hosting/servers/srv-1/world/export",
                "GET /v2/hosting/servers/srv-1/world/export/job",
                "GET /v2/hosting/servers/srv-1/world/export/job",
                "GET /v2/hosting/servers/srv-1/world/download",
            ),
            calls.map { "${it.method} ${it.path}" },
        )
        assertTrue(
            "архив качается кусками по Range, иначе щит обрывает запрос на второй минуте",
            calls[3].getHeader("Range").orEmpty().startsWith("bytes=0-"),
        )
        assertEquals("все байты архива дошли до файла на телефоне", "world", sink.toString("UTF-8"))
        assertEquals("размер скачанного совпадает с архивом", 5L, received)
        assertTrue("пока нода пакует, экран должен показывать сборку, а не застывшую полосу", packing >= 2)
    }

    @Test
    fun `несобранный архив мира не скачивается и объясняется словами`() {
        data class Case(val why: String, val replies: List<MockResponse>, val said: String)

        val cases = listOf(
            Case(
                "сборка упала на ноде — её причина важнее нашей догадки",
                listOf(
                    MockResponse().setBody("""{"state":"running"}"""),
                    MockResponse().setBody("""{"state":"failed","error":"Не хватает места на диске","sizeBytes":0}"""),
                ),
                "Не хватает места на диске",
            ),
            Case(
                "нода забыла задачу и архива нет — качать нечего, иначе игрок получит пустой файл",
                listOf(
                    MockResponse().setBody("""{"state":"running"}"""),
                    MockResponse().setBody("""{"state":"none","error":"","sizeBytes":0}"""),
                ),
                "Сборка архива прервалась",
            ),
            Case(
                "мир ещё не создан — отказ бэкенда доходит текстом, а не кодом 409",
                listOf(
                    MockResponse().setResponseCode(409)
                        .setBody("""{"statusCode":409,"message":"Мир ещё не создан — запусти сервер, он сгенерируется при первом запуске"}"""),
                ),
                "Мир ещё не создан",
            ),
        )

        for (case in cases) {
            val node = MockWebServer()
            node.start()
            try {
                case.replies.forEach { node.enqueue(it) }
                node.enqueue(worldPiece())
                val sink = ByteArrayOutputStream()

                val error = runBlocking { runCatching { client(node).downloadWorldTo("srv-1", sink) }.exceptionOrNull() }

                assertTrue("${case.why}: обязана быть ошибка, а не «Мир сохранён»", error is HostingError)
                assertTrue("${case.why}: текст «${error?.message}»", error?.message.orEmpty().contains(case.said))
                assertEquals(
                    "${case.why}: запроса на скачивание быть не должно",
                    case.replies.size,
                    node.requestCount,
                )
                assertEquals("${case.why}: в файл на телефоне ничего не записано", 0, sink.size())
            } finally {
                node.shutdown()
            }
        }
    }
}
