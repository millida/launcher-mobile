package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.friends.FriendStatsClient
import net.millida.launcher.core.friends.FriendsError
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FriendStatsTest {

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

    private fun stats(signedIn: Boolean = true): FriendStatsClient {
        val vault = MemoryVault()
        if (signedIn) vault.write(MillidaAuth.KEY_ACCESS, "token")
        return FriendStatsClient(MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/')))
    }

    @Test
    fun `скрытие наигранного времени бьёт в тот же адрес, что и десктоп`() {
        runBlocking {
            server.enqueue(MockResponse().setBody("{}"))
            val error = runCatching { stats().hide() }.exceptionOrNull()
            assertNull("успешный ответ не должен давать ошибку", error)
            val request = server.takeRequest()
            assertEquals("/v2/friends/stats/hide", request.path)
            assertEquals("POST", request.method)
            assertEquals("тело — пустой объект, как шлёт десктоп", "{}", request.body.readUtf8())
            assertEquals("Bearer token", request.getHeader("Authorization"))
        }
    }

    private data class Case(val code: Int, val body: String, val expected: String, val why: String)

    @Test
    fun `отказ скрыть время объясняется игроку`() {
        runBlocking {
            val cases = listOf(
                Case(500, "", "Сбой на стороне Millida — повтори через минуту", "голый 500 — сбой на нашей стороне"),
                Case(404, """{"message":"Not Found"}""", "Не удалось скрыть наигранное время", "шум заменяется тем, что не получилось"),
                Case(401, "", "Сессия Millida закончилась — войди в аккаунт заново", "истёкшая сессия зовёт войти, иначе игрок думает, что время скрыто"),
            )
            cases.forEach { case ->
                server.enqueue(MockResponse().setResponseCode(case.code).setBody(case.body))
                val error = runCatching { stats().hide() }.exceptionOrNull()
                assertTrue("${case.code}: ожидалась FriendsError, получено $error", error is FriendsError)
                assertEquals("${case.code}: ${case.why}", case.expected, error?.message)
            }
        }
    }

    @Test
    fun `без входа скрытие не уходит в сеть`() {
        runBlocking {
            val error = runCatching { stats(signedIn = false).hide() }.exceptionOrNull()
            assertTrue("ошибка видна игроку, а не проглочена: $error", error is FriendsError)
            assertEquals(0, server.requestCount)
        }
    }
}
