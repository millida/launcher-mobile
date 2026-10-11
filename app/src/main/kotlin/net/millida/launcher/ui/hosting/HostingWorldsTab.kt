package net.millida.launcher.ui.hosting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import net.millida.launcher.HostingToolsModel
import net.millida.launcher.core.hosting.HostingWorld
import net.millida.launcher.forServer
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MDangerButton
import net.millida.launcher.ui.MDialog
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MSwitchRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida

@Composable
fun HostingWorldsTab(serverId: String, tools: HostingToolsModel) {
    LaunchedEffect(serverId) { tools.openWorlds(serverId) }
    val colors = millida
    val all by tools.worlds.collectAsState()
    val transfer by tools.transfer.collectAsState()
    val busy by tools.busy.collectAsState()
    val rights by tools.rights.collectAsState()
    val access = rights.forServer(serverId)
    val full = access?.fullAccess ?: true
    val running = access?.running ?: false
    val state = all.takeIf { it.serverId == serverId }
    val ownTransfer = transfer?.takeIf { it.serverId == serverId }
    val regenerating = tools.key(serverId, "worlds:regenerate") in busy

    var switching by remember { mutableStateOf<HostingWorld?>(null) }
    var regenerate by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf<String?>(null) }

    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) tools.uploadWorld(serverId, uri)
    }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri != null) tools.downloadWorld(serverId, uri)
    }

    if (access != null && !access.can("world")) {
        ToolsList { item(key = "no-rights") { ToolsNoRights() } }
    } else {
        ToolsList {
            if (ownTransfer != null) {
                item(key = "transfer") { ToolsTransferCard(ownTransfer, onCancel = { tools.cancelTransfer() }) }
            }
            item(key = "head") {
                MCard {
                    ToolsHead(
                        title = "Миры сервера",
                        subtitle = if (running) "Останови сервер, чтобы загрузить или пересоздать мир" else null,
                    )
                    Spacer(Modifier.height(12.dp))
                    MButtonRow {
                        if (full) {
                            MSecondaryButton(
                                "Загрузить",
                                onClick = { pickArchive.launch(arrayOf("*/*")) },
                                enabled = ownTransfer == null,
                                icon = MIcons.upload,
                                size = MButtonSize.Small,
                            )
                        } else {
                            ToolsLockButton("Загрузить") { locked = "Загрузка своего мира" }
                        }
                        MSecondaryButton(
                            "Скачать",
                            onClick = { pickTarget.launch(worldFileName(access?.slug.orEmpty())) },
                            enabled = ownTransfer == null,
                            icon = MIcons.download,
                            size = MButtonSize.Small,
                        )
                    }
                }
            }
            val list = state?.items?.sortedByDescending { it.active }
            val error = state?.error
            when {
                error != null && list == null -> item(key = "error") {
                    MErrorCard(error, onRetry = { tools.loadWorlds(serverId) })
                }
                list == null -> items(2, key = { "skeleton-$it" }) { MSkeletonRow() }
                list.isEmpty() -> item(key = "empty") {
                    MEmptyState(icon = MIcons.map, title = "Мир появится после первого запуска")
                }
                else -> {
                    if (error != null) {
                        item(key = "error") { MErrorCard(error, onRetry = { tools.loadWorlds(serverId) }) }
                    }
                    items(list, key = { "world:" + it.name }) { world ->
                        val switchingNow = tools.key(serverId, "worlds:switch:${world.name}") in busy
                        MListRow(
                            title = world.name,
                            modifier = Modifier.animateItem(),
                            subtitle = worldSubtitle(world),
                            icon = if (world.dimension) MIcons.flame else MIcons.grassBlock,
                            trailing = when {
                                world.active -> {
                                    { MBadge("Активен", colors.accentText, colors.accentSoft, icon = MIcons.check) }
                                }
                                world.dimension -> null
                                else -> {
                                    {
                                        MSecondaryButton(
                                            "Включить",
                                            onClick = { switching = world },
                                            size = MButtonSize.Small,
                                            loading = switchingNow,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
            item(key = "danger") {
                MCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Пересоздать мир", style = MillidaType.rowTitle, color = colors.fg)
                            Spacer(Modifier.height(2.dp))
                            Text("Моды и настройки останутся", style = MillidaType.small, color = colors.fgSubtle)
                        }
                        Spacer(Modifier.width(12.dp))
                        MDangerButton("Пересоздать", onClick = { regenerate = true }, enabled = !regenerating)
                    }
                }
            }
        }
    }

    switching?.let { world ->
        MConfirmDialog(
            title = "Включить «${world.name}»?",
            text = "Применится после перезапуска",
            confirm = "Включить",
            danger = false,
            onConfirm = {
                switching = null
                tools.switchWorld(serverId, world)
            },
            onDismiss = { switching = null },
        )
    }

    if (regenerate) {
        var backup by remember { mutableStateOf(true) }
        MDialog(
            title = "Пересоздать мир?",
            onDismiss = { regenerate = false },
            body = {
                Text(
                    "Текущий мир удалится безвозвратно. Моды, плагины и настройки останутся",
                    style = MillidaType.body,
                    color = colors.fgMuted,
                )
                Spacer(Modifier.height(8.dp))
                MSwitchRow("Сначала сохранить копию", null, backup) { backup = it }
            },
            actions = {
                MTextButton("Отмена", onClick = { regenerate = false })
                MDangerButton(
                    "Пересоздать",
                    onClick = {
                        regenerate = false
                        tools.regenerateWorld(serverId, backup)
                    },
                    size = MButtonSize.Medium,
                )
            },
        )
    }

    locked?.let { feature ->
        ToolsPlanDialog(feature, serverId, tools) { locked = null }
    }
}

private fun worldSubtitle(world: HostingWorld): String {
    val size = if (world.sizeMb > 0) toolsBytes(world.sizeMb * 1024 * 1024) else "пусто"
    return if (world.dimension) "$size · измерение" else size
}

private fun worldFileName(slug: String): String {
    val base = slug.replace(Regex("""[^A-Za-z0-9._-]"""), "-").trim('-').ifEmpty { "server" }
    return "$base-world.tar.gz"
}
