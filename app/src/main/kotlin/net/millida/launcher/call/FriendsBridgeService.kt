package net.millida.launcher.call

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.Container
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.call.CallMode
import net.millida.launcher.core.call.CallStatus
import net.millida.launcher.core.friends.BridgeTarget
import net.millida.launcher.core.friends.BridgeText
import net.millida.launcher.core.friends.ChatTarget
import net.millida.launcher.core.friends.OverlayCall
import net.millida.launcher.core.friends.OverlayCard
import net.millida.launcher.core.friends.OverlayDialog
import net.millida.launcher.core.friends.OverlaySnapshot
import net.millida.launcher.core.friends.OverlayThread
import net.millida.launcher.core.friends.OverlayWire

/** Message codes between the game process and the launcher process. */
object BridgeCodes {
    const val REGISTER = 1
    const val OPEN = 2
    const val CLOSE = 3
    const val SEND = 4
    const val CALL_ACCEPT = 5
    const val CALL_DECLINE = 6
    const val CALL_HANG_UP = 7
    const val CALL_MUTE = 8
    const val CALL_SPEAKER = 9
    const val ROOM_JOIN = 10
    const val ROOM_DISMISS = 11
    const val REFRESH = 12

    const val SNAPSHOT = 101
    const val CARD = 102
    const val CALL = 103
    const val NOTICE = 104
    const val ROOM_INVITE = 105

    const val KEY = "json"
}

/**
 * The friends chat over the game, served from the launcher process. Only this
 * process reads the session, so the game never holds a token it could spend
 * twice. The service reads the thread and the list on its own clock instead of
 * the launcher's message cursor: two readers of one cursor eat each other's
 * messages (the desktop overlay is built the same way).
 */
class FriendsBridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clients = ArrayList<Messenger>()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message -> handle(message) })
    private val container by lazy { Container.get(this) }
    private val calls by lazy { CallCenter.get(this) }

    private var snapshot = OverlaySnapshot(signedIn = false, loading = true)
    private var target: ChatTarget? = null
    private var unreadSeen: Map<String, Int>? = null
    private var loops: Job? = null
    private var threadJob: Job? = null
    private var threadSeq = 0L
    private var releasePump: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        stopAll()
        return false
    }

    override fun onDestroy() {
        stopAll()
        scope.cancel()
        super.onDestroy()
    }

    private fun handle(message: Message): Boolean {
        val json = message.data?.getString(BridgeCodes.KEY)
        when (message.what) {
            BridgeCodes.REGISTER -> message.replyTo?.let(::register)
            BridgeCodes.OPEN -> OverlayWire.decode(BridgeTarget.serializer(), json)?.let(::open)
            BridgeCodes.CLOSE -> closeThread()
            BridgeCodes.SEND -> OverlayWire.decode(BridgeText.serializer(), json)?.let { send(it.text) }
            BridgeCodes.REFRESH -> scope.launch { refreshList() }
            BridgeCodes.CALL_ACCEPT -> calls.accept()
            BridgeCodes.CALL_DECLINE -> calls.decline()
            BridgeCodes.CALL_HANG_UP -> calls.leaveEverything()
            BridgeCodes.CALL_MUTE -> calls.toggleMute()
            BridgeCodes.CALL_SPEAKER -> calls.toggleSpeaker()
            BridgeCodes.ROOM_JOIN -> OverlayWire.decode(BridgeTarget.serializer(), json)?.let { room ->
                OverlayWire.targetOf(OverlayWire.KIND_ROOM, room.id)?.let { calls.joinRoom((it as ChatTarget.Room).roomId, room.title) }
            }
            BridgeCodes.ROOM_DISMISS -> calls.dismissRoomInvite()
            else -> return false
        }
        return true
    }

    private fun register(client: Messenger) {
        if (clients.none { it.binder == client.binder }) clients += client
        if (loops?.isActive != true) start()
        push(BridgeCodes.SNAPSHOT, OverlayWire.encode(OverlaySnapshot.serializer(), snapshot))
        pushCall(calls.state.value)
    }

    private fun start() {
        if (releasePump == null) releasePump = calls.retainPump()
        loops = scope.launch {
            launch {
                while (isActive) {
                    refreshList()
                    delay(LIST_POLL_MS)
                }
            }
            launch { calls.state.collect(::pushCall) }
            launch {
                calls.roomInvite.collect { invite ->
                    val payload = invite?.let { OverlayCard(OverlayWire.KIND_ROOM, it.roomId, it.title, it.nick) }
                    push(BridgeCodes.ROOM_INVITE, payload?.let { OverlayWire.encode(OverlayCard.serializer(), it) })
                }
            }
            launch { calls.notices.collect { push(BridgeCodes.NOTICE, OverlayWire.encode(BridgeText.serializer(), BridgeText(it.text))) } }
        }
    }

    private fun stopAll() {
        loops?.cancel()
        loops = null
        threadJob?.cancel()
        threadJob = null
        clients.clear()
        target = null
        unreadSeen = null
        releasePump?.invoke()
        releasePump = null
    }

    private suspend fun refreshList() {
        if (!container.millidaAuth.signedIn()) {
            publish(snapshot.copy(signedIn = false, loading = false, dialogs = emptyList(), error = null))
            return
        }
        val result = attempt { withContext(Dispatchers.IO) { container.friends.dialogs() } }
        val dialogs = result.getOrNull()
        if (dialogs == null) {
            val failure = result.exceptionOrNull()
            publish(snapshot.copy(signedIn = !failure.signInLost(), loading = false, error = failure.readable("Список переписок не загрузился")))
            return
        }
        val rows = dialogs.map(OverlayWire::dialog)
        val open = target?.let { OverlayWire.kindOf(it) + ":" + OverlayWire.idOf(it) }
        OverlayWire.grown(unreadSeen, rows).filter { it.key != open }.forEach { announce(it) }
        unreadSeen = rows.associate { it.key to it.unread }
        publish(snapshot.copy(signedIn = true, loading = false, error = null, dialogs = rows))
    }

    /** A card names the newest line of the thread, the way the desktop overlay card does. */
    private fun announce(dialog: OverlayDialog) {
        val chat = OverlayWire.targetOf(dialog.kind, dialog.id) ?: return
        scope.launch {
            val page = attempt { withContext(Dispatchers.IO) { container.friends.history(chat, limit = 1) } }.getOrNull()
            val last = page?.messages?.lastOrNull()?.takeIf { !it.me }
            val text = when {
                last == null -> "Новое сообщение"
                last.attachment?.kind == "voice" -> "Голосовое сообщение"
                last.attachment != null && last.text.isEmpty() -> "Картинка"
                else -> last.text.replace('\n', ' ').take(CARD_CHARS)
            }
            val author = last?.fromNick?.takeIf { dialog.kind == OverlayWire.KIND_ROOM && it.isNotEmpty() }
            val card = OverlayCard(dialog.kind, dialog.id, dialog.title, if (author != null) "$author: $text" else text)
            push(BridgeCodes.CARD, OverlayWire.encode(OverlayCard.serializer(), card))
        }
    }

    private fun open(request: BridgeTarget) {
        val chat = OverlayWire.targetOf(request.kind, request.id) ?: return
        target = chat
        val title = request.title.ifEmpty { snapshot.dialogs.firstOrNull { it.kind == request.kind && it.id == chat.idText() }?.title.orEmpty() }
        publish(snapshot.copy(thread = OverlayThread(request.kind, chat.idText(), title, loading = true)))
        threadJob?.cancel()
        val seq = ++threadSeq
        threadJob = scope.launch {
            var first = true
            while (isActive && threadSeq == seq) {
                loadThread(chat, seq, markRead = first)
                first = false
                delay(THREAD_POLL_MS)
            }
        }
    }

    private suspend fun loadThread(chat: ChatTarget, seq: Long, markRead: Boolean) {
        val result = attempt { withContext(Dispatchers.IO) { container.friends.history(chat) } }
        if (threadSeq != seq) return
        val current = snapshot.thread ?: return
        val page = result.getOrNull()
        if (page == null) {
            publish(snapshot.copy(thread = current.copy(loading = false, error = result.exceptionOrNull().readable("Переписка не открылась"))))
            return
        }
        val lines = page.messages.takeLast(THREAD_LINES).map(OverlayWire::line)
        publish(snapshot.copy(thread = current.copy(lines = lines, loading = false, error = null)))
        val newest = page.messages.lastOrNull()
        if (markRead || (newest != null && !newest.me && current.lines.lastOrNull()?.id != newest.id)) {
            val read = attempt { withContext(Dispatchers.IO) { container.friends.markRead(chat) } }
            read.exceptionOrNull()?.let { Log.i(TAG, "прочитанное не отметилось: ${it.message}") }
            unreadSeen = unreadSeen?.plus(OverlayWire.kindOf(chat) + ":" + chat.idText() to 0)
        }
    }

    private fun closeThread() {
        threadSeq += 1
        threadJob?.cancel()
        threadJob = null
        target = null
        publish(snapshot.copy(thread = null))
    }

    private fun send(text: String) {
        val chat = target ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        val current = snapshot.thread ?: return
        publish(snapshot.copy(thread = current.copy(sending = true, error = null)))
        val seq = threadSeq
        scope.launch {
            val sent = attempt { withContext(Dispatchers.IO) { container.friends.send(chat, body) } }
            if (threadSeq != seq) return@launch
            val thread = snapshot.thread ?: return@launch
            val message = sent.getOrNull()
            if (message == null) {
                publish(snapshot.copy(thread = thread.copy(sending = false, error = sent.exceptionOrNull().readable("Сообщение не ушло — повтори"))))
                return@launch
            }
            val lines = (thread.lines + OverlayWire.line(message)).distinctBy { it.id.ifEmpty { "local:" + it.ts } }.takeLast(THREAD_LINES)
            publish(snapshot.copy(thread = thread.copy(sending = false, lines = lines)))
        }
    }

    private fun pushCall(call: CallUiState) {
        val payload = if (call.status == CallStatus.IDLE) {
            null
        } else {
            OverlayCall(
                status = call.status.name.lowercase(),
                mode = if (call.mode == CallMode.ROOM) OverlayWire.KIND_ROOM else OverlayWire.KIND_DM,
                title = call.title,
                muted = call.muted,
                deafened = call.deafened,
                speaker = call.speaker,
                answeredAt = call.answeredAt,
                quality = if (call.status == CallStatus.ACTIVE) call.quality.text else "",
            )
        }
        push(BridgeCodes.CALL, payload?.let { OverlayWire.encode(OverlayCall.serializer(), it) })
    }

    private fun publish(next: OverlaySnapshot) {
        snapshot = next
        push(BridgeCodes.SNAPSHOT, OverlayWire.encode(OverlaySnapshot.serializer(), next))
    }

    private fun push(code: Int, json: String?) {
        val dead = ArrayList<Messenger>()
        clients.forEach { client ->
            val message = Message.obtain(null, code).apply { data = Bundle().apply { putString(BridgeCodes.KEY, json) } }
            try {
                client.send(message)
            } catch (gone: DeadObjectException) {
                dead += client
            } catch (failed: RemoteException) {
                Log.w(TAG, "игра не приняла обновление чата: ${failed.message}")
            }
        }
        clients.removeAll(dead.toSet())
    }

    private fun ChatTarget.idText(): String = OverlayWire.idOf(this)

    private companion object {
        const val TAG = "millida-overlay"
        const val LIST_POLL_MS = 20_000L
        const val THREAD_POLL_MS = 5_000L
        const val THREAD_LINES = 60
        const val CARD_CHARS = 120
    }
}

private inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

private fun Throwable?.signInLost(): Boolean {
    val start = this ?: return false
    return generateSequence(start) { current -> current.cause?.takeIf { it !== current } }.take(8).any { it is Unauthorized }
}

private fun Throwable?.readable(fallback: String): String {
    if (signInLost()) return "Вход Millida истёк — открой лаунчер и войди заново"
    return this?.message?.trim()?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() } ?: fallback
}
