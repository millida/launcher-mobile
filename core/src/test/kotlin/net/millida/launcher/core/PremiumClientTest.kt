package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.catalog.MillidaCatalog
import net.millida.launcher.core.premium.PremiumClient
import net.millida.launcher.core.premium.PremiumPack
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class PremiumClientTest {

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

    private fun premium(signedIn: Boolean = false): PremiumClient {
        val vault = MemoryVault()
        if (signedIn) vault.write(MillidaAuth.KEY_ACCESS, "test-token")
        val auth = MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/'))
        return PremiumClient(auth, MillidaCatalog(auth))
    }

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test
    fun `упавшая витрина подписок заменяется платными сборками каталога`() {
        runBlocking {
            reply("""{"message":"internal"}""", 500)
            reply(
                """[{"slug":"arcania","title":"Arcania","accessRequired":true,"game":"1.20.1","loader":"forge"},
                   {"slug":"free","title":"Бесплатная","accessRequired":false},
                   {"slug":"../bad","accessRequired":true}]""",
            )

            val showcase = premium().showcase()

            assertEquals("/v2/launcher/premium", server.takeRequest().path)
            assertEquals("запасной источник — каталог", "/v2/catalog/packs", server.takeRequest().path)
            assertEquals(
                "сбой подписок не оставляет игрока с пустой витриной; бесплатные и битые адреса не попадают",
                listOf("arcania"),
                showcase.packs.map { it.id },
            )
        }
    }

    @Test
    fun `живая витрина подписок не дёргает каталог`() {
        runBlocking {
            reply("""{"packs":[{"id":"p1","title":"Сборка"}]}""")

            val showcase = premium().showcase()

            assertEquals(listOf("p1"), showcase.packs.map { it.id })
            assertEquals("лишний запрос к каталогу не делается", 1, server.requestCount)
        }
    }

    @Test
    fun `упали оба источника — ошибка доходит до экрана`() {
        runBlocking {
            reply("""{"message":"internal"}""", 500)
            reply("""{"message":"Каталог недоступен"}""", 503)

            val error = runCatching { premium().showcase() }.exceptionOrNull()

            assertTrue("оба источника мертвы — игрок видит причину, а не пустоту: $error", error is IOException)
            assertEquals("Каталог недоступен", error!!.message)
        }
    }

    private data class SlugCase(val id: String, val why: String)

    @Test
    fun `адрес сборки проверяется до запроса`() {
        runBlocking {
            val cases = listOf(
                SlugCase("../admin", "выход из пути"),
                SlugCase("a b", "пробел"),
                SlugCase("", "пустой адрес"),
                SlugCase("-arcania", "адрес не начинается с дефиса"),
                SlugCase("arcania?x=1", "параметры в адресе"),
                SlugCase("a".repeat(129), "длиннее 128 символов"),
            )
            val client = premium(signedIn = true)
            for (case in cases) {
                val calls = listOf(
                    "buy" to runCatching { client.buy(case.id) },
                    "packView" to runCatching { client.packView(case.id) },
                    "pack" to runCatching { client.pack(PremiumPack(id = case.id, title = "x")) },
                    "subscribe" to runCatching { client.subscribe("plan", case.id) },
                )
                for ((name, result) in calls) {
                    val error = result.exceptionOrNull()
                    assertTrue("$name(«${case.id}»): ${case.why} — отказ", error is IOException)
                    assertTrue("$name(«${case.id}»): текст называет адрес", error!!.message.orEmpty().startsWith("некорректный адрес сборки"))
                }
            }
            assertEquals("ни один плохой адрес не ушёл в сеть", 0, server.requestCount)
        }
    }

    @Test
    fun `покупка возвращает адрес оплаты без пробелов`() {
        runBlocking {
            reply("""{"paymentUrl":"  https://pay.millida.net/p/1  "}""")
            reply("""{"status":"pending"}""")
            val client = premium(signedIn = true)

            val url = client.buy("arcania")
            val none = client.buy("arcania")

            val request = server.takeRequest()
            assertEquals("/v2/launcher/premium/arcania/buy", request.path)
            assertEquals("POST", request.method)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertEquals("https://pay.millida.net/p/1", url)
            assertEquals("нет адреса — пустая строка, экран не откроет «null»", "", none)
        }
    }

    @Test
    fun `страница сборки каталога читается по своему адресу`() {
        runBlocking {
            reply("""{"title":"Arcania","banner":"https://cdn.millida.net/b.png","files":[{"side":"client","size":1048576}]}""")

            val view = premium().packView("arcania")

            assertEquals("/v2/catalog/packs/arcania", server.takeRequest().path)
            assertEquals("без slug в ответе остаётся запрошенный", "arcania", view.slug)
            assertEquals(1_048_576L, view.clientBytes)
            assertEquals("https://cdn.millida.net/b.png", view.art)
        }
    }
}
