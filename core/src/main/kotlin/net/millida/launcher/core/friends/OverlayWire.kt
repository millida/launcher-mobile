package net.millida.launcher.core.friends

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What crosses between the game process and the launcher process for the
 * friends chat over the game. The launcher process owns the session and the
 * refresh token; the game process never reads them, because two processes
 * refreshing one token would spend it twice and the server treats a reuse as
 * a stolen session.
 */
object OverlayWire {
    const val KIND_DM = "dm"
    const val KIND_ROOM = "room"

    /** A passive card leaves after this long, as on the desktop overlay. */
    const val CARD_MS = 9_000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    /** Null on anything malformed: the other side may run a different build after an update. */
    fun <T> decode(serializer: KSerializer<T>, text: String?): T? {
        if (text.isNullOrEmpty()) return null
        return try {
            json.decodeFromString(serializer, text)
        } catch (malformed: SerializationException) {
            null
        } catch (malformed: IllegalArgumentException) {
            null
        }
    }

    fun kindOf(target: ChatTarget): String = if (target is ChatTarget.Room) KIND_ROOM else KIND_DM

    fun idOf(target: ChatTarget): String = when (target) {
        is ChatTarget.Dm -> target.userId
        is ChatTarget.Room -> target.roomId
    }

    fun targetOf(kind: String, id: String): ChatTarget? {
        val clean = id.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains("..")) return null
        return when (kind) {
            KIND_DM -> ChatTarget.Dm(clean)
            KIND_ROOM -> ChatTarget.Room(clean)
            else -> null
        }
    }

    fun dialog(dialog: Dialog): OverlayDialog = OverlayDialog(
        kind = if (dialog.kind == DialogKind.ROOM) KIND_ROOM else KIND_DM,
        id = dialog.id,
        title = dialog.title,
        subtitle = dialog.subtitle,
        unread = dialog.unread,
        online = dialog.online,
        lastMessageAt = dialog.lastMessageAt,
    )

    fun line(message: ChatMessage): OverlayLine = OverlayLine(
        id = message.id,
        text = if (message.deleted) "" else message.text,
        me = message.me,
        ts = message.ts,
        fromNick = message.fromNick,
        attachment = message.attachment?.takeIf { !message.deleted }?.kind,
        deleted = message.deleted,
    )

    /**
     * Arrivals worth a card: a thread whose unread count grew since the last
     * look. A thread seen for the first time only sets the baseline, or every
     * start of the game would greet the player with old news.
     */
    fun grown(before: Map<String, Int>?, now: List<OverlayDialog>): List<OverlayDialog> {
        if (before == null) return emptyList()
        return now.filter { dialog ->
            val was = before[dialog.key]
            was != null && dialog.unread > was
        }
    }
}

@Serializable
data class OverlayDialog(
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val unread: Int = 0,
    val online: Boolean = false,
    val lastMessageAt: Long? = null,
) {
    val key: String get() = "$kind:$id"
}

@Serializable
data class OverlayLine(
    val id: String,
    val text: String,
    val me: Boolean = false,
    val ts: Long = 0,
    val fromNick: String = "",
    val attachment: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class OverlayThread(
    val kind: String,
    val id: String,
    val title: String,
    val lines: List<OverlayLine> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val sending: Boolean = false,
)

@Serializable
data class OverlayCard(val kind: String, val id: String, val title: String, val text: String)

@Serializable
data class OverlayCall(
    val status: String,
    val mode: String,
    val title: String,
    val muted: Boolean = false,
    val deafened: Boolean = false,
    val speaker: Boolean = true,
    val answeredAt: Long = 0,
    val quality: String = "",
)

@Serializable
data class OverlaySnapshot(
    val signedIn: Boolean,
    val dialogs: List<OverlayDialog> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val thread: OverlayThread? = null,
)

/** A thread the game asks to open, or a room it asks to join. */
@Serializable
data class BridgeTarget(val kind: String, val id: String, val title: String = "")

@Serializable
data class BridgeText(val text: String)
