package net.millida.launcher.core.game

import kotlin.math.roundToInt

/**
 * The game can draw fewer pixels than the glass has and leave the stretching
 * to the display hardware: on a weak phone that is the cheapest way to more
 * frames. Everything that speaks to the game in pixels (the window size, the
 * cursor) goes through [toGame], so a touch still lands where the finger is.
 */
object GameResolution {
    const val FULL = 100
    const val MIN = 30
    const val STEP = 5

    /** Below this the game refuses to lay out its menus: 320 by 240 is its own floor. */
    private const val MIN_WIDTH = 320
    private const val MIN_HEIGHT = 240

    fun clamp(percent: Int): Int = ((percent.coerceIn(MIN, FULL) + STEP / 2) / STEP * STEP).coerceIn(MIN, FULL)

    fun factor(percent: Int): Float = clamp(percent) / FULL.toFloat()

    /** The window size the game gets for a screen of [width] by [height] pixels. */
    fun window(width: Int, height: Int, percent: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val wanted = factor(percent)
        val floor = maxOf(MIN_WIDTH.toFloat() / width, MIN_HEIGHT.toFloat() / height).coerceAtMost(1f)
        val applied = maxOf(wanted, floor)
        return (width * applied).roundToInt().coerceAtLeast(1) to (height * applied).roundToInt().coerceAtLeast(1)
    }

    fun toGame(value: Float, factor: Float): Float = value * factor

    /** What a player who never touched the slider gets: entry-level GPUs draw 75% of the pixels. */
    const val WEAK_GPU = 75
    private val WEAK = Regex("(?i)adreno[^0-9]*(5\\d\\d|6[0-2]\\d)|mali-(g31|g51|g52|g57|g68|t)|powervr")

    fun defaultFor(glRenderer: String?): Int = if (glRenderer != null && WEAK.containsMatchIn(glRenderer)) WEAK_GPU else FULL
}

/** The frame counts the in-game meter keeps; the graph's top follows the best second seen, never below sixty. */
class FpsHistory private constructor(val samples: List<Int>) {
    constructor() : this(emptyList())

    val last: Int
        get() = samples.lastOrNull() ?: 0

    val ceiling: Int
        get() = maxOf(FrameCap.DEFAULT, samples.maxOrNull() ?: 0)

    fun add(fps: Int): FpsHistory = FpsHistory((samples + fps.coerceAtLeast(0)).takeLast(CAPACITY))

    companion object {
        const val CAPACITY = 60
        const val LOW = 30
    }
}

/**
 * Every in-game second's frame count, kept as a histogram: a median and a slow tail
 * for the pack's card without storing the seconds. Zero is a loading screen or a
 * paused window, not a speed, and is left out.
 */
class FpsStats {
    private val counts = IntArray(CEILING + 1)

    var n: Int = 0
        private set

    fun add(fps: Int) {
        if (fps <= 0) return
        counts[minOf(fps, CEILING)] += 1
        n += 1
    }

    /** The [percent]th percentile (nearest rank), or null before [MIN_SAMPLES] seconds. */
    fun percentile(percent: Int): Int? {
        if (n < MIN_SAMPLES) return null
        val rank = maxOf(1, (percent * n + 99) / 100)
        var seen = 0
        for (fps in 1..CEILING) {
            seen += counts[fps]
            if (seen >= rank) return fps
        }
        return null
    }

    companion object {
        const val CEILING = 300
        const val MIN_SAMPLES = 20
    }
}

/**
 * A display mode as the system lists it. Only the rate may change: a mode of
 * another size would rebuild the game's window under it.
 */
data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

object DisplayModes {
    fun fastest(modes: List<DisplayModeInfo>, current: DisplayModeInfo?): Int? {
        val same = if (current == null) modes else modes.filter { it.width == current.width && it.height == current.height }
        val best = same.filter { it.refreshRate.isFinite() }.maxByOrNull { it.refreshRate } ?: return null
        return if (current != null && best.refreshRate <= current.refreshRate) null else best.id
    }
}

/**
 * Most phones refresh at 90 or 120 Hz but hand an app 60 unless it asks, and
 * the game caps itself at the cap written for it. Both have to move for the
 * frames to go past sixty.
 */
object FrameCap {
    const val DEFAULT = 60
    private const val MAX = 260

    fun forDisplay(refreshRates: List<Float>): Int =
        refreshRates.filter { it.isFinite() && it > 0f }.maxOrNull()?.roundToInt()?.coerceIn(DEFAULT, MAX) ?: DEFAULT
}
