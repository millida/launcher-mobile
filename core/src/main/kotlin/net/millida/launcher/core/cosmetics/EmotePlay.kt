package net.millida.launcher.core.cosmetics

/**
 * The figure's parts as a viewer sees them: y up, the face towards +z, every part
 * turning about its own pivot (the neck for head and body, shoulders, hips).
 * This is the frame of the phone's stage and of the desktop viewer alike.
 */
class FigurePose {
    /** Radians per part in [EmotePart] order, turned z, then y, then x. */
    val rotation = FloatArray(PARTS * 3)
    /** Model pixels per part, added to its pivot. */
    val shift = FloatArray(PARTS * 3)
    val posed = BooleanArray(PARTS)
    var lift = 0f
    var lifted = false

    fun reset() {
        rotation.fill(0f)
        shift.fill(0f)
        posed.fill(false)
        lift = 0f
        lifted = false
    }

    companion object {
        val PARTS = EmotePart.entries.size
    }
}

/**
 * An emote moves the player, not an item, so it stands in for the figure's own
 * motion (desktop src/lib/cosmeticEmote.ts). With the emote's skeleton the pose
 * is taken from the whole chain; a paired emote plays without it, as the mod does.
 */
class EmotePlay(val sequence: EmoteSequence, private val skeleton: EmoteSkeleton?) {

    fun pose(progress: Float, out: FigurePose) {
        out.reset()
        val clip = sequence.clipAt(progress)
        val seconds = sequence.timeAt(progress)
        if (skeleton != null) {
            for ((part, pose) in skeleton.poseAt(clip, seconds)) {
                val at = part.ordinal * 3
                out.posed[part.ordinal] = true
                out.rotation[at] = pose.turn[0]
                out.rotation[at + 1] = -pose.turn[1]
                out.rotation[at + 2] = -pose.turn[2]
                out.shift[at] = pose.shift[0]
                out.shift[at + 1] = -pose.shift[1]
                out.shift[at + 2] = -pose.shift[2]
            }
            return
        }
        for (part in EmotePart.entries) {
            val pose = CosmeticAnimation.poseOf(clip, part.bone, seconds)
                ?: part.alias?.let { CosmeticAnimation.poseOf(clip, it, seconds) }
                ?: continue
            val at = part.ordinal * 3
            out.posed[part.ordinal] = true
            out.rotation[at] = radians(pose.rotation[0])
            out.rotation[at + 1] = radians(-pose.rotation[1])
            out.rotation[at + 2] = radians(-pose.rotation[2])
        }
        // A jump or a squat is set on the root: without it the emote's jump stays on the floor.
        val root = CosmeticAnimation.poseOf(clip, "root", seconds) ?: CosmeticAnimation.poseOf(clip, "body", seconds)
        if (root != null) {
            out.lift = root.position[1]
            out.lifted = true
        }
    }

    /** How long a lobby shows it: the wind-up and enough loops to read, within the desktop's bounds. */
    fun showSeconds(): Float {
        val intro = sequence.intro?.length ?: 0f
        val loop = sequence.main.length.takeIf { it > 0f } ?: 1f
        val loops = maxOf(1f, kotlin.math.ceil(SHOW_AT_LEAST / loop))
        return ((intro + loop * loops)).coerceIn(SHOW_MIN, SHOW_MAX)
    }

    private fun radians(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()

    companion object {
        private const val SHOW_AT_LEAST = 3f
        private const val SHOW_MIN = 2.5f
        private const val SHOW_MAX = 8f
    }
}
