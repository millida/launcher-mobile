package net.millida.launcher.ui.shop.chest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The desktop's Blockbench chest models (millida-launcher components/daily/bbModel.ts),
 * read the same way: cubes in bones, embedded png textures, bone keyframe clips.
 */
internal class BbModelError(message: String) : IllegalArgumentException(message)

internal enum class BbFaceName { NORTH, EAST, SOUTH, WEST, UP, DOWN }

internal class BbFace(val uv: FloatArray, val texture: Int, val rotation: Int)

internal sealed interface BbNode {
    val uuid: String
    val origin: FloatArray
    val rotation: FloatArray
}

internal class BbCube(
    override val uuid: String,
    val from: FloatArray,
    val to: FloatArray,
    override val origin: FloatArray,
    override val rotation: FloatArray,
    val inflate: Float,
    val faces: Map<BbFaceName, BbFace>,
) : BbNode

internal class BbBone(
    override val uuid: String,
    val name: String,
    override val origin: FloatArray,
    override val rotation: FloatArray,
    val children: List<BbNode>,
) : BbNode

internal class BbTexture(val pngBase64: String, val uvWidth: Float, val uvHeight: Float)

internal enum class BbChannel { ROTATION, POSITION, SCALE }

internal enum class BbInterpolation { LINEAR, CATMULLROM, STEP }

internal class BbKeyframe(val time: Float, val value: FloatArray, val interpolation: BbInterpolation)

internal class BbTrack(val bone: String, val channel: BbChannel, val keys: List<BbKeyframe>)

internal enum class BbLoop { LOOP, HOLD, ONCE }

internal class BbClip(val name: String, val loop: BbLoop, val length: Float, val tracks: List<BbTrack>)

internal class BbModel(val roots: List<BbNode>, val textures: List<BbTexture>, val clips: List<BbClip>)

private const val PNG_PREFIX = "data:image/png;base64,"
private const val MAX_DEPTH = 32
private val JSON = Json { ignoreUnknownKeys = true }

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

private fun num(value: JsonElement?, what: String): Float {
    val primitive = value as? JsonPrimitive ?: throw BbModelError("bbmodel: $what is not a number")
    val parsed = if (primitive.isString) {
        val text = primitive.content.trim()
        if (text.isEmpty()) 0.0 else text.toDoubleOrNull()
    } else {
        primitive.doubleOrNull
    }
    if (parsed == null || !parsed.isFinite()) throw BbModelError("bbmodel: $what is not a number")
    return parsed.toFloat()
}

private fun vec(value: JsonElement?, what: String, fallback: FloatArray? = null): FloatArray {
    if (value == null && fallback != null) return fallback.copyOf()
    val list = value.arr()
    if (list == null || list.size != 3) throw BbModelError("bbmodel: $what is not a vector")
    return floatArrayOf(num(list[0], what), num(list[1], what), num(list[2], what))
}

private fun isFalse(value: JsonElement?): Boolean = (value as? JsonPrimitive)?.booleanOrNull == false

private fun parseCube(e: JsonObject, textureCount: Int): BbCube {
    val faces = LinkedHashMap<BbFaceName, BbFace>()
    val raw = e["faces"].obj()
    for (name in BbFaceName.entries) {
        val f = raw?.get(name.name.lowercase()).obj() ?: continue
        val texture = f["texture"]
        // Blockbench does not render a face that has no texture assigned.
        if (texture == null || texture is JsonNull || isFalse(texture)) continue
        val index = num(texture, "face texture")
        if (index != index.toInt().toFloat() || index < 0 || index >= textureCount) throw BbModelError("bbmodel: face texture out of range")
        val uv = f["uv"].arr()
        if (uv == null || uv.size != 4) throw BbModelError("bbmodel: face uv")
        val rotation = if (f["rotation"] == null) 0f else num(f["rotation"], "face rotation")
        if (rotation !in listOf(0f, 90f, 180f, 270f)) throw BbModelError("bbmodel: face rotation")
        faces[name] = BbFace(floatArrayOf(num(uv[0], "uv"), num(uv[1], "uv"), num(uv[2], "uv"), num(uv[3], "uv")), index.toInt(), rotation.toInt())
    }
    return BbCube(
        uuid = (e["uuid"] as? JsonPrimitive)?.content.orEmpty(),
        from = vec(e["from"], "cube from"),
        to = vec(e["to"], "cube to"),
        origin = vec(e["origin"], "cube origin", floatArrayOf(0f, 0f, 0f)),
        rotation = vec(e["rotation"], "cube rotation", floatArrayOf(0f, 0f, 0f)),
        inflate = if (e["inflate"] == null) 0f else num(e["inflate"], "inflate"),
        faces = faces,
    )
}

private fun parseClip(a: JsonObject, bones: Set<String>): BbClip {
    val loop = when ((a["loop"] as? JsonPrimitive)?.content) {
        "loop" -> BbLoop.LOOP
        "hold" -> BbLoop.HOLD
        else -> BbLoop.ONCE
    }
    val tracks = ArrayList<BbTrack>()
    val animators = a["animators"].obj() ?: JsonObject(emptyMap())
    for ((bone, value) in animators) {
        val animator = value.obj() ?: continue
        if (bone !in bones || (animator["type"] as? JsonPrimitive)?.content != "bone") continue
        val frames = animator["keyframes"].arr() ?: continue
        for (channel in BbChannel.entries) {
            val keys = ArrayList<BbKeyframe>()
            for (k in frames) {
                val key = k.obj() ?: continue
                if ((key["channel"] as? JsonPrimitive)?.content != channel.name.lowercase()) continue
                val point = key["data_points"].arr()?.firstOrNull().obj() ?: continue
                val interpolation = when ((key["interpolation"] as? JsonPrimitive)?.content) {
                    "linear" -> BbInterpolation.LINEAR
                    "step" -> BbInterpolation.STEP
                    else -> BbInterpolation.CATMULLROM
                }
                keys += BbKeyframe(
                    num(key["time"], "keyframe time"),
                    floatArrayOf(num(point["x"], "x"), num(point["y"], "y"), num(point["z"], "z")),
                    interpolation,
                )
            }
            if (keys.isNotEmpty()) tracks += BbTrack(bone, channel, keys.sortedBy { it.time })
        }
    }
    val length = a["length"]?.let { num(it, "animation length") } ?: 0f
    return BbClip((a["name"] as? JsonPrimitive)?.content.orEmpty(), loop, max(0f, length), tracks)
}

internal fun parseBbModel(text: String): BbModel {
    val root = try {
        JSON.parseToJsonElement(text)
    } catch (error: Exception) {
        throw BbModelError("bbmodel: not json")
    }.obj()
    val elements = root?.get("elements").arr()
    val outliner = root?.get("outliner").arr()
    val texturesRaw = root?.get("textures").arr()
    if (root == null || elements == null || outliner == null || texturesRaw == null) throw BbModelError("bbmodel: not a Blockbench model")
    val textures = texturesRaw.map { t ->
        val tex = t.obj()
        val source = (tex?.get("source") as? JsonPrimitive)?.content
        if (tex == null || source == null || !source.startsWith(PNG_PREFIX)) throw BbModelError("bbmodel: texture must be an embedded png")
        BbTexture(
            source.removePrefix(PNG_PREFIX),
            num(tex["uv_width"] ?: tex["width"], "uv_width"),
            num(tex["uv_height"] ?: tex["height"], "uv_height"),
        )
    }
    val cubes = HashMap<String, BbCube>()
    for (e in elements) {
        val element = e.obj() ?: continue
        val type = (element["type"] as? JsonPrimitive)?.content
        if ((type == null || type == "cube") && !isFalse(element["visibility"])) {
            val cube = parseCube(element, textures.size)
            cubes[cube.uuid] = cube
        }
    }
    val groups = HashMap<String, JsonObject>()
    for (g in root["groups"].arr().orEmpty()) {
        val group = g.obj() ?: continue
        groups[(group["uuid"] as? JsonPrimitive)?.content.orEmpty()] = group
    }
    val bones = HashSet<String>()

    fun node(n: JsonElement, depth: Int): BbNode? {
        if (depth > MAX_DEPTH) throw BbModelError("bbmodel: outliner is too deep")
        if (n is JsonPrimitive && n.isString) return cubes[n.content]
        val entry = n.obj() ?: return null
        val uuid = (entry["uuid"] as? JsonPrimitive)?.content.orEmpty()
        // Older files keep bone properties inline in the outliner, newer ones in groups.
        val g = groups[uuid] ?: entry
        if (isFalse(g["visibility"])) return null
        bones += uuid
        val children = entry["children"].arr().orEmpty().mapNotNull { node(it, depth + 1) }
        return BbBone(
            uuid,
            (g["name"] as? JsonPrimitive)?.content.orEmpty(),
            vec(g["origin"], "bone origin", floatArrayOf(0f, 0f, 0f)),
            vec(g["rotation"], "bone rotation", floatArrayOf(0f, 0f, 0f)),
            children,
        )
    }

    val roots = outliner.mapNotNull { node(it, 0) }
    val clips = root["animations"].arr().orEmpty().mapNotNull { it.obj() }.map { parseClip(it, bones) }
    return BbModel(roots, textures, clips)
}

/** three.js CatmullRomCurve3 (centripetal) between the middle two of four points, at [weight]. */
private fun centripetal(p0: FloatArray, p1: FloatArray, p2: FloatArray, p3: FloatArray, weight: Float): FloatArray {
    fun dist4(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return (dx * dx + dy * dy + dz * dz).toDouble().pow(0.25).toFloat()
    }
    var dt0 = dist4(p0, p1)
    var dt1 = dist4(p1, p2)
    var dt2 = dist4(p2, p3)
    if (dt1 < 1e-4f) dt1 = 1f
    if (dt0 < 1e-4f) dt0 = dt1
    if (dt2 < 1e-4f) dt2 = dt1
    return FloatArray(3) { i ->
        val x0 = p0[i]
        val x1 = p1[i]
        val x2 = p2[i]
        val x3 = p3[i]
        val t1 = ((x1 - x0) / dt0 - (x2 - x0) / (dt0 + dt1) + (x2 - x1) / dt1) * dt1
        val t2 = ((x2 - x1) / dt1 - (x3 - x1) / (dt1 + dt2) + (x3 - x2) / dt2) * dt1
        val c2 = -3f * x1 + 3f * x2 - 2f * t1 - t2
        val c3 = 2f * x1 - 2f * x2 + t1 + t2
        x1 + t1 * weight + c2 * weight * weight + c3 * weight * weight * weight
    }
}

/** Mirrors Blockbench: values hold outside the key range, catmullrom spans the neighbouring keys. */
internal fun sampleTrack(keys: List<BbKeyframe>, t: Float): FloatArray {
    val first = keys.firstOrNull() ?: return floatArrayOf(0f, 0f, 0f)
    val last = keys.last()
    if (t <= first.time) return first.value
    if (t >= last.time) return last.value
    var i = 0
    while (i < keys.size - 2 && keys[i + 1].time <= t) i++
    val a = keys[i]
    val b = keys[i + 1]
    if (b.time == a.time || a.interpolation == BbInterpolation.STEP) return a.value
    val k = (t - a.time) / (b.time - a.time)
    if (a.interpolation == BbInterpolation.CATMULLROM || b.interpolation == BbInterpolation.CATMULLROM) {
        val before = keys.getOrNull(i - 1) ?: a
        val after = keys.getOrNull(i + 2) ?: b
        return centripetal(before.value, a.value, b.value, after.value, k)
    }
    return FloatArray(3) { a.value[it] + (b.value[it] - a.value[it]) * k }
}

internal fun clipTime(clip: BbClip, t: Float): Float {
    if (clip.length <= 0f) return 0f
    if (clip.loop == BbLoop.LOOP) return ((t % clip.length) + clip.length) % clip.length
    return min(max(0f, t), clip.length)
}

private const val LID_BONE = "chest_up"
private const val LID_OPEN_DEG = 45f

internal fun findBone(nodes: List<BbNode>, name: String): BbBone? {
    for (n in nodes) {
        if (n !is BbBone) continue
        if (n.name == name) return n
        findBone(n.children, name)?.let { return it }
    }
    return null
}

/** The chest model of a tier and its two clips: idle loops, open holds; [lidAt] is when the lid passes 45°. */
internal class ChestRigModel(val model: BbModel, val idle: BbClip?, val open: BbClip?, val lidAt: Float)

internal fun chestRigModel(model: BbModel): ChestRigModel {
    val open = model.clips.firstOrNull { it.loop == BbLoop.HOLD }
    val idle = model.clips.firstOrNull { it.loop == BbLoop.LOOP }
    val lid = findBone(model.roots, LID_BONE)
    val track = open?.tracks?.firstOrNull { it.bone == lid?.uuid && it.channel == BbChannel.ROTATION }
    var lidAt = 0f
    if (open != null && track != null) {
        var t = 0f
        while (t <= open.length) {
            if (abs(sampleTrack(track.keys, t)[0]) >= LID_OPEN_DEG) {
                lidAt = t
                break
            }
            t += 1f / 60f
        }
    }
    return ChestRigModel(model, idle, open, lidAt)
}

/** Which model file each tier wears; the legendary has its own netherite chest. */
internal fun chestModelFile(tier: String): String = when (tier.uppercase()) {
    "RARE" -> "diamond"
    "EPIC" -> "obsidian"
    "LEGEND", "LEGENDARY" -> "netherite"
    else -> "copper"
}

/**
 * The shop cases' recolour (bbModel.tintPixels): every pixel takes the tint's
 * hue, keeps its own lightness and scales its saturation by the tint's.
 */
internal fun tintPixels(argb: IntArray, tint: Int) {
    val th = rgbToHsl((tint shr 16) and 255, (tint shr 8) and 255, tint and 255)
    val k = min(1.4f, th[1] / 0.7f)
    val add = if (th[1] > 0.3f) 0.08f else 0f
    for (i in argb.indices) {
        val p = argb[i]
        val alpha = p ushr 24
        if (alpha == 0) continue
        val hsl = rgbToHsl((p shr 16) and 255, (p shr 8) and 255, p and 255)
        val rgb = hslToRgb(th[0], min(1f, hsl[1] * k + add), hsl[2])
        argb[i] = (alpha shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }
}

private fun rgbToHsl(red: Int, green: Int, blue: Int): FloatArray {
    val r = red / 255f
    val g = green / 255f
    val b = blue / 255f
    val hi = max(r, max(g, b))
    val lo = min(r, min(g, b))
    val l = (hi + lo) / 2f
    if (hi == lo) return floatArrayOf(0f, 0f, l)
    val d = hi - lo
    val s = if (l > 0.5f) d / (2f - hi - lo) else d / (hi + lo)
    val h = when (hi) {
        r -> (g - b) / d + if (g < b) 6f else 0f
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return floatArrayOf(h / 6f, s, l)
}

private fun hslToRgb(h: Float, s: Float, l: Float): IntArray {
    if (s == 0f) {
        val v = Math.round(l * 255f)
        return intArrayOf(v, v, v)
    }
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun f(x: Float): Float {
        var t = x
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    return intArrayOf(Math.round(f(h + 1f / 3f) * 255f), Math.round(f(h) * 255f), Math.round(f(h - 1f / 3f) * 255f))
}

internal fun length3(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
