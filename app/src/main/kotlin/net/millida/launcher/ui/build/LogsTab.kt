package net.millida.launcher.ui.build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.Area
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.shareFile
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MillidaColors
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.PixelCorners
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.settings.BugSheet
import java.io.File

private val LOG_STYLE = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)

private fun logTone(line: String, colors: MillidaColors): Color {
    val trimmed = line.trimStart()
    return when {
        line.contains("ERROR") || line.contains("FATAL") || line.contains("Exception") ||
            trimmed.startsWith("at ") || trimmed.startsWith("Caused by") -> colors.danger
        line.contains("WARN") -> colors.warning
        else -> colors.fgMuted
    }
}

// One lazy row per log line: a single Text holding a long crash log stalls layout.
internal fun LazyListScope.logsTab(state: UiState, model: LauncherViewModel, lines: List<String>) {
    val text = state.buildLog.orEmpty()
    val error = state.errorOf(Area.BUILD)
    when {
        text.isBlank() && state.isLoading(Area.BUILD) -> item(key = "logs:loading", contentType = "loading") {
            Column(
                Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MSkeleton(height = 44.dp)
                MSkeleton(height = 160.dp)
            }
        }

        text.isBlank() && error != null -> item(key = "logs:error", contentType = "error") {
            MErrorCard(error, onRetry = { model.retry(Area.BUILD) }, modifier = Modifier.padding(top = 10.dp))
        }

        text.isBlank() -> item(key = "logs:empty", contentType = "empty") {
            MEmptyState(icon = MIcons.list, title = "Журнала ещё нет")
        }

        else -> {
            item(key = "logs:actions", contentType = "actions") { LogActions(text, model) }
            items(lines.size, key = { "log:$it" }, contentType = { "log" }) { index ->
                LogLine(lines[index], first = index == 0, last = index == lines.lastIndex)
            }
        }
    }
}

@Composable
private fun LogActions(text: String, model: LauncherViewModel) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val support by model.support.state.collectAsStateWithLifecycle()
    var reporting by rememberSaveable { mutableStateOf(false) }
    MButtonRow(Modifier.padding(top = 10.dp, bottom = 10.dp)) {
        MSecondaryButton(
            "Скопировать",
            onClick = {
                clipboard.setText(AnnotatedString(text))
                model.notice("Журнал скопирован")
            },
            icon = MIcons.copy,
            size = MButtonSize.Small,
        )
        MSecondaryButton(
            "Отправить",
            onClick = {
                scope.launch {
                    val file = File(context.cacheDir, "millida-log.txt")
                    val written = withContext(Dispatchers.IO) { runCatching { file.writeText(text) }.isSuccess }
                    when {
                        !written -> model.notice("Журнал не сохранился — освободи место и повтори", isError = true)
                        !shareFile(context, file, "text/plain", "Журнал Millida") ->
                            model.notice("Не нашлось приложения, чтобы отправить журнал", isError = true)
                    }
                }
            },
            icon = MIcons.share,
            size = MButtonSize.Small,
        )
        MSecondaryButton(
            "Сообщить об ошибке",
            onClick = { reporting = true },
            icon = MIcons.flag,
            size = MButtonSize.Small,
        )
    }
    if (reporting) {
        BugSheet(
            sending = support.sendingBug,
            onSend = { kind, report, attach, onSent -> model.support.sendBug(kind, report, attach, onSent) },
            onDismiss = { reporting = false },
        )
    }
}

@Composable
private fun LogLine(line: String, first: Boolean, last: Boolean) {
    val colors = millida
    val corners = when {
        first && last -> PixelCorners.All
        first -> PixelCorners.Top
        last -> PixelCorners.Bottom
        else -> PixelCorners.None
    }
    Text(
        line.ifEmpty { " " },
        modifier = Modifier
            .fillMaxWidth()
            .pixelSurface(colors.inset, MillidaCut.control, corners = corners)
            .padding(start = 12.dp, end = 12.dp, top = if (first) 10.dp else 0.dp, bottom = if (last) 12.dp else 0.dp),
        style = LOG_STYLE,
        color = logTone(line, colors),
    )
}
