package net.millida.launcher.core.input

/** What a finger that went down on the world turned out to be. */
enum class Gesture { TAP, HOLD, DRAG }

/**
 * The rules that tell a tap from a hold from a drag and that decide what each does in
 * the scheme. Kept out of the screen code so the thresholds can be tested: a hit that
 * is a few milliseconds late is a fight lost.
 */
object TouchGestures {
    const val TAP_MS = 200L
    const val DOUBLE_TAP_MS = 300L
    const val SWIPE_DP = 9f

    /**
     * [travel] is how far the finger went from where it landed, in the same unit as
     * [slop]; [heldMs] how long it has been down.
     */
    fun classify(heldMs: Long, travel: Float, slop: Float, holdMs: Long): Gesture = when {
        travel > slop -> Gesture.DRAG
        heldMs >= holdMs -> Gesture.HOLD
        else -> Gesture.TAP
    }

    /** A release counts as a tap only if the finger stayed put and came up quickly. */
    fun isTap(heldMs: Long, travel: Float, slop: Float, tapMs: Long = TAP_MS): Boolean =
        travel <= slop && heldMs in 0 until tapMs

    fun isDoubleTap(previousUpMs: Long?, downMs: Long, gapMs: Long = DOUBLE_TAP_MS): Boolean =
        previousUpMs != null && downMs - previousUpMs in 0 until gapMs

    /** Vertical look motion with the inversion applied. */
    fun lookY(delta: Float, invert: Boolean): Float = if (invert) -delta else delta

    /** Hotbar slots passed by a swipe of [travel] pixels, signed; the remainder goes on to the next move. */
    fun slotSteps(travel: Float, step: Float): Pair<Int, Float> {
        if (step <= 0f || !travel.isFinite()) return 0 to 0f
        var steps = 0
        var rest = travel
        while (rest >= step) {
            steps++
            rest -= step
        }
        while (rest <= -step) {
            steps--
            rest += step
        }
        return steps to rest
    }
}
