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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import net.millida.launcher.core.hosting.HostingOnlinePlayer
import net.millida.launcher.core.hosting.HostingPlayer
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.core.hosting.HostingStats
import net.millida.launcher.ui.HOSTING_BUSY_STATES
import net.millida.launcher.ui.HostingAddressWell
import net.millida.launcher.ui.HostingFact
import net.millida.launcher.ui.HostingMeter
import net.millida.launcher.ui.HostingStatusPlate
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.MAvatar
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MDangerButton
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MHeroTone
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPresence
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSectionTitle
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.headAvatarUrl
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

private data class HostingPlayerAction(
    val id: String,
    val label: String,
    val icon: MillidaIcon,
    val done: (String) -> String,
)

private val PLAYER_ACTIONS: List<HostingPlayerAction> = listOf(
    HostingPlayerAction("op", "Выдать права оператора", MIcons.key) { "$it теперь оператор" },
    HostingPlayerAction("creative", "Творческий режим", MIcons.brush) { "$it в творческом режиме" },
    HostingPlayerAction("survival", "Режим выживания", MIcons.hammer) { "$it в режиме выживания" },
    HostingPlayerAction("heal", "Вылечить", MIcons.heart) { "$it вылечен" },
    HostingPlayerAction("spawn", "На спавн", MIcons.map) { "$it отправлен на спавн" },
    HostingPlayerAction("kick", "Кикнуть", MIcons.logout) { "$it кикнут" },
    HostingPlayerAction("ban", "Забанить", MIcons.ban) { "$it забанен" },
)

private const val BAN = "ban"
private const val ROSTER_SHOWN = 6
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
    var player by rememberSaveable(serverId) { mutableStateOf<String?>(null) }
    var banAsk by rememberSaveable(serverId) { mutableStateOf<String?>(null) }

    val wide = LocalWideLayout.current

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
        val load: @Composable () -> Unit = { LoadCard(running = running, status = status, stats = stats, panel = panel) }
        val players: @Composable () -> Unit = {
            PlayersCard(
                running = running,
                online = panel.online,
                onlineError = panel.onlineError,
                roster = state.hostingDetails?.players.orEmpty(),
                onPlayer = { player = it },
            )
        }
        if (wide) {
            item(key = "load-players") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { load() }
                    Box(Modifier.weight(1f)) { players() }
                }
            }
        } else {
            item(key = "load") { load() }
            item(key = "players") { players() }
        }
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

    player?.let { nick ->
        MBottomSheet(onDismiss = { player = null }, title = nick) {
            PLAYER_ACTIONS.forEachIndexed { index, action ->
                if (index > 0) MDivider()
                MListRow(
                    title = action.label,
                    icon = action.icon,
                    plate = false,
                    onClick = {
                        player = null
                        if (action.id == BAN) {
                            banAsk = nick
                        } else {
                            hosting.playerAction(serverId, nick, action.id, action.done(nick))
                        }
                    },
                )
            }
        }
    }

    banAsk?.let { nick ->
        MConfirmDialog(
            title = "Забанить $nick?",
            text = "Игрок не сможет зайти, пока бан не снимут.",
            confirm = "Забанить",
            onConfirm = {
                banAsk = null
                hosting.playerAction(serverId, nick, BAN, "$nick забанен")
            },
            onDismiss = { banAsk = null },
        )
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
                    "Играть",
                    onClick = onPlay,
                    enabled = server.address.isNotEmpty(),
                    icon = MIcons.play,
                    size = MButtonSize.Large,
                )
                MSecondaryButton(
                    "Перезапустить",
                    onClick = onRestart,
                    enabled = !busy && canControl,
                    icon = MIcons.refresh,
                    size = MButtonSize.Large,
                )
            } else if (status == "SUSPENDED") {
                MPrimaryButton("Продлить", onClick = onPlan, icon = MIcons.wallet, size = MButtonSize.Large)
            } else {
                MPrimaryButton(
                    if (between) hostingStatusLabel(status) else "Запустить",
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
            caption = "в сети",
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
            caption = server.planName.ifEmpty { if (server.free) "бесплатный" else "в месяц" },
            modifier = placement,
            fill = tone.band,
            ink = MillidaPixel.white,
            sub = MillidaPixel.whiteSoft,
        )
    }
    val planLink: @Composable () -> Unit = {
        MTextButton("Тариф и оплата", onClick = onPlan, icon = MIcons.chevronRight, color = MillidaPixel.white)
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
    val title = crash?.hintTitle?.takeIf { it.isNotEmpty() } ?: "Сервер упал"
    val advice = crash?.hintAdvice?.takeIf { it.isNotEmpty() }
        ?: crash?.reason?.takeIf { it.isNotEmpty() }
        ?: fallback.ifEmpty { "Причина — в консоли" }
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
        MSecondaryButton("Открыть консоль", onClick = onConsole, icon = MIcons.list)
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
                Text("Нужен перезапуск", style = MillidaType.rowTitle, color = colors.fg)
                Text("Изменения вступят в силу после него", style = MillidaType.small, color = colors.fgSubtle)
            }
        }
        if (running) {
            Spacer(Modifier.height(10.dp))
            MSecondaryButton("Перезапустить", onClick = onRestart, enabled = !busy, icon = MIcons.refresh, size = MButtonSize.Small)
        }
    }
}

@Composable
private fun LoadCard(running: Boolean, status: String, stats: HostingStats?, panel: HostingPanel) {
    val colors = millida
    MCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Нагрузка",
                modifier = Modifier.weight(1f),
                style = MillidaType.sectionTitle.copy(fontSize = 16.sp),
                color = colors.fg,
            )
            if (running && stats != null && panel.statsStale) {
                MBadge("не обновляется", colors.warning, colors.warningSoft)
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            running && stats == null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) { MSkeleton(height = 52.dp) }
            }

            running && stats != null -> {
                val cpu = stats.cpuPercent.toFloat().coerceIn(0f, 100f)
                LoadRow("Процессор", "${cpu.roundToInt()}%", cpu / 100f, panel.cpuTrail)
                Spacer(Modifier.height(14.dp))
                LoadRow(
                    label = "Память",
                    value = if (stats.memoryLimitMb > 0) {
                        "${hostingGb(stats.memoryUsedMb)} / ${hostingGb(stats.memoryLimitMb)} ГБ"
                    } else {
                        "${hostingGb(stats.memoryUsedMb)} ГБ"
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
                        if (status in HOSTING_BUSY_STATES) hostingStatusLabel(status) else "Сервер выключен",
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
        label = "Диск",
        value = "${hostingGb(stats.diskUsedMb)} / ${hostingGb(stats.diskLimitMb)} ГБ",
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
private fun PlayersCard(
    running: Boolean,
    online: List<HostingOnlinePlayer>?,
    onlineError: Boolean,
    roster: List<HostingPlayer>,
    onPlayer: (String) -> Unit,
) {
    val colors = millida
    MCard(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Сейчас в игре",
                modifier = Modifier.weight(1f),
                style = MillidaType.sectionTitle.copy(fontSize = 16.sp),
                color = colors.fg,
            )
            if (running && online != null) {
                Text("${online.size}", style = MillidaType.pixel, color = colors.accentText)
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            !running -> Text("Сервер выключен — в игре никого", style = MillidaType.small, color = colors.fgFaint)
            online == null && onlineError -> Row(
                Modifier.heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MIcon(MIcons.users, size = 20.dp, color = colors.fgMuted)
                Spacer(Modifier.width(10.dp))
                Text("Список игроков недоступен", style = MillidaType.body, color = colors.fgMuted)
            }

            online == null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(2) { MSkeleton(height = 44.dp) }
            }

            online.isEmpty() -> Row(
                Modifier.heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MIcon(MIcons.users, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Text("Никого нет в игре", style = MillidaType.body, color = colors.fgMuted)
            }

            else -> online.forEachIndexed { index, entry ->
                if (index > 0) MDivider()
                MListRow(
                    title = entry.name,
                    plate = false,
                    leading = { MAvatar(headAvatarUrl(entry.name), size = 36.dp, presence = MPresence.Playing) },
                    onClick = { onPlayer(entry.name) },
                )
            }
        }
        if (roster.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            MSectionTitle("КОМАНДА СЕРВЕРА")
            Spacer(Modifier.height(6.dp))
            roster.take(ROSTER_SHOWN).forEach { member -> RosterRow(member) }
            if (roster.size > ROSTER_SHOWN) {
                Text(
                    "и ещё ${roster.size - ROSTER_SHOWN}",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MillidaType.small,
                    color = colors.fgFaint,
                )
            }
        }
    }
}

@Composable
private fun RosterRow(member: HostingPlayer) {
    val colors = millida
    val nick = member.nickname.ifEmpty { member.id }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MAvatar(headAvatarUrl(nick), size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            nick,
            modifier = Modifier.weight(1f),
            style = MillidaType.rowTitle.copy(fontSize = 14.sp),
            color = colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (member.banned) {
            MBadge("забанен", colors.danger, colors.dangerSoft)
        } else {
            val role = rosterRole(member.role)
            if (role.isNotEmpty()) MBadge(role, colors.fgSubtle, colors.surface2)
        }
    }
}

private fun rosterRole(role: String): String = when (role.uppercase()) {
    "OWNER" -> "владелец"
    "ADMIN" -> "админ"
    "MODERATOR" -> "модератор"
    "PLAYER" -> "игрок"
    else -> role.lowercase()
}

@Composable
private fun EventsCard(events: List<HostingEvent>) {
    val colors = millida
    val shown = events.take(EVENTS_SHOWN)
    MCard {
        Text("События", style = MillidaType.sectionTitle.copy(fontSize = 16.sp), color = colors.fg)
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
                        event.message.ifEmpty { "Событие сервера" },
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
            Text("Выключение", style = MillidaType.sectionTitle.copy(fontSize = 16.sp), color = colors.fg)
        }
        Spacer(Modifier.height(12.dp))
        MButtonRow {
            if (running) {
                MDangerButton("Остановить", onClick = onStop, enabled = !busy, icon = MIcons.power, size = MButtonSize.Medium)
            }
            MTextButton("Завершить процесс", onClick = onKill, enabled = !busy, icon = MIcons.zap, color = colors.danger)
        }
    }
}
