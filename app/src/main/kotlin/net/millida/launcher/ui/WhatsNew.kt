package net.millida.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import net.millida.launcher.core.update.ReleaseNotes
import net.millida.launcher.core.update.ReleaseNotesParser
import net.millida.launcher.core.update.UpdateRelease
import net.millida.launcher.ui.settings.rememberTrackedAction
import kotlin.math.roundToInt

/**
 * Shown once after an update, and only to somebody who had an older build: on a
 * first install there is nothing new to report yet.
 */
@Composable
fun WhatsNewDialog(version: String, notes: ReleaseNotes, onClose: () -> Unit) {
    val colors = millida
    MBottomSheet(onDismiss = onClose, title = "Что нового") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .pixelSurface(colors.accentSoft, MillidaCut.badge)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(version, style = MillidaType.pixel, color = colors.accentText, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            Text("Millida Mobile", style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
        }
        if (notes.intro.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(notes.intro, style = MillidaType.body, color = colors.fgMuted)
        }
        notes.sections.filter { it.items.isNotEmpty() }.forEach { section ->
            Spacer(Modifier.height(16.dp))
            MSectionTitle(section.title.uppercase())
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                section.items.forEach { item -> NoteRow(item) }
            }
        }
        Spacer(Modifier.height(20.dp))
        MPrimaryButton("Понятно", onClick = onClose, modifier = Modifier.fillMaxWidth(), size = MButtonSize.Large)
    }
}

@Composable
private fun NoteRow(text: String) {
    val colors = millida
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .pixelSurface(colors.accent, 1.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, modifier = Modifier.weight(1f), style = MillidaType.body, color = colors.fg)
    }
}

@Composable
fun UpdateOfferSheet(
    release: UpdateRelease,
    progress: Float,
    onUpdate: () -> Job,
    onLater: () -> Unit,
) {
    val colors = millida
    val download = rememberTrackedAction()
    val notes = ReleaseNotesParser.parse(release.notes)
    MBottomSheet(onDismiss = onLater, title = "Вышло обновление") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .pixelSurface(colors.accentSoft, MillidaCut.badge)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(release.version, style = MillidaType.pixel, color = colors.accentText, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            Text("Millida Mobile · ${humanSize(release.size)}", style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
        }
        if (notes.intro.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(notes.intro, style = MillidaType.body, color = colors.fgMuted)
        }
        notes.sections.filter { it.items.isNotEmpty() }.forEach { section ->
            Spacer(Modifier.height(16.dp))
            MSectionTitle(section.title.uppercase())
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                section.items.forEach { item -> NoteRow(item) }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (progress > 0f) {
            MProgressBar(progress)
            Spacer(Modifier.height(6.dp))
            Text("Качаем: ${(progress * 100).roundToInt()}%", style = MillidaType.small, color = colors.fgMuted)
        } else {
            MPrimaryButton(
                "Обновить",
                onClick = { download.start(onUpdate) },
                modifier = Modifier.fillMaxWidth(),
                icon = MIcons.download,
                size = MButtonSize.Large,
                loading = download.running,
            )
            Spacer(Modifier.height(8.dp))
            MSecondaryButton("Позже", onClick = onLater, modifier = Modifier.fillMaxWidth(), size = MButtonSize.Large)
        }
    }
}
