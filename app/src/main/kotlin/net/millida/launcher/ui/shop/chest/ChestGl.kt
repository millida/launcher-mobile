package net.millida.launcher.ui.shop.chest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val LAMBERT_VS = """
uniform mat4 uMvp;
uniform mat4 uModel;
uniform mat3 uNormal;
attribute vec3 aPos;
attribute vec2 aUv;
attribute vec3 aNormal;
varying vec2 vUv;
varying vec3 vNormal;
varying vec3 vWorld;
void main() {
  vUv = aUv;
  vNormal = uNormal * aNormal;
  vWorld = (uModel * vec4(aPos, 1.0)).xyz;
  gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

// three.js MeshLambertMaterial with physical lights: albedo / PI times the summed irradiance,
// sRGB textures decoded before lighting and the result encoded back to sRGB.
private const val LAMBERT_FS = """
precision mediump float;
uniform sampler2D uTex;
uniform vec3 uAmbient;
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uPointPos;
uniform vec3 uPointColor;
uniform float uPointCutoff;
uniform float uPointDecay;
varying vec2 vUv;
varying vec3 vNormal;
varying vec3 vWorld;
vec3 toLinear(vec3 c) {
  return mix(pow(c * 0.9478672986 + 0.0521327014, vec3(2.4)), c * 0.0773993808, vec3(lessThanEqual(c, vec3(0.04045))));
}
vec3 toSrgb(vec3 c) {
  return mix(pow(c, vec3(0.41666)) * 1.055 - 0.055, c * 12.92, vec3(lessThanEqual(c, vec3(0.0031308))));
}
void main() {
  vec4 tex = texture2D(uTex, vUv);
  if (tex.a < 0.5) discard;
  float len = length(vNormal);
  vec3 n = len > 0.0001 ? vNormal / len : vec3(0.0);
  vec3 irradiance = uAmbient + uSunColor * max(dot(n, uSunDir), 0.0);
  vec3 toLight = uPointPos - vWorld;
  float dist = length(toLight);
  float falloff = 1.0 / max(pow(dist, uPointDecay), 0.01);
  float cut = clamp(1.0 - pow(dist / uPointCutoff, 4.0), 0.0, 1.0);
  falloff *= cut * cut;
  irradiance += uPointColor * falloff * max(dot(n, toLight / max(dist, 0.0001)), 0.0);
  vec3 color = toLinear(tex.rgb) * irradiance * 0.3183098862;
  gl_FragColor = vec4(toSrgb(max(color, vec3(0.0))), tex.a);
}
"""

private const val GLOW_VS = """
uniform mat4 uMvp;
attribute vec3 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
  vUv = aUv;
  gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

private const val GLOW_FS = """
precision mediump float;
uniform sampler2D uTex;
uniform vec3 uColor;
uniform float uOpacity;
varying vec2 vUv;
void main() {
  vec4 tex = texture2D(uTex, vUv);
  gl_FragColor = vec4(uColor * tex.rgb, uOpacity * tex.a);
}
"""

private const val FLAT_VS = """
uniform mat4 uMvp;
attribute vec3 aPos;
void main() {
  gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

private const val FLAT_FS = """
precision mediump float;
uniform vec3 uColor;
void main() {
  gl_FragColor = vec4(uColor, 1.0);
}
"""

internal class GlProgram(vs: String, fs: String, attributes: List<String>) {
    val id: Int
    private val uniforms = HashMap<String, Int>()
    val attribs = HashMap<String, Int>()

    init {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, v)
        GLES20.glAttachShader(id, f)
        attributes.forEachIndexed { index, name -> GLES20.glBindAttribLocation(id, index, name) }
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw IllegalStateException("chest shader did not link: $log")
        }
        attributes.forEachIndexed { index, name -> attribs[name] = index }
    }

    fun u(name: String): Int = uniforms.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

    fun release() = GLES20.glDeleteProgram(id)

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("chest shader did not compile: $log")
        }
        return shader
    }
}

/** A vertex buffer of interleaved floats; [stride] floats per vertex. */
internal class GlMesh(data: FloatArray, val stride: Int) {
    val id: Int
    val count: Int = data.size / stride

    init {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        id = ids[0]
        val buffer: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun release() = GLES20.glDeleteBuffers(1, intArrayOf(id), 0)
}

internal fun uploadTexture(width: Int, height: Int, rgba: ByteArray, nearest: Boolean): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
    val buffer = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder())
    buffer.put(rgba).position(0)
    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
    val filter = if (nearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR
    if (nearest) {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
    } else {
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
    }
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    return ids[0]
}

/** White texels whose alpha is [alpha]; row j is texture t = (j + 0.5) / h, the way three's uv reads it. */
private fun whiteTexture(w: Int, h: Int, alpha: (u: Float, v: Float, x: Int, y: Int) -> Float): Int {
    val data = ByteArray(w * h * 4)
    for (j in 0 until h) {
        for (i in 0 until w) {
            val a = alpha((i + 0.5f) / w, (j + 0.5f) / h, i, j).coerceIn(0f, 1f)
            val o = (j * w + i) * 4
            data[o] = 255.toByte()
            data[o + 1] = 255.toByte()
            data[o + 2] = 255.toByte()
            data[o + 3] = Math.round(a * 255f).toByte()
        }
    }
    return uploadTexture(w, h, data, nearest = false)
}

private fun stops(t: Float, vararg points: Float): Float {
    if (t <= points[0]) return points[1]
    var i = 0
    while (i + 3 < points.size) {
        val t0 = points[i]
        val a0 = points[i + 1]
        val t1 = points[i + 2]
        val a1 = points[i + 3]
        if (t <= t1) return a0 + (a1 - a0) * (t - t0) / (t1 - t0)
        i += 2
    }
    return points[points.size - 1]
}

/** The scene's light textures (chestScene.ts glowTexture, raysTexture, beamTexture, fanTexture). */
internal class ChestEffects {
    val glow: Int = whiteTexture(128, 128) { u, v, _, _ ->
        val r = sqrt((u - 0.5f) * (u - 0.5f) + (v - 0.5f) * (v - 0.5f)) * 2f
        stops(r, 0f, 1f, 0.25f, 0.55f, 0.6f, 0.12f, 1f, 0f)
    }
    val rays: Int = whiteTexture(256, 256) { _, _, x, y ->
        val dx = x + 0.5f - 128f
        val dy = 128f - (y + 0.5f)
        val r = sqrt(dx * dx + dy * dy)
        if (r > 128f) {
            0f
        } else {
            val step = (2.0 * PI / RAY_COUNT).toFloat()
            var a = atan2(dy, dx)
            if (a < 0f) a += (2.0 * PI).toFloat()
            val near = abs(a - Math.round(a / step) * step)
            if (near > RAY_HALF) 0f else stops(r, 8f, 0.9f, 128f, 0f)
        }
    }
    val beam: Int = whiteTexture(4, 128) { _, v, _, _ -> stops(v, 0f, 1f, 0.2f, 0.55f, 0.6f, 0.08f, 1f, 0f) }
    val fan: Int = whiteTexture(64, 256) { u, v, _, _ ->
        val fade = (1f - v).toDouble().pow(1.7).toFloat()
        val x = u * 2f - 1f
        fade * max(0f, 1f - x * x).toDouble().pow(2.2).toFloat()
    }

    fun release() = GLES20.glDeleteTextures(4, intArrayOf(glow, rays, beam, fan), 0)

    private companion object {
        const val RAY_COUNT = 14
        const val RAY_HALF = 0.09f
    }
}

/** PlaneGeometry(1, 1): pos.xyz, uv; [lift] moves it up by half, the fan's base at its foot. */
internal fun planeData(lift: Float = 0f): FloatArray {
    val tl = floatArrayOf(-0.5f, 0.5f + lift, 0f, 0f, 1f)
    val tr = floatArrayOf(0.5f, 0.5f + lift, 0f, 1f, 1f)
    val bl = floatArrayOf(-0.5f, -0.5f + lift, 0f, 0f, 0f)
    val br = floatArrayOf(0.5f, -0.5f + lift, 0f, 1f, 0f)
    return tl + bl + tr + bl + br + tr
}

/** An open CylinderGeometry, as triangles of pos.xyz, uv. */
internal fun cylinderData(top: Float, bottom: Float, height: Float, segments: Int): FloatArray {
    val out = ArrayList<Float>()
    val half = height / 2f
    fun vertex(x: Int, y: Int) {
        val v = y.toFloat()
        val radius = v * (bottom - top) + top
        val u = x.toFloat() / segments
        val theta = u * 2f * PI.toFloat()
        out += radius * sin(theta)
        out += -v * height + half
        out += radius * cos(theta)
        out += u
        out += 1f - v
    }
    for (x in 0 until segments) {
        vertex(x, 0)
        vertex(x, 1)
        vertex(x + 1, 0)
        vertex(x, 1)
        vertex(x + 1, 1)
        vertex(x + 1, 0)
    }
    return out.toFloatArray()
}

/** BoxGeometry of side [side], positions only. */
internal fun boxData(side: Float): FloatArray {
    val h = side / 2f
    val faces = arrayOf(
        floatArrayOf(h, h, h, h, -h, h, h, -h, -h, h, h, -h),
        floatArrayOf(-h, h, -h, -h, -h, -h, -h, -h, h, -h, h, h),
        floatArrayOf(-h, h, -h, -h, h, h, h, h, h, h, h, -h),
        floatArrayOf(-h, -h, h, -h, -h, -h, h, -h, -h, h, -h, h),
        floatArrayOf(-h, h, h, -h, -h, h, h, -h, h, h, h, h),
        floatArrayOf(h, h, -h, h, -h, -h, -h, -h, -h, -h, h, -h),
    )
    val out = ArrayList<Float>()
    for (f in faces) {
        for (i in intArrayOf(0, 1, 2, 0, 2, 3)) {
            out += f[i * 3]
            out += f[i * 3 + 1]
            out += f[i * 3 + 2]
        }
    }
    return out.toFloatArray()
}

/** One draw of a cube: a texture and a vertex range of the model buffer. */
internal class CubeDraw(val texture: Int, val first: Int, val count: Int)

/** A node of the rig on the GPU: a bone with children, or a cube with its draws. */
internal class RigNode(
    val uuid: String,
    val name: String,
    val position: FloatArray,
    val rotationDeg: FloatArray,
    val bone: Boolean,
    val children: List<RigNode>,
    val draws: List<CubeDraw>,
)

/** The model of one tier (and tint) uploaded: one vertex buffer, its textures and the node tree. */
internal class GlChestModel(val rig: ChestRigModel, val mesh: GlMesh, val textures: IntArray, val roots: List<RigNode>) {
    fun release() {
        mesh.release()
        GLES20.glDeleteTextures(textures.size, textures, 0)
    }
}

private val FACE_ORDER = BbFaceName.entries

private fun faceCorners(face: BbFaceName, a: FloatArray, b: FloatArray): Array<FloatArray> {
    val x0 = a[0]
    val y0 = a[1]
    val z0 = a[2]
    val x1 = b[0]
    val y1 = b[1]
    val z1 = b[2]
    return when (face) {
        BbFaceName.EAST -> arrayOf(floatArrayOf(x1, y1, z1), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y0, z0))
        BbFaceName.WEST -> arrayOf(floatArrayOf(x0, y1, z0), floatArrayOf(x0, y1, z1), floatArrayOf(x0, y0, z0), floatArrayOf(x0, y0, z1))
        BbFaceName.UP -> arrayOf(floatArrayOf(x0, y1, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z1), floatArrayOf(x1, y1, z1))
        BbFaceName.DOWN -> arrayOf(floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0))
        BbFaceName.SOUTH -> arrayOf(floatArrayOf(x0, y1, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1))
        BbFaceName.NORTH -> arrayOf(floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x0, y0, z0))
    }
}

/** Corner uv in the order of [faceCorners]: top-left, top-right, bottom-left, bottom-right, with the face's rotation. */
private fun faceUv(f: BbFace, tex: BbTexture): Array<FloatArray> {
    val u1 = f.uv[0]
    val v1 = f.uv[1]
    val u2 = f.uv[2]
    val v2 = f.uv[3]
    val ring = arrayOf(floatArrayOf(u1, v1), floatArrayOf(u2, v1), floatArrayOf(u2, v2), floatArrayOf(u1, v2))
    val steps = f.rotation / 90
    fun at(corner: Int) = ring[(corner - steps + 4) % 4]
    return arrayOf(at(0), at(1), at(3), at(2)).map { floatArrayOf(it[0] / tex.uvWidth, it[1] / tex.uvHeight) }.toTypedArray()
}

/** three's computeVertexNormals for a triangle (A, B, C) is (C - B) x (A - B); called as cross(B, C, A). */
private fun cross(o: FloatArray, a: FloatArray, b: FloatArray): FloatArray {
    val ax = a[0] - o[0]
    val ay = a[1] - o[1]
    val az = a[2] - o[2]
    val bx = b[0] - o[0]
    val by = b[1] - o[1]
    val bz = b[2] - o[2]
    return floatArrayOf(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)
}

private fun grow(lo: Float, hi: Float, d: Float): FloatArray = if (lo <= hi) floatArrayOf(lo - d, hi + d) else floatArrayOf(lo + d, hi - d)

/** Decodes the embedded png without premultiplying, optionally recoloured, as RGBA bytes. */
internal fun decodeTexture(tex: BbTexture, tint: Int?): Triple<Int, Int, ByteArray>? {
    val bytes = try {
        Base64.decode(tex.pngBase64, Base64.DEFAULT)
    } catch (error: IllegalArgumentException) {
        return null
    }
    val options = BitmapFactory.Options().apply {
        inPremultiplied = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    bitmap.recycle()
    if (tint != null) tintPixels(pixels, tint)
    val out = ByteArray(w * h * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        out[i * 4] = (p shr 16).toByte()
        out[i * 4 + 1] = (p shr 8).toByte()
        out[i * 4 + 2] = p.toByte()
        out[i * 4 + 3] = (p ushr 24).toByte()
    }
    return Triple(w, h, out)
}

/** Builds the vertex buffer (pos, uv, normal) and the node tree of a model, as buildBbRig does. */
internal fun uploadModel(rig: ChestRigModel, tint: Int?): GlChestModel {
    val model = rig.model
    val textures = IntArray(model.textures.size) { index ->
        val decoded = decodeTexture(model.textures[index], tint)
        if (decoded == null) {
            uploadTexture(1, 1, byteArrayOf(0, 0, 0, 0), nearest = true)
        } else {
            uploadTexture(decoded.first, decoded.second, decoded.third, nearest = true)
        }
    }
    val data = ArrayList<Float>()

    fun cubeDraws(c: BbCube): List<CubeDraw> {
        val xs = grow(c.from[0], c.to[0], c.inflate)
        val ys = grow(c.from[1], c.to[1], c.inflate)
        val zs = grow(c.from[2], c.to[2], c.inflate)
        val a = floatArrayOf(xs[0] - c.origin[0], ys[0] - c.origin[1], zs[0] - c.origin[2])
        val b = floatArrayOf(xs[1] - c.origin[0], ys[1] - c.origin[1], zs[1] - c.origin[2])
        val byTexture = LinkedHashMap<Int, ArrayList<Float>>()
        for (name in FACE_ORDER) {
            val f = c.faces[name] ?: continue
            val p = faceCorners(name, a, b)
            val uv = faceUv(f, model.textures[f.texture])
            val list = byTexture.getOrPut(f.texture) { ArrayList() }
            for ((v0, v1, v2) in listOf(Triple(0, 2, 1), Triple(2, 3, 1))) {
                val n = cross(p[v1], p[v2], p[v0])
                for (v in intArrayOf(v0, v1, v2)) {
                    list += p[v][0]
                    list += p[v][1]
                    list += p[v][2]
                    list += uv[v][0]
                    list += uv[v][1]
                    list += n[0]
                    list += n[1]
                    list += n[2]
                }
            }
        }
        return byTexture.map { (texture, floats) ->
            val first = data.size / VERTEX_FLOATS
            data += floats
            CubeDraw(textures[texture], first, floats.size / VERTEX_FLOATS)
        }
    }

    fun place(n: BbNode, parentOrigin: FloatArray): RigNode {
        val position = floatArrayOf(n.origin[0] - parentOrigin[0], n.origin[1] - parentOrigin[1], n.origin[2] - parentOrigin[2])
        return when (n) {
            is BbBone -> RigNode(n.uuid, n.name, position, n.rotation, true, n.children.map { place(it, n.origin) }, emptyList())
            is BbCube -> RigNode(n.uuid, "", position, n.rotation, false, emptyList(), cubeDraws(n))
        }
    }

    val roots = model.roots.map { place(it, floatArrayOf(0f, 0f, 0f)) }
    val mesh = GlMesh(if (data.isEmpty()) FloatArray(VERTEX_FLOATS) else data.toFloatArray(), VERTEX_FLOATS)
    return GlChestModel(rig, mesh, textures, roots)
}

internal const val VERTEX_FLOATS = 8

internal class ChestPrograms {
    val lambert = GlProgram(LAMBERT_VS, LAMBERT_FS, listOf("aPos", "aUv", "aNormal"))
    val glow = GlProgram(GLOW_VS, GLOW_FS, listOf("aPos", "aUv"))
    val flat = GlProgram(FLAT_VS, FLAT_FS, listOf("aPos"))

    fun release() {
        lambert.release()
        glow.release()
        flat.release()
    }
}

internal fun clamp01(value: Float): Float = min(1f, max(0f, value))
