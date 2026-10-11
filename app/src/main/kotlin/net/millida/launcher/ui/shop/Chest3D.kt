package net.millida.launcher.ui.shop

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.NativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.delay
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.drawRays
import net.millida.launcher.ui.mixSrgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * closed — taken: stands and sways; ready — waiting: hops, the lid knocks;
 * shake — opening: the shake grows; open — lid thrown back, light from inside.
 */
internal enum class ChestMode { Closed, Ready, Shake, Open }

/** One frame of the voxel chest: yaw and lid angle in radians, a negative lid opens. */
internal class ChestPose {
    var yaw = REST_YAW
    var lid = 0f
    var lit = false
}

internal const val REST_YAW = -0.5f
internal const val LID_OPEN = -1.95f
internal const val CHEST_LID_MS = 420f

private const val BODY_HEIGHT = 10f
private const val CENTER_Y = 7f
private const val CAMERA = 48f
private const val PITCH = 0.18f
private const val SHADE_LEVELS = 6
private const val AMBIENT = 0.55f
private const val DIFFUSE = 0.45f
private const val HINGE_Y = 10f
private const val HINGE_Z = -7f
private const val MIN_AREA = 0.5f

private val LIGHT = run {
    val x = 0.35f
    val y = 0.8f
    val z = 0.55f
    val length = sqrt(x * x + y * y + z * z)
    floatArrayOf(x / length, y / length, z / length)
}

/** Corner order of each face as seen from outside: top-left, top-right, bottom-right, bottom-left. */
private val FACE_CORNERS = arrayOf(
    intArrayOf(6, 7, 5, 4),
    intArrayOf(3, 2, 0, 1),
    intArrayOf(7, 3, 1, 5),
    intArrayOf(2, 6, 4, 0),
    intArrayOf(2, 3, 7, 6),
    intArrayOf(4, 5, 1, 0),
)

private const val FRONT = 0
private const val BACK = 1
private const val RIGHT = 2
private const val LEFT = 3
private const val TOP = 4
private const val BOTTOM = 5

private class VoxelBox(
    val min: FloatArray,
    val max: FloatArray,
    val hinged: Boolean,
    val faces: Array<ChestFace>,
    val seed: Int,
) {
    fun faceSize(face: Int): Pair<Int, Int> {
        val dx = (max[0] - min[0]).roundToInt()
        val dy = (max[1] - min[1]).roundToInt()
        val dz = (max[2] - min[2]).roundToInt()
        return when (face) {
            FRONT, BACK -> dx to dy
            RIGHT, LEFT -> dz to dy
            else -> dx to dz
        }
    }
}

/** Sizes in texels like the in-game chest: body 14×10×14, lid 14×5×14 on a back hinge, latch 2×4×1. */
private val BOXES = listOf(
    VoxelBox(
        floatArrayOf(-7f, 0f, -7f),
        floatArrayOf(7f, BODY_HEIGHT, 7f),
        hinged = false,
        faces = arrayOf(ChestFace.BODY, ChestFace.BODY, ChestFace.BODY, ChestFace.BODY, ChestFace.INSIDE, ChestFace.BODY),
        seed = 11,
    ),
    VoxelBox(
        floatArrayOf(-7f, HINGE_Y, -7f),
        floatArrayOf(7f, HINGE_Y + 5f, 7f),
        hinged = true,
        faces = arrayOf(ChestFace.LID_SIDE, ChestFace.LID_SIDE, ChestFace.LID_SIDE, ChestFace.LID_SIDE, ChestFace.LID_TOP, ChestFace.INSIDE),
        seed = 23,
    ),
    VoxelBox(
        floatArrayOf(-1f, HINGE_Y - 1.5f, 7f),
        floatArrayOf(1f, HINGE_Y + 2.5f, 8f),
        hinged = true,
        faces = Array(6) { ChestFace.LATCH },
        seed = 3,
    ),
)

private const val FACE_COUNT = 6
private const val CORNERS = 8

/**
 * The voxel chest drawn the way the 3D skin is: corners projected on the CPU,
 * each face a pixel texture mapped with a matrix, painter's order, stepped
 * Lambert shade. No GL context, so it lives inside any Compose canvas.
 */
internal class ChestModel(private val tier: String) {
    private val textures = HashMap<String, Bitmap>()
    private val viewX = FloatArray(BOXES.size * CORNERS)
    private val viewY = FloatArray(BOXES.size * CORNERS)
    private val viewZ = FloatArray(BOXES.size * CORNERS)
    private val screenX = FloatArray(BOXES.size * CORNERS)
    private val screenY = FloatArray(BOXES.size * CORNERS)
    private val order = IntArray(BOXES.size * FACE_COUNT)
    private val depths = FloatArray(BOXES.size * FACE_COUNT)
    private val levels = IntArray(BOXES.size * FACE_COUNT)
    private val source = FloatArray(8)
    private val target = FloatArray(8)
    private val mapping = Matrix()
    private val paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }
    private val shades = Array(SHADE_LEVELS) { level ->
        val gray = ((AMBIENT + DIFFUSE * level / (SHADE_LEVELS - 1)) * 255f).roundToInt().coerceIn(0, 255)
        LightingColorFilter(AndroidColor.rgb(gray, gray, gray), 0)
    }

    fun draw(canvas: NativeCanvas, centerX: Float, floorY: Float, unit: Float, pose: ChestPose) {
        project(centerX, floorY, unit, pose)
        var count = 0
        for (box in BOXES.indices) {
            for (face in 0 until FACE_COUNT) {
                val level = visibleLevel(box, face)
                if (level < 0) continue
                val slot = box * FACE_COUNT + face
                order[count] = slot
                depths[count] = meanDepth(box, face)
                levels[slot] = level
                count++
            }
        }
        sortFarToNear(count)
        for (index in 0 until count) {
            val slot = order[index]
            val box = slot / FACE_COUNT
            val face = slot % FACE_COUNT
            val shape = BOXES[box]
            val kind = if (pose.lit && box == 0 && face == TOP) ChestFace.INSIDE_LIT else shape.faces[face]
            val (width, height) = shape.faceSize(face)
            val bitmap = texture(kind, width, height, shape.seed + face * 7)
            val corners = FACE_CORNERS[face]
            for (corner in 0 until 4) {
                val at = box * CORNERS + corners[corner]
                target[corner * 2] = screenX[at]
                target[corner * 2 + 1] = screenY[at]
            }
            source[0] = 0f
            source[1] = 0f
            source[2] = width.toFloat()
            source[3] = 0f
            source[4] = width.toFloat()
            source[5] = height.toFloat()
            source[6] = 0f
            source[7] = height.toFloat()
            if (!mapping.setPolyToPoly(source, 0, target, 0, 4)) continue
            paint.colorFilter = if (kind == ChestFace.INSIDE_LIT) null else shades[levels[slot]]
            canvas.drawBitmap(bitmap, mapping, paint)
        }
    }

    private fun texture(kind: ChestFace, width: Int, height: Int, seed: Int): Bitmap {
        val key = "$kind:$width:$height:$seed"
        return textures.getOrPut(key) {
            Bitmap.createBitmap(ChestPaint.face(tier, kind, width, height, seed), width, height, Bitmap.Config.ARGB_8888)
        }
    }

    private fun project(centerX: Float, floorY: Float, unit: Float, pose: ChestPose) {
        val yawCos = cos(pose.yaw)
        val yawSin = sin(pose.yaw)
        val lidCos = cos(pose.lid)
        val lidSin = sin(pose.lid)
        val pitchCos = cos(PITCH)
        val pitchSin = sin(PITCH)
        val centerY = floorY - CENTER_Y * unit * pitchCos
        for (box in BOXES.indices) {
            val shape = BOXES[box]
            for (corner in 0 until CORNERS) {
                var x = if (corner and 1 == 0) shape.min[0] else shape.max[0]
                var y = if (corner and 2 == 0) shape.min[1] else shape.max[1]
                var z = if (corner and 4 == 0) shape.min[2] else shape.max[2]
                if (shape.hinged) {
                    val dy = y - HINGE_Y
                    val dz = z - HINGE_Z
                    y = HINGE_Y + dy * lidCos - dz * lidSin
                    z = HINGE_Z + dy * lidSin + dz * lidCos
                }
                val turnedX = x * yawCos + z * yawSin
                val turnedZ = -x * yawSin + z * yawCos
                x = turnedX
                val lifted = y - CENTER_Y
                val tiltedY = lifted * pitchCos - turnedZ * pitchSin
                val tiltedZ = lifted * pitchSin + turnedZ * pitchCos
                val at = box * CORNERS + corner
                viewX[at] = x
                viewY[at] = tiltedY
                viewZ[at] = tiltedZ
                val scale = unit * CAMERA / (CAMERA - tiltedZ)
                screenX[at] = centerX + x * scale
                screenY[at] = centerY - tiltedY * scale
            }
        }
    }

    /** -1 when the face looks away from the camera, else its shade step. */
    private fun visibleLevel(box: Int, face: Int): Int {
        val corners = FACE_CORNERS[face]
        val base = box * CORNERS
        val tl = base + corners[0]
        val tr = base + corners[1]
        val bl = base + corners[3]
        val downX = viewX[bl] - viewX[tl]
        val downY = viewY[bl] - viewY[tl]
        val downZ = viewZ[bl] - viewZ[tl]
        val acrossX = viewX[tr] - viewX[tl]
        val acrossY = viewY[tr] - viewY[tl]
        val acrossZ = viewZ[tr] - viewZ[tl]
        val normalX = downY * acrossZ - downZ * acrossY
        val normalY = downZ * acrossX - downX * acrossZ
        val normalZ = downX * acrossY - downY * acrossX
        val length = sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ)
        if (length < MIN_AREA) return -1
        var midX = 0f
        var midY = 0f
        var midZ = 0f
        for (corner in corners) {
            midX += viewX[base + corner]
            midY += viewY[base + corner]
            midZ += viewZ[base + corner]
        }
        val towardX = -midX / 4f
        val towardY = -midY / 4f
        val towardZ = CAMERA - midZ / 4f
        if (normalX * towardX + normalY * towardY + normalZ * towardZ <= 0f) return -1
        val lambert = ((normalX * LIGHT[0] + normalY * LIGHT[1] + normalZ * LIGHT[2]) / length).coerceAtLeast(0f)
        return (lambert * (SHADE_LEVELS - 1)).roundToInt().coerceIn(0, SHADE_LEVELS - 1)
    }

    private fun meanDepth(box: Int, face: Int): Float {
        var sum = 0f
        for (corner in FACE_CORNERS[face]) sum += viewZ[box * CORNERS + corner]
        return sum / 4f
    }

    private fun sortFarToNear(count: Int) {
        for (index in 1 until count) {
            val slot = order[index]
            val depth = depths[index]
            var cursor = index - 1
            while (cursor >= 0 && depths[cursor] > depth) {
                order[cursor + 1] = order[cursor]
                depths[cursor + 1] = depths[cursor]
                cursor--
            }
            order[cursor + 1] = slot
            depths[cursor + 1] = depth
        }
    }
}

internal fun DrawScope.drawChest(model: ChestModel, centerX: Float, floorY: Float, unit: Float, pose: ChestPose) {
    drawIntoCanvas { model.draw(it.nativeCanvas, centerX, floorY, unit, pose) }
}

internal fun chestEaseOutBack(t: Float): Float {
    val c1 = 2.2f
    val c3 = c1 + 1f
    val x = t - 1f
    return 1f + c3 * x * x * x + c1 * x * x
}

private const val SHAKE_RAMP_MS = 1300f
private const val ASK_EVERY_S = 2.6f
private const val RAYS_GROW_MS = 500f
private const val FLASH_MS = 500f

/**
 * A standalone voxel chest for the shop shelf and the X-ray opening. Light is
 * hard rays and a stepped pillar: the pixel language has no blur or gradient.
 */
@Composable
internal fun Chest3D(tier: String, mode: ChestMode, modifier: Modifier = Modifier, onOpened: (() -> Unit)? = null) {
    val reduced = LocalReducedMotion.current
    val model = remember(tier) { ChestModel(tier) }
    val pose = remember { ChestPose() }
    val clock = remember { mutableLongStateOf(0L) }
    val modeAt = remember { mutableLongStateOf(0L) }
    val opened by rememberUpdatedState(onOpened)
    val animated = !reduced || mode == ChestMode.Shake || mode == ChestMode.Open
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        while (true) clock.longValue = withFrameMillis { it }
    }
    LaunchedEffect(mode) {
        modeAt.longValue = withFrameMillis { it }
        if (mode == ChestMode.Open) {
            if (!reduced) delay(CHEST_LID_MS.toLong())
            opened?.invoke()
        }
    }
    val palette = remember(tier) { chestPalette(tier) }
    val tone = chestTone(tier)
    Canvas(modifier) {
        val now = clock.longValue
        val since = max(0L, now - modeAt.longValue).toFloat()
        val seconds = since / 1000f
        val unit = min(size.width / 24f, size.height / 22f)
        val floorY = size.height * 0.84f
        val seam = Offset(size.width / 2f, floorY - BODY_HEIGHT * unit)
        var dx = 0f
        var dy = 0f
        var degrees = 0f
        pose.lit = false
        pose.yaw = REST_YAW
        pose.lid = 0f
        when (mode) {
            ChestMode.Closed -> if (!reduced) pose.yaw = REST_YAW + sin(seconds * 0.6f) * 0.06f
            ChestMode.Ready -> {
                drawRays(seam, max(size.width, size.height) * 0.6f, tone.copy(alpha = 0.16f), rotation = if (reduced) 0f else seconds * 0.25f)
                if (!reduced) {
                    pose.yaw = REST_YAW + sin(seconds * 0.9f) * 0.12f
                    dy = -abs(sin(seconds * 2.4f)) * 1.4f * unit
                    val ask = (seconds % ASK_EVERY_S) / ASK_EVERY_S
                    if (ask > 0.82f) pose.lid = -sin(((ask - 0.82f) / 0.18f) * PI.toFloat()) * 0.22f
                }
            }
            ChestMode.Shake -> {
                val k = min(1f, since / SHAKE_RAMP_MS)
                drawRays(seam, max(size.width, size.height) * 0.5f, tone.copy(alpha = 0.06f + 0.12f * k))
                if (!reduced) {
                    val amplitude = 0.04f + k * 0.16f
                    degrees = sin(seconds * (30f + k * 26f)) * amplitude * 28f
                    dx = sin(seconds * (24f + k * 20f) + 1.3f) * amplitude * 5f * unit
                    pose.lid = -abs(sin(seconds * (18f + k * 16f))) * (0.05f + k * 0.2f)
                }
            }
            ChestMode.Open -> {
                val grow = if (reduced) 1f else min(1f, since / RAYS_GROW_MS)
                drawRays(seam, max(size.width, size.height) * 0.75f * grow, tone.copy(alpha = 0.26f), rotation = if (reduced) 0f else seconds * 0.25f)
                val pillarHeight = seam.y * grow
                drawRect(tone.copy(alpha = 0.16f), Offset(seam.x - unit * 5f, seam.y - pillarHeight), Size(unit * 10f, pillarHeight))
                drawRect(tone.copy(alpha = 0.26f), Offset(seam.x - unit * 2.5f, seam.y - pillarHeight), Size(unit * 5f, pillarHeight))
                pose.lit = true
                pose.lid = if (reduced) LID_OPEN else LID_OPEN * chestEaseOutBack(min(1f, since / CHEST_LID_MS))
                if (!reduced) pose.yaw = REST_YAW + sin(seconds * 0.7f) * 0.08f
            }
        }
        val pivot = Offset(size.width / 2f, floorY)
        withTransform({
            translate(floor(dx), floor(dy))
            rotate(degrees, pivot)
        }) {
            drawChest(model, size.width / 2f, floorY, unit, pose)
        }
        if (mode == ChestMode.Open && !reduced && since < FLASH_MS) {
            val alpha = 0.85f * (1f - floor(since / FLASH_MS * 4f) / 4f)
            val color = palette.glowColor.mixSrgb(MillidaPixel.white, 0.4f).copy(alpha = alpha)
            val core = unit * 6f
            val arm = unit * 20f
            val thick = unit * 2f
            drawRect(color, Offset(seam.x - core / 2f, seam.y - core / 2f), Size(core, core))
            drawRect(color, Offset(seam.x - arm, seam.y - thick / 2f), Size(arm * 2f, thick))
            drawRect(color, Offset(seam.x - thick / 2f, seam.y - arm), Size(thick, arm * 2f))
        }
    }
}
