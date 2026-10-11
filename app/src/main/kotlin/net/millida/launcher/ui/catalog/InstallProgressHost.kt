package net.millida.launcher.ui.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.ui.MBusyCard
import net.millida.launcher.ui.screenPadding

/**
 * A pack started from a server card or the lobby downloads for minutes;
 * the catalogue shows its progress in its own list, every other screen here.
 */
@Composable
fun InstallProgressHost(screen: Screen, model: LauncherViewModel) {
    val catalog by model.catalogModel.state.collectAsStateWithLifecycle()
    val job = catalog.modpack ?: return
    if (screen == Screen.CATALOG) return
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = screenPadding, vertical = 8.dp),
    ) {
        MBusyCard(
            label = "Ставим «${job.title}»",
            progress = job.progress,
            note = job.stage,
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 520.dp),
        )
    }
}
