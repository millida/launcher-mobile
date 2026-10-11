package net.millida.launcher.core.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.millida.launcher.core.http.NetworkFaults
import net.millida.launcher.core.mojang.MojangJson
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

data class DeviceCodePrompt(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSec: Int,
    val expiresInSec: Int,
)

class MicrosoftSignInRequired(message: String) : IOException(message)

sealed interface DevicePoll {
    data object Pending : DevicePoll
    data class Ready(val session: GameSession, val refreshToken: String, val expiresInSec: Long) : DevicePoll
}

class MicrosoftAuth(
    private val client: OkHttpClient,
    private val clientId: String = DEFAULT_CLIENT_ID,
) {
    suspend fun startDeviceFlow(): DeviceCodePrompt = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", "XboxLive.signin offline_access")
            .build()
        val json = postJson("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", body)
        json["error"]?.let { throw IOException(json.text("error_description").ifEmpty { it.jsonPrimitive.content }) }
        val device = json.text("device_code")
        val user = json.text("user_code")
        if (device.isEmpty() || user.isEmpty()) throw IOException("Microsoft не выдал код устройства")
        DeviceCodePrompt(
            deviceCode = device,
            userCode = user,
            verificationUri = json.text("verification_uri").ifEmpty { "https://microsoft.com/link" },
            intervalSec = json.number("interval", 5).toInt(),
            expiresInSec = json.number("expires_in", 900).toInt(),
        )
    }

    suspend fun poll(deviceCode: String): DevicePoll = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("device_code", deviceCode)
            .build()
        val json = postJson("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", body)
        val error = json.text("error")
        if (error.isNotEmpty()) {
            if (error == "authorization_pending" || error == "slow_down") return@withContext DevicePoll.Pending
            throw IOException(json.text("error_description").ifEmpty { error })
        }
        val msToken = json.text("access_token")
        if (msToken.isEmpty()) throw IOException("Microsoft не выдал токен")
        val chain = xboxChain(msToken)
        DevicePoll.Ready(chain.first, json.text("refresh_token"), chain.second)
    }

    suspend fun refresh(refreshToken: String): DevicePoll.Ready = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("scope", "XboxLive.signin offline_access")
            .build()
        val json = postJson("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", body)
        val error = json.text("error")
        if (error == "invalid_grant" || error == "interaction_required") {
            throw MicrosoftSignInRequired("Сессия Microsoft истекла — войди заново")
        }
        val msToken = json.text("access_token")
        if (msToken.isEmpty()) throw IOException("Microsoft не ответил — повтори через минуту")
        val chain = xboxChain(msToken)
        DevicePoll.Ready(chain.first, json.text("refresh_token").ifEmpty { refreshToken }, chain.second)
    }

    /**
     * Whether Minecraft still takes the game token: false only on 401, null when
     * the question could not be asked (no network, a server hiccup) and the
     * token may well be alive.
     */
    suspend fun tokenAccepted(accessToken: String): Boolean? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(PROFILE_URL).header("Authorization", "Bearer $accessToken").build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> false
                    response.isSuccessful -> true
                    else -> null
                }
            }
        } catch (network: IOException) {
            null
        }
    }

    private fun xboxChain(msToken: String): Pair<GameSession, Long> {
        val xbl = postJson(
            "https://user.auth.xboxlive.com/user/authenticate",
            buildJsonObject {
                putJsonObject("Properties") {
                    put("AuthMethod", "RPS")
                    put("SiteName", "user.auth.xboxlive.com")
                    put("RpsTicket", "d=$msToken")
                }
                put("RelyingParty", "http://auth.xboxlive.com")
                put("TokenType", "JWT")
            }
        )
        val xblToken = xbl.text("Token")
        if (xblToken.isEmpty()) throw IOException("Xbox Live отказал во входе — повтори через минуту")
        val uhs = xbl["DisplayClaims"]?.jsonObject?.get("xui")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("uhs")?.jsonPrimitive?.content
            ?: throw IOException("Xbox Live не вернул идентификатор пользователя")

        val xsts = postJson(
            "https://xsts.auth.xboxlive.com/xsts/authorize",
            buildJsonObject {
                putJsonObject("Properties") {
                    put("SandboxId", "RETAIL")
                    putJsonArray("UserTokens") { add(kotlinx.serialization.json.JsonPrimitive(xblToken)) }
                }
                put("RelyingParty", "rp://api.minecraftservices.com/")
                put("TokenType", "JWT")
            }
        )
        val xstsToken = xsts.text("Token")
        if (xstsToken.isEmpty()) throw IOException("XSTS отказал — на аккаунте нет профиля Xbox")
        val xuid = xsts["DisplayClaims"]?.jsonObject?.get("xui")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("xid")?.jsonPrimitive?.content.orEmpty()

        val mc = postJson(
            "https://api.minecraftservices.com/authentication/login_with_xbox",
            buildJsonObject { put("identityToken", "XBL3.0 x=$uhs;$xstsToken") }
        )
        val mcToken = mc.text("access_token")
        if (mcToken.isEmpty()) throw IOException("Minecraft отказал в сессии — повтори через минуту")
        val expires = mc.number("expires_in", 86400)

        val profile = getJson(PROFILE_URL, mcToken)
        val nick = profile.text("name")
        if (nick.isEmpty()) throw IOException("На этом аккаунте нет лицензии Minecraft")

        return GameSession(
            nick = nick,
            uuid = profile.text("id"),
            accessToken = mcToken,
            xuid = xuid,
        ) to expires
    }

    private fun postJson(url: String, form: FormBody): JsonObject {
        val request = Request.Builder().url(url).post(form).build()
        return execute(request)
    }

    private fun postJson(url: String, payload: JsonObject): JsonObject {
        val body = MojangJson.encodeToString(JsonObject.serializer(), payload)
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body)
            .header("Accept", "application/json")
            .build()
        return execute(request)
    }

    private fun getJson(url: String, bearer: String): JsonObject {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $bearer").build()
        return execute(request)
    }

    /** The body is read whatever the status: the token endpoint sends its verdicts (authorization_pending, invalid_grant) with 400. */
    private fun execute(request: Request): JsonObject {
        call(request).use { response ->
            val text = response.body?.string().orEmpty()
            val unreadable = "Сервер входа Microsoft ответил ${response.code} — повтори позже"
            if (text.isBlank()) throw IOException(unreadable)
            return try {
                MojangJson.parseToJsonElement(text).jsonObject
            } catch (malformed: IllegalArgumentException) {
                throw IOException(unreadable, malformed)
            }
        }
    }

    // the system message of UnknownHostException is English and tells the player nothing
    private fun call(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (network: IOException) {
        NetworkFaults.unknownHost(network) ?: throw network
        throw IOException(
            NetworkFaults.nameLookupAdvice("сервера входа Microsoft (${request.url.host})"),
            network,
        )
    }

    private fun JsonObject.text(key: String): String =
        this[key]?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.number(key: String, fallback: Long): Long =
        this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: fallback

    companion object {
        const val DEFAULT_CLIENT_ID = "499c8d36-be2a-4231-9ebd-ef291b7bb64c"
        private const val PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"
    }
}
