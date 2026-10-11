package net.millida.launcher.core.call

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToLong

enum class CallOutcome(val wire: String) { DONE("done"), MISSED("missed"), DECLINED("declined"), CANCELED("canceled"), FAILED("failed") }

data class CallLogEntry(val outcome: CallOutcome, val seconds: Long)

/**
 * The outcome of a call arrives as an ordinary chat message with a marker in
 * its text. The server writes it (friends/calls/call-log.ts in trade-api), the
 * launchers only read it.
 */
object CallLogs {
    const val PREFIX = "⟪call⟫"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): CallLogEntry? {
        if (!text.startsWith(PREFIX)) return null
        val body = try {
            json.parseToJsonElement(text.removePrefix(PREFIX)) as? JsonObject
        } catch (malformed: SerializationException) {
            null
        } catch (malformed: IllegalArgumentException) {
            null
        } ?: return null
        val outcome = body.string("outcome")?.let { wire -> CallOutcome.entries.firstOrNull { it.wire == wire } } ?: return null
        val seconds = body.finite("seconds") ?: body.string("seconds")?.toDoubleOrNull()?.takeIf { it.isFinite() }
        return CallLogEntry(outcome, seconds?.roundToLong()?.coerceAtLeast(0) ?: 0L)
    }

    fun title(entry: CallLogEntry, mine: Boolean): String = when (entry.outcome) {
        CallOutcome.DONE -> if (mine) "Исходящий звонок" else "Входящий звонок"
        CallOutcome.DECLINED -> if (mine) "Звонок отклонён" else "Ты отклонил звонок"
        CallOutcome.FAILED -> "Звонок сорвался"
        CallOutcome.MISSED, CallOutcome.CANCELED -> if (mine) "Не дозвонился" else "Пропущенный звонок"
    }

    fun subtitle(entry: CallLogEntry): String =
        if (entry.outcome == CallOutcome.DONE) CallRules.formatTime(entry.seconds * 1000) else "Перезвонить"
}
