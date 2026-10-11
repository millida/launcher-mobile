package net.millida.launcher.ui.build

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.MemoryTuning
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.VersionManifest
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MDangerButton
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MInfoRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSectionTitle
import net.millida.launcher.ui.MSegmented
import net.millida.launcher.ui.MSlider
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.home.LoaderChip
import net.millida.launcher.ui.home.LoaderGate
import net.millida.launcher.ui.home.LobbyState
import net.millida.launcher.ui.home.loaderGate
import net.millida.launcher.ui.home.playedLabel
import net.millida.launcher.ui.home.playedSeconds
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.shotMoment
import kotlin.math.roundToInt

private const val MEMORY_FLOOR = 512
private const val MIGRATE_PREVIEW = 6

internal fun LazyListScope.optionsTab(
    profile: Profile,
    state: UiState,
    lobby: LobbyState,
    model: LauncherViewModel,
    onPickCover: () -> Unit,
) {
    item(key = "options:main", contentType = "card") { MainCard(profile, state, lobby, model, onPickCover) }
    item(key = "options:game", contentType = "card") { GameCard(profile, state, model) }
    val packUpdate = model.packUpdates.state.value
    val auto = packUpdate.auto?.takeIf { it.catalog && packUpdate.profileId == profile.id }
    if (auto != null) {
        item(key = "options:pack-auto", contentType = "card") {
            PackAutoUpdateCard(auto) { on -> model.packUpdates.setAuto(profile, on) }
        }
    }
    item(key = "options:played", contentType = "card") { PlayedCard(profile, state, lobby, model) }
    item(key = "options:migrate", contentType = "card") { MigrateCard(profile, state, model) }
    item(key = "options:repair", contentType = "card") { RepairCard(profile, state, model) }
    item(key = "options:delete", contentType = "card") { DeleteCard(profile, model) }
}

@Composable
private fun MainCard(profile: Profile, state: UiState, lobby: LobbyState, model: LauncherViewModel, onPickCover: () -> Unit) {
    val colors = millida
    val busy = state.busy != null
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    val savedGroup = (state.buildMeta?.group ?: lobby.metas[profile.id]?.group).orEmpty().trim()
    var group by remember(profile.id, savedGroup) { mutableStateOf(savedGroup) }
    val hasCover = !lobby.covers[profile.id].isNullOrBlank()
    val nameChanged = name.trim().isNotEmpty() && name.trim() != profile.name
    val groupChanged = group.trim() != savedGroup

    MCard {
        OptionField("НАЗВАНИЕ") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MField(name, { name = it }, placeholder = "Название сборки", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                MIconButton(
                    icon = MIcons.check,
                    onClick = { model.renameProfile(profile, name) },
                    tint = colors.accentText,
                    enabled = nameChanged,
                    label = "Сохранить название",
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        OptionField("ГРУППА НА ГЛАВНОЙ") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MField(group, { group = it }, placeholder = "Без группы", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                MIconButton(
                    icon = MIcons.check,
                    onClick = { model.setBuildGroup(profile, group) },
                    tint = colors.accentText,
                    enabled = groupChanged && !busy,
                    label = "Сохранить группу",
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        OptionField("ОБЛОЖКА") {
            MButtonRow {
                MSecondaryButton(
                    if (hasCover) "Сменить" else "Выбрать",
                    onClick = onPickCover,
                    icon = MIcons.image,
                    size = MButtonSize.Small,
                    loading = lobby.coverBusy,
                )
                if (hasCover) {
                    MTextButton("Убрать", onClick = { model.lobby.clearCover(profile, state.profiles) })
                }
            }
        }
    }
}

private const val OPTION_CONTROL_SHARE = 0.6f
private val OPTION_CONTROL_MAX = 360.dp

/**
 * One setting. On a wide window it is the desktop's `.set-row`: the caption on
 * the left and the control at the right end of the same line.
 */
@Composable
private fun OptionField(title: String, control: @Composable () -> Unit) {
    if (!LocalWideLayout.current) {
        MSectionTitle(title)
        Spacer(Modifier.height(8.dp))
        control()
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MSectionTitle(title, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .fillMaxWidth(OPTION_CONTROL_SHARE)
                .widthIn(max = OPTION_CONTROL_MAX),
        ) { control() }
    }
}

@Composable
private fun GameCard(profile: Profile, state: UiState, model: LauncherViewModel) {
    val colors = millida
    val memoryValue: @Composable () -> Unit = {
        Text(
            if (profile.memoryMb > 0) "${profile.memoryMb} МБ" else "Авто",
            style = MillidaType.pixel,
            color = colors.accentText,
            maxLines = 1,
        )
    }
    val memoryControl: @Composable () -> Unit = {
        MSlider(
            value = profile.memoryMb.toFloat().coerceIn(0f, state.memoryCeilingMb.toFloat()),
            onValueChange = { value ->
                val snapped = (value / MemoryTuning.SETTING_STEP_MB).roundToInt() * MemoryTuning.SETTING_STEP_MB
                model.setProfileMemory(profile, if (snapped in 1 until MEMORY_FLOOR) MEMORY_FLOOR else snapped)
            },
            valueRange = 0f..state.memoryCeilingMb.toFloat(),
            steps = state.memoryCeilingMb / MemoryTuning.SETTING_STEP_MB - 1,
        )
        Text("Ноль — подберёт лаунчер", style = MillidaType.small, color = colors.fgFaint)
    }
    MCard {
        if (LocalWideLayout.current) {
            OptionField("ПАМЯТЬ") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        memoryValue()
                    }
                    memoryControl()
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MSectionTitle("ПАМЯТЬ", Modifier.weight(1f))
                memoryValue()
            }
            memoryControl()
        }

        if (state.renderers.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            val options = listOf("") + state.renderers.map { it.id }.distinct()
            val selected = profile.rendererId.takeIf { it in options } ?: ""
            val label: (String) -> String = { id ->
                if (id.isEmpty()) {
                    "Общий"
                } else {
                    state.renderers.firstOrNull { it.id == id }?.label?.ifEmpty { id } ?: id
                }
            }
            OptionField("РЕНДЕРЕР") {
                if (options.size <= 3) {
                    MSegmented(
                        options = options,
                        selected = selected,
                        onSelect = { model.setProfileRenderer(profile, it) },
                        label = label,
                    )
                } else {
                    MButtonRow {
                        options.forEach { id ->
                            MChip(
                                text = label(id),
                                selected = id == selected,
                                onClick = { model.setProfileRenderer(profile, id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayedCard(profile: Profile, state: UiState, lobby: LobbyState, model: LauncherViewModel) {
    val played = state.playStats?.builds?.firstOrNull { it.profileId == profile.id }
    val seconds = playedSeconds(profile, lobby)
    MCard {
        MSectionTitle("НАИГРАНО")
        Spacer(Modifier.height(6.dp))
        MInfoRow("Время в игре", if (seconds > 0) playedLabel(seconds) else "Пока ничего")
        if (played != null) {
            MInfoRow("Запусков", played.sessions.toString(), mono = true)
            if (played.lastAt > 0) MInfoRow("Последний раз", shotMoment(played.lastAt * 1000))
        }
        val folder = remember(profile.id) { model.profileDirectory(profile) }
        MInfoRow("Папка", folder)
    }
}

@Composable
private fun MigrateCard(profile: Profile, state: UiState, model: LauncherViewModel) {
    val colors = millida
    val busy = state.busy != null
    var version by rememberSaveable(profile.id) { mutableStateOf("") }
    var loader by rememberSaveable(profile.id) { mutableStateOf(profile.loader) }
    val target = version.trim()
    val entry = state.versions.firstOrNull { it.id == target }
        ?: target.takeIf { it.isNotEmpty() }?.let { VersionManifest.ManifestEntry(id = it, url = "") }
    val ready = target.isNotEmpty() && loaderGate(loader, entry) == LoaderGate.Ready
    var plannedFor by rememberSaveable(profile.id) { mutableStateOf("") }
    val planKey = "$target|${loader.name}"
    val plan = state.migratePlan?.takeIf { plannedFor == planKey }

    MCard {
        MSectionTitle("ПЕРЕНОС НА ДРУГУЮ ВЕРСИЮ")
        Spacer(Modifier.height(4.dp))
        Text("Соберём копию — эта сборка не изменится", style = MillidaType.small, color = colors.fgSubtle)
        Spacer(Modifier.height(10.dp))
        MField(version, { version = it }, placeholder = "Версия игры, например 1.21.4")
        Spacer(Modifier.height(8.dp))
        MButtonRow {
            LoaderKind.entries.forEach { kind ->
                LoaderChip(
                    kind = kind,
                    selected = kind == loader,
                    unsupported = entry != null && loaderGate(kind, entry) == LoaderGate.Unsupported,
                    onClick = { loader = kind },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MButtonRow {
            MSecondaryButton(
                "Посчитать",
                onClick = {
                    plannedFor = planKey
                    model.planMigration(profile, target, loader)
                },
                enabled = ready && !busy,
                icon = MIcons.search,
                size = MButtonSize.Small,
            )
            if (plan != null) {
                MPrimaryButton(
                    "Перенести",
                    onClick = { model.migrateBuild(profile, target, loader) },
                    enabled = ready && !busy,
                    size = MButtonSize.Small,
                )
            }
        }
        if (plan != null && plan.items.isEmpty() && plan.unlinked.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Модов нет — переедут миры и настройки", style = MillidaType.small, color = colors.fgSubtle)
        } else if (plan != null) {
            Spacer(Modifier.height(10.dp))
            MButtonRow {
                MBadge("Переедет: ${plan.ready}", colors.accentText, colors.accentSoft)
                if (plan.missing > 0) MBadge("Не нашлось: ${plan.missing}", colors.danger, colors.dangerSoft)
            }
            plan.items.filterNot { it.ok }.take(MIGRATE_PREVIEW).forEach { item ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "${item.title}: ${item.note}",
                    style = MillidaType.small,
                    color = colors.fgFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (plan.unlinked.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Останутся здесь: ${plan.unlinked.take(4).joinToString(", ")}",
                    style = MillidaType.small,
                    color = colors.fgFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RepairCard(profile: Profile, state: UiState, model: LauncherViewModel) {
    val busy = state.busy != null
    MCard {
        MSectionTitle("КОПИЯ И ПОЧИНКА")
        Spacer(Modifier.height(10.dp))
        MButtonRow {
            MSecondaryButton(
                "Копия",
                onClick = { model.duplicateBuild(profile, true) },
                enabled = !busy,
                icon = MIcons.copy,
                size = MButtonSize.Small,
            )
            MSecondaryButton(
                "Копия без модов",
                onClick = { model.duplicateBuild(profile, false) },
                enabled = !busy,
                icon = MIcons.copy,
                size = MButtonSize.Small,
            )
            MSecondaryButton(
                "Починить",
                onClick = { model.repairBuild(profile) },
                enabled = !busy,
                icon = MIcons.shield,
                size = MButtonSize.Small,
            )
        }
    }
}

@Composable
private fun DeleteCard(profile: Profile, model: LauncherViewModel) {
    var confirming by rememberSaveable(profile.id) { mutableStateOf(false) }
    MCard {
        MSectionTitle("УДАЛЕНИЕ")
        Spacer(Modifier.height(10.dp))
        MDangerButton(
            "Удалить сборку",
            onClick = { confirming = true },
            icon = MIcons.trash,
            size = MButtonSize.Medium,
        )
    }
    if (confirming) {
        MConfirmDialog(
            title = "Удалить сборку?",
            text = "«${profile.name}» удалится с модами и мирами. Вернуть нельзя.",
            confirm = "Удалить",
            onConfirm = {
                confirming = false
                model.deleteProfile(profile.id)
            },
            onDismiss = { confirming = false },
        )
    }
}
