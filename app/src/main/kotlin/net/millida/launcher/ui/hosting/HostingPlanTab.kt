package net.millida.launcher.ui.hosting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.Area
import net.millida.launcher.HostingPanel
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.hosting.HOSTING_NAME_MAX
import net.millida.launcher.core.hosting.HOSTING_NAME_MIN
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.core.hosting.HostingSubscription
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.LocalUiFeedback
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MDangerButton
import net.millida.launcher.ui.MDialog
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MInfoRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MProgressBar
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSegmented
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MSwitchRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.hostingDay
import net.millida.launcher.ui.hostingName
import net.millida.launcher.ui.hostingRubles
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.screenPadding
import kotlin.math.roundToInt

private val USAGE_PERIODS = listOf(7, 30, 90)

@Composable
fun HostingPlanTab(
    state: UiState,
    model: LauncherViewModel,
    panel: HostingPanel,
    server: HostingServerState?,
    onChangePlan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hosting = model.hosting
    val feedback = LocalUiFeedback.current
    val serverId = state.hostingServerId
    val details = state.hostingDetails
    val error = state.errorOf(Area.HOSTED_SERVER)
    var draft by rememberSaveable(serverId) { mutableStateOf("") }
    var deleteAsk by rememberSaveable(serverId) { mutableStateOf(false) }
    var typed by rememberSaveable(serverId) { mutableStateOf("") }
    val name = server?.let(::hostingName).orEmpty()
    val owner = details?.owner ?: true
    val canRename = details?.can("settings") ?: true

    LaunchedEffect(serverId) {
        hosting.loadPlans()
        if (serverId.isNotEmpty() && panel.usage == null && !panel.usageLoading) {
            hosting.loadUsage(serverId, panel.usageDays)
        }
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
            if (error == null) items(3, key = { "skeleton-$it" }) { MSkeletonRow() }
            return@LazyColumn
        }
        item(key = "plan") {
            PlanSummary(
                server = server,
                subscription = details?.subscription,
                autoRenew = panel.autoRenew,
                owner = owner,
                canUpgrade = panel.plans.isEmpty() || panel.plans.any { it.priceKopecks > server.planPriceKopecks },
                onAutoRenew = { hosting.setAutoRenew(serverId, it) },
                onChangePlan = onChangePlan,
            )
        }
        item(key = "usage") {
            UsageCard(
                panel = panel,
                onDays = { hosting.loadUsage(serverId, it) },
                onRetry = { hosting.loadUsage(serverId, panel.usageDays) },
            )
        }
        if (canRename) {
            item(key = "rename") {
                RenameCard(
                    current = name,
                    draft = draft,
                    busy = panel.renaming,
                    onDraft = { draft = it.take(HOSTING_NAME_MAX) },
                    onSave = { hosting.rename(serverId, draft) { draft = "" } },
                )
            }
        }
        if (owner) {
            item(key = "delete") {
                DeleteCard(
                    allowed = details?.canDelete != false,
                    busy = panel.deleting,
                    onDelete = {
                        typed = ""
                        deleteAsk = true
                    },
                )
            }
        }
    }

    if (deleteAsk && name.isNotEmpty()) {
        val colors = millida
        MDialog(
            title = "Удалить сервер?",
            onDismiss = { deleteAsk = false },
            body = {
                Text(
                    "Мир, плагины и настройки пропадут навсегда. Впиши имя сервера, чтобы подтвердить.",
                    style = MillidaType.body,
                    color = colors.fgMuted,
                )
                Spacer(Modifier.height(10.dp))
                Text(name, style = MillidaType.pixel, color = colors.fg)
                Spacer(Modifier.height(10.dp))
                MField(value = typed, onValueChange = { typed = it.take(HOSTING_NAME_MAX + 8) }, placeholder = name)
            },
            actions = {
                MTextButton("Отмена", onClick = { deleteAsk = false })
                MDangerButton(
                    "Удалить навсегда",
                    onClick = {
                        deleteAsk = false
                        hosting.deleteServer(serverId) {
                            feedback.play(UiSoundEvent.Delete)
                            model.back()
                        }
                    },
                    enabled = typed.trim() == name.trim() && !panel.deleting,
                    icon = MIcons.trash,
                    size = MButtonSize.Medium,
                )
            },
        )
    }
}

@Composable
private fun PlanSummary(
    server: HostingServerState,
    subscription: HostingSubscription?,
    autoRenew: Boolean?,
    owner: Boolean,
    canUpgrade: Boolean,
    onAutoRenew: (Boolean) -> Unit,
    onChangePlan: () -> Unit,
) {
    val colors = millida
    val price = if (server.free) "Бесплатно" else "${hostingRubles(server.planPriceKopecks)} в месяц"
    MCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    server.planName.ifEmpty { if (server.free) "Бесплатный" else "Тариф" },
                    style = MillidaType.sectionTitle,
                    color = colors.fg,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(price, style = MillidaType.pixel, color = colors.accentText, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            MIcon(MIcons.star, size = 28.dp)
        }
        Spacer(Modifier.height(12.dp))
        if (subscription != null) {
            MInfoRow("Оплачен до", hostingDay(subscription.paidUntil).ifEmpty { "—" })
            if (subscription.periodDays > 0) MInfoRow("Период", hostingDaysLabel(subscription.periodDays))
            if (subscription.status.isNotEmpty()) MInfoRow("Подписка", subscriptionLabel(subscription.status))
            if (owner) {
                Spacer(Modifier.height(4.dp))
                MDivider()
                MSwitchRow(
                    title = "Продление с баланса",
                    hint = "Раз в период с кошелька Millida",
                    checked = autoRenew ?: subscription.autoRenew,
                    onChange = onAutoRenew,
                )
            }
        } else {
            val kept = hostingDay(server.worldDeleteAt)
            if (server.free && kept.isNotEmpty()) {
                MInfoRow("Мир хранится до", kept)
                Text("Запуск сервера продлевает срок", style = MillidaType.small, color = colors.fgFaint)
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            !owner -> Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.lock, size = 18.dp, color = colors.fgSubtle)
                Spacer(Modifier.width(8.dp))
                Text("Тарифом управляет владелец", style = MillidaType.small, color = colors.fgSubtle)
            }

            canUpgrade -> MPrimaryButton(
                "Сменить тариф",
                onClick = onChangePlan,
                modifier = Modifier.fillMaxWidth(),
                icon = MIcons.arrowUp,
            )

            else -> Text("Это самый большой тариф", style = MillidaType.small, color = colors.fgSubtle)
        }
    }
}

@Composable
private fun UsageCard(panel: HostingPanel, onDays: (Int) -> Unit, onRetry: () -> Unit) {
    val colors = millida
    val usage = panel.usage
    val usageError = panel.usageError
    MCard {
        Text("Как работал", style = MillidaType.sectionTitle.copy(fontSize = 16.sp), color = colors.fg)
        Spacer(Modifier.height(10.dp))
        MSegmented(
            options = USAGE_PERIODS,
            selected = panel.usageDays,
            onSelect = onDays,
            label = { "$it дн." },
        )
        Spacer(Modifier.height(12.dp))
        when {
            usageError != null -> MErrorCard(usageError, onRetry = onRetry)
            panel.usageLoading || usage == null -> MSkeleton(height = 132.dp)
            else -> {
                val uptime = usage.uptimePercent.toFloat().coerceIn(0f, 100f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Был доступен", modifier = Modifier.weight(1f), style = MillidaType.small, color = colors.fgSubtle)
                    Text("${uptime.roundToInt()}%", style = MillidaType.pixel, color = colors.fg)
                }
                Spacer(Modifier.height(6.dp))
                MProgressBar(uptime / 100f)
                Spacer(Modifier.height(8.dp))
                MInfoRow("Работал", "${usage.runningHours.roundToInt()} ч", mono = true)
                MInfoRow("Пик игроков", "${usage.peakPlayers}", mono = true)
                MInfoRow(
                    "Падений",
                    "${usage.crashes}",
                    valueColor = if (usage.crashes > 0) colors.danger else colors.fg,
                    mono = true,
                )
                MInfoRow("Память", "${usage.ramGbHours.roundToInt()} ГБ·ч", mono = true)
            }
        }
    }
}

@Composable
private fun RenameCard(
    current: String,
    draft: String,
    busy: Boolean,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
) {
    val colors = millida
    val title = draft.trim()
    MCard {
        Text("Название", style = MillidaType.sectionTitle.copy(fontSize = 16.sp), color = colors.fg)
        Spacer(Modifier.height(10.dp))
        MField(value = draft, onValueChange = onDraft, placeholder = current.ifEmpty { "Мой сервер" }, clearable = true)
        Spacer(Modifier.height(10.dp))
        MSecondaryButton(
            "Сохранить",
            onClick = onSave,
            enabled = !busy && title.length in HOSTING_NAME_MIN..HOSTING_NAME_MAX && title != current,
            icon = MIcons.check,
            loading = busy,
        )
    }
}

@Composable
private fun DeleteCard(allowed: Boolean, busy: Boolean, onDelete: () -> Unit) {
    val colors = millida
    MCard(frame = colors.plateFrame().copy(left = colors.danger, leftWidth = 4.dp)) {
        Text("Удалить сервер", style = MillidaType.sectionTitle.copy(fontSize = 16.sp), color = colors.fg)
        Spacer(Modifier.height(4.dp))
        Text(
            if (allowed) "Мир, плагины и настройки пропадут" else "Сначала выключи продление",
            style = MillidaType.small,
            color = colors.fgSubtle,
        )
        Spacer(Modifier.height(12.dp))
        MDangerButton("Удалить", onClick = onDelete, enabled = allowed && !busy, icon = MIcons.trash)
    }
}

private fun subscriptionLabel(status: String): String = when (status.uppercase()) {
    "ACTIVE" -> "активна"
    "EXPIRED" -> "истекла"
    "CANCELLED", "CANCELED" -> "отменена"
    "PENDING" -> "ждёт оплаты"
    "SUSPENDED" -> "приостановлена"
    else -> status.lowercase()
}
