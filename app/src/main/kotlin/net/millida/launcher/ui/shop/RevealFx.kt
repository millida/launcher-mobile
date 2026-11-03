package net.millida.launcher.ui.shop

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal val EASE_SMOOTH = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
internal val EASE_SLAM = CubicBezierEasing(0.2f, 1.5f, 0.4f, 1f)
internal val EASE_FLIP = CubicBezierEasing(0.2f, 1.3f, 0.4f, 1f)
private val EASE_BURST = CubicBezierEasing(0.12f, 0.75f, 0.3f, 1f)

/** The desktop's seeded noise (RewardReveal.tsx rand): particles keep their places across recompositions. */
internal class SeededRandom(seed: Int) {
    private var s = seed * 9301 + 49297

    fun next(): Float {
        s = (s * 9301 + 49297) % 233280
        return s / 233280f
    }
}

/** Linear keyframes of one value, each segment eased like a CSS keyframe interval. */
internal fun keyframes(p: Float, easing: CubicBezierEasing?, vararg points: Float): Float {
    if (p <= points[0]) return points[1]
    var i = 0
    while (i + 3 < points.size) {
        val t0 = points[i]
        val v0 = points[i + 1]
        val t1 = points[i + 2]
        val v1 = points[i + 3]
        if (p <= t1) {
            val local = (p - t0) / (t1 - t0)
            val k = easing?.transform(local) ?: local
            return v0 + (v1 - v0) * k
        }
        i += 2
    }
    return points[points.size - 1]
}

private fun DrawScope.wedges(center: Offset, radius: Float, from: Float, on: Float, period: Float, rotation: Float, brush: Brush) {
    val path = Path()
    val count = (360f / period).roundToInt()
    for (index in 0 until count) {
        val start = Math.toRadians((from + index * period - 90f + rotation).toDouble())
        val end = Math.toRadians((from + index * period + on - 90f + rotation).toDouble())
        path.moveTo(center.x, center.y)
        val steps = 4
        for (s in 0..steps) {
            val a = start + (end - start) * s / steps
            path.lineTo(center.x + radius * cos(a).toFloat(), center.y + radius * sin(a).toFloat())
        }
        path.close()
    }
    drawPath(path, brush)
}

/**
 * The spinning rays behind a reward (reward.css .rw-rays): a soft tone glow,
 * sixteen tone wedges fading out from the middle and twelve thin white ones
 * turning the other way.
 */
@Composable
internal fun RevealRays(tone: Color, modifier: Modifier = Modifier, scale: Float = 1f, center: (Size) -> Offset = { Offset(it.width / 2f, it.height / 2f) }) {
    val reduced = LocalReducedMotion.current
    val spin: State<Float>? = if (reduced) {
        null
    } else {
        rememberInfiniteTransition(label = "rays").animateFloat(0f, 1f, infiniteRepeatable(tween(200_000, easing = LinearEasing)), label = "rays-spin")
    }
    Canvas(modifier) {
        val c = center(size)
        val k = scale * density
        val turn = (spin?.value ?: 0f) * 200f
        val glowR = 260f * 1.41421f * k
        drawCircle(
            Brush.radialGradient(0f to tone.copy(alpha = 0.42f), 0.62f to tone.copy(alpha = 0f), center = c, radius = glowR),
            radius = glowR,
            center = c,
        )
        val aR = 620f * 1.41421f * k
        val aBrush = Brush.radialGradient(0f to tone.copy(alpha = 0.36f), 0.16f to tone.copy(alpha = 0.36f), 0.66f to tone.copy(alpha = 0f), center = c, radius = aR)
        wedges(c, aR * 0.66f, 0f, 9f, 22.5f, turn / 25f * 360f, aBrush)
        val bR = 520f * 1.41421f * k
        val white = MillidaPixel.white
        val bBrush = Brush.radialGradient(0f to white.copy(alpha = 0.14f), 0.12f to white.copy(alpha = 0.14f), 0.58f to white.copy(alpha = 0f), center = c, radius = bR)
        wedges(c, bR * 0.58f, 11f, 4f, 30f, -turn / 40f * 360f, bBrush)
    }
}

/** A white flash from the middle of the screen that fades in half a second (reveal.css .rv2-flash). */
@Composable
internal fun RevealFlash(tone: Color, modifier: Modifier = Modifier, durationMs: Int = 550) {
    if (LocalReducedMotion.current) return
    val fade = remember { Animatable(0.95f) }
    LaunchedEffect(Unit) { fade.animateTo(0f, tween(durationMs, easing = EASE_SMOOTH)) }
    Canvas(modifier) {
        val alpha = fade.value
        if (alpha <= 0f) return@Canvas
        val c = Offset(size.width / 2f, size.height * 0.46f)
        val r = max(hypot(c.x, c.y), hypot(size.width - c.x, size.height - c.y))
        val core = tone.mixSrgb(MillidaPixel.white, 0.55f)
        drawRect(
            Brush.radialGradient(0f to core.copy(alpha = alpha), 0.28f to tone.copy(alpha = 0.6f * alpha), 0.7f to tone.copy(alpha = 0f), center = c, radius = r),
        )
    }
}

private class Particle(val dx: Float, val dy: Float, val side: Float, val color: Color, val life: Float)

/** Pixel squares bursting from the middle of their box and fading (RewardReveal.tsx Burst). */
@Composable
internal fun RevealBurst(n: Int, spread: Float, seed: Int, tone: Color, modifier: Modifier = Modifier) {
    if (LocalReducedMotion.current) return
    val accent = millida.accent
    val palette = remember(tone, accent) { listOf(tone, MillidaPixel.white, Rarity.LEGENDARY.color, accent, MillidaPixel.rubyIcon, tone) }
    val parts = remember(n, spread, seed, palette) {
        val r = SeededRandom(seed)
        List(n) { i ->
            val a = (i.toFloat() / n) * PI.toFloat() * 2f + r.next() * 0.5f
            val d = spread * (0.45f + r.next() * 0.55f)
            val side = floatArrayOf(4f, 6f, 8f, 12f)[floor(r.next() * 4f).toInt().coerceIn(0, 3)]
            Particle(cos(a) * d, sin(a) * d, side, palette[i % palette.size], 620f + r.next() * 420f)
        }
    }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { clock.animateTo(1100f, tween(1100, easing = LinearEasing)) }
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val ms = clock.value
        val unit = density
        for (p in parts) {
            val t = min(1f, ms / p.life)
            if (t >= 1f) continue
            val e = EASE_BURST.transform(t)
            val alpha = if (t < 0.7f) 1f else 1f - EASE_BURST.transform((t - 0.7f) / 0.3f)
            val scale = 1.5f + (0.5f - 1.5f) * e
            val side = p.side * scale * unit
            val x = c.x + p.dx * e * unit
            val y = c.y + (p.dy + 40f) * e * unit
            drawRect(MillidaPixel.revealShade.copy(alpha = 0.35f * alpha), Offset(x - side / 2f, y - side / 2f + 2f * unit), Size(side, side))
            drawRect(p.color.copy(alpha = alpha), Offset(x - side / 2f, y - side / 2f), Size(side, side))
        }
    }
}

private class Leaf(val left: Float, val w: Float, val h: Float, val color: Color, val dx: Float, val fall: Float, val delay: Float, val flip: Float)

/** Pixel confetti falling from the top and flipping (RewardReveal.tsx Confetti). */
@Composable
internal fun RevealConfetti(modifier: Modifier = Modifier, n: Int = 46, seed: Int = 7, tone: Color = Rarity.LEGENDARY.color) {
    if (LocalReducedMotion.current) return
    val accent = millida.accent
    val leaves = remember(n, seed, tone, accent) {
        val palette = listOf(tone, MillidaPixel.white, Rarity.LEGENDARY.color, accent, MillidaPixel.rubyIcon, tone)
        val r = SeededRandom(seed)
        List(n) { i ->
            val w = floatArrayOf(6f, 8f, 10f)[floor(r.next() * 3f).toInt().coerceIn(0, 2)]
            Leaf(
                left = r.next(),
                w = w,
                h = if (r.next() > 0.5f) w else w + 6f,
                color = palette[i % palette.size],
                dx = (r.next() - 0.5f) * 160f,
                fall = 2100f + r.next() * 1400f,
                delay = r.next() * 700f,
                flip = 380f + r.next() * 420f,
            )
        }
    }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { clock.animateTo(4300f, tween(4300, easing = LinearEasing)) }
    Canvas(modifier) {
        val ms = clock.value
        val unit = density
        for (leaf in leaves) {
            val t = (ms - leaf.delay) / leaf.fall
            if (t >= 1f) continue
            val p = t.coerceAtLeast(0f)
            val alpha = if (p < 0.85f) 1f else 1f - (p - 0.85f) / 0.15f
            val x = leaf.left * size.width + leaf.dx * p * unit
            val y = -24f * unit + size.height * 1.1f * p
            val phase = ((ms - leaf.delay).coerceAtLeast(0f) % leaf.flip) / leaf.flip
            val squeeze = EASE_SMOOTH.transform(if (phase < 0.5f) phase * 2f else (1f - phase) * 2f)
            val sx = 1f - 0.75f * squeeze
            val shade = 1f - 0.3f * squeeze
            val w = leaf.w * unit * sx
            val color = Color(leaf.color.red * shade, leaf.color.green * shade, leaf.color.blue * shade, alpha)
            drawRect(color, Offset(x + (leaf.w * unit - w) / 2f, y), Size(w, leaf.h * unit))
        }
    }
}

/** Places its child at its natural size, scaled down (never up) to fit the space it is given. */
@Composable
internal fun FitToBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val child = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity))
            ?: return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val byHeight = if (constraints.hasBoundedHeight && child.height > 0) constraints.maxHeight.toFloat() / child.height else 1f
        val byWidth = if (constraints.hasBoundedWidth && child.width > 0) constraints.maxWidth.toFloat() / child.width else 1f
        val scale = min(1f, min(byHeight, byWidth))
        val w = (child.width * scale).roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val h = (child.height * scale).roundToInt().coerceIn(constraints.minHeight, if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE)
        layout(w, h) {
            child.placeWithLayer((w - child.width) / 2, (h - child.height) / 2) {
                scaleX = scale
                scaleY = scale
            }
        }
    }
}

private val CURRENCY = ConcurrentHashMap<String, ImageBitmap>()

/** The desktop's voxel currency renders (public/currency): ruby, shard, a fragment of each rarity. */
@Composable
internal fun CurrencyArt(art: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image = remember(art) {
        CURRENCY[art] ?: runCatching { context.assets.open("currency/$art.webp").use { BitmapFactory.decodeStream(it) } }
            .getOrNull()?.asImageBitmap()?.also { CURRENCY[art] = it }
    }
    if (image == null) {
        Box(modifier.size(size))
        return
    }
    Image(image, contentDescription = null, modifier = modifier.size(size), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Low)
}

internal fun fragmentArt(rarity: Rarity?): String = "fragment-" + (rarity ?: Rarity.RARE).name.lowercase()
