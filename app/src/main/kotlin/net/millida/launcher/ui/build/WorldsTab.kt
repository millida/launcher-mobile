package net.millida.launcher.ui.build

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.Area
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.game.WorldCard
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.shareFile
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSectionTitle
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.home.itemMotion
import net.millida.launcher.ui.humanSize
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.shotMoment
import java.io.File

private fun worldMode(world: WorldCard): String = when {
    world.hardcore -> "Хардкор"
    world.gameMode == 1 -> "Творческий"
    world.gameMode == 2 -> "Приключение"
    world.gameMode == 3 -> "Наблюдатель"
    else -> "Выживание"
}

internal fun LazyListScope.worldsTab(
    profile: Profile,
    state: UiState,
    model: LauncherViewModel,
    onImport: () -> Unit,
) {
    val worlds = state.buildWorlds.distinctBy { it.folder }
    val error = state.errorOf(Area.BUILD)
    val busy = state.busy != null
    when {
        worlds.isEmpty() && state.isLoading(Area.BUILD) ->
            items(2, key = { "worlds:skeleton:$it" }, contentType = { "skeleton" }) { MSkeletonRow() }

        worlds.isEmpty() && error != null -> item(key = "worlds:error", contentType = "error") {
            MErrorCard(error, onRetry = { model.retry(Area.BUILD) })
        }

        worlds.isEmpty() -> item(key = "worlds:empty", contentType = "empty") {
            MEmptyState(
                icon = MIcons.map,
                title = "Миров ещё нет",
                actionText = "Мир из файла",
                onAction = onImport,
                actionIcon = MIcons.folder,
                primary = false,
            )
        }

        else -> {
            item(key = "worlds:actions", contentType = "actions") {
                MButtonRow {
                    MSecondaryButton(
                        "Мир из файла",
                        onClick = onImport,
                        enabled = !busy,
                        icon = MIcons.folder,
                        size = MButtonSize.Small,
                    )
                }
            }
            if (error != null) {
                item(key = "worlds:error", contentType = "error") {
                    MErrorCard(error, onRetry = { model.retry(Area.BUILD) })
                }
            }
            items(worlds, key = { "world:${it.folder}" }, contentType = { "world" }) { world ->
                WorldRow(profile, world, state, model, itemMotion())
            }
        }
    }
}

@Composable
private fun WorldRow(
    profile: Profile,
    world: WorldCard,
    state: UiState,
    model: LauncherViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val context = LocalContext.current
    val busy = state.busy != null
    val title = world.name.ifEmpty { world.folder }
    var expanded by rememberSaveable(world.folder) { mutableStateOf(false) }
    var renaming by rememberSaveable(world.folder) { mutableStateOf(false) }
    var draft by rememberSaveable(world.folder) { mutableStateOf(world.name) }
    var deleting by rememberSaveable(world.folder) { mutableStateOf(false) }
    var restoring by rememberSaveable(world.folder) { mutableStateOf<String?>(null) }
    var dropping by rememberSaveable(world.folder) { mutableStateOf<String?>(null) }

    LaunchedEffect(world.folder, expanded) {
        if (expanded) model.loadWorldBackups(profile, world.folder)
    }

    MCard(modifier = modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .pixelSurface(colors.surface2, MillidaCut.control),
                contentAlignment = Alignment.Center,
            ) {
                MIcon(MIcons.grassBlock, size = 24.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    listOf(worldMode(world), world.version, humanSize(world.sizeBytes))
                        .filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    style = MillidaType.small,
                    color = colors.fgSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MIconButton(
                icon = if (expanded) MIcons.chevronUp else MIcons.chevronDown,
                onClick = { expanded = !expanded },
                label = if (expanded) "Свернуть" else "Действия с миром",
            )
            Spacer(Modifier.width(8.dp))
            MIconButton(
                icon = MIcons.trash,
                onClick = { deleting = true },
                enabled = !busy,
                label = "Удалить мир",
            )
        }

        if (!expanded) return@MCard

        Spacer(Modifier.height(12.dp))
        MButtonRow {
            MSecondaryButton(
                "Копия",
                onClick = { model.backupWorld(profile, world.folder) },
                enabled = !busy,
                icon = MIcons.copy,
                size = MButtonSize.Small,
            )
            MSecondaryButton(
                "Дубликат",
                onClick = { model.duplicateWorld(profile, world.folder) },
                enabled = !busy,
                icon = MIcons.plus,
                size = MButtonSize.Small,
            )
            MSecondaryButton(
                "Имя",
                onClick = { renaming = !renaming },
                icon = MIcons.edit,
                size = MButtonSize.Small,
            )
            MSecondaryButton(
                "Файлом",
                onClick = {
                    val target = File(context.cacheDir, "${world.folder}.zip")
                    model.exportWorldFile(profile, world.folder, target) { file ->
                        if (!shareFile(context, file, "application/zip", "Мир $title")) {
                            model.notice("Не нашлось приложения, чтобы отправить мир", isError = true)
                        }
                    }
                },
                enabled = !busy,
                icon = MIcons.share,
                size = MButtonSize.Small,
            )
        }

        if (renaming) {
            Spacer(Modifier.height(10.dp))
            MField(draft, { draft = it }, placeholder = "Название мира")
            Spacer(Modifier.height(8.dp))
            MSecondaryButton(
                "Сохранить",
                onClick = {
                    renaming = false
                    model.renameWorld(profile, world.folder, draft)
                },
                enabled = !busy && draft.isNotBlank(),
                size = MButtonSize.Small,
            )
        }

        val backups = state.worldBackups.filter { it.folder == world.folder }
        Spacer(Modifier.height(14.dp))
        MSectionTitle("КОПИИ МИРА")
        Spacer(Modifier.height(6.dp))
        if (backups.isEmpty()) {
            Text("Копий ещё нет", style = MillidaType.small, color = colors.fgSubtle)
        } else {
            backups.forEach { backup ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(shotMoment(backup.createdAt), style = MillidaType.body, color = colors.fg)
                        Text(humanSize(backup.sizeBytes), style = MillidaType.small, color = colors.fgFaint)
                    }
                    MSecondaryButton(
                        "Вернуть",
                        onClick = { restoring = backup.file },
                        enabled = !busy,
                        size = MButtonSize.Small,
                    )
                    Spacer(Modifier.width(8.dp))
                    MIconButton(
                        icon = MIcons.trash,
                        onClick = { dropping = backup.file },
                        enabled = !busy,
                        label = "Удалить копию",
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (deleting) {
        MConfirmDialog(
            title = "Удалить мир?",
            text = "«$title» удалится без возврата.",
            confirm = "Удалить",
            onConfirm = {
                deleting = false
                model.deleteWorld(world)
            },
            onDismiss = { deleting = false },
        )
    }
    restoring?.let { file ->
        MConfirmDialog(
            title = "Вернуть копию?",
            text = "Мир станет таким, каким был в копии.",
            confirm = "Вернуть",
            onConfirm = {
                restoring = null
                model.restoreWorld(profile, file)
            },
            onDismiss = { restoring = null },
        )
    }
    dropping?.let { file ->
        MConfirmDialog(
            title = "Удалить копию?",
            text = null,
            confirm = "Удалить",
            onConfirm = {
                dropping = null
                model.deleteWorldBackup(profile, file, world.folder)
            },
            onDismiss = { dropping = null },
        )
    }
}
