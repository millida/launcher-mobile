package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.FileMirror
import net.millida.launcher.core.http.HttpError
import net.millida.launcher.core.http.RemoteFile
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 13.10.2026 «Не удалось определить адрес resources.download.minecraft.net» kept 70 phones a day
 * from installing the game: the files of Mojang were asked only from Mojang. They are now asked
 * from our mirror when their own address does not answer.
 */
class DownloaderMirrorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val client = OkHttpClient.Builder().connectTimeout(500, TimeUnit.MILLISECONDS).readTimeout(2, TimeUnit.SECONDS).build()

    /** Nothing listens here: a connection is refused at once. */
    private val dead = "http://127.0.0.1:9/"

    @Test
    fun `адреса Mojang и загрузчиков зеркалятся, остальные нет`() {
        val asset = "https://resources.download.minecraft.net/ab/abcdef"
        assertEquals(
            "https://api.millida.net/v2/launcher/dl?url=https%3A%2F%2Fresources.download.minecraft.net%2Fab%2Fabcdef",
            FileMirror.of(asset),
        )
        assertTrue(FileMirror.of("https://LIBRARIES.minecraft.net/a/b.jar")!!.startsWith("https://api.millida.net/v2/launcher/dl?url="))
        assertNull("чужой адрес зеркалу не отдаётся", FileMirror.of("https://example.com/a.jar"))
        assertNull("похожий домен — чужой", FileMirror.of("https://libraries.minecraft.net.evil.example/a.jar"))
        assertNull("уже зеркало", FileMirror.of(FileMirror.of(asset)!!))
        assertNull("только https", FileMirror.of("http://libraries.minecraft.net/a.jar"))
    }

    @Test
    fun `файл с недоступного адреса берётся с зеркала, и следующий идёт туда сразу`() {
        val server = MockWebServer()
        val asked = ArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(asked) { asked.add(request.path.orEmpty()) }
                return MockResponse().setBody("bytes of " + request.path)
            }
        }
        server.start()
        try {
            val mirror = server.url("/mirror").toString()
            val downloader = Downloader(client, attempts = 1, mirrorOf = { url ->
                if (url.startsWith(dead)) mirror + "?url=" + url.removePrefix(dead) else null
            })
            val first = File(tmp.root, "a.bin")
            val second = File(tmp.root, "b.bin")
            runBlocking {
                downloader.fetch(RemoteFile(dead + "a", first))
                downloader.fetch(RemoteFile(dead + "b", second))
            }
            assertEquals("bytes of /mirror?url=a", first.readText())
            assertEquals("bytes of /mirror?url=b", second.readText())
            assertEquals(listOf("/mirror?url=a", "/mirror?url=b"), asked)
            assertEquals("текст тоже берётся с зеркала", "bytes of /mirror?url=c", runBlocking { downloader.text(dead + "c") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `отказ в самом файле зеркалу не переадресуется`() {
        val server = MockWebServer()
        var mirrorAsked = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().startsWith("/mirror")) mirrorAsked++
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val origin = server.url("/origin/").toString()
            val downloader = Downloader(client, attempts = 1, mirrorOf = { server.url("/mirror").toString() })
            try {
                runBlocking { downloader.text(origin + "missing.json") }
                fail("404 должен дойти до вызывающего")
            } catch (error: HttpError) {
                assertEquals(404, error.code)
            }
            assertEquals("файла нет и у зеркала: спрашивать его незачем", 0, mirrorAsked)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `без зеркала ошибка та же, что и раньше`() {
        val downloader = Downloader(client, attempts = 1, mirrorOf = { null })
        try {
            runBlocking { downloader.text(dead + "x") }
            fail("недоступный адрес без зеркала — ошибка")
        } catch (error: java.io.IOException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("не удалось скачать"))
        }
    }
}
