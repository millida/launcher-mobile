package net.millida.launcher.call

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import net.millida.launcher.core.friends.BridgeTarget
import net.millida.launcher.core.friends.BridgeText
import net.millida.launcher.core.friends.OverlayCall
import net.millida.launcher.core.friends.OverlayCard
import net.millida.launcher.core.friends.OverlaySnapshot
import net.millida.launcher.core.friends.OverlayWire

/**
 * The game process side of the friends chat: it only draws what the launcher
 * process sends and forwards what the player taps.
 */
class FriendsBridgeClient(private val context: Context) {

    private val snapshotFlow = MutableStateFlow<OverlaySnapshot?>(null)
    val snapshot: StateFlow<OverlaySnapshot?> = snapshotFlow.asStateFlow()

    private val callFlow = MutableStateFlow<OverlayCall?>(null)
    val call: StateFlow<OverlayCall?> = callFlow.asStateFlow()

    private val inviteFlow = MutableStateFlow<OverlayCard?>(null)
    val roomInvite: StateFlow<OverlayCard?> = inviteFlow.asStateFlow()

    private val cardFlow = MutableSharedFlow<OverlayCard>(extraBufferCapacity = 8)
    val cards: SharedFlow<OverlayCard> = cardFlow.asSharedFlow()

    private val noticeFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = noticeFlow.asSharedFlow()

    private val connectedFlow = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = connectedFlow.asStateFlow()

    private val inbox = Messenger(Handler(Looper.getMainLooper()) { message -> receive(message) })
    private var service: Messenger? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let(::Messenger)
            connectedFlow.value = service != null
            send(BridgeCodes.REGISTER, null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            connectedFlow.value = false
            callFlow.value = null
        }
    }

    fun bind() {
        if (bound) return
        bound = try {
            context.bindService(Intent(context, FriendsBridgeService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (refused: SecurityException) {
            Log.w(TAG, "чат друзей не подключился: ${refused.message}")
            false
        }
        if (!bound) noticeFlow.tryEmit("Чат друзей не подключился — открой его в лаунчере")
    }

    fun unbind() {
        if (!bound) return
        bound = false
        runCatching { context.unbindService(connection) }
        service = null
        connectedFlow.value = false
    }

    fun open(kind: String, id: String, title: String) =
        send(BridgeCodes.OPEN, OverlayWire.encode(BridgeTarget.serializer(), BridgeTarget(kind, id, title)))

    fun close() = send(BridgeCodes.CLOSE, null)

    fun refresh() = send(BridgeCodes.REFRESH, null)

    fun sendText(text: String) = send(BridgeCodes.SEND, OverlayWire.encode(BridgeText.serializer(), BridgeText(text)))

    fun accept() = send(BridgeCodes.CALL_ACCEPT, null)

    fun decline() = send(BridgeCodes.CALL_DECLINE, null)

    fun hangUp() = send(BridgeCodes.CALL_HANG_UP, null)

    fun toggleMute() = send(BridgeCodes.CALL_MUTE, null)

    fun toggleSpeaker() = send(BridgeCodes.CALL_SPEAKER, null)

    fun joinRoom(roomId: String, title: String) =
        send(BridgeCodes.ROOM_JOIN, OverlayWire.encode(BridgeTarget.serializer(), BridgeTarget(OverlayWire.KIND_ROOM, roomId, title)))

    fun dismissRoomInvite() {
        inviteFlow.value = null
        send(BridgeCodes.ROOM_DISMISS, null)
    }

    private fun send(code: Int, json: String?): Boolean {
        val target = service
        if (target == null) {
            noticeFlow.tryEmit("Чат друзей ещё подключается — повтори через секунду")
            return false
        }
        val message = Message.obtain(null, code).apply {
            replyTo = inbox
            data = Bundle().apply { putString(BridgeCodes.KEY, json) }
        }
        return try {
            target.send(message)
            true
        } catch (failed: RemoteException) {
            Log.w(TAG, "лаунчер не принял действие чата: ${failed.message}")
            noticeFlow.tryEmit("Лаунчер не ответил — повтори")
            false
        }
    }

    private fun receive(message: Message): Boolean {
        val json = message.data?.getString(BridgeCodes.KEY)
        when (message.what) {
            BridgeCodes.SNAPSHOT -> OverlayWire.decode(OverlaySnapshot.serializer(), json)?.let { snapshotFlow.value = it }
            BridgeCodes.CALL -> callFlow.value = OverlayWire.decode(OverlayCall.serializer(), json)
            BridgeCodes.ROOM_INVITE -> inviteFlow.value = OverlayWire.decode(OverlayCard.serializer(), json)
            BridgeCodes.CARD -> OverlayWire.decode(OverlayCard.serializer(), json)?.let { cardFlow.tryEmit(it) }
            BridgeCodes.NOTICE -> OverlayWire.decode(BridgeText.serializer(), json)?.let { noticeFlow.tryEmit(it.text) }
            else -> return false
        }
        return true
    }

    private companion object {
        const val TAG = "millida-overlay"
    }
}
