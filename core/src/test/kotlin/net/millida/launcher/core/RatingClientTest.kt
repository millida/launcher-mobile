package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.HttpError
import net.millida.launcher.core.rating.LicenseFilter
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.core.rating.RatingClient
import net.millida.launcher.core.rating.RatingSort
import net.millida.launcher.core.rating.VersionFacet
import net.millida.launcher.core.rating.newestFirst
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class RatingClientTest {

    private class UrlCase(val expected: String, val why: String, val call: suspend RatingClient.() -> Unit)

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

    private fun client(): RatingClient = RatingClient(Downloader(attempts = 1), server.url("/").toString().trimEnd('/'))

    private fun pathOf(call: suspend RatingClient.() -> Unit): String {
        server.enqueue(MockResponse().setBody("""{"servers":[],"total":0}"""))
        runBlocking { client().call() }
        return server.takeRequest().path.orEmpty()
    }

    @Test
    fun `запрос рейтинга собирается по контракту сайта`() {
        val cases = listOf(
            UrlCase("/rating/servers?limit=30&offset=0&sort=rating", "без фильтров уходят только страница и сортировка") { servers() },
            UrlCase(
                "/rating/servers?limit=50&offset=0&sort=online&license=CRACKED&category=SURVIVAL&version=1.21.1&minOnline=20&search=%D0%BC%D0%B8%D1%80",
                "все фильтры разом, кириллица в поиске кодируется, лимит больше 50 срезается, отрицательный сдвиг — ноль",
            ) { servers(" мир ", RatingSort.ONLINE, LicenseFilter.CRACKED, "SURVIVAL", "1.21.1", 20, -5, 100) },
            UrlCase(
                "/rating/servers?limit=1&offset=60&sort=new&license=LICENSE",
                "лимит ниже единицы поднимается до одного, иначе служба вернёт ошибку",
            ) { servers(sort = RatingSort.NEW, license = LicenseFilter.LICENSE, offset = 60, limit = 0) },
            UrlCase(
                "/rating/servers?limit=30&offset=0&sort=name",
                "пустые категория, версия и поиск, нулевой онлайн не превращаются в параметры",
            ) { servers(sort = RatingSort.NAME, category = "  ", version = "", minOnline = 0, search = "   ") },
            UrlCase(
                "/rating/servers?limit=30&offset=0&sort=rating&category=A%26B&search=a+b%3Dc",
                "& и = во вводе не ломают строку запроса",
            ) { servers(category = "A&B", search = "a b=c") },
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, pathOf(case.call))
        }
    }

    @Test
    fun `длинный ввод обрезается до запроса`() {
        val search = pathOf { servers(search = "x".repeat(200)) }
        assertTrue("поиск длиннее 60 знаков ушёл целиком: $search", search.endsWith("&search=" + "x".repeat(60)))
        val version = pathOf { servers(version = "1.21.1-pre-release-candidate") }
        assertTrue("версия длиннее 16 знаков ушла целиком: $version", version.endsWith("&version=1.21.1-pre-relea"))
    }

    @Test
    fun `версии рейтинга идут от новой к старой, мусор отбрасывается`() {
        server.enqueue(
            MockResponse().setBody(
                """[{"version":"1.20.1","servers":5},{"version":"1.21.10","servers":2},{"version":"1.21.9"},
                   {"version":"24w14a","servers":1},{"version":"","servers":9},{"version":" 1.8 ","servers":40},{"servers":3}]""",
            ),
        )
        val facets = runBlocking { client().versions() }
        assertEquals("ответ служба отдаёт по адресу версий", "/rating/servers/versions", server.takeRequest().path)
        assertEquals("версия без числа серверов — ноль", 0, facets.first { it.version == "1.21.9" }.servers)
        assertEquals(
            "1.21.10 новее 1.21.9: сравнение строкой поставило бы десятую ниже девятой",
            listOf("1.21.10", "1.21.9", "1.20.1", " 1.8 "),
            facets.newestFirst().map { it.version },
        )
        assertEquals("пустой список не падает", emptyList<VersionFacet>(), emptyList<VersionFacet>().newestFirst())
    }

    @Test
    fun `сбой рейтинга не выдаётся за пустой список`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val missing = runCatching { runBlocking { client().servers() } }.exceptionOrNull()
        assertTrue("404 должна дойти как HttpError, а не как пустой рейтинг: $missing", missing is HttpError && missing.code == 404)

        server.enqueue(MockResponse().setResponseCode(503))
        val down = runCatching { runBlocking { client().servers() } }.exceptionOrNull()
        assertTrue("503 должна стать ошибкой загрузки: $down", down is IOException)
        // Coroutines may wrap the thrown exception in a recovered copy, so the code is looked for along the chain.
        val chain = generateSequence(down) { it.cause }.toList()
        assertTrue("503 потеряла причину: $chain", chain.any { it is HttpError && it.code == 503 })
    }

    @Test
    fun `адрес входа и описание карточки`() {
        val hidden = RatedServer(ip = " play.example.net ", address = "")
        assertEquals("без отдельного адреса заходим по ip без пробелов", "play.example.net", hidden.joinAddress)
        val own = RatedServer(ip = "play.example.net", address = "join.example.net:25570")
        assertEquals("отдельный адрес входа важнее ip", "join.example.net:25570", own.joinAddress)
        assertEquals("нет адреса вовсе — пусто, а не null", "", RatedServer().joinAddress)

        val ai = RatedServer(shortDesc = "  ", aiDescription = "Выживание\nс кланами ")
        assertEquals("краткое описание пустое — берётся ИИ-описание в одну строку", "Выживание с кланами", ai.summary)
        val manual = RatedServer(shortDesc = "Анархия", aiDescription = "ИИ")
        assertEquals("описание владельца важнее ИИ", "Анархия", manual.summary)
    }
}
