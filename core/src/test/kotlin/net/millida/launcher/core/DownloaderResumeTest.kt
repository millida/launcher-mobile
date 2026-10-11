package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * 08.10.2026 a 981 MB pack on a phone broke off after 50-350 MB on a saturated link, and every
 * repeated attempt deleted what it had and started from the first byte: after four breaks the
 * player got «нет сети» and the pack never installed. A repeated attempt now asks for the rest.
 */
class DownloaderResumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = ByteArray(256 * 1024) { (it * 31 % 251).toByte() }
    private val sha1 = MessageDigest.getInstance("SHA-1").digest(payload).joinToString("") { "%02x".format(it) }

    private data class Case(val breaks: Int, val honoursRange: Boolean, val why: String)

    @Test
    fun `оборванный файл докачивается с места обрыва`() {
        val cases = listOf(
            Case(1, true, "один обрыв: вторая попытка просит Range с места обрыва, а не весь файл"),
            Case(7, true, "обрывов больше, чем попыток: попытка, которая продвинула файл, попытку не тратит"),
            Case(1, false, "сервер не умеет Range и шлёт 200 целиком: файл пишется заново, а не дописывается в хвост"),
        )
        for (case in cases) {
            val server = MockWebServer()
            val ranges = mutableListOf<String?>()
            var served = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.getHeader("Range")
                    ranges += range
                    val from = if (case.honoursRange) range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0 else 0
                    val rest = payload.copyOfRange(from, payload.size)
                    val response = MockResponse()
                        .setResponseCode(if (from > 0) 206 else 200)
                        .setBody(Buffer().write(rest))
                    if (from > 0) response.setHeader("Content-Range", "bytes $from-${payload.size - 1}/${payload.size}")
                    if (served++ < case.breaks) response.socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
                    return response
                }
            }
            server.start()
            try {
                val target = File(tmp.root, "pack-" + System.nanoTime() + ".zip")
                val downloader = Downloader(client = OkHttpClient(), attempts = 4)
                var counted = 0L
                runBlocking {
                    downloader.fetch(RemoteFile(url = server.url("/pack.zip").toString(), target = target, sha1 = sha1)) { chunk ->
                        counted = if (chunk < 0) 0 else counted + chunk
                    }
                }
                assertTrue("файл собран не тот: ${case.why}", payload.contentEquals(target.readBytes()))
                assertEquals("прогресс должен кончаться на размере файла, без двойного счёта: ${case.why}", payload.size.toLong(), counted)
                if (case.honoursRange) {
                    assertTrue(
                        "повторная попытка обязана просить остаток, а не файл с нуля: ${case.why}; были $ranges",
                        ranges.drop(1).all { it != null && it != "bytes=0-" },
                    )
                }
                assertTrue("недокачанный кусок не должен оставаться рядом: ${case.why}", tmp.root.listFiles()!!.none { it.name.endsWith(".part") })
            } finally {
                server.shutdown()
            }
        }
    }
}
