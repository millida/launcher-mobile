package net.millida.launcher.ui.social

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.call.CallCenter
import net.millida.launcher.call.CallUiState
import net.millida.launcher.call.RoomInvite
import net.millida.launcher.core.call.CallMode
import net.millida.launcher.core.call.CallRules
import net.millida.launcher.core.call.CallStatus
import net.millida.launcher.core.friends.DialogKind
import net.millida.launcher.ui.MAvatar
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MDangerButton
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame

/**
 * Calls inside the launcher: the incoming card, the bar of a call in progress
 * and the invitation into a group conversation. Receiving runs while the
 * launcher is on screen; behind the game the friends bridge holds it.
 */
@Composable
internal fun CallLayer(state: UiState, model: LauncherViewModel) {
    val context = LocalContext.current
    val center = remember { CallCenter.get(context) }
    val call by center.state.collectAsStateWithLifecycle()
    val invite by center.roomInvite.collectAsStateWithLifecycle()

    LaunchedEffect(state.friends, state.dialogs) {
        val fromDialogs = state.dialogs.filter { it.kind == DialogKind.DM && it.title.isNotEmpty() }.associate { it.id to it.title }
        val fromFriends = state.friends.associate { it.userId to it.shownName() }
        center.nicknames = fromDialogs + fromFriends
    }
    LaunchedEffect(center) {
        center.notices.collect { model.notice(it.text, isError = it.error) }
    }
    if (state.millidaSignedIn) {
        LifecycleResumeEffect(center) {
            val release = center.retainPump()
            onPauseOrDispose { release() }
        }
    }

    val accept = rememberMicGate(model) { center.accept() }

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        when (call.status) {
            CallStatus.IDLE -> invite?.let { room ->
                RoomInviteCard(
                    invite = room,
                    onJoin = rememberMicGate(model) { center.joinRoom(room.roomId, room.title) },
                    onDismiss = center::dismissRoomInvite,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            CallStatus.INCOMING -> IncomingCallCard(
                call = call,
                onAccept = accept,
                onDecline = center::decline,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            else -> CallBar(call = call, center = center, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

/** Runs [action] once the microphone is allowed, asking for it first when needed. */
@Composable
internal fun rememberMicGate(model: LauncherViewModel, action: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val center = remember { CallCenter.get(context) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = pending
        pending = null
        if (granted) {
            next?.invoke()
        } else {
            model.notice("Без микрофона разговор не начать — разреши его в настройках телефона", isError = true)
        }
    }
    return {
        if (center.micGranted()) {
            action()
        } else {
            pending = action
            ask.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
private fun IncomingCallCard(call: CallUiState, onAccept: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    Column(
        modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .pixelSurface(colors.raised, MillidaCut.plate, colors.plateFrame())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MAvatar(url = avatarFor(call.peerNick), size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(call.title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Входящий звонок", style = MillidaType.small, color = colors.fgSubtle)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MPrimaryButton("Ответить", onClick = onAccept, modifier = Modifier.weight(1f), icon = MIcons.phone)
            MDangerButton("Отклонить", onClick = onDecline, modifier = Modifier.weight(1f), icon = MIcons.phoneHang, size = MButtonSize.Medium)
        }
    }
}

@Composable
private fun RoomInviteCard(invite: RoomInvite, onJoin: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    Row(
        modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .pixelSurface(colors.raised, MillidaCut.plate, colors.plateFrame())
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoomAvatar(size = 40.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "${invite.nick} зовёт в «${invite.title}»",
            modifier = Modifier.weight(1f),
            style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
            color = colors.fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        MTextButton("Зайти", onClick = onJoin, icon = MIcons.headset)
        MIconButton(MIcons.close, onClick = onDismiss, label = "Скрыть")
    }
}

@Composable
private fun CallBar(call: CallUiState, center: CallCenter, modifier: Modifier = Modifier) {
    val colors = millida
    val line = callStateLine(call)
    Column(
        modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .pixelSurface(colors.raised, MillidaCut.plate, colors.plateFrame())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (call.mode == CallMode.ROOM) RoomAvatar(size = 36.dp) else MAvatar(url = avatarFor(call.peerNick), size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(call.title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, style = MillidaType.small, color = colors.fgSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MIconButton(
                if (call.muted) MIcons.micOff else MIcons.mic,
                onClick = center::toggleMute,
                tint = if (call.muted) colors.danger else colors.fgSubtle,
                label = if (call.muted) "Включить микрофон" else "Выключить микрофон",
            )
            MIconButton(
                if (call.deafened) MIcons.headsetOff else MIcons.headset,
                onClick = center::toggleDeafen,
                tint = if (call.deafened) colors.danger else colors.fgSubtle,
                label = if (call.deafened) "Включить звук" else "Заглушить звук",
            )
            MIconButton(
                if (call.speaker) MIcons.volume else MIcons.mute,
                onClick = center::toggleSpeaker,
                tint = if (call.speaker) colors.accentText else colors.fgSubtle,
                label = if (call.speaker) "Выключить громкую связь" else "Громкая связь",
            )
            if (call.mode == CallMode.ROOM) {
                MIconButton(MIcons.bell, onClick = { center.ringRoom(call.roomId) }, label = "Позвать остальных")
            }
            Spacer(Modifier.weight(1f))
            MDangerButton(
                if (call.mode == CallMode.ROOM) "Выйти" else "Завершить",
                onClick = center::hangUp,
                icon = MIcons.phoneHang,
                size = MButtonSize.Medium,
            )
        }
    }
}

@Composable
private fun callStateLine(call: CallUiState): String {
    val now by produceState(System.currentTimeMillis(), call.status, call.answeredAt) {
        while (call.status == CallStatus.ACTIVE) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val parts = buildList {
        if (call.status == CallStatus.ACTIVE && call.answeredAt > 0) add(CallRules.formatTime(now - call.answeredAt))
        if (call.mode == CallMode.ROOM && call.status == CallStatus.ACTIVE) add("${call.parts.size + 1} в разговоре")
        add(
            when (call.status) {
                CallStatus.OUTGOING -> "Звоним…"
                CallStatus.CONNECTING -> "Соединяем…"
                else -> call.quality.text
            },
        )
        if (call.peerMuted) add("без микрофона")
    }
    return parts.joinToString(" · ")
}
