package net.millida.launcher.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import net.millida.launcher.core.media.Screenshot
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.MLocalImage
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed

@Composable
internal fun ShotTile(shot: Screenshot, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Nav)
    val progress = rememberPressProgress(pressed, MillidaMotion.tilePress, MillidaMotion.steps(2))
    Box(
        modifier
            .aspectRatio(1f)
            .pressShift(progress)
            .pixelSurface(millida.surface2, MillidaCut.control)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Открыть снимок",
                role = Role.Button,
                onClick = onOpen,
            ),
    ) {
        MLocalImage(shot.path, Modifier.fillMaxSize())
    }
}
