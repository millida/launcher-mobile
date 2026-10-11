package net.millida.launcher.ui

import android.graphics.Bitmap
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.NativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.flow.collectLatest
import net.millida.launcher.core.cosmetics.CosmeticAnchor
import net.millida.launcher.core.cosmetics.CosmeticAnimation
import net.millida.launcher.core.cosmetics.CosmeticCover
import net.millida.launcher.core.cosmetics.CosmeticPose
import net.millida.launcher.core.cosmetics.EmotePart
import net.millida.launcher.core.cosmetics.FigurePose
import net.millida.launcher.core.cosmetics.Pixels
import net.millida.launcher.ui.character.StageOutfit
import net.millida.launcher.ui.character.StagePiece
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import android.graphics.Color as AndroidColor

enum class SkinPose { Still, Idle, Walk, Wave }

@Composable
fun SkinStage(
    skinUrl: String?,
    slim: Boolean,
    modifier: Modifier = Modifier,
    capeUrl: String? = null,
    pose: SkinPose = SkinPose.Idle,
    interactive: Boolean = true,
    background: Color = Color.Transparent,
    onTap: (() -> Unit)? = null,
    hopKey: Any? = null,
    outfit: StageOutfit? = null,
    clipToFrame: Boolean = true,
) {
    val masks by rememberMaskSheets(outfit?.masks.orEmpty())
    val skin by rememberTexture(skinUrl, slim to masks.key) { source -> buildSkinAtlas(source, slim, masks.sheets) }
    val cape by rememberTexture(if (outfit?.hideCape == true) null else capeUrl, null, ::buildCapeAtlas)
    val pieceTextures by rememberPieceTextures(outfit?.pieces.orEmpty().map { it.textureUrl })
    val reducedMotion = LocalReducedMotion.current || LocalInspectionMode.current
    val motion = remember { StageMotion(hopKey) }
    val renderer = remember { SkinRenderer() }
    val tap = rememberUpdatedState(onTap)
    val silhouette = millida.fgFaint
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var onScreen by remember { mutableStateOf(true) }
    val animatePose = (pose != SkinPose.Still || outfit?.animated == true) && !reducedMotion
    val live = animatePose || interactive
    val running = live && onScreen && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val roomy = interactive || pose == SkinPose.Wave

    SideEffect { motion.reducedMotion = reducedMotion }

    LaunchedEffect(motion, hopKey) { motion.hopFor(hopKey) }

    LaunchedEffect(motion, outfit) { motion.restartOutfit() }

    LaunchedEffect(motion, running, animatePose) {
        if (!running) return@LaunchedEffect
        if (animatePose) {
            motion.play(animatePose = true)
        } else {
            snapshotFlow { motion.moving.value }.collectLatest { active ->
                if (active) motion.play(animatePose = false)
            }
        }
    }

    val watched = if (live) {
        Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            val visible = bounds.width > 0f && bounds.height > 0f
            if (visible != onScreen) onScreen = visible
        }
    } else {
        Modifier
    }
    val touch = if (interactive) Modifier.stageGestures(motion, tap) else Modifier

    Canvas(
        modifier
            .background(background)
            .then(if (clipToFrame) Modifier.clipToBounds() else Modifier)
            .then(watched)
            .then(touch),
    ) {
        motion.frame.longValue
        val time = if (animatePose) motion.clock else pose.restTime()
        drawIntoCanvas { canvas ->
            renderer.render(
                canvas = canvas.nativeCanvas,
                width = size.width,
                height = size.height,
                skin = skin,
                cape = cape,
                stance = pose,
                time = time,
                motion = motion,
                slim = slim,
                silhouette = silhouette,
                roomy = roomy,
                outfit = outfit,
                outfitTime = if (animatePose) motion.outfitTime() else 0f,
                pieceTextures = pieceTextures,
            )
        }
    }
}

private fun Modifier.stageGestures(motion: StageMotion, onTap: State<(() -> Unit)?>): Modifier =
    pointerInput(motion) {
        detectTapGestures(onTap = {
            motion.jump()
            onTap.value?.invoke()
        })
    }.pointerInput(motion) {
        val tracker = VelocityTracker()
        val turnPerPixel = TURN_PER_DP / density
        val tiltPerPixel = TILT_PER_DP / density
        detectHorizontalDragGestures(
            onDragStart = {
                tracker.resetTracking()
                motion.grab()
            },
            onDragEnd = { motion.release(tracker.calculateVelocity().x * turnPerPixel) },
            onDragCancel = { motion.release(0f) },
        ) { change, dragAmount ->
            tracker.addPosition(change.uptimeMillis, change.position)
            motion.drag(dragAmount * turnPerPixel, change.positionChange().y * tiltPerPixel)
            change.consume()
        }
    }

private fun SkinPose.restTime(): Float = when (this) {
    SkinPose.Still, SkinPose.Idle -> 0f
    SkinPose.Walk -> WALK_PERIOD / 4f
    SkinPose.Wave -> WAVE_PERIOD / 4f
}

private class StageMotion(private var hopKey: Any?) {
    val frame = mutableLongStateOf(0L)
    val moving = mutableStateOf(false)
    var reducedMotion = false

    var yaw = HOME_YAW
        private set
    var pitch = HOME_PITCH
        private set
    var hop = NO_HOP
        private set
    var clock = 0f
        private set
    var capeSwing = 0f
        private set

    private var spin = 0f
    private var dragging = false
    private var dragTurn = 0f
    private var rest = 0f
    private var dirty = false
    private var outfitStart = 0f

    /** A new outfit starts its clips and its emote from the first frame. */
    fun restartOutfit() {
        outfitStart = clock
        dirty = true
    }

    fun outfitTime(): Float = ((clock - outfitStart) % CLOCK_WRAP + CLOCK_WRAP) % CLOCK_WRAP

    fun grab() {
        dragging = true
        spin = 0f
        dragTurn = 0f
        rest = 0f
        moving.value = true
    }

    fun drag(turn: Float, tilt: Float) {
        yaw = wrapAngle(yaw + turn)
        pitch = (pitch + tilt).coerceIn(PITCH_MIN, PITCH_MAX)
        dragTurn += turn
        dirty = true
    }

    fun release(velocity: Float) {
        dragging = false
        rest = 0f
        spin = if (reducedMotion) 0f else velocity.coerceIn(-SPIN_LIMIT, SPIN_LIMIT)
        dirty = true
        moving.value = true
    }

    fun jump() {
        if (reducedMotion || (hop >= 0f && hop < HOP_RETRIGGER)) return
        hop = 0f
        moving.value = true
    }

    /** The key the stage was created with is not a change, so a figure does not hop just by appearing. */
    fun hopFor(key: Any?) {
        if (key == hopKey) return
        hopKey = key
        if (key != null) jump()
    }

    suspend fun play(animatePose: Boolean) {
        var last = -1L
        while (true) {
            val proceed = withFrameNanos { now ->
                val step = if (last < 0L) 0f else ((now - last) / NANOS_PER_SECOND).coerceIn(0f, MAX_STEP)
                last = now
                if (advance(step, animatePose)) frame.longValue = now
                animatePose || !settled()
            }
            if (!proceed) {
                moving.value = false
                return
            }
        }
    }

    private fun advance(step: Float, animatePose: Boolean): Boolean {
        var changed = dirty
        dirty = false
        if (animatePose) {
            clock = (clock + step) % CLOCK_WRAP
            changed = true
        }
        if (hop >= 0f) {
            val next = hop + step / HOP_SECONDS
            hop = if (next >= 1f) NO_HOP else next
            changed = true
        }
        val turning: Float
        if (dragging) {
            turning = if (step > 0f) dragTurn / step else 0f
            dragTurn = 0f
        } else {
            turning = spin
            if (spin != 0f) {
                yaw = wrapAngle(yaw + spin * step)
                spin *= exp(-SPIN_FRICTION * step)
                if (abs(spin) < SPIN_REST) spin = 0f
                rest = 0f
                changed = true
            } else if (!reducedMotion && !atHome()) {
                rest += step
                if (rest >= RETURN_DELAY) {
                    returnHome(step)
                    changed = true
                }
            }
        }
        if (easeCape(turning, step)) changed = true
        return changed
    }

    private fun settled(): Boolean =
        !dragging && hop < 0f && spin == 0f && capeSwing == 0f && (reducedMotion || atHome())

    private fun easeCape(turning: Float, step: Float): Boolean {
        val target = if (reducedMotion) 0f else min(abs(turning) * CAPE_SPIN_GAIN, CAPE_SPIN_LIMIT)
        val eased = capeSwing + (target - capeSwing) * (1f - exp(-CAPE_EASE * step))
        val next = if (target == 0f && eased < SETTLE_EPSILON) 0f else eased
        if (next == capeSwing) return false
        capeSwing = next
        return true
    }

    private fun returnHome(step: Float) {
        val ease = 1f - exp(-RETURN_RATE * step)
        yaw = wrapAngle(yaw + wrapAngle(homeYaw() - yaw) * ease)
        pitch += (HOME_PITCH - pitch) * ease
        if (atHome()) {
            yaw = homeYaw()
            pitch = HOME_PITCH
        }
    }

    private fun homeYaw(): Float =
        if (abs(wrapAngle(HOME_YAW - yaw)) <= abs(wrapAngle(-HOME_YAW - yaw))) HOME_YAW else -HOME_YAW

    private fun atHome(): Boolean =
        abs(wrapAngle(homeYaw() - yaw)) < SETTLE_EPSILON && abs(HOME_PITCH - pitch) < SETTLE_EPSILON
}

private fun wrapAngle(angle: Float): Float {
    var wrapped = angle % TAU
    if (wrapped > PI_F) wrapped -= TAU
    if (wrapped <= -PI_F) wrapped += TAU
    return wrapped
}

private class SkinRenderer {
    private val view = SkinView()
    private val pose = RigPose()
    private val projection = RigProjection()
    private val texturePaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }
    private val flatPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }
    private val mapping = Matrix()
    private val source = FloatArray(8)
    private val target = FloatArray(8)
    private val outline = Path()
    private val shades = Array(SHADE_LEVELS) { level ->
        val gray = channel(shadeOf(level))
        LightingColorFilter(AndroidColor.rgb(gray, gray, gray), 0)
    }
    private var order = IntArray(FACE_SLOTS)
    private var depths = FloatArray(FACE_SLOTS)
    private val inner = BooleanArray(FACE_SLOTS)
    private val figure = FigurePose()
    private val quads = PieceQuads()
    private val slices = HashMap<StagePiece, PieceSlices>()
    private var slicedFor: StageOutfit? = null
    private val corners = FloatArray(12)
    private val uvPixels = FloatArray(8)

    fun render(
        canvas: NativeCanvas,
        width: Float,
        height: Float,
        skin: TextureLoad<SkinAtlas>,
        cape: TextureLoad<CapeAtlas>,
        stance: SkinPose,
        time: Float,
        motion: StageMotion,
        slim: Boolean,
        silhouette: Color,
        roomy: Boolean,
        outfit: StageOutfit? = null,
        outfitTime: Float = 0f,
        pieceTextures: Map<String, Bitmap> = emptyMap(),
    ) {
        if (width < 1f || height < 1f) return
        val atlas = if (skin is TextureLoad.Ready) skin.value else null
        val capeFaces = if (cape is TextureLoad.Ready) cape.value.faces else null
        val rig = atlas?.rig ?: SkinRig.of(slim, legacy = false)
        val emote = outfit?.emote
        emote?.pose(outfitTime, figure)
        view.frame(width, height, roomy)
        view.orient(motion.yaw, motion.pitch)
        poseRig(stance, time, motion.hop, motion.capeSwing, pose, if (emote != null) figure else null)
        projection.project(rig, pose, view)
        drawFloor(canvas)
        if (atlas == null) {
            drawSilhouette(canvas, rig, width, height, silhouette, pending = skin is TextureLoad.Pending)
        } else {
            collectPieces(outfit, outfitTime, pieceTextures)
            drawTextured(canvas, rig, atlas.faces, capeFaces)
        }
    }

    /**
     * Every face of every worn piece, projected through the part it hangs on. The
     * stage sorts faces far to near instead of keeping a depth buffer, so pieces are
     * drawn on both sides: other people's models wind their faces either way.
     */
    private fun collectPieces(outfit: StageOutfit?, time: Float, textures: Map<String, Bitmap>) {
        quads.count = 0
        if (outfit !== slicedFor) {
            slices.keys.retainAll(outfit?.pieces.orEmpty().toSet())
            slicedFor = outfit
        }
        if (outfit == null) return
        val emote = outfit.emote
        for (piece in outfit.pieces) {
            val bitmap = textures[piece.textureUrl] ?: continue
            val sheet = slices[piece]?.takeIf { it.bitmap === bitmap }
                ?: PieceSlices(bitmap, piece.textureWidth, piece.textureHeight).also { slices[piece] = it }
            val clip = if (piece.followsEmote && emote != null) emote.sequence.clipAt(time) else piece.clip
            val clipTime = if (piece.followsEmote && emote != null) emote.sequence.timeAt(time) else time
            CosmeticPose.bake(piece.rig, clip, clipTime, piece.matrices)
            val frame = if (sheet.frames > 1) floor(time * CosmeticAnimation.ATLAS_FPS).toInt().mod(sheet.frames) else 0
            var quadIndex = 0
            piece.rig.bones.forEachIndexed { at, bone ->
                if (piece.hidden[at]) {
                    quadIndex += bone.quads.size
                    return@forEachIndexed
                }
                val slot = slotOf(piece.anchors[at])
                val m = piece.matrices[at]
                for (quad in bone.quads) {
                    val index = quadIndex++
                    val p = quad.positions
                    for (corner in 0 until 4) {
                        val x = p[corner * 3]
                        val y = p[corner * 3 + 1]
                        val z = p[corner * 3 + 2]
                        val gameX = m[0] * x + m[1] * y + m[2] * z + m[3]
                        val gameY = m[4] * x + m[5] * y + m[6] * z + m[7]
                        val gameZ = m[8] * x + m[9] * y + m[10] * z + m[11]
                        // The game measures height down and the back towards +z; the stage the other way round.
                        projection.toView(slot, gameX, -gameY, -gameZ, corners, corner * 3)
                        uvPixels[corner * 2] = quad.uvs[corner * 2] * sheet.width
                        uvPixels[corner * 2 + 1] = (frame + quad.uvs[corner * 2 + 1]) * sheet.frameHeight
                    }
                    val slice = sheet.slice(index, frame, uvPixels) ?: continue
                    addQuad(slice)
                }
            }
        }
    }

    private fun addQuad(slice: TextureSlice) {
        val at = quads.reserve()
        val screen = quads.screen
        val source = quads.source
        var depth = 0f
        for (corner in 0 until 4) {
            val viewX = corners[corner * 3]
            val viewY = corners[corner * 3 + 1]
            val viewZ = corners[corner * 3 + 2]
            val scale = view.scaleAt(viewZ)
            screen[at * 8 + corner * 2] = view.centerX + viewX * scale
            screen[at * 8 + corner * 2 + 1] = view.centerY - viewY * scale
            source[at * 8 + corner * 2] = uvPixels[corner * 2] - slice.left
            source[at * 8 + corner * 2 + 1] = uvPixels[corner * 2 + 1] - slice.top
            depth += viewZ
        }
        var area = 0f
        for (corner in 0 until 4) {
            val next = (corner + 1) % 4
            area += screen[at * 8 + corner * 2] * screen[at * 8 + next * 2 + 1] -
                screen[at * 8 + next * 2] * screen[at * 8 + corner * 2 + 1]
        }
        if (abs(area) < MIN_FACE_AREA) return
        quads.depth[at] = depth / 4f
        quads.level[at] = twoSidedShade()
        quads.bitmaps[at] = slice.bitmap
        quads.count = at + 1
    }

    private fun twoSidedShade(): Int {
        val acrossX = corners[3] - corners[0]
        val acrossY = corners[4] - corners[1]
        val acrossZ = corners[5] - corners[2]
        val downX = corners[9] - corners[0]
        val downY = corners[10] - corners[1]
        val downZ = corners[11] - corners[2]
        val normalX = downY * acrossZ - downZ * acrossY
        val normalY = downZ * acrossX - downX * acrossZ
        val normalZ = downX * acrossY - downY * acrossX
        val length = sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ)
        if (length < MIN_NORMAL) return SHADE_LEVELS - 1
        val lambert = abs(normalX * LIGHT_X + normalY * LIGHT_Y + normalZ * LIGHT_Z) / length
        return (lambert * (SHADE_LEVELS - 1)).roundToInt().coerceIn(0, SHADE_LEVELS - 1)
    }

    private fun drawQuad(canvas: NativeCanvas, at: Int) {
        val bitmap = quads.bitmaps[at] ?: return
        quads.screen.copyInto(target, 0, at * 8, at * 8 + 8)
        if (!mapping.setPolyToPoly(quads.source, at * 8, target, 0, 4)) return
        texturePaint.colorFilter = shades[quads.level[at]]
        canvas.drawBitmap(bitmap, mapping, texturePaint)
    }

    private fun ensureOrder(size: Int) {
        if (order.size >= size) return
        order = IntArray(size * 2)
        depths = FloatArray(size * 2)
    }

    private fun drawFloor(canvas: NativeCanvas) {
        val fade = (1f - max(pose.lift, 0f) / FLOOR_FADE_LIFT).coerceIn(0f, 1f)
        if (fade <= 0f) return
        val basis = view.basis
        val floorY = -view.orbitY
        for (slot in 0 until 4) {
            val x = if (slot == 1 || slot == 2) FLOOR_HALF_WIDTH else -FLOOR_HALF_WIDTH
            val z = if (slot >= 2) FLOOR_HALF_DEPTH else -FLOOR_HALF_DEPTH
            val viewX = basis[0] * x + basis[1] * floorY + basis[2] * z
            val viewY = basis[3] * x + basis[4] * floorY + basis[5] * z
            val viewZ = basis[6] * x + basis[7] * floorY + basis[8] * z
            val scale = view.scaleAt(viewZ)
            target[slot * 2] = view.centerX + viewX * scale
            target[slot * 2 + 1] = view.centerY - viewY * scale
        }
        traceTarget()
        flatPaint.color = AndroidColor.argb(channel(FLOOR_ALPHA * fade), 0, 0, 0)
        canvas.drawPath(outline, flatPaint)
    }

    private fun drawSilhouette(
        canvas: NativeCanvas,
        rig: SkinRig,
        width: Float,
        height: Float,
        tint: Color,
        pending: Boolean,
    ) {
        var count = 0
        for (part in 0 until SkinPart.CAPE) {
            val box = part * 2 + RigBox.BASE
            for (face in 0 until FACES_PER_BOX) {
                if (facing(rig, box, face) < MIN_FACE_AREA) continue
                order[count] = box * FACES_PER_BOX + face
                depths[count] = meanDepth(rig, box, face)
                count++
            }
        }
        sortFarToNear(count)
        val opacity = tint.alpha * (if (pending) PENDING_TINT else 1f)
        val layer = canvas.saveLayerAlpha(0f, 0f, width, height, channel(opacity))
        for (index in 0 until count) {
            val slot = order[index]
            val box = slot / FACES_PER_BOX
            val face = slot % FACES_PER_BOX
            val shade = shadeOf(shadeLevel(rig, box, face, fromInside = false))
            flatPaint.color = AndroidColor.rgb(
                channel(tint.red * shade),
                channel(tint.green * shade),
                channel(tint.blue * shade),
            )
            fillTarget(rig.boxes[box], box, face)
            traceTarget()
            canvas.drawPath(outline, flatPaint)
        }
        canvas.restoreToCount(layer)
    }

    private fun drawTextured(
        canvas: NativeCanvas,
        rig: SkinRig,
        faces: Array<Bitmap?>,
        capeFaces: Array<Bitmap?>?,
    ) {
        // The cape hangs outside the back plane, so from behind it has to win over
        // arms and legs even where their face centres sit closer to the camera.
        val capeFromBehind = capeFaces != null && facing(rig, CAPE_BOX, BoxFace.FRONT) > 0f
        ensureOrder(FACE_SLOTS + quads.count)
        var count = 0
        for (box in 0 until BOX_COUNT) {
            val layer = rig.boxes[box].layer
            for (face in 0 until FACES_PER_BOX) {
                faceBitmap(faces, capeFaces, box, face) ?: continue
                val signedArea = facing(rig, box, face)
                if (abs(signedArea) < MIN_FACE_AREA) continue
                val fromInside = signedArea < 0f
                if (fromInside && layer != RigBox.OVERLAY) continue
                val slot = box * FACES_PER_BOX + face
                order[count] = slot
                depths[count] = if (box == CAPE_BOX && capeFromBehind) {
                    nearestDepth(rig, box, face)
                } else {
                    meanDepth(rig, box, face)
                }
                inner[slot] = fromInside
                count++
            }
        }
        for (piece in 0 until quads.count) {
            order[count] = FACE_SLOTS + piece
            depths[count] = quads.depth[piece]
            count++
        }
        sortFarToNear(count)
        for (index in 0 until count) {
            val slot = order[index]
            if (slot >= FACE_SLOTS) {
                drawQuad(canvas, slot - FACE_SLOTS)
                continue
            }
            val box = slot / FACES_PER_BOX
            val face = slot % FACES_PER_BOX
            val bitmap = faceBitmap(faces, capeFaces, box, face) ?: continue
            drawTexturedFace(canvas, rig.boxes[box], box, face, bitmap, shadeLevel(rig, box, face, inner[slot]))
        }
    }

    private fun drawTexturedFace(
        canvas: NativeCanvas,
        shape: RigBox,
        box: Int,
        face: Int,
        bitmap: Bitmap,
        level: Int,
    ) {
        fillTarget(shape, box, face)
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val start = if (shape.mirrored) width else 0f
        val end = if (shape.mirrored) 0f else width
        source[0] = start
        source[1] = 0f
        source[2] = end
        source[3] = 0f
        source[4] = end
        source[5] = height
        source[6] = start
        source[7] = height
        if (!mapping.setPolyToPoly(source, 0, target, 0, 4)) return
        texturePaint.colorFilter = shades[level]
        canvas.drawBitmap(bitmap, mapping, texturePaint)
    }

    private fun faceBitmap(faces: Array<Bitmap?>, capeFaces: Array<Bitmap?>?, box: Int, face: Int): Bitmap? =
        if (box == CAPE_BOX) capeFaces?.get(face) else faces[box * FACES_PER_BOX + face]

    private fun fillTarget(shape: RigBox, box: Int, face: Int) {
        for (slot in 0 until 4) {
            val corner = box * CORNERS_PER_BOX + shape.corner(face, slot)
            target[slot * 2] = projection.screenX[corner]
            target[slot * 2 + 1] = projection.screenY[corner]
        }
    }

    private fun traceTarget() {
        outline.rewind()
        outline.moveTo(target[0], target[1])
        outline.lineTo(target[2], target[3])
        outline.lineTo(target[4], target[5])
        outline.lineTo(target[6], target[7])
        outline.close()
    }

    private fun facing(rig: SkinRig, box: Int, face: Int): Float {
        val shape = rig.boxes[box]
        val base = box * CORNERS_PER_BOX
        val x = projection.screenX
        val y = projection.screenY
        var area = 0f
        for (slot in 0 until 4) {
            val from = base + shape.corner(face, slot)
            val to = base + shape.corner(face, (slot + 1) % 4)
            area += x[from] * y[to] - x[to] * y[from]
        }
        return area * BoxFace.winding(face)
    }

    private fun meanDepth(rig: SkinRig, box: Int, face: Int): Float {
        val shape = rig.boxes[box]
        val base = box * CORNERS_PER_BOX
        var sum = 0f
        for (slot in 0 until 4) sum += projection.viewZ[base + shape.corner(face, slot)]
        return sum / 4f
    }

    private fun nearestDepth(rig: SkinRig, box: Int, face: Int): Float {
        val shape = rig.boxes[box]
        val base = box * CORNERS_PER_BOX
        var nearest = -Float.MAX_VALUE
        for (slot in 0 until 4) nearest = max(nearest, projection.viewZ[base + shape.corner(face, slot)])
        return nearest
    }

    private fun shadeLevel(rig: SkinRig, box: Int, face: Int, fromInside: Boolean): Int {
        val shape = rig.boxes[box]
        val base = box * CORNERS_PER_BOX
        val topLeft = base + shape.corner(face, 0)
        val topRight = base + shape.corner(face, 1)
        val bottomLeft = base + shape.corner(face, 3)
        val x = projection.viewX
        val y = projection.viewY
        val z = projection.viewZ
        val acrossX = x[topRight] - x[topLeft]
        val acrossY = y[topRight] - y[topLeft]
        val acrossZ = z[topRight] - z[topLeft]
        val downX = x[bottomLeft] - x[topLeft]
        val downY = y[bottomLeft] - y[topLeft]
        val downZ = z[bottomLeft] - z[topLeft]
        val normalX = downY * acrossZ - downZ * acrossY
        val normalY = downZ * acrossX - downX * acrossZ
        val normalZ = downX * acrossY - downY * acrossX
        val length = sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ)
        if (length < MIN_NORMAL) return SHADE_LEVELS - 1
        val side = if (fromInside) -BoxFace.winding(face) else BoxFace.winding(face)
        val lambert = ((normalX * LIGHT_X + normalY * LIGHT_Y + normalZ * LIGHT_Z) * side / length).coerceAtLeast(0f)
        return (lambert * (SHADE_LEVELS - 1)).roundToInt().coerceIn(0, SHADE_LEVELS - 1)
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

/** Faces of worn pieces for one frame; the arrays grow and are reused, never reallocated per frame. */
private class PieceQuads {
    var count = 0
    var screen = FloatArray(QUADS_INITIAL * 8)
        private set
    var source = FloatArray(QUADS_INITIAL * 8)
        private set
    var depth = FloatArray(QUADS_INITIAL)
        private set
    var level = IntArray(QUADS_INITIAL)
        private set
    var bitmaps = arrayOfNulls<Bitmap>(QUADS_INITIAL)
        private set

    /** The index the next face goes to; it counts only once the face is accepted. */
    fun reserve(): Int {
        if (count >= depth.size) {
            val size = depth.size * 2
            screen = screen.copyOf(size * 8)
            source = source.copyOf(size * 8)
            depth = depth.copyOf(size)
            level = level.copyOf(size)
            bitmaps = bitmaps.copyOf(size)
        }
        return count
    }
}

private class TextureSlice(val bitmap: Bitmap, val left: Int, val top: Int)

/**
 * A worn piece's picture cut per face, the way the skin is: a face maps exactly its
 * own rectangle, so the perspective never reaches texels outside it. A strip of
 * animation frames is cut lazily, frame by frame, and the cache is capped.
 */
private class PieceSlices(val bitmap: Bitmap, textureWidth: Float, textureHeight: Float) {
    val frames = CosmeticAnimation.atlasFrames(bitmap.width, bitmap.height, textureWidth, textureHeight)
    val width = bitmap.width * CosmeticAnimation.atlasWidthShare(bitmap.width, textureWidth)
    val frameHeight = bitmap.height.toFloat() / frames
    private val cache = HashMap<Long, TextureSlice?>()

    fun slice(quad: Int, frame: Int, uv: FloatArray): TextureSlice? {
        val key = (quad.toLong() shl 32) or frame.toLong()
        if (cache.containsKey(key)) return cache[key]
        if (cache.size >= SLICE_CACHE_LIMIT) cache.clear()
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (corner in 0 until 4) {
            minU = min(minU, uv[corner * 2])
            maxU = max(maxU, uv[corner * 2])
            minV = min(minV, uv[corner * 2 + 1])
            maxV = max(maxV, uv[corner * 2 + 1])
        }
        val left = floor(minU + SLICE_EPSILON).toInt().coerceIn(0, bitmap.width)
        val right = ceil(maxU - SLICE_EPSILON).toInt().coerceIn(0, bitmap.width)
        val top = floor(minV + SLICE_EPSILON).toInt().coerceIn(0, bitmap.height)
        val bottom = ceil(maxV - SLICE_EPSILON).toInt().coerceIn(0, bitmap.height)
        val slice = if (right > left && bottom > top) {
            TextureSlice(Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top), left, top)
        } else {
            null
        }
        cache[key] = slice
        return slice
    }
}

/** The part a piece hangs on; a cape piece follows the torso, as the desktop mount does. */
private fun slotOf(anchor: CosmeticAnchor): Int = when (anchor) {
    CosmeticAnchor.HEAD -> SkinPart.HEAD
    CosmeticAnchor.BODY, CosmeticAnchor.CAPE -> SkinPart.BODY
    CosmeticAnchor.RIGHT_ARM -> SkinPart.RIGHT_ARM
    CosmeticAnchor.LEFT_ARM -> SkinPart.LEFT_ARM
    CosmeticAnchor.RIGHT_LEG -> SkinPart.RIGHT_LEG
    CosmeticAnchor.LEFT_LEG -> SkinPart.LEFT_LEG
    CosmeticAnchor.ROOT -> ROOT_SLOT
}

private fun shadeOf(level: Int): Float = AMBIENT + DIFFUSE * level / (SHADE_LEVELS - 1)

private fun channel(value: Float): Int = (value * 255f).roundToInt().coerceIn(0, 255)

private class SkinAtlas(val rig: SkinRig, val faces: Array<Bitmap?>)

private class CapeAtlas(val faces: Array<Bitmap?>)

private fun buildSkinAtlas(source: Bitmap, slim: Boolean, masks: List<Pixels> = emptyList()): SkinAtlas? {
    val width = source.width
    val height = source.height
    if (width < SHEET_UNITS || width % SHEET_UNITS != 0 || width / SHEET_UNITS > MAX_SKIN_UNIT) return null
    val legacy = height * 2 == width
    if (!legacy && height != width) return null
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val sheet = PixelSheet(pixels, width, width / SHEET_UNITS)
    // The game draws the base layer opaque and wipes a legacy hat painted solid.
    sheet.makeOpaque(0, 0, 32, 16)
    sheet.makeOpaque(0, 16, 64, 32)
    if (legacy) sheet.clearWhenSolid(32, 0, 64, 32) else sheet.makeOpaque(16, 48, 48, 64)
    // Clothing cuts the skin after the base layer is made opaque: a cut base pixel must stay a hole.
    val masked = masks.isNotEmpty() && CosmeticCover.cutSkin(Pixels(pixels, width, height), masks, slim)
    val rig = SkinRig.of(slim, legacy)
    val faces = arrayOfNulls<Bitmap>(FACE_SLOTS)
    for (box in 0 until CAPE_BOX) {
        val shape = rig.boxes[box]
        if (!shape.textured) continue
        for (face in 0 until FACES_PER_BOX) {
            faces[box * FACES_PER_BOX + face] = sheet.slice(
                shape.textureRect(face),
                skipEmpty = shape.layer == RigBox.OVERLAY || masked,
            )
        }
    }
    return SkinAtlas(rig, faces)
}

private fun buildCapeAtlas(source: Bitmap): CapeAtlas? {
    val unit = (source.width / SHEET_UNITS).coerceAtLeast(1)
    val width = CAPE_SHEET_COLUMNS * unit
    val height = CAPE_SHEET_ROWS * unit
    if (unit > MAX_CAPE_UNIT || source.width < width || source.height < height) return null
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val sheet = PixelSheet(pixels, width, unit)
    sheet.makeOpaque(0, 0, CAPE_SHEET_COLUMNS, CAPE_SHEET_ROWS)
    val shape = SkinRig.of(slim = false, legacy = false).boxes[CAPE_BOX]
    val faces = arrayOfNulls<Bitmap>(FACES_PER_BOX)
    for (face in 0 until FACES_PER_BOX) {
        faces[face] = sheet.slice(shape.textureRect(face), skipEmpty = false)
    }
    return CapeAtlas(faces)
}

private class PixelSheet(private val pixels: IntArray, private val width: Int, private val unit: Int) {
    private val height = pixels.size / width

    fun makeOpaque(left: Int, top: Int, right: Int, bottom: Int) {
        for (y in top * unit until min(bottom * unit, height)) {
            for (x in left * unit until min(right * unit, width)) {
                val index = y * width + x
                pixels[index] = pixels[index] or OPAQUE
            }
        }
    }

    fun clearWhenSolid(left: Int, top: Int, right: Int, bottom: Int) {
        val rowEnd = min(bottom * unit, height)
        val columnEnd = min(right * unit, width)
        for (y in top * unit until rowEnd) {
            for (x in left * unit until columnEnd) {
                if ((pixels[y * width + x] ushr 24) < SOLID_ALPHA) return
            }
        }
        for (y in top * unit until rowEnd) {
            for (x in left * unit until columnEnd) pixels[y * width + x] = 0
        }
    }

    fun slice(rect: TexRect, skipEmpty: Boolean): Bitmap? {
        val left = rect.x * unit
        val top = rect.y * unit
        val sliceWidth = rect.width * unit
        val sliceHeight = rect.height * unit
        if (sliceWidth <= 0 || sliceHeight <= 0) return null
        if (left < 0 || top < 0 || left + sliceWidth > width || top + sliceHeight > height) return null
        if (skipEmpty && isClear(left, top, sliceWidth, sliceHeight)) return null
        return Bitmap.createBitmap(pixels, top * width + left, width, sliceWidth, sliceHeight, Bitmap.Config.ARGB_8888)
    }

    private fun isClear(left: Int, top: Int, sliceWidth: Int, sliceHeight: Int): Boolean {
        for (y in top until top + sliceHeight) {
            for (x in left until left + sliceWidth) {
                if ((pixels[y * width + x] ushr 24) != 0) return false
            }
        }
        return true
    }
}

internal object SkinPart {
    const val HEAD = 0
    const val BODY = 1
    const val RIGHT_ARM = 2
    const val LEFT_ARM = 3
    const val RIGHT_LEG = 4
    const val LEFT_LEG = 5
    const val CAPE = 6
    const val COUNT = 7
}

internal object BoxFace {
    const val TOP = 0
    const val BOTTOM = 1
    const val RIGHT = 2
    const val FRONT = 3
    const val LEFT = 4
    const val BACK = 5

    private val corners = intArrayOf(
        2, 3, 7, 6,
        0, 1, 5, 4,
        2, 6, 4, 0,
        6, 7, 5, 4,
        7, 3, 1, 5,
        3, 2, 0, 1,
    )

    fun corner(face: Int, slot: Int): Int = corners[face * 4 + slot]

    // The game maps the bottom texture as seen from above, so its corners run the other way round.
    fun winding(face: Int): Int = if (face == BOTTOM) -1 else 1
}

internal class TexRect(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun unwrapFace(u: Int, v: Int, width: Int, height: Int, depth: Int, face: Int): TexRect = when (face) {
    BoxFace.TOP -> TexRect(u + depth, v, width, depth)
    BoxFace.BOTTOM -> TexRect(u + depth + width, v, width, depth)
    BoxFace.RIGHT -> TexRect(u, v + depth, depth, height)
    BoxFace.FRONT -> TexRect(u + depth, v + depth, width, height)
    BoxFace.LEFT -> TexRect(u + depth + width, v + depth, depth, height)
    else -> TexRect(u + depth * 2 + width, v + depth, width, height)
}

internal class RigBox(
    val part: Int,
    val layer: Int,
    val x0: Float,
    val y0: Float,
    val z0: Float,
    val x1: Float,
    val y1: Float,
    val z1: Float,
    private val u: Int,
    private val v: Int,
    private val width: Int,
    private val height: Int,
    private val depth: Int,
    val mirrored: Boolean,
    val turned: Boolean,
    val textured: Boolean,
) {
    fun corner(face: Int, slot: Int): Int {
        val corner = BoxFace.corner(face, slot)
        return if (turned) corner xor TURN_MASK else corner
    }

    fun textureRect(face: Int): TexRect {
        val region = when {
            !mirrored -> face
            face == BoxFace.RIGHT -> BoxFace.LEFT
            face == BoxFace.LEFT -> BoxFace.RIGHT
            else -> face
        }
        return unwrapFace(u, v, width, height, depth, region)
    }

    companion object {
        const val BASE = 0
        const val OVERLAY = 1
        const val CLOAK = 2
        private const val TURN_MASK = 5
    }
}

private fun rigBox(
    part: Int,
    layer: Int,
    x: Float,
    y: Float,
    z: Float,
    width: Int,
    height: Int,
    depth: Int,
    u: Int,
    v: Int,
    inflate: Float = 0f,
    mirrored: Boolean = false,
    turned: Boolean = false,
    textured: Boolean = true,
): RigBox = RigBox(
    part = part,
    layer = layer,
    x0 = x - inflate,
    y0 = y - inflate,
    z0 = z - inflate,
    x1 = x + width + inflate,
    y1 = y + height + inflate,
    z1 = z + depth + inflate,
    u = u,
    v = v,
    width = width,
    height = height,
    depth = depth,
    mirrored = mirrored,
    turned = turned,
    textured = textured,
)

internal class SkinRig private constructor(val slim: Boolean, val legacy: Boolean) {
    val pivots: FloatArray
    val boxes: Array<RigBox>

    init {
        val arm = if (slim) 3 else 4
        val shoulder = if (slim) 21.5f else 22f
        val layered = !legacy
        pivots = floatArrayOf(
            0f, 24f, 0f,
            0f, 24f, 0f,
            -5f, shoulder, 0f,
            5f, shoulder, 0f,
            -2f, 12f, 0f,
            2f, 12f, 0f,
            0f, 24f, -2f,
        )
        val leftArm = if (legacy) {
            rigBox(SkinPart.LEFT_ARM, RigBox.BASE, -1f, -10f, -2f, arm, 12, 4, 40, 16, mirrored = true)
        } else {
            rigBox(SkinPart.LEFT_ARM, RigBox.BASE, -1f, -10f, -2f, arm, 12, 4, 32, 48)
        }
        val leftLeg = if (legacy) {
            rigBox(SkinPart.LEFT_LEG, RigBox.BASE, -2f, -12f, -2f, 4, 12, 4, 0, 16, mirrored = true)
        } else {
            rigBox(SkinPart.LEFT_LEG, RigBox.BASE, -2f, -12f, -2f, 4, 12, 4, 16, 48)
        }
        boxes = arrayOf(
            rigBox(SkinPart.HEAD, RigBox.BASE, -4f, 0f, -4f, 8, 8, 8, 0, 0),
            rigBox(SkinPart.HEAD, RigBox.OVERLAY, -4f, 0f, -4f, 8, 8, 8, 32, 0, inflate = 0.5f),
            rigBox(SkinPart.BODY, RigBox.BASE, -4f, -12f, -2f, 8, 12, 4, 16, 16),
            rigBox(SkinPart.BODY, RigBox.OVERLAY, -4f, -12f, -2f, 8, 12, 4, 16, 32, inflate = 0.25f, textured = layered),
            rigBox(SkinPart.RIGHT_ARM, RigBox.BASE, 1f - arm, -10f, -2f, arm, 12, 4, 40, 16),
            rigBox(SkinPart.RIGHT_ARM, RigBox.OVERLAY, 1f - arm, -10f, -2f, arm, 12, 4, 40, 32, inflate = 0.25f, textured = layered),
            leftArm,
            rigBox(SkinPart.LEFT_ARM, RigBox.OVERLAY, -1f, -10f, -2f, arm, 12, 4, 48, 48, inflate = 0.25f, textured = layered),
            rigBox(SkinPart.RIGHT_LEG, RigBox.BASE, -2f, -12f, -2f, 4, 12, 4, 0, 16),
            rigBox(SkinPart.RIGHT_LEG, RigBox.OVERLAY, -2f, -12f, -2f, 4, 12, 4, 0, 32, inflate = 0.25f, textured = layered),
            leftLeg,
            rigBox(SkinPart.LEFT_LEG, RigBox.OVERLAY, -2f, -12f, -2f, 4, 12, 4, 0, 48, inflate = 0.25f, textured = layered),
            rigBox(SkinPart.CAPE, RigBox.CLOAK, -5f, -16f, -1f, 10, 16, 1, 0, 0, turned = true),
        )
    }

    companion object {
        private val classic = SkinRig(slim = false, legacy = false)
        private val slender = SkinRig(slim = true, legacy = false)
        private val classicLegacy = SkinRig(slim = false, legacy = true)
        private val slenderLegacy = SkinRig(slim = true, legacy = true)

        fun of(slim: Boolean, legacy: Boolean): SkinRig = when {
            slim && legacy -> slenderLegacy
            slim -> slender
            legacy -> classicLegacy
            else -> classic
        }
    }
}

internal class RigPose {
    val rotation = FloatArray(SkinPart.COUNT * 3)
    val shift = FloatArray(SkinPart.COUNT * 3)
    var lift = 0f

    fun reset() {
        rotation.fill(0f)
        shift.fill(0f)
        lift = 0f
    }

    fun turn(part: Int, x: Float = 0f, y: Float = 0f, z: Float = 0f) {
        rotation[part * 3] += x
        rotation[part * 3 + 1] += y
        rotation[part * 3 + 2] += z
    }

    fun raise(part: Int, amount: Float) {
        shift[part * 3 + 1] += amount
    }
}

internal fun poseRig(
    pose: SkinPose,
    time: Float,
    hop: Float,
    capeSwing: Float,
    out: RigPose,
    figure: FigurePose? = null,
) {
    out.reset()
    var capeTilt = CAPE_REST_TILT
    if (figure != null) {
        holdFigure(figure, out)
    } else {
        when (pose) {
            SkinPose.Still -> Unit
            SkinPose.Idle -> capeTilt += breathe(time, out)
            SkinPose.Walk -> capeTilt = walk(time, out)
            SkinPose.Wave -> {
                capeTilt += breathe(time, out)
                wave(time, out)
            }
        }
    }
    if (hop in 0f..1f) {
        val air = sin(PI_F * hop)
        out.lift += HOP_HEIGHT * 4f * hop * (1f - hop)
        out.turn(SkinPart.RIGHT_LEG, x = -0.3f * air)
        out.turn(SkinPart.LEFT_LEG, x = 0.22f * air)
        if (pose != SkinPose.Wave) out.turn(SkinPart.RIGHT_ARM, z = -0.4f * air)
        out.turn(SkinPart.LEFT_ARM, z = 0.4f * air)
        capeTilt += 0.35f * air
    }
    out.rotation[SkinPart.CAPE * 3] += capeTilt + capeSwing
}

private fun partOf(part: EmotePart): Int = when (part) {
    EmotePart.HEAD -> SkinPart.HEAD
    EmotePart.BODY -> SkinPart.BODY
    EmotePart.RIGHT_ARM -> SkinPart.RIGHT_ARM
    EmotePart.LEFT_ARM -> SkinPart.LEFT_ARM
    EmotePart.RIGHT_LEG -> SkinPart.RIGHT_LEG
    EmotePart.LEFT_LEG -> SkinPart.LEFT_LEG
}

/**
 * An emote poses the figure instead of its own motion. The cape hangs apart from
 * the body, so it takes the torso's turn and shift, or it stayed upright while
 * the player danced.
 */
private fun holdFigure(figure: FigurePose, out: RigPose) {
    for (part in EmotePart.entries) {
        if (!figure.posed[part.ordinal]) continue
        val from = part.ordinal * 3
        val to = partOf(part) * 3
        for (axis in 0 until 3) {
            out.rotation[to + axis] = figure.rotation[from + axis]
            out.shift[to + axis] = figure.shift[from + axis]
        }
    }
    if (figure.lifted) out.lift = figure.lift
    val body = SkinPart.BODY * 3
    val cape = SkinPart.CAPE * 3
    for (axis in 0 until 3) {
        out.rotation[cape + axis] = out.rotation[body + axis]
        out.shift[cape + axis] = out.shift[body + axis]
    }
}

private fun breathe(time: Float, out: RigPose): Float {
    val breath = sin(TAU * time / BREATH_PERIOD)
    val bob = -0.3f * (0.5f + 0.5f * breath)
    for (part in UPPER_BODY) out.raise(part, bob)
    val sway = 0.07f * sin(TAU * time / SWAY_PERIOD)
    val spread = 0.035f + 0.025f * breath
    out.turn(SkinPart.RIGHT_ARM, x = sway, z = -spread)
    out.turn(SkinPart.LEFT_ARM, x = -sway, z = spread)
    out.turn(
        SkinPart.HEAD,
        x = 0.035f * sin(TAU * time / NOD_PERIOD),
        y = 0.09f * sin(TAU * time / GLANCE_PERIOD),
    )
    return 0.03f * breath
}

private fun walk(time: Float, out: RigPose): Float {
    val phase = TAU * time / WALK_PERIOD
    val stride = sin(phase)
    val leg = 0.52f * stride
    out.turn(SkinPart.RIGHT_LEG, x = leg)
    out.turn(SkinPart.LEFT_LEG, x = -leg)
    out.turn(SkinPart.RIGHT_ARM, x = -0.45f * stride, z = -0.03f)
    out.turn(SkinPart.LEFT_ARM, x = 0.45f * stride, z = 0.03f)
    out.turn(SkinPart.HEAD, x = 0.03f * sin(phase * 2f))
    out.lift -= LEG_LENGTH * (1f - cos(leg))
    return 0.28f + 0.07f * sin(phase * 2f)
}

private fun wave(time: Float, out: RigPose) {
    val arm = SkinPart.RIGHT_ARM * 3
    out.rotation[arm] = -2.7f
    out.rotation[arm + 1] = 0f
    out.rotation[arm + 2] = 0.38f + 0.28f * sin(TAU * time / WAVE_PERIOD)
    out.turn(SkinPart.HEAD, z = 0.05f)
}

internal class SkinView {
    val basis = FloatArray(9)
    var focal = 1f
        private set
    var centerX = 0f
        private set
    var centerY = 0f
        private set
    var orbitY = REST_ORBIT_Y
        private set

    fun frame(width: Float, height: Float, roomy: Boolean) {
        val fitHeight = if (roomy) ROOMY_FIT_HEIGHT else REST_FIT_HEIGHT
        val fitWidth = if (roomy) ROOMY_FIT_WIDTH else REST_FIT_WIDTH
        focal = min(height / fitHeight, width / fitWidth) * CAMERA_DISTANCE
        centerX = width / 2f
        centerY = height / 2f
        orbitY = if (roomy) ROOMY_ORBIT_Y else REST_ORBIT_Y
    }

    fun orient(yaw: Float, pitch: Float) {
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)
        val cosPitch = cos(pitch)
        val sinPitch = sin(pitch)
        basis[0] = cosYaw
        basis[1] = 0f
        basis[2] = sinYaw
        basis[3] = sinPitch * sinYaw
        basis[4] = cosPitch
        basis[5] = -sinPitch * cosYaw
        basis[6] = -cosPitch * sinYaw
        basis[7] = sinPitch
        basis[8] = cosPitch * cosYaw
    }

    fun scaleAt(depth: Float): Float = focal / max(CAMERA_DISTANCE - depth, NEAR_LIMIT)
}

internal class RigProjection {
    val screenX = FloatArray(BOX_COUNT * CORNERS_PER_BOX)
    val screenY = FloatArray(BOX_COUNT * CORNERS_PER_BOX)
    val viewX = FloatArray(BOX_COUNT * CORNERS_PER_BOX)
    val viewY = FloatArray(BOX_COUNT * CORNERS_PER_BOX)
    val viewZ = FloatArray(BOX_COUNT * CORNERS_PER_BOX)
    private val parts = FloatArray((ROOT_SLOT + 1) * PART_MATRIX)

    /** A point in the frame of a part (or of the figure at the neck, [ROOT_SLOT]) to view space. */
    fun toView(slot: Int, x: Float, y: Float, z: Float, out: FloatArray, at: Int) {
        val m = slot * PART_MATRIX
        out[at] = parts[m] * x + parts[m + 1] * y + parts[m + 2] * z + parts[m + 9]
        out[at + 1] = parts[m + 3] * x + parts[m + 4] * y + parts[m + 5] * z + parts[m + 10]
        out[at + 2] = parts[m + 6] * x + parts[m + 7] * y + parts[m + 8] * z + parts[m + 11]
    }

    fun project(rig: SkinRig, pose: RigPose, view: SkinView) {
        val basis = view.basis
        for (part in 0 until SkinPart.COUNT) {
            val angles = part * 3
            val cosX = cos(pose.rotation[angles])
            val sinX = sin(pose.rotation[angles])
            val cosY = cos(pose.rotation[angles + 1])
            val sinY = sin(pose.rotation[angles + 1])
            val cosZ = cos(pose.rotation[angles + 2])
            val sinZ = sin(pose.rotation[angles + 2])
            val l0 = cosZ * cosY
            val l1 = cosZ * sinY * sinX - sinZ * cosX
            val l2 = cosZ * sinY * cosX + sinZ * sinX
            val l3 = sinZ * cosY
            val l4 = sinZ * sinY * sinX + cosZ * cosX
            val l5 = sinZ * sinY * cosX - cosZ * sinX
            val l6 = -sinY
            val l7 = cosY * sinX
            val l8 = cosY * cosX
            val matrix = part * PART_MATRIX
            for (row in 0 until 3) {
                val b0 = basis[row * 3]
                val b1 = basis[row * 3 + 1]
                val b2 = basis[row * 3 + 2]
                parts[matrix + row * 3] = b0 * l0 + b1 * l3 + b2 * l6
                parts[matrix + row * 3 + 1] = b0 * l1 + b1 * l4 + b2 * l7
                parts[matrix + row * 3 + 2] = b0 * l2 + b1 * l5 + b2 * l8
            }
            val pivotX = rig.pivots[angles] + pose.shift[angles]
            val pivotY = rig.pivots[angles + 1] + pose.shift[angles + 1] + pose.lift - view.orbitY
            val pivotZ = rig.pivots[angles + 2] + pose.shift[angles + 2]
            parts[matrix + 9] = basis[0] * pivotX + basis[1] * pivotY + basis[2] * pivotZ
            parts[matrix + 10] = basis[3] * pivotX + basis[4] * pivotY + basis[5] * pivotZ
            parts[matrix + 11] = basis[6] * pivotX + basis[7] * pivotY + basis[8] * pivotZ
        }
        val root = ROOT_SLOT * PART_MATRIX
        for (cell in 0 until 9) parts[root + cell] = basis[cell]
        val neckY = NECK_Y + pose.lift - view.orbitY
        parts[root + 9] = basis[1] * neckY
        parts[root + 10] = basis[4] * neckY
        parts[root + 11] = basis[7] * neckY
        for (box in 0 until BOX_COUNT) {
            val shape = rig.boxes[box]
            val matrix = shape.part * PART_MATRIX
            for (corner in 0 until CORNERS_PER_BOX) {
                val localX = if ((corner and 1) != 0) shape.x1 else shape.x0
                val localY = if ((corner and 2) != 0) shape.y1 else shape.y0
                val localZ = if ((corner and 4) != 0) shape.z1 else shape.z0
                val x = parts[matrix] * localX + parts[matrix + 1] * localY + parts[matrix + 2] * localZ + parts[matrix + 9]
                val y = parts[matrix + 3] * localX + parts[matrix + 4] * localY + parts[matrix + 5] * localZ + parts[matrix + 10]
                val z = parts[matrix + 6] * localX + parts[matrix + 7] * localY + parts[matrix + 8] * localZ + parts[matrix + 11]
                val index = box * CORNERS_PER_BOX + corner
                viewX[index] = x
                viewY[index] = y
                viewZ[index] = z
                val scale = view.scaleAt(z)
                screenX[index] = view.centerX + x * scale
                screenY[index] = view.centerY - y * scale
            }
        }
    }
}

private val UPPER_BODY = intArrayOf(
    SkinPart.HEAD,
    SkinPart.BODY,
    SkinPart.RIGHT_ARM,
    SkinPart.LEFT_ARM,
    SkinPart.CAPE,
)

private const val PI_F = 3.1415927f
private const val TAU = 6.2831855f

private const val FACES_PER_BOX = 6
private const val CORNERS_PER_BOX = 8
private const val BOX_COUNT = 13
private const val CAPE_BOX = 12
private const val FACE_SLOTS = BOX_COUNT * FACES_PER_BOX
private const val PART_MATRIX = 12

private const val SHEET_UNITS = 64
private const val MAX_SKIN_UNIT = 16
private const val MAX_CAPE_UNIT = 32
private const val CAPE_SHEET_COLUMNS = 22
private const val CAPE_SHEET_ROWS = 17
private const val OPAQUE = 255 shl 24
private const val SOLID_ALPHA = 128

private const val CAMERA_DISTANCE = 96f
private const val NEAR_LIMIT = 1f
private const val REST_ORBIT_Y = 16f
private const val ROOMY_ORBIT_Y = 17.5f
private const val REST_FIT_HEIGHT = 35.5f
private const val ROOMY_FIT_HEIGHT = 40f
private const val REST_FIT_WIDTH = 19f
private const val ROOMY_FIT_WIDTH = 24f

private const val FLOOR_HALF_WIDTH = 5.5f
private const val FLOOR_HALF_DEPTH = 3.25f
private const val FLOOR_ALPHA = 0.2f
private const val FLOOR_FADE_LIFT = 10f

private const val MIN_FACE_AREA = 0.5f
private const val MIN_NORMAL = 0.000001f
private const val SHADE_LEVELS = 9
private const val AMBIENT = 0.6f
private const val DIFFUSE = 0.4f
private const val LIGHT_X = 0.617f
private const val LIGHT_Y = 0.617f
private const val LIGHT_Z = 0.488f

private const val BREATH_PERIOD = 3.2f
private const val SWAY_PERIOD = 2.8f
private const val GLANCE_PERIOD = 7.3f
private const val NOD_PERIOD = 5.1f
private const val WALK_PERIOD = 0.9f
private const val WAVE_PERIOD = 0.75f
private const val HOP_HEIGHT = 3.5f
private const val LEG_LENGTH = 12f
private const val CAPE_REST_TILT = 0.105f

private const val HOME_YAW = 0.5f
private const val HOME_PITCH = 0.2f
private const val PITCH_MIN = -0.05f
private const val PITCH_MAX = 0.55f
private const val TURN_PER_DP = 0.012f
private const val TILT_PER_DP = 0.004f
private const val SPIN_FRICTION = 3.2f
private const val SPIN_REST = 0.06f
private const val SPIN_LIMIT = 14f
private const val RETURN_DELAY = 1.4f
private const val RETURN_RATE = 3.5f
private const val HOP_SECONDS = 0.46f
private const val HOP_RETRIGGER = 0.7f
private const val NO_HOP = -1f
private const val CAPE_SPIN_GAIN = 0.05f
private const val CAPE_SPIN_LIMIT = 0.5f
private const val CAPE_EASE = 6f
private const val SETTLE_EPSILON = 0.002f
private const val MAX_STEP = 0.05f
private const val CLOCK_WRAP = 3600f
private const val NANOS_PER_SECOND = 1_000_000_000f

private const val ROOT_SLOT = SkinPart.COUNT
private const val NECK_Y = 24f
private const val QUADS_INITIAL = 64
private const val SLICE_CACHE_LIMIT = 4096
private const val SLICE_EPSILON = 0.001f
