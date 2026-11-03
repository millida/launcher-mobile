package net.millida.launcher.ui.build

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.profile.BuildFileEntry
import net.millida.launcher.core.profile.BuildFiles
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.shareFile
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MDangerButton
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPixelLoader
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.humanSize
import net.millida.launcher.ui.millida

/**
 * A plain browser over the build's folder: walk in and out, add a file from
 * the phone, open or send a file, remove what is not needed. Each listing is
 * numbered, so a slow answer for a folder already left does not replace the
 * one on screen.
 */
@Composable
internal fun BuildFilesSheet(profile: Profile, model: LauncherViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by rememberSaveable(profile.id) { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<BuildFileEntry>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<BuildFileEntry?>(null) }
    var deleting by remember { mutableStateOf<BuildFileEntry?>(null) }

    LaunchedEffect(profile.id, path, reload) {
        entries = null
        failure = null
        try {
            entries = model.buildFiles(profile, path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: tr("папка не прочиталась")
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val folder = path
        scope.launch {
            try {
                val added = model.importBuildFile(profile, folder, uri)
                model.notice(tr("Добавлен «{name}»", added.name))
                reload++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                model.notice(error.message ?: tr("файл не добавился"), isError = true)
            }
        }
    }

    MBottomSheet(onDismiss = onDismiss, title = tr("Файлы сборки")) {
        Text(
            "/" + path,
            style = MillidaType.small,
            color = millida.fgSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        MButtonRow {
            if (path.isNotEmpty()) {
                MSecondaryButton(tr("Наверх"), onClick = { path = BuildFiles.parent(path) }, icon = MIcons.arrowUp, size = MButtonSize.Small)
            }
            MSecondaryButton(tr("Добавить файл"), onClick = { importer.launch(arrayOf("*/*")) }, icon = MIcons.plus, size = MButtonSize.Small)
        }
        Spacer(Modifier.height(8.dp))
        val shown = entries
        when {
            failure != null -> Text(failure.orEmpty(), style = MillidaType.body, color = millida.danger)
            shown == null -> MPixelLoader()
            shown.isEmpty() -> MEmptyState(icon = MIcons.folder, title = tr("Папка пустая"))
            else -> shown.forEach { entry ->
                MListRow(
                    title = entry.name,
                    subtitle = if (entry.directory) tr("Папка") else humanSize(entry.sizeBytes),
                    icon = if (entry.directory) MIcons.folder else MIcons.book,
                    plate = false,
                    onClick = {
                        if (entry.directory) path = entry.path else picked = entry
                    },
                )
            }
        }
    }

    picked?.let { entry ->
        MBottomSheet(onDismiss = { picked = null }, title = entry.name) {
            Text(humanSize(entry.sizeBytes), style = MillidaType.small, color = millida.fgSubtle)
            Spacer(Modifier.height(10.dp))
            MButtonRow {
                MSecondaryButton(tr("Открыть"), onClick = {
                    picked = null
                    if (!openExternally(context, model, profile, entry)) {
                        model.notice(tr("Нет приложения, которое откроет «{name}»", entry.name), isError = true)
                    }
                }, icon = MIcons.externalLink, size = MButtonSize.Small)
                MSecondaryButton(tr("Отправить"), onClick = {
                    picked = null
                    val file = runCatching { model.buildFile(profile, entry.path) }.getOrNull()
                    if (file == null || !shareFile(context, file, mimeOf(entry.name), entry.name)) {
                        model.notice(tr("Не нашлось приложения, чтобы отправить файл"), isError = true)
                    }
                }, icon = MIcons.share, size = MButtonSize.Small)
            }
            Spacer(Modifier.height(10.dp))
            MDangerButton(tr("Удалить"), onClick = {
                picked = null
                deleting = entry
            }, icon = MIcons.trash)
            Spacer(Modifier.height(6.dp))
            MTextButton(tr("Отмена"), onClick = { picked = null })
        }
    }

    deleting?.let { entry ->
        MConfirmDialog(
            title = tr("Удалить «{name}»?", entry.name),
            text = tr("Файл удалится из сборки насовсем. Если это нужный мод или настройка, игра может перестать запускаться."),
            confirm = tr("Удалить"),
            onConfirm = {
                deleting = null
                scope.launch {
                    try {
                        model.deleteBuildFile(profile, entry.path)
                        reload++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        model.notice(error.message ?: tr("не удалилось"), isError = true)
                    }
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

private fun mimeOf(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "txt", "log", "cfg", "properties", "toml", "json5", "snbt", "mcmeta" -> "text/plain"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }
}

private fun openExternally(context: Context, model: LauncherViewModel, profile: Profile, entry: BuildFileEntry): Boolean {
    val file = runCatching { model.buildFile(profile, entry.path) }.getOrNull() ?: return false
    val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }.getOrNull() ?: return false
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeOf(entry.name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(Intent.createChooser(intent, entry.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
