package net.millida.launcher.core.cosmetics

import kotlinx.serialization.json.JsonElement
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Parts of the player an emote moves. */
enum class EmotePart(val bone: String, val alias: String?, val pivot: FloatArray) {
    HEAD("head", null, floatArrayOf(0f, 0f, 0f)),
    BODY("body", null, floatArrayOf(0f, 0f, 0f)),
    LEFT_ARM("arm_left", "left_arm", floatArrayOf(5f, 2f, 0f)),
    RIGHT_ARM("arm_right", "right_arm", floatArrayOf(-5f, 2f, 0f)),
    LEFT_LEG("leg_left", "left_leg", floatArrayOf(1.9f, 12f, 0f)),
    RIGHT_LEG("leg_right", "right_leg", floatArrayOf(-1.9f, 12f, 0f)),
}

/**
 * Where one part of the player ends up: [shift] from its pivot in model pixels
 * (game space) and the absolute [turn] in radians, in the order the game turns:
 * z, then y, then x.
 */
class PartPose(val shift: FloatArray, val turn: FloatArray)

/**
 * The player's body as the artist animated it, built the way the mod builds it
 * (desktop src/lib/emoteSkeleton.ts). An emote carries its own chain of
 * wrappers - root, orbit, body, shoulder, arm - and each part gets the place and
 * turn the whole chain gives it; turning parts by their own clip angle alone
 * let the figure fall apart when a dance laid the body on its side.
 */
class EmoteSkeleton private constructor(private val bones: List<MeshBone>) {
    private val index = HashMap<String, Int>()
    private val offsets: List<FloatArray>
    private val rest: List<FloatArray>

    init {
        bones.forEachIndexed { at, bone -> index[bone.name.lowercase()] = at }
        offsets = bones.map { bone ->
            val parent = bone.parent?.lowercase()?.let { index[it] }?.let { bones[it] }
            if (parent != null) {
                floatArrayOf(bone.pivot[0] - parent.pivot[0], bone.pivot[1] - parent.pivot[1], bone.pivot[2] - parent.pivot[2])
            } else {
                bone.pivot.copyOf()
            }
        }
        rest = bake(null, 0f)
    }

    private fun boneIndex(part: EmotePart): Int =
        index[part.bone] ?: part.alias?.let { index[it] } ?: -1

    private fun usable(): Boolean = EmotePart.entries.any { boneIndex(it) >= 0 }

    private fun bake(clip: AnimationClip?, seconds: Float): List<FloatArray> {
        val out = arrayOfNulls<FloatArray>(bones.size)
        fun visit(at: Int, trail: Set<Int>): FloatArray {
            out[at]?.let { return it }
            val bone = bones[at]
            val pose = clip?.let { CosmeticAnimation.poseOf(it, bone.name, seconds) }
            var local = rotation(radians(-bone.rotation[0]), radians(bone.rotation[1]), radians(-bone.rotation[2]))
            if (pose != null) {
                val (px, py, pz) = pose.rotation.let { Triple(it[0], it[1], it[2]) }
                if (px != 0f || py != 0f || pz != 0f) local = multiply(local, rotation(radians(px), radians(py), radians(pz)))
                for (row in 0 until 3) {
                    local[row * 4] *= pose.scale[0]
                    local[row * 4 + 1] *= pose.scale[1]
                    local[row * 4 + 2] *= pose.scale[2]
                }
            }
            val offset = offsets[at]
            local[3] = offset[0] + (pose?.position?.get(0) ?: 0f)
            local[7] = offset[1] + (pose?.position?.get(1) ?: 0f)
            local[11] = offset[2] + (pose?.position?.get(2) ?: 0f)
            val parentIndex = bone.parent?.lowercase()?.let { index[it] } ?: -1
            // A file that names its own descendant as a parent would loop forever.
            val matrix = if (parentIndex >= 0 && parentIndex !in trail) {
                multiply(visit(parentIndex, trail + parentIndex), local)
            } else {
                local
            }
            out[at] = matrix
            return matrix
        }
        for (at in bones.indices) visit(at, setOf(at))
        return out.map { it!! }
    }

    /** Poses of the player's parts at a moment of the clip; parts the file lacks are left out. */
    fun poseAt(clip: AnimationClip, seconds: Float): Map<EmotePart, PartPose> {
        val now = bake(clip, seconds)
        val poses = LinkedHashMap<EmotePart, PartPose>()
        for (part in EmotePart.entries) {
            val at = boneIndex(part)
            if (at < 0) continue
            val matrix = now[at]
            poses[part] = PartPose(shiftOf(matrix, rest[at], part.pivot), turnOf(rowsOf(matrix)))
        }
        return poses
    }

    companion object {
        fun of(geometry: JsonElement?): EmoteSkeleton? {
            val mesh = CosmeticGeometry.read(geometry) ?: return null
            val skeleton = EmoteSkeleton(mesh.bones)
            return if (skeleton.usable()) skeleton else null
        }

        private fun radians(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()

        /** Three rows of four: the turn (z, then y, then x) and an empty place column. */
        internal fun rotation(rx: Float, ry: Float, rz: Float): FloatArray {
            val cx = cos(rx)
            val sx = sin(rx)
            val cy = cos(ry)
            val sy = sin(ry)
            val cz = cos(rz)
            val sz = sin(rz)
            return floatArrayOf(
                cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx, 0f,
                sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx, 0f,
                -sy, cy * sx, cy * cx, 0f,
            )
        }

        internal fun multiply(parent: FloatArray, child: FloatArray): FloatArray {
            val out = FloatArray(12)
            for (row in 0 until 3) {
                val p = row * 4
                for (column in 0 until 3) {
                    out[p + column] = parent[p] * child[column] + parent[p + 1] * child[4 + column] + parent[p + 2] * child[8 + column]
                }
                out[p + 3] = parent[p] * child[3] + parent[p + 1] * child[7] + parent[p + 2] * child[11] + parent[p + 3]
            }
            return out
        }

        /** Straightens the columns, since a clip's scale multiplies them; straightening rows gave a wrong arm angle. */
        private fun rowsOf(matrix: FloatArray): FloatArray {
            val m = floatArrayOf(
                matrix[0], matrix[1], matrix[2],
                matrix[4], matrix[5], matrix[6],
                matrix[8], matrix[9], matrix[10],
            )
            for (column in 0 until 3) {
                for (earlier in 0 until column) {
                    val dot = m[column] * m[earlier] + m[3 + column] * m[3 + earlier] + m[6 + column] * m[6 + earlier]
                    m[column] -= dot * m[earlier]
                    m[3 + column] -= dot * m[3 + earlier]
                    m[6 + column] -= dot * m[6 + earlier]
                }
                val length = sqrt(m[column] * m[column] + m[3 + column] * m[3 + column] + m[6 + column] * m[6 + column])
                if (length <= 0.00001f) {
                    m[column] = if (column == 0) 1f else 0f
                    m[3 + column] = if (column == 1) 1f else 0f
                    m[6 + column] = if (column == 2) 1f else 0f
                } else {
                    m[column] /= length
                    m[3 + column] /= length
                    m[6 + column] /= length
                }
            }
            return m
        }

        /**
         * How far the clip moved the point the game treats as this part's pivot. Artists hang
         * the body bone twelve pixels below it, and the travel of the bone's own origin sent
         * the torso around a different centre.
         */
        private fun shiftOf(now: FloatArray, was: FloatArray, pivot: FloatArray): FloatArray {
            val dx = pivot[0] - was[3]
            val dy = pivot[1] - was[7]
            val dz = pivot[2] - was[11]
            val back = rowsOf(was)
            val lx = back[0] * dx + back[3] * dy + back[6] * dz
            val ly = back[1] * dx + back[4] * dy + back[7] * dz
            val lz = back[2] * dx + back[5] * dy + back[8] * dz
            return floatArrayOf(
                now[3] + now[0] * lx + now[1] * ly + now[2] * lz - pivot[0],
                now[7] + now[4] * lx + now[5] * ly + now[6] * lz - pivot[1],
                now[11] + now[8] * lx + now[9] * ly + now[10] * lz - pivot[2],
            )
        }

        private fun turnOf(r: FloatArray): FloatArray =
            floatArrayOf(atan2(r[7], r[8]), asin(-r[6].coerceIn(-1f, 1f)), atan2(r[3], r[0]))
    }
}
