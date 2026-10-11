package net.millida.launcher.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.millida.launcher.Area
import net.millida.launcher.ChatLine
import net.millida.launcher.chatTarget
import net.millida.launcher.threadKey
import net.millida.launcher.ChatThreadState
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.SendState
import net.millida.launcher.UiState
import net.millida.launcher.core.call.CallLogs
import net.millida.launcher.core.friends.ChatMessage
import net.millida.launcher.core.friends.ChatTarget
import net.millida.launcher.core.friends.ChatTyping
import net.millida.launcher.core.friends.ServerInvites
import net.millida.launcher.ui.social.ChatTypingLine
import net.millida.launcher.ui.social.DialogRow
import net.millida.launcher.ui.social.inboxOf
import net.millida.launcher.ui.social.FriendProfileHeader
import net.millida.launcher.ui.social.InvitePlaySheet
import net.millida.launcher.ui.social.SAVED_PICTURE_NOTICE
import net.millida.launcher.ui.social.copyChatPicture
import net.millida.launcher.ui.social.isOwnMediaUrl
import net.millida.launcher.ui.social.pictureFailure
import net.millida.launcher.ui.social.saveChatPicture
import net.millida.launcher.core.friends.DialogKind
import net.millida.launcher.core.friends.Friend
import net.millida.launcher.core.friends.VoiceNotes
import net.millida.launcher.ui.social.ChatComposer
import net.millida.launcher.ui.social.REC_BARS
import net.millida.launcher.ui.social.VoiceRecordFailure
import net.millida.launcher.ui.social.VoiceRecorder
import net.millida.launcher.ui.social.VoiceRecordingUi
import net.millida.launcher.ui.social.rememberMicGate
import net.millida.launcher.ui.social.ChatDay
import net.millida.launcher.ui.social.ChatSkeleton
import net.millida.launcher.ui.social.ComposerQuote
import net.millida.launcher.ui.social.ImageViewer
import net.millida.launcher.ui.social.MessageBubble
import net.millida.launcher.ui.social.MessageMenu
import net.millida.launcher.ui.social.OlderRow
import net.millida.launcher.ui.social.RoomAvatar
import net.millida.launcher.ui.social.avatarFor
import net.millida.launcher.ui.social.dayLabel
import net.millida.launcher.ui.social.localDay
import net.millida.launcher.ui.social.presence
import net.millida.launcher.ui.social.readChatImage
import net.millida.launcher.ui.social.rememberCallHandoff
import net.millida.launcher.ui.social.shownName
import net.millida.launcher.ui.social.statusLine
import kotlin.math.max

private const val QUOTE_FLASH_MS = 1_400L

private const val GROUP_GAP_MS = 120_000L
private const val FOCUS_DELAY_MS = 120L

private data class ChatPeer(
    val title: String,
    val subtitle: String,
    val avatarUrl: String?,
    val friend: Friend?,
    val room: Boolean,
)

private fun peerOf(state: UiState, target: ChatTarget): ChatPeer = when (target) {
    is ChatTarget.Dm -> {
        val friend = state.friends.firstOrNull { it.userId == target.userId }
        val dialog = state.dialogs.firstOrNull { it.kind == DialogKind.DM && it.id == target.userId }
        val title = friend?.shownName() ?: dialog?.title?.takeIf { it.isNotEmpty() } ?: "Переписка"
        ChatPeer(
            title = title,
            subtitle = friend?.statusLine() ?: dialog?.subtitle.orEmpty(),
            avatarUrl = avatarFor(title, friend?.avatarUrl ?: dialog?.avatarUrl),
            friend = friend,
            room = false,
        )
    }

    is ChatTarget.Room -> {
        val dialog = state.dialogs.firstOrNull { it.kind == DialogKind.ROOM && it.id == target.roomId }
        ChatPeer(
            title = dialog?.title?.takeIf { it.isNotEmpty() } ?: "Группа",
            subtitle = dialog?.subtitle?.takeIf { it.isNotEmpty() } ?: "Группа",
            avatarUrl = null,
            friend = null,
            room = true,
        )
    }
}

/**
 * The day separator rides inside the first message of its day instead of being
 * an item of its own: a prepended older page would otherwise move that key
 * above the viewport anchor and make the list jump.
 */
private data class ChatLineRow(
    val line: ChatLine,
    val grouped: Boolean,
    val author: String?,
    val dayLabel: String?,
    val key: String,
)

/** Grouping: a run from one side within two minutes reads as one utterance. */
private fun threadRows(lines: List<ChatLine>, room: Boolean): List<ChatLineRow> {
    val rows = ArrayList<ChatLineRow>(lines.size)
    val used = HashSet<String>()

    fun unique(key: String): String {
        var candidate = key
        var copy = 1
        while (!used.add(candidate)) candidate = key + "#" + copy++
        return candidate
    }

    var lastDay: Long? = null
    var previous: ChatLine? = null
    for (line in lines) {
        val message = line.message
        val day = if (message.ts > 0) localDay(message.ts).toEpochDay() else lastDay
        val newDay = day != null && day != lastDay
        val label = if (newDay && message.ts > 0) dayLabel(localDay(message.ts)) else null
        val before = previous?.message
        val grouped = !newDay && before != null && before.me == message.me &&
            before.from == message.from && (message.ts - before.ts) in 0L until GROUP_GAP_MS
        val author = message.fromNick.takeIf { room && !message.me && !grouped && it.isNotEmpty() }
        rows += ChatLineRow(line, grouped, author, label, unique("line:" + line.key.ifEmpty { "i${rows.size}" }))
        lastDay = day
        previous = line
    }
    return rows
}

@Composable
fun ChatScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val target = state.chatTarget
    if (!state.millidaSignedIn) {
        MScreen(modifier = modifier, title = "Переписка", onBack = { model.back() }) {
            item(key = "gate") { MSignInGate(onSignIn = model::loginMillida, title = "Сообщения — после входа") }
        }
        return
    }
    if (LocalWideLayout.current) {
        WideMessages(state, model, target, modifier)
        return
    }
    if (target == null) {
        MScreen(modifier = modifier, title = "Переписка", onBack = { model.back() }) {
            item(key = "none") {
                MEmptyState(
                    icon = MIcons.chat,
                    title = "Переписка не выбрана",
                    actionText = "К друзьям",
                    onAction = { model.open(Screen.FRIENDS) },
                    actionIcon = MIcons.friends,
                )
            }
        }
        return
    }
    ChatThread(state, model, target, modifier)
}

@Composable
private fun ChatThread(
    state: UiState,
    model: LauncherViewModel,
    target: ChatTarget,
    modifier: Modifier,
    onBack: (() -> Unit)? = { model.back() },
) {
    val social by model.social.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val colors = millida
    val chat = social.chat.takeIf { it.target == target } ?: ChatThreadState(target = target, loading = true)
    val peer = remember(state.friends, state.dialogs, target) { peerOf(state, target) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val call = rememberCallHandoff(model, social.callHintShown)

    var draft by rememberSaveable(target.toString()) { mutableStateOf("") }
    var replyTo by remember(target) { mutableStateOf<ChatMessage?>(null) }
    var editing by remember(target) { mutableStateOf<ChatMessage?>(null) }
    var menuFor by remember(target) { mutableStateOf<ChatLine?>(null) }
    var deleting by remember(target) { mutableStateOf<ChatMessage?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }
    var seenNewest by remember(target) { mutableStateOf<String?>(null) }
    var settled by remember(target) { mutableStateOf(false) }
    var recorder by remember(target) { mutableStateOf<VoiceRecorder?>(null) }
    var recMs by remember(target) { mutableLongStateOf(0L) }
    var recBars by remember(target) { mutableStateOf(emptyList<Float>()) }
    var uploadLabel by remember { mutableStateOf("Отправляем картинку") }
    var inviting by remember(target) { mutableStateOf(false) }

    LaunchedEffect(target) { model.social.ensureThread(target) }
    DisposableEffect(target) {
        model.social.attach(target)
        onDispose { model.social.detach(target) }
    }
    LaunchedEffect(focusTick) {
        if (focusTick > 0) {
            delay(FOCUS_DELAY_MS)
            runCatching { focus.requestFocus() }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val quoted = replyTo
            replyTo = null
            uploadLabel = "Отправляем картинку"
            model.social.sendImage({ readChatImage(context.applicationContext, uri) }, quoted)
        }
    }

    DisposableEffect(target) {
        onDispose {
            recorder?.cancel()
            recorder = null
        }
    }

    fun stopRecording(keep: Boolean) {
        val active = recorder ?: return
        recorder = null
        if (!keep) {
            active.cancel()
            return
        }
        val take = try {
            active.stop()
        } catch (failed: VoiceRecordFailure) {
            model.notice(failed.message ?: "Запись не получилась — попробуй ещё раз", isError = !failed.tooShort)
            return
        }
        val quoted = replyTo
        replyTo = null
        uploadLabel = "Отправляем голосовое"
        val job = model.social.sendVoice({ take.read() }, take.durationMs, take.peaks, quoted)
        if (job == null) {
            take.discard()
            model.notice("Дождись, пока уйдёт прошлое вложение, и запиши снова", isError = true)
        } else {
            job.invokeOnCompletion { take.discard() }
        }
    }

    val startRecording = rememberMicGate(model) {
        if (recorder == null && !chat.uploading) {
            recMs = 0L
            recBars = emptyList()
            recorder = try {
                VoiceRecorder.start(
                    context = context.applicationContext,
                    scope = scope,
                    onLevel = { level, ms ->
                        recBars = (recBars + VoiceNotes.liveBar(level)).takeLast(REC_BARS)
                        recMs = ms
                    },
                    onLimit = { stopRecording(keep = true) },
                )
            } catch (failed: VoiceRecordFailure) {
                model.notice(failed.message ?: "Микрофон не включился", isError = true)
                null
            }
        }
    }

    val rows = remember(chat.lines, peer.room) { threadRows(chat.lines, peer.room) }
    val headed = chat.profileOpen && target is ChatTarget.Dm
    val lead = if (headed) 1 else 0
    val typingText = ChatTyping.line(chat.typers)
    var seeking by remember(target) { mutableStateOf<String?>(null) }
    var flashId by remember(target) { mutableStateOf<String?>(null) }
    var flashTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(seeking, rows, chat.hasMore, chat.olderLoading, chat.olderError) {
        val id = seeking ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.line.message.id == id }
        when {
            index >= 0 -> {
                seeking = null
                listState.animateScrollToItem(index + lead)
                flashId = id
                flashTick += 1
            }
            chat.olderError != null || (!chat.hasMore && !chat.olderLoading) -> {
                seeking = null
                model.notice("Исходное сообщение уже недоступно", isError = true)
            }
            !chat.olderLoading -> model.social.loadOlder()
        }
    }
    LaunchedEffect(flashTick) {
        if (flashId == null) return@LaunchedEffect
        delay(QUOTE_FLASH_MS)
        flashId = null
    }
    val newest = chat.lines.lastOrNull()
    LaunchedEffect(newest?.key) {
        if (newest == null || rows.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val previousIndex = rows.indexOfLast { it.line.key == seenNewest }
        val added = if (previousIndex >= 0) rows.lastIndex - previousIndex else 0
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisible >= info.totalItemsCount - 2 - added
        val first = seenNewest == null
        seenNewest = newest.key
        when {
            first -> listState.scrollToItem(rows.lastIndex + lead)
            nearBottom || newest.message.me -> listState.animateScrollToItem(rows.lastIndex + lead)
        }
        settled = true
    }
    val nearTop by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(nearTop, settled, chat.hasMore, chat.olderLoading, chat.olderError) {
        if (nearTop && settled && chat.hasMore && !chat.olderLoading && chat.olderError == null) model.social.loadOlder()
    }
    val lifted by remember { derivedStateOf { listState.canScrollBackward } }
    val awayFromBottom by remember { derivedStateOf { listState.canScrollForward } }

    fun send() {
        val text = draft
        val edit = editing
        if (text.isBlank()) return
        if (edit != null) {
            if (text.trim() != edit.text) {
                model.social.edit(edit.id, text) { saved ->
                    if (!saved && editing == null && draft.isEmpty()) {
                        editing = edit
                        draft = text
                    }
                }
            }
            editing = null
        } else {
            model.social.send(text, replyTo)
            replyTo = null
        }
        draft = ""
    }

    fun sendInvite(address: String, name: String, version: String?) {
        if (!ServerInvites.isServerAddr(address)) {
            model.notice("Не похоже на адрес сервера", isError = true)
            return
        }
        inviting = false
        val label = name.ifBlank { address }.trim().take(ServerInvites.NAME_LIMIT)
        model.social.send(ServerInvites.encode(address.trim(), label, version), null)
    }

    fun currentBuildVersion(): String? =
        (state.profiles.firstOrNull { it.id == state.activeProfileId } ?: state.profiles.firstOrNull())?.gameVersion

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding(),
    ) {
        ChatTopBar(
            peer = peer,
            divider = lifted,
            onBack = onBack,
            onCall = { call(target) },
            onInvite = { inviting = true },
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val failure = chat.error
            when {
                chat.lines.isEmpty() && chat.loading && !headed -> ChatSkeleton()
                chat.lines.isEmpty() && failure != null && !headed -> Column(Modifier.padding(16.dp)) {
                    MErrorCard(failure, onRetry = { model.social.reloadThread() })
                }
                chat.lines.isEmpty() && !headed && typingText == null -> MEmptyState(
                    icon = MIcons.chat,
                    title = if (peer.room) "Пока тихо — начни разговор" else "Напиши первым",
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                ) {
                    if (headed) {
                        item(key = "profile", contentType = "profile") {
                            FriendProfileHeader(
                                nick = peer.friend?.shownName() ?: chat.profile?.nickname?.takeIf { it.isNotEmpty() } ?: peer.title,
                                status = peer.subtitle.ifEmpty { chat.profile?.text.orEmpty() },
                                card = chat.profile,
                                onJoin = { card ->
                                    val address = card.serverIp.orEmpty().trim()
                                    model.playOnAddress(
                                        address,
                                        card.serverName?.takeIf { it.isNotBlank() } ?: "Сервер ${peer.title}",
                                    )
                                },
                            )
                        }
                    }
                    items(rows, key = { it.key }, contentType = { if (it.dayLabel != null) 1 else 0 }) { row ->
                        val message = row.line.message
                        val read = !peer.room && message.me && row.line.state == SendState.Sent &&
                            message.ts > 0 && message.ts <= chat.peerReadAt
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .animateItem(),
                        ) {
                            row.dayLabel?.let { ChatDay(it) }
                            MessageBubble(
                                line = row.line,
                                author = row.author,
                                peerName = if (peer.room) "Участник" else peer.title,
                                grouped = row.grouped,
                                read = read,
                                onMenu = { menuFor = row.line },
                                onReply = {
                                    replyTo = message
                                    editing = null
                                    focusTick += 1
                                },
                                onOpenImage = { viewing = it },
                                onRetry = { model.social.retry(row.line.localId) },
                                onDrop = { model.social.drop(row.line.localId) },
                                onCallBack = if (peer.room) null else ({ call(target) }),
                                flash = message.id.isNotEmpty() && message.id == flashId,
                                onQuote = { id -> seeking = id },
                                onJoinServer = { invite ->
                                    model.playServer(invite.addr, invite.name, listOfNotNull(invite.version))
                                },
                                onReact = { emoji -> model.social.react(message.id, emoji) },
                                room = peer.room,
                            )
                        }
                    }
                    if (typingText != null) {
                        item(key = "typing", contentType = "typing") {
                            ChatTypingLine(typingText, Modifier.animateItem())
                        }
                    }
                }
            }
            val topLoading = chat.olderLoading || (chat.loading && failure == null)
            val topError = failure ?: chat.olderError
            if ((chat.lines.isNotEmpty() || headed) && (topLoading || topError != null)) {
                OlderRow(
                    loading = topLoading && topError == null,
                    error = topError,
                    onRetry = { if (failure != null) model.social.reloadThread() else model.social.loadOlder() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .background(colors.bg)
                        .padding(horizontal = 12.dp),
                )
            }
            if (settled && awayFromBottom && rows.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                ) {
                    MIconButton(
                        MIcons.arrowDown,
                        onClick = { scope.launch { listState.animateScrollToItem(rows.lastIndex + lead) } },
                        label = "К последним",
                    )
                }
            }
        }
        val quote = editing?.let { ComposerQuote(MIcons.edit, "Изменение сообщения", it.text) }
            ?: replyTo?.let { reply ->
                ComposerQuote(
                    MIcons.reply,
                    if (reply.me) "Ответ на своё сообщение" else "Ответ",
                    CallLogs.parse(reply.text)?.let { CallLogs.title(it, reply.me) } ?: reply.text.ifEmpty {
                        when {
                            reply.attachment?.kind == "voice" -> "Голосовое сообщение"
                            reply.attachment != null -> "Картинка"
                            else -> "Сообщение"
                        }
                    },
                )
            }
        ChatComposer(
            draft = draft,
            onDraft = { draft = it },
            quote = quote,
            onCancelQuote = {
                if (editing != null) draft = ""
                editing = null
                replyTo = null
            },
            editing = editing != null,
            uploading = chat.uploading,
            onPickImage = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onSend = { send() },
            focusRequester = focus,
            recording = if (recorder != null) VoiceRecordingUi(recMs, recBars) else null,
            onRecord = startRecording,
            onCancelRecord = { stopRecording(keep = false) },
            onSendRecord = { stopRecording(keep = true) },
            uploadingText = uploadLabel,
        )
    }

    menuFor?.let { line ->
        val message = line.message
        val own = message.me
        val text = if (CallLogs.parse(message.text) != null) "" else message.text
        val picture = message.attachment?.takeIf { it.kind == "image" && isOwnMediaUrl(it.url) }?.url
        MessageMenu(
            message = message,
            onReply = {
                menuFor = null
                replyTo = message
                editing = null
                focusTick += 1
            },
            onCopy = if (text.isNotEmpty()) {
                {
                    menuFor = null
                    clipboard.setText(AnnotatedString(text))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) model.notice("Текст скопирован")
                }
            } else {
                null
            },
            onEdit = if (own && text.isNotEmpty()) {
                {
                    menuFor = null
                    editing = message
                    replyTo = null
                    draft = text
                    focusTick += 1
                }
            } else {
                null
            },
            onDelete = if (own) {
                {
                    menuFor = null
                    deleting = message
                }
            } else {
                null
            },
            onDismiss = { menuFor = null },
            onReact = { emoji ->
                menuFor = null
                model.social.react(message.id, emoji)
            },
            onCopyImage = picture?.let { url ->
                {
                    menuFor = null
                    scope.launch {
                        val failure = pictureFailure { copyChatPicture(context, url) }
                        when {
                            failure != null -> model.notice(failure, isError = true)
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> model.notice("Картинка скопирована")
                        }
                    }
                }
            },
            onSaveImage = picture?.let { url ->
                {
                    menuFor = null
                    scope.launch {
                        var inGallery = false
                        val failure = pictureFailure { inGallery = saveChatPicture(context, url) }
                        when {
                            failure != null -> model.notice(failure, isError = true)
                            inGallery -> model.notice(SAVED_PICTURE_NOTICE)
                        }
                    }
                }
            },
        )
    }

    if (inviting) {
        LaunchedEffect(Unit) { model.social.loadMyServers() }
        InvitePlaySheet(
            servers = social.myServers,
            onServer = { server ->
                sendInvite(server.address, ServerInvites.serverTitle(server), server.version.ifBlank { null })
            },
            onCreate = {
                inviting = false
                model.social.invitePlay(target, peer.title) { model.open(Screen.HOSTING) }
            },
            onAddress = { address -> sendInvite(address, address, currentBuildVersion()) },
            onDismiss = { inviting = false },
        )
    }

    deleting?.let { message ->
        MConfirmDialog(
            title = "Удалить сообщение?",
            text = "Сообщение исчезнет и у собеседника.",
            confirm = "Удалить",
            onConfirm = {
                deleting = null
                if (editing?.id == message.id) {
                    editing = null
                    draft = ""
                }
                model.social.delete(message.id)
            },
            onDismiss = { deleting = null },
        )
    }

    viewing?.let { url -> ImageViewer(url, onDismiss = { viewing = null }) }
}

@Composable
private fun ChatTopBar(peer: ChatPeer, divider: Boolean, onBack: (() -> Unit)?, onCall: () -> Unit, onInvite: () -> Unit) {
    val colors = millida
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .drawBehind {
                if (divider) {
                    val line = max(1f, 2.dp.roundToPx().toFloat())
                    drawRect(colors.plateBottom, Offset(0f, size.height - line), Size(size.width, line))
                }
            }
            .heightIn(min = 64.dp)
            .padding(horizontal = screenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            MBackButton(onBack)
            Spacer(Modifier.width(12.dp))
        }
        if (peer.room) {
            RoomAvatar(size = 40.dp)
        } else {
            MAvatar(url = peer.avatarUrl, size = 40.dp, presence = peer.friend?.presence())
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                peer.title,
                style = MillidaType.barTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (peer.subtitle.isNotEmpty()) {
                Text(
                    peer.subtitle,
                    style = MillidaType.small,
                    color = if (peer.friend?.playing == true) colors.accentText else colors.fgFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        MPrimaryButton("Позвать играть", onClick = onInvite, icon = MIcons.server, size = MButtonSize.Small)
        Spacer(Modifier.width(8.dp))
        MIconButton(MIcons.phone, onClick = onCall, label = "Позвонить")
    }
}

private val MESSAGES_SIDE_WIDTH = 300.dp
private val MESSAGES_GAP = 12.dp

/**
 * The desktop's «Сообщения» (`screens/Messages.tsx`): the strip on top, the
 * chats on a plate down the left - personal ones and groups in one list by
 * the last message, a search that also finds friends to start a chat with -
 * and the open conversation filling the rest. Coming in without a chat
 * opens the topmost one, so the right side is never blank.
 */
@Composable
private fun WideMessages(state: UiState, model: LauncherViewModel, target: ChatTarget?, modifier: Modifier) {
    val social by model.social.state.collectAsStateWithLifecycle()
    val colors = millida
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { model.loadFriends() }

    val needle = query.trim().lowercase()
    val byId = remember(state.friends) { state.friends.associateBy { it.userId } }
    val threads = remember(state.dialogs, byId, needle) {
        inboxOf(state.dialogs).filter { dialog ->
            val name = byId[dialog.id]?.takeIf { dialog.kind == DialogKind.DM }?.shownName() ?: dialog.title
            needle.isEmpty() || name.lowercase().contains(needle)
        }
    }
    val inList = threads.mapTo(HashSet()) { threadKey(it.chatTarget()) }
    val fresh = (target as? ChatTarget.Dm)
        ?.takeIf { needle.isEmpty() && threadKey(it) !in inList }
        ?.let { byId[it.userId] }
    val others = if (needle.isEmpty()) {
        emptyList()
    } else {
        state.friends.filter { friend ->
            threadKey(ChatTarget.Dm(friend.userId)) !in inList &&
                (friend.nickname.lowercase().contains(needle) || friend.gameNick.orEmpty().lowercase().contains(needle))
        }
    }
    val loading = state.isLoading(Area.FRIENDS) && state.dialogs.isEmpty()

    LaunchedEffect(target == null, threads.firstOrNull()?.id) {
        if (target == null) threads.firstOrNull()?.let { model.openChat(it.chatTarget()) }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        WideTopBar(title = null, onBack = null)
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = screenPadding, end = screenPadding, top = MESSAGES_GAP),
            horizontalArrangement = Arrangement.spacedBy(MESSAGES_GAP),
        ) {
            Column(
                Modifier
                    .width(MESSAGES_SIDE_WIDTH)
                    .fillMaxHeight()
                    .padding(bottom = MESSAGES_GAP)
                    .pixelSurface(colors.surface, MillidaCut.plate),
            ) {
                MField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Поиск",
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                    leadingIcon = MIcons.search,
                    clearable = true,
                    imeAction = ImeAction.Go,
                    onImeAction = {
                        val first = threads.firstOrNull()?.chatTarget() ?: others.firstOrNull()?.let { ChatTarget.Dm(it.userId) }
                        if (first != null) model.openChat(first)
                    },
                )
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (loading) {
                        items(5, key = { "skeleton:$it" }) { MSkeletonRow() }
                    }
                    if (fresh != null) {
                        item(key = "fresh:" + fresh.userId) {
                            PaneFriendRow(fresh, active = true, onOpen = {})
                        }
                    }
                    items(threads, key = { "thread:" + threadKey(it.chatTarget()) }) { dialog ->
                        val thread = dialog.chatTarget()
                        DialogRow(
                            dialog = dialog,
                            friend = if (dialog.kind == DialogKind.DM) byId[dialog.id] else null,
                            preview = social.previews[threadKey(thread)],
                            onOpen = { if (thread != target) model.openChat(thread) },
                            plate = false,
                            active = thread == target,
                        )
                    }
                    if (others.isNotEmpty()) {
                        item(key = "cap:friends") {
                            Text(
                                "Друзья",
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                                style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
                                color = colors.fgSubtle,
                            )
                        }
                        items(others, key = { "friend:" + it.userId }) { friend ->
                            PaneFriendRow(friend, active = false, onOpen = { model.openChat(ChatTarget.Dm(friend.userId)) })
                        }
                    }
                    if (!loading && threads.isEmpty() && others.isEmpty() && fresh == null) {
                        item(key = "blank") {
                            if (needle.isNotEmpty()) {
                                Text(
                                    "Никого не нашли",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                                    style = MillidaType.small,
                                    color = colors.fgFaint,
                                )
                            } else {
                                MEmptyState(
                                    icon = MIcons.chat,
                                    title = "Переписок нет",
                                    actionText = "Друзья",
                                    onAction = { model.open(Screen.FRIENDS) },
                                    actionIcon = MIcons.friends,
                                )
                            }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                if (target != null) {
                    key(threadKey(target)) {
                        ChatThread(state, model, target, Modifier.fillMaxSize(), onBack = null)
                    }
                } else if (!loading) {
                    MEmptyState(
                        icon = MIcons.chat,
                        title = "Выбери чат слева",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

@Composable
private fun PaneFriendRow(friend: Friend, active: Boolean, onOpen: () -> Unit) {
    val colors = millida
    val name = friend.shownName()
    MListRow(
        title = name,
        modifier = if (active) Modifier.pixelSurface(colors.accentSoft, MillidaCut.control) else Modifier,
        subtitle = friend.statusLine().ifEmpty { null },
        onClick = onOpen,
        plate = false,
        leading = { MAvatar(url = avatarFor(name, friend.avatarUrl), size = 44.dp, presence = friend.presence()) },
    )
}
