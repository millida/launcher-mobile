package net.millida.launcher.ui.hosting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.HostingScheduleDraft
import net.millida.launcher.HostingToolsModel
import net.millida.launcher.forServer
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSectionTitle
import net.millida.launcher.ui.MSegmented
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MToggle
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface

private val KINDS = listOf("restart", "backup", "stop", "command")

private val DAY_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

private const val EVERY_DAY = 127
private const val WEEKDAYS = 31
private const val WEEKEND = 96
private const val SCHEDULE_LIMIT = 10
private const val COMMAND_MAX = 600

@Composable
fun HostingScheduleTab(serverId: String, tools: HostingToolsModel) {
    LaunchedEffect(serverId) { tools.openSchedule(serverId) }
    val all by tools.schedule.collectAsState()
    val busy by tools.busy.collectAsState()
    val rights by tools.rights.collectAsState()
    val access = rights.forServer(serverId)
    val state = all.takeIf { it.serverId == serverId }
    val saving = tools.key(serverId, "schedule:save") in busy
    var editing by remember { mutableStateOf<String?>(null) }

    if (access != null && !access.can("schedule")) {
        ToolsList { item(key = "no-rights") { ToolsNoRights() } }
    } else {
        val list = state?.items
        val error = state?.error
        ToolsList {
            item(key = "head") {
                MCard {
                    ToolsHead(
                        title = "Расписание",
                        subtitle = "Время московское",
                        action = {
                            MSecondaryButton(
                                "Добавить",
                                onClick = { tools.addSchedule(serverId)?.let { editing = it } },
                                enabled = list != null && list.size < SCHEDULE_LIMIT && !saving,
                                icon = MIcons.plus,
                                size = MButtonSize.Small,
                            )
                        },
                    )
                    if (state?.dirty == true) {
                        Spacer(Modifier.height(12.dp))
                        MButtonRow {
                            MPrimaryButton(
                                "Сохранить",
                                onClick = { tools.saveSchedule(serverId) },
                                icon = MIcons.check,
                                loading = saving,
                            )
                            MTextButton("Отменить", onClick = { tools.loadSchedule(serverId) }, enabled = !saving)
                        }
                    }
                }
            }
            when {
                error != null && list == null -> item(key = "error") {
                    MErrorCard(error, onRetry = { tools.loadSchedule(serverId) })
                }
                list == null -> items(2, key = { "skeleton-$it" }) { MSkeletonRow() }
                list.isEmpty() -> item(key = "empty") {
                    MEmptyState(icon = MIcons.clock, title = "Задач нет")
                }
                else -> {
                    if (error != null) {
                        item(key = "error") { MErrorCard(error, onRetry = { tools.loadSchedule(serverId) }) }
                    }
                    items(list, key = { "task:" + it.key }) { draft ->
                        ScheduleCard(
                            draft = draft,
                            modifier = Modifier.animateItem(),
                            onToggle = { on -> tools.editSchedule(serverId, draft.key) { it.copy(enabled = on) } },
                            onEdit = { editing = draft.key },
                        )
                    }
                }
            }
        }
    }

    val open = editing?.let { key -> state?.items?.firstOrNull { it.key == key } }
    val gone = editing != null && open == null
    LaunchedEffect(gone) {
        if (gone) editing = null
    }
    if (open != null) {
        ScheduleSheet(
            draft = open,
            onChange = { change -> tools.editSchedule(serverId, open.key, change) },
            onRemove = {
                editing = null
                tools.removeSchedule(serverId, open.key)
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ScheduleCard(
    draft: HostingScheduleDraft,
    modifier: Modifier,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val colors = millida
    val last = toolsMoment(draft.lastRunAt)
    MCard(modifier = modifier, onClick = onEdit, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .pixelSurface(colors.surface2, MillidaCut.control),
                contentAlignment = Alignment.Center,
            ) {
                MIcon(kindIcon(draft), size = 24.dp, color = colors.fgMuted)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    kindTitle(draft),
                    style = MillidaType.rowTitle,
                    color = if (draft.enabled) colors.fg else colors.fgFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${timeText(draft)} · ${daysText(draft.days)}",
                    style = MillidaType.small,
                    color = colors.fgSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (last.isNotEmpty()) {
                    Text(
                        "Запускалась $last",
                        style = MillidaType.small,
                        color = colors.fgFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            MToggle(checked = draft.enabled, onChange = onToggle)
        }
    }
}

@Composable
private fun ScheduleSheet(
    draft: HostingScheduleDraft,
    onChange: ((HostingScheduleDraft) -> HostingScheduleDraft) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = millida
    MBottomSheet(onDismiss = onDismiss, title = kindTitle(draft)) {
        if (draft.steps > 0) {
            MBadge("Цепочка из ${draft.steps} шагов · правится на сайте", colors.fgMuted, colors.chipFill, icon = MIcons.list)
        } else {
            MSectionTitle("ЧТО ДЕЛАТЬ")
            Spacer(Modifier.height(8.dp))
            MSegmented(
                options = KINDS,
                selected = draft.kind,
                onSelect = { kind -> onChange { it.copy(kind = kind) } },
                label = { kindShort(it) },
            )
        }
        Spacer(Modifier.height(16.dp))
        MSectionTitle("ВРЕМЯ ПО МОСКВЕ")
        Spacer(Modifier.height(8.dp))
        if (draft.everyMin > 0) {
            Text("Каждые ${draft.everyMin} мин · интервал меняется на сайте", style = MillidaType.body, color = colors.fgMuted)
        } else {
            TimeFields(key = draft.key, minute = draft.minute) { minute -> onChange { it.copy(minute = minute) } }
        }
        Spacer(Modifier.height(16.dp))
        MSectionTitle("ДНИ")
        Spacer(Modifier.height(8.dp))
        MButtonRow {
            DAY_NAMES.forEachIndexed { index, name ->
                val bit = 1 shl index
                val on = (draft.days and bit) != 0
                MChip(
                    text = name,
                    selected = on,
                    onClick = { onChange { it.copy(days = if (on) it.days and bit.inv() else it.days or bit) } },
                )
            }
            MChip(
                text = "Каждый день",
                selected = draft.days == EVERY_DAY,
                onClick = { onChange { it.copy(days = EVERY_DAY) } },
            )
        }
        if (draft.days == 0) {
            Spacer(Modifier.height(6.dp))
            Text("Выбери хотя бы один день", style = MillidaType.small, color = colors.danger)
        }
        if (draft.kind == "command" && draft.steps == 0) {
            Spacer(Modifier.height(16.dp))
            MSectionTitle("КОМАНДА")
            Spacer(Modifier.height(8.dp))
            MField(
                value = draft.command,
                onValueChange = { text -> onChange { it.copy(command = text.take(COMMAND_MAX)) } },
                placeholder = "say Перезапуск через 5 минут",
                singleLine = false,
                maxLines = 4,
            )
        }
        Spacer(Modifier.height(20.dp))
        MPrimaryButton("Готово", onClick = onDismiss, modifier = Modifier.fillMaxWidth(), icon = MIcons.check)
        Spacer(Modifier.height(8.dp))
        MTextButton("Убрать задачу", onClick = onRemove, modifier = Modifier.fillMaxWidth(), icon = MIcons.trash, color = colors.danger)
    }
}

@Composable
private fun TimeFields(key: String, minute: Int, onChange: (Int) -> Unit) {
    var hours by remember(key) { mutableStateOf(twoDigits(minute / 60)) }
    var minutes by remember(key) { mutableStateOf(twoDigits(minute % 60)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        MField(
            value = hours,
            onValueChange = { typed ->
                val clean = typed.filter { it.isDigit() }.take(2)
                hours = clean
                clean.toIntOrNull()?.takeIf { it in 0..23 }?.let { onChange(it * 60 + minute % 60) }
            },
            placeholder = "ЧЧ",
            modifier = Modifier.weight(1f),
            keyboardType = KeyboardType.Number,
        )
        Text(
            ":",
            modifier = Modifier.padding(horizontal = 10.dp),
            style = MillidaType.pixelLarge,
            color = millida.fgMuted,
        )
        MField(
            value = minutes,
            onValueChange = { typed ->
                val clean = typed.filter { it.isDigit() }.take(2)
                minutes = clean
                clean.toIntOrNull()?.takeIf { it in 0..59 }?.let { onChange(minute / 60 * 60 + it) }
            },
            placeholder = "ММ",
            modifier = Modifier.weight(1f),
            keyboardType = KeyboardType.Number,
        )
    }
}

private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')

private fun kindShort(kind: String): String = when (kind) {
    "restart" -> "Рестарт"
    "backup" -> "Копия"
    "stop" -> "Стоп"
    "command" -> "Команда"
    else -> kind
}

private fun kindTitle(draft: HostingScheduleDraft): String {
    if (draft.steps > 0) return "Цепочка действий"
    return when (draft.kind) {
        "restart" -> "Перезапуск"
        "backup" -> "Резервная копия"
        "stop" -> "Остановка"
        "command" -> draft.command.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { "/$it" } ?: "Команда"
        else -> "Задача"
    }
}

private fun kindIcon(draft: HostingScheduleDraft): MillidaIcon = when {
    draft.steps > 0 -> MIcons.list
    draft.kind == "restart" -> MIcons.refresh
    draft.kind == "backup" -> MIcons.chest
    draft.kind == "stop" -> MIcons.power
    else -> MIcons.chat
}

private fun timeText(draft: HostingScheduleDraft): String =
    if (draft.everyMin > 0) "каждые ${draft.everyMin} мин" else "${twoDigits(draft.minute / 60)}:${twoDigits(draft.minute % 60)}"

private fun daysText(days: Int): String = when (days) {
    EVERY_DAY -> "каждый день"
    WEEKDAYS -> "по будням"
    WEEKEND -> "по выходным"
    0 -> "дни не выбраны"
    else -> DAY_NAMES.filterIndexed { index, _ -> (days and (1 shl index)) != 0 }.joinToString(", ")
}
