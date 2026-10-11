package net.millida.launcher.core.call

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Timings of the desktop call state (src/state/call.ts), kept equal so both sides give up together. */
object CallTimings {
    /** Ringing without an answer: longer than this nobody is at the phone. */
    const val RING_TIMEOUT_MS = 45_000L

    /** After the answer: past this it is silence, not "connecting". */
    const val CONNECT_TIMEOUT_MS = 30_000L

    /** A dropped connection is rebuilt once quietly; after that it is not a connection. */
    const val RETRY_LIMIT = 1

    /** The server forgets a silent voice member after 45 seconds, so the beat is three times faster. */
    const val VOICE_BEAT_MS = 15_000L

    const val STATS_MS = 3_000L

    /** A group ring sounds briefly: it is an invitation, not a call to answer. */
    const val ROOM_RING_MS = 4_000L

    /** Pause between catch-up reads after a failure. */
    const val RETRY_MS = 3_000L
}

data class PeerFlags(
    val muted: Boolean? = null,
    val deafened: Boolean? = null,
    val screen: Boolean? = null,
    val cam: Boolean? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        muted?.let { put("muted", it) }
        deafened?.let { put("deafened", it) }
        screen?.let { put("screen", it) }
        cam?.let { put("cam", it) }
    }

    companion object {
        /** Unknown or mistyped fields are ignored: the state channel never affects the sound. */
        fun parse(data: JsonObject): PeerFlags = PeerFlags(
            muted = data.flag("muted"),
            deafened = data.flag("deafened"),
            screen = data.flag("screen"),
            cam = data.flag("cam"),
        )
    }
}

data class PeerQuality(val rttMs: Long?, val lossPct: Double, val kbps: Double)

enum class QualityLevel(val text: String) {
    WAIT("Соединяемся"),
    BAD("Плохая связь"),
    MID("Связь так себе"),
    OK("Связь хорошая"),
}

object CallRules {

    /** The answer to "can they hear me", not a latency readout. Thresholds as on the desktop. */
    fun quality(rttMs: Long?, lossPct: Double): QualityLevel = when {
        rttMs == null -> QualityLevel.WAIT
        lossPct > 8 || rttMs > 350 -> QualityLevel.BAD
        lossPct > 3 || rttMs > 180 -> QualityLevel.MID
        else -> QualityLevel.OK
    }

    /** The conversation feels like its worst link, so the worst one is shown. */
    fun worst(qualities: List<PeerQuality>): PeerQuality? =
        qualities.maxByOrNull { it.rttMs ?: 0L }

    fun lossPct(lost: Long, received: Long): Double {
        val total = lost + received
        return if (total > 0) lost * 100.0 / total else 0.0
    }

    fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1000
        val minutes = total / 60
        val seconds = total % 60
        val hours = minutes / 60
        val mm = if (hours > 0) (minutes % 60).toString().padStart(2, '0') else minutes.toString()
        return (if (hours > 0) "$hours:" else "") + mm + ":" + seconds.toString().padStart(2, '0')
    }

    fun endedText(kind: CallSignalKind): String = when (kind) {
        CallSignalKind.DECLINE -> "Звонок отклонён"
        CallSignalKind.BUSY -> "Собеседник уже разговаривает"
        CallSignalKind.CANCEL -> "Звонок отменён"
        else -> "Звонок завершён"
    }
}
