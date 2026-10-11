package net.millida.launcher.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import net.millida.launcher.Container
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.call.CallClient
import net.millida.launcher.core.call.CallEvent
import net.millida.launcher.core.call.CallIds
import net.millida.launcher.core.call.CallMode
import net.millida.launcher.core.call.CallRoute
import net.millida.launcher.core.call.CallRouter
import net.millida.launcher.core.call.CallRules
import net.millida.launcher.core.call.CallSignalKind
import net.millida.launcher.core.call.CallStatus
import net.millida.launcher.core.call.CallTimings
import net.millida.launcher.core.call.EnvelopeLog
import net.millida.launcher.core.call.PeerFlags
import net.millida.launcher.core.call.PeerQuality
import net.millida.launcher.core.call.QualityLevel
import net.millida.launcher.core.call.Ring
import net.millida.launcher.core.call.VoiceMember
import net.millida.launcher.core.call.politeToward
import org.webrtc.PeerConnection

data class CallParticipant(
    val userId: String,
    val nick: String,
    val muted: Boolean = false,
    val deafened: Boolean = false,
    val connection: PeerConnection.PeerConnectionState? = null,
    val quality: PeerQuality? = null,
)

data class CallUiState(
    val mode: CallMode = CallMode.DM,
    val status: CallStatus = CallStatus.IDLE,
    val callId: String = "",
    val roomId: String = "",
    val roomTitle: String = "",
    val peerId: String = "",
    val peerNick: String = "",
    val answeredAt: Long = 0L,
    val muted: Boolean = false,
    val deafened: Boolean = false,
    val speaker: Boolean = true,
    val volume: Int = 100,
    val parts: List<CallParticipant> = emptyList(),
) {
    val title: String get() = if (mode == CallMode.ROOM) roomTitle.ifEmpty { "Группа" } else peerNick.ifEmpty { "Друг" }

    val quality: QualityLevel
        get() {
            val worst = CallRules.worst(parts.mapNotNull { it.quality })
            return CallRules.quality(worst?.rttMs, worst?.lossPct ?: 0.0)
        }

    val peerMuted: Boolean get() = mode == CallMode.DM && parts.firstOrNull()?.muted == true
}

data class CallNotice(val text: String, val error: Boolean)

/** Somebody calls everyone into a group conversation; joining is one tap away. */
data class RoomInvite(val roomId: String, val title: String, val nick: String)

/**
 * The call state of the launcher process, ported step for step from the
 * desktop (src/state/call.ts) so a phone and a computer behave the same on
 * both ends of one call. A personal call is a conversation with one person,
 * so the personal call and the group voice share every line of the media code.
 *
 * All state changes happen on the engine thread, the way the desktop runs
 * them on its single event loop: an answer that arrives while the microphone
 * is still opening waits for its turn instead of racing it.
 */
class CallCenter private constructor(private val context: Context) {

    private val client = CallClient(Container.get(context).millidaAuth)
    private val auth = Container.get(context).millidaAuth
    private val audio = CallAudio(context)
    private val ring = RingPlayer(context)
    private val cursorStore = context.getSharedPreferences("millida-call", Context.MODE_PRIVATE)

    private val mutable = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = mutable.asStateFlow()

    private val noticeFlow = MutableSharedFlow<CallNotice>(extraBufferCapacity = 16)
    val notices: SharedFlow<CallNotice> = noticeFlow.asSharedFlow()

    private val inviteFlow = MutableStateFlow<RoomInvite?>(null)
    val roomInvite: StateFlow<RoomInvite?> = inviteFlow.asStateFlow()

    @Volatile
    var nicknames: Map<String, String> = emptyMap()

    private val peers = HashMap<String, VoicePeer>()
    private val retries = HashMap<String, Int>()
    private var mic: MicTrack? = null
    private var amCaller = false
    private var myId = ""
    private var ringTimer: Job? = null
    private var statsTimer: Job? = null
    private var beatTimer: Job? = null
    private var inviteTimer: Job? = null

    private val outbox = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val log = EnvelopeLog()
    private var pumpJob: Job? = null
    private var pumpHolders = 0

    init {
        VoiceEngine.onMicFailure = { text -> notice(text, error = true) }
        VoiceEngine.scope.launch(Dispatchers.IO) {
            for (send in outbox) {
                try {
                    send()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "конверт звонка не ушёл: ${error.message}")
                }
            }
        }
    }

    private val st: CallUiState get() = mutable.value

    private fun set(change: (CallUiState) -> CallUiState) = mutable.value.let { mutable.value = change(it) }

    fun available(): Boolean = VoiceEngine.available(context)

    fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Receiving calls runs while somebody holds it: the launcher screen while it
     * is open, the game while the friends bridge is bound. The request waits on
     * the server for the first envelope, so a call rings at once without a socket.
     */
    fun retainPump(): () -> Unit {
        VoiceEngine.post {
            pumpHolders += 1
            if (pumpJob?.isActive != true) pumpJob = VoiceEngine.scope.launch { pump() }
        }
        var released = false
        return {
            if (!released) {
                released = true
                VoiceEngine.post {
                    pumpHolders = (pumpHolders - 1).coerceAtLeast(0)
                    if (pumpHolders == 0 && st.status == CallStatus.IDLE) {
                        pumpJob?.cancel()
                        pumpJob = null
                    }
                }
            }
        }
    }

    fun callFriend(peerId: String, nick: String) = VoiceEngine.post { startCall(peerId, nick) }

    fun accept() = VoiceEngine.post { acceptCall() }

    fun decline() = VoiceEngine.post { finish(CallSignalKind.DECLINE) }

    fun hangUp() = VoiceEngine.post { finish(CallSignalKind.END) }

    fun toggleMute() = VoiceEngine.post {
        val muted = !st.muted
        set { it.copy(muted = muted) }
        mic?.setMuted(muted)
        shareFlags(PeerFlags(muted = muted))
    }

    /** Deafening also mutes: hearing nobody while still talking to them is a conversation nobody wants. */
    fun toggleDeafen() = VoiceEngine.post {
        val deafened = !st.deafened
        val muted = if (deafened) true else st.muted
        set { it.copy(deafened = deafened, muted = muted) }
        peers.values.forEach { it.setDeafened(deafened) }
        mic?.setMuted(muted)
        shareFlags(PeerFlags(muted = muted, deafened = deafened))
    }

    fun toggleSpeaker() = VoiceEngine.post {
        val speaker = !st.speaker
        set { it.copy(speaker = speaker) }
        if (st.status != CallStatus.IDLE && mic != null) audio.setSpeaker(speaker)
    }

    fun setVolume(pct: Int) = VoiceEngine.post {
        val volume = pct.coerceIn(0, 200)
        set { it.copy(volume = volume) }
        peers.values.forEach { it.setVolume(volume) }
    }

    fun joinRoom(roomId: String, title: String) = VoiceEngine.post { joinRoomVoice(roomId, title) }

    fun leaveRoom() = VoiceEngine.post { leaveRoomVoice() }

    fun ringRoom(roomId: String) = VoiceEngine.post {
        try {
            val called = withContext(Dispatchers.IO) { client.ringRoom(roomId) }
            notice(if (called > 0) "Позвали $called чел." else "Все уже в разговоре")
        } catch (failed: Exception) {
            if (failed is CancellationException) throw failed
            notice(failed.message ?: "Не удалось позвать", error = true)
        }
    }

    fun dismissRoomInvite() {
        inviteFlow.value = null
    }

    /** The launcher was swiped away mid-call: the other side must hear about it, not sit with a dead line. */
    fun leaveEverything() = VoiceEngine.post {
        if (st.mode == CallMode.ROOM) leaveRoomVoice() else finish(CallSignalKind.END)
    }

    private fun notice(text: String, error: Boolean = false) {
        noticeFlow.tryEmit(CallNotice(text, error))
    }

    private fun nickOf(userId: String): String = nicknames[userId]?.takeIf { it.isNotBlank() } ?: "Друг"

    private suspend fun pump() {
        var cursor = cursorStore.getLong(KEY_CURSOR, 0L)
        while (true) {
            if (!auth.signedIn()) {
                delay(CallTimings.RETRY_MS)
                continue
            }
            val inbox = try {
                withContext(Dispatchers.IO) { client.poll(cursor, hold = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generateSequence<Throwable>(error) { it.cause }.take(6).any { it is Unauthorized }) {
                    Log.i(TAG, "приём звонков ждёт входа в Millida")
                } else {
                    Log.w(TAG, "приём звонков: ${error.message}")
                }
                delay(CallTimings.RETRY_MS)
                continue
            }
            for (event in inbox.events) {
                if (!log.admit(event)) continue
                if (event.seq > cursor) cursor = event.seq
                try {
                    onEvent(event)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (broken: Exception) {
                    Log.w(TAG, "конверт ${event.kind.wire} не применился: ${broken.message}")
                }
            }
            inbox.cursor?.let { cursor = it }
            cursorStore.edit().putLong(KEY_CURSOR, cursor).apply()
        }
    }

    private fun send(
        callId: String,
        peerId: String,
        kind: CallSignalKind,
        data: JsonObject = JsonObject(emptyMap()),
        seconds: Long? = null,
        roomId: String? = null,
    ) {
        outbox.trySend { client.signal(callId, peerId, kind, data, seconds, roomId) }
    }

    private fun openMicOrFail(): MicTrack? {
        if (!micGranted()) {
            notice("Нужен доступ к микрофону — разреши его и позвони снова", error = true)
            return null
        }
        return try {
            VoiceEngine.openMic(context).also { it.setMuted(st.muted) }
        } catch (failed: IllegalStateException) {
            notice("Микрофон не открылся: ${failed.message}", error = true)
            null
        }
    }

    /** The service keeps the microphone alive behind the game; the audio mode tells the system this is a call. */
    private fun beginMedia() {
        CallService.start(context, st.title)
        audio.start(st.speaker)
    }

    private fun clearTimers() {
        ringTimer?.cancel()
        ringTimer = null
        statsTimer?.cancel()
        statsTimer = null
        beatTimer?.cancel()
        beatTimer = null
    }

    private fun teardown() {
        clearTimers()
        ring.stop()
        peers.values.forEach { it.close() }
        peers.clear()
        mic?.close()
        mic = null
        retries.clear()
        audio.stop()
        CallService.stop(context)
    }

    private fun reset() {
        teardown()
        set { CallUiState(volume = it.volume, speaker = it.speaker) }
        if (pumpHolders == 0) {
            pumpJob?.cancel()
            pumpJob = null
        }
    }

    private fun patchPart(userId: String, change: (CallParticipant) -> CallParticipant) {
        if (st.status == CallStatus.IDLE) return
        if (st.parts.none { it.userId == userId }) return
        set { s -> s.copy(parts = s.parts.map { if (it.userId == userId) change(it) else it }) }
        val now = st
        if (now.mode == CallMode.DM && now.status == CallStatus.CONNECTING &&
            now.parts.any { it.connection == PeerConnection.PeerConnectionState.CONNECTED }
        ) {
            markAnswered()
        }
    }

    private fun applyFlags(userId: String, flags: PeerFlags) = patchPart(userId) { part ->
        part.copy(muted = flags.muted ?: part.muted, deafened = flags.deafened ?: part.deafened)
    }

    private fun markAnswered() {
        ring.stop()
        set { it.copy(status = CallStatus.ACTIVE, answeredAt = it.answeredAt.takeIf { at -> at > 0 } ?: System.currentTimeMillis()) }
        ringTimer?.cancel()
        ringTimer = null
        startStats()
    }

    private fun startStats() {
        if (statsTimer?.isActive == true) return
        statsTimer = VoiceEngine.scope.launch {
            while (isActive) {
                delay(CallTimings.STATS_MS)
                for ((peerId, peer) in peers.toMap()) {
                    val quality = try {
                        peer.quality()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failed: Exception) {
                        Log.i(TAG, "качество связи не измерилось: ${failed.message}")
                        continue
                    }
                    patchPart(peerId) { it.copy(quality = quality) }
                }
            }
        }
    }

    private fun armConnectTimeout() {
        ringTimer?.cancel()
        ringTimer = VoiceEngine.scope.launch {
            delay(CallTimings.CONNECT_TIMEOUT_MS)
            if (st.status != CallStatus.CONNECTING) return@launch
            notice("Не удалось соединиться — попробуй позвонить ещё раз", error = true)
            finish(CallSignalKind.END)
        }
    }

    private fun politeFor(peerId: String): Boolean =
        if (st.mode == CallMode.DM) !amCaller else politeToward(myId, peerId)

    private suspend fun connect(peerId: String, polite: Boolean) {
        if (peers.containsKey(peerId)) return
        val ice = withContext(Dispatchers.IO) { client.ice() }
        if (peers.containsKey(peerId) || st.status == CallStatus.IDLE) return
        val factory = VoiceEngine.factory(context)
        val peer = VoicePeer(
            factory,
            ice,
            polite,
            object : VoicePeerEvents {
                override fun onSignal(kind: CallSignalKind, data: JsonObject) {
                    val now = st
                    if (now.status == CallStatus.IDLE) return
                    send(now.callId, peerId, kind, data, roomId = now.roomId.ifEmpty { null })
                }

                override fun onFlags(flags: PeerFlags) {
                    VoiceEngine.post { applyFlags(peerId, flags) }
                }

                override fun onConnection(state: PeerConnection.PeerConnectionState) {
                    VoiceEngine.post {
                        patchPart(peerId) { it.copy(connection = state) }
                        if (state == PeerConnection.PeerConnectionState.FAILED) recover(peerId)
                    }
                }
            },
        )
        peers[peerId] = peer
        peer.setVolume(st.volume)
        peer.setDeafened(st.deafened)
        mic?.let { peer.setMic(it.track) }
    }

    private fun drop(peerId: String) {
        peers.remove(peerId)?.close()
    }

    /**
     * One quiet attempt to rebuild a dropped connection: a changed address or a
     * Wi-Fi that dozed off is the usual cause, and nobody should have to call
     * back for it. In a group only the link with that one person breaks.
     */
    private suspend fun recover(peerId: String) {
        if (st.status == CallStatus.IDLE || !peers.containsKey(peerId)) return
        val used = retries[peerId] ?: 0
        if (used >= CallTimings.RETRY_LIMIT) {
            if (st.mode == CallMode.DM) {
                notice("Связь оборвалась", error = true)
                finish(CallSignalKind.END)
                return
            }
            notice("Связь с ${nickOf(peerId)} оборвалась", error = true)
            drop(peerId)
            patchPart(peerId) { it.copy(connection = PeerConnection.PeerConnectionState.FAILED) }
            return
        }
        retries[peerId] = used + 1
        if (st.mode == CallMode.DM) notice("Связь пропала — восстанавливаем")
        drop(peerId)
        connect(peerId, politeFor(peerId))
    }

    /** Flags go by the data channel; a peer whose channel is not open yet gets them by signalling. */
    private fun shareFlags(flags: PeerFlags) {
        val now = st
        if (now.callId.isEmpty()) return
        val missed = if (peers.isEmpty()) now.parts.map { it.userId } else peers.filter { !it.value.sendFlags(flags) }.keys
        missed.forEach { peerId -> send(now.callId, peerId, CallSignalKind.STATE, flags.toJson(), roomId = now.roomId.ifEmpty { null }) }
    }

    private suspend fun finish(reason: CallSignalKind) {
        val now = st
        if (now.status == CallStatus.IDLE) return
        if (now.mode == CallMode.ROOM) {
            leaveRoomVoice()
            return
        }
        val seconds = if (now.answeredAt > 0) (System.currentTimeMillis() - now.answeredAt + 500) / 1000 else 0L
        reset()
        if (reason != CallSignalKind.BUSY) ring.start(Ring.ENDED)
        send(now.callId, now.peerId, reason, seconds = seconds)
    }

    private suspend fun startCall(peerId: String, nick: String) {
        if (st.status != CallStatus.IDLE) {
            notice("Сначала заверши текущий звонок", error = true)
            return
        }
        val callId = CallIds.next()
        amCaller = true
        set { CallUiState(mode = CallMode.DM, status = CallStatus.OUTGOING, callId = callId, peerId = peerId, peerNick = nick, volume = it.volume, speaker = it.speaker) }
        val chain = openMicOrFail()
        if (chain == null) {
            reset()
            return
        }
        mic = chain
        beginMedia()
        ensurePump()
        try {
            withContext(Dispatchers.IO) { client.signal(callId, peerId, CallSignalKind.INVITE) }
        } catch (failed: Exception) {
            if (failed is CancellationException) throw failed
            if (st.callId == callId) reset()
            notice(failed.message ?: "Не удалось начать звонок", error = true)
            return
        }
        if (st.callId != callId || st.status != CallStatus.OUTGOING) return
        ring.start(Ring.OUTGOING)
        ringTimer = VoiceEngine.scope.launch {
            delay(CallTimings.RING_TIMEOUT_MS)
            if (st.status != CallStatus.OUTGOING || st.callId != callId) return@launch
            notice("Не ответил — звонок отменён")
            finish(CallSignalKind.CANCEL)
        }
    }

    private suspend fun acceptCall() {
        if (st.status != CallStatus.INCOMING) return
        ring.stop()
        val chain = openMicOrFail()
        if (chain == null) {
            finish(CallSignalKind.DECLINE)
            return
        }
        mic = chain
        val peerId = st.peerId
        set { it.copy(status = CallStatus.CONNECTING, parts = listOf(CallParticipant(peerId, it.peerNick))) }
        beginMedia()
        armConnectTimeout()
        connect(peerId, polite = true)
        send(st.callId, peerId, CallSignalKind.ACCEPT)
    }

    /** A call placed from a screen that does not hold the pump would never hear the answer. */
    private fun ensurePump() {
        if (pumpJob?.isActive != true) pumpJob = VoiceEngine.scope.launch { pump() }
    }

    /**
     * Group voice is not dialling: the room is always open, a person walks in
     * and sees who is already there. Calling the others is a separate button.
     */
    private suspend fun joinRoomVoice(roomId: String, title: String) {
        if (st.status != CallStatus.IDLE) {
            if (st.mode == CallMode.ROOM && st.roomId == roomId) return
            notice("Сначала заверши текущий разговор", error = true)
            return
        }
        inviteFlow.value = null
        set { CallUiState(mode = CallMode.ROOM, status = CallStatus.CONNECTING, callId = roomId, roomId = roomId, roomTitle = title, volume = it.volume, speaker = it.speaker) }
        val chain = openMicOrFail()
        if (chain == null) {
            reset()
            return
        }
        mic = chain
        beginMedia()
        ensurePump()
        val reply = try {
            withContext(Dispatchers.IO) { client.joinVoice(roomId) }
        } catch (failed: Exception) {
            if (failed is CancellationException) throw failed
            if (st.roomId == roomId) reset()
            notice(failed.message ?: "Не удалось войти в разговор", error = true)
            return
        }
        if (st.roomId != roomId) return
        myId = reply.me.ifEmpty { myId }
        set { it.copy(status = CallStatus.ACTIVE, answeredAt = System.currentTimeMillis()) }
        startStats()
        beatTimer = VoiceEngine.scope.launch {
            while (isActive) {
                delay(CallTimings.VOICE_BEAT_MS)
                beatVoice()
            }
        }
        applyRoster(roomId, reply.members)
    }

    private suspend fun beatVoice() {
        val now = st
        if (now.mode != CallMode.ROOM || now.roomId.isEmpty()) return
        try {
            val reply = withContext(Dispatchers.IO) { client.beatVoice(now.roomId, now.muted, now.deafened) }
            applyRoster(now.roomId, reply.members)
        } catch (failed: Exception) {
            if (failed is CancellationException) throw failed
            Log.i(TAG, "удар голоса пропущен — сервер ждёт три: ${failed.message}")
        }
    }

    private suspend fun leaveRoomVoice() {
        val now = st
        if (now.mode != CallMode.ROOM || now.roomId.isEmpty()) return
        reset()
        ring.start(Ring.ENDED)
        try {
            withContext(Dispatchers.IO) { client.leaveVoice(now.roomId) }
        } catch (failed: Exception) {
            if (failed is CancellationException) throw failed
            Log.w(TAG, "выход из голоса не дошёл: ${failed.message}")
        }
    }

    /** The server decides who is in the conversation, so the answer to "who do I talk to" is one for everybody. */
    private suspend fun applyRoster(roomId: String, members: List<VoiceMember>) {
        val now = st
        if (now.mode != CallMode.ROOM || now.roomId != roomId) return
        val others = members.filter { it.userId != myId }
        val known = now.parts.associateBy { it.userId }
        set { s ->
            s.copy(
                parts = others.map { member ->
                    known[member.userId]?.copy(muted = member.muted) ?: CallParticipant(member.userId, nickOf(member.userId), muted = member.muted)
                },
            )
        }
        now.parts.filter { part -> others.none { it.userId == part.userId } }.forEach { drop(it.userId) }
        for (member in others) {
            if (peers.containsKey(member.userId)) continue
            retries.remove(member.userId)
            connect(member.userId, politeToward(myId, member.userId))
        }
    }

    private suspend fun onEvent(event: CallEvent) {
        when (val route = CallRouter.route(event, st.status, st.mode, st.callId)) {
            is CallRoute.RoomRing -> onRing(event, route)
            is CallRoute.Room -> onRoomEvent(event, route.roomId)
            is CallRoute.Invite -> onInvite(event, route.busy)
            CallRoute.Current -> onCurrent(event)
            CallRoute.Ignore -> Unit
        }
    }

    private fun onRing(event: CallEvent, route: CallRoute.RoomRing) {
        val nick = nickOf(event.from)
        inviteFlow.value = RoomInvite(route.roomId, route.title, nick)
        ring.start(Ring.INCOMING)
        inviteTimer?.cancel()
        inviteTimer = VoiceEngine.scope.launch {
            delay(CallTimings.ROOM_RING_MS)
            if (st.status == CallStatus.IDLE) ring.stop()
        }
    }

    private suspend fun onRoomEvent(event: CallEvent, roomId: String) {
        if (event.kind == CallSignalKind.ROSTER) {
            val members = CallClient.voiceMembers(event.data["members"] as? JsonArray)
            if (st.mode == CallMode.ROOM && st.roomId == roomId) applyRoster(roomId, members)
            return
        }
        if (st.mode != CallMode.ROOM || st.roomId != roomId || st.status == CallStatus.IDLE) return
        if (event.kind == CallSignalKind.STATE) {
            applyFlags(event.from, PeerFlags.parse(event.data))
            return
        }
        if (!event.kind.negotiation) return
        if (!peers.containsKey(event.from)) {
            if (st.parts.none { it.userId == event.from }) {
                set { it.copy(parts = it.parts + CallParticipant(event.from, nickOf(event.from))) }
            }
            connect(event.from, politeToward(myId, event.from))
        }
        peers[event.from]?.accept(event.kind, event.data)
    }

    private fun onInvite(event: CallEvent, busy: Boolean) {
        if (busy) {
            send(event.callId, event.from, CallSignalKind.BUSY)
            return
        }
        amCaller = false
        set { CallUiState(mode = CallMode.DM, status = CallStatus.INCOMING, callId = event.callId, peerId = event.from, peerNick = nickOf(event.from), volume = it.volume, speaker = it.speaker) }
        ring.start(Ring.INCOMING)
        ringTimer?.cancel()
        ringTimer = VoiceEngine.scope.launch {
            delay(CallTimings.RING_TIMEOUT_MS)
            if (st.status == CallStatus.INCOMING && st.callId == event.callId) reset()
        }
    }

    private suspend fun onCurrent(event: CallEvent) {
        when {
            event.kind == CallSignalKind.ACCEPT -> onAccept(event)
            event.kind.terminal -> {
                val ringing = st.status == CallStatus.INCOMING || st.status == CallStatus.OUTGOING
                reset()
                if (ringing || event.kind != CallSignalKind.END) notice(CallRules.endedText(event.kind))
                ring.start(Ring.ENDED)
            }
            event.kind == CallSignalKind.STATE -> applyFlags(st.peerId, PeerFlags.parse(event.data))
            event.kind.negotiation -> peers[st.peerId]?.accept(event.kind, event.data)
            else -> Unit
        }
    }

    private suspend fun onAccept(event: CallEvent) {
        if (st.status != CallStatus.OUTGOING || st.callId != event.callId) return
        val peerId = st.peerId
        set { it.copy(status = CallStatus.CONNECTING, parts = listOf(CallParticipant(peerId, it.peerNick))) }
        armConnectTimeout()
        connect(peerId, polite = false)
    }

    companion object {
        private const val TAG = "millida-call"
        private const val KEY_CURSOR = "cursor"

        @Volatile
        private var instance: CallCenter? = null

        fun get(context: Context): CallCenter = instance ?: synchronized(this) {
            instance ?: CallCenter(context.applicationContext).also { instance = it }
        }
    }
}
