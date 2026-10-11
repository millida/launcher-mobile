package net.millida.launcher

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.friends.BeatStatus
import net.millida.launcher.core.telemetry.BeatState
import net.millida.launcher.core.friends.BlockedPlayer
import net.millida.launcher.core.friends.ChatMessage
import net.millida.launcher.core.friends.ChatReaction
import net.millida.launcher.core.friends.ChatReactions
import net.millida.launcher.core.friends.ChatReplyPreview
import net.millida.launcher.core.friends.ChatTarget
import net.millida.launcher.core.friends.Dialog
import net.millida.launcher.core.friends.Eltaller
import net.millida.launcher.core.friends.DialogKind
import net.millida.launcher.core.friends.Friend
import net.millida.launcher.core.friends.FriendProfileCard
import net.millida.launcher.core.friends.FriendStatsClient
import net.millida.launcher.core.friends.FriendsPoll
import net.millida.launcher.core.friends.InviteOverview
import net.millida.launcher.core.friends.NotifyKind
import net.millida.launcher.core.friends.NotifyLevel
import net.millida.launcher.core.friends.PresenceKind
import net.millida.launcher.core.friends.PresenceMemo
import net.millida.launcher.core.friends.PresenceNotify
import net.millida.launcher.core.friends.Realtime
import net.millida.launcher.core.friends.RealtimeClient
import net.millida.launcher.core.friends.RealtimeTopic
import net.millida.launcher.core.friends.ReferralsClient
import net.millida.launcher.core.friends.RequestOutcome
import net.millida.launcher.core.friends.ServerInvites
import net.millida.launcher.core.friends.ThreadMessage
import net.millida.launcher.core.friends.audible
import net.millida.launcher.core.friends.shown
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.core.call.CallLogs
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.media.UiSoundEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

enum class FriendsTab { FRIENDS, MESSAGES, REQUESTS, INVITE }

enum class SendState { Sent, Sending, Failed }

/**
 * One bubble of the open conversation. A message typed here carries a local id
 * for its whole life, so the list key does not change when the server answers.
 */
data class ChatLine(
    val message: ChatMessage,
    val localId: String = "",
    val state: SendState = SendState.Sent,
    val error: String? = null,
) {
    val key: String get() = localId.ifEmpty { message.id }
}

data class ChatThreadState(
    val target: ChatTarget? = null,
    val lines: List<ChatLine> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    val olderLoading: Boolean = false,
    val olderError: String? = null,
    val peerReadAt: Long = 0,
    val uploading: Boolean = false,
    val typers: List<String> = emptyList(),
    val profileOpen: Boolean = false,
    val profile: FriendProfileCard? = null,
)

data class InvitesState(
    val overview: InviteOverview? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val applying: Boolean = false,
    val draft: String = "",
)

/** Who to call once the new server has an address; it lives an hour, then means nothing. */
data class PendingInvite(val target: ChatTarget, val nick: String, val at: Long)

data class SocialState(
    val tab: FriendsTab = FriendsTab.FRIENDS,
    val chat: ChatThreadState = ChatThreadState(),
    val blocked: List<BlockedPlayer> = emptyList(),
    val blockedLoading: Boolean = false,
    val blockedError: String? = null,
    val hours: Map<String, Long> = emptyMap(),
    val previews: Map<String, String> = emptyMap(),
    val pending: Set<String> = emptySet(),
    val me: String = "",
    val callHintShown: Boolean = false,
    val invites: InvitesState = InvitesState(),
    val myServers: List<HostingServerState>? = null,
    val pendingInvite: PendingInvite? = null,
    val called: Set<String> = emptySet(),
    val roomNicks: Map<String, String> = emptyMap(),
)

interface SocialHost {
    val ui: StateFlow<UiState>
    val resumed: StateFlow<Boolean>

    fun updateUi(change: (UiState) -> UiState)

    fun notice(text: String, isError: Boolean = false)

    fun reloadFriends()

    fun recheckSignIn()
}

class SocialModel(
    private val container: Container,
    private val scope: CoroutineScope,
    private val host: SocialHost,
    private val alerts: FriendAlerts,
) {
    private val mutable = MutableStateFlow(SocialState())
    val state: StateFlow<SocialState> = mutable.asStateFlow()

    private val attached = MutableStateFlow<ChatTarget?>(null)
    private val threadSeq = AtomicLong()
    private val blockedSeq = AtomicLong()
    private val localSeq = AtomicLong()
    private val hoursAsked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val roomsKnown: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val profileSeq = AtomicLong()
    private val hoursGate = Semaphore(HOURS_PARALLEL)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val presenceMemo = PresenceMemo()
    private val announced = LinkedHashSet<String>()
    private val referrals = ReferralsClient(container.millidaAuth)
    private val stats = FriendStatsClient(container.millidaAuth)
    private val inviteSeq = AtomicLong()
    private val realtime = RealtimeClient(Downloader.shared(), REALTIME_CLIENT) {
        withContext(Dispatchers.IO) { Realtime.readGrant(container.millidaAuth.authorized("/realtime/token", "GET", null)) }
    }

    @Volatile
    private var cursor = 0L

    @Volatile
    private var delivering = false

    init {
        val signedIn = host.ui.map { it.millidaSignedIn }.distinctUntilChanged()
        scope.launch {
            combine(host.resumed, signedIn) { resumed, millida -> resumed && millida }
                .distinctUntilChanged()
                .collectLatest { active -> if (active) beatLoop() }
        }
        // In the background the poll only stays for notifications: with every
        // kind switched off, or the system forbidding them, it would cost battery
        // for nothing.
        scope.launch {
            combine(host.resumed, signedIn, alerts.levels) { resumed, millida, levels ->
                millida && (resumed || (levels.values.any { it != NotifyLevel.OFF } && alerts.systemAllowed()))
            }
                .distinctUntilChanged()
                .collectLatest { active -> if (active) liveLoop() }
        }
        scope.launch {
            attached.collect { target -> if (target != null) wake.trySend(Unit) }
        }
        scope.launch {
            host.resumed.drop(1).filter { it }.collect { onResumed() }
        }
        scope.launch {
            signedIn.collect { millida -> if (!millida) reset() }
        }
        scope.launch {
            host.ui.map { it.hosted }.distinctUntilChanged().collect { list -> deliverPendingInvite(list) }
        }
        scope.launch {
            var shared: Boolean? = null
            host.ui.map { it.millidaSignedIn to it.privacy?.showPlaytime }
                .distinctUntilChanged()
                .collect { (millida, now) ->
                    if (!millida) {
                        shared = null
                        return@collect
                    }
                    if (shared == true && now == false) hidePlaytime()
                    if (now != null) shared = now
                }
        }
    }

    val notifyLevels: StateFlow<Map<NotifyKind, NotifyLevel>> get() = alerts.levels

    fun setNotifyLevel(kind: NotifyKind, level: NotifyLevel) = alerts.setLevel(kind, level)

    fun notifySystemAllowed(): Boolean = alerts.systemAllowed()

    fun setTab(tab: FriendsTab) = mutable.update { it.copy(tab = tab) }

    /** A friend's code from a link waits in the field; applying it stays the player's choice. */
    fun offerInviteCode(code: String) = mutable.update {
        it.copy(tab = FriendsTab.INVITE, invites = it.invites.copy(draft = code))
    }

    fun markCallHintShown() = mutable.update { it.copy(callHintShown = true) }

    fun openThread(target: ChatTarget): Job {
        val seq = threadSeq.incrementAndGet()
        mutable.update { s ->
            val unsent = if (s.chat.target == target) {
                s.chat.lines.filter { it.localId.isNotEmpty() && it.state != SendState.Sent }
            } else {
                emptyList()
            }
            s.copy(chat = ChatThreadState(target = target, lines = unsent, loading = true))
        }
        if (target is ChatTarget.Room && (mutable.value.me.isEmpty() || target.roomId !in roomsKnown)) loadMe()
        return scope.launch {
            val page = withContext(Dispatchers.IO) { socialAttempt { container.friends.history(target) } }
            if (threadSeq.get() != seq) return@launch
            val loaded = page.getOrNull()
            if (loaded == null) {
                val failure = page.exceptionOrNull()
                if (failure.signInLost()) host.recheckSignIn()
                mutable.update { s ->
                    s.copy(chat = s.chat.copy(loading = false, error = failure.readable("Переписка не открылась — нажми «Повторить»")))
                }
                return@launch
            }
            mutable.update { s ->
                val ids = loaded.messages.mapTo(HashSet()) { it.id }
                val arrived = s.chat.lines.filter { it.message.id.isEmpty() || it.message.id !in ids }
                s.copy(
                    chat = s.chat.copy(
                        lines = loaded.messages.map { ChatLine(it) } + arrived,
                        loading = false,
                        error = null,
                        hasMore = loaded.hasMore,
                        peerReadAt = loaded.peerReadAt ?: 0L,
                    ),
                )
            }
            markRead(target)
        }
    }

    fun ensureThread(target: ChatTarget) {
        if (mutable.value.chat.target != target) openThread(target)
    }

    fun reloadThread() {
        mutable.value.chat.target?.let { openThread(it) }
    }

    fun attach(target: ChatTarget) {
        attached.value = target
    }

    fun detach(target: ChatTarget) {
        if (attached.value == target) attached.value = null
    }

    fun loadOlder() {
        val chat = mutable.value.chat
        val target = chat.target ?: return
        if (!chat.hasMore || chat.olderLoading || chat.loading) return
        val oldest = chat.lines.firstOrNull { it.message.ts > 0 }?.message?.ts ?: return
        val seq = threadSeq.get()
        mutable.update { it.copy(chat = it.chat.copy(olderLoading = true, olderError = null)) }
        scope.launch {
            val page = withContext(Dispatchers.IO) { socialAttempt { container.friends.history(target, before = oldest) } }
            if (threadSeq.get() != seq) return@launch
            val loaded = page.getOrNull()
            mutable.update { s ->
                if (loaded == null) {
                    s.copy(chat = s.chat.copy(olderLoading = false, olderError = page.exceptionOrNull().readable("Старые сообщения не загрузились")))
                } else {
                    val ids = s.chat.lines.mapTo(HashSet()) { it.message.id }
                    val older = loaded.messages.filter { it.id.isEmpty() || it.id !in ids }.map { ChatLine(it) }
                    s.copy(chat = s.chat.copy(lines = older + s.chat.lines, hasMore = loaded.hasMore, olderLoading = false))
                }
            }
            if (page.exceptionOrNull().signInLost()) host.recheckSignIn()
        }
    }

    fun send(text: String, replyTo: ChatMessage?) {
        val target = mutable.value.chat.target ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        val draft = ChatMessage(
            id = "",
            text = body,
            me = true,
            ts = System.currentTimeMillis(),
            replyTo = replyTo?.preview(),
        )
        val localId = nextLocalId()
        appendLocal(target, ChatLine(draft, localId, SendState.Sending))
        deliver(target, localId, draft)
    }

    /** [read] runs off the main thread: it opens and shrinks the picked picture. */
    fun sendImage(read: suspend () -> ByteArray, replyTo: ChatMessage?): Job? {
        val target = mutable.value.chat.target ?: return null
        if (mutable.value.chat.uploading) return null
        setUploading(target, true)
        return scope.launch {
            try {
                val uploaded = withContext(Dispatchers.IO) {
                    socialAttempt { container.friends.uploadImage(read()) }
                }
                val attachment = uploaded.getOrNull()
                if (attachment == null) {
                    fail(uploaded.exceptionOrNull(), "Картинка не отправилась — попробуй ещё раз")
                    return@launch
                }
                val draft = ChatMessage(
                    id = "",
                    text = "",
                    me = true,
                    ts = System.currentTimeMillis(),
                    attachment = attachment,
                    replyTo = replyTo?.preview(),
                )
                val localId = nextLocalId()
                appendLocal(target, ChatLine(draft, localId, SendState.Sending))
                deliver(target, localId, draft)
            } finally {
                setUploading(target, false)
            }
        }
    }

    /** A recorded voice note: [read] hands over the file off the main thread, then it goes out like a picture. */
    fun sendVoice(read: suspend () -> ByteArray, durationMs: Long, peaks: List<Int>, replyTo: ChatMessage?): Job? {
        val target = mutable.value.chat.target ?: return null
        if (mutable.value.chat.uploading) return null
        setUploading(target, true)
        return scope.launch {
            try {
                val uploaded = withContext(Dispatchers.IO) {
                    socialAttempt { container.friends.uploadVoice(read(), durationMs, peaks) }
                }
                val attachment = uploaded.getOrNull()
                if (attachment == null) {
                    fail(uploaded.exceptionOrNull(), "Голосовое не отправилось — попробуй ещё раз")
                    return@launch
                }
                val draft = ChatMessage(
                    id = "",
                    text = "",
                    me = true,
                    ts = System.currentTimeMillis(),
                    attachment = attachment,
                    replyTo = replyTo?.preview(),
                )
                val localId = nextLocalId()
                appendLocal(target, ChatLine(draft, localId, SendState.Sending))
                deliver(target, localId, draft)
            } finally {
                setUploading(target, false)
            }
        }
    }

    fun retry(localId: String) {
        val chat = mutable.value.chat
        val target = chat.target ?: return
        val line = chat.lines.firstOrNull { it.localId == localId && it.state == SendState.Failed } ?: return
        replaceLine(target, localId) { it.copy(state = SendState.Sending, error = null) }
        deliver(target, localId, line.message)
    }

    fun drop(localId: String) {
        mutable.update { s ->
            s.copy(chat = s.chat.copy(lines = s.chat.lines.filterNot { it.localId == localId && it.state == SendState.Failed }))
        }
    }

    /** Drawn at once; the server's whole message follows and settles the count, a refusal puts the old row back. */
    fun react(messageId: String, emoji: String): Job? {
        val target = mutable.value.chat.target ?: return null
        val before = mutable.value.chat.lines.firstOrNull { it.message.id == messageId }?.message?.reactions ?: return null
        setReactions(target, messageId, ChatReactions.toggled(before, emoji))
        return scope.launch {
            val result = withContext(Dispatchers.IO) { socialAttempt { container.friends.react(messageId, emoji) } }
            val view = result.getOrNull()
            if (view == null) {
                setReactions(target, messageId, before)
                fail(result.exceptionOrNull(), "Реакция не поставилась")
                return@launch
            }
            setReactions(target, messageId, view.reactions)
        }
    }

    /**
     * The desktop "Профиль" is the conversation with the friend's card on top
     * (openFriendProfile in src/state/friends.ts): the card shows at once from
     * what the list knows, the stats arrive after.
     */
    fun showProfile(userId: String): Job {
        val target = ChatTarget.Dm(userId)
        val seq = profileSeq.incrementAndGet()
        mutable.update { s ->
            if (s.chat.target != target) s else s.copy(chat = s.chat.copy(profileOpen = true, profile = null))
        }
        return scope.launch {
            val result = withContext(Dispatchers.IO) { socialAttempt { container.friends.profile(userId) } }
            if (profileSeq.get() != seq) return@launch
            val card = result.getOrNull()
            if (card == null) {
                Log.w(TAG, "профиль друга не загрузился: ${result.exceptionOrNull()?.message}")
                return@launch
            }
            mutable.update { s ->
                if (s.chat.target != target || !s.chat.profileOpen) s else s.copy(chat = s.chat.copy(profile = card))
            }
        }
    }

    fun edit(messageId: String, text: String, onDone: (Boolean) -> Unit = {}): Job = scope.launch {
        val target = mutable.value.chat.target ?: return@launch
        val result = withContext(Dispatchers.IO) { socialAttempt { container.friends.editMessage(messageId, text) } }
        val view = result.getOrNull()
        if (view == null) {
            fail(result.exceptionOrNull(), "Сообщение не изменилось — повтори")
            onDone(false)
            return@launch
        }
        applyView(target, view.copy(id = view.id.ifEmpty { messageId }))
        onDone(true)
    }

    fun delete(messageId: String): Job = scope.launch {
        val target = mutable.value.chat.target ?: return@launch
        val result = withContext(Dispatchers.IO) { socialAttempt { container.friends.deleteMessage(messageId) } }
        val view = result.getOrNull()
        if (view == null) {
            fail(result.exceptionOrNull(), "Сообщение не удалилось — повтори")
            return@launch
        }
        applyView(target, view.copy(id = view.id.ifEmpty { messageId }, deleted = true))
        container.uiSoundPlayer.play(UiSoundEvent.Delete)
    }

    fun addFriend(nickname: String): Job {
        val nick = nickname.trim()
        return act(ADD_FRIEND_KEY, "Заявка не ушла — проверь ник и повтори") {
            when (container.friends.addByNickname(nick)) {
                RequestOutcome.SENT -> "Заявка ушла: $nick"
                RequestOutcome.ACCEPTED -> "$nick теперь в друзьях"
                RequestOutcome.ALREADY_FRIENDS -> "$nick уже в друзьях"
            }
        }
    }

    fun accept(requestId: String): Job {
        val nick = requestNick(requestId)
        return act(requestKey(requestId), "Заявка не принялась — повтори") {
            container.friends.acceptRequest(requestId)
            if (nick.isEmpty()) "Теперь вы друзья" else "$nick теперь в друзьях"
        }
    }

    fun decline(requestId: String): Job = act(requestKey(requestId), "Заявка не отклонилась — повтори") {
        container.friends.declineRequest(requestId)
        null
    }

    fun cancel(requestId: String): Job = act(requestKey(requestId), "Заявка не отменилась — повтори") {
        container.friends.cancelRequest(requestId)
        null
    }

    fun remove(userId: String): Job {
        val nick = friendNick(userId)
        return act(friendKey(userId), "Не получилось убрать из друзей — повтори") {
            container.friends.removeFriend(userId)
            "$nick убран из друзей"
        }
    }

    /**
     * The block service only records the block, the friendship stays, so the
     * friend is removed right after it: the confirm dialog promised exactly that.
     */
    fun block(userId: String): Job {
        val nick = friendNick(userId)
        return act(friendKey(userId), "Не получилось заблокировать — повтори") {
            container.friends.blockPlayer(userId)
            val dropped = socialAttempt { container.friends.removeFriend(userId) }
            loadBlocked()
            if (dropped.isSuccess) "$nick заблокирован" else "$nick заблокирован, но остался в друзьях — убери его вручную"
        }
    }

    fun unblock(entry: BlockedPlayer): Job {
        val nick = entry.nickname.ifEmpty { "Игрок" }
        return act(blockKey(entry.blockedId), "Не получилось разблокировать — повтори", reloadFriends = false) {
            container.friends.unblock(entry.blockedId)
            mutable.update { s -> s.copy(blocked = s.blocked.filterNot { it.blockedId == entry.blockedId }) }
            "$nick разблокирован"
        }
    }

    fun loadBlocked(): Job {
        val seq = blockedSeq.incrementAndGet()
        mutable.update { it.copy(blockedLoading = true) }
        return scope.launch {
            val result = withContext(Dispatchers.IO) { socialAttempt { container.friends.blocked() } }
            if (blockedSeq.get() != seq) return@launch
            val list = result.getOrNull()
            mutable.update { s ->
                if (list != null) {
                    s.copy(blocked = list, blockedLoading = false, blockedError = null)
                } else {
                    s.copy(blockedLoading = false, blockedError = result.exceptionOrNull().readable("Список блокировок не загрузился"))
                }
            }
            if (result.exceptionOrNull().signInLost()) host.recheckSignIn()
        }
    }

    fun loadHours(userIds: List<String>) {
        userIds.filter { it.isNotEmpty() && hoursAsked.add(it) }.forEach { id ->
            scope.launch {
                val result = hoursGate.withPermit {
                    withContext(Dispatchers.IO) { socialAttempt { container.friends.profile(id) } }
                }
                val seconds = result.getOrNull()?.totalSeconds
                if (seconds != null) mutable.update { it.copy(hours = it.hours + (id to seconds)) }
                result.exceptionOrNull()?.let { Log.w(TAG, "часы друга не загрузились: ${it.message}") }
            }
        }
    }

    private suspend fun beatLoop() {
        while (true) {
            val result = withContext(Dispatchers.IO) {
                socialAttempt {
                    container.friends.heartbeat(BeatStatus.LOBBY, telemetry = container.telemetry.beatPayload(BeatState.IDLE))
                }
            }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                Log.w(TAG, "присутствие не отправлено: ${failure.message}")
                if (failure.signInLost()) {
                    host.recheckSignIn()
                    return
                }
            }
            delay(BEAT_PERIOD_MS)
        }
    }

    /**
     * One delta poll for the whole social graph, the desktop's loop
     * (src/App.tsx): the open chat, the badges and the notifications all read
     * the same answers, so a message is never fetched or announced twice.
     */
    private suspend fun liveLoop() = coroutineScope {
        launch {
            try {
                realtime.run()
            } catch (lost: Unauthorized) {
                host.recheckSignIn()
            }
        }
        launch {
            realtime.pokes.collect { topic ->
                if (topic == RealtimeTopic.FRIENDS || topic == RealtimeTopic.PRESENCE) wake.trySend(Unit)
            }
        }
        launch {
            realtime.live.drop(1).filter { !it }.collect { wake.trySend(Unit) }
        }
        pollLoop()
        coroutineContext.cancelChildren()
    }

    private suspend fun pollLoop() {
        var failures = 0
        var pace = Realtime.POLL_BASE_MS
        if (cursor <= 0L) cursor = System.currentTimeMillis() - POLL_SKEW_MS
        var counted = false
        while (true) {
            val since = cursor
            val wait = Realtime.pollWait(realtime.live.value)
            val result = withContext(Dispatchers.IO) {
                socialAttempt { container.friends.poll(since, wait = wait) }
            }
            val poll = result.getOrNull()
            var waited = false
            if (poll != null) {
                failures = 0
                cursor = poll.now.takeIf { it > 0 } ?: System.currentTimeMillis()
                pace = Realtime.pollIntervalFrom(poll.nextPollMs)
                waited = poll.waited
                absorb(poll, counted)
                counted = true
            } else {
                val failure = result.exceptionOrNull()
                if (failure.signInLost()) {
                    host.recheckSignIn()
                    return
                }
                failures += 1
                Log.w(TAG, "опрос переписки не удался: ${failure?.message}")
            }
            val pause = Realtime.pollDelayMs(
                live = realtime.live.value,
                serverMs = pace,
                failures = failures,
                hidden = !host.resumed.value,
                waited = waited,
                random = Math.random(),
            )
            withTimeoutOrNull(pause) { wake.receive() }
        }
    }

    /**
     * The first answer after a (re)start overlaps what the badge numbers already
     * count, so it only fills previews and announces nothing.
     */
    private fun absorb(poll: FriendsPoll, countElsewhere: Boolean) {
        val me = mutable.value.me
        val resumed = host.resumed.value
        val showing = mutable.value.chat.target?.takeIf { it == attached.value }
        val mine = { item: ThreadMessage -> item.message.me || (me.isNotEmpty() && item.message.from == me) }
        val here = poll.incoming.filter { it.target == showing && !mine(it) }
        val elsewhere = poll.incoming.filter { it.target != showing && !mine(it) }
        val beforeFriends = host.ui.value.friends
        val beforeIncoming = host.ui.value.friendRequests?.incoming.orEmpty().mapTo(HashSet()) { it.id }
        var fresh = 0
        if (showing != null && here.isNotEmpty()) {
            val known = mutable.value.chat.lines.mapTo(HashSet()) { it.message.id }
            val added = here.map { it.message }.filter { it.id.isEmpty() || known.add(it.id) }
            fresh = added.size
            if (added.isNotEmpty()) {
                mutable.update { s ->
                    if (s.chat.target != showing) s else s.copy(chat = s.chat.copy(lines = s.chat.lines + added.map { ChatLine(it) }))
                }
            }
        }
        if (showing != null) {
            poll.updates.filter { it.target == showing }.forEach { applyView(showing, it.message) }
            if (showing is ChatTarget.Dm) {
                poll.reads.filter { it.userId == showing.userId }.maxOfOrNull { it.readAt }?.let { readAt ->
                    mutable.update { s ->
                        if (s.chat.target != showing) s else s.copy(chat = s.chat.copy(peerReadAt = max(s.chat.peerReadAt, readAt)))
                    }
                }
            }
            val typers = typersOf(poll, showing)
            mutable.update { s ->
                if (s.chat.target != showing || s.chat.typers == typers) s else s.copy(chat = s.chat.copy(typers = typers))
            }
        }
        if (elsewhere.isNotEmpty()) {
            mutable.update { s -> s.copy(previews = s.previews + elsewhere.associate { threadKey(it.target) to previewText(it) }) }
        }
        host.updateUi { ui ->
            var next = ui
            if (poll.hasPresence) next = withPresence(next, poll.friends)
            if (poll.hasRequests) next = next.copy(friendRequests = poll.requests)
            if (countElsewhere && elsewhere.isNotEmpty()) next = bumpThreads(next, elsewhere, directCounted = poll.hasPresence)
            next
        }
        if (fresh > 0 && showing != null && !mutable.value.chat.loading && resumed) {
            val kind = if (showing is ChatTarget.Room) NotifyKind.ROOM else NotifyKind.MSG
            if (countElsewhere && alerts.levels.value.audible(kind)) container.uiSoundPlayer.play(UiSoundEvent.Notify)
            markRead(showing)
        }
        if (!countElsewhere) return
        announceMessages(if (resumed) elsewhere else here + elsewhere)
        if (poll.hasRequests) {
            poll.requests.incoming.filter { it.id !in beforeIncoming }.forEach { request ->
                announce(
                    FriendAlert(
                        kind = NotifyKind.REQUEST,
                        key = "request:" + request.userId.ifEmpty { request.id },
                        title = request.nickname.ifEmpty { "Игрок" },
                        text = "Хочет добавить тебя в друзья",
                        open = AlertOpen.Friends(FriendsTab.REQUESTS),
                    ),
                )
            }
        }
        if (poll.hasPresence) {
            val events = PresenceNotify.events(beforeFriends, poll.friends, presenceMemo, System.currentTimeMillis())
            announcePresence(events.started, PresenceKind.PLAY)
            announcePresence(events.cameOnline, PresenceKind.ONLINE)
        }
    }

    private fun announceMessages(arrivals: List<ThreadMessage>) {
        arrivals.forEach { item ->
            val id = item.message.id
            if (id.isNotEmpty() && !firstSighting(id)) return@forEach
            val alert = when (val target = item.target) {
                is ChatTarget.Dm -> FriendAlert(
                    kind = NotifyKind.MSG,
                    key = threadKey(target),
                    title = item.message.fromNick.ifEmpty { friendNickOrNull(target.userId) ?: "Друг" },
                    text = previewText(item),
                    open = AlertOpen.Chat(target),
                )
                is ChatTarget.Room -> FriendAlert(
                    kind = NotifyKind.ROOM,
                    key = threadKey(target),
                    title = roomTitle(target.roomId),
                    text = previewText(item),
                    open = AlertOpen.Chat(target),
                )
            }
            announce(alert)
        }
    }

    /** One card per batch: a tick that brings five friends online used to stack five cards and five sounds. */
    private fun announcePresence(list: List<Friend>, kind: PresenceKind) {
        if (list.isEmpty()) return
        val notifyKind = if (kind == PresenceKind.PLAY) NotifyKind.PLAY else NotifyKind.ONLINE
        announce(
            FriendAlert(
                kind = notifyKind,
                key = notifyKind.wire + ":" + list.first().userId,
                title = PresenceNotify.title(list),
                text = PresenceNotify.text(list, kind),
                open = AlertOpen.Friends(FriendsTab.FRIENDS),
            ),
        )
    }

    /** In front the launcher shows its own card; the system shade is for when it is behind the game. */
    private fun announce(alert: FriendAlert) {
        val levels = alerts.levels.value
        if (!levels.shown(alert.kind)) return
        if (host.resumed.value) {
            host.notice(alert.title + ": " + alert.text)
            if (levels.audible(alert.kind)) container.uiSoundPlayer.play(UiSoundEvent.Notify)
        } else {
            alerts.post(alert)
        }
    }

    private fun firstSighting(messageId: String): Boolean = synchronized(announced) {
        if (!announced.add(messageId)) return false
        if (announced.size > ANNOUNCED_LIMIT) announced.remove(announced.first())
        true
    }

    private fun onResumed() {
        wake.trySend(Unit)
        val open = attached.value
        if (open != null && mutable.value.chat.target == open && !mutable.value.chat.loading) markRead(open)
        if (mutable.value.pendingInvite != null) loadMyServers()
    }

    // --- Позвать играть, «Позови друга», наигранное ---

    fun loadMyServers(): Job = scope.launch {
        val result = withContext(Dispatchers.IO) { socialAttempt { container.hosting.servers() } }
        val list = result.getOrNull()
        if (list == null) {
            Log.w(TAG, "свои серверы не загрузились: ${result.exceptionOrNull()?.message}")
            return@launch
        }
        mutable.update { it.copy(myServers = list) }
        deliverPendingInvite(list)
    }

    /**
     * With a server of their own the invite leaves at once. Without one the
     * player goes to create it ([onNoServer]) and the invite waits for the new
     * server's address, the desktop flow (src/state/playInvite.ts).
     */
    fun invitePlay(target: ChatTarget, nick: String, onNoServer: () -> Unit): Job {
        val key = inviteKey(target)
        return scope.launch {
            if (key in mutable.value.pending) return@launch
            mutable.update { it.copy(pending = it.pending + key) }
            try {
                val fetched = withContext(Dispatchers.IO) { socialAttempt { container.hosting.servers() } }
                val fresh = fetched.getOrNull()
                if (fresh != null) mutable.update { it.copy(myServers = fresh) }
                val known = fresh ?: mutable.value.myServers ?: host.ui.value.hosted
                val server = ServerInvites.pickServer(known)
                if (server != null) {
                    sendServerInvite(target, nick, server)
                    return@launch
                }
                if (fresh == null) {
                    fail(fetched.exceptionOrNull(), "Не удалось проверить твои серверы — повтори")
                    return@launch
                }
                mutable.update { it.copy(pendingInvite = PendingInvite(target, nick, System.currentTimeMillis())) }
                host.notice("Создай сервер — $nick позовём, когда он запустится")
                onNoServer()
            } finally {
                mutable.update { it.copy(pending = it.pending - key) }
            }
        }
    }

    fun cancelPendingInvite() = mutable.update { it.copy(pendingInvite = null) }

    /** The lobby "Позвать": a plain line into the friend's chat; the button stays pressed only on success. */
    fun lobbyCall(friend: Friend, modeTitle: String?): Job {
        val nick = friend.nickname.ifEmpty { "друга" }
        return scope.launch {
            if (friend.userId in mutable.value.called) return@launch
            mutable.update { it.copy(called = it.called + friend.userId) }
            val target = ChatTarget.Dm(friend.userId)
            val result = withContext(Dispatchers.IO) {
                socialAttempt { container.friends.send(target, ServerInvites.lobbyCall(modeTitle)) }
            }
            if (result.isSuccess) {
                host.notice("Позвали $nick")
                return@launch
            }
            mutable.update { it.copy(called = it.called - friend.userId) }
            fail(result.exceptionOrNull(), "Не удалось позвать")
        }
    }

    fun loadInvites(): Job {
        val seq = inviteSeq.incrementAndGet()
        mutable.update { it.copy(invites = it.invites.copy(loading = true, error = null)) }
        return scope.launch {
            val result = withContext(Dispatchers.IO) { socialAttempt { referrals.overview() } }
            if (inviteSeq.get() != seq) return@launch
            val overview = result.getOrNull()
            mutable.update { s ->
                if (overview != null) {
                    s.copy(invites = s.invites.copy(overview = overview, loading = false, error = null))
                } else {
                    s.copy(invites = s.invites.copy(loading = false, error = result.exceptionOrNull().readable("Не удалось загрузить приглашения")))
                }
            }
            if (result.exceptionOrNull().signInLost()) host.recheckSignIn()
        }
    }

    fun applyInviteCode(code: String): Job = scope.launch {
        if (mutable.value.invites.applying) return@launch
        mutable.update { it.copy(invites = it.invites.copy(applying = true)) }
        try {
            val result = withContext(Dispatchers.IO) { socialAttempt { referrals.apply(code) } }
            val overview = result.getOrNull()
            if (overview == null) {
                fail(result.exceptionOrNull(), "Не удалось ввести код")
                return@launch
            }
            inviteSeq.incrementAndGet()
            mutable.update { it.copy(invites = it.invites.copy(overview = overview, loading = false, error = null)) }
            container.uiSoundPlayer.play(UiSoundEvent.Achievement)
            host.notice("Код друга принят")
        } finally {
            mutable.update { it.copy(invites = it.invites.copy(applying = false)) }
        }
    }

    private suspend fun sendServerInvite(target: ChatTarget, nick: String, server: HostingServerState): Boolean {
        val result = withContext(Dispatchers.IO) {
            socialAttempt { container.friends.send(target, ServerInvites.forServer(server)) }
        }
        if (result.isFailure) {
            fail(result.exceptionOrNull(), "Приглашение не ушло — попробуй ещё раз")
            return false
        }
        host.notice("Позвали $nick на «${ServerInvites.serverTitle(server)}»")
        return true
    }

    private fun deliverPendingInvite(list: List<HostingServerState>) {
        val waiting = mutable.value.pendingInvite ?: return
        if (delivering) return
        if (System.currentTimeMillis() - waiting.at > PENDING_INVITE_TTL_MS) {
            cancelPendingInvite()
            return
        }
        val server = ServerInvites.pickServer(list) ?: return
        delivering = true
        scope.launch {
            try {
                if (sendServerInvite(waiting.target, waiting.nick, server)) {
                    mutable.update { s -> if (s.pendingInvite == waiting) s.copy(pendingInvite = null) else s }
                }
            } finally {
                delivering = false
            }
        }
    }

    /** Switching "Наигранное время" off wipes what friends already see, on whichever device it was switched. */
    private fun hidePlaytime() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { socialAttempt { stats.hide() } }
            if (result.isFailure) fail(result.exceptionOrNull(), "Наигранное время у друзей не скрылось — переключи ещё раз")
        }
    }

    private fun markRead(target: ChatTarget) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { socialAttempt { container.friends.markRead(target) } }
            if (result.isSuccess) {
                host.updateUi { clearThread(it, target) }
                alerts.dismiss(threadKey(target))
            } else {
                Log.w(TAG, "прочитанное не отметилось: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun deliver(target: ChatTarget, localId: String, draft: ChatMessage) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                socialAttempt { container.friends.send(target, draft.text, draft.attachment, draft.replyTo?.id) }
            }
            val sent = result.getOrNull()
            if (sent != null) {
                val delivered = draft.copy(
                    id = sent.id,
                    ts = if (sent.ts > 0) sent.ts else draft.ts,
                    attachment = sent.attachment ?: draft.attachment,
                )
                replaceLine(target, localId) { it.copy(message = delivered, state = SendState.Sent, error = null) }
                host.updateUi { ui -> touchThread(ui, target, delivered.ts) }
            } else {
                val failure = result.exceptionOrNull()
                if (failure.signInLost()) host.recheckSignIn()
                replaceLine(target, localId) {
                    it.copy(state = SendState.Failed, error = failure.readable("Сообщение не ушло"))
                }
            }
        }
    }

    private fun appendLocal(target: ChatTarget, line: ChatLine) {
        mutable.update { s ->
            if (s.chat.target != target) s else s.copy(chat = s.chat.copy(lines = s.chat.lines + line))
        }
    }

    private fun replaceLine(target: ChatTarget, localId: String, change: (ChatLine) -> ChatLine) {
        mutable.update { s ->
            if (s.chat.target != target) {
                s
            } else {
                s.copy(chat = s.chat.copy(lines = s.chat.lines.map { if (it.localId == localId) change(it) else it }))
            }
        }
    }

    /** Edits, deletions and reactions arrive as the whole message; authorship is never taken from them. */
    private fun applyView(target: ChatTarget, view: ChatMessage) {
        if (view.id.isEmpty()) return
        mutable.update { s ->
            if (s.chat.target != target) {
                s
            } else {
                val lines = s.chat.lines.map { line ->
                    if (line.message.id != view.id) line else line.copy(message = line.message.mergedWith(view))
                }
                s.copy(chat = s.chat.copy(lines = lines))
            }
        }
    }

    private fun setReactions(target: ChatTarget, messageId: String, reactions: List<ChatReaction>) {
        mutable.update { s ->
            if (s.chat.target != target) {
                s
            } else {
                val lines = s.chat.lines.map { line ->
                    if (line.message.id != messageId) line else line.copy(message = line.message.copy(reactions = reactions))
                }
                s.copy(chat = s.chat.copy(lines = lines))
            }
        }
    }

    private fun setUploading(target: ChatTarget, uploading: Boolean) {
        mutable.update { s ->
            if (s.chat.target != target) s else s.copy(chat = s.chat.copy(uploading = uploading))
        }
    }

    private fun loadMe() {
        scope.launch {
            val page = withContext(Dispatchers.IO) { socialAttempt { container.friends.roomsPage() } }
            page.getOrNull()?.me?.takeIf { it.isNotEmpty() }?.let { me -> mutable.update { it.copy(me = me) } }
            page.getOrNull()?.rooms?.let { rooms ->
                roomsKnown += rooms.map { it.id }
                val nicks = rooms.flatMap { it.members }.filter { it.nickname.isNotEmpty() }.associate { it.userId to it.nickname }
                mutable.update { it.copy(roomNicks = it.roomNicks + nicks) }
            }
        }
    }

    /** Who types in the open thread, by name; the desktop reads the same two poll fields (src/App.tsx). */
    private fun typersOf(poll: FriendsPoll, showing: ChatTarget): List<String> = when (showing) {
        is ChatTarget.Dm -> if (showing.userId in poll.typing) listOf(friendNickOrNull(showing.userId) ?: "Собеседник") else emptyList()
        is ChatTarget.Room -> poll.roomTyping[showing.roomId].orEmpty().map { id -> nickOf(id) ?: "Кто-то" }
    }

    private fun nickOf(userId: String): String? =
        mutable.value.roomNicks[userId]
            ?: mutable.value.chat.lines.firstOrNull { it.message.from == userId && it.message.fromNick.isNotEmpty() }?.message?.fromNick
            ?: friendNickOrNull(userId)

    private fun act(
        key: String,
        fallback: String,
        reloadFriends: Boolean = true,
        work: suspend () -> String?,
    ): Job = scope.launch {
        if (key in mutable.value.pending) return@launch
        mutable.update { it.copy(pending = it.pending + key) }
        try {
            val result = withContext(Dispatchers.IO) { socialAttempt { work() } }
            val failure = result.exceptionOrNull()
            if (failure == null) {
                result.getOrNull()?.let { host.notice(it) }
            } else {
                fail(failure, fallback)
            }
            if (reloadFriends) host.reloadFriends()
        } finally {
            mutable.update { it.copy(pending = it.pending - key) }
        }
    }

    private fun fail(failure: Throwable?, fallback: String) {
        if (failure.signInLost()) host.recheckSignIn()
        host.notice(failure.readable(fallback), isError = true)
    }

    private fun reset() {
        threadSeq.incrementAndGet()
        blockedSeq.incrementAndGet()
        inviteSeq.incrementAndGet()
        hoursAsked.clear()
        roomsKnown.clear()
        profileSeq.incrementAndGet()
        cursor = 0L
        presenceMemo.clear()
        synchronized(announced) { announced.clear() }
        alerts.dismissAll()
        mutable.update { SocialState(tab = it.tab, callHintShown = it.callHintShown) }
    }

    private fun friendNickOrNull(userId: String): String? =
        host.ui.value.friends.firstOrNull { it.userId == userId }?.nickname?.takeIf { it.isNotEmpty() }

    private fun roomTitle(roomId: String): String =
        host.ui.value.dialogs.firstOrNull { it.kind == DialogKind.ROOM && it.id == roomId }?.title?.takeIf { it.isNotEmpty() } ?: "Группа"

    private fun requestNick(requestId: String): String =
        host.ui.value.friendRequests?.incoming?.firstOrNull { it.id == requestId }?.nickname.orEmpty()

    private fun friendNick(userId: String): String =
        host.ui.value.friends.firstOrNull { it.userId == userId }?.nickname?.takeIf { it.isNotEmpty() } ?: "Игрок"

    private fun nextLocalId(): String = "local-" + localSeq.incrementAndGet()

    /** Presence carries the server's unread count per friend, which already includes the messages of this answer. */
    private fun withPresence(ui: UiState, fresh: List<Friend>): UiState {
        if (fresh.isEmpty()) return ui
        val byId = fresh.associateBy { it.userId }
        val dialogs = ui.dialogs.map { dialog ->
            val live = byId[dialog.id]?.takeIf { dialog.kind == DialogKind.DM } ?: return@map dialog
            dialog.copy(unread = live.unread, lastMessageAt = live.lastMessageAt ?: dialog.lastMessageAt)
        }
        return ui.copy(
            friends = mergePresence(ui.friends, fresh),
            dialogs = dialogs,
            unreadMessages = container.friends.unreadTotal(dialogs),
        )
    }

    private fun bumpThreads(ui: UiState, arrivals: List<ThreadMessage>, directCounted: Boolean): UiState {
        val byThread = arrivals.groupBy { it.target }
        val dialogs = ui.dialogs.map { dialog ->
            if (directCounted && dialog.kind == DialogKind.DM) return@map dialog
            val hits = byThread[dialog.chatTarget()] ?: return@map dialog
            dialog.copy(unread = dialog.unread + hits.size, lastMessageAt = latest(dialog.lastMessageAt, hits))
        }
        val friends = if (directCounted) {
            ui.friends
        } else {
            ui.friends.map { friend ->
                val hits = byThread[ChatTarget.Dm(friend.userId)] ?: return@map friend
                friend.copy(unread = friend.unread + hits.size, lastMessageAt = latest(friend.lastMessageAt, hits))
            }
        }
        return ui.copy(dialogs = dialogs, friends = friends, unreadMessages = container.friends.unreadTotal(dialogs))
    }

    private fun clearThread(ui: UiState, target: ChatTarget): UiState {
        val dialogs = ui.dialogs.map { if (it.chatTarget() == target && it.unread > 0) it.copy(unread = 0) else it }
        val friends = if (target is ChatTarget.Dm) {
            ui.friends.map { if (it.userId == target.userId && it.unread > 0) it.copy(unread = 0) else it }
        } else {
            ui.friends
        }
        return ui.copy(dialogs = dialogs, friends = friends, unreadMessages = container.friends.unreadTotal(dialogs))
    }

    private fun touchThread(ui: UiState, target: ChatTarget, at: Long): UiState = ui.copy(
        dialogs = ui.dialogs.map { if (it.chatTarget() == target) it.copy(lastMessageAt = max(it.lastMessageAt ?: 0L, at)) else it },
    )

    private companion object {
        const val TAG = "millida"
        const val HOURS_PARALLEL = 3
        const val BEAT_PERIOD_MS = 45_000L
        const val POLL_SKEW_MS = 60_000L
        const val ANNOUNCED_LIMIT = 500
        const val PENDING_INVITE_TTL_MS = 60 * 60_000L
        const val REALTIME_CLIENT = "millida-mobile"
    }
}

const val ADD_FRIEND_KEY = "add"

fun friendKey(userId: String): String = "friend:$userId"

fun requestKey(requestId: String): String = "request:$requestId"

fun blockKey(blockedId: String): String = "block:$blockedId"

fun inviteKey(target: ChatTarget): String = "invite:" + threadKey(target)

fun Dialog.chatTarget(): ChatTarget = if (kind == DialogKind.ROOM) ChatTarget.Room(id) else ChatTarget.Dm(id)

fun threadKey(target: ChatTarget): String = when (target) {
    is ChatTarget.Dm -> "dm:" + target.userId
    is ChatTarget.Room -> "room:" + target.roomId
}

fun mergePresence(current: List<Friend>, fresh: List<Friend>): List<Friend> {
    if (fresh.isEmpty()) return current
    val byId = fresh.associateBy { it.userId }
    return current.map { friend ->
        val live = byId[friend.userId] ?: return@map friend
        friend.copy(
            online = live.online,
            playing = live.playing,
            place = live.place,
            text = live.text,
            serverIp = live.serverIp,
            serverName = live.serverName,
            build = live.build,
            gameNick = live.gameNick ?: friend.gameNick,
            lastSeen = live.lastSeen ?: friend.lastSeen,
            unread = live.unread,
            lastMessageAt = live.lastMessageAt ?: friend.lastMessageAt,
        )
    }
}

fun ChatMessage.preview(): ChatReplyPreview = ChatReplyPreview(
    id = id,
    me = me,
    from = fromNick,
    text = text.take(REPLY_PREVIEW_CHARS),
    deleted = deleted,
    kind = attachment?.kind,
)

private const val REPLY_PREVIEW_CHARS = 120

private fun ChatMessage.mergedWith(view: ChatMessage): ChatMessage = copy(
    text = if (view.deleted) "" else view.text.ifEmpty { text },
    editedAt = view.editedAt ?: editedAt,
    deleted = view.deleted || deleted,
    attachment = if (view.deleted) null else view.attachment ?: attachment,
    replyTo = view.replyTo ?: replyTo,
    reactions = view.reactions,
)

private fun previewText(item: ThreadMessage): String {
    val message = item.message
    val call = CallLogs.parse(message.text)
    val body = when {
        ServerInvites.parse(message.text) != null -> "Приглашение на сервер"
        call != null -> CallLogs.title(call, message.me)
        message.attachment?.kind == "image" -> "Картинка"
        message.attachment?.kind == "voice" -> "Голосовое сообщение"
        message.attachment != null && message.text.isEmpty() -> "Вложение"
        else -> Eltaller.plain(message.text).replace('\n', ' ').take(REPLY_PREVIEW_CHARS)
    }
    val author = message.fromNick.takeIf { item.target is ChatTarget.Room && it.isNotEmpty() }
    return if (author != null) "$author: $body" else body
}

private fun latest(current: Long?, hits: List<ThreadMessage>): Long? {
    val newest = hits.maxOf { it.message.ts }
    val best = max(current ?: 0L, newest)
    return if (best > 0) best else current
}

private inline fun <T> socialAttempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

private fun Throwable?.signInLost(): Boolean {
    val start = this ?: return false
    return generateSequence(start) { current -> current.cause?.takeIf { it !== current } }.take(8).any { it is Unauthorized }
}

private fun Throwable?.readable(fallback: String): String {
    if (signInLost()) return "Вход Millida истёк — войди заново"
    return this?.message?.trim()?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() } ?: fallback
}
