package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.runtime.AUTHLIB_INJECTOR_FEED_URL
import net.millida.launcher.core.runtime.AUTHLIB_INJECTOR_SHA256
import net.millida.launcher.core.runtime.AUTHLIB_INJECTOR_URL
import net.millida.launcher.core.runtime.AUTHLIB_INJECTOR_VERSION
import net.millida.launcher.core.runtime.AuthlibAgent
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AuthlibAgentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hosts = ArrayList<String>()

    private fun agent(code: Int, body: String): AuthlibAgent {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    synchronized(hosts) { hosts += request.url.host }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("fake")
                        .body(body.toResponseBody("application/java-archive".toMediaType()))
                        .build()
                },
            )
            .build()
        return AuthlibAgent(GameLayout(tmp.root), Downloader(client = client, attempts = 1))
    }

    @Test
    fun `агент берётся только закреплённой сборки по https`() {
        val sources = AuthlibAgent.sources()

        assertEquals("сначала релиз GitHub, потом зеркало автора", listOf(AUTHLIB_INJECTOR_URL, AUTHLIB_INJECTOR_FEED_URL), sources)
        for (url in sources) {
            assertTrue("$url: программа внутри игры не качается по http", url.startsWith("https://"))
            assertTrue("$url: версия в адресе совпадает с закреплённой", url.contains("authlib-injector-$AUTHLIB_INJECTOR_VERSION.jar"))
        }
        assertEquals("хеш — sha256 из 64 hex-символов", 64, AUTHLIB_INJECTOR_SHA256.length)
        assertTrue(AUTHLIB_INJECTOR_SHA256.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `файл агента лежит в рантаймах и без верного хеша не готов`() {
        val agent = agent(404, "")

        assertEquals(File(File(tmp.root, "runtimes"), AuthlibAgent.JAR_NAME), agent.jar)
        assertFalse("нет файла — агент не готов", agent.ready())

        agent.jar.parentFile.mkdirs()
        agent.jar.writeText("подменённый jar")
        assertFalse("файл с чужим содержимым не запускается внутри игры", agent.ready())
    }

    @Test
    fun `недоступные источники перечислены в ошибке, а старый файл не тронут`() {
        runBlocking {
            val agent = agent(404, "not found")
            agent.jar.parentFile.mkdirs()
            agent.jar.writeText("старая копия")

            val error = runCatching { agent.ensure() }.exceptionOrNull()

            assertTrue("оба источника отказали — ошибка, а не тихий запуск без агента: $error", error is IOException)
            val text = error!!.message.orEmpty()
            assertTrue("в тексте назван GitHub: $text", text.contains("github.com"))
            assertTrue("в тексте назван запасной источник: $text", text.contains("authlib-injector.yushi.moe"))
            assertEquals("после первого отказа спрошен второй источник", listOf("github.com", "authlib-injector.yushi.moe"), hosts)
            assertEquals("неудачная загрузка не стирает то, что было", "старая копия", agent.jar.readText())
        }
    }

    @Test
    fun `скачанный файл с чужим хешем не подменяет агента`() {
        runBlocking {
            val agent = agent(200, "это не authlib-injector")

            val error = runCatching { agent.ensure() }.exceptionOrNull()

            assertTrue("файл с неверным хешем — отказ: $error", error is IOException)
            assertTrue("причина — хеш, а не сеть: ${error!!.message}", error.message.orEmpty().contains("хеш не совпал"))
            assertFalse("непроверенный файл не остаётся на месте агента", agent.jar.exists())
            assertFalse(agent.ready())
            val leftovers = agent.jar.parentFile?.listFiles().orEmpty().map { it.name }
            assertTrue("временные файлы загрузки убраны: $leftovers", leftovers.none { it.contains(".part-") })
        }
    }
}
