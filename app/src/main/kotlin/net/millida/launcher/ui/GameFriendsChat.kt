package net.millida.launcher.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.millida.launcher.Prefs
import net.millida.launcher.call.FriendsBridgeClient
import net.millida.launcher.core.call.CallLogs
import net.millida.launcher.core.friends.OverlayCall
import net.millida.launcher.core.friends.OverlayCard
import net.millida.launcher.core.friends.OverlayDialog
import net.millida.launcher.core.friends.OverlayLine
import net.millida.launcher.core.friends.OverlaySnapshot
import net.millida.launcher.core.friends.OverlayThread
import net.millida.launcher.core.friends.OverlayWire
import net.millida.launcher.ui.social.EltText

/** The setting, with the desktop's name for it. */
@Composable
internal fun FriendsOverlaySwitch() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var on by remember { mutableStateOf(prefs.friendsOverlay) }
    MSwitchRow(
        "Чат друзей поверх игры",
        "Сообщения и звонки — не выходя из игры",
        on,
    ) { next ->
        prefs.friendsOverlay = next
        on = next
    }
}

/** Bound only while the game screen lives and the setting is on. */
@Composable
internal fun rememberFriendsBridge(enabled: Boolean): FriendsBridgeClient? {
    if (!enabled) return null
    val context = LocalContext.current
    val client = remember { FriendsBridgeClient(context.applicationContext) }
    DisposableEffect(client) {
        client.bind()
        onDispose { client.unbind() }
    }
    return client
}

/**
 * Everything of the friends chat that floats over the game: arrival cards,
 * the call cards and, when opened, the chat panel on the right edge. It sits
 * above the controls but never covers the middle of the screen.
 */
@Composable
internal fun GameFriendsLayer(
    client: FriendsBridgeClient,
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val snapshot by client.snapshot.collectAsStateWithLifecycle()
    val call by client.call.collectAsStateWithLifecycle()
    val invite by client.roomInvite.collectAsStateWithLifecycle()
    var card by remember { mutableStateOf<OverlayCard?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = pendingMic
        pendingMic = null
        if (granted) next?.invoke() else notice = "Без микрофона разговор не начать — разреши его в настройках телефона"
    }
    fun withMic(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingMic = action
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(client) { client.cards.collect { card = it } }
    LaunchedEffect(client) { client.notices.collect { notice = it } }
    LaunchedEffect(card) {
        if (card != null) {
            delay(OverlayWire.CARD_MS)
            card = null
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(OverlayWire.CARD_MS)
            notice = null
        }
    }
    LaunchedEffect(open) { if (open) client.refresh() }

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.6f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            call?.let { active ->
                GameCallCard(
                    call = active,
                    onAccept = { withMic(client::accept) },
                    onDecline = client::decline,
                    onHangUp = client::hangUp,
                    onMute = client::toggleMute,
                    onSpeaker = client::toggleSpeaker,
                )
            }
            invite?.takeIf { call == null }?.let { room ->
                FloatingCard(
                    title = room.title,
                    text = "${room.text} зовёт в разговор",
                    onTap = null,
                    trailing = {
                        MTextButton("Зайти", onClick = { withMic { client.joinRoom(room.id, room.title) } }, icon = MIcons.headset)
                        MIconButton(MIcons.close, onClick = client::dismissRoomInvite, label = "Скрыть")
                    },
                )
            }
            card?.takeIf { !open }?.let { arrived ->
                FloatingCard(
                    title = arrived.title,
                    text = arrived.text,
                    onTap = {
                        card = null
                        client.open(arrived.kind, arrived.id, arrived.title)
                        onOpen()
                    },
                    trailing = { MIconButton(MIcons.close, onClick = { card = null }, label = "Скрыть") },
                )
            }
            notice?.let { text ->
                FloatingCard(title = null, text = text, onTap = { notice = null }, trailing = {})
            }
        }
        if (open) {
            FriendsPanel(
                snapshot = snapshot,
                connected = client.connected.collectAsStateWithLifecycle().value,
                onOpenThread = { dialog -> client.open(dialog.kind, dialog.id, dialog.title) },
                onBack = client::close,
                onSend = client::sendText,
                onClose = onClose,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .widthIn(max = 380.dp)
                    .fillMaxWidth(0.45f)
                    .imePadding(),
            )
        }
    }
}

@Composable
private fun FloatingCard(
    title: String?,
    text: String,
    onTap: (() -> Unit)?,
    trailing: @Composable () -> Unit,
) {
    val colors = millida
    val tap = if (onTap != null) Modifier.pointerInput(onTap) { detectTapGestures { onTap() } } else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .pixelSurface(colors.raised, MillidaCut.plate, colors.plateFrame())
            .then(tap)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(MIcons.chat, size = 16.dp, color = colors.accentText)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (title != null) {
                Text(title, style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            EltText(text, style = MillidaType.small, color = colors.fgMuted, quote = true, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun GameCallCard(
    call: OverlayCall,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
) {
    val colors = millida
    val incoming = call.status == "incoming"
    val line = when (call.status) {
        "incoming" -> "Входящий звонок"
        "outgoing" -> "Звоним…"
        "connecting" -> "Соединяем…"
        else -> call.quality.ifEmpty { "Разговор" }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .pixelSurface(colors.raised, MillidaCut.plate, colors.plateFrame())
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MIcon(MIcons.phone, size = 16.dp, color = colors.accentText)
        Column(Modifier.weight(1f)) {
            Text(call.title, style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
        }
        if (incoming) {
            MIconButton(MIcons.phone, onClick = onAccept, tint = colors.onAccent, background = colors.accent, label = "Ответить")
            MIconButton(MIcons.phoneHang, onClick = onDecline, tint = colors.danger, label = "Отклонить")
        } else {
            MIconButton(
                if (call.muted) MIcons.micOff else MIcons.mic,
                onClick = onMute,
                tint = if (call.muted) colors.danger else colors.fgSubtle,
                label = if (call.muted) "Включить микрофон" else "Выключить микрофон",
            )
            MIconButton(
                if (call.speaker) MIcons.volume else MIcons.mute,
                onClick = onSpeaker,
                tint = if (call.speaker) colors.accentText else colors.fgSubtle,
                label = "Громкая связь",
            )
            MIconButton(MIcons.phoneHang, onClick = onHangUp, tint = colors.danger, label = "Завершить")
        }
    }
}

@Composable
private fun FriendsPanel(
    snapshot: OverlaySnapshot?,
    connected: Boolean,
    onOpenThread: (OverlayDialog) -> Unit,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val thread = snapshot?.thread
    Column(
        modifier
            .pixelSurface(colors.raised, MillidaCut.plate, colors.plateFrame())
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (thread != null) {
                MBackButton(onBack)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                thread?.title?.ifEmpty { "Переписка" } ?: "Друзья",
                modifier = Modifier.weight(1f),
                style = MillidaType.barTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MIconButton(MIcons.close, onClick = onClose, label = "Закрыть")
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            when {
                snapshot == null || (!connected && snapshot.dialogs.isEmpty()) -> PanelSkeleton()
                !snapshot.signedIn -> PanelText("Войди в Millida в лаунчере — и переписка появится здесь")
                thread != null -> ThreadView(thread, onSend)
                snapshot.loading && snapshot.dialogs.isEmpty() -> PanelSkeleton()
                snapshot.error != null && snapshot.dialogs.isEmpty() -> PanelText(snapshot.error.orEmpty())
                snapshot.dialogs.isEmpty() -> PanelText("Пока не с кем переписываться — добавь друзей в лаунчере")
                else -> DialogList(snapshot.dialogs, onOpenThread)
            }
        }
    }
}

@Composable
private fun PanelSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(4) { MSkeleton(height = 44.dp, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun PanelText(text: String) {
    Text(text, style = MillidaType.small, color = millida.fgSubtle)
}

@Composable
private fun DialogList(dialogs: List<OverlayDialog>, onOpen: (OverlayDialog) -> Unit) {
    val sorted = remember(dialogs) {
        dialogs.sortedWith(compareByDescending<OverlayDialog> { it.unread > 0 }.thenByDescending { it.lastMessageAt ?: 0L })
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(sorted, key = { it.key }) { dialog ->
            MListRow(
                title = dialog.title.ifEmpty { "Переписка" },
                subtitle = dialog.subtitle.ifEmpty { null },
                onClick = { onOpen(dialog) },
                plate = false,
                trailing = { MCounterBadge(dialog.unread) },
            )
        }
    }
}

@Composable
private fun ThreadView(thread: OverlayThread, onSend: (String) -> Unit) {
    val colors = millida
    var draft by remember(thread.kind, thread.id) { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(thread.lines.lastOrNull()?.id) {
        if (thread.lines.isNotEmpty()) list.scrollToItem(thread.lines.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                thread.lines.isEmpty() && thread.loading -> PanelSkeleton()
                thread.lines.isEmpty() && thread.error != null -> PanelText(thread.error.orEmpty())
                thread.lines.isEmpty() -> PanelText("Напиши первым")
                else -> LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(thread.lines, key = { it.id.ifEmpty { "t" + it.ts } }) { line ->
                        OverlayBubble(line, room = thread.kind == OverlayWire.KIND_ROOM)
                    }
                }
            }
        }
        val failure = thread.error
        if (failure != null && thread.lines.isNotEmpty()) {
            Text(failure, style = MillidaType.small, color = colors.danger, maxLines = 2)
        }
        Row(
            Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                MField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = "Сообщение",
                    imeAction = ImeAction.Send,
                    onImeAction = {
                        if (draft.isNotBlank() && !thread.sending) {
                            onSend(draft)
                            draft = ""
                        }
                    },
                )
            }
            Spacer(Modifier.width(6.dp))
            MIconButton(
                MIcons.send,
                onClick = {
                    onSend(draft)
                    draft = ""
                },
                tint = colors.onAccent,
                background = colors.accent,
                enabled = draft.isNotBlank() && !thread.sending,
                label = "Отправить",
            )
        }
    }
}

@Composable
private fun OverlayBubble(line: OverlayLine, room: Boolean) {
    val colors = millida
    val callLog = CallLogs.parse(line.text)
    val body = when {
        line.deleted -> "Сообщение удалено"
        callLog != null -> CallLogs.title(callLog, line.me)
        line.attachment == "voice" -> "Голосовое сообщение"
        line.attachment != null && line.text.isEmpty() -> "Картинка"
        else -> line.text
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (line.me) Alignment.End else Alignment.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 280.dp)
                .clip(MillidaShape.bubble)
                .background(if (line.me) colors.accentToday else colors.surface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            if (room && !line.me && line.fromNick.isNotEmpty()) {
                Text(line.fromNick, style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = colors.accentText, maxLines = 1)
            }
            EltText(body, style = MillidaType.small, color = if (line.deleted) colors.fgFaint else colors.fg)
        }
    }
}
