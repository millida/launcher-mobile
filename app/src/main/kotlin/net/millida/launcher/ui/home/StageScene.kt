package net.millida.launcher.ui.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.mixSrgb
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round

/**
 * The colours of the desktop stage (stageArt.ts makePal): the light behind the
 * hero is the button colour lifted towards white, the edges sink towards black,
 * so the scene follows whatever colour the player picked for the buttons.
 */
@Immutable
internal class StagePalette(
    val core: Color,
    val mid: Color,
    val edge: Color,
    val deep: Color,
    val void: Color,
)

internal fun stagePalette(accent: Color): StagePalette = StagePalette(
    core = accent.mixSrgb(MillidaPixel.white, 0.68f),
    mid = accent.mixSrgb(MillidaPixel.stageInk, 0.82f),
    edge = accent.mixSrgb(MillidaPixel.stageInk, 0.66f),
    deep = accent.mixSrgb(MillidaPixel.stageInk, 0.5f),
    void = accent.mixSrgb(MillidaPixel.stageInk, 0.34f),
)

/**
 * The faint pixel squares of the desktop stage, laid out by the same seeded
 * generator (paintScene), so the pattern holds still between starts. Four
 * floats per square: x and y in dp, its side in dp and its alpha.
 */
internal object StagePixels {
    const val STRIDE = 4
    private const val SEED = 1337L
    private const val MULTIPLIER = 16807L
    private const val MODULUS = 2147483647L
    private const val AREA_PER_SQUARE_DP = 26_000f
    private const val SIDE_DP = 10f
    private const val SIDE_STEP_DP = 7f
    private const val SIDE_STEPS = 3
    private const val ALPHA_MIN = 0.05f
    private const val ALPHA_SPREAD = 0.07f
    private const val NEAR_FLOOR = 0.4f
    private const val NEAR_REACH = 1.1f

    fun count(widthDp: Float, heightDp: Float): Int =
        if (widthDp <= 0f || heightDp <= 0f) 0 else round(widthDp * heightDp / AREA_PER_SQUARE_DP).toInt()

    fun layout(widthDp: Float, heightDp: Float): FloatArray {
        val count = count(widthDp, heightDp)
        val out = FloatArray(count * STRIDE)
        val cx = widthDp / 2f
        val cy = heightDp / 2f
        val reach = hypot(widthDp, heightDp) * RAY_REACH * NEAR_REACH
        var seed = SEED
        for (index in 0 until count) {
            seed = seed * MULTIPLIER % MODULUS
            val x = seed.toFloat() / MODULUS * widthDp
            seed = seed * MULTIPLIER % MODULUS
            val y = seed.toFloat() / MODULUS * heightDp
            seed = seed * MULTIPLIER % MODULUS
            val side = SIDE_DP + floor(seed.toDouble() / MODULUS * SIDE_STEPS).toFloat() * SIDE_STEP_DP
            seed = seed * MULTIPLIER % MODULUS
            val near = max(NEAR_FLOOR, 1f - hypot(x - cx, y - cy) / reach)
            val alpha = (ALPHA_MIN + seed.toFloat() / MODULUS * ALPHA_SPREAD) * near
            val at = index * STRIDE
            out[at] = x
            out[at + 1] = y
            out[at + 2] = side
            out[at + 3] = alpha
        }
        return out
    }
}

private const val RAY_REACH = 0.55f
private const val RAYS = 14
private const val RAY_TURN_DEG = 6.3025f
private const val RAY_SWEEP_DEG = 180f / RAYS * 0.9f
private const val RAY_CORE_ALPHA = 0.07f
private const val RAY_MID_ALPHA = 0.03f
private const val RAY_OUTER = 0.78f
private const val RAY_INNER_STOP = 0.06f / RAY_OUTER
private const val RAY_MID_STOP = (0.06f + 0.5f * (RAY_OUTER - 0.06f)) / RAY_OUTER
private const val GLOW_REACH = 0.42f
private const val FLOOR_FROM = 0.7f
private const val FLOOR_ALPHA = 0.28f

/**
 * The desktop lobby's background (PixelField with stageArt.paintScene): a
 * smooth light from the bright centre behind the hero to the deep colour at
 * the edges, fourteen soft rays, the rare pixel squares, a pool of light with
 * a soft shadow where the feet stand [feet] above the bottom and [figureX]
 * from the start edge, and the floor
 * darkening under the buttons. Everything is built once per size and colour,
 * drawing only replays it.
 */
internal fun Modifier.stageScene(accent: Color, feet: Dp, figureX: Dp): Modifier = drawWithCache {
    val width = size.width
    val height = size.height
    val palette = stagePalette(accent)
    val feetX = if (layoutDirection == LayoutDirection.Ltr) figureX.toPx() else width - figureX.toPx()
    // The light stands behind the hero, who stands in the middle of the stage
    // between the rails rather than in the middle of the window.
    val centre = Offset(feetX, height / 2f)
    val diagonal = hypot(width, height)
    val glow = Brush.radialGradient(
        0f to palette.core,
        0.16f to palette.core,
        0.4f to palette.mid,
        0.62f to palette.edge,
        0.82f to palette.deep,
        1f to palette.void,
        center = centre,
        radius = max(diagonal * GLOW_REACH, 1f),
    )
    val reach = max(diagonal * RAY_REACH, 1f)
    val rayLight = MillidaPixel.white
    val rayBrush = Brush.radialGradient(
        0f to rayLight.copy(alpha = RAY_CORE_ALPHA),
        RAY_INNER_STOP to rayLight.copy(alpha = RAY_CORE_ALPHA),
        RAY_MID_STOP to rayLight.copy(alpha = RAY_MID_ALPHA),
        1f to rayLight.copy(alpha = 0f),
        center = centre,
        radius = reach * RAY_OUTER,
    )
    val rays = Path()
    val bounds = Rect(centre.x - reach, centre.y - reach, centre.x + reach, centre.y + reach)
    for (ray in 0 until RAYS) {
        rays.moveTo(centre.x, centre.y)
        rays.arcTo(bounds, ray * 360f / RAYS + RAY_TURN_DEG, RAY_SWEEP_DEG, false)
        rays.close()
    }
    val unit = density
    val pixels = StagePixels.layout(width / unit, height / unit)
    val feetY = height - feet.toPx()
    val pool = FloorGlow(
        centre = Offset(feetX, feetY + 2f * unit),
        radiusX = width * 0.2f,
        radiusY = height * 0.05f,
        brush = ellipseBrush(Offset(feetX, feetY + 2f * unit), width * 0.2f, rayLight, 0.16f, 0.6f to 0.07f),
    )
    val shade = FloorGlow(
        centre = Offset(feetX, feetY + 4f * unit),
        radiusX = width * 0.085f,
        radiusY = height * 0.02f,
        brush = ellipseBrush(Offset(feetX, feetY + 4f * unit), width * 0.085f, MillidaPixel.stageFeetShade, 0.38f, 0.55f to 0.2f),
    )
    val floorTop = height * FLOOR_FROM
    val floor = Brush.verticalGradient(
        0f to MillidaPixel.stageInk.copy(alpha = 0f),
        1f to MillidaPixel.stageInk.copy(alpha = FLOOR_ALPHA),
        startY = floorTop,
        endY = height,
    )
    onDrawBehind {
        drawRect(glow)
        drawPath(rays, rayBrush)
        var at = 0
        while (at < pixels.size) {
            val side = round(pixels[at + 2] * unit)
            drawRect(
                rayLight.copy(alpha = pixels[at + 3]),
                Offset(round(pixels[at] * unit), round(pixels[at + 1] * unit)),
                Size(side, side),
            )
            at += StagePixels.STRIDE
        }
        drawFloorGlow(pool)
        drawFloorGlow(shade)
        drawRect(floor, Offset(0f, floorTop), Size(width, height - floorTop))
    }
}

private class FloorGlow(val centre: Offset, val radiusX: Float, val radiusY: Float, val brush: Brush)

private fun ellipseBrush(centre: Offset, radius: Float, color: Color, coreAlpha: Float, middle: Pair<Float, Float>): Brush =
    Brush.radialGradient(
        0f to color.copy(alpha = coreAlpha),
        middle.first to color.copy(alpha = middle.second),
        1f to color.copy(alpha = 0f),
        center = centre,
        radius = max(radius, 1f),
    )

/** A round gradient squashed into an ellipse around its centre, the desktop's ellipseGlow. */
private fun DrawScope.drawFloorGlow(glow: FloorGlow) {
    if (glow.radiusX <= 0f || glow.radiusY <= 0f) return
    scale(scaleX = 1f, scaleY = glow.radiusY / glow.radiusX, pivot = glow.centre) {
        drawRect(
            glow.brush,
            Offset(glow.centre.x - glow.radiusX, glow.centre.y - glow.radiusX),
            Size(glow.radiusX * 2f, glow.radiusX * 2f),
        )
    }
}
