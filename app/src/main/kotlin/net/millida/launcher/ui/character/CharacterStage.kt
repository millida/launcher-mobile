package net.millida.launcher.ui.character

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.LocalUiFeedback
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.SkinPose
import net.millida.launcher.ui.SkinStage
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import kotlin.math.roundToInt

private val POSES: List<Pair<SkinPose, String>> = listOf(
    SkinPose.Idle to "Стоит",
    SkinPose.Walk to "Идёт",
    SkinPose.Wave to "Машет",
)

@Composable
internal fun CharacterStage(
    nick: String,
    skinUrl: String?,
    slim: Boolean,
    capeUrl: String?,
    pose: SkinPose,
    trying: Boolean,
    onPose: (SkinPose) -> Unit,
    modifier: Modifier = Modifier,
    outfit: StageOutfit? = null,
) {
    val colors = millida
    val feedback = LocalUiFeedback.current
    Box(
        modifier
            .fillMaxWidth()
            .pixelSurface(colors.surface, MillidaCut.plate, colors.plateFrame()),
    ) {
        SkinStage(
            skinUrl = skinUrl,
            slim = slim,
            modifier = Modifier.fillMaxSize(),
            capeUrl = capeUrl,
            pose = pose,
            interactive = true,
            onTap = { feedback.tap(UiSoundEvent.Toggle) },
            hopKey = if (trying) "$skinUrl|$capeUrl" else null,
            outfit = outfit,
        )
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            POSES.forEach { (value, label) ->
                MChip(label, selected = pose == value, onClick = { onPose(value) })
            }
        }
        if (trying) {
            MBadge(
                "Примерка",
                color = colors.info,
                background = colors.infoSoft,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                icon = MIcons.eye,
            )
        }
    }
}

/** The height is read at layout, so a collapsing stage never recomposes the screen on every scroll pixel. */
internal fun Modifier.collapsingHeight(height: State<Float>, min: Float, max: Float): Modifier =
    layout { measurable, constraints ->
        val wanted = height.value.coerceIn(min, max).roundToInt()
        val fitted = wanted.coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minHeight = fitted, maxHeight = fitted))
        layout(placeable.width, fitted) { placeable.place(0, 0) }
    }

/**
 * The stage gives up height before the list scrolls and takes it back once
 * the list is at its top again, so the figure stays in view while browsing.
 */
internal class StageCollapse(
    private val height: MutableFloatState,
    private val min: Float,
    private val max: Float,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y >= 0f) return Offset.Zero
        return Offset(0f, resize(available.y))
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (available.y <= 0f) return Offset.Zero
        return Offset(0f, resize(available.y))
    }

    private fun resize(delta: Float): Float {
        val current = height.floatValue.coerceIn(min, max)
        val next = (current + delta).coerceIn(min, max)
        height.floatValue = next
        return next - current
    }
}
