package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.packs.ForgeCdn
import net.millida.launcher.core.packs.PackHostPolicy
import net.millida.launcher.core.packs.PackReader
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ForgeCdnTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val routes = HashMap<String, MockResponse>()
    private val asked = ArrayList<String>()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                synchronized(asked) { asked += path }
                return routes[path] ?: MockResponse().setResponseCode(502)
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private data class UrlCase(val fileId: Long, val name: String, val expected: List<String>, val why: String)

    @Test
    fun `адрес файла CurseForge собирается из номера файла и имени`() {
        val cases = listOf(
            UrlCase(
                4567890, "jei.jar",
                listOf(
                    "https://edge.forgecdn.net/files/4567/890/jei.jar",
                    "https://mediafilez.forgecdn.net/files/4567/890/jei.jar",
                ),
                "номер делится на тысячи так же, как раскладывает файлы сам CDN",
            ),
            UrlCase(
                4567005, "a.jar",
                listOf(
                    "https://edge.forgecdn.net/files/4567/5/a.jar",
                    "https://mediafilez.forgecdn.net/files/4567/5/a.jar",
                ),
                "остаток без ведущих нулей — с нулями CDN отвечает 404",
            ),
            UrlCase(
                4567890, "A Mod [1.20].jar",
                listOf(
                    "https://edge.forgecdn.net/files/4567/890/A%20Mod%20%5B1.20%5D.jar",
                    "https://mediafilez.forgecdn.net/files/4567/890/A%20Mod%20%5B1.20%5D.jar",
                ),
                "пробел кодируется как %20, а не +, иначе CDN ищет другой файл",
            ),
            UrlCase(0, "jei.jar", emptyList(), "без номера файла адреса нет — лучше честный отказ, чем чужой файл"),
            UrlCase(4567890, "", emptyList(), "без имени адреса нет"),
        )
        for (case in cases) {
            assertEquals("ForgeCdn.urls(${case.fileId}, «${case.name}»): ${case.why}", case.expected, ForgeCdn.urls(case.fileId, case.name))
        }
    }

    @Test
    fun `собранные адреса проходят проверку хоста сборки`() {
        val policy = PackHostPolicy()
        ForgeCdn.urls(4567890, "jei.jar").forEach { url ->
            assertTrue("адрес $url должен пропускаться установщиком, иначе запасной путь бесполезен", policy.allows(url))
        }
    }

    private data class ReaderCase(
        val required: Boolean,
        val sha1: String?,
        val expectedUrls: List<String>?,
        val why: String,
    )

    @Test
    fun `файл без downloadUrl с известным sha1 берётся с CDN, а не через download-url`() {
        val sha = "a".repeat(40)
        val cdn = ForgeCdn.urls(4567890, "jei.jar")
        val cases = listOf(
            ReaderCase(true, sha, cdn, "обязательный файл: наш download-url отвечает 502 и рушил всю сборку"),
            ReaderCase(false, sha, cdn, "необязательный файл тоже пробуем — установщик пропустит его, если CDN не отдаст"),
            ReaderCase(false, null, null, "необязательный файл без хеша не качаем непроверенным, а пропускаем"),
        )
        for (case in cases) {
            routes.clear()
            synchronized(asked) { asked.clear() }
            val hashes = if (case.sha1 == null) "[]" else """[{"algo":1,"value":"${case.sha1}"}]"""
            routes["/cf/v1/mods/1/files/4567890"] = MockResponse()
                .setBody("""{"data":{"fileName":"jei.jar","downloadUrl":null,"hashes":$hashes,"fileLength":10}}""")
            val root = tmp.newFolder("cf-" + System.nanoTime())
            File(root, "manifest.json").writeText(
                """
                {"name":"x","minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.2.0","primary":true}]},
                 "files":[{"projectID":1,"fileID":4567890,"required":${case.required}}]}
                """.trimIndent(),
            )

            val plan = runBlocking {
                PackReader(Downloader(attempts = 1), server.url("/cf").toString().trimEnd('/')).readCurseForge(root)
            }.plan

            val entry = plan.files.firstOrNull()
            assertEquals("адреса файла: ${case.why}", case.expectedUrls, entry?.urls)
            if (entry != null) {
                assertEquals("хеш сохраняется — он единственное, что доказывает байты с CDN: ${case.why}", case.sha1, entry.sha1)
                assertEquals("обязательность не меняется: ${case.why}", case.required, entry.required)
            }
            assertFalse(
                "при известном хеше download-url не спрашивается: ${case.why}",
                asked.any { it.endsWith("/download-url") },
            )
        }
    }
}
