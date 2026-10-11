package net.millida.launcher.core.cosmetics

/**
 * Places every bone of a piece for one moment of its clip, as the desktop viewer
 * does (src/lib/cosmeticModel.ts): a bone is its offset, its rest turn, then the
 * clip's turn in the bone's own axes, then the clip's scale, all under its parent.
 * Rest and clip turns are multiplied, never added as angles: adding them swung the
 * lower pair of double wings half a player's height upwards.
 */
object CosmeticPose {
    const val MATRIX = 12

    /** [out] gets one 3x4 matrix per bone, in game space relative to the bone's attachment point. */
    fun bake(rig: CosmeticRig, clip: AnimationClip?, seconds: Float, out: Array<FloatArray>) {
        rig.bones.forEachIndexed { at, bone ->
            val pose = clip?.let { CosmeticAnimation.poseOf(it, bone.name, seconds) }
            var local = turn(bone.rotation)
            if (pose != null) {
                local = EmoteSkeleton.multiply(local, turn(pose.rotation))
                for (row in 0 until 3) {
                    local[row * 4] *= pose.scale[0]
                    local[row * 4 + 1] *= pose.scale[1]
                    local[row * 4 + 2] *= pose.scale[2]
                }
            }
            local[3] = bone.offset[0] + (pose?.position?.get(0) ?: 0f)
            local[7] = bone.offset[1] + (pose?.position?.get(1) ?: 0f)
            local[11] = bone.offset[2] + (pose?.position?.get(2) ?: 0f)
            val parent = bone.parent
            val world = if (parent in 0 until at) EmoteSkeleton.multiply(out[parent], local) else local
            world.copyInto(out[at])
        }
    }

    fun buffers(rig: CosmeticRig): Array<FloatArray> = Array(rig.bones.size) { FloatArray(MATRIX) }

    private fun turn(degrees: FloatArray): FloatArray = EmoteSkeleton.rotation(
        Math.toRadians(degrees[0].toDouble()).toFloat(),
        Math.toRadians(degrees[1].toDouble()).toFloat(),
        Math.toRadians(degrees[2].toDouble()).toFloat(),
    )
}
