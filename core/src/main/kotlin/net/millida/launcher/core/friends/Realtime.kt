package net.millida.launcher.core.friends

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.mojang.MojangJson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import kotlin.math.min
import kotlin.math.roundToLong

/** Pokes the server sends to a personal channel; a poke carries no data, the client refetches. */
enum class RealtimeTopic(val wire: String) {
    FRIENDS("friends"),
    PRESENCE("presence"),
    CALLS("calls"),
    INBOX("inbox"),
    HOSTING("hosting"),
    ACCOUNT("account"),
}

sealed class RealtimeGrant {
    object Off : RealtimeGrant()

    data class On(val url: String, val token: String) : RealtimeGrant()
}

/** One parsed message of the Centrifugo JSON protocol, only what this client acts on. */
sealed class CentrifugeFrame {
    object Ping : CentrifugeFrame()

    data class Connected(val id: Long, val ttlSeconds: Long?, val pong: Boolean) : CentrifugeFrame()

    data class Refreshed(val id: Long, val ttlSeconds: Long?) : CentrifugeFrame()

    data class Failed(val id: Long, val code: Long, val message: String) : CentrifugeFrame()

    data class Publication(val channel: String, val data: JsonElement?) : CentrifugeFrame()

    data class Disconnect(val code: Long, val reason: String) : CentrifugeFrame()
}

/**
 * Rules shared with the desktop (src/lib/realtimePace.ts): which address may
 * receive the token, which channel a poke may come from and how the delta poll
 * paces itself around a live socket.
 */
object Realtime {

    const val HOST = "api.millida.net"

    const val STRETCHED_MS = 120_000L
    const val OFF_RETRY_MS = 600_000L
    const val ERROR_RETRY_MS = 60_000L

    const val POLL_BASE_MS = 5_000L
    const val POLL_SERVER_MAX_MS = 30_000L
    const val POLL_HIDDEN_MIN_MS = 30_000L
    const val POLL_FAIL_MAX_MS = 60_000L
    const val POLL_AFTER_WAIT_MS = 1_000L

    private const val PERSONAL_PREFIX = "personal:#"

    /** The token endpoint names the socket address; only our own host may receive the token. */
    fun readGrant(body: JsonObject): RealtimeGrant {
        val enabled = body["enabled"] as? JsonPrimitive
        if (enabled == null || enabled.isString || enabled.content != "true") return RealtimeGrant.Off
        val token = body.plain("token")
        val url = body.plain("url")
        if (token.isEmpty() || url.isEmpty()) return RealtimeGrant.Off
        val parsed = runCatching { URI(url) }.getOrNull() ?: return RealtimeGrant.Off
        val ours = parsed.scheme.equals("wss", ignoreCase = true) &&
            parsed.host.equals(HOST, ignoreCase = true) &&
            parsed.port == -1 &&
            parsed.rawUserInfo == null
        return if (ours) RealtimeGrant.On(parsed.toString(), token) else RealtimeGrant.Off
    }

    fun pokeTopic(channel: String?, data: JsonElement?): RealtimeTopic? {
        if (channel == null || !channel.startsWith(PERSONAL_PREFIX)) return null
        val body = data as? JsonObject ?: return null
        val topic = (body["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return RealtimeTopic.entries.firstOrNull { it.wire == topic }
    }

    fun pollWait(live: Boolean): Boolean = !live

    fun pollIntervalFrom(value: Long?): Long {
        if (value == null || value <= 0) return POLL_BASE_MS
        return min(POLL_SERVER_MAX_MS, maxOf(POLL_BASE_MS, value))
    }

    /** Spread is required: without it every phone returns in one wave after the server restarts. */
    fun pollDelayMs(
        live: Boolean,
        serverMs: Long,
        failures: Int,
        hidden: Boolean,
        waited: Boolean,
        random: Double,
    ): Long {
        val pace = if (live) STRETCHED_MS else serverMs
        val base = when {
            failures > 0 -> min(POLL_FAIL_MAX_MS, POLL_BASE_MS shl min(failures - 1, 4))
            hidden -> maxOf(POLL_HIDDEN_MIN_MS, pace)
            waited && !live -> POLL_AFTER_WAIT_MS
            else -> pace
        }
        return (base * (0.8 + random.coerceIn(0.0, 1.0) * 0.4)).roundToLong()
    }

    fun connectCommand(id: Long, token: String, name: String): String = buildJsonObject {
        put("id", id)
        putJsonObject("connect") {
            put("token", token)
            put("name", name)
        }
    }.toString()

    fun refreshCommand(id: Long, token: String): String = buildJsonObject {
        put("id", id)
        putJsonObject("refresh") { put("token", token) }
    }.toString()

    const val PONG = "{}"

    /** A frame may carry several messages separated by new lines; an empty object is a ping. */
    fun parse(frame: String): List<CentrifugeFrame> = frame.split('\n').mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val message = runCatching { MojangJson.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
            ?: return@mapNotNull null
        if (message.isEmpty()) return@mapNotNull CentrifugeFrame.Ping
        val id = message.number("id") ?: 0L
        val error = message["error"] as? JsonObject
        if (error != null) return@mapNotNull CentrifugeFrame.Failed(id, error.number("code") ?: 0L, error.plain("message"))
        (message["connect"] as? JsonObject)?.let { reply ->
            return@mapNotNull CentrifugeFrame.Connected(id, reply.ttl(), (reply["pong"] as? JsonPrimitive)?.content == "true")
        }
        (message["refresh"] as? JsonObject)?.let { reply ->
            return@mapNotNull CentrifugeFrame.Refreshed(id, reply.ttl())
        }
        val push = message["push"] as? JsonObject ?: return@mapNotNull null
        (push["disconnect"] as? JsonObject)?.let { gone ->
            return@mapNotNull CentrifugeFrame.Disconnect(gone.number("code") ?: 0L, gone.plain("reason"))
        }
        val publication = push["pub"] as? JsonObject ?: return@mapNotNull null
        CentrifugeFrame.Publication(push.plain("channel"), publication["data"])
    }

    /** A token is refreshed ahead of its end so the socket never drops for it. */
    fun refreshDelayMs(ttlSeconds: Long?): Long? {
        val ttl = ttlSeconds?.takeIf { it > 0 } ?: return null
        val ahead = min(60L, ttl / 4)
        return maxOf(5L, ttl - ahead) * 1000
    }

    private fun JsonObject.ttl(): Long? =
        if ((this["expires"] as? JsonPrimitive)?.content == "true") number("ttl") else null

    private fun JsonObject.plain(key: String): String {
        val value = this[key]
        if (value == null || value is JsonNull || value !is JsonPrimitive) return ""
        return value.content
    }

    private fun JsonObject.number(key: String): Long? = plain(key).toDoubleOrNull()?.toLong()
}

/**
 * The live socket the desktop keeps open next to the delta poll. While it is up
 * the poll stretches to two minutes and the server's pokes wake it at once;
 * when it is down nothing is lost, the poll simply runs at its own pace.
 */
class RealtimeClient(
    private val http: OkHttpClient,
    private val clientName: String,
    private val grant: suspend () -> RealtimeGrant,
) {
    private val liveState = MutableStateFlow(false)
    val live: StateFlow<Boolean> = liveState.asStateFlow()

    private val pokeFlow = MutableSharedFlow<RealtimeTopic>(
        extraBufferCapacity = POKE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pokes: SharedFlow<RealtimeTopic> = pokeFlow.asSharedFlow()

    /** Runs until cancelled. A refused sign-in is the only failure that leaves: the caller re-checks it. */
    suspend fun run() {
        var failures = 0
        while (true) {
            val ticket = fetchGrant()
            if (ticket !is RealtimeGrant.On) {
                delay(if (ticket == null) Realtime.ERROR_RETRY_MS else Realtime.OFF_RETRY_MS)
                continue
            }
            val reached = session(ticket)
            failures = if (reached) 0 else failures + 1
            val backoff = min(MAX_RECONNECT_MS, MIN_RECONNECT_MS shl min(failures, 5))
            delay((backoff * (0.8 + Math.random() * 0.4)).roundToLong())
        }
    }

    private suspend fun fetchGrant(): RealtimeGrant? = try {
        grant()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (lost: Unauthorized) {
        throw lost
    } catch (error: Exception) {
        null
    }

    /** True when the server accepted the connection at least once, which resets the backoff. */
    private suspend fun session(start: RealtimeGrant.On): Boolean {
        val inbox = Channel<SocketEvent>(Channel.UNLIMITED)
        val socket = http.newWebSocket(
            Request.Builder().url(start.url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    inbox.trySend(SocketEvent.Text(text))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSE, null)
                    inbox.trySend(SocketEvent.Closed)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    inbox.trySend(SocketEvent.Closed)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    inbox.trySend(SocketEvent.Closed)
                }
            },
        )
        var nextId = 1L
        var reached = false
        var pongs = false
        var refreshAt = Long.MAX_VALUE
        try {
            socket.send(Realtime.connectCommand(nextId++, start.token, clientName))
            while (true) {
                val wait = refreshAt - System.currentTimeMillis()
                val event = if (refreshAt == Long.MAX_VALUE) {
                    inbox.receive()
                } else {
                    withTimeoutOrNull(wait.coerceAtLeast(1)) { inbox.receive() }
                }
                if (event == null) {
                    refreshAt = Long.MAX_VALUE
                    val fresh = fetchGrant() as? RealtimeGrant.On ?: return reached
                    socket.send(Realtime.refreshCommand(nextId++, fresh.token))
                    continue
                }
                val text = (event as? SocketEvent.Text)?.text ?: return reached
                for (frame in Realtime.parse(text)) {
                    when (frame) {
                        CentrifugeFrame.Ping -> if (pongs) socket.send(Realtime.PONG)
                        is CentrifugeFrame.Connected -> {
                            reached = true
                            pongs = frame.pong
                            refreshAt = Realtime.refreshDelayMs(frame.ttlSeconds)?.let { System.currentTimeMillis() + it } ?: Long.MAX_VALUE
                            liveState.value = true
                            pokeFlow.tryEmit(RealtimeTopic.FRIENDS)
                            pokeFlow.tryEmit(RealtimeTopic.PRESENCE)
                        }
                        is CentrifugeFrame.Refreshed -> {
                            refreshAt = Realtime.refreshDelayMs(frame.ttlSeconds)?.let { System.currentTimeMillis() + it } ?: Long.MAX_VALUE
                        }
                        is CentrifugeFrame.Failed -> return reached
                        is CentrifugeFrame.Disconnect -> return reached
                        is CentrifugeFrame.Publication -> {
                            Realtime.pokeTopic(frame.channel, frame.data)?.let { pokeFlow.tryEmit(it) }
                        }
                    }
                }
            }
        } finally {
            liveState.value = false
            socket.cancel()
            inbox.close()
        }
    }

    private sealed class SocketEvent {
        data class Text(val text: String) : SocketEvent()

        object Closed : SocketEvent()
    }

    private companion object {
        const val POKE_BUFFER = 16
        const val NORMAL_CLOSE = 1000
        const val MIN_RECONNECT_MS = 2_000L
        const val MAX_RECONNECT_MS = 60_000L
    }
}
