package net.millida.launcher.ui.hosting

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.millida.launcher.HostingBackupsState
import net.millida.launcher.HostingToolsModel
import net.millida.launcher.copyFailed
import net.millida.launcher.copyPending
import net.millida.launcher.core.hosting.HostingBackup
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.forServer
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPixelLoader
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.millida

internal data class HostingBackupsView(
    val state: HostingBackupsState?,
    val allowed: Boolean,
    val creating: Boolean,
    val restoringNow: Boolean,
)

@Stable
internal class HostingBackupsPicks {
    var menuFor by mutableStateOf<HostingBackup?>(null)
    var restoring by mutableStateOf<HostingBackup?>(null)
    var deleting by mutableStateOf<HostingBackup?>(null)
}

/** The desktop panel keeps backups under «Мир», so the world tab carries this section instead of a tab of its own. */
@Composable
internal fun rememberHostingBackups(serverId: String, tools: HostingToolsModel): HostingBackupsView {
    LaunchedEffect(serverId) { tools.openBackups(serverId) }
    val all by tools.backups.collectAsState()
    val busy by tools.busy.collectAsState()
    val rights by tools.rights.collectAsState()
    val access = rights.forServer(serverId)
    return HostingBackupsView(
        state = all.takeIf { it.serverId == serverId },
        allowed = access == null || access.can("backups"),
        creating = tools.key(serverId, "backups:create") in busy,
        restoringNow = tools.key(serverId, "backups:restore") in busy,
    )
}

internal fun LazyListScope.hostingBackupItems(
    serverId: String,
    tools: HostingToolsModel,
    view: HostingBackupsView,
    picks: HostingBackupsPicks,
) {
    if (!view.allowed) return
    val state = view.state
    item(key = "backups-head") {
        MCard {
            ToolsHead(
                title = tr("Резервные копии"),
                subtitle = state?.items?.let { list -> if (list.isEmpty()) tr("Пока ни одной") else tr("Всего: {size}", list.size) },
            )
            Spacer(Modifier.height(12.dp))
            MPrimaryButton(
                tr("Создать копию"),
                onClick = { tools.createBackup(serverId) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !view.restoringNow,
                icon = MIcons.plus,
                loading = view.creating,
            )
        }
    }
    state?.restoring?.let { job ->
        item(key = "backups-restoring") { ToolsJobCard(tr("Восстанавливаем из копии"), job) }
    }
    val list = state?.items
    val error = state?.error
    when {
        error != null && list == null -> item(key = "backups-error") {
            MErrorCard(error, onRetry = { tools.loadBackups(serverId) })
        }
        list == null -> items(3, key = { "backups-skeleton-$it" }) { MSkeletonRow() }
        list.isEmpty() -> item(key = "backups-empty") {
            MEmptyState(icon = MIcons.chest, title = tr("Копий пока нет"))
        }
        else -> {
            if (error != null) {
                item(key = "backups-error") { MErrorCard(error, onRetry = { tools.loadBackups(serverId) }) }
            }
            items(list, key = { "backup:" + it.id }) { backup ->
                val colors = millida
                MListRow(
                    title = backupTitle(backup),
                    modifier = Modifier.animateItem(),
                    subtitle = backupSubtitle(backup),
                    icon = MIcons.chest,
                    onClick = { picks.menuFor = backup },
                    trailing = when {
                        backup.copyPending -> {
                            { MPixelLoader() }
                        }
                        backup.copyFailed -> {
                            { MBadge(tr("не собралась"), colors.danger, colors.dangerSoft) }
                        }
                        backup.locked -> {
                            { MIcon(MIcons.lock, size = 18.dp, color = colors.fgFaint) }
                        }
                        else -> null
                    },
                )
            }
        }
    }
}

@Composable
internal fun HostingBackupDialogs(
    serverId: String,
    tools: HostingToolsModel,
    view: HostingBackupsView,
    picks: HostingBackupsPicks,
) {
    picks.menuFor?.let { backup ->
        MBottomSheet(onDismiss = { picks.menuFor = null }, title = backupTitle(backup)) {
            ToolsSheetAction(
                tr("Восстановить"),
                MIcons.refresh,
                enabled = !backup.copyPending && !backup.copyFailed && view.state?.restoring == null,
                onClick = {
                    picks.menuFor = null
                    picks.restoring = backup
                },
            )
            if (!backup.locked) {
                ToolsSheetAction(
                    tr("Удалить"),
                    MIcons.trash,
                    enabled = !backup.copyPending,
                    onClick = {
                        picks.menuFor = null
                        picks.deleting = backup
                    },
                )
            }
        }
    }

    picks.restoring?.let { backup ->
        MConfirmDialog(
            title = tr("Восстановить копию?"),
            text = tr("Мир и файлы заменятся копией от {0}. Сервер должен быть выключен", backupTitle(backup)),
            confirm = tr("Восстановить"),
            onConfirm = {
                picks.restoring = null
                tools.restoreBackup(serverId, backup)
            },
            onDismiss = { picks.restoring = null },
        )
    }

    picks.deleting?.let { backup ->
        MConfirmDialog(
            title = tr("Удалить копию?"),
            text = tr("Копию от {0} не вернуть", backupTitle(backup)),
            confirm = tr("Удалить"),
            onConfirm = {
                picks.deleting = null
                tools.deleteBackup(serverId, backup)
            },
            onDismiss = { picks.deleting = null },
        )
    }
}

@Composable
internal fun rememberHostingBackupPicks(serverId: String): HostingBackupsPicks = remember(serverId) { HostingBackupsPicks() }

private fun backupTitle(backup: HostingBackup): String = toolsMoment(backup.createdAt).ifEmpty { tr("Копия") }

private fun backupSubtitle(backup: HostingBackup): String {
    val size = if (backup.sizeMb > 0) toolsBytes(backup.sizeMb * 1024 * 1024) else ""
    val kind = when (backup.kind.lowercase()) {
        "manual" -> tr("вручную")
        "auto", "daily", "scheduled", "schedule" -> tr("автоматически")
        else -> ""
    }
    val status = when {
        backup.copyPending -> tr("собирается")
        backup.copyFailed -> tr("не собралась")
        else -> ""
    }
    val guard = if (backup.locked) tr("защищена") else ""
    return listOf(size, kind, status, guard).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { tr("копия сервера") }
}
