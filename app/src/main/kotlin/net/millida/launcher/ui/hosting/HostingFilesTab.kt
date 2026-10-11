package net.millida.launcher.ui.hosting

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.HostingEditorState
import net.millida.launcher.HostingToolsModel
import net.millida.launcher.core.hosting.HostingFileEntry
import net.millida.launcher.forServer
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MDialog
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MPixelLoader
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.screenPadding

private sealed interface NameAsk {
    data object Folder : NameAsk

    data class Rename(val from: String) : NameAsk
}

@Composable
fun HostingFilesTab(serverId: String, tools: HostingToolsModel) {
    LaunchedEffect(serverId) { tools.openFiles(serverId) }
    val editor by tools.editor.collectAsState()
    val open = editor?.takeIf { it.serverId == serverId }
    if (open != null) {
        FileEditor(open, tools)
    } else {
        FileBrowser(serverId, tools)
    }
}

@Composable
private fun FileBrowser(serverId: String, tools: HostingToolsModel) {
    val all by tools.files.collectAsState()
    val transfer by tools.transfer.collectAsState()
    val busy by tools.busy.collectAsState()
    val rights by tools.rights.collectAsState()
    val access = rights.forServer(serverId)
    val full = access?.fullAccess ?: true
    val state = all.takeIf { it.serverId == serverId }
    val dir = state?.dir.orEmpty()
    val ownTransfer = transfer?.takeIf { it.serverId == serverId }

    var menuFor by remember { mutableStateOf<HostingFileEntry?>(null) }
    var naming by remember { mutableStateOf<NameAsk?>(null) }
    var deleting by remember { mutableStateOf<HostingFileEntry?>(null) }
    var unpacking by remember { mutableStateOf<HostingFileEntry?>(null) }
    var locked by remember { mutableStateOf<String?>(null) }
    var uploadDir by rememberSaveable { mutableStateOf("") }
    var downloadDir by rememberSaveable { mutableStateOf("") }
    var downloadName by rememberSaveable { mutableStateOf("") }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) tools.uploadFile(serverId, uploadDir, uri)
    }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val name = downloadName
        if (uri != null && name.isNotEmpty()) tools.downloadFile(serverId, downloadDir, name, uri)
    }

    val startUpload: () -> Unit = {
        if (full) {
            uploadDir = dir
            pickUpload.launch(arrayOf("*/*"))
        } else {
            locked = "Загрузка своих файлов"
        }
    }
    val openEntry: (HostingFileEntry) -> Unit = { entry ->
        val path = childPath(dir, entry.name)
        when {
            entry.dir -> tools.loadFiles(serverId, path)
            toolsIsText(entry.name) && entry.size <= TOOLS_EDIT_LIMIT -> tools.openEditor(serverId, path)
            toolsIsText(entry.name) -> tools.tell("Файл больше 512 КБ — скачай его, чтобы открыть", true)
            else -> {
                menuFor = entry
            }
        }
    }

    if (access != null && !access.can("files")) {
        ToolsList { item(key = "no-rights") { ToolsNoRights() } }
    } else {
        ToolsList {
            item(key = "path") {
                PathBar(
                    dir = dir,
                    onOpen = { tools.loadFiles(serverId, it) },
                    onRefresh = { tools.loadFiles(serverId, dir) },
                )
            }
            item(key = "actions") {
                MButtonRow {
                    if (full) {
                        MSecondaryButton(
                            "Папка",
                            onClick = { naming = NameAsk.Folder },
                            icon = MIcons.plus,
                            size = MButtonSize.Small,
                        )
                        MSecondaryButton(
                            "Загрузить",
                            onClick = startUpload,
                            icon = MIcons.upload,
                            size = MButtonSize.Small,
                            enabled = ownTransfer == null,
                        )
                    } else {
                        ToolsLockButton("Папка") { locked = "Свои папки" }
                        ToolsLockButton("Загрузить") { locked = "Загрузка своих файлов" }
                    }
                }
            }
            if (ownTransfer != null) {
                item(key = "transfer") { ToolsTransferCard(ownTransfer, onCancel = { tools.cancelTransfer() }) }
            }
            state?.extracting?.let { job ->
                item(key = "extract") { ToolsJobCard("Распаковываем ${job.name}", job) }
            }
            val entries = state?.entries
            val error = state?.error
            when {
                error != null && entries == null -> item(key = "error") {
                    MErrorCard(error, onRetry = { tools.loadFiles(serverId, dir) })
                }
                entries == null -> items(4, key = { "skeleton-$it" }) { MSkeletonRow() }
                entries.isEmpty() -> item(key = "empty") {
                    MEmptyState(
                        icon = MIcons.inbox,
                        title = "Папка пуста",
                        actionText = if (full) "Загрузить" else null,
                        onAction = if (full) startUpload else null,
                        actionIcon = MIcons.upload,
                        primary = false,
                    )
                }
                else -> {
                    if (error != null) {
                        item(key = "error") { MErrorCard(error, onRetry = { tools.loadFiles(serverId, dir) }) }
                    }
                    items(entries, key = { "entry:" + it.name }) { entry ->
                        val working = busy.any { it.startsWith(tools.key(serverId, "files:")) && it.endsWith(":" + entry.name) }
                        FileRow(
                            entry = entry,
                            working = working,
                            modifier = Modifier.animateItem(),
                            onOpen = { openEntry(entry) },
                            onMenu = { menuFor = entry },
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { entry ->
        MBottomSheet(onDismiss = { menuFor = null }, title = entry.name) {
            if (!entry.dir && toolsIsText(entry.name)) {
                ToolsSheetAction("Открыть", MIcons.edit, onClick = {
                    menuFor = null
                    openEntry(entry)
                })
            }
            if (!entry.dir) {
                ToolsSheetAction("Скачать", MIcons.download, locked = !full, enabled = ownTransfer == null, onClick = {
                    menuFor = null
                    if (full) {
                        downloadDir = dir
                        downloadName = entry.name
                        pickTarget.launch(entry.name)
                    } else {
                        locked = "Скачивание файлов"
                    }
                })
            }
            if (!entry.dir && toolsIsArchive(entry.name)) {
                ToolsSheetAction("Распаковать", MIcons.chest, locked = !full, onClick = {
                    menuFor = null
                    if (full) {
                        unpacking = entry
                    } else {
                        locked = "Распаковка архивов"
                    }
                })
            }
            ToolsSheetAction("Переименовать", MIcons.brush, locked = !full, onClick = {
                menuFor = null
                if (full) {
                    naming = NameAsk.Rename(entry.name)
                } else {
                    locked = "Переименование файлов"
                }
            })
            ToolsSheetAction("Удалить", MIcons.trash, onClick = {
                menuFor = null
                deleting = entry
            })
        }
    }

    naming?.let { ask ->
        NameDialog(
            ask = ask,
            working = tools.key(serverId, "files:mkdir") in busy ||
                (ask is NameAsk.Rename && tools.key(serverId, "files:rename:${ask.from}") in busy),
            onDismiss = { naming = null },
            onSubmit = { value ->
                when (ask) {
                    NameAsk.Folder -> tools.makeFolder(serverId, dir, value) { naming = null }
                    is NameAsk.Rename -> tools.renameEntry(serverId, dir, ask.from, value) { naming = null }
                }
            },
        )
    }

    deleting?.let { entry ->
        MConfirmDialog(
            title = "Удалить «${entry.name}»?",
            text = if (entry.dir) "Папка удалится со всем содержимым" else "Файл не вернуть",
            confirm = "Удалить",
            onConfirm = {
                deleting = null
                tools.deleteEntry(serverId, dir, entry)
            },
            onDismiss = { deleting = null },
        )
    }

    unpacking?.let { entry ->
        MConfirmDialog(
            title = "Распаковать «${entry.name}»?",
            text = "Файлы лягут в эту папку",
            confirm = "Распаковать",
            danger = false,
            onConfirm = {
                unpacking = null
                tools.extractEntry(serverId, dir, entry)
            },
            onDismiss = { unpacking = null },
        )
    }

    locked?.let { feature ->
        ToolsPlanDialog(feature, serverId, tools) { locked = null }
    }
}

private fun childPath(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

@Composable
private fun PathBar(dir: String, onOpen: (String) -> Unit, onRefresh: () -> Unit) {
    val parts = dir.split('/').filter { it.isNotEmpty() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        MIconButton(
            MIcons.arrowUp,
            onClick = { onOpen(parts.dropLast(1).joinToString("/")) },
            enabled = parts.isNotEmpty(),
            label = "Наверх",
        )
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState(), reverseScrolling = true),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MChip(text = "сервер", selected = parts.isEmpty(), onClick = { onOpen("") }, icon = MIcons.server)
            parts.forEachIndexed { index, part ->
                MChip(
                    text = part,
                    selected = index == parts.lastIndex,
                    onClick = { onOpen(parts.take(index + 1).joinToString("/")) },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        MIconButton(MIcons.refresh, onClick = onRefresh, label = "Обновить папку")
    }
}

@Composable
private fun FileRow(
    entry: HostingFileEntry,
    working: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    val moment = toolsMoment(entry.modTime)
    val subtitle = if (entry.dir) {
        "папка"
    } else {
        listOf(toolsBytes(entry.size), moment).filter { it.isNotEmpty() }.joinToString(" · ")
    }
    MListRow(
        title = entry.name,
        modifier = modifier,
        subtitle = subtitle,
        icon = when {
            entry.dir -> MIcons.folder
            toolsIsText(entry.name) -> MIcons.list
            toolsIsArchive(entry.name) -> MIcons.chest
            else -> MIcons.box
        },
        onClick = onOpen,
        trailing = {
            if (working) MPixelLoader()
            MIconButton(MIcons.more, onClick = onMenu, label = "Действия: ${entry.name}")
        },
    )
}

@Composable
private fun NameDialog(ask: NameAsk, working: Boolean, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var value by remember(ask) { mutableStateOf(if (ask is NameAsk.Rename) ask.from else "") }
    val rename = ask is NameAsk.Rename
    val ready = value.isNotBlank() && !working
    MDialog(
        title = if (rename) "Новое имя" else "Новая папка",
        onDismiss = onDismiss,
        body = {
            MField(
                value = value,
                onValueChange = { value = it },
                placeholder = if (rename) "Имя файла" else "Название папки",
                onImeAction = { if (ready) onSubmit(value) },
            )
        },
        actions = {
            MTextButton("Отмена", onClick = onDismiss)
            MPrimaryButton(
                if (rename) "Переименовать" else "Создать",
                onClick = { onSubmit(value) },
                enabled = value.isNotBlank(),
                loading = working,
            )
        },
    )
}

@Composable
private fun FileEditor(state: HostingEditorState, tools: HostingToolsModel) {
    val colors = millida
    var leaving by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (state.dirty) {
            leaving = true
        } else {
            tools.closeEditor()
        }
    }
    BackHandler(onBack = close)
    val error = state.error
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = screenPadding, end = screenPadding, top = 6.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIconButton(MIcons.back, onClick = close, label = "К файлам")
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.name,
                    style = MillidaType.rowTitle,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.dirty) "есть несохранённые правки" else state.path,
                    style = MillidaType.small,
                    color = if (state.dirty) colors.warning else colors.fgFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            MPrimaryButton(
                "Сохранить",
                onClick = { tools.saveEditor() },
                enabled = state.dirty,
                icon = MIcons.check,
                size = MButtonSize.Small,
                loading = state.saving,
            )
        }
        Spacer(Modifier.height(10.dp))
        when {
            state.loading -> MSkeleton(height = 280.dp)
            error != null -> MErrorCard(error, onRetry = { tools.retryEditor() })
            else -> ToolsCodeEditor(
                value = state.draft,
                onValueChange = { tools.editDraft(it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
    if (leaving) {
        MConfirmDialog(
            title = "Выйти без сохранения?",
            text = "Правки в «${state.name}» пропадут",
            confirm = "Выйти",
            onConfirm = {
                leaving = false
                tools.closeEditor()
            },
            onDismiss = { leaving = false },
        )
    }
}
