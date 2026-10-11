package net.millida.launcher.ui.character

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.MCounterBadge
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.rememberPressed

private val SECTION_GAP: Dp = 6.dp
private val SECTION_MIN_WIDTH: Dp = 64.dp
private val SECTION_HEIGHT: Dp = 64.dp

/**
 * The desktop's `.ch-secs`: sections share the row evenly as plates with the
 * icon over the name, and the row scrolls once they stop fitting.
 */
@Composable
internal fun <T> WideSectionBar(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    icon: (T) -> MillidaIcon,
    modifier: Modifier = Modifier,
    badge: (T) -> Int = { 0 },
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val count = tabs.size.coerceAtLeast(1)
        val share = (maxWidth - SECTION_GAP * (count - 1)) / count
        val cell = share.coerceAtLeast(SECTION_MIN_WIDTH)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SECTION_GAP),
        ) {
            tabs.forEach { tab ->
                SectionPlate(
                    text = label(tab),
                    icon = icon(tab),
                    picked = tab == selected,
                    badge = badge(tab),
                    onClick = { if (tab != selected) onSelect(tab) },
                    modifier = Modifier.width(cell),
                )
            }
        }
    }
}

@Composable
private fun SectionPlate(
    text: String,
    icon: MillidaIcon,
    picked: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (picked) null else UiSoundEvent.Nav)
    val ink = if (picked) colors.onAccent else colors.fgSubtle
    Column(
        modifier
            .heightIn(min = SECTION_HEIGHT)
            .then(
                if (picked) {
                    Modifier.pixelSurface(colors.accent, MillidaCut.control, PixelFrame.Button, dots = true)
                } else {
                    Modifier.pixelSurface(if (pressed) colors.inset else colors.surface2, MillidaCut.control)
                },
            )
            .selectable(picked, interaction, null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box {
            MIcon(icon, size = 24.dp, color = ink)
            MCounterBadge(
                badge,
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 10.dp, y = (-6).dp),
            )
        }
        Text(
            text,
            style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
