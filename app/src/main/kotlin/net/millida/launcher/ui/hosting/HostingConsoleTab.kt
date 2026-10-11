package net.millida.launcher.ui.hosting

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import net.millida.launcher.ConsoleLink
import net.millida.launcher.ConsoleTone
import net.millida.launcher.HostingModel
import net.millida.launcher.HostingPanel
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MButton
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MButtonVariant
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaColors
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.screenPadding
import net.millida.launcher.ui.wellFrame

private val CONSOLE_HINTS = listOf("list", "say привет", "time set day", "weather clear")
private const val COMMAND_LIMIT = 256
private const val CHIPS_SHOWN = 8

@Composable
fun HostingConsoleTab(
    serverId: String,
    running: Boolean,
    panel: HostingPanel,
    hosting: HostingModel,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    var command by rememberSaveable(serverId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val lines = panel.console
    val canSend = running && command.isNotBlank()

    DisposableEffect(serverId, visible) {
        if (visible && serverId.isNotEmpty()) hosting.openConsole(serverId)
        onDispose { hosting.closeConsole() }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                follow = false
            } else if (interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel) {
                if (!listState.canScrollForward) follow = true
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canScroll ->
            if (!canScroll) follow = true
        }
    }
    LaunchedEffect(lines.lastOrNull()?.id, follow) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    val send: () -> Unit = {
        if (canSend) {
            follow = true
            hosting.sendCommand(serverId, command) { command = "" }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .padding(start = screenPadding, end = screenPadding, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConsoleLinkChip(panel.consoleLink, panel.consoleRetryAt)
            Spacer(Modifier.weight(1f))
            if (panel.consoleLink == ConsoleLink.Failed) {
                MTextButton(
                    "Подключить",
                    onClick = { hosting.openConsole(serverId) },
                    icon = MIcons.refresh,
                    color = colors.accentText,
                )
            }
            MTextButton("Очистить", onClick = { hosting.clearConsole(serverId) }, enabled = lines.isNotEmpty())
        }
        val linkError = panel.consoleError
        if (linkError != null && panel.consoleLink != ConsoleLink.Live) {
            Text(
                linkError,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                style = MillidaType.small,
                color = if (panel.consoleLink == ConsoleLink.Failed) colors.danger else colors.fgSubtle,
                maxLines = 2,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pixelSurface(colors.inset, MillidaCut.control, colors.wellFrame()),
        ) {
            if (lines.isEmpty()) {
                ConsolePlaceholder(running = running, link = panel.consoleLink)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                ) {
                    items(lines, key = { it.id }) { entry ->
                        Text(
                            entry.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp,
                            fontWeight = if (entry.tone == ConsoleTone.Command) FontWeight.Bold else FontWeight.Normal,
                            color = consoleColor(entry.tone, colors),
                        )
                    }
                }
            }
            if (!follow && lines.isNotEmpty()) {
                MButton(
                    text = "К концу",
                    onClick = { follow = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp),
                    variant = MButtonVariant.Secondary,
                    size = MButtonSize.Small,
                    icon = MIcons.arrowDown,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (panel.history + CONSOLE_HINTS).distinct().take(CHIPS_SHOWN).forEach { hint ->
                MChip(text = hint, selected = false, onClick = { command = hint }, enabled = running)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MField(
                value = command,
                onValueChange = { command = it.take(COMMAND_LIMIT) },
                placeholder = if (running) "Команда серверу" else "Сервер выключен",
                modifier = Modifier.weight(1f),
                imeAction = ImeAction.Send,
                onImeAction = send,
                leadingIcon = MIcons.chevronRight,
                enabled = running,
            )
            Spacer(Modifier.width(8.dp))
            MIconButton(
                MIcons.send,
                onClick = send,
                tint = if (canSend) colors.accentText else colors.fgFaint,
                enabled = canSend,
                label = "Отправить",
            )
        }
    }
}

@Composable
private fun ConsoleLinkChip(link: ConsoleLink, retryAt: Long) {
    val colors = millida
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(link, retryAt) {
        if (link != ConsoleLink.Waiting) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = ((retryAt - now + 999) / 1_000).coerceAtLeast(0)
    val label = when (link) {
        ConsoleLink.Idle -> "Консоль закрыта"
        ConsoleLink.Connecting -> "Подключаемся"
        ConsoleLink.Live -> "На связи"
        ConsoleLink.Waiting -> "Повтор через $seconds с"
        ConsoleLink.Failed -> "Нет связи"
    }
    val dot = when (link) {
        ConsoleLink.Live -> colors.accent
        ConsoleLink.Connecting, ConsoleLink.Waiting -> colors.warning
        ConsoleLink.Failed -> colors.danger
        ConsoleLink.Idle -> colors.fgFaint
    }
    val fill = when (link) {
        ConsoleLink.Live -> colors.accentSoft
        ConsoleLink.Connecting, ConsoleLink.Waiting -> colors.warningSoft
        ConsoleLink.Failed -> colors.dangerSoft
        ConsoleLink.Idle -> colors.surface2
    }
    val pulsing = link == ConsoleLink.Live || link == ConsoleLink.Connecting
    val blink: State<Float>? = if (pulsing && !LocalReducedMotion.current) {
        rememberInfiniteTransition(label = "console-link").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_200, easing = MillidaMotion.steps(2))),
            label = "console-link-blink",
        )
    } else {
        null
    }
    Row(
        Modifier
            .heightIn(min = 28.dp)
            .pixelSurface(fill, MillidaCut.badge)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = if ((blink?.value ?: 0f) >= 0.5f) 0.35f else 1f }
                .pixelSurface(dot, 2.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            style = MillidaType.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = colors.fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun ConsolePlaceholder(running: Boolean, link: ConsoleLink) {
    val colors = millida
    if (running && (link == ConsoleLink.Connecting || link == ConsoleLink.Live)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listOf(0.62f, 0.48f, 0.71f, 0.4f).forEach { share ->
                Box(
                    Modifier
                        .fillMaxWidth(share)
                        .height(10.dp)
                        .pixelSurface(colors.surface2, 2.dp),
                )
            }
        }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MIcon(if (running) MIcons.list else MIcons.power, size = 32.dp, color = colors.fgSubtle)
        Spacer(Modifier.height(10.dp))
        Text(
            if (running) "Пока тихо" else "Сервер выключен",
            style = MillidaType.rowTitle,
            color = colors.fgMuted,
        )
    }
}

private fun consoleColor(tone: ConsoleTone, colors: MillidaColors): Color = when (tone) {
    ConsoleTone.Command -> colors.accentText
    ConsoleTone.Error -> colors.danger
    ConsoleTone.Warning -> colors.warning
    ConsoleTone.Good -> colors.info
    ConsoleTone.Plain -> colors.fgMuted
}
