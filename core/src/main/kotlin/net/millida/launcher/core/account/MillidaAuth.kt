package net.millida.launcher.core.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.mojang.MojangJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

const val MILLIDA_API = "https://api.millida.net/v2"
const val MILLIDA_YGGDRASIL = "$MILLIDA_API/yggdrasil"

class Unauthorized(message: String = tr("сессия Millida недействительна")) : IOException(message)

data class LauncherInit(
    val deviceCode: String,
    val userCode: String,
    val verifyUrl: String,
    val expiresInSec: Int,
)

class MillidaAuth(
    private val client: OkHttpClient,
    private val vault: SecretVault,
    private val apiBase: String = MILLIDA_API,
) {
    suspend fun startLogin(clientName: String = "Millida Mobile"): LauncherInit {
        val response = call("/auth/launcher/init", "POST", buildJsonObject { put("clientName", clientName) }, null).asObject()
        val code = response.text("deviceCode")
        val user = response.text("userCode")
        if (code.isEmpty() || user.isEmpty()) throw IOException(tr("сервер не выдал код входа"))
        return LauncherInit(
            deviceCode = code,
            userCode = user,
            verifyUrl = response.text("verifyUrl").ifEmpty { "https://millida.net/auth/launcher" },
            expiresInSec = response.text("expiresInSec").toIntOrNull() ?: 600,
        )
    }

    suspend fun pollLogin(deviceCode: String): Boolean {
        val response = call("/auth/launcher/poll", "POST", buildJsonObject { put("deviceCode", deviceCode) }, null).asObject()
        when (val status = response.text("status")) {
            "ok" -> Unit
            "denied" -> throw IOException(tr("вход отклонён на странице Millida"))
            "expired" -> throw IOException(tr("код входа истёк — начни заново"))
            "pending", "" -> return false
            else -> throw IOException(tr("сервер ответил статусом {status}", status))
        }
        val access = response.text("accessToken")
        if (access.isEmpty()) throw IOException(tr("в ответе нет accessToken"))
        vault.write(KEY_ACCESS, access)
        response.text("refreshToken").takeIf { it.isNotEmpty() }?.let { vault.write(KEY_REFRESH, it) }
        return true
    }

    suspend fun profile(): JsonObject = authorized("/auth/me", "GET", null)

    suspend fun gameSession(): GameSession {
        var lastError = tr("не удалось получить сессию игры")
        repeat(SESSION_ATTEMPTS) { attempt ->
            try {
                val response = authorized("/launcher/game-session", "POST", buildJsonObject { })
                val token = response.text("accessToken")
                if (token.isNotEmpty()) {
                    return GameSession(
                        nick = response.text("name"),
                        uuid = response.text("uuid"),
                        accessToken = token,
                        yggdrasil = "$apiBase/yggdrasil",
                    )
                }
                lastError = tr("в ответе нет accessToken")
            } catch (unauthorized: Unauthorized) {
                throw unauthorized
            } catch (error: IOException) {
                lastError = error.message ?: lastError
            }
            if (attempt + 1 < SESSION_ATTEMPTS) delay(700L * (attempt + 1))
        }
        throw IOException(lastError)
    }

    suspend fun modSessionToken(): String? {
        if (!signedIn()) return null
        val response = try {
            authorizedElement("/launcher/mod-session", "POST", buildJsonObject { }, MOD_SESSION_TIMEOUT_MS)
        } catch (failure: IOException) {
            return null
        }
        val token = (response as? JsonObject)?.get("token") as? JsonPrimitive
        return token?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
    }

    fun signedIn(): Boolean = !vault.read(KEY_ACCESS).isNullOrEmpty()

    fun signOut() {
        vault.remove(KEY_ACCESS)
        vault.remove(KEY_REFRESH)
    }

    suspend fun authorized(path: String, method: String, payload: JsonObject?): JsonObject =
        authorizedElement(path, method, payload).asObject()

    /** An answer as it came: the status and the text, whatever the status. */
    data class RawAnswer(val status: Int, val body: String)

    /**
     * For website screens, which show the server's errors themselves: the answer is returned with its
     * status instead of being turned into an exception. A signed-in player goes with the session (renewed
     * once on 401); a guest goes without. The token never leaves this class.
     */
    suspend fun raw(path: String, method: String, bodyText: String?): RawAnswer {
        val token = vault.read(KEY_ACCESS)
        val first = rawCall(path, method, bodyText, token)
        if (first.status != 401 || token == null) return first
        val fresh = try {
            refresh(token)
        } catch (offline: IOException) {
            null
        } ?: return first
        return rawCall(path, method, bodyText, fresh)
    }

    private suspend fun rawCall(path: String, method: String, bodyText: String?, token: String?): RawAnswer =
        withContext(Dispatchers.IO) {
            if (!path.startsWith("/") || path.contains("..")) throw IOException("недопустимый путь запроса")
            val body = bodyText?.toRequestBody(JSON)
            val request = Request.Builder()
                .url(apiBase + path)
                .method(method, body ?: if (method == "GET") null else ByteArray(0).toRequestBody(JSON))
                .header("Accept", "application/json")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .build()
            client.newCall(request).execute().use { RawAnswer(it.code, it.body?.string().orEmpty()) }
        }

    /** Some of our endpoints answer with a list, not an object. */
    suspend fun authorizedElement(
        path: String,
        method: String,
        payload: JsonObject?,
        callTimeoutMs: Long = 0L,
    ): JsonElement {
        val token = vault.read(KEY_ACCESS) ?: throw Unauthorized(tr("вход в Millida не выполнен"))
        return try {
            call(path, method, payload, token, callTimeoutMs)
        } catch (unauthorized: Unauthorized) {
            val fresh = refresh(token) ?: throw unauthorized
            call(path, method, payload, fresh, callTimeoutMs)
        }
    }

    /**
     * For endpoints that also take guests. A signed-in player goes with the
     * session so the request is theirs; a dead session falls back to a guest
     * request instead of losing it. [withSession] false never sends it.
     */
    suspend fun publicCall(
        path: String,
        method: String,
        payload: JsonObject?,
        withSession: Boolean = true,
    ): JsonElement {
        if (withSession && signedIn()) {
            // A guest-friendly endpoint takes an expired token as no token and never answers 401,
            // so the refresh a 401 would trigger has to happen before the call.
            // A failed early refresh is not fatal here: the call below still goes out, as a guest if it must.
            vault.read(KEY_ACCESS)?.takeIf { expiresSoon(it) }?.let { stale ->
                try {
                    refresh(stale)
                } catch (unreachable: IOException) {
                    null
                }
            }
            val member = try {
                authorizedElement(path, method, payload)
            } catch (expired: Unauthorized) {
                null
            }
            if (member != null) return member
        }
        return call(path, method, payload, null)
    }

    private fun expiresSoon(token: String): Boolean = try {
        val claims = token.split('.').getOrNull(1)
        val exp = claims?.let {
            val json = String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8)
            (MojangJson.parseToJsonElement(json).jsonObject["exp"] as? JsonPrimitive)?.longOrNull
        }
        exp != null && exp * 1000 <= System.currentTimeMillis() + EXPIRY_MARGIN_MS
    } catch (malformed: IllegalArgumentException) {
        false
    }

    /**
     * Serialised: two parallel calls that both see 401 would spend the same
     * refresh token twice, and the server treats reuse as a stolen session.
     * Only a 401 from the server ends the session; a network failure is thrown
     * as is, so a dropped connection never reads as "sign in again".
     */
    @Throws(IOException::class)
    private suspend fun refresh(spent: String?): String? = refreshLock.withLock {
        val current = vault.read(KEY_ACCESS)
        if (!current.isNullOrEmpty() && current != spent) return@withLock current
        val refreshToken = vault.read(KEY_REFRESH) ?: return@withLock null
        return@withLock try {
            val (body, cookies) = exchange("/auth/refresh", "POST", buildJsonObject { put("refreshToken", refreshToken) }, null)
            val response = body.asObject()
            val access = response.text("accessToken")
            if (access.isEmpty()) return@withLock null
            vault.write(KEY_ACCESS, access)
            // The server rotates the refresh token on every renewal and hands the new one in
            // the rt2 cookie only. Keeping the spent one means the next renewal reuses it, the
            // server takes that for a stolen session and ends it: «Вход Millida истёк» in two days.
            val renewed = response.text("refreshToken").ifEmpty { refreshFromCookies(cookies).orEmpty() }
            if (renewed.isNotEmpty()) vault.write(KEY_REFRESH, renewed)
            access
        } catch (unauthorized: Unauthorized) {
            signOut()
            null
        }
    }

    private suspend fun call(
        path: String,
        method: String,
        payload: JsonObject?,
        token: String?,
        callTimeoutMs: Long = 0L,
    ): JsonElement = exchange(path, method, payload, token, callTimeoutMs).first

    /** The answer together with its Set-Cookie lines. */
    private suspend fun exchange(
        path: String,
        method: String,
        payload: JsonObject?,
        token: String?,
        callTimeoutMs: Long = 0L,
    ): Pair<JsonElement, List<String>> = withContext(Dispatchers.IO) {
        if (!path.startsWith("/") || path.contains("..")) throw IOException(tr("недопустимый путь запроса"))
        val body = payload?.let {
            MojangJson.encodeToString(JsonObject.serializer(), it).toRequestBody(JSON)
        }
        val request = Request.Builder()
            .url(apiBase + path)
            .method(method, body ?: if (method == "GET") null else ByteArray(0).toRequestBody(JSON))
            .header("Accept", "application/json")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        val pending = client.newCall(request)
        if (callTimeoutMs > 0) pending.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        pending.execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401) throw Unauthorized()
            if (!response.isSuccessful) {
                throw IOException(errorMessage(text) ?: tr("сервер ответил {code}", response.code))
            }
            val cookies = response.headers("Set-Cookie")
            (if (text.isBlank()) buildJsonObject { } else parseBody(text)) to cookies
        }
    }

    private fun parseBody(text: String): JsonElement = try {
        MojangJson.parseToJsonElement(text)
    } catch (broken: SerializationException) {
        throw IOException(tr("сервер Millida ответил не JSON — попробуй ещё раз позже"), broken)
    }

    private fun JsonElement.asObject(): JsonObject =
        this as? JsonObject ?: throw IOException(tr("сервер вернул не тот ответ, что ожидался"))

    private fun errorMessage(text: String): String? = try {
        val json = MojangJson.parseToJsonElement(text).jsonObject
        json["message"].firstText() ?: json["error"].firstText()
    } catch (ignored: Exception) {
        null
    }

    private fun JsonElement?.firstText(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonArray -> (firstOrNull() as? JsonPrimitive)?.contentOrNull
        else -> null
    }?.takeIf { it.isNotBlank() }

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

    private val refreshLock = Mutex()

    companion object {
        /** The cookie the server puts the renewed refresh token in (F-C3). */
        private const val REFRESH_COOKIE = "rt2"

        /** The renewed refresh token from Set-Cookie lines; an emptied (cleared) cookie is no token. */
        internal fun refreshFromCookies(lines: List<String>): String? = lines.firstNotNullOfOrNull { line ->
            val pair = line.substringBefore(';').trim()
            val name = pair.substringBefore('=', "").trim()
            pair.substringAfter('=', "").trim().removeSurrounding("\"").takeIf { name == REFRESH_COOKIE && it.isNotEmpty() }
        }

        const val KEY_ACCESS = "millida-access"
        const val KEY_REFRESH = "millida-refresh"
        private const val SESSION_ATTEMPTS = 3
        private const val MOD_SESSION_TIMEOUT_MS = 6_000L
        private const val EXPIRY_MARGIN_MS = 30_000L
        private val JSON = "application/json".toMediaType()
    }
}
