package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.call.CallClient
import net.millida.launcher.core.call.IceConfigs
import net.millida.launcher.core.hub.HubClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * | вход                                              | вердикт                                                    |
 * | ------------------------------------------------- | ---------------------------------------------------------- |
 * | 401, обновление токена рвётся по сети             | IOException, но не Unauthorized; вход сохранён             |
 * | 401, обновление токена отвечает 500               | IOException, но не Unauthorized; вход сохранён             |
 * | 401, обновление токена отвечает 401               | Unauthorized, вход стёрт                                   |
 * | ответ не JSON                                     | IOException, а не SerializationException                   |
 * | путь с `..`                                       | IOException, а не IllegalArgumentException                 |
 * | ICE отвечает не JSON                              | запасной STUN, а не падение звонка                         |
 * | serverOnline("../x")                              | IOException                                                |
 */
class MillidaAuthFaultsTest {

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

    private fun auth(vault: MemoryVault = signedIn()) =
        MillidaAuth(
            OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            vault,
            server.url("/v2").toString().trimEnd('/'),
        )

    private fun signedIn(): MemoryVault = MemoryVault().apply {
        write(MillidaAuth.KEY_ACCESS, "old-access")
        write(MillidaAuth.KEY_REFRESH, "refresh-1")
    }

    private data class RefreshCase(
        val refreshAnswer: MockResponse,
        val signedOut: Boolean,
        val why: String,
    )

    @Test
    fun `сбой сети при обновлении входа не выдаётся за истёкший вход`() {
        val cases = listOf(
            RefreshCase(
                // The refresh rides the kept-alive connection of the 401, where only a per-request policy cuts it.
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
                false,
                "обрыв сети: игрок видел «Вход Millida истёк» и входил заново, хотя пропал только интернет",
            ),
            RefreshCase(
                MockResponse().setResponseCode(500).setBody("""{"message":"упал"}"""),
                false,
                "сервер прилёг — сессия жива, выкидывать игрока нельзя",
            ),
            RefreshCase(
                MockResponse().setResponseCode(401),
                true,
                "сервер сам отказал в обновлении — вот это и есть истёкший вход",
            ),
        )
        for (case in cases) {
            val vault = signedIn()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(case.refreshAnswer)
            val error = runCatching { runBlocking { auth(vault).profile() } }.exceptionOrNull()
            assertTrue("ошибка должна быть IOException: ${case.why}; было $error", error is IOException)
            assertEquals(
                "Unauthorized только при отказе сервера: ${case.why}",
                case.signedOut,
                error is Unauthorized,
            )
            assertEquals("вход стирается только при отказе сервера: ${case.why}", !case.signedOut, vault.read(MillidaAuth.KEY_REFRESH) != null)
        }
    }

    private data class FaultCase(val call: suspend () -> Any?, val why: String)

    @Test
    fun `кривой ответ и кривой путь превращаются в IOException`() {
        val cases = listOf(
            FaultCase(
                {
                    server.enqueue(MockResponse().setBody("<html>502 Bad Gateway</html>"))
                    auth().profile()
                },
                "щит отдал HTML: вызывающие ловят только IOException, SerializationException ронял экран",
            ),
            FaultCase(
                {
                    server.enqueue(MockResponse().setBody("[1,2]"))
                    auth().profile()
                },
                "список вместо объекта — тот же неожиданный ответ",
            ),
            FaultCase(
                { auth().authorized("/files/../admin", "GET", null) },
                "путь с выходом наверх: IllegalArgumentException проходил мимо catch (IOException)",
            ),
            FaultCase(
                { HubClient(auth()).serverOnline("../x") },
                "слаг карточки приходит извне и не должен ронять экран хаба",
            ),
        )
        for (case in cases) {
            val error = runCatching { runBlocking { case.call() } }.exceptionOrNull()
            assertNotNull("ожидалась ошибка: ${case.why}", error)
            assertTrue("ошибка должна быть IOException: ${case.why}; было $error", error is IOException)
        }
    }

    @Test
    fun `ICE с ответом не JSON уходит на запасной STUN`() {
        server.enqueue(MockResponse().setBody("not json"))
        val config = runBlocking { CallClient(auth()).ice() }
        assertEquals("звонок обязан начаться даже при сломанной ручке ICE", IceConfigs.FALLBACK, config)
        assertFalse("запасной конфиг не прячет ошибку под видом ответа сервера", config.servers.isEmpty())
    }
}
