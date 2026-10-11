package net.millida.launcher.ui.hosting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.HostingPanel
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.core.hosting.HOSTING_NAME_MAX
import net.millida.launcher.core.hosting.HostingMap
import net.millida.launcher.core.hosting.HostingPlan
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.LocalUiFeedback
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButton
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonVariant
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSectionTitle
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.hostingGb
import net.millida.launcher.ui.hostingRubles
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.openLinkOrNotice
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.wellFrame

enum class HostingBuyRoad { Paid, Free }

private const val MAP_KIND = "map"

private data class HostingPlayKind(val id: String, val label: String, val icon: MillidaIcon)

private val PLAY_KIND_OPTIONS: List<HostingPlayKind> = listOf(
    HostingPlayKind("vanilla", "Обычный", MIcons.blocks),
    HostingPlayKind("modpack", "Сборка модов", MIcons.box),
    HostingPlayKind(MAP_KIND, "Карта", MIcons.map),
    HostingPlayKind("minigames", "Мини-игры", MIcons.users),
)

@Composable
fun HostingBuySheet(
    model: LauncherViewModel,
    panel: HostingPanel,
    hosted: List<HostingServerState>,
    road: HostingBuyRoad?,
    onDismiss: () -> Unit,
    upgradeServerId: String = "",
    currentCode: String = "",
    currentPriceKopecks: Long = 0L,
    upgradeCreditKopecks: Long = 0L,
) {
    val hosting = model.hosting
    val colors = millida
    val context = LocalContext.current
    val feedback = LocalUiFeedback.current
    val upgrade = upgradeServerId.isNotEmpty()
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(PLAY_KIND_OPTIONS.first().id) }
    var mapQuery by rememberSaveable { mutableStateOf("") }
    var mapPick by remember { mutableStateOf<HostingMap?>(null) }
    var confirm by remember { mutableStateOf<HostingPlan?>(null) }
    var gatePlan by remember { mutableStateOf<HostingPlan?>(null) }
    val freeServer = if (upgrade) null else hosted.firstOrNull { it.free }
    val choosingMap = !upgrade && kind == MAP_KIND

    LaunchedEffect(Unit) {
        hosting.loadPlans()
        hosting.loadBalance()
    }
    LaunchedEffect(choosingMap, mapQuery) {
        if (choosingMap) hosting.searchMaps(mapQuery)
    }

    val topUp: () -> Unit = {
        hosting.awaitTopUp()
        model.openLinkOrNotice(context, model.topUpUrl())
    }

    fun order(plan: HostingPlan) {
        if (upgrade) {
            hosting.changePlan(
                upgradeServerId,
                plan,
                onDone = {
                    feedback.play(UiSoundEvent.Achievement)
                    onDismiss()
                },
                onNoFunds = topUp,
            )
        } else {
            if (plan.free) gatePlan = plan
            hosting.buy(
                plan = plan,
                playKind = kind,
                name = name,
                map = mapPick.takeIf { kind == MAP_KIND },
                onCreated = { server ->
                    feedback.play(UiSoundEvent.Achievement)
                    onDismiss()
                    model.openHostedServer(server.id)
                },
                onNoFunds = topUp,
            )
        }
    }

    val downgrade: (HostingPlan) -> Boolean = { plan -> upgrade && (plan.free || plan.priceKopecks < currentPriceKopecks) }
    val taken: (HostingPlan) -> Boolean = { plan ->
        (currentCode.isNotEmpty() && plan.code == currentCode) || (!upgrade && plan.free && freeServer != null) || downgrade(plan)
    }
    val open = panel.plans.filterNot(taken)
    val mainCode = when {
        upgrade -> {
            val higher = open.filter { it.priceKopecks > currentPriceKopecks }
            (higher.firstOrNull { it.featured } ?: higher.minByOrNull { it.priceKopecks })?.code
        }
        road == HostingBuyRoad.Paid -> (open.firstOrNull { !it.free && it.featured } ?: open.firstOrNull { !it.free } ?: open.firstOrNull())?.code
        road == HostingBuyRoad.Free -> (open.firstOrNull { it.free } ?: open.firstOrNull())?.code
        else -> open.firstOrNull()?.code
    }
    val dueOf: (HostingPlan) -> Long = { plan ->
        if (upgrade) maxOf(0L, plan.priceKopecks - upgradeCreditKopecks) else plan.priceKopecks
    }

    MBottomSheet(onDismiss = onDismiss, title = if (upgrade) "Сменить тариф" else "Новый сервер") {
        if (!upgrade) {
            MField(
                value = name,
                onValueChange = { name = it.take(HOSTING_NAME_MAX) },
                placeholder = "Название сервера",
                leadingIcon = MIcons.edit,
                clearable = true,
            )
            Spacer(Modifier.height(16.dp))
            MSectionTitle("ВО ЧТО ИГРАЕМ")
            Spacer(Modifier.height(8.dp))
            MButtonRow {
                PLAY_KIND_OPTIONS.forEach { option ->
                    MChip(
                        text = option.label,
                        selected = kind == option.id,
                        onClick = {
                            kind = option.id
                            if (option.id != MAP_KIND) mapPick = null
                        },
                        icon = option.icon,
                    )
                }
            }
            if (choosingMap) {
                Spacer(Modifier.height(12.dp))
                MapPicker(
                    panel = panel,
                    query = mapQuery,
                    onQuery = { mapQuery = it },
                    picked = mapPick,
                    onPick = { map -> mapPick = if (mapPick?.id == map.id) null else map },
                    onRetry = { hosting.searchMaps(mapQuery) },
                )
            }
            if (freeServer != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pixelSurface(colors.surface2, MillidaCut.control)
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Бесплатный уже есть — один на аккаунт",
                        modifier = Modifier.weight(1f),
                        style = MillidaType.small,
                        color = colors.fgSubtle,
                    )
                    MTextButton(
                        "Открыть",
                        onClick = {
                            onDismiss()
                            model.openHostedServer(freeServer.id)
                        },
                        color = colors.accentText,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        BalanceRow(panel.balanceKopecks, onTopUp = topUp)
        Spacer(Modifier.height(12.dp))

        val plansError = panel.plansError
        when {
            panel.plans.isEmpty() && plansError != null -> MErrorCard(plansError, onRetry = { hosting.loadPlans(force = true) })
            panel.plans.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { MSkeleton(height = 150.dp) }
            }

            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                panel.plans.forEach { plan ->
                    val current = currentCode.isNotEmpty() && plan.code == currentCode
                    val blocked = when {
                        current -> null
                        downgrade(plan) -> "Только повышение"
                        !upgrade && plan.free && freeServer != null -> "Бесплатный уже есть"
                        choosingMap && mapPick == null -> "Сначала выбери карту"
                        else -> null
                    }
                    PlanOffer(
                        plan = plan,
                        payKopecks = dueOf(plan),
                        main = plan.code == mainCode,
                        current = current,
                        blocked = blocked,
                        busy = panel.buying == plan.code,
                        locked = panel.buying != null,
                        onPick = {
                            if (plan.free && !upgrade) {
                                order(plan)
                            } else {
                                confirm = plan
                            }
                        },
                    )
                }
            }
        }
    }

    confirm?.let { plan ->
        val price = hostingRubles(plan.priceKopecks)
        val due = hostingRubles(dueOf(plan))
        val credited = upgrade && upgradeCreditKopecks > 0
        MConfirmDialog(
            title = "Тариф «${plan.name}»",
            text = if (credited) {
                "К оплате $due — остаток прошлого тарифа зачтён. Спишем с кошелька Millida."
            } else {
                "$price в месяц спишем с кошелька Millida."
            },
            confirm = "Оплатить $due",
            onConfirm = {
                confirm = null
                order(plan)
            },
            onDismiss = { confirm = null },
            danger = false,
        )
    }

    panel.freeGate?.let { gate ->
        val link = if (gate.botStarted) gate.channelUrl else gate.botUrl
        MBottomSheet(onDismiss = { hosting.closeFreeGate() }, title = "Бесплатный сервер") {
            Text(
                if (gate.botStarted) {
                    "Подпишись на канал Millida и нажми «Проверить»"
                } else {
                    "Запусти бота${gate.botUsername.takeIf { it.isNotBlank() }?.let { " @$it" }.orEmpty()} — после этого выдадим сервер"
                },
                style = MillidaType.body,
                color = colors.fgMuted,
            )
            Spacer(Modifier.height(16.dp))
            MPrimaryButton(
                if (gate.botStarted) "Открыть канал" else "Открыть бота",
                onClick = { model.openLinkOrNotice(context, link) },
                modifier = Modifier.fillMaxWidth(),
                enabled = link.isNotBlank(),
                icon = MIcons.link,
            )
            Spacer(Modifier.height(8.dp))
            MSecondaryButton(
                "Проверить",
                onClick = { hosting.recheckFreeGate { gatePlan?.let { picked -> order(picked) } } },
                modifier = Modifier.fillMaxWidth(),
                icon = MIcons.refresh,
                loading = panel.gateChecking,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun BalanceRow(balanceKopecks: Long?, onTopUp: () -> Unit) {
    val colors = millida
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pixelSurface(colors.inset, MillidaCut.control, colors.wellFrame())
            .padding(start = 12.dp, end = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(MIcons.wallet, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Баланс Millida", style = MillidaType.small.copy(fontSize = 11.5.sp), color = colors.fgSubtle, maxLines = 1)
            Text(
                balanceKopecks?.let(::hostingRubles) ?: "—",
                style = MillidaType.pixel,
                color = colors.fg,
                maxLines = 1,
            )
        }
        MTextButton("Пополнить", onClick = onTopUp, icon = MIcons.plus, color = colors.accentText)
    }
}

@Composable
private fun PlanOffer(
    plan: HostingPlan,
    payKopecks: Long,
    main: Boolean,
    current: Boolean,
    blocked: String?,
    busy: Boolean,
    locked: Boolean,
    onPick: () -> Unit,
) {
    val colors = millida
    val players = when {
        plan.maxPlayers > 0 -> "${plan.maxPlayers} игроков"
        plan.playersComfort > 0 -> "до ${plan.playersComfort} игроков"
        else -> null
    }
    MCard(background = if (current) colors.accentToday else colors.surface2, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                plan.name,
                modifier = Modifier.weight(1f),
                style = MillidaType.rowTitle.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (plan.free) "0 ₽" else "${hostingRubles(plan.priceKopecks)}/мес",
                style = MillidaType.pixel,
                color = colors.accentText,
                maxLines = 1,
            )
        }
        if (plan.featured) {
            Spacer(Modifier.height(6.dp))
            MBadge("Берут чаще всего", colors.accentText, colors.accentSoft, icon = MIcons.star)
        } else if (plan.tagline.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(plan.tagline, style = MillidaType.small, color = colors.fgSubtle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        MButtonRow {
            if (plan.ramMb > 0) PlanSpec(MIcons.monitor, "${hostingGb(plan.ramMb)} ГБ")
            if (players != null) PlanSpec(MIcons.users, players)
            if (plan.diskMb > 0) PlanSpec(MIcons.box, "${hostingGb(plan.diskMb)} ГБ диск")
        }
        Spacer(Modifier.height(12.dp))
        val label = when {
            current -> "Текущий тариф"
            busy -> "Оформляем"
            blocked != null -> blocked
            plan.free -> "Запустить бесплатно"
            else -> "Оплатить ${hostingRubles(payKopecks)}"
        }
        MButton(
            text = label,
            onClick = onPick,
            modifier = Modifier.fillMaxWidth(),
            variant = when {
                current -> MButtonVariant.Ghost
                main -> MButtonVariant.Primary
                else -> MButtonVariant.Secondary
            },
            enabled = !current && blocked == null && !locked,
            loading = busy,
            icon = when {
                current -> null
                plan.free -> MIcons.play
                else -> MIcons.wallet
            },
        )
    }
}

@Composable
private fun PlanSpec(icon: MillidaIcon, text: String) {
    Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        MIcon(icon, size = 16.dp)
        Spacer(Modifier.width(5.dp))
        Text(text, style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = millida.fgMuted, maxLines = 1)
    }
}

@Composable
private fun MapPicker(
    panel: HostingPanel,
    query: String,
    onQuery: (String) -> Unit,
    picked: HostingMap?,
    onPick: (HostingMap) -> Unit,
    onRetry: () -> Unit,
) {
    val colors = millida
    val maps = panel.maps
    val error = panel.mapsError
    MField(
        value = query,
        onValueChange = { onQuery(it.take(60)) },
        placeholder = "Поиск карты",
        leadingIcon = MIcons.search,
        clearable = true,
        imeAction = ImeAction.Search,
    )
    Spacer(Modifier.height(8.dp))
    if (picked != null) {
        MBadge("Карта: ${picked.name}", colors.accentText, colors.accentSoft, icon = MIcons.check)
        Spacer(Modifier.height(8.dp))
    }
    when {
        error != null -> MErrorCard(error, onRetry = onRetry)
        maps == null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { MSkeletonRow() }
        }

        maps.isEmpty() -> Text(
            "Ничего не нашлось",
            modifier = Modifier.padding(vertical = 10.dp),
            style = MillidaType.small,
            color = colors.fgFaint,
        )

        else -> Column {
            maps.take(8).forEach { map ->
                MListRow(
                    title = map.name,
                    subtitle = if (map.downloads > 0) "Загрузок: ${groupDigits(map.downloads)}" else null,
                    plate = false,
                    leading = { MRemoteImage(url = map.iconUrl, icon = MIcons.map, size = 40.dp) },
                    onClick = { onPick(map) },
                    trailing = {
                        if (picked?.id == map.id) MIcon(MIcons.check, size = 18.dp, color = colors.accentText)
                    },
                )
            }
        }
    }
}
