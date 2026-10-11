package net.millida.launcher.ui.hosting

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.HostingToolsModel
import net.millida.launcher.HostingTransfer
import net.millida.launcher.core.hosting.HOSTING_PERMISSIONS
import net.millida.launcher.core.hosting.HostingJob
import net.millida.launcher.ui.DISABLED_ALPHA
import net.millida.launcher.ui.MBusyCard
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MDialog
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPixelLoader
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MProgressBar
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.humanSize
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.screenPadding
import net.millida.launcher.ui.shotMoment
import net.millida.launcher.ui.wellFrame
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.roundToInt

internal const val TOOLS_EDIT_LIMIT = 512L * 1024

private val TEXT_EXTENSIONS = setOf(
    "txt", "yml", "yaml", "json", "json5", "properties", "conf", "cfg", "toml", "ini", "log", "md", "csv",
    "snbt", "mcmeta", "xml", "lang", "sk", "js", "sh", "env",
)

internal fun toolsIsText(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

internal fun toolsIsArchive(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
}

internal fun toolsMoment(iso: String): String {
    if (iso.isBlank()) return ""
    val millis = try {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (notOffset: Exception) {
        try {
            Instant.parse(iso).toEpochMilli()
        } catch (notInstant: Exception) {
            return ""
        }
    }
    return shotMoment(millis)
}

internal fun toolsBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 Б"
    bytes < 1024 -> "$bytes Б"
    else -> humanSize(bytes)
}

internal fun toolsRightsCount(count: Int): String {
    val tail = count % 100
    val word = when {
        tail in 11..14 -> "прав"
        count % 10 == 1 -> "право"
        count % 10 in 2..4 -> "права"
        else -> "прав"
    }
    return "$count $word"
}

internal val HostingJob.jobShare: Float?
    get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null

@Composable
internal fun ToolsList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = screenPadding, end = screenPadding, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
internal fun ToolsHead(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = millida
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MillidaType.sectionTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MillidaType.small,
                    color = colors.fgSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.width(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = action,
            )
        }
    }
}

@Composable
internal fun ToolsTransferCard(transfer: HostingTransfer, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    val share = transfer.share
    MCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MPixelLoader()
            Spacer(Modifier.width(10.dp))
            Text(
                transfer.title,
                modifier = Modifier.weight(1f),
                style = MillidaType.rowTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (share != null) {
                Spacer(Modifier.width(8.dp))
                Text("${(share * 100).roundToInt()}%", style = MillidaType.pixel, color = colors.accentText)
            }
        }
        Spacer(Modifier.height(12.dp))
        MProgressBar(share)
        val line = listOfNotNull(
            transfer.note.ifBlank { null },
            if (transfer.total > 0) "${toolsBytes(transfer.done)} из ${toolsBytes(transfer.total)}" else null,
        ).joinToString(" · ")
        if (line.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(line, style = MillidaType.small, color = colors.fgFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        MTextButton("Остановить", onClick = onCancel, icon = MIcons.close)
    }
}

@Composable
internal fun ToolsJobCard(label: String, job: HostingJob, modifier: Modifier = Modifier) {
    MBusyCard(
        label = label,
        progress = job.jobShare,
        modifier = modifier,
        note = if (job.total > 0) "${toolsBytes(job.bytes)} из ${toolsBytes(job.total)}" else null,
    )
}

@Composable
internal fun ToolsNoRights() {
    MEmptyState(icon = MIcons.lock, title = "Нет доступа к этому разделу")
}

@Composable
internal fun ToolsLockButton(text: String, onClick: () -> Unit) {
    MSecondaryButton(text, onClick = onClick, icon = MIcons.lock, size = MButtonSize.Small)
}

@Composable
internal fun ToolsPlanDialog(feature: String, serverId: String, tools: HostingToolsModel, onDismiss: () -> Unit) {
    val listeners by tools.planAskListeners.collectAsState()
    MDialog(
        title = "$feature — на платном тарифе",
        onDismiss = onDismiss,
        body = {
            Text(
                "Файлы, SFTP, свои миры и порты открываются на любом платном тарифе",
                style = MillidaType.body,
                color = millida.fgMuted,
            )
        },
        actions = {
            MTextButton("Закрыть", onClick = onDismiss)
            if (listeners > 0) {
                MPrimaryButton(
                    "Выбрать тариф",
                    onClick = {
                        tools.askForPlan(serverId)
                        onDismiss()
                    },
                )
            }
        },
    )
}

@Composable
internal fun ToolsSecretDialog(title: String, secret: String, onCopy: () -> Unit, onDone: () -> Unit) {
    val colors = millida
    MDialog(
        title = title,
        onDismiss = {},
        body = {
            Text("Сохрани сейчас — второй раз его не показать", style = MillidaType.body, color = colors.fgMuted)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .pixelSurface(colors.inset, MillidaCut.control, colors.wellFrame())
                    .padding(14.dp),
            ) {
                SelectionContainer {
                    Text(secret, style = MillidaType.pixel.copy(fontSize = 13.sp, lineHeight = 18.sp), color = colors.accentText)
                }
            }
        },
        actions = {
            MSecondaryButton("Скопировать", onClick = onCopy, icon = MIcons.copy)
            MPrimaryButton("Готово", onClick = onDone)
        },
    )
}

@Composable
internal fun ToolsSheetAction(
    title: String,
    icon: MillidaIcon,
    onClick: () -> Unit,
    locked: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = millida
    MListRow(
        title = title,
        modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
        icon = icon,
        onClick = if (enabled) onClick else null,
        plate = false,
        trailing = if (locked) {
            { MIcon(MIcons.lock, size = 18.dp, color = colors.fgFaint) }
        } else {
            null
        },
    )
}

@Composable
internal fun ToolsPermissionChips(selected: Set<String>, onToggle: (String) -> Unit) {
    MButtonRow {
        HOSTING_PERMISSIONS.forEach { (id, label) ->
            val on = id in selected
            MChip(
                text = label,
                selected = on,
                onClick = { onToggle(id) },
                icon = if (on) MIcons.check else null,
            )
        }
    }
}

@Composable
internal fun ToolsCodeEditor(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .pixelSurface(colors.inset, MillidaCut.control, colors.wellFrame(focused))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = colors.fg,
        ),
        interactionSource = interaction,
        cursorBrush = SolidColor(colors.accent),
    )
}

@Composable
internal fun rememberToolsCopy(tools: HostingToolsModel): (String, String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard, tools) {
        { label: String, text: String ->
            clipboard.setText(AnnotatedString(text))
            tools.tell("$label скопирован")
        }
    }
}

@Composable
internal fun rememberToolsSecretCopy(tools: HostingToolsModel): (String, String) -> Unit {
    val context = LocalContext.current
    return remember(context, tools) {
        { label: String, text: String ->
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            if (clipboard == null) {
                tools.tell("Буфер обмена недоступен — перепиши вручную", isError = true)
            } else {
                val clip = ClipData.newPlainText(label, text)
                clip.description.extras = PersistableBundle().apply { putBoolean(SENSITIVE_CLIP_EXTRA, true) }
                clipboard.setPrimaryClip(clip)
                tools.tell("$label скопирован")
            }
        }
    }
}

private val SENSITIVE_CLIP_EXTRA: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
