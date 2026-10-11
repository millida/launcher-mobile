package net.millida.launcher.core.call

enum class CallStatus { IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE }

enum class CallMode { DM, ROOM }

sealed class CallRoute {
    /** Someone calls everyone into a group conversation: a short ring and a card, not a phone call. */
    data class RoomRing(val roomId: String, val title: String) : CallRoute()

    data class Room(val roomId: String) : CallRoute()

    /** A new personal call; a line that is already busy answers "busy" instead. */
    data class Invite(val busy: Boolean) : CallRoute()

    /** Belongs to the personal call in progress. */
    data object Current : CallRoute()

    /** An envelope of a call that already ended must not touch the next one. */
    data object Ignore : CallRoute()
}

/** Where an envelope goes, in the order the desktop state machine asks (src/state/call.ts onEvent). */
object CallRouter {

    fun route(event: CallEvent, status: CallStatus, mode: CallMode, callId: String): CallRoute {
        if (event.kind == CallSignalKind.RING) {
            val roomId = event.roomId ?: event.callId
            val title = event.data.string("title")?.takeIf { it.isNotBlank() } ?: "группе"
            return if (status == CallStatus.IDLE) CallRoute.RoomRing(roomId, title) else CallRoute.Ignore
        }
        event.roomId?.let { return CallRoute.Room(it) }
        if (event.kind == CallSignalKind.INVITE) return CallRoute.Invite(busy = status != CallStatus.IDLE)
        if (status == CallStatus.IDLE || mode != CallMode.DM || callId != event.callId) return CallRoute.Ignore
        return CallRoute.Current
    }
}
