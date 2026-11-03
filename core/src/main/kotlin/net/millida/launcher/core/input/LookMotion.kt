package net.millida.launcher.core.input

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turning the camera by turning the phone. The gyroscope reports in the axes of
 * the phone held upright; the game is always landscape, either side up, so the
 * two axes swap and one of them flips with the side.
 */
object GyroLook {
    /** Mouse pixels per radian at sensitivity one: the game's default turns about 0.15 degrees a pixel. */
    private const val PIXELS_PER_RADIAN = 380f

    /** Below this a phone held still drifts, and the drift would turn the camera on its own. */
    private const val DEAD_ZONE = 0.03f

    /**
     * [rotation] is the display rotation in quarter turns (1 is 90 degrees, 3 is 270).
     * Returns the cursor move for [seconds] of rotation at [x] and [y] rad/s.
     */
    fun delta(rotation: Int, x: Float, y: Float, seconds: Float, sensitivity: Float): Pair<Float, Float> {
        if (!x.isFinite() || !y.isFinite() || seconds <= 0f) return 0f to 0f
        val rateX = if (abs(x) < DEAD_ZONE) 0f else x
        val rateY = if (abs(y) < DEAD_ZONE) 0f else y
        val gain = PIXELS_PER_RADIAN * sensitivity * seconds
        return when (rotation and 3) {
            3 -> rateX * gain to -rateY * gain
            else -> -rateX * gain to rateY * gain
        }
    }
}

/**
 * A quick swipe turns further than a slow one of the same length, as a mouse
 * with acceleration does: aiming stays fine and a turn around takes one flick.
 */
object TouchAcceleration {
    private const val SLOW_PX_PER_MS = 0.4f
    private const val FAST_PX_PER_MS = 3f
    private const val MAX_GAIN = 2.2f

    fun gain(dx: Float, dy: Float, millis: Long): Float {
        if (millis <= 0L) return 1f
        val speed = hypot(dx, dy) / millis
        if (!speed.isFinite() || speed <= SLOW_PX_PER_MS) return 1f
        val share = ((speed - SLOW_PX_PER_MS) / (FAST_PX_PER_MS - SLOW_PX_PER_MS)).coerceIn(0f, 1f)
        return 1f + (MAX_GAIN - 1f) * share
    }
}
