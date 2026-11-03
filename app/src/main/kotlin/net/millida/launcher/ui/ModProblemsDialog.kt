package net.millida.launcher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.content.ModCompat
import net.millida.launcher.core.i18n.tr

/** Asked before a start that the game would refuse: the listed mods do not fit the build. */
@Composable
fun ModProblemsDialog(problems: List<ModCompat.Problem>, onDisable: () -> Unit, onPlay: () -> Unit, onCancel: () -> Unit) {
    MDialog(
        title = tr("Моды не подходят"),
        onDismiss = onCancel,
        body = {
            problems.take(SHOWN).forEach { problem ->
                Spacer(Modifier.height(6.dp))
                Text(problem.text(), style = MillidaType.small, color = millida.fgMuted)
            }
            if (problems.size > SHOWN) {
                Spacer(Modifier.height(6.dp))
                Text(tr("и ещё {0}", problems.size - SHOWN), style = MillidaType.small, color = millida.fgMuted)
            }
        },
        actions = {
            MTextButton(tr("Играть так"), onClick = onPlay)
            MPrimaryButton(tr("Выключить"), onClick = onDisable)
        },
    )
}

private const val SHOWN = 6
