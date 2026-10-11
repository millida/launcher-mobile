package net.millida.launcher.core.telemetry

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.millida.launcher.core.account.MillidaAuth

/**
 * The collector the desktop sends to. Both calls also take guests; the session,
 * when there is one, only ties the install to the account.
 */
class TelemetryClient(private val auth: MillidaAuth) {
    suspend fun send(device: TelemetryDevice, events: List<TelemetryEvent>) {
        auth.publicCall("/launcher/telemetry", "POST", TelemetryPayload.batch(device, events))
    }

    suspend fun beat(payload: JsonObject) {
        auth.publicCall("/launcher/heartbeat", "POST", payload)
    }
}

/**
 * The desktop's telemetry module (src/lib/telemetry.ts) without the browser:
 * events queue on disk, leave in batches of a hundred, and a batch is dropped
 * from the queue only after the server took it.
 */
class TelemetryCore(
    private val store: TelemetryStore,
    private val client: TelemetryClient,
    private val device: () -> TelemetryDevice,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val failuresSeen = HashMap<String, Long>()

    @Volatile
    private var lastBeat: Pair<BeatState, Long>? = null

    val enabled: Boolean get() = store.enabled

    fun track(type: String, data: Map<String, JsonPrimitive> = emptyMap(), durationMs: Long? = null, ok: Boolean? = null) {
        if (!store.enabled) return
        store.add(TelemetryEvent(type, data, durationMs, ok, clock(), appVersion))
    }

    /**
     * A failed scenario as one `error {where, code, kind}` event. The same
     * place and code more than once a minute is written once, and a
     * cancellation is not a failure.
     */
    fun trackFailure(where: String, error: Throwable, extra: Map<String, JsonPrimitive> = emptyMap()): String? {
        if (error is CancellationException) return null
        val text = error.message ?: error.javaClass.simpleName
        val kind = FailureKind.of(error)
        if (!store.enabled) return kind
        val code = TelemetryPrivacy.errorCode(text)
        // "Свободно всего 1634 МБ" and "… 1590 МБ" are one failure, not one per megabyte.
        val key = "$where\n" + code.replace(DIGITS, "#")
        val now = clock()
        synchronized(failuresSeen) {
            val last = failuresSeen[key]
            if (last != null && now - last < FAILURE_REPEAT_MS) return kind
            failuresSeen[key] = now
        }
        track(TelemetryType.ERROR, EventData().putAll(extra).put("where", where).put("code", code).put("kind", kind).build(), ok = false)
        return kind
    }

    /** Sends what is queued; false when something stayed for the next try. */
    suspend fun flush(): Boolean {
        if (!store.enabled) return true
        val sending = store.trySending() ?: return false
        sending.use {
            repeat(MAX_ROUNDS) {
                val batch = store.peek(TelemetryPayload.MAX_BATCH)
                if (batch.isEmpty()) return true
                try {
                    client.send(device(), batch)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    // The answer may be lost on a slow link after the server stored the batch: it is
                    // kept and sent again, and the server drops the repeat by the events' moments.
                    return false
                }
                store.remove(batch)
            }
        }
        return store.size() == 0
    }

    /** The heartbeat body, or null when telemetry is off or the same state was reported moments ago. */
    fun beat(state: BeatState, build: String? = null, mc: String? = null, server: String? = null, force: Boolean = false): JsonObject? {
        if (!store.enabled) return null
        val now = clock()
        val last = lastBeat
        if (!force && last != null && last.first == state && now - last.second < BEAT_MIN_MS) return null
        lastBeat = state to now
        return TelemetryPayload.beat(device(), state, build, mc, server)
    }

    suspend fun sendBeat(payload: JsonObject) {
        client.beat(payload)
    }

    companion object {
        const val FLUSH_MS = 120_000L
        const val BEAT_MIN_MS = 40_000L
        private const val FAILURE_REPEAT_MS = 60_000L
        private const val MAX_ROUNDS = 3
        private val DIGITS = Regex("""\d+""")
    }
}
