package net.millida.launcher.ui.social

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.millida.launcher.core.call.CallLogEntry
import net.millida.launcher.core.call.CallLogs
import net.millida.launcher.core.call.CallOutcome
import net.millida.launcher.core.friends.ChatAttachment
import net.millida.launcher.core.friends.VoiceNotes
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface

/** One voice note plays at a time in the whole app: two at once is never what was meant. */
private object VoicePlayback {
    private var current: MediaPlayer? = null

    fun claim(player: MediaPlayer) {
        val previous = current
        if (previous != null && previous !== player) runCatching { if (previous.isPlaying) previous.pause() }
        current = player
    }

    fun release(player: MediaPlayer) {
        if (current === player) current = null
    }
}

private const val TICK_MS = 200L

/**
 * A voice note as the desktop draws it (VoiceMessage.tsx): play, a wave that
 * fills as it plays and can be tapped to seek, and the time left. The file is
 * taken only from our own storage, like chat pictures.
 */
@Composable
internal fun VoiceNote(attachment: ChatAttachment, mine: Boolean) {
    val colors = millida
    var player by remember(attachment.url) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(attachment.url) { mutableStateOf(false) }
    var preparing by remember(attachment.url) { mutableStateOf(false) }
    var at by remember(attachment.url) { mutableLongStateOf(0L) }
    var failed by remember(attachment.url) { mutableStateOf<String?>(null) }
    var duration by remember(attachment.url) { mutableLongStateOf(attachment.durationMs ?: 0L) }
    val bars = remember(attachment.peaks) { VoiceNotes.downsample(attachment.peaks) }

    DisposableEffect(attachment.url) {
        onDispose {
            player?.let { gone ->
                VoicePlayback.release(gone)
                runCatching { gone.release() }
            }
            player = null
        }
    }
    LaunchedEffect(playing, player) {
        val active = player ?: return@LaunchedEffect
        while (playing) {
            at = runCatching { active.currentPosition.toLong() }.getOrDefault(at)
            if (!active.isPlaying) playing = false
            delay(TICK_MS)
        }
    }

    fun toggle() {
        if (!isOwnMediaUrl(attachment.url)) {
            failed = "Файл не из хранилища Millida"
            return
        }
        val ready = player
        if (ready != null && !preparing) {
            if (playing) {
                ready.pause()
                playing = false
            } else {
                VoicePlayback.claim(ready)
                ready.start()
                playing = true
            }
            return
        }
        if (preparing) return
        failed = null
        preparing = true
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            created.setDataSource(attachment.url)
            created.setOnPreparedListener { prepared ->
                preparing = false
                if (prepared.duration > 0) duration = prepared.duration.toLong()
                VoicePlayback.claim(prepared)
                prepared.start()
                playing = true
            }
            created.setOnCompletionListener {
                playing = false
                at = 0L
            }
            created.setOnErrorListener { _, what, extra ->
                Log.w("millida-voice", "голосовое не играет: $what/$extra")
                preparing = false
                playing = false
                failed = if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) "плеер упал" else "не открылся"
                true
            }
            player = created
            created.prepareAsync()
        } catch (broken: Exception) {
            preparing = false
            failed = "не открылся"
            runCatching { created.release() }
            Log.w("millida-voice", "голосовое не открылось: ${broken.message}")
        }
    }

    val total = duration
    val done = if (total > 0) (at.toFloat() / total).coerceIn(0f, 1f) else 0f
    val left = if (total > 0) (total - at).coerceAtLeast(0) else 0L
    Row(verticalAlignment = Alignment.CenterVertically) {
        MIconButton(
            if (playing) MIcons.pause else MIcons.play,
            onClick = ::toggle,
            tint = if (mine) colors.onAccent else colors.accentText,
            background = if (mine) colors.accent else colors.surface2,
            label = if (playing) "Пауза" else "Слушать",
        )
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier
                .heightIn(min = 28.dp)
                .pointerInput(attachment.url, total) {
                    detectTapGestures { tap ->
                        val ready = player
                        if (ready == null || preparing || total <= 0 || size.width <= 0) return@detectTapGestures
                        val target = (total * (tap.x / size.width).coerceIn(0f, 1f)).toLong()
                        runCatching { ready.seekTo(target.toInt()) }
                        at = target
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bars.forEachIndexed { index, peak ->
                val played = index.toFloat() / bars.size < done
                Box(
                    Modifier
                        .width(3.dp)
                        .height((peak * 0.24f).coerceAtLeast(3f).dp)
                        .clip(MillidaShape.badge)
                        .background(if (played) colors.accentText else colors.fgFaint),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            failed ?: VoiceNotes.formatTime(if (playing || at > 0) left else total),
            style = MillidaType.small,
            color = if (failed != null) colors.danger else colors.fgSubtle,
            maxLines = 1,
        )
    }
}

/** The outcome of a call in the thread; tapping it calls back, as on the desktop. */
@Composable
internal fun CallLogCard(
    entry: CallLogEntry,
    mine: Boolean,
    onCallBack: (() -> Unit)?,
    fill: Color = millida.inset,
) {
    val colors = millida
    val missed = entry.outcome != CallOutcome.DONE
    val press = if (onCallBack != null) {
        Modifier.pointerInput(onCallBack) { detectTapGestures(onTap = { onCallBack() }) }
    } else {
        Modifier
    }
    Row(
        Modifier
            .pixelSurface(fill, MillidaCut.control)
            .then(press)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(if (missed) MIcons.phoneOff else MIcons.phone, size = 16.dp, color = if (missed) colors.danger else colors.accentText)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                CallLogs.title(entry, mine),
                style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
                color = colors.fg,
                maxLines = 1,
            )
            Text(CallLogs.subtitle(entry), style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
        }
    }
}
