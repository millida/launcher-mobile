package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.DevicePoll
import net.millida.launcher.core.account.MicrosoftAuth
import net.millida.launcher.core.account.MicrosoftSignInRequired
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MicrosoftDeviceFlowTest {

    private val routes = HashMap<String, ArrayDeque<Pair<Int, String>>>()
    private val seen = ArrayList<Request>()

    private fun route(address: String, body: String, code: Int = 200) {
        synchronized(routes) { routes.getOrPut(address) { ArrayDeque() }.addLast(code to body) }
    }

    private fun auth(): MicrosoftAuth {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    synchronized(seen) { seen += request }
                    val key = request.url.host + request.url.encodedPath
                    val (code, body) = synchronized(routes) { routes[key]?.removeFirstOrNull() }
                        ?: throw IOException("нет связи с $key")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("fake")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        return MicrosoftAuth(client)
    }

    private fun sent(address: String): Request = synchronized(seen) {
        seen.first { it.url.host + it.url.encodedPath == address }
    }

    private fun form(request: Request): Map<String, String> {
        val body = request.body as FormBody
        return (0 until body.size).associate { body.name(it) to body.value(it) }
    }

    private fun json(request: Request): String {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun xboxChain(profile: String = """{"id":"0123abcd","name":"Steve"}""", minecraft: String = """{"access_token":"MC","expires_in":3600}""") {
        route(XBL, """{"Token":"XBL-TOKEN","DisplayClaims":{"xui":[{"uhs":"UHS1"}]}}""")
        route(XSTS, """{"Token":"XSTS-TOKEN","DisplayClaims":{"xui":[{"uhs":"UHS1","xid":"2535"}]}}""")
        route(LOGIN, minecraft)
        route(PROFILE, profile)
    }

    @Test
    fun `код устройства разбирается, пропуски получают значения Microsoft`() {
        runBlocking {
            route(DEVICE, """{"device_code":"D1","user_code":"ABCD-EFGH","verification_uri":"https://microsoft.com/devicelogin","interval":7,"expires_in":600}""")
            route(DEVICE, """{"device_code":"D2","user_code":"IJKL"}""")
            val microsoft = auth()

            val full = microsoft.startDeviceFlow()
            val sparse = microsoft.startDeviceFlow()

            assertEquals("ABCD-EFGH", full.userCode)
            assertEquals("https://microsoft.com/devicelogin", full.verificationUri)
            assertEquals("опрос не чаще, чем просит Microsoft", 7, full.intervalSec)
            assertEquals(600, full.expiresInSec)
            assertEquals("без адреса игрок идёт на короткую ссылку Microsoft", "https://microsoft.com/link", sparse.verificationUri)
            assertEquals("интервал по умолчанию — 5 секунд по RFC 8628", 5, sparse.intervalSec)
            assertEquals("срок по умолчанию — 15 минут", 900, sparse.expiresInSec)
            val fields = form(sent(DEVICE))
            assertEquals(MicrosoftAuth.DEFAULT_CLIENT_ID, fields["client_id"])
            assertEquals("offline_access нужен, чтобы вход не слетал через час", "XboxLive.signin offline_access", fields["scope"])
        }
    }

    private data class StartCase(val body: String, val message: String, val why: String)

    @Test
    fun `отказ выдать код объясняется текстом Microsoft`() {
        runBlocking {
            val cases = listOf(
                StartCase("""{"error":"unauthorized_client","error_description":"AADSTS700016: app not found"}""", "AADSTS700016: app not found", "описание ошибки важнее кода"),
                StartCase("""{"error":"invalid_scope"}""", "invalid_scope", "без описания показывается код"),
                StartCase("""{"device_code":"D"}""", "Microsoft не выдал код устройства", "без кода для игрока вводить нечего"),
                StartCase("""{"user_code":"U"}""", "Microsoft не выдал код устройства", "без device_code нечего опрашивать"),
            )
            val microsoft = auth()
            for (case in cases) {
                route(DEVICE, case.body, 400)
                val error = runCatching { microsoft.startDeviceFlow() }.exceptionOrNull()
                assertTrue("${case.body}: ${case.why}", error is IOException)
                assertEquals("${case.body}: ${case.why}", case.message, error!!.message)
            }
        }
    }

    private data class PollCase(val body: String, val pending: Boolean, val message: String?, val why: String)

    @Test
    fun `опрос входа отличает ожидание от отказа`() {
        runBlocking {
            val cases = listOf(
                PollCase("""{"error":"authorization_pending"}""", true, null, "игрок ещё не ввёл код"),
                PollCase("""{"error":"slow_down"}""", true, null, "просьба замедлиться — не повод обрывать вход"),
                PollCase("""{"error":"expired_token","error_description":"Код истёк"}""", false, "Код истёк", "истёкший код — отказ с текстом"),
                PollCase("""{"error":"access_denied"}""", false, "access_denied", "игрок отказал — без описания видно код"),
                PollCase("""{"token_type":"Bearer"}""", false, "Microsoft не выдал токен", "ответ без ошибки и без токена"),
            )
            val microsoft = auth()
            for (case in cases) {
                route(TOKEN, case.body, 400)
                val result = runCatching { microsoft.poll("D1") }
                if (case.pending) {
                    assertEquals("${case.body}: ${case.why}", DevicePoll.Pending, result.getOrNull())
                } else {
                    assertEquals("${case.body}: ${case.why}", case.message, result.exceptionOrNull()?.message)
                }
            }
            val fields = form(sent(TOKEN))
            assertEquals("urn:ietf:params:oauth:grant-type:device_code", fields["grant_type"])
            assertEquals("D1", fields["device_code"])
        }
    }

    @Test
    fun `готовый вход проходит цепочку Xbox и отдаёт сессию игры`() {
        runBlocking {
            route(TOKEN, """{"access_token":"MS","refresh_token":"R1"}""")
            xboxChain()

            val ready = auth().poll("D1") as DevicePoll.Ready

            assertEquals("Steve", ready.session.nick)
            assertEquals("0123abcd", ready.session.uuid)
            assertEquals("в игру уходит токен Minecraft, а не Microsoft", "MC", ready.session.accessToken)
            assertEquals("xuid берётся из XSTS — без него не работает чат", "2535", ready.session.xuid)
            assertEquals("R1", ready.refreshToken)
            assertEquals(3600L, ready.expiresInSec)
            assertTrue("лицензионная сессия без своего сервера входа", ready.session.licensed)

            assertTrue("билет Xbox собран из токена Microsoft", json(sent(XBL)).contains("\"RpsTicket\":\"d=MS\""))
            val xsts = Json.parseToJsonElement(json(sent(XSTS))).jsonObject
            assertEquals(
                "XSTS получает токен Xbox Live",
                "XBL-TOKEN",
                xsts["Properties"]?.jsonObject?.get("UserTokens")?.jsonArray?.single()?.jsonPrimitive?.content,
            )
            assertEquals("rp://api.minecraftservices.com/", xsts["RelyingParty"]?.jsonPrimitive?.content)
            val login = Json.parseToJsonElement(json(sent(LOGIN))).jsonObject
            assertEquals("XBL3.0 x=UHS1;XSTS-TOKEN", login["identityToken"]?.jsonPrimitive?.content)
            assertEquals("Bearer MC", sent(PROFILE).header("Authorization"))
        }
    }

    @Test
    fun `срок токена Minecraft по умолчанию — сутки`() {
        runBlocking {
            route(TOKEN, """{"access_token":"MS","refresh_token":"R1"}""")
            xboxChain(minecraft = """{"access_token":"MC"}""")

            val ready = auth().poll("D1") as DevicePoll.Ready

            assertEquals(86400L, ready.expiresInSec)
        }
    }

    private data class ChainCase(val xsts: String, val minecraft: String, val profile: String, val message: String, val why: String)

    @Test
    fun `сбой в цепочке Xbox называет шаг и что делать`() {
        runBlocking {
            val cases = listOf(
                ChainCase(
                    """{"Identity":"0","XErr":2148916233}""",
                    """{"access_token":"MC"}""",
                    """{"name":"Steve"}""",
                    "XSTS отказал — на аккаунте нет профиля Xbox",
                    "у аккаунта нет профиля Xbox — частая причина у новичков",
                ),
                ChainCase(
                    """{"Token":"XSTS-TOKEN"}""",
                    """{"error":"TOO_MANY_REQUESTS"}""",
                    """{"name":"Steve"}""",
                    "Minecraft отказал в сессии — повтори через минуту",
                    "Minecraft не выдал токен",
                ),
                ChainCase(
                    """{"Token":"XSTS-TOKEN"}""",
                    """{"access_token":"MC"}""",
                    """{"path":"/minecraft/profile","error":"NOT_FOUND"}""",
                    "На этом аккаунте нет лицензии Minecraft",
                    "аккаунт Microsoft без купленной игры",
                ),
            )
            for (case in cases) {
                synchronized(routes) { routes.clear() }
                route(TOKEN, """{"access_token":"MS"}""")
                route(XBL, """{"Token":"XBL-TOKEN","DisplayClaims":{"xui":[{"uhs":"UHS1"}]}}""")
                route(XSTS, case.xsts, 401)
                route(LOGIN, case.minecraft)
                route(PROFILE, case.profile, 404)
                val error = runCatching { auth().poll("D1") }.exceptionOrNull()
                assertEquals(case.why, case.message, error?.message)
            }
        }
    }

    @Test
    fun `Xbox без идентификатора пользователя — понятная ошибка`() {
        runBlocking {
            route(TOKEN, """{"access_token":"MS"}""")
            route(XBL, """{"Token":"XBL-TOKEN","DisplayClaims":{"xui":[]}}""")

            val error = runCatching { auth().poll("D1") }.exceptionOrNull()

            assertEquals("Xbox Live не вернул идентификатор пользователя", error?.message)
        }
    }

    @Test
    fun `протухший refresh требует нового входа, живой сохраняет старый токен`() {
        runBlocking {
            route(TOKEN, """{"error":"invalid_grant","error_description":"AADSTS70000"}""", 400)
            route(TOKEN, """{"error":"interaction_required"}""", 400)
            route(TOKEN, """{"access_token":"MS"}""")
            xboxChain()
            val microsoft = auth()

            val expired = runCatching { microsoft.refresh("R0") }.exceptionOrNull()
            val interaction = runCatching { microsoft.refresh("R0") }.exceptionOrNull()
            val ready = microsoft.refresh("R0")

            assertTrue("invalid_grant — вход заново, а не «повтори позже»", expired is MicrosoftSignInRequired)
            assertTrue("interaction_required — тоже вход заново", interaction is MicrosoftSignInRequired)
            assertEquals("Microsoft не вернул новый refresh — старый не теряется", "R0", ready.refreshToken)
            assertEquals("refresh_token", form(sent(TOKEN))["grant_type"])
        }
    }

    @Test
    fun `refresh без токена и без понятной ошибки просит повторить`() {
        runBlocking {
            route(TOKEN, """{"error":"temporarily_unavailable"}""", 503)

            val error = runCatching { auth().refresh("R0") }.exceptionOrNull()

            assertFalse("временный сбой — не повод выкидывать игрока из аккаунта", error is MicrosoftSignInRequired)
            assertEquals("Microsoft не ответил — повтори через минуту", error?.message)
        }
    }

    private data class BodyCase(val body: String, val code: Int, val why: String)

    @Test
    fun `нечитаемый ответ сервера входа называет код`() {
        runBlocking {
            val cases = listOf(
                BodyCase("", 502, "пустое тело шлюза"),
                BodyCase("<html>Bad gateway</html>", 502, "страница шлюза вместо JSON"),
                BodyCase("[1,2]", 500, "JSON, но не объект"),
            )
            val microsoft = auth()
            for (case in cases) {
                route(DEVICE, case.body, case.code)
                val error = runCatching { microsoft.startDeviceFlow() }.exceptionOrNull()
                assertEquals(case.why, "Сервер входа Microsoft ответил ${case.code} — повтори позже", error?.message)
            }
        }
    }

    @Test
    fun `проверка токена Minecraft различает отказ и невозможность спросить`() {
        runBlocking {
            route(PROFILE, """{"error":"UNAUTHORIZED"}""", 401)
            route(PROFILE, """{"name":"Steve"}""")
            route(PROFILE, """{"error":"busy"}""", 503)
            val microsoft = auth()

            assertEquals("401 — токен мёртв", false, microsoft.tokenAccepted("T"))
            assertEquals("200 — токен жив", true, microsoft.tokenAccepted("T"))
            assertNull("503 — неизвестно, токен не выбрасываем", microsoft.tokenAccepted("T"))
            assertNull("нет сети — неизвестно, токен не выбрасываем", microsoft.tokenAccepted("T"))
            assertEquals("Bearer T", sent(PROFILE).header("Authorization"))
        }
    }

    private companion object {
        const val DEVICE = "login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
        const val TOKEN = "login.microsoftonline.com/consumers/oauth2/v2.0/token"
        const val XBL = "user.auth.xboxlive.com/user/authenticate"
        const val XSTS = "xsts.auth.xboxlive.com/xsts/authorize"
        const val LOGIN = "api.minecraftservices.com/authentication/login_with_xbox"
        const val PROFILE = "api.minecraftservices.com/minecraft/profile"
    }
}
