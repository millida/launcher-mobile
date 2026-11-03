package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * «Вход Millida истёк» на телефонах (прод 17–19.10: 2 013 «краж» refresh-токена
 * от Android, у 1 566 семей ровно одна ротация). Сервер отдаёт новый refresh
 * только в cookie rt2, а телефон хранил старый и предъявлял его повторно.
 *
 * | ответ /auth/refresh                          | что лежит в хранилище после         |
 * | -------------------------------------------- | ----------------------------------- |
 * | accessToken в теле, refresh в Set-Cookie rt2 | новый refresh из cookie             |
 * | refresh и в теле, и в cookie                 | тот, что в теле                     |
 * | cookie rt2 пустая (сервер её стёр)           | прежний refresh, не пустая строка   |
 * | две ротации подряд                           | вторая идёт с refresh первой        |
 */
class MillidaRefreshTest {

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

    private fun auth(vault: MemoryVault) =
        MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/'))

    private fun signedIn() = MemoryVault().apply {
        write(MillidaAuth.KEY_ACCESS, "access-0")
        write(MillidaAuth.KEY_REFRESH, "refresh-0")
    }

    private fun refreshed(access: String, cookie: String?, bodyRefresh: String? = null) =
        MockResponse().setBody(
            buildString {
                append("""{"accessToken":"$access"""")
                bodyRefresh?.let { append(""","refreshToken":"$it"""") }
                append("}")
            },
        ).apply { cookie?.let { addHeader("Set-Cookie", it) } }

    @Test
    fun `новый refresh из cookie rt2 сохраняется`() = runBlocking {
        val vault = signedIn()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(refreshed("access-1", "rt2=refresh-1; Path=/; HttpOnly; Secure; SameSite=None"))
        server.enqueue(MockResponse().setBody("""{"id":"u1"}"""))

        auth(vault).profile()

        assertEquals("access-1", vault.read(MillidaAuth.KEY_ACCESS))
        assertEquals("refresh-1", vault.read(MillidaAuth.KEY_REFRESH))
    }

    @Test
    fun `вторая ротация идёт с refresh первой, а не с потраченным`() = runBlocking {
        val vault = signedIn()
        val auth = auth(vault)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(refreshed("access-1", "rt2=refresh-1; Path=/"))
        server.enqueue(MockResponse().setBody("{}"))
        auth.profile()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(refreshed("access-2", "rt2=refresh-2; Path=/"))
        server.enqueue(MockResponse().setBody("{}"))
        auth.profile()

        repeat(4) { server.takeRequest() }
        val secondRefresh = server.takeRequest().body.readUtf8()
        assertEquals("""{"refreshToken":"refresh-1"}""", secondRefresh)
        assertEquals("refresh-2", vault.read(MillidaAuth.KEY_REFRESH))
    }

    @Test
    fun `refresh в теле важнее cookie`() = runBlocking {
        val vault = signedIn()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(refreshed("access-1", "rt2=from-cookie; Path=/", bodyRefresh = "from-body"))
        server.enqueue(MockResponse().setBody("{}"))

        auth(vault).profile()

        assertEquals("from-body", vault.read(MillidaAuth.KEY_REFRESH))
    }

    @Test
    fun `стёртая cookie не затирает refresh пустой строкой`() = runBlocking {
        val vault = signedIn()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(refreshed("access-1", "rt2=; Path=/; Max-Age=0"))
        server.enqueue(MockResponse().setBody("{}"))

        auth(vault).profile()

        assertEquals("refresh-0", vault.read(MillidaAuth.KEY_REFRESH))
    }

    @Test
    fun `чужие cookie за refresh не принимаются`() {
        assertNull(MillidaAuth.refreshFromCookies(listOf("rt=legacy; Path=/", "did=abc; Path=/")))
        assertEquals("x", MillidaAuth.refreshFromCookies(listOf("did=abc", "rt2=x; HttpOnly")))
    }
}
