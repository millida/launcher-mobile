package net.millida.launcher.ui.hosting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import net.millida.launcher.Area
import net.millida.launcher.HostingModel
import net.millida.launcher.HostingPanel
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.hosting.HostingCrash
import net.millida.launcher.core.hosting.HostingEvent
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.core.hosting.HostingStats
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.ui.HOSTING_BUSY_STATES
import net.millida.launcher.ui.HostingAddressWell
import net.millida.launcher.ui.HostingFact
import net.millida.launcher.ui.HostingMeter
import net.millida.launcher.ui.HostingStatusPlate
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MDangerButton
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MHeroTone
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.hostingGb
import net.millida.launcher.ui.hostingMoment
import net.millida.launcher.ui.hostingName
import net.millida.launcher.ui.hostingPaidNote
import net.millida.launcher.ui.hostingRubles
import net.millida.launcher.ui.hostingStatusLabel
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.screenPadding
import net.millida.launcher.ui.wellFrame
import kotlin.math.floor
import kotlin.math.roundToInt

private const val EVENTS_SHOWN = 8

@Composable
fun HostingOverviewTab(
    state: UiState,
    model: LauncherViewModel,
    panel: HostingPanel,
    server: HostingServerState?,
    onAsk: (String) -> Unit,
    onCopy: () -> Unit,
    onPlay: () -> Unit,
    onConsole: () -> Unit,
    onPlan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hosting = model.hosting
    val serverId = state.hostingServerId
    val status = server?.status.orEmpty().uppercase()
    val running = status == "RUNNING"
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val error = state.errorOf(Area.HOSTED_SERVER)
    val stats = panel.stats ?: state.hostingStats
    val busy = panel.powering.containsKey(serverId)
    val canControl = state.hostingDetails?.can("control") ?: true
    val canKill = canControl && (running || status == "CRASHED" || status == "STOPPING")

    DisposableEffect(serverId, visible) {
        if (visible && serverId.isNotEmpty()) hosting.startLive(serverId)
        onDispose { hosting.stopLive() }
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = screenPadding, end = screenPadding, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (error != null) {
            item(key = "error") { MErrorCard(error, onRetry = { model.retry(Area.HOSTED_SERVER) }) }
        }
        if (server == null) {
            if (error == null) {
                item(key = "skeleton-scene") { MSkeleton(height = 240.dp) }
                items(3, key = { "skeleton-row-$it" }) { MSkeletonRow() }
            }
            return@LazyColumn
        }
        item(key = "scene") {
            HostingScene(
                server = server,
                paidUntil = state.hostingDetails?.subscription?.paidUntil.orEmpty(),
                busy = busy,
                canControl = canControl,
                onCopy = onCopy,
                onPlay = onPlay,
                onStart = { onAsk(HostingModel.START) },
                onRestart = { onAsk(HostingModel.RESTART) },
                onPlan = onPlan,
            )
        }
        if (status == "CRASHED") {
            item(key = "crash") {
                CrashCard(
                    crash = panel.crash,
                    fallback = state.hostingDetails?.crashReason.orEmpty(),
                    onConsole = onConsole,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (server.pendingRestart.isNotEmpty()) {
            item(key = "pending") {
                PendingCard(
                    running = running,
                    busy = busy || !canControl,
                    onRestart = { onAsk(HostingModel.RESTART) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        item(key = "load") { LoadCard(running = running, status = status, stats = stats, panel = panel) }
        if (state.hostingEvents.isNotEmpty()) {
            item(key = "events") { EventsCard(state.hostingEvents) }
        }
        if (canKill) {
            item(key = "shutdown") {
                ShutdownCard(
                    running = running,
                    busy = busy,
                    onStop = { onAsk(HostingModel.STOP) },
                    onKill = { onAsk(HostingModel.KILL) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun HostingScene(
    server: HostingServerState,
    paidUntil: String,
    busy: Boolean,
    canControl: Boolean,
    onCopy: () -> Unit,
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onRestart: () -> Unit,
    onPlan: () -> Unit,
) {
    val colors = millida
    val tone = MHeroTone.server(colors.accent)
    val shadow = with(LocalDensity.current) { 2.dp.toPx() }
    val status = server.status.uppercase()
    val running = status == "RUNNING"
    val between = status in HOSTING_BUSY_STATES
    val note = hostingPaidNote(server, paidUntil)
    val online = server.playersOnline
    val limit = server.maxPlayers.takeIf { it > 0 } ?: server.planMaxPlayers
    val identity: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .pixelSurface(tone.band, MillidaCut.control),
                contentAlignment = Alignment.Center,
            ) {
                MIcon(MIcons.server, size = 30.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                HostingStatusPlate(server.status, scene = tone.band)
                Spacer(Modifier.height(6.dp))
                Text(
                    hostingName(server),
                    style = MillidaType.tileTitle.copy(
                        fontSize = 20.sp,
                        lineHeight = 23.sp,
                        shadow = Shadow(MillidaPixel.textShadow, Offset(0f, shadow), 0f),
                    ),
                    color = MillidaPixel.white,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val noteLine: @Composable () -> Unit = {
        if (note != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.clock, size = 14.dp, color = MillidaPixel.whiteSoft)
                Spacer(Modifier.width(6.dp))
                Text(note, style = MillidaType.small, color = MillidaPixel.whiteSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val buttons: @Composable () -> Unit = {
        MButtonRow {
            if (running) {
                MPrimaryButton(
                    tr("Играть"),
                    onClick = onPlay,
                    enabled = server.address.isNotEmpty(),
                    icon = MIcons.play,
                    size = MButtonSize.Large,
                )
                MSecondaryButton(
                    tr("Перезапустить"),
                    onClick = onRestart,
                    enabled = !busy && canControl,
                    icon = MIcons.refresh,
                    size = MButtonSize.Large,
                )
            } else if (status == "SUSPENDED") {
                MPrimaryButton(tr("Продлить"), onClick = onPlan, icon = MIcons.wallet, size = MButtonSize.Large)
            } else {
                MPrimaryButton(
                    if (between) hostingStatusLabel(status) else tr("Запустить"),
                    onClick = onStart,
                    enabled = !busy && !between && canControl,
                    icon = MIcons.power,
                    size = MButtonSize.Large,
                    loading = busy || between,
                )
            }
        }
    }
    val players: @Composable (Modifier) -> Unit = { placement ->
        HostingFact(
            icon = MIcons.users,
            value = if (limit > 0) "$online / $limit" else "$online",
            caption = tr("в сети"),
            modifier = placement,
            fraction = if (limit > 0) online.toFloat() / limit else null,
            fill = tone.band,
            ink = MillidaPixel.white,
            sub = MillidaPixel.whiteSoft,
            meterEmpty = MillidaPixel.divider,
        )
    }
    val price: @Composable (Modifier) -> Unit = { placement ->
        HostingFact(
            icon = MIcons.star,
            value = if (server.free) "0 ₽" else hostingRubles(server.planPriceKopecks),
            caption = server.planName.ifEmpty { if (server.free) tr("бесплатный") else tr("в месяц") },
            modifier = placement,
            fill = tone.band,
            ink = MillidaPixel.white,
            sub = MillidaPixel.whiteSoft,
        )
    }
    val planLink: @Composable () -> Unit = {
        MTextButton(tr("Тариф и оплата"), onClick = onPlan, icon = MIcons.chevronRight, color = MillidaPixel.white)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .pixelSurface(
                fill = tone.plate,
                cut = MillidaCut.plate,
                frame = PixelFrame(
                    outline = tone.outline,
                    outlineWidth = 3.dp,
                    top = MillidaPixel.tileTop,
                    topWidth = 4.dp,
                    bottom = MillidaPixel.tileBottom,
                    bottomWidth = 5.dp,
                ),
                dots = true,
            )
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (LocalWideLayout.current) {
            Row {
                Column(Modifier.weight(WIDE_SCENE_SERVER), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    identity()
                    HostingAddressWell(server.address, onCopy, scene = tone.band)
                    noteLine()
                    buttons()
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    players(Modifier.fillMaxWidth())
                    price(Modifier.fillMaxWidth())
                    planLink()
                }
            }
        } else {
            identity()
            HostingAddressWell(server.address, onCopy, scene = tone.band)
            noteLine()
            buttons()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                players(Modifier.weight(1f))
                price(Modifier.weight(1f))
            }
            planLink()
        }
    }
}

/** The desktop scene's two halves, `.hsx-scene` 1.15fr : 1fr - the server left, its numbers right. */
private const val WIDE_SCENE_SERVER = 1.15f

@Composable
private fun CrashCard(
    crash: HostingCrash?,
    fallback: String,
    onConsole: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val title = crash?.hintTitle?.takeIf { it.isNotEmpty() } ?: tr("Сервер упал")
    val advice = crash?.hintAdvice?.takeIf { it.isNotEmpty() }
        ?: crash?.reason?.takeIf { it.isNotEmpty() }
        ?: fallback.ifEmpty { tr("Причина — в консоли") }
    val lines = crash?.lines.orEmpty().takeLast(6)
    MCard(modifier = modifier, frame = colors.plateFrame().copy(left = colors.danger, leftWidth = 4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            MIcon(MIcons.alert, size = 22.dp, color = colors.danger)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MillidaType.rowTitle, color = colors.fg)
                Spacer(Modifier.height(4.dp))
                Text(advice, style = MillidaType.body, color = colors.fgMuted)
            }
        }
        if (lines.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .pixelSurface(colors.inset, MillidaCut.control, colors.wellFrame())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                lines.forEach { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = colors.fgSubtle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        MSecondaryButton(tr("Открыть консоль"), onClick = onConsole, icon = MIcons.list)
    }
}

@Composable
private fun PendingCard(running: Boolean, busy: Boolean, onRestart: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    MCard(modifier = modifier, frame = colors.plateFrame().copy(left = colors.warning, leftWidth = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.refresh, size = 20.dp, color = colors.warning)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Нужен перезапуск"), style = MillidaType.rowTitle, color = colors.fg)
                Text(tr("Изменения вступят в силу после него"), style = MillidaType.small, color = colors.fgSubtle)
            }
        }
        if (running) {
            Spacer(Modifier.height(10.dp))
            MSecondaryButton(tr("Перезапустить"), onClick = onRestart, enabled = !busy, icon = MIcons.refresh, size = MButtonSize.Small)
        }
    }
}

@Composable
private fun LoadCard(running: Boolean, status: String, stats: HostingStats?, panel: HostingPanel) {
    val colors = millida
    MCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Нагрузка"),
                modifier = Modifier.weight(1f),
                style = MillidaType.sectionTitle.copy(fontSize = 16.sp),
                color = colors.fg,
            )
            if (running && stats != null && panel.statsStale) {
                MBadge(tr("не обновляется"), colors.warning, colors.warningSoft)
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            running && stats == null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) { MSkeleton(height = 52.dp) }
            }

            running && stats != null -> {
                val cpu = stats.cpuPercent.toFloat().coerceIn(0f, 100f)
                LoadRow(tr("Процессор"), "${cpu.roundToInt()}%", cpu / 100f, panel.cpuTrail)
                Spacer(Modifier.height(14.dp))
                LoadRow(
                    label = tr("Память"),
                    value = if (stats.memoryLimitMb > 0) {
                        tr("{0} / {1} ГБ", hostingGb(stats.memoryUsedMb), hostingGb(stats.memoryLimitMb))
                    } else {
                        tr("{0} ГБ", hostingGb(stats.memoryUsedMb))
                    },
                    fraction = if (stats.memoryLimitMb > 0) stats.memoryUsedMb.toFloat() / stats.memoryLimitMb else 0f,
                    trail = panel.memoryTrail,
                )
                DiskRow(stats)
            }

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MIcon(MIcons.power, size = 20.dp, color = colors.fgSubtle)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (status in HOSTING_BUSY_STATES) hostingStatusLabel(status) else tr("Сервер выключен"),
                        style = MillidaType.body,
                        color = colors.fgMuted,
                    )
                }
                if (stats != null) DiskRow(stats)
            }
        }
    }
}

@Composable
private fun DiskRow(stats: HostingStats) {
    if (stats.diskLimitMb <= 0) return
    Spacer(Modifier.height(14.dp))
    LoadRow(
        label = tr("Диск"),
        value = tr("{0} / {1} ГБ", hostingGb(stats.diskUsedMb), hostingGb(stats.diskLimitMb)),
        fraction = stats.diskUsedMb.toFloat() / stats.diskLimitMb,
        trail = null,
    )
}

@Composable
private fun LoadRow(label: String, value: String, fraction: Float, trail: List<Float>?) {
    val colors = millida
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MillidaType.small, color = colors.fgSubtle)
            Text(value, style = MillidaType.pixel, color = colors.fg, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        if (trail != null && trail.size > 1) {
            HostingBars(trail)
            Spacer(Modifier.height(6.dp))
        }
        HostingMeter(fraction)
    }
}

@Composable
private fun HostingBars(trail: List<Float>, slots: Int = 24) {
    val colors = millida
    val samples = trail.takeLast(slots)
    val pad = slots - samples.size
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(36.dp),
    ) {
        val gap = floor(2.dp.toPx())
        val rows = 9
        val cellHeight = floor((size.height - gap * (rows - 1)) / rows)
        val column = (size.width - gap * (slots - 1)) / slots
        for (slot in 0 until slots) {
            val x = floor(slot * (column + gap))
            val value = if (slot >= pad) samples[slot - pad] else null
            val lit = if (value == null) 0 else ((value / 100f) * rows).roundToInt().coerceIn(1, rows)
            val tone = when {
                value == null -> colors.inset
                value >= 90f -> colors.danger
                value >= 75f -> colors.warning
                else -> colors.accent
            }
            for (row in 0 until rows) {
                val y = size.height - (row + 1) * cellHeight - row * gap
                drawRect(
                    color = if (row < lit) tone else colors.inset,
                    topLeft = Offset(x, y),
                    size = Size(column, cellHeight),
                )
            }
        }
    }
}

@Composable
private fun EventsCard(events: List<HostingEvent>) {
    val colors = millida
    val shown = events.take(EVENTS_SHOWN)
    MCard {
        Text(tr("События"), style = MillidaType.sectionTitle.copy(fontSize = 16.sp), color = colors.fg)
        Spacer(Modifier.height(12.dp))
        shown.forEachIndexed { index, event ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                Column(
                    Modifier
                        .width(12.dp)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(8.dp)
                            .pixelSurface(if (index == 0) colors.accent else colors.borderStrong, 2.dp),
                    )
                    if (index < shown.lastIndex) {
                        Box(
                            Modifier
                                .padding(top = 3.dp)
                                .width(2.dp)
                                .weight(1f)
                                .background(colors.border),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .padding(bottom = if (index < shown.lastIndex) 12.dp else 0.dp),
                ) {
                    Text(
                        event.message.ifEmpty { tr("Событие сервера") },
                        style = MillidaType.body,
                        color = colors.fg,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOf(hostingMoment(event.createdAt), event.actorLabel).filter { it.isNotEmpty() }
                    if (meta.isNotEmpty()) {
                        Text(meta.joinToString(" · "), style = MillidaType.small, color = colors.fgFaint, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShutdownCard(
    running: Boolean,
    busy: Boolean,
    onStop: () -> Unit,
    onKill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    MCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.power, size = 20.dp, color = colors.fgSubtle)
            Spacer(Modifier.width(10.dp))
            Text(tr("Выключение"), style = MillidaType.sectionTitle.copy(fontSize = 16.sp), color = colors.fg)
        }
        Spacer(Modifier.height(12.dp))
        MButtonRow {
            if (running) {
                MDangerButton(tr("Остановить"), onClick = onStop, enabled = !busy, icon = MIcons.power, size = MButtonSize.Medium)
            }
            MTextButton(tr("Завершить процесс"), onClick = onKill, enabled = !busy, icon = MIcons.zap, color = colors.danger)
        }
    }
}
