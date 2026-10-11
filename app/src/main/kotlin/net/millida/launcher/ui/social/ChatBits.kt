package net.millida.launcher.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import net.millida.launcher.core.call.CallLogEntry
import net.millida.launcher.ui.MAvatar
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import net.millida.launcher.ChatLine
import net.millida.launcher.SendState
import net.millida.launcher.core.call.CallLogs
import net.millida.launcher.core.friends.ChatAttachment
import net.millida.launcher.core.friends.ChatMessage
import net.millida.launcher.core.friends.ChatReplyPreview
import net.millida.launcher.core.friends.Eltaller
import net.millida.launcher.core.friends.VoiceNotes
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.LocalUiFeedback
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPixelLoader
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import kotlin.math.max
import androidx.compose.foundation.clickable
import net.millida.launcher.core.friends.ServerInvite
import net.millida.launcher.core.friends.ServerInvites
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MButtonSize

private const val BUBBLE_SHARE = 0.82f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

@Composable
internal fun ChatDay(label: String, modifier: Modifier = Modifier) {
    val colors = millida
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            modifier = Modifier
                .pixelSurface(colors.surface2, MillidaCut.badge)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = MillidaType.tabLabel,
            color = colors.fgSubtle,
        )
    }
}

internal fun replyLabel(reply: ChatReplyPreview): String = when {
    reply.deleted -> "Сообщение удалено"
    reply.kind == "image" || reply.kind == "sticker" -> "Картинка"
    reply.kind == "voice" -> "Голосовое сообщение"
    CallLogs.parse(reply.text) != null -> CallLogs.parse(reply.text)?.let { CallLogs.title(it, reply.me) }.orEmpty()
    reply.text.isNotBlank() -> reply.text
    else -> "Сообщение"
}

@Composable
internal fun MessageBubble(
    line: ChatLine,
    author: String?,
    peerName: String,
    grouped: Boolean,
    read: Boolean,
    onMenu: () -> Unit,
    onReply: () -> Unit,
    onOpenImage: (String) -> Unit,
    onRetry: () -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
    onCallBack: (() -> Unit)? = null,
    flash: Boolean = false,
    onQuote: ((String) -> Unit)? = null,
    onJoinServer: ((ServerInvite) -> Unit)? = null,
    onReact: ((String) -> Unit)? = null,
    room: Boolean = false,
) {
    val colors = millida
    val feedback = LocalUiFeedback.current
    val message = line.message
    val mine = message.me
    val actionable = message.id.isNotEmpty() && line.state == SendState.Sent && !message.deleted
    val callLog = if (message.deleted) null else CallLogs.parse(message.text)
    val invite = if (message.deleted || callLog != null) null else ServerInvites.parse(message.text)
    if (invite != null || (callLog != null && !room)) {
        StandaloneCard(line, invite, callLog, onCallBack, onJoinServer, onRetry, onDrop, modifier)
        return
    }
    val menu by rememberUpdatedState(onMenu)
    val reply by rememberUpdatedState(onReply)
    val press = if (actionable) {
        Modifier.pointerInput(message.id) {
            detectTapGestures(
                onLongPress = {
                    feedback.tap(UiSoundEvent.Click)
                    menu()
                },
                onDoubleTap = { reply() },
            )
        }
    } else {
        Modifier
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = if (grouped) 3.dp else 8.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (author != null) {
            Row(
                Modifier.padding(start = 2.dp, top = 2.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MAvatar(url = avatarFor(author), size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    author,
                    style = MillidaType.small.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.fgSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth(BUBBLE_SHARE),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            Column(
                Modifier
                    .alpha(if (line.state == SendState.Sending) 0.7f else 1f)
                    .pixelSurface(
                        fill = if (mine) colors.accentToday else colors.surface,
                        cut = MillidaCut.control,
                        frame = if (flash) PixelFrame(outline = colors.accent, outlineWidth = 2.dp) else PixelFrame.None,
                    )
                    .then(press)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                message.replyTo?.takeIf { !message.deleted }?.let { quote ->
                    ReplyQuote(
                        who = if (quote.me) "Ты" else peerName,
                        text = replyLabel(quote),
                        onClick = onQuote?.takeIf { quote.id.isNotEmpty() }?.let { jump -> { jump(quote.id) } },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                val attachment = message.attachment
                if (attachment != null && !message.deleted) {
                    Attachment(attachment, actionable, onOpenImage, menu, mine)
                    if (message.text.isNotEmpty()) Spacer(Modifier.height(6.dp))
                }
                if (message.deleted) {
                    Text("Сообщение удалено", style = MillidaType.body, color = colors.fgFaint)
                } else if (callLog != null) {
                    CallLogCard(callLog, mine, onCallBack)
                } else if (message.text.isNotEmpty()) {
                    EltText(message.text, style = MillidaType.body.copy(fontWeight = FontWeight.Normal), color = colors.fg)
                }
                Spacer(Modifier.height(3.dp))
                Row(
                    Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (message.editedAt != null && !message.deleted) {
                        Text("изм.", style = MillidaType.small, color = colors.fgFaint)
                    }
                    Text(clockText(message.ts), style = MillidaType.small, color = colors.fgFaint)
                    if (mine) DeliveryMark(line.state, read)
                }
            }
            if (message.reactions.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    message.reactions.take(6).forEach { reaction ->
                        val art = Eltaller.reactionArt(reaction.emoji)
                        val toggle = onReact?.takeIf { actionable }
                        MBadge(
                            if (art != null) "${reaction.count}" else "${reaction.emoji} ${reaction.count}",
                            color = if (reaction.mine) colors.accentText else colors.fgMuted,
                            background = if (reaction.mine) colors.accentSoft else colors.surface2,
                            modifier = if (toggle != null) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                ) {
                                    feedback.tap(UiSoundEvent.Click)
                                    toggle(reaction.emoji)
                                }
                            } else {
                                Modifier
                            },
                            leading = if (art != null) {
                                { EltImage(art, Modifier.size(16.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            if (line.state == SendState.Failed) FailedRow(line.error, onRetry, onDrop)
        }
    }
}

@Composable
private fun FailedRow(error: String?, onRetry: () -> Unit, onDrop: () -> Unit) {
    val colors = millida
    Row(verticalAlignment = Alignment.CenterVertically) {
        MIcon(MIcons.alert, size = 14.dp, color = colors.danger)
        Spacer(Modifier.width(6.dp))
        Text(
            error ?: "Не отправлено",
            modifier = Modifier.weight(1f, fill = false),
            style = MillidaType.small,
            color = colors.danger,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Row {
        MTextButton("Повторить", onClick = onRetry, icon = MIcons.refresh)
        MTextButton("Удалить", onClick = onDrop, color = colors.fgSubtle)
    }
}

/**
 * An invite and a call result stand in the feed on their own, without the
 * bubble around them, as on the desktop (Chat.tsx renders them before the
 * bubble branch): they are cards with one action, not words someone wrote.
 */
@Composable
private fun StandaloneCard(
    line: ChatLine,
    invite: ServerInvite?,
    callLog: CallLogEntry?,
    onCallBack: (() -> Unit)?,
    onJoinServer: ((ServerInvite) -> Unit)?,
    onRetry: () -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier,
) {
    val colors = millida
    val mine = line.message.me
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Column(
            Modifier
                .fillMaxWidth(BUBBLE_SHARE)
                .alpha(if (line.state == SendState.Sending) 0.7f else 1f),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            if (invite != null) {
                ServerInviteCard(invite, onJoinServer, fill = if (mine) colors.accentSoft else colors.surface2)
            } else if (callLog != null) {
                CallLogCard(callLog, mine, onCallBack, fill = colors.surface2)
            }
            if (line.state == SendState.Failed) FailedRow(line.error, onRetry, onDrop)
        }
    }
}

/** The desktop's «Зайти» card for a «Позвать играть» message (src/lib/invite.ts). */
@Composable
private fun ServerInviteCard(invite: ServerInvite, onJoin: ((ServerInvite) -> Unit)?, fill: Color) {
    val colors = millida
    Row(
        Modifier
            .pixelSurface(fill, MillidaCut.control)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(MIcons.server, size = 16.dp, color = colors.accentText)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                "Приглашение на сервер",
                style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
                color = colors.fg,
                maxLines = 1,
            )
            Text(
                listOfNotNull(invite.name, invite.addr, invite.version).distinct().joinToString(" · "),
                style = MillidaType.small,
                color = colors.fgSubtle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onJoin != null) {
            Spacer(Modifier.width(10.dp))
            MSecondaryButton("Зайти", onClick = { onJoin(invite) }, size = MButtonSize.Small)
        }
    }
}

@Composable
private fun ReplyQuote(who: String, text: String, onClick: (() -> Unit)? = null) {
    val colors = millida
    Row(
        Modifier
            .height(IntrinsicSize.Min)
            .clip(MillidaShape.mini)
            .background(colors.inset)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(colors.accent),
        )
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(
                who,
                style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
                color = colors.accentText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            EltText(
                text,
                style = MillidaType.small,
                color = colors.fgSubtle,
                quote = true,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Attachment(
    attachment: ChatAttachment,
    actionable: Boolean,
    onOpenImage: (String) -> Unit,
    onMenu: () -> Unit,
    mine: Boolean,
) {
    val colors = millida
    if (attachment.kind == "voice") {
        VoiceNote(attachment, mine)
        return
    }
    val picture = attachment.kind == "image" || attachment.kind == "sticker"
    if (picture && isOwnMediaUrl(attachment.url)) {
        val open by rememberUpdatedState(onOpenImage)
        val menu by rememberUpdatedState(onMenu)
        MRemoteImage(
            url = attachment.url,
            icon = MIcons.image,
            modifier = Modifier.pointerInput(attachment.url, actionable) {
                detectTapGestures(
                    onTap = { open(attachment.url) },
                    onLongPress = { if (actionable) menu() },
                )
            },
            size = 200.dp,
            shape = MillidaShape.control,
            background = colors.inset,
        )
        return
    }
    val (icon, label) = when (attachment.kind) {
        "image", "sticker" -> MIcons.image to "Картинка"
        else -> MIcons.link to attachment.name.ifEmpty { "Вложение" }
    }
    Row(
        Modifier
            .pixelSurface(colors.inset, MillidaCut.control)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(icon, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MillidaType.small, color = colors.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Pixel ticks of the desktop src/components/Ticks.tsx: a 6-row grid, one cell is 1.5 dp. */
private val TICK_ONE = listOf(".......c", "......cc", "c....cc.", "cc..cc..", ".cccc...", "..cc....")
private val TICK_TWO = listOf(".......c...c", "......cc..cc", "c....cc..cc.", "cc..cc..cc..", ".cccc..cc...", "..cc..cc....")
private const val TICK_CELL_DP = 1.5f
private const val TICK_PENDING_ALPHA = 0.5f

/** One grey tick while it flies, two grey once the server took it, two blue once the other side read it. */
@Composable
private fun DeliveryMark(state: SendState, read: Boolean) {
    val colors = millida
    if (state == SendState.Failed) return
    val sending = state == SendState.Sending
    val art = if (sending) TICK_ONE else TICK_TWO
    val tone = when {
        sending -> colors.fgFaint.copy(alpha = colors.fgFaint.alpha * TICK_PENDING_ALPHA)
        read -> colors.tickRead
        else -> colors.fgFaint
    }
    Canvas(Modifier.size(width = (art[0].length * TICK_CELL_DP).dp, height = (art.size * TICK_CELL_DP).dp)) {
        val cell = size.height / art.size
        art.forEachIndexed { y, row ->
            var x = 0
            while (x < row.length) {
                if (row[x] != 'c') {
                    x++
                    continue
                }
                var end = x
                while (end + 1 < row.length && row[end + 1] == 'c') end++
                drawRect(tone, Offset(x * cell, y * cell), Size((end - x + 1) * cell, cell))
                x = end + 1
            }
        }
    }
}

@Composable
internal fun ChatSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listOf(0.55f to false, 0.4f to true, 0.7f to false, 0.35f to true, 0.5f to false).forEach { (share, mine) ->
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                MSkeleton(height = 44.dp, modifier = Modifier.fillMaxWidth(share))
            }
        }
    }
}

internal data class ComposerQuote(val icon: MillidaIcon, val title: String, val text: String)

@Composable
internal fun ChatComposer(
    draft: String,
    onDraft: (String) -> Unit,
    quote: ComposerQuote?,
    onCancelQuote: () -> Unit,
    editing: Boolean,
    uploading: Boolean,
    onPickImage: () -> Unit,
    onSend: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    recording: VoiceRecordingUi? = null,
    onRecord: (() -> Unit)? = null,
    onCancelRecord: () -> Unit = {},
    onSendRecord: () -> Unit = {},
    uploadingText: String = "Отправляем картинку",
) {
    val colors = millida
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        EltPickerSheet(
            onPick = { code ->
                picking = false
                onDraft(draft + code)
            },
            onDismiss = { picking = false },
        )
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
            .drawBehind {
                val line = max(1f, 2.dp.roundToPx().toFloat())
                drawRect(colors.plateBottom, Offset.Zero, Size(size.width, line))
            },
    ) {
        if (uploading) {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MPixelLoader()
                Spacer(Modifier.width(10.dp))
                Text(uploadingText, style = MillidaType.small, color = colors.fgSubtle)
            }
        }
        if (quote != null) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MIcon(quote.icon, size = 16.dp, color = colors.accentText)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        quote.title,
                        style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
                        color = colors.accentText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    EltText(
                        quote.text,
                        style = MillidaType.small,
                        color = colors.fgSubtle,
                        quote = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MIconButton(MIcons.close, onClick = onCancelQuote, label = "Отменить")
            }
        }
        if (recording != null) {
            RecordingStrip(recording, onCancelRecord, onSendRecord)
            return@Column
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            MIconButton(MIcons.smile, onClick = { picking = true }, label = "Смайлы")
            Spacer(Modifier.width(8.dp))
            MIconButton(
                MIcons.image,
                onClick = onPickImage,
                enabled = !uploading && !editing,
                label = "Картинка",
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                MField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = if (editing) "Новый текст…" else "Сообщение…",
                    singleLine = false,
                    imeAction = ImeAction.Default,
                    focusRequester = focusRequester,
                    maxLines = 5,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (onRecord != null && draft.isBlank() && !editing) {
                MIconButton(
                    MIcons.mic,
                    onClick = onRecord,
                    tint = colors.onAccent,
                    background = colors.accent,
                    enabled = !uploading,
                    label = "Записать голосовое",
                )
            } else {
                MIconButton(
                    if (editing) MIcons.check else MIcons.send,
                    onClick = onSend,
                    tint = colors.onAccent,
                    background = colors.accent,
                    enabled = draft.isNotBlank(),
                    label = if (editing) "Сохранить" else "Отправить",
                )
            }
        }
    }
}

/** What the composer shows while a voice note is being recorded. */
internal data class VoiceRecordingUi(val ms: Long, val bars: List<Float>)

/** Bars in the live strip; the newest sits next to the send button, so the strip runs towards it like a tape. */
internal const val REC_BARS = 28

private const val REC_WARN_MS = 10_000L

@Composable
private fun RecordingStrip(recording: VoiceRecordingUi, onCancel: () -> Unit, onSend: () -> Unit) {
    val colors = millida
    val left = (VoiceNotes.MAX_MS - recording.ms).coerceAtLeast(0)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIconButton(MIcons.trash, onClick = onCancel, tint = colors.danger, label = "Отменить запись")
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(8.dp)
                .clip(MillidaShape.badge)
                .background(colors.danger),
        )
        Spacer(Modifier.width(8.dp))
        Text(VoiceNotes.formatTime(recording.ms), style = MillidaType.small, color = colors.fg)
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shift = REC_BARS - recording.bars.size
            repeat(REC_BARS) { index ->
                val level = recording.bars.getOrNull(index - shift) ?: 0f
                Box(
                    Modifier
                        .weight(1f)
                        .height((3 + level * 19).dp)
                        .clip(MillidaShape.badge)
                        .background(colors.accentText),
                )
            }
        }
        if (left < REC_WARN_MS) {
            Spacer(Modifier.width(8.dp))
            Text(VoiceNotes.formatTime(left), style = MillidaType.small, color = colors.danger)
        }
        Spacer(Modifier.width(10.dp))
        MIconButton(MIcons.send, onClick = onSend, tint = colors.onAccent, background = colors.accent, label = "Отправить")
    }
}

@Composable
internal fun MessageMenu(
    message: ChatMessage,
    onReply: () -> Unit,
    onCopy: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    onReact: ((String) -> Unit)? = null,
    onCopyImage: (() -> Unit)? = null,
    onSaveImage: (() -> Unit)? = null,
) {
    val colors = millida
    MBottomSheet(onDismiss = onDismiss) {
        if (onReact != null) ReactionRow(message, onReact)
        val callLog = CallLogs.parse(message.text)
        val preview = when {
            callLog != null -> CallLogs.title(callLog, message.me)
            message.text.isNotBlank() -> message.text
            message.attachment?.kind == "voice" -> "Голосовое сообщение"
            message.attachment != null -> "Картинка"
            else -> ""
        }
        if (preview.isNotEmpty()) {
            EltText(
                preview,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                style = MillidaType.small,
                color = colors.fgSubtle,
                quote = true,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MListRow("Ответить", icon = MIcons.reply, onClick = onReply, plate = false, trailing = {})
        if (onCopy != null) MListRow("Копировать текст", icon = MIcons.copy, onClick = onCopy, plate = false, trailing = {})
        if (onCopyImage != null) MListRow("Копировать картинку", icon = MIcons.copy, onClick = onCopyImage, plate = false, trailing = {})
        if (onSaveImage != null) MListRow("Сохранить картинку", icon = MIcons.download, onClick = onSaveImage, plate = false, trailing = {})
        if (onEdit != null) MListRow("Изменить", icon = MIcons.edit, onClick = onEdit, plate = false, trailing = {})
        if (onDelete != null) MListRow("Удалить", icon = MIcons.trash, onClick = onDelete, plate = false, trailing = {})
    }
}

/** The eight reactions on top of the message menu; the one already put is lit, tapping it takes it back. */
@Composable
private fun ReactionRow(message: ChatMessage, onReact: (String) -> Unit) {
    val colors = millida
    val feedback = LocalUiFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Eltaller.reactions.forEach { emoji ->
            val on = message.reactions.any { it.emoji == emoji && it.mine }
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .pixelSurface(if (on) colors.accentSoft else Color.Transparent, MillidaCut.control)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) {
                        feedback.tap(UiSoundEvent.Click)
                        onReact(emoji)
                    },
                contentAlignment = Alignment.Center,
            ) {
                val art = Eltaller.reactionArt(emoji)
                if (art != null) EltImage(art, Modifier.size(26.dp))
            }
        }
    }
}

/** The "печатает…" line at the end of the feed, quiet italic like the desktop `.chat-typing`. */
@Composable
internal fun ChatTypingLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 2.dp, top = 6.dp),
        style = MillidaType.small.copy(fontStyle = FontStyle.Italic),
        color = millida.fgSubtle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun ImageViewer(url: String, onDismiss: () -> Unit) {
    val close by rememberUpdatedState(onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .background(MillidaPixel.sceneScrim)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { close() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_ZOOM
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        offset = if (scale <= 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = "Картинка",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
            ) {
                MIconButton(MIcons.close, onClick = onDismiss, label = "Закрыть")
            }
        }
    }
}

@Composable
internal fun OlderRow(loading: Boolean, error: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            MPixelLoader()
        } else if (error != null) {
            Text(
                error,
                modifier = Modifier.weight(1f, fill = false),
                style = MillidaType.small,
                color = colors.fgSubtle,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            MTextButton("Повторить", onClick = onRetry, icon = MIcons.refresh)
        }
    }
}

