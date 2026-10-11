package net.millida.launcher.core.call

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.friends.FriendsError
import net.millida.launcher.core.friends.FriendsErrors
import java.io.IOException
import java.net.URLEncoder

data class VoiceMember(val userId: String, val since: Long, val muted: Boolean, val screen: Boolean)

data class VoiceReply(val me: String, val members: List<VoiceMember>)

/**
 * Call signalling and group voice over the very endpoints the desktop launcher
 * uses (src/lib/call/signal.ts, src/state/call.ts). The server only relays
 * envelopes and hands out relay logins; voice goes peer to peer.
 */
class CallClient(private val auth: MillidaAuth, private val now: () -> Long = System::currentTimeMillis) {

    @Volatile
    private var cachedIce: Pair<IceConfig, Long>? = null

    suspend fun signal(
        callId: String,
        peerId: String,
        kind: CallSignalKind,
        data: JsonObject = JsonObject(emptyMap()),
        seconds: Long? = null,
        roomId: String? = null,
    ) = guarded("Сигнал звонка не ушёл") {
        auth.authorized("/friends/call/signal", "POST", CallEnvelopes.signalBody(callId, peerId, kind, data, seconds, roomId))
        Unit
    }

    /**
     * The server holds the request until the first envelope or 45 seconds, so a
     * call rings at once without a socket. [hold] false turns it into a plain read.
     */
    suspend fun poll(after: Long, hold: Boolean): CallInbox = guarded("Звонки не обновились") {
        val path = "/friends/call/poll?after=" + after.coerceAtLeast(0) + if (hold) "" else "&wait=0"
        CallEnvelopes.parseInbox(auth.authorized(path, "GET", null))
    }

    /** Relay logins live an hour, so they are fetched once and reused; a failure falls back to STUN. */
    suspend fun ice(): IceConfig {
        cachedIce?.let { (config, until) -> if (until > now()) return config }
        val parsed = try {
            IceConfigs.parse(auth.authorized("/friends/call/ice", "GET", null))
        } catch (error: IOException) {
            null
        } ?: return IceConfigs.FALLBACK
        val (config, seconds) = parsed
        cachedIce = config to now() + seconds * 1000
        return config
    }

    suspend fun joinVoice(roomId: String): VoiceReply = guarded("Не удалось войти в разговор") {
        voiceReply(auth.authorized(voicePath(roomId, "join"), "POST", buildJsonObject { }))
    }

    suspend fun beatVoice(roomId: String, muted: Boolean, deafened: Boolean): VoiceReply = guarded("Голос группы не ответил") {
        val body = buildJsonObject {
            put("muted", muted)
            put("deafened", deafened)
            put("screen", false)
        }
        voiceReply(auth.authorized(voicePath(roomId, "beat"), "POST", body))
    }

    suspend fun leaveVoice(roomId: String) = guarded("Не удалось выйти из разговора") {
        auth.authorized(voicePath(roomId, "leave"), "POST", buildJsonObject { })
        Unit
    }

    /** How many were called; zero means everyone is already inside. */
    suspend fun ringRoom(roomId: String): Int = guarded("Не удалось позвать") {
        auth.authorized(voicePath(roomId, "ring"), "POST", buildJsonObject { }).finite("called")?.toInt() ?: 0
    }

    private fun voicePath(roomId: String, action: String): String {
        val id = roomId.trim()
        if (id.isEmpty() || id.contains('/') || id.contains("..")) throw FriendsError("Неверная группа")
        return "/friends/rooms/" + URLEncoder.encode(id, "UTF-8").replace("+", "%20") + "/voice/" + action
    }

    private inline fun <T> guarded(fallback: String, block: () -> T): T = try {
        block()
    } catch (already: FriendsError) {
        throw already
    } catch (error: IOException) {
        throw FriendsErrors.wrap(error, fallback)
    }

    companion object {
        fun voiceReply(response: JsonObject): VoiceReply = VoiceReply(
            me = response.string("me").orEmpty(),
            members = voiceMembers(response["members"] as? JsonArray),
        )

        fun voiceMembers(raw: JsonArray?): List<VoiceMember> = raw.orEmpty().mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val user = row.string("userId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VoiceMember(
                userId = user,
                since = row.finite("since")?.toLong() ?: 0L,
                muted = row.flag("muted") ?: false,
                screen = row.flag("screen") ?: false,
            )
        }.distinctBy { it.userId }
    }
}
