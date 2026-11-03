package net.millida.launcher.core

import net.millida.launcher.core.catalog.ModrinthCatalog
import net.millida.launcher.core.http.Downloader
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A request that leaves as "okhttp/x.y.z" looks to a server like any Android application. */
class UserAgentTest {

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    private fun sent(explicit: String?): String? {
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url("http://127.0.0.1:${server.port}/mod/variants.json").apply {
            if (explicit != null) header("User-Agent", explicit)
        }.build()
        Downloader.defaultClient().newCall(request).execute().close()
        return server.takeRequest().getHeader("User-Agent")
    }

    /** What the request names itself → what the server must read → why the case is pinned. */
    private val cases = listOf(
        Triple(null, Downloader.USER_AGENT, "без своего имени лаунчер неотличим от чужого приложения"),
        Triple(ModrinthCatalog.USER_AGENT, ModrinthCatalog.USER_AGENT, "каталог модов просит подписываться адресом для связи"),
    )

    @Test
    fun `запрос называет лаунчер, а заданное имя не перетирается`() {
        for ((explicit, expected, why) in cases) {
            assertEquals("User-Agent при заданном «$explicit»: $why", expected, sent(explicit))
        }
    }

    @Test
    fun `имя общее с настольным лаунчером, а версия на настольную не похожа`() {
        val agent = Downloader.USER_AGENT
        assertTrue(
            "сервер узнаёт лаунчер по началу «MillidaLauncher/», с другим началом телефон для него чужой: $agent",
            agent.startsWith("MillidaLauncher/"),
        )
        assertFalse(
            "по настольной версии из этого заголовка сервер предлагает игры только для компьютера, телефон скачает такую и не запустит: $agent",
            Regex("^MillidaLauncher/\\d+\\.\\d+\\.\\d+(?:[\\s(]|$)").containsMatchIn(agent),
        )
        assertTrue(
            "версия сборки должна читаться из заголовка: $agent",
            Regex("^MillidaLauncher/android-[0-9A-Za-z._-]+ \\(\\+https://millida\\.net\\)$").matches(agent),
        )
    }
}
