package net.millida.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import net.millida.launcher.HostingModel
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.hosting.HostingAccessTab
import net.millida.launcher.ui.hosting.HostingBackupsTab
import net.millida.launcher.ui.hosting.HostingBuySheet
import net.millida.launcher.ui.hosting.HostingConsoleTab
import net.millida.launcher.ui.hosting.HostingContentTab
import net.millida.launcher.ui.hosting.HostingFilesTab
import net.millida.launcher.ui.hosting.HostingNetworkTab
import net.millida.launcher.ui.hosting.HostingOverviewTab
import net.millida.launcher.ui.hosting.HostingPlanTab
import net.millida.launcher.ui.hosting.HostingScheduleTab
import net.millida.launcher.ui.hosting.HostingSettingsTab
import net.millida.launcher.ui.hosting.HostingWorldsTab

internal enum class HostingTab(val label: String, val icon: MillidaIcon) {
    Overview("Обзор", MIcons.monitor),
    Console("Консоль", MIcons.list),
    Content("Моды", MIcons.blocks),
    Worlds("Мир", MIcons.map),
    Backups("Бэкапы", MIcons.box),
    Settings("Настройки", MIcons.settings),
    Files("Файлы", MIcons.folder),
    Network("Сеть", MIcons.link),
    Schedule("Автоматика", MIcons.clock),
    Access("Доступ", MIcons.key),
    Plan("Тариф", MIcons.star),
}

private const val DETAILS_FAST_MS = 10_000L
private const val DETAILS_SLOW_MS = 20_000L

/** The desktop panel's five tabs in sight; the players' place goes to the backups its «Мир» holds. */
private val WIDE_MAIN_TABS: List<HostingTab> = listOf(
    HostingTab.Overview,
    HostingTab.Console,
    HostingTab.Content,
    HostingTab.Worlds,
    HostingTab.Backups,
)

private val WIDE_MORE_TABS: List<HostingTab> = listOf(
    HostingTab.Settings,
    HostingTab.Files,
    HostingTab.Network,
    HostingTab.Schedule,
    HostingTab.Access,
    HostingTab.Plan,
)

private val WIDE_TAB_HEIGHT = 54.dp
private val WIDE_TAB_COMPACT_HEIGHT = 40.dp
private val WIDE_MORE_WIDTH = 210.dp
private val WIDE_MORE_ITEM_HEIGHT = 40.dp

@Composable
fun HostingManageScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val colors = millida
    val hosting = model.hosting
    val panel by hosting.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val serverId = state.hostingServerId
    val server = state.hostingDetails?.server ?: state.hosted.firstOrNull { it.id == serverId }
    val status = server?.status.orEmpty()
    val running = status.equals("RUNNING", ignoreCase = true)
    val name = server?.let(::hostingName) ?: "Сервер"
    val address = server?.address.orEmpty()
    val tag = server?.let(::hostingTag).orEmpty()
    val canControl = state.hostingDetails?.can("control") ?: true
    val currentStatus by rememberUpdatedState(status)
    var tabName by rememberSaveable(serverId) { mutableStateOf(HostingTab.Overview.name) }
    val tab = HostingTab.entries.firstOrNull { it.name == tabName } ?: HostingTab.Overview
    var ask by rememberSaveable(serverId) { mutableStateOf<String?>(null) }
    var powerSheet by rememberSaveable(serverId) { mutableStateOf(false) }
    var planSheet by rememberSaveable(serverId) { mutableStateOf(false) }

    val onAsk: (String) -> Unit = { action ->
        if (action == HostingModel.START) {
            hosting.power(serverId, action)
        } else {
            ask = action
        }
    }
    val onCopy: () -> Unit = { model.copyHostAddress(clipboard, address) }
    val onPlay: () -> Unit = { model.playOnAddress(address, name) }

    LaunchedEffect(serverId) {
        hosting.select(serverId)
    }
    LaunchedEffect(serverId) {
        model.hostingTools.planAsks.collect { asked ->
            if (asked == serverId) tabName = HostingTab.Plan.name
        }
    }
    LaunchedEffect(serverId, visible) {
        if (!visible || serverId.isEmpty()) return@LaunchedEffect
        while (true) {
            val upper = currentStatus.uppercase()
            delay(if (upper in HOSTING_BUSY_STATES && upper != "QUEUED") DETAILS_FAST_MS else DETAILS_SLOW_MS)
            model.loadHostingDetails(full = false)
        }
    }
    LaunchedEffect(resumed) {
        if (resumed) hosting.resumed()
    }
    LaunchedEffect(serverId, status) {
        if (status.equals("CRASHED", ignoreCase = true)) hosting.loadCrash(serverId) else hosting.clearCrash()
    }

    val refresh: () -> Unit = {
        model.loadHostingDetails()
        model.hostingTools.ensureRights(serverId, force = true)
    }
    val strip: @Composable (Modifier) -> Unit = { placement ->
        HostingStrip(
            status = status,
            address = address,
            busy = panel.powering.containsKey(serverId),
            canControl = canControl,
            onCopy = onCopy,
            onPower = { powerSheet = true },
            modifier = placement,
        )
    }
    val body: @Composable () -> Unit = {
        when (tab) {
            HostingTab.Overview -> HostingOverviewTab(
                state = state,
                model = model,
                panel = panel,
                server = server,
                onAsk = onAsk,
                onCopy = onCopy,
                onPlay = onPlay,
                onConsole = { tabName = HostingTab.Console.name },
                onPlan = { tabName = HostingTab.Plan.name },
            )

            HostingTab.Console -> HostingConsoleTab(
                serverId = serverId,
                running = running,
                panel = panel,
                hosting = hosting,
            )

            HostingTab.Content -> HostingContentTab(
                serverId = serverId,
                tools = model.hostingTools,
                core = server?.core.orEmpty(),
                version = server?.version.orEmpty(),
                running = running,
                onChanged = { model.loadHostingDetails() },
            )

            HostingTab.Settings -> HostingSettingsTab(
                state = state,
                model = model,
                hosting = hosting,
                server = server,
                saving = panel.savingSetting,
                running = running,
            )

            HostingTab.Files -> HostingFilesTab(serverId, model.hostingTools)
            HostingTab.Backups -> HostingBackupsTab(serverId, model.hostingTools)
            HostingTab.Worlds -> HostingWorldsTab(serverId, model.hostingTools)
            HostingTab.Network -> HostingNetworkTab(serverId, model.hostingTools)
            HostingTab.Access -> HostingAccessTab(serverId, model.hostingTools)
            HostingTab.Schedule -> HostingScheduleTab(serverId, model.hostingTools)
            HostingTab.Plan -> HostingPlanTab(
                state = state,
                model = model,
                panel = panel,
                server = server,
                onChangePlan = { planSheet = true },
            )
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        if (LocalWideLayout.current) {
            val compact = LocalConfiguration.current.screenHeightDp < WIDE_ROOMY_HEIGHT_DP
            MTopBar(
                title = name,
                onBack = { model.back() },
                subtitle = tag.ifEmpty { hostingStatusLabel(status) },
                actions = {
                    MSecondaryButton("Обновить", onClick = refresh, icon = MIcons.refresh, size = MButtonSize.Small)
                },
            )
            HostingWideTabs(
                selected = tab,
                onSelect = { tabName = it.name },
                compact = compact,
                modifier = Modifier.padding(start = screenPadding, end = screenPadding, top = if (compact) 8.dp else 12.dp),
            )
            if (tab != HostingTab.Overview) {
                strip(Modifier.padding(start = screenPadding, end = screenPadding, top = if (compact) 8.dp else 10.dp))
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                body()
            }
        } else {
            MTopBar(
                title = name,
                onBack = { model.back() },
                subtitle = tag.ifEmpty { hostingStatusLabel(status) },
                actions = {
                    MIconButton(MIcons.refresh, onClick = refresh, label = "Обновить")
                },
            )
            MTabs(
                tabs = HostingTab.entries,
                selected = tab,
                onSelect = { tabName = it.name },
                modifier = Modifier.padding(horizontal = screenPadding),
                label = { it.label },
                icon = { it.icon },
                scrollable = true,
            )
            if (tab != HostingTab.Overview) {
                strip(Modifier.padding(start = screenPadding, end = screenPadding, top = 10.dp))
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                body()
            }
        }
    }

    ask?.let { action ->
        MConfirmDialog(
            title = hostingConfirmTitle(action),
            text = hostingConfirmText(action),
            confirm = hostingConfirmLabel(action),
            onConfirm = {
                ask = null
                hosting.power(serverId, action)
            },
            onDismiss = { ask = null },
            danger = action != HostingModel.RESTART,
        )
    }

    if (powerSheet && canControl) {
        HostingPowerSheet(
            status = status,
            busy = panel.powering.containsKey(serverId),
            onDismiss = { powerSheet = false },
            onAction = { action ->
                powerSheet = false
                onAsk(action)
            },
            onRenew = {
                powerSheet = false
                tabName = HostingTab.Plan.name
            },
        )
    }

    if (planSheet) {
        HostingBuySheet(
            model = model,
            panel = panel,
            hosted = state.hosted,
            road = null,
            onDismiss = { planSheet = false },
            upgradeServerId = serverId,
            currentCode = server?.planCode.orEmpty(),
            currentPriceKopecks = server?.planPriceKopecks ?: 0L,
            upgradeCreditKopecks = state.hostingDetails?.upgradeCreditKopecks ?: 0L,
        )
    }
}

@Composable
private fun HostingStrip(
    status: String,
    address: String,
    busy: Boolean,
    canControl: Boolean,
    onCopy: () -> Unit,
    onPower: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    Row(
        modifier
            .fillMaxWidth()
            .pixelSurface(colors.surface, MillidaCut.plate, colors.plateFrame())
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HostingStatusPlate(status)
        Spacer(Modifier.width(10.dp))
        Text(
            address.ifEmpty { "адрес появится после запуска" },
            modifier = Modifier.weight(1f),
            style = if (address.isEmpty()) MillidaType.small else MillidaType.pixelSmall,
            color = if (address.isEmpty()) colors.fgFaint else colors.fgMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (address.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            MIconButton(MIcons.copy, onClick = onCopy, label = "Скопировать адрес")
        }
        Spacer(Modifier.width(6.dp))
        if (busy) {
            Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) { MPixelLoader() }
        } else {
            MIconButton(MIcons.power, onClick = onPower, tint = colors.fg, label = "Питание сервера", enabled = canControl)
        }
    }
}

@Composable
private fun HostingPowerSheet(
    status: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
    onRenew: () -> Unit,
) {
    val colors = millida
    val upper = status.uppercase()
    val running = upper == "RUNNING"
    val canKill = running || upper == "CRASHED" || upper == "STOPPING"
    MBottomSheet(onDismiss = onDismiss, title = "Питание") {
        if (upper == "SUSPENDED" && !busy) {
            MListRow(
                title = "Продлить",
                subtitle = "Сервер приостановлен",
                icon = MIcons.wallet,
                plate = false,
                onClick = onRenew,
            )
        } else if (busy || upper in HOSTING_BUSY_STATES) {
            Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                MPixelLoader()
                Spacer(Modifier.width(10.dp))
                Text(
                    if (busy) "Команда уже выполняется" else hostingStatusLabel(status),
                    style = MillidaType.body,
                    color = colors.fgMuted,
                )
            }
        } else if (!running) {
            MListRow(
                title = "Запустить",
                icon = MIcons.play,
                plate = false,
                onClick = { onAction(HostingModel.START) },
            )
        }
        if (running && !busy) {
            MListRow(
                title = "Перезапустить",
                subtitle = "Игроков выкинет на минуту",
                icon = MIcons.refresh,
                plate = false,
                onClick = { onAction(HostingModel.RESTART) },
            )
            MDivider()
            MListRow(
                title = "Остановить",
                subtitle = "Мир сохранится",
                icon = MIcons.power,
                plate = false,
                onClick = { onAction(HostingModel.STOP) },
            )
        }
        if (canKill && !busy) {
            MDivider()
            MListRow(
                title = "Завершить процесс",
                subtitle = "Если сервер завис",
                icon = MIcons.zap,
                plate = false,
                onClick = { onAction(HostingModel.KILL) },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

internal fun hostingConfirmTitle(action: String): String = when (action) {
    HostingModel.STOP -> "Остановить сервер?"
    HostingModel.RESTART -> "Перезапустить сервер?"
    else -> "Завершить процесс?"
}

internal fun hostingConfirmLabel(action: String): String = when (action) {
    HostingModel.STOP -> "Остановить"
    HostingModel.RESTART -> "Перезапустить"
    else -> "Завершить"
}

internal fun hostingConfirmText(action: String): String = when (action) {
    HostingModel.STOP -> "Игроков выкинет из игры, мир сохранится."
    HostingModel.RESTART -> "Игроков выкинет на время перезапуска — обычно меньше минуты."
    else -> "Процесс оборвётся без сохранения, свежие постройки могут пропасть. Так делают, только если сервер завис."
}

@Composable
private fun HostingWideTabs(
    selected: HostingTab,
    onSelect: (HostingTab) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    var moreOpen by remember { mutableStateOf(false) }
    val height = if (compact) WIDE_TAB_COMPACT_HEIGHT else WIDE_TAB_HEIGHT
    val picked = WIDE_MORE_TABS.firstOrNull { it == selected }
    val drop = with(LocalDensity.current) { (height + 8.dp).roundToPx() }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WIDE_MAIN_TABS.forEach { item ->
            HostingWideTab(
                text = item.label,
                icon = item.icon,
                picked = item == selected,
                height = height,
                compact = compact,
                modifier = Modifier.weight(1f),
                onClick = { if (item != selected) onSelect(item) },
            )
        }
        Box(Modifier.weight(1f)) {
            HostingWideTab(
                text = picked?.label ?: "Ещё",
                icon = picked?.icon ?: MIcons.more,
                picked = picked != null,
                height = height,
                compact = compact,
                modifier = Modifier.fillMaxWidth(),
                trailing = MIcons.chevronDown,
                onClick = { moreOpen = !moreOpen },
            )
            if (moreOpen) {
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(0, drop),
                    onDismissRequest = { moreOpen = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        Modifier
                            .width(WIDE_MORE_WIDTH)
                            .pixelSurface(colors.raised, MillidaCut.plate, colors.plateFrame())
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        WIDE_MORE_TABS.forEach { item ->
                            HostingMoreItem(
                                text = item.label,
                                icon = item.icon,
                                picked = item == selected,
                                onClick = {
                                    moreOpen = false
                                    if (item != selected) onSelect(item)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostingWideTab(
    text: String,
    icon: MillidaIcon,
    picked: Boolean,
    height: Dp,
    compact: Boolean,
    modifier: Modifier = Modifier,
    trailing: MillidaIcon? = null,
    onClick: () -> Unit,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (picked && trailing == null) null else UiSoundEvent.Nav)
    val fill = when {
        picked -> colors.accentSoft
        pressed -> colors.raised
        else -> colors.surface2
    }
    val ink = if (picked) colors.accentText else colors.fgSubtle
    Row(
        modifier
            .height(height)
            .pixelSurface(fill, MillidaCut.control)
            .selectable(picked, interaction, null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(icon, size = if (compact) 16.dp else 18.dp, color = ink)
        Text(
            text,
            modifier = Modifier.weight(1f, fill = false),
            style = MillidaType.rowTitle.copy(fontSize = if (compact) 14.sp else 15.sp),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) MIcon(trailing, size = 14.dp, color = ink)
    }
}

@Composable
private fun HostingMoreItem(text: String, icon: MillidaIcon, picked: Boolean, onClick: () -> Unit) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Nav)
    val ink = if (picked) colors.accentText else colors.fgMuted
    Row(
        Modifier
            .fillMaxWidth()
            .height(WIDE_MORE_ITEM_HEIGHT)
            .pixelSurface(if (pressed) colors.surface2 else Color.Transparent, MillidaCut.control)
            .selectable(picked, interaction, null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(icon, size = 16.dp, color = ink)
        Text(
            text,
            style = MillidaType.rowTitle.copy(fontSize = 14.sp),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
