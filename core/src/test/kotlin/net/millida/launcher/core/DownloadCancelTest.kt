package net.millida.launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DownloadCancelTest {

    @get:Rule
    val temp = TemporaryFolder()

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

    @Test
    fun `отменённая загрузка не оставляет ни файла, ни обрывка`() = runBlocking {
        val size = 4L * 1024 * 1024
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(ByteArray(size.toInt())))
                .throttleBody(64 * 1024, 50, TimeUnit.MILLISECONDS),
        )
        val folder = temp.newFolder()
        val target = folder.resolve("library.jar")
        val started = CountDownLatch(1)
        val downloader = Downloader(client = OkHttpClient(), attempts = 1)

        val job = launch(Dispatchers.IO) {
            downloader.fetch(RemoteFile(server.url("/lib.jar").toString(), target, size = size)) {
                started.countDown()
            }
        }
        assertTrue("загрузка так и не началась — отменять нечего", started.await(10, TimeUnit.SECONDS))

        withTimeout(5_000) { job.cancelAndJoin() }

        assertFalse(
            "отмена оставила файл под итоговым именем — в следующий запуск он сойдёт за скачанный",
            target.exists(),
        )
        assertTrue(
            "после отмены в папке остался обрывок: ${folder.list()?.joinToString()}",
            folder.list().isNullOrEmpty(),
        )
    }
}
