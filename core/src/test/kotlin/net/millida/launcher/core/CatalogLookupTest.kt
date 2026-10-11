package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.catalog.ModrinthCatalog
import net.millida.launcher.core.content.CatalogLookup
import net.millida.launcher.core.content.HashMatch
import net.millida.launcher.core.content.ProjectMeta
import net.millida.launcher.core.http.Downloader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CatalogLookupTest {

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

    private fun lookup() = CatalogLookup(Downloader(attempts = 1), server.url("/mr/v2").toString().trimEnd('/'))

    private val sha = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"

    @Test
    fun `файл опознаётся по хешу`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"V1","project_id":"P1","version_number":"0.5.0","extra":1}"""))

        val match = lookup().byHash(sha)

        assertEquals(HashMatch("P1", "V1", "0.5.0"), match)
        val request = server.takeRequest()
        assertEquals(
            "хеш уходит строчными буквами — Modrinth заглавные не находит",
            "/mr/v2/version_file/${sha.lowercase()}?algorithm=sha1",
            request.path,
        )
        assertEquals("запрос подписан нашим агентом", ModrinthCatalog.USER_AGENT, request.getHeader("User-Agent"))
    }

    @Test
    fun `не хеш не уходит в сеть`() = runBlocking {
        val cases = listOf("" to "пустая строка", "abc" to "обрывок", sha + "0" to "длиннее sha1")
        for ((raw, why) in cases) {
            assertNull("$why: ответа нет", lookup().byHash(raw))
        }
        assertEquals("ни одного запроса за мусором", 0, server.requestCount)
    }

    @Test
    fun `промах каталога — ответ, а не исключение`() = runBlocking {
        data class Case(val response: MockResponse, val why: String)

        val cases = listOf(
            Case(MockResponse().setResponseCode(404), "каталог не знает файл — обычное дело для самосбора"),
            Case(MockResponse().setResponseCode(500), "каталог лёг — план обновлений не должен падать целиком"),
            Case(MockResponse().setBody("<html>bad gateway</html>"), "мусор вместо JSON"),
            Case(MockResponse().setBody("""{"id":"","project_id":"P1"}"""), "без id версии сравнивать не с чем"),
            Case(MockResponse().setBody("""{"id":"V1"}"""), "без проекта не найти обновление"),
        )
        for (case in cases) {
            server.enqueue(case.response)
            assertNull(case.why, lookup().byHash(sha))
        }
    }

    @Test
    fun `проект запоминается вместе с промахом`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"P1","slug":"sodium","title":"Sodium"}"""))
        server.enqueue(MockResponse().setResponseCode(404))

        val client = lookup()
        val first = client.project("P1")
        val second = client.project("P1")
        val missing = client.project("GONE")
        val missingAgain = client.project("GONE")

        assertEquals(ProjectMeta("P1", "sodium", "Sodium"), first)
        assertEquals("повтор берётся из памяти", first, second)
        assertNull(missing)
        assertNull(missingAgain)
        assertEquals("одна дорога на проект и одна на промах — граф зависимостей не дёргает каталог по кругу", 2, server.requestCount)
        assertNull("пустой id не спрашивается", client.project(""))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `пустой id в ответе заменяется запрошенным`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"slug":"lib","title":"Lib"}"""))

        val meta = lookup().project("LIB")

        assertEquals("LIB", meta?.projectId)
        assertArrayEquals(
            "все три написания идут в поиск по установленному",
            arrayOf("LIB", "lib", "Lib"),
            meta?.names(),
        )
    }
}
