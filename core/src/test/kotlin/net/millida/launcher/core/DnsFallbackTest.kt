package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MicrosoftAuth
import net.millida.launcher.core.http.DohDns
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong

/**
 * Вход через Microsoft падал там, где системный резолвер молчит по имени, а
 * браузер на том же телефоне его открывает. Таблица «вход -> вердикт»:
 * системный ответил — в сеть не ходим; молчит — берём адрес у DoH; DoH врёт —
 * игрок видит текст, а не падение; локальные имена и цифры мимо DoH всегда.
 */
class DnsFallbackTest {

    private lateinit var server: MockWebServer
    private val now = AtomicLong(1_000_000)

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun endpoints(vararg paths: String): List<String> =
        paths.map { server.url(it).toString() }

    private fun resolver(
        system: Dns,
        endpoints: List<String> = endpoints("/resolve"),
        ttl: Long = DohDns.DEFAULT_CACHE_TTL_MILLIS,
    ) = DohDns(
        system = system,
        endpoints = endpoints,
        cacheTtlMillis = ttl,
        clock = { now.get() },
        resolver = OkHttpClient(),
    )

    private val deadSystem = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            throw UnknownHostException("$hostname: No address associated with hostname")
    }

    private fun workingSystem(literal: String) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByAddress(hostname, DohDns.ipv4Bytes(literal)!!))
    }

    private fun answer(vararg literals: String): MockResponse = MockResponse().setBody(
        """{"Status":0,"Answer":[""" +
            literals.joinToString(",") { """{"name":"login.microsoftonline.com","type":1,"TTL":60,"data":"$it"}""" } +
            "]}"
    )

    @Test
    fun `системный резолвер сработал — DoH не дёргается`() {
        val dns = resolver(workingSystem("93.184.216.34"))

        val addresses = dns.lookup("login.microsoftonline.com")

        assertEquals("адрес пришёл от системы", "93.184.216.34", addresses.single().hostAddress)
        assertEquals("рабочий системный резолвер не должен ходить в DoH", 0, server.requestCount)
    }

    @Test
    fun `системный молчит — адрес берётся из DoH`() {
        server.enqueue(answer("20.190.159.4"))
        val dns = resolver(deadSystem)

        val addresses = dns.lookup("login.microsoftonline.com")

        assertEquals("адрес взят из ответа DoH", "20.190.159.4", addresses.single().hostAddress)
        assertEquals("имя сохранено для TLS и SNI", "login.microsoftonline.com", addresses.single().hostName)

        val request = server.takeRequest()
        assertTrue(
            "имя уходит параметром name",
            request.path.orEmpty().contains("name=login.microsoftonline.com"),
        )
        assertTrue("тип запрашивается A", request.path.orEmpty().contains("type=A"))
        assertEquals(
            "без этого заголовка резолверы отдают бинарный формат",
            "application/dns-json",
            request.getHeader("accept"),
        )
    }

    @Test
    fun `первый резолвер лёг — спрашиваем второй`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(answer("20.190.159.4"))
        val dns = resolver(deadSystem, endpoints("/resolve", "/dns-query"))

        val addresses = dns.lookup("login.microsoftonline.com")

        assertEquals("сработал запасной резолвер", "20.190.159.4", addresses.single().hostAddress)
        assertEquals("спрошены оба", 2, server.requestCount)
    }

    @Test
    fun `DoH ответил мусором — понятная ошибка, а не падение`() {
        server.enqueue(MockResponse().setBody("<html>портал провайдера</html>"))
        server.enqueue(MockResponse().setBody("""{"Status":0,"Answer":[{"type":1,"data":"злой-хост"}]}"""))
        val dns = resolver(deadSystem, endpoints("/resolve", "/dns-query"))

        val error = runCatching { dns.lookup("login.microsoftonline.com") }.exceptionOrNull()

        assertTrue("наружу должен лететь UnknownHostException", error is UnknownHostException)
        val text = error?.message.orEmpty()
        assertTrue("в тексте есть само имя: игроку видно, что не открылось", text.contains("login.microsoftonline.com"))
        assertTrue("сказано, что делать с сетью", text.contains("Wi-Fi") && text.contains("VPN"))
        assertTrue("текст по-русски, а не системный английский", !text.contains("No address associated"))
    }

    @Test
    fun `ответ кэшируется — второй запрос не идёт в сеть`() {
        server.enqueue(answer("20.190.159.4"))
        val dns = resolver(deadSystem)

        val first = dns.lookup("login.microsoftonline.com")
        val second = dns.lookup("login.microsoftonline.com")

        assertEquals("адрес тот же", first.single().hostAddress, second.single().hostAddress)
        assertEquals("в сеть сходили один раз", 1, server.requestCount)
    }

    @Test
    fun `кэш протухает через отведённое время`() {
        server.enqueue(answer("20.190.159.4"))
        server.enqueue(answer("40.126.0.1"))
        val dns = resolver(deadSystem, ttl = 60_000)

        val first = dns.lookup("login.microsoftonline.com")
        now.addAndGet(60_001)
        val second = dns.lookup("login.microsoftonline.com")

        assertEquals("первый адрес из первого ответа", "20.190.159.4", first.single().hostAddress)
        assertEquals("после срока спрашиваем заново", "40.126.0.1", second.single().hostAddress)
        assertEquals("итого два запроса", 2, server.requestCount)
    }

    @Test
    fun `вход Microsoft при мёртвом имени объясняется по-русски`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>портал провайдера</html>"))
        val client = OkHttpClient.Builder().dns(resolver(deadSystem)).build()

        val error = runCatching { MicrosoftAuth(client).startDeviceFlow() }.exceptionOrNull()

        val text = error?.message.orEmpty()
        assertTrue("сказано, что не удалось", text.contains("Не удалось определить адрес"))
        assertTrue("названо место сбоя", text.contains("Microsoft"))
        assertTrue("сказано, что делать", text.contains("Wi-Fi") && text.contains("VPN"))
        assertTrue("системный английский наружу не идёт", !text.contains("No address associated"))
    }

    @Test
    fun `localhost резолвится системой без DoH`() {
        val dns = resolver(Dns.SYSTEM)

        val addresses = dns.lookup("localhost")

        assertTrue("localhost должен резолвиться", addresses.isNotEmpty())
        assertEquals("локальное имя не уходит наружу", 0, server.requestCount)
    }

    @Test
    fun `цифровой адрес не уходит в DoH даже при мёртвом системном резолвере`() {
        val dns = resolver(deadSystem)

        val literal = runCatching { dns.lookup("203.0.113.9") }.exceptionOrNull()
        val local = runCatching { dns.lookup("millida-server") }.exceptionOrNull()

        assertTrue("сбой системы по литералу остаётся сбоем системы", literal is UnknownHostException)
        assertTrue("имя без точки — локальное, наружу не спрашиваем", local is UnknownHostException)
        assertEquals("ни одного запроса к DoH", 0, server.requestCount)
    }
}
