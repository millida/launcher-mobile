package net.millida.launcher.ui.hosting

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.millida.launcher.HostingToolsModel
import net.millida.launcher.copyFailed
import net.millida.launcher.copyPending
import net.millida.launcher.core.hosting.HostingBackup
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

@Composable
fun HostingBackupsTab(serverId: String, tools: HostingToolsModel) {
    LaunchedEffect(serverId) { tools.openBackups(serverId) }
    val colors = millida
    val all by tools.backups.collectAsState()
    val busy by tools.busy.collectAsState()
    val rights by tools.rights.collectAsState()
    val access = rights.forServer(serverId)
    val state = all.takeIf { it.serverId == serverId }
    val creating = tools.key(serverId, "backups:create") in busy
    val restoringNow = tools.key(serverId, "backups:restore") in busy

    var menuFor by remember { mutableStateOf<HostingBackup?>(null) }
    var restoring by remember { mutableStateOf<HostingBackup?>(null) }
    var deleting by remember { mutableStateOf<HostingBackup?>(null) }

    if (access != null && !access.can("backups")) {
        ToolsList { item(key = "no-rights") { ToolsNoRights() } }
    } else {
        ToolsList {
            item(key = "head") {
                MCard {
                    ToolsHead(
                        title = "Резервные копии",
                        subtitle = state?.items?.let { list -> if (list.isEmpty()) "Пока ни одной" else "Всего: ${list.size}" },
                    )
                    Spacer(Modifier.height(12.dp))
                    MPrimaryButton(
                        "Создать копию",
                        onClick = { tools.createBackup(serverId) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !restoringNow,
                        icon = MIcons.plus,
                        loading = creating,
                    )
                }
            }
            state?.restoring?.let { job ->
                item(key = "restoring") { ToolsJobCard("Восстанавливаем из копии", job) }
            }
            val list = state?.items
            val error = state?.error
            when {
                error != null && list == null -> item(key = "error") {
                    MErrorCard(error, onRetry = { tools.loadBackups(serverId) })
                }
                list == null -> items(3, key = { "skeleton-$it" }) { MSkeletonRow() }
                list.isEmpty() -> item(key = "empty") {
                    MEmptyState(icon = MIcons.chest, title = "Копий пока нет")
                }
                else -> {
                    if (error != null) {
                        item(key = "error") { MErrorCard(error, onRetry = { tools.loadBackups(serverId) }) }
                    }
                    items(list, key = { "backup:" + it.id }) { backup ->
                        MListRow(
                            title = backupTitle(backup),
                            modifier = Modifier.animateItem(),
                            subtitle = backupSubtitle(backup),
                            icon = MIcons.chest,
                            onClick = { menuFor = backup },
                            trailing = when {
                                backup.copyPending -> {
                                    { MPixelLoader() }
                                }
                                backup.copyFailed -> {
                                    { MBadge("не собралась", colors.danger, colors.dangerSoft) }
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
    }

    menuFor?.let { backup ->
        MBottomSheet(onDismiss = { menuFor = null }, title = backupTitle(backup)) {
            ToolsSheetAction(
                "Восстановить",
                MIcons.refresh,
                enabled = !backup.copyPending && !backup.copyFailed && state?.restoring == null,
                onClick = {
                    menuFor = null
                    restoring = backup
                },
            )
            if (!backup.locked) {
                ToolsSheetAction(
                    "Удалить",
                    MIcons.trash,
                    enabled = !backup.copyPending,
                    onClick = {
                        menuFor = null
                        deleting = backup
                    },
                )
            }
        }
    }

    restoring?.let { backup ->
        MConfirmDialog(
            title = "Восстановить копию?",
            text = "Мир и файлы заменятся копией от ${backupTitle(backup)}. Сервер должен быть выключен",
            confirm = "Восстановить",
            onConfirm = {
                restoring = null
                tools.restoreBackup(serverId, backup)
            },
            onDismiss = { restoring = null },
        )
    }

    deleting?.let { backup ->
        MConfirmDialog(
            title = "Удалить копию?",
            text = "Копию от ${backupTitle(backup)} не вернуть",
            confirm = "Удалить",
            onConfirm = {
                deleting = null
                tools.deleteBackup(serverId, backup)
            },
            onDismiss = { deleting = null },
        )
    }
}

private fun backupTitle(backup: HostingBackup): String = toolsMoment(backup.createdAt).ifEmpty { "Копия" }

private fun backupSubtitle(backup: HostingBackup): String {
    val size = if (backup.sizeMb > 0) toolsBytes(backup.sizeMb * 1024 * 1024) else ""
    val kind = when (backup.kind.lowercase()) {
        "manual" -> "вручную"
        "auto", "daily", "scheduled", "schedule" -> "автоматически"
        else -> ""
    }
    val status = when {
        backup.copyPending -> "собирается"
        backup.copyFailed -> "не собралась"
        else -> ""
    }
    val guard = if (backup.locked) "защищена" else ""
    return listOf(size, kind, status, guard).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "копия сервера" }
}
