package net.millida.launcher.core.call

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * Signalling envelope kinds exactly as trade-api names them
 * (friends/calls/call-mailbox.ts). The last two are written only by the
 * server, which refuses them from a client.
 */
enum class CallSignalKind(val wire: String, val fromClient: Boolean = true) {
    INVITE("invite"),
    ACCEPT("accept"),
    DECLINE("decline"),
    CANCEL("cancel"),
    END("end"),
    BUSY("busy"),
    OFFER("offer"),
    ANSWER("answer"),
    ICE("ice"),
    STATE("state"),
    ROSTER("roster", fromClient = false),
    RING("ring", fromClient = false),
    ;

    val terminal: Boolean get() = this == DECLINE || this == CANCEL || this == END || this == BUSY

    val negotiation: Boolean get() = this == OFFER || this == ANSWER || this == ICE

    companion object {
        fun of(wire: String): CallSignalKind? = entries.firstOrNull { it.wire == wire }
    }
}

data class CallEvent(
    val seq: Long,
    val callId: String,
    val from: String,
    val kind: CallSignalKind,
    val data: JsonObject,
    val ts: Long,
) {
    /** A group conversation: the server stamps the room into every envelope of it. */
    val roomId: String? get() = data.string("roomId")?.takeIf { it.isNotBlank() }
}

data class CallInbox(val cursor: Long?, val events: List<CallEvent>)

object CallEnvelopes {

    /**
     * One envelope from the mailbox or the socket. Anything malformed or of a
     * kind this client does not know is dropped, never half-applied.
     */
    fun parse(element: JsonElement?): CallEvent? {
        val row = element as? JsonObject ?: return null
        val seq = row.finite("seq") ?: return null
        val ts = row.finite("ts") ?: return null
        val callId = row.string("callId") ?: return null
        val from = row.string("from") ?: return null
        val kind = row.string("kind")?.let(CallSignalKind::of) ?: return null
        val data = row["data"] as? JsonObject ?: JsonObject(emptyMap())
        return CallEvent(seq.toLong(), callId, from, kind, data, ts.toLong())
    }

    fun parseInbox(response: JsonObject): CallInbox {
        val events = (response["events"] as? JsonArray).orEmpty().mapNotNull(::parse)
        return CallInbox(cursor = response.finite("cursor")?.toLong(), events = events)
    }

    /**
     * The body of POST /friends/call/signal. The limits are the server's own
     * (CallSignalDto): a request it would refuse is not worth sending.
     */
    fun signalBody(
        callId: String,
        peerId: String,
        kind: CallSignalKind,
        data: JsonObject = JsonObject(emptyMap()),
        seconds: Long? = null,
        roomId: String? = null,
    ): JsonObject {
        require(kind.fromClient) { "конверт ${kind.wire} пишет только сервер" }
        require(callId.length in 8..64) { "неверный идентификатор звонка" }
        require(peerId.isNotBlank() && peerId.length <= 40) { "неверный собеседник" }
        roomId?.let { require(it.length in 8..64) { "неверная группа" } }
        return buildJsonObject {
            put("callId", callId)
            put("peerId", peerId)
            put("kind", kind.wire)
            put("data", data)
            seconds?.let { put("seconds", it.coerceIn(0, MAX_CALL_SECONDS)) }
            roomId?.let { put("roomId", it) }
        }
    }

    const val MAX_CALL_SECONDS = 86_400L
}

/**
 * The same envelope can come twice: once from a live answer and once more from
 * the catch-up read after a reconnect. Applying an offer twice breaks the
 * connection. The key carries the time because the sequence restarts whenever
 * the server storage does.
 */
class EnvelopeLog(private val now: () -> Long = System::currentTimeMillis) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun admit(event: CallEvent): Boolean {
        val key = "${event.seq}:${event.ts}"
        if (seen.containsKey(key)) return false
        val at = now()
        seen[key] = at
        if (seen.size > PRUNE_ABOVE) seen.entries.removeIf { at - it.value > REMEMBER_MS }
        return true
    }

    private companion object {
        /** The mailbox forgets an envelope after this long, so remembering it longer is pointless. */
        const val REMEMBER_MS = 2 * 60_000L
        const val PRUNE_ABOVE = 256
    }
}

object CallIds {
    private val random = SecureRandom()

    /** Twelve random bytes in hex, as the desktop launcher makes them. */
    fun next(): String {
        val raw = ByteArray(12)
        random.nextBytes(raw)
        return raw.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/**
 * Who rolls back an offer when both sides offer at once. Both ends compute it
 * from the same pair of ids, so they agree without another round trip.
 */
fun politeToward(me: String, peer: String): Boolean = me < peer

internal fun JsonObject.string(key: String): String? {
    val value = this[key]
    if (value == null || value is JsonNull || value !is JsonPrimitive || !value.isString) return null
    return value.content
}

internal fun JsonObject.finite(key: String): Double? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (value.isString) return null
    return value.doubleOrNull?.takeIf { it.isFinite() }
}

internal fun JsonObject.flag(key: String): Boolean? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (value.isString) return null
    return value.content.toBooleanStrictOrNull()
}
