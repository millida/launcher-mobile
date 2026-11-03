package net.millida.launcher.ui.shop.chest

import android.opengl.GLES20
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * closed — taken: stands and sways; ready — waiting: idles, glows and asks to
 * be opened; shake — opening: the shake grows, light leaks from the gap;
 * open — the lid flies back, a pillar of light and sparks.
 */
internal enum class ChestMode { Closed, Ready, Shake, Open }

internal fun chestEaseOutBack(t: Float): Float {
    val c1 = 2.2f
    val c3 = c1 + 1f
    return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
}

private class FanBeam(val a: Float, val w: Float, val h: Float, val o: Float, val sp: Float, val ph: Float, val core: Boolean)

private val FAN = listOf(
    FanBeam(-0.62f, 5f, 17f, 0.5f, 0.9f, 0.3f, false),
    FanBeam(-0.36f, 6f, 22f, 0.75f, 1.3f, 2.1f, false),
    FanBeam(-0.14f, 4f, 26f, 0.9f, 1.1f, 4.0f, true),
    FanBeam(0.1f, 7f, 25f, 0.85f, 0.8f, 1.2f, false),
    FanBeam(0.34f, 5f, 21f, 0.7f, 1.5f, 3.3f, true),
    FanBeam(0.6f, 5f, 16f, 0.5f, 1.0f, 5.2f, false),
)

private class Spark {
    var x = 0f
    var y = -999f
    var z = 0f
    var vx = 0f
    var vy = 0f
    var vz = 0f
    var life = 0f
    var max = 1f
    var s = 1f
    var g = 42f
}

private const val SPARKS = 56
private const val AJAR = 0.3f
private const val BREATH = 0.052f
private const val MODEL_SCALE = 15f / 16f
private const val GAP_Z = 4f
private const val GAP_Y = 7.6f
private const val SHAKE_IDLE_SPEED = 1.8f
private const val IDLE_FRAME_MS = 30.0
private const val BEAM_H = 30f
private const val FOV = 30f
private const val DEG = (PI / 180.0).toFloat()
private val CAMERA = floatArrayOf(0f, 22f, 62f)
private val TARGET = floatArrayOf(0f, 12f, 0f)
private val SUN_DIR = run {
    val l = sqrt(20f * 20f + 40f * 40f + 30f * 30f)
    floatArrayOf(20f / l, 40f / l, 30f / l)
}

private fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c * 0.0773993808f else (c * 0.9478672986f + 0.0521327014f).pow(2.4f)

private fun rgb(argb: Int): FloatArray = floatArrayOf(((argb shr 16) and 255) / 255f, ((argb shr 8) and 255) / 255f, (argb and 255) / 255f)

/** Everything the chest scenes share in one GL context: shaders, light textures, meshes, uploaded models. */
internal class ChestGlShared(private val loadModel: (String) -> ChestRigModel?) {
    val programs = ChestPrograms()
    val effects = ChestEffects()
    val plane = GlMesh(planeData(), 5)
    val fanPlane = GlMesh(planeData(lift = 0.5f), 5)
    val beam = GlMesh(cylinderData(6.5f, 5f, BEAM_H, 20), 5)
    val core = GlMesh(cylinderData(3f, 2.4f, BEAM_H, 12), 5)
    val box = GlMesh(boxData(0.9f), 3)
    private val models = HashMap<String, GlChestModel?>()

    fun model(tier: String, tint: Int?): GlChestModel? {
        val file = chestModelFile(tier)
        val key = file + "|" + (tint?.toString(16) ?: "")
        return models.getOrPut(key) { loadModel(file)?.let { uploadModel(it, tint) } }
    }

    fun release() {
        programs.release()
        effects.release()
        plane.release()
        fanPlane.release()
        beam.release()
        core.release()
        box.release()
        models.values.forEach { it?.release() }
        models.clear()
    }
}

/**
 * One chest on screen, the desktop's chestScene.ts frame by frame: the same
 * camera, lights, Blockbench rig and clips, glow sprite, rays, light fan from
 * the gap, the pillar and sparks. Lives on the GL thread only.
 */
internal class ChestGlScene(
    tier: String,
    private var tint: Int?,
    private val reduced: Boolean,
    private val onOpened: () -> Unit,
) {
    private var tier = tier
    private var mode = ChestMode.Closed
    private var modeAt = 0.0
    private var opened = false
    private var model: GlChestModel? = null
    private var built = false
    private var width = 1
    private var height = 1
    private var frameSize = 60f
    private var raysSize = 60f
    private var last = -1.0
    private var trickle = 0f
    private var ambient = 0f
    private var fanK = 0f
    private var yaw = -0.5f
    private val sparks = Array(SPARKS) { Spark() }
    private val random = Random(tier.hashCode())
    private var glowColor = floatArrayOf(1f, 1f, 1f)
    private var sparkColor = floatArrayOf(1f, 1f, 1f)
    private var innerLinear = floatArrayOf(1f, 1f, 1f)
    private val sparkColors = Array(SPARKS) { floatArrayOf(1f, 1f, 1f) }
    private var poses = HashMap<String, FloatArray>()
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val viewProj = FloatArray(16)

    val idle: Boolean get() = mode == ChestMode.Closed || mode == ChestMode.Ready

    fun setMode(next: ChestMode, now: Double) {
        if (next == mode && modeAt != 0.0) return
        mode = next
        modeAt = now
        if (next != ChestMode.Open) opened = false
    }

    fun setLook(nextTier: String, nextTint: Int?) {
        if (nextTier == tier && nextTint == tint) return
        tier = nextTier
        tint = nextTint
        built = false
    }

    fun resize(w: Int, h: Int) {
        width = max(1, w)
        height = max(1, h)
        val aspect = width.toFloat() / height
        Mat4.perspective(FOV, aspect, 1f, 400f, proj)
        Mat4.lookAt(CAMERA, TARGET, view)
        Mat4.multiply(proj, view, viewProj)
        raysSize = frameAt(floatArrayOf(0f, 10f, -20f), aspect)
        frameSize = frameAt(floatArrayOf(0f, 8f, -12f), aspect)
    }

    private fun frameAt(at: FloatArray, aspect: Float): Float {
        val dx = CAMERA[0] - at[0]
        val dy = CAMERA[1] - at[1]
        val dz = CAMERA[2] - at[2]
        val visH = 2f * sqrt(dx * dx + dy * dy + dz * dz) * tan(FOV * DEG / 2f)
        return min(visH, visH * aspect)
    }

    private fun build(shared: ChestGlShared) {
        built = true
        model = shared.model(tier, tint) ?: throw IllegalStateException("no chest model for $tier")
        val palette = tint
        val glow: Int
        val spark: Int
        if (palette != null) {
            glow = palette
            spark = 0xFFFFFF
        } else {
            val p = chestGlowColors(tier)
            glow = p.first
            spark = p.second
        }
        glowColor = rgb(glow)
        sparkColor = rgb(spark)
        innerLinear = FloatArray(3) { srgbToLinear(glowColor[it]) }
        for (i in 0 until SPARKS) sparkColors[i] = if (i % 3 == 0) floatArrayOf(1f, 1f, 1f) else if (i % 2 == 1) glowColor else sparkColor
    }

    private fun emitGap(n: Int) {
        var left = n
        for (s in sparks) {
            if (left <= 0) break
            if (s.life > 0f) continue
            s.x = (random.nextFloat() - 0.5f) * 11f
            s.z = 3f + random.nextFloat() * 4.5f
            s.y = GAP_Y + 0.6f
            s.vx = (random.nextFloat() - 0.5f) * 2.4f
            s.vz = (random.nextFloat() - 0.2f) * 1.4f
            s.vy = 6f + random.nextFloat() * 7f
            s.g = 1.2f + random.nextFloat() * 1.6f
            s.max = 1.7f + random.nextFloat() * 1.2f
            s.life = s.max
            s.s = 0.28f + random.nextFloat() * 0.4f
            left--
        }
    }

    private fun emit(n: Int, power: Float) {
        var left = n
        for (s in sparks) {
            if (left <= 0) break
            if (s.life > 0f) continue
            val a = random.nextFloat() * PI.toFloat() * 2f
            val r = random.nextFloat() * 5f
            s.x = kotlin.math.cos(a) * r
            s.z = sin(a) * r
            s.y = 10f
            val speed = (0.5f + random.nextFloat()) * power
            s.vx = kotlin.math.cos(a) * speed * 9f
            s.vz = sin(a) * speed * 9f
            s.vy = (26f + random.nextFloat() * 30f) * power
            s.max = 0.9f + random.nextFloat() * 0.9f
            s.life = s.max
            s.s = 0.6f + random.nextFloat() * 0.9f
            s.g = 42f
            left--
        }
    }

    /** Draws one frame into the current surface; false when an idle chest skips the frame. */
    fun frame(now: Double, shared: ChestGlShared): Boolean {
        if (!built) build(shared)
        if (idle && last >= 0.0 && now - last < IDLE_FRAME_MS) return false
        val dt = if (last < 0.0) 0f else min(0.05f, ((now - last) / 1000.0).toFloat())
        last = now
        val t = (now / 1000.0).toFloat()
        val since = ((now - modeAt) / 1000.0).toFloat()
        val rig = model?.rig

        var glowK = 0f
        var raysK = 0f
        var beamK = 0f
        var innerK = 0f
        var shakeX = 0f
        var shakeZ = 0f
        var squash = 1f

        when {
            mode == ChestMode.Closed -> yaw = -0.5f + if (reduced) 0f else sin(t * 0.6f) * 0.06f
            mode == ChestMode.Ready -> {
                yaw = -0.5f + if (reduced) 0f else sin(t * 0.9f) * 0.12f
                glowK = 0.55f + 0.25f * sin(t * 3.2f)
                raysK = 0.18f
                if (!reduced && (t % 2.6f) / 2.6f > 0.82f) innerK = 0.8f
            }
            mode == ChestMode.Shake -> {
                val k = min(1f, since / 1.3f)
                glowK = 0.6f + k * 0.8f
                raysK = 0.2f + k * 0.4f
                innerK = 0.6f + k * 2.4f
                if (!reduced) {
                    val amp = 0.04f + k * 0.16f
                    shakeZ = sin(t * (30f + k * 26f)) * amp
                    shakeX = sin(t * (24f + k * 20f) + 1.3f) * amp * 5f
                    squash = 1f - abs(sin(t * 22f)) * 0.03f * k
                    trickle += dt * (4f + k * 18f)
                    while (trickle > 1f) {
                        emit(1, 0.35f)
                        trickle -= 1f
                    }
                }
            }
            rig != null && !reduced && since < rig.lidAt -> {
                glowK = 1.4f
                raysK = 0.6f
                innerK = 3f
            }
            else -> {
                if (!opened) {
                    opened = true
                    if (!reduced) emit(40, 1f)
                    onOpened()
                }
                val lit = since - if (rig != null && !reduced) rig.lidAt else 0f
                val flash = if (reduced) 0f else max(0f, 1f - lit / 0.5f)
                glowK = 1.3f + flash * 2.2f + 0.15f * sin(t * 2f)
                raysK = min(1f, lit / 0.5f) * 0.85f
                beamK = min(1f, lit / 0.35f)
                innerK = 3.2f
                if (!reduced) {
                    yaw = -0.5f + sin(t * 0.7f) * 0.08f
                    trickle += dt * 10f
                    while (trickle > 1f) {
                        emit(1, 0.55f)
                        trickle -= 1f
                    }
                }
            }
        }

        val ajar = if (mode == ChestMode.Open) 0f else AJAR + if (reduced) 0f else sin(t * 1.4f) * BREATH
        val fanTarget = when (mode) {
            ChestMode.Open -> 0.3f
            ChestMode.Shake -> 1.25f
            ChestMode.Ready -> 1f
            ChestMode.Closed -> 0.62f
        }
        fanK = if (reduced) fanTarget else fanK + (fanTarget - fanK) * min(1f, dt * 5f)

        poses.clear()
        if (rig != null) {
            when {
                mode == ChestMode.Open -> pose(rig.open, if (reduced) Float.MAX_VALUE else since)
                reduced || mode == ChestMode.Closed -> Unit
                else -> pose(rig.idle, if (mode == ChestMode.Shake) since * SHAKE_IDLE_SPEED else t)
            }
        }

        val bloom = if (mode == ChestMode.Open) 0f else fanK * 0.5f * (1f + 0.15f * if (reduced) 0f else sin(t * 1.8f))
        glowK += bloom
        val gs = min(frameSize * 0.95f, 30f + glowK * 12f)
        val glowOpacity = min(1f, glowK * 0.55f)
        val beamOpacity = beamK * (0.55f + 0.1f * sin(t * 5f))
        val coreOpacity = beamK * 0.8f
        val beamScale = max(0.001f, beamK)
        val beamY = 10f + (BEAM_H / 2f) * beamK
        val pointIntensity = innerK * 900f
        val pulse = if (reduced) 0f else sin(t * 1.8f)
        val gk = fanK * (0.85f + 0.15f * pulse)

        if (!reduced && mode != ChestMode.Open) {
            ambient += dt * when (mode) {
                ChestMode.Closed -> 3.5f
                ChestMode.Shake -> 9f
                else -> 6f
            }
            while (ambient > 1f) {
                emitGap(1)
                ambient -= 1f
            }
        }

        var alive = false
        for (s in sparks) {
            if (s.life > 0f) {
                alive = true
                s.life -= dt
                s.vy -= s.g * dt
                s.x += s.vx * dt
                s.y += s.vy * dt
                s.z += s.vz * dt
            }
        }

        val chestM = Mat4.compose(0f, 0f, 0f, 0f, yaw, 0f, 1f, 1f, 1f)
        val stretch = 1f + (1f - squash) * 0.6f
        val hopLocal = Mat4.compose(shakeX, 0f, 0f, 0f, 0f, shakeZ, stretch, squash, stretch)
        val hop = Mat4.multiply(chestM, hopLocal)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)

        val current = model
        if (current != null) {
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_BACK)
            GLES20.glFrontFace(GLES20.GL_CCW)
            val root = Mat4.multiply(hop, Mat4.compose(0f, 0f, 0f, 0f, PI.toFloat(), 0f, MODEL_SCALE, MODEL_SCALE, MODEL_SCALE))
            drawModel(shared, current, root, ajar, pointIntensity)
        }

        GLES20.glDisable(GLES20.GL_CULL_FACE)
        if (alive) drawSparks(shared, hop, t)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        val glowProg = shared.programs.glow
        GLES20.glUseProgram(glowProg.id)

        if (raysK > 0f) {
            val rays = Mat4.compose(0f, 10f, -20f, 0f, 0f, t * 0.25f, raysSize, raysSize, 1f)
            drawGlow(shared, shared.plane, rays, shared.effects.rays, glowColor, raysK)
        }
        if (glowOpacity > 0f) drawSprite(shared, Mat4.compose(0f, 8f, -12f, 0f, 0f, 0f, gs, gs, 1f), shared.effects.glow, glowColor, glowOpacity)

        if (beamK > 0f) {
            val beam = Mat4.multiply(hop, Mat4.compose(0f, beamY, 0f, 0f, 0f, 0f, 1f, beamScale, 1f))
            drawGlow(shared, shared.beam, beam, shared.effects.beam, glowColor, beamOpacity)
            drawGlow(shared, shared.core, beam, shared.effects.beam, sparkColor, coreOpacity)
        }

        val fan = Mat4.multiply(hop, Mat4.compose(0f, GAP_Y + 0.2f, GAP_Z, 0f, -yaw, 0f, 1f, 1f, 1f))
        for (b in FAN) {
            val w = if (reduced) 0f else sin(t * b.sp + b.ph)
            val opacity = min(1f, fanK * b.o * 0.85f * (0.7f + 0.3f * w))
            if (opacity <= 0f) continue
            val m = Mat4.multiply(fan, Mat4.compose(0f, 0f, 0f, 0f, 0f, b.a + w * 0.035f, b.w * (1f + 0.1f * w), b.h * (1f + 0.07f * w + 0.03f * pulse), 1f))
            drawGlow(shared, shared.fanPlane, m, shared.effects.fan, if (b.core) sparkColor else glowColor, opacity)
        }
        val gapOpacity = min(1f, gk * 0.8f)
        if (gapOpacity > 0f) {
            drawSprite(shared, Mat4.multiply(hop, Mat4.compose(0f, GAP_Y + 1.2f, GAP_Z, 0f, 0f, 0f, 15f + 3f * gk, 11f + 2f * gk, 1f)), shared.effects.glow, glowColor, gapOpacity)
        }
        val poolOpacity = min(1f, gk * 0.9f)
        if (poolOpacity > 0f) {
            val pool = Mat4.multiply(hop, Mat4.compose(0f, GAP_Y + 0.08f, 0f, -PI.toFloat() / 2f, 0f, 0f, 12.6f, 12.6f, 1f))
            drawGlow(shared, shared.plane, pool, shared.effects.glow, glowColor, poolOpacity)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        return true
    }

    private fun pose(clip: BbClip?, time: Float) {
        if (clip == null) return
        val at = clipTime(clip, time)
        for (track in clip.tracks) {
            val value = sampleTrack(track.keys, at)
            val entry = poses.getOrPut(track.bone) { floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, Float.NaN, 1f, 1f) }
            when (track.channel) {
                BbChannel.ROTATION -> {
                    entry[0] += value[0]
                    entry[1] += value[1]
                    entry[2] += value[2]
                }
                BbChannel.POSITION -> {
                    entry[3] += value[0]
                    entry[4] += value[1]
                    entry[5] += value[2]
                }
                BbChannel.SCALE -> {
                    entry[6] = value[0]
                    entry[7] = value[1]
                    entry[8] = value[2]
                }
            }
        }
    }

    private fun drawModel(shared: ChestGlShared, m: GlChestModel, root: FloatArray, ajar: Float, point: Float) {
        val prog = shared.programs.lambert
        GLES20.glUseProgram(prog.id)
        GLES20.glUniform3f(prog.u("uAmbient"), 1.35f, 1.35f, 1.35f)
        GLES20.glUniform3f(prog.u("uSunDir"), SUN_DIR[0], SUN_DIR[1], SUN_DIR[2])
        GLES20.glUniform3f(prog.u("uSunColor"), 2.1f, 2.1f, 2.1f)
        GLES20.glUniform3f(prog.u("uPointPos"), 0f, 13f, 3f)
        GLES20.glUniform3f(prog.u("uPointColor"), innerLinear[0] * point, innerLinear[1] * point, innerLinear[2] * point)
        GLES20.glUniform1f(prog.u("uPointCutoff"), 60f)
        GLES20.glUniform1f(prog.u("uPointDecay"), 1.6f)
        GLES20.glUniform1i(prog.u("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.mesh.id)
        val stride = VERTEX_FLOATS * 4
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, stride, 12)
        GLES20.glEnableVertexAttribArray(2)
        GLES20.glVertexAttribPointer(2, 3, GLES20.GL_FLOAT, false, stride, 20)
        val mvp = FloatArray(16)
        val normal = FloatArray(9)
        fun walk(node: RigNode, parent: FloatArray) {
            var rx = node.rotationDeg[0] * DEG
            var ry = node.rotationDeg[1] * DEG
            var rz = node.rotationDeg[2] * DEG
            var px = node.position[0]
            var py = node.position[1]
            var pz = node.position[2]
            var sx = 1f
            var sy = 1f
            var sz = 1f
            if (node.bone) {
                val p = poses[node.uuid]
                if (p != null) {
                    rx += p[0] * DEG
                    ry += p[1] * DEG
                    rz += p[2] * DEG
                    px += p[3]
                    py += p[4]
                    pz += p[5]
                    if (!p[6].isNaN()) {
                        sx = p[6]
                        sy = p[7]
                        sz = p[8]
                    }
                }
                if (node.name == LID_NAME && mode != ChestMode.Open) rx += ajar
            }
            val world = Mat4.multiply(parent, Mat4.compose(px, py, pz, rx, ry, rz, sx, sy, sz, EulerOrder.ZYX))
            if (node.draws.isNotEmpty()) {
                Mat4.multiply(viewProj, world, mvp)
                Mat4.normalMatrix(world, normal)
                GLES20.glUniformMatrix4fv(prog.u("uMvp"), 1, false, mvp, 0)
                GLES20.glUniformMatrix4fv(prog.u("uModel"), 1, false, world, 0)
                GLES20.glUniformMatrix3fv(prog.u("uNormal"), 1, false, normal, 0)
                for (d in node.draws) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, d.texture)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, d.first, d.count)
                }
            }
            for (child in node.children) walk(child, world)
        }
        for (r in m.roots) walk(r, root)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glDisableVertexAttribArray(2)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawSparks(shared: ChestGlShared, hop: FloatArray, t: Float) {
        val prog = shared.programs.flat
        GLES20.glUseProgram(prog.id)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, shared.box.id)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 12, 0)
        val mvp = FloatArray(16)
        sparks.forEachIndexed { i, s ->
            if (s.life <= 0f) return@forEachIndexed
            val k = max(0f, s.life / s.max)
            val scale = s.s * k
            val local = Mat4.compose(s.x, s.y, s.z, t * 3f + i, t * 2f + i, 0f, scale, scale, scale)
            Mat4.multiply(viewProj, Mat4.multiply(hop, local), mvp)
            val c = sparkColors[i]
            GLES20.glUniformMatrix4fv(prog.u("uMvp"), 1, false, mvp, 0)
            GLES20.glUniform3f(prog.u("uColor"), c[0], c[1], c[2])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, shared.box.count)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawGlow(shared: ChestGlShared, mesh: GlMesh, world: FloatArray, texture: Int, color: FloatArray, opacity: Float) {
        drawGlowMvp(shared, mesh, Mat4.multiply(viewProj, world), texture, color, opacity)
    }

    /** A three.js Sprite: faces the camera, sized by its world scale. */
    private fun drawSprite(shared: ChestGlShared, world: FloatArray, texture: Int, color: FloatArray, opacity: Float) {
        val center = Mat4.transformPoint(Mat4.multiply(view, world), 0f, 0f, 0f)
        val sx = Mat4.columnLength(world, 0)
        val sy = Mat4.columnLength(world, 1)
        val billboard = Mat4.compose(center[0], center[1], center[2], 0f, 0f, 0f, sx, sy, 1f)
        drawGlowMvp(shared, shared.plane, Mat4.multiply(proj, billboard), texture, color, opacity)
    }

    private fun drawGlowMvp(shared: ChestGlShared, mesh: GlMesh, mvp: FloatArray, texture: Int, color: FloatArray, opacity: Float) {
        val prog = shared.programs.glow
        GLES20.glUseProgram(prog.id)
        GLES20.glUniformMatrix4fv(prog.u("uMvp"), 1, false, mvp, 0)
        GLES20.glUniform3f(prog.u("uColor"), color[0], color[1], color[2])
        GLES20.glUniform1f(prog.u("uOpacity"), opacity)
        GLES20.glUniform1i(prog.u("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.id)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 20, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 20, 12)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.count)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private companion object {
        const val LID_NAME = "chest_up"
    }
}

/** The glow and spark colours of a tier's chest (chestPaint.ts CHEST_PALETTE), as 0xRRGGBB. */
internal fun chestGlowColors(tier: String): Pair<Int, Int> = when (tier.uppercase()) {
    "RARE" -> 0x58B0FF to 0xFFE685
    "EPIC" -> 0xC07BFF to 0xFFE685
    "LEGEND", "LEGENDARY" -> 0xFFD84A to 0xFFFBE0
    else -> 0xFFD56A to 0xFFF1B8
}
