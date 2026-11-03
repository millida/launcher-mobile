package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.runtime.AuthlibMetadata
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Base64

class AuthlibMetadataTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val url = "https://api.millida.net/v2/yggdrasil"
    private val body = """{"meta":{"serverName":"Millida"},"skinDomains":["api.millida.net"]}"""

    private fun metadata(code: Int = 200, answer: String = body, offline: Boolean = false): AuthlibMetadata {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    if (offline) throw IOException("Connection timed out")
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("fake")
                        .body(answer.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        return AuthlibMetadata(GameLayout(tmp.root), Downloader(client = client, attempts = 1))
    }

    private fun decoded(argument: String?): String? =
        argument?.substringAfter('=')?.let { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }

    @Test
    fun `ответ сервера входа уходит агенту как есть и запоминается`() = runBlocking {
        val argument = metadata().jvmArgument(url)
        assertTrue("свойство называется так, как его читает агент", argument!!.startsWith("-Dauthlibinjector.yggdrasil.prefetched="))
        assertEquals(body, decoded(argument))
        assertFalse("base64 без переводов строк: это один аргумент командной строки", argument.contains('\n'))
    }

    @Test
    fun `без сети берётся последний хороший ответ, а без него агент спросит сам`() = runBlocking {
        assertNull("ничего не запоминали — и ответа нет", metadata(offline = true).jvmArgument(url))
        metadata().jvmArgument(url)
        assertEquals("сеть пропала — прежнее описание", body, decoded(metadata(offline = true).jvmArgument(url)))
        assertNull("запомненное другого сервера не подставляется", metadata(offline = true).jvmArgument("https://other.example/yggdrasil"))
    }

    @Test
    fun `страница ошибки не считается описанием`() = runBlocking {
        assertNull(metadata(answer = "<html>Bad gateway</html>").jvmArgument(url))
        assertNull(metadata(code = 502).jvmArgument(url))
        assertFalse(AuthlibMetadata.looksLikeMetadata("{}"))
    }
}
