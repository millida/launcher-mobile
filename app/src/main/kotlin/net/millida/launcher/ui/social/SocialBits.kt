package net.millida.launcher.ui.social

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.call.CallCenter
import net.millida.launcher.core.friends.CallHandoff
import net.millida.launcher.core.friends.ChatTarget
import net.millida.launcher.core.friends.Dialog
import net.millida.launcher.core.friends.DialogKind
import net.millida.launcher.core.friends.Friend
import net.millida.launcher.ui.MAvatar
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MCounterBadge
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPresence
import net.millida.launcher.ui.MSectionTitle
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.headAvatarUrl
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.openLinkOrNotice
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame

internal fun avatarFor(nick: String, url: String? = null): String? =
    url?.takeIf { it.isNotBlank() } ?: nick.takeIf { it.isNotBlank() }?.let { headAvatarUrl(it) }

@Composable
internal fun SocialCaption(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    actionIcon: MillidaIcon? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MSectionTitle(text.uppercase(), Modifier.weight(1f))
        if (action != null && onAction != null) {
            MTextButton(action, onClick = onAction, color = millida.accentText, icon = actionIcon)
        }
    }
}

@Composable
internal fun RoomAvatar(size: Dp = 44.dp) {
    val colors = millida
    Box(
        Modifier
            .size(size)
            .pixelSurface(colors.surface2, MillidaCut.control, colors.plateFrame()),
        contentAlignment = Alignment.Center,
    ) {
        MIcon(MIcons.users, size = size * 0.5f)
    }
}

@Composable
internal fun PersonCard(
    nick: String,
    avatarUrl: String?,
    status: String,
    modifier: Modifier = Modifier,
    faded: Boolean = false,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = millida
    MCard(modifier = modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MAvatar(
                url = avatarFor(nick, avatarUrl),
                modifier = Modifier.alpha(if (faded) 0.55f else 1f),
                size = 44.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    nick.ifEmpty { "Игрок" },
                    style = MillidaType.rowTitle,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    status,
                    style = MillidaType.small,
                    color = colors.fgSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        MButtonRow(content = actions)
    }
}

/**
 * A row of the conversation list. The server does not send the last message's
 * text, so the line shows it only when it arrived during this session; before
 * that a friend's status or a group's members stand there.
 */
@Composable
internal fun DialogRow(
    dialog: Dialog,
    friend: Friend?,
    preview: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    plate: Boolean = true,
    active: Boolean = false,
) {
    val colors = millida
    val room = dialog.kind == DialogKind.ROOM
    val title = friend?.shownName() ?: dialog.title.ifEmpty { if (room) "Группа" else "Игрок" }
    val line = preview ?: when {
        room -> dialog.subtitle.ifEmpty { "Группа" }
        friend != null -> friend.statusLine()
        else -> dialog.subtitle
    }
    MListRow(
        title = title,
        modifier = if (active) modifier.pixelSurface(colors.accentSoft, MillidaCut.control) else modifier,
        subtitle = line.ifEmpty { null },
        onClick = onOpen,
        plate = plate,
        leading = {
            if (room) {
                RoomAvatar()
            } else {
                MAvatar(
                    url = avatarFor(title, friend?.avatarUrl ?: dialog.avatarUrl),
                    size = 44.dp,
                    presence = friend?.presence() ?: if (dialog.online) MPresence.Online else MPresence.Offline,
                )
            }
        },
        trailing = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val time = chatWhen(dialog.lastMessageAt)
                if (time.isNotEmpty()) {
                    Text(
                        time,
                        style = MillidaType.small.copy(fontWeight = if (dialog.unread > 0) FontWeight.Bold else FontWeight.Medium),
                        color = if (dialog.unread > 0) colors.accentText else colors.fgFaint,
                        maxLines = 1,
                    )
                }
                MCounterBadge(dialog.unread)
            }
        },
    )
}

internal fun inboxOf(dialogs: List<Dialog>): List<Dialog> {
    val knowsTime = dialogs.any { it.kind == DialogKind.DM && it.lastMessageAt != null }
    return dialogs
        .filter { it.kind == DialogKind.ROOM || !knowsTime || it.lastMessageAt != null || it.unread > 0 }
        .sortedWith(
            compareByDescending<Dialog> { it.lastMessageAt ?: 0L }
                .thenByDescending { it.online }
                .thenBy { it.title.lowercase() },
        )
}

internal fun LauncherViewModel.startCall(context: Context, target: ChatTarget) {
    val url = runCatching { CallHandoff.threadUrl(target) }.getOrElse { failure ->
        notice(failure.message ?: "Звонок не открылся — открой переписку на сайте", isError = true)
        return
    }
    openLinkOrNotice(context, url)
}

/** A personal call rings the friend; a group joins its always-open voice room, as on the desktop. */
internal fun LauncherViewModel.placeCall(context: Context, target: ChatTarget) {
    val center = CallCenter.get(context)
    val now = ui.value
    when (target) {
        is ChatTarget.Dm -> {
            val nick = now.friends.firstOrNull { it.userId == target.userId }?.shownName()
                ?: now.dialogs.firstOrNull { it.kind == DialogKind.DM && it.id == target.userId }?.title?.takeIf { it.isNotEmpty() }
                ?: "Друг"
            center.callFriend(target.userId, nick)
        }
        is ChatTarget.Room -> {
            val title = now.dialogs.firstOrNull { it.kind == DialogKind.ROOM && it.id == target.roomId }?.title?.takeIf { it.isNotEmpty() } ?: "Группа"
            center.joinRoom(target.roomId, title)
        }
    }
}

/**
 * Calls run in the launcher itself. Only when the voice engine cannot load on
 * this device does the call go to the site; the first such handover of a
 * session says so once, later ones go straight to the browser.
 */
@Composable
internal fun rememberCallHandoff(model: LauncherViewModel, hintShown: Boolean): (ChatTarget) -> Unit {
    val context = LocalContext.current
    var dialing by remember { mutableStateOf<ChatTarget?>(null) }
    val native = rememberMicGate(model) { dialing?.let { model.placeCall(context, it) } }
    var asking by remember { mutableStateOf<ChatTarget?>(null) }
    asking?.let { target ->
        MConfirmDialog(
            title = "Звонок на сайте",
            text = CallHandoff.HINT,
            confirm = "Открыть",
            onConfirm = {
                asking = null
                model.social.markCallHintShown()
                model.startCall(context, target)
            },
            onDismiss = { asking = null },
            danger = false,
        )
    }
    return { target ->
        when {
            CallCenter.get(context).available() -> {
                dialing = target
                native()
            }
            !hintShown -> asking = target
            else -> model.startCall(context, target)
        }
    }
}
