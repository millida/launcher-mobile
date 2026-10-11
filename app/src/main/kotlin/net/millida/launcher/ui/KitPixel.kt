package net.millida.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import net.millida.launcher.core.media.UiSoundEvent
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

private const val MIN_PRESS_MS = 90L

@Immutable
class StepsEasing(private val count: Int) : Easing {
    override fun transform(fraction: Float): Float {
        if (fraction >= 1f) return 1f
        val steps = max(1, count)
        return floor(fraction * steps) / steps
    }

    override fun equals(other: Any?): Boolean = other is StepsEasing && other.count == count

    override fun hashCode(): Int = count
}

enum class PixelCorners(val top: Boolean, val bottom: Boolean) {
    All(true, true),
    Top(true, false),
    Bottom(false, true),
    None(false, false),
}

/**
 * The step never eats more than a quarter of the short side: a 10 dp dot with a
 * 4 dp step would turn into a cross, with 2 dp it reads as a pixel circle.
 */
internal fun pixelStep(cutPx: Float, width: Float, height: Float): Float {
    val limit = floor(min(width, height) / 4f)
    return max(0f, min(cutPx, limit))
}

internal fun steppedPath(width: Float, height: Float, top: Float, bottom: Float, path: Path = Path()): Path {
    path.reset()
    path.moveTo(top, 0f)
    path.lineTo(width - top, 0f)
    path.lineTo(width - top, top)
    path.lineTo(width, top)
    path.lineTo(width, height - bottom)
    path.lineTo(width - bottom, height - bottom)
    path.lineTo(width - bottom, height)
    path.lineTo(bottom, height)
    path.lineTo(bottom, height - bottom)
    path.lineTo(0f, height - bottom)
    path.lineTo(0f, top)
    path.lineTo(top, top)
    path.close()
    return path
}

@Immutable
data class PixelCutShape(val cut: Dp, val corners: PixelCorners = PixelCorners.All) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val step = pixelStep(with(density) { cut.roundToPx() }.toFloat(), size.width, size.height)
        if (step <= 0f || corners == PixelCorners.None) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        val top = if (corners.top) step else 0f
        val bottom = if (corners.bottom) step else 0f
        return Outline.Generic(steppedPath(size.width, size.height, top, bottom))
    }
}

/**
 * The stepped frame: an outline and four faces, each a solid line of one
 * thickness along the whole silhouette. Drawn as bands that never overlap,
 * so half-transparent colours do not darken at the joints.
 */
@Immutable
data class PixelFrame(
    val outline: Color = Color.Transparent,
    val outlineWidth: Dp = 2.dp,
    val top: Color = Color.Transparent,
    val topWidth: Dp = 2.dp,
    val bottom: Color = Color.Transparent,
    val bottomWidth: Dp = 3.dp,
    val left: Color = Color.Transparent,
    val leftWidth: Dp = 3.dp,
    val right: Color = Color.Transparent,
    val rightWidth: Dp = 3.dp,
) {
    companion object {
        val None = PixelFrame()

        val Button = PixelFrame(
            outline = MillidaPixel.buttonOutline,
            top = MillidaPixel.buttonTop,
            topWidth = 3.dp,
            bottom = MillidaPixel.buttonBottom,
            bottomWidth = 5.dp,
            left = MillidaPixel.buttonLeft,
            right = MillidaPixel.buttonRight,
        )

        val ButtonPressed = PixelFrame(
            outline = MillidaPixel.pressOutline,
            top = MillidaPixel.pressTop,
            topWidth = 3.dp,
            bottom = MillidaPixel.pressBottom,
            bottomWidth = 2.dp,
        )

        val Danger = PixelFrame(
            top = MillidaPixel.dangerTop,
            topWidth = 3.dp,
            bottom = MillidaPixel.dangerBottom,
            bottomWidth = 5.dp,
        )

        val Chip = PixelFrame(
            top = MillidaPixel.chipOnTop,
            bottom = MillidaPixel.chipOnBottom,
        )

        val Tile = PixelFrame(
            top = MillidaPixel.tileTop,
            topWidth = 4.dp,
            bottom = MillidaPixel.tileBottom,
            bottomWidth = 5.dp,
        )

        fun running(accent: Color): PixelFrame = PixelFrame(
            outline = accent,
            bottom = MillidaPixel.runningBottom,
            bottomWidth = 5.dp,
        )
    }
}

fun MillidaColors.plateFrame(): PixelFrame = PixelFrame(top = plateTop, bottom = plateBottom)

fun MillidaColors.wellFrame(focused: Boolean = false): PixelFrame = if (focused) {
    PixelFrame(outline = accent, outlineWidth = 2.dp, top = wellTop, topWidth = 3.dp)
} else {
    PixelFrame(outline = wellOutline, outlineWidth = 1.dp, top = wellTop, topWidth = 3.dp)
}

private fun DrawScope.px(value: Dp): Float {
    if (value <= 0.dp) return 0f
    return max(1, value.roundToPx()).toFloat()
}

private fun DrawScope.drawBands(
    stepTop: Float,
    stepBottom: Float,
    topColor: Color,
    topWidth: Float,
    bottomColor: Color,
    bottomWidth: Float,
    leftColor: Color,
    leftWidth: Float,
    rightColor: Color,
    rightWidth: Float,
) {
    val w = size.width
    val h = size.height
    if (topColor.alpha > 0f && topWidth > 0f) {
        drawRect(topColor, Offset.Zero, Size(w, topWidth))
        if (stepTop > 0f) {
            drawRect(topColor, Offset(0f, topWidth), Size(stepTop + topWidth, stepTop))
            drawRect(topColor, Offset(w - stepTop - topWidth, topWidth), Size(stepTop + topWidth, stepTop))
        }
    }
    val sideTop = stepTop + topWidth
    val sideHeight = h - stepTop - stepBottom - topWidth - bottomWidth
    if (sideHeight > 0f) {
        if (leftColor.alpha > 0f && leftWidth > 0f) {
            drawRect(leftColor, Offset(0f, sideTop), Size(leftWidth, sideHeight))
        }
        if (rightColor.alpha > 0f && rightWidth > 0f) {
            drawRect(rightColor, Offset(w - rightWidth, sideTop), Size(rightWidth, sideHeight))
        }
    }
    if (bottomColor.alpha > 0f && bottomWidth > 0f) {
        if (stepBottom > 0f) {
            drawRect(bottomColor, Offset(0f, h - bottomWidth - stepBottom), Size(stepBottom + bottomWidth, stepBottom))
            drawRect(
                bottomColor,
                Offset(w - stepBottom - bottomWidth, h - bottomWidth - stepBottom),
                Size(stepBottom + bottomWidth, stepBottom),
            )
        }
        drawRect(bottomColor, Offset(0f, h - bottomWidth), Size(w, bottomWidth))
    }
}

fun DrawScope.drawPixelFrame(frame: PixelFrame, stepTop: Float, stepBottom: Float) {
    drawBands(
        stepTop, stepBottom,
        frame.top, px(frame.topWidth),
        frame.bottom, px(frame.bottomWidth),
        frame.left, px(frame.leftWidth),
        frame.right, px(frame.rightWidth),
    )
    val ring = px(frame.outlineWidth)
    if (frame.outline.alpha > 0f && ring > 0f) {
        drawBands(stepTop, stepBottom, frame.outline, ring, frame.outline, ring, frame.outline, ring, frame.outline, ring)
    }
}

fun DrawScope.drawPixelDots() {
    val side = px(2.dp)
    val xs = MillidaPixel.dotX
    val ys = MillidaPixel.dotY
    val colors = MillidaPixel.dotColors
    for (index in colors.indices) {
        val x = floor(size.width * xs[index] - side / 2f)
        val y = floor(size.height * ys[index] - side / 2f)
        drawRect(colors[index], Offset(x, y), Size(side, side))
    }
}

internal fun DrawScope.stepFor(cut: Dp): Float = pixelStep(cut.roundToPx().toFloat(), size.width, size.height)

fun Modifier.pixelSurface(
    fill: Color,
    cut: Dp = MillidaCut.control,
    frame: PixelFrame = PixelFrame.None,
    dots: Boolean = false,
    corners: PixelCorners = PixelCorners.All,
): Modifier = this
    .clip(PixelCutShape(cut, corners))
    .drawBehind {
        if (fill.alpha > 0f) drawRect(fill)
        if (dots) drawPixelDots()
        val step = stepFor(cut)
        drawPixelFrame(frame, if (corners.top) step else 0f, if (corners.bottom) step else 0f)
    }

fun Modifier.pixelFrame(
    frame: PixelFrame,
    cut: Dp = MillidaCut.control,
    corners: PixelCorners = PixelCorners.All,
): Modifier = drawBehind {
    val step = stepFor(cut)
    drawPixelFrame(frame, if (corners.top) step else 0f, if (corners.bottom) step else 0f)
}

/**
 * The hard unblurred sole under a floating tile: the silhouette itself, shifted
 * down. Goes before the clip, so it is not cut away with the plate.
 */
fun Modifier.pixelSole(
    lift: State<Float>,
    cut: Dp = MillidaCut.plate,
    rest: Dp = 5.dp,
    pressed: Dp = 2.dp,
    color: Color = MillidaPixel.sole,
): Modifier = drawBehind {
    val step = stepFor(cut)
    val drop = round(rest.toPx() + (pressed.toPx() - rest.toPx()) * lift.value)
    translate(top = drop) {
        drawPath(steppedPath(size.width, size.height, step, step), color)
    }
}

// Whole pixels keep the plate edges crisp.
fun Modifier.pressShift(progress: State<Float>, distance: Dp = MillidaMotion.pressOffset): Modifier =
    graphicsLayer { translationY = round(progress.value * distance.toPx()) }

// The flag outlives a quick tap by MIN_PRESS_MS so the press is always seen.
@Composable
internal fun rememberPressed(interaction: InteractionSource, sound: UiSoundEvent?): State<Boolean> {
    val feedback = LocalUiFeedback.current
    val pressed = remember(interaction) { mutableStateOf(false) }
    val currentSound by rememberUpdatedState(sound)
    LaunchedEffect(interaction, feedback) {
        var downAt = 0L
        interaction.interactions.collectLatest { event ->
            when (event) {
                is PressInteraction.Press -> {
                    downAt = SystemClock.uptimeMillis()
                    pressed.value = true
                    currentSound?.let { feedback.tap(it) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    val held = SystemClock.uptimeMillis() - downAt
                    if (held < MIN_PRESS_MS) delay(MIN_PRESS_MS - held)
                    pressed.value = false
                }
                else -> Unit
            }
        }
    }
    return pressed
}

@Composable
internal fun rememberPressProgress(
    pressed: Boolean,
    durationMs: Int = MillidaMotion.press,
    easing: Easing = LinearEasing,
): State<Float> = animateFloatAsState(
    targetValue = if (pressed) 1f else 0f,
    animationSpec = tween(durationMs, easing = easing),
    label = "press",
)
