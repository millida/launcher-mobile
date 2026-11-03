package net.millida.launcher.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.millida.launcher.GameLaunchModel
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.ui.build.LOG_STYLE
import net.millida.launcher.ui.build.logTone

private const val LOG_POLL_MS = 1_000L

/**
 * The game's log while the game runs: the tail of the session file, read again
 * every second. The list stays at the last line until the player scrolls up,
 * and follows again once they scroll back down.
 */
@Composable
internal fun GameLogPanel(model: GameLaunchModel, onClose: () -> Unit, onOverlay: (() -> Unit)? = null) {
    val colors = millida
    val clipboard = LocalClipboardManager.current
    val log by produceState(initialValue = "", model) {
        while (true) {
            value = withContext(Dispatchers.IO) { model.logTail() }
            delay(LOG_POLL_MS)
        }
    }
    val lines = remember(log) { log.lines() }
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { moving ->
            if (!moving) follow = !listState.canScrollForward
        }
    }
    LaunchedEffect(lines) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(MillidaPixel.modalScrim) }
            .pointerInput(Unit) { detectTapGestures { onClose() } }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { } }
                .pixelSurface(colors.raised, MillidaCut.plate, colors.plateFrame())
                .padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Лог игры"), modifier = Modifier.weight(1f), style = MillidaType.dialogTitle, color = colors.fg)
                MTextButton(
                    tr("Скопировать"),
                    onClick = {
                        clipboard.setText(AnnotatedString(log))
                        model.showNotice(tr("Лог скопирован"))
                    },
                    icon = MIcons.copy,
                )
                MTextButton(tr("Поделиться"), onClick = { model.shareLog() }, icon = MIcons.share)
                if (onOverlay != null) {
                    MTextButton(tr("Поверх игры"), onClick = onOverlay, icon = MIcons.eye)
                }
                MIconButton(MIcons.close, onClick = onClose, label = tr("Закрыть"))

            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 6.dp, end = 8.dp)
                    .pixelSurface(colors.inset, MillidaCut.control, colors.wellFrame())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                state = listState,
            ) {
                items(lines.size, key = { it }, contentType = { "log" }) { index ->
                    Text(lines[index].ifEmpty { " " }, style = LOG_STYLE, color = logTone(lines[index], colors))
                }
            }
        }
    }
}

private const val OVERLAY_LINES = 8
private const val OVERLAY_ALPHA = 0.72f

/**
 * The last lines of the log over the running game. Only the close button takes
 * touches: the rest of the strip lets fingers through to the game below.
 */
@Composable
internal fun GameLogOverlay(model: GameLaunchModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    val log by produceState(initialValue = "", model) {
        while (true) {
            value = withContext(Dispatchers.IO) { model.logTail() }
            delay(LOG_POLL_MS)
        }
    }
    val lines = remember(log) { log.lines().filter { it.isNotBlank() }.takeLast(OVERLAY_LINES) }
    Row(
        modifier
            .widthIn(max = 560.dp)
            .pixelSurface(colors.inset.copy(alpha = OVERLAY_ALPHA), MillidaCut.control)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            lines.forEach { line ->
                Text(line, style = LOG_STYLE, color = logTone(line, colors), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        MIconButton(MIcons.close, onClick = onClose, label = tr("Убрать лог с экрана"))
    }
}
