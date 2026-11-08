package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.LoginRefused
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.PendingLogin
import net.millida.launcher.core.account.Unauthorized
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * The player confirms the sign-in in a browser, so the launcher waits in the background,
 * where the system may cut its network or end its process.
 *
 * | вход                                        | вердикт                                             |
 * | ------------------------------------------- | --------------------------------------------------- |
 * | опрос рвётся по сети или отвечает 500/HTML  | опрос продолжается и забирает подтверждённый вход   |
 * | сервер ответил denied/expired               | ожидание окончено, код забыт                        |
 * | срок кода вышел без ответа                  | false, код забыт                                    |
 * | приложение перезапущено во время ожидания   | тот же код читается из хранилища                    |
 * | токены получены, сессия игры не выдалась    | вход остаётся незавершённым, новый код не нужен     |
 */
class MillidaLoginWaitTest {

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
        MillidaAuth(
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                // A dual-stack localhost would strand this client: without retries, a failure on the first
                // connection postpones the address the server listens on and it never comes back to it.
                .dns(
                    object : Dns {
                        override fun lookup(hostname: String) = listOf(InetAddress.getByName(hostname))
                    },
                )
                .build(),
            vault,
            server.url("/v2").toString().trimEnd('/'),
        )

    private fun initAnswer() = MockResponse().setBody(
        """{"deviceCode":"device-1","userCode":"ABCD-EFGH","verifyUrl":"https://millida.net/auth/launcher?code=ABCD-EFGH","expiresInSec":900}""",
    )

    private fun approvedAnswer() = MockResponse().setBody("""{"status":"ok","accessToken":"access-1","refreshToken":"refresh-1"}""")

    private fun sessionAnswer() = MockResponse().setBody("""{"accessToken":"game-1","name":"Nick","uuid":"uuid-1"}""")

    private fun pending(expiresAtMs: Long = System.currentTimeMillis() + 60_000) =
        PendingLogin("device-1", "ABCD-EFGH", "https://millida.net/auth/launcher", expiresAtMs, inline = true)

    private data class HiccupCase(val answer: MockResponse, val why: String)

    @Test
    fun `сбой во время опроса не обрывает ожидание входа`() {
        val cases = listOf(
            HiccupCase(
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
                "в фоне система режет сеть приложению: опрос умирал, подтверждение терялось, игрок подтверждал заново по кругу",
            ),
            HiccupCase(
                MockResponse().setResponseCode(502).setBody("<html>502</html>"),
                "щит или сервер прилёг на секунду: подтверждённый код через минуту всё ещё действует",
            ),
            HiccupCase(
                MockResponse().setResponseCode(429),
                "ограничитель запросов — временная причина, а не отказ во входе",
            ),
        )
        for (case in cases) {
            val vault = MemoryVault()
            server.enqueue(case.answer)
            server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
            server.enqueue(approvedAnswer())
            val approved = runBlocking { auth(vault).awaitApproval(pending(), periodMs = 1) }
            assertTrue("опрос обязан дождаться подтверждения: ${case.why}", approved)
            assertEquals("токен подтверждённого входа сохранён: ${case.why}", "access-1", vault.read(MillidaAuth.KEY_ACCESS))
        }
    }

    private data class RefusalCase(val status: String, val why: String)

    @Test
    fun `отказ сервера заканчивает ожидание и забывает код`() {
        val cases = listOf(
            RefusalCase("denied", "игрок нажал «Это не я»: опрашивать дальше незачем"),
            RefusalCase("expired", "код мёртв: перезапуск приложения не должен снова его ждать"),
        )
        for (case in cases) {
            val vault = MemoryVault()
            val auth = auth(vault)
            server.enqueue(initAnswer())
            val started = runBlocking { auth.startLogin(inline = true) }
            server.enqueue(MockResponse().setBody("""{"status":"${case.status}"}"""))
            val error = runCatching { runBlocking { auth.awaitApproval(started, periodMs = 1) } }.exceptionOrNull()
            assertTrue("отказ сервера окончателен: ${case.why}; было $error", error is LoginRefused)
            assertNull("код забыт: ${case.why}", auth.pendingLogin())
        }
    }

    @Test
    fun `вышедший срок кода заканчивает ожидание`() {
        val vault = MemoryVault()
        val auth = auth(vault)
        val approved = runBlocking { auth.awaitApproval(pending(expiresAtMs = System.currentTimeMillis() - 1), periodMs = 1) }
        assertFalse("просроченный код опрашивать незачем: он не может стать подтверждённым", approved)
    }

    @Test
    fun `перезапуск приложения продолжает вход тем же кодом`() {
        val vault = MemoryVault()
        server.enqueue(initAnswer())
        val started = runBlocking { auth(vault).startLogin(inline = true) }

        val restored = auth(vault).pendingLogin()
        assertEquals(
            "система закрыла приложение, пока игрок в браузере: новый код заставил бы подтверждать заново",
            started,
            restored,
        )
        assertEquals("начат на экране входа — на нём и продолжается", true, restored?.inline)
    }

    @Test
    fun `просроченный код после перезапуска не восстанавливается`() {
        val vault = MemoryVault()
        val auth = auth(vault)
        server.enqueue(initAnswer().also { it.setBody("""{"deviceCode":"d","userCode":"u","expiresInSec":0}""") })
        runBlocking { auth.startLogin() }
        assertNull("мёртвый код нечего показывать игроку", auth.pendingLogin())
        assertNull("мёртвый код не остаётся в хранилище", vault.read(MillidaAuth.KEY_PENDING_LOGIN))
    }

    @Test
    fun `кривая запись незавершённого входа считается её отсутствием`() {
        val vault = MemoryVault().apply { write(MillidaAuth.KEY_PENDING_LOGIN, "{broken") }
        val auth = auth(vault)
        assertNull("запись могла оборваться при гибели процесса: запуск приложения не должен падать", auth.pendingLogin())
        assertNull("кривая запись убрана", vault.read(MillidaAuth.KEY_PENDING_LOGIN))
    }

    @Test
    fun `подтверждённый вход без сессии игры не требует нового кода`() {
        val vault = MemoryVault()
        val auth = auth(vault)
        server.enqueue(initAnswer())
        val started = runBlocking { auth.startLogin() }
        server.enqueue(approvedAnswer())
        assertTrue(runBlocking { auth.awaitApproval(started, periodMs = 1) })

        val restored = auth(vault).pendingLogin()
        assertNotNull("код уже потрачен на сервере: без этой отметки игрока снова отправят подтверждать", restored)
        assertEquals("после перезапуска опрос пропускается", true, restored?.approved)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(sessionAnswer())
        val session = runBlocking { auth.awaitGameSession(periodMs = 1) }
        assertEquals("сбой сети при выдаче сессии только откладывает вход", "Nick", session.nick)

        auth.finishLogin()
        assertNull("завершённый вход не продолжается при следующем запуске", auth.pendingLogin())
        assertEquals("токены остаются после завершения", "access-1", vault.read(MillidaAuth.KEY_ACCESS))
    }

    @Test
    fun `отказ в сессии игры не повторяется бесконечно`() {
        val vault = MemoryVault()
        val auth = auth(vault)
        server.enqueue(initAnswer())
        val started = runBlocking { auth.startLogin() }
        server.enqueue(approvedAnswer())
        assertTrue(runBlocking { auth.awaitApproval(started, periodMs = 1) })

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        val error = runCatching { runBlocking { auth.awaitGameSession(periodMs = 1) } }.exceptionOrNull()
        assertTrue("сервер отверг токены: ждать нечего, нужен новый вход; было $error", error is Unauthorized)
        assertNull("мёртвый вход не продолжается при следующем запуске", auth.pendingLogin())
    }

    @Test
    fun `затянувшийся сбой выдачи сессии доходит до игрока`() {
        val vault = MemoryVault()
        val auth = auth(vault)
        server.enqueue(initAnswer())
        val started = runBlocking { auth.startLogin() }
        server.enqueue(approvedAnswer())
        assertTrue(runBlocking { auth.awaitApproval(started, periodMs = 1) })

        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        val error = runCatching { runBlocking { auth.awaitGameSession(periodMs = 1, windowMs = 0) } }.exceptionOrNull()
        assertNotNull("без предела игрок навсегда застрял бы на «Ждём подтверждения»", error)
        assertEquals("токены живы: следующая попытка обойдётся без нового кода", true, auth.pendingLogin()?.approved)
    }
}
