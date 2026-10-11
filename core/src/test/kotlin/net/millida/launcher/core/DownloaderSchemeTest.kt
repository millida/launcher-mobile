package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Library and file addresses come from version descriptions and catalogues: a
 * local path there used to be copied into the game folder, which let a crafted
 * version JSON read any file the launcher can see.
 */
class DownloaderSchemeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private data class Case(val url: (File) -> String, val why: String)

    @Test
    fun `загрузчик берёт только сетевые адреса`() {
        val cases = listOf(
            Case({ secret -> "file://" + secret.absolutePath }, "file:// читал файл телефона"),
            Case({ secret -> secret.absolutePath }, "голый путь тоже копировался"),
            Case({ _ -> "content://downloads/1" }, "чужая схема"),
            Case({ _ -> "ftp://example.com/x.jar" }, "не http(s)"),
        )
        for (case in cases) {
            val secret = tmp.newFile("secret-" + System.nanoTime()).also { it.writeText("token") }
            val target = File(tmp.root, "out-" + System.nanoTime() + ".jar")
            val downloader = Downloader(attempts = 1)
            val fetched = runCatching { runBlocking { downloader.fetch(RemoteFile(url = case.url(secret), target = target)) } }
            val text = runCatching { runBlocking { downloader.text(case.url(secret)) } }
            assertTrue("fetch должен отказать: ${case.why}; было $fetched", fetched.exceptionOrNull() is IOException)
            assertFalse("файл не должен появиться в папке игры: ${case.why}", target.exists())
            assertTrue(
                "text должен отказать IOException, а не IllegalArgumentException от OkHttp: ${case.why}; было $text",
                text.exceptionOrNull() is IOException,
            )
        }
    }
}
