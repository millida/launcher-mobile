package net.millida.launcher.core.cosmetics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class Keyframe(
    val time: Float,
    /** The value the curve leaves this key with. */
    val value: FloatArray,
    /** The value it arrives at when the file switches here (`pre`/`post`): an instant change. */
    val before: FloatArray?,
    val catmullRom: Boolean,
)

class BoneClip(val rotation: List<Keyframe>, val position: List<Keyframe>, val scale: List<Keyframe>)

class AnimationClip(
    val name: String,
    val length: Float,
    /** A piece idles on a clip that simply omits the flag, so only an explicit `false` stops it. */
    val loop: Boolean,
    /** The file says `"loop": true`: an emote tells its loop from its wind-up by this. */
    val endless: Boolean,
    val bones: Map<String, BoneClip>,
)

class BonePose(val rotation: FloatArray, val position: FloatArray, val scale: FloatArray)

/**
 * Bedrock animations read the way the mod reads them (desktop
 * src/lib/cosmeticAnimation.ts): the format turns about x and z the other way
 * round and counts height downwards. A wrong sign bends wings forwards and
 * crosses an emote's arms.
 */
object CosmeticAnimation {
    /** Frames per second of a texture strip, as the game shows it. */
    const val ATLAS_FPS = 20
    private const val ATLAS_SLACK = 0.02f
    private const val ATLAS_MOST = 512

    fun read(source: JsonElement?): Map<String, AnimationClip> {
        val root = source as? JsonObject ?: return emptyMap()
        val body = root["animations"] as? JsonObject ?: root
        val clips = LinkedHashMap<String, AnimationClip>()
        for ((name, raw) in body) {
            val clip = raw as? JsonObject ?: continue
            val bones = LinkedHashMap<String, BoneClip>()
            val boneRoot = clip["bones"] as? JsonObject
            if (boneRoot != null) {
                for ((boneName, boneRaw) in boneRoot) {
                    val bone = boneRaw as? JsonObject ?: continue
                    bones[boneName] = BoneClip(
                        rotation = turned(frames(bone["rotation"], 0f)),
                        position = frames(bone["position"], 0f),
                        scale = frames(bone["scale"], 1f),
                    )
                }
            }
            if (bones.isEmpty()) continue
            var length = clip["animation_length"].number()?.takeIf { it > 0f } ?: 0f
            if (length <= 0f) {
                for (bone in bones.values) {
                    length = maxOf(length, lastTime(bone.rotation), lastTime(bone.position), lastTime(bone.scale))
                }
            }
            val loopFlag = (clip["loop"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
            clips[name] = AnimationClip(
                name = name,
                length = length,
                loop = loopFlag != "false",
                endless = loopFlag == "true",
                bones = bones,
            )
        }
        return clips
    }

    private fun lastTime(list: List<Keyframe>): Float = list.lastOrNull()?.time ?: 0f

    private fun numbers(value: JsonElement?, fallback: Float): FloatArray = when {
        value is JsonPrimitive && !value.isString && value.number() != null -> value.number()!!.let { floatArrayOf(it, it, it) }
        value is JsonArray -> FloatArray(3) { index -> value.getOrNull(index).number() ?: fallback }
        else -> floatArrayOf(fallback, fallback, fallback)
    }

    private fun present(value: JsonElement?): JsonElement? = value?.takeIf { it !is JsonNull }

    private fun frames(source: JsonElement?, fallback: Float): List<Keyframe> {
        val given = present(source) ?: return emptyList()
        if (given is JsonArray || (given is JsonPrimitive && given.number() != null)) {
            return listOf(Keyframe(0f, numbers(given, fallback), null, catmullRom = false))
        }
        val keys = given as? JsonObject ?: return emptyList()
        val out = ArrayList<Keyframe>()
        for ((key, body) in keys) {
            val time = key.trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: continue
            var value: JsonElement? = body
            var arriving: JsonElement? = null
            var catmullRom = false
            if (body is JsonObject) {
                val mode = (body["lerp_mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (mode != null) catmullRom = mode.lowercase() == "catmullrom"
                value = present(body["post"]) ?: present(body["pre"]) ?: present(body["vector"])
                arriving = present(body["pre"])
            }
            val leaving = present(value) ?: continue
            out.add(Keyframe(time, numbers(leaving, fallback), arriving?.let { numbers(it, fallback) }, catmullRom))
        }
        return out.sortedBy { it.time }
    }

    private fun flipXZ(value: FloatArray): FloatArray = floatArrayOf(-value[0], value[1], -value[2])

    private fun turned(list: List<Keyframe>): List<Keyframe> = list.map { frame ->
        Keyframe(frame.time, flipXZ(frame.value), frame.before?.let(::flipXZ), frame.catmullRom)
    }

    private fun at(list: List<Keyframe>, time: Float, fallback: Float): FloatArray {
        if (list.isEmpty()) return floatArrayOf(fallback, fallback, fallback)
        val first = list.first()
        if (time < first.time) return first.before ?: first.value
        if (time == first.time || list.size == 1) return first.value
        val last = list.last()
        if (time >= last.time) return last.value
        for (index in 0 until list.size - 1) {
            val from = list[index]
            val to = list[index + 1]
            if (time < from.time || time > to.time) continue
            val span = to.time - from.time
            val k = if (span <= 0f) 0f else (time - from.time) / span
            // The key the curve leaves sets its easing, as the game eases a segment by its opening key.
            val t = if (from.catmullRom) k * k * (3f - 2f * k) else k
            val target = to.before ?: to.value
            return FloatArray(3) { axis -> from.value[axis] + (target[axis] - from.value[axis]) * t }
        }
        return last.value
    }

    /** Pose of one bone at this moment of the clip, already in the game's signs. */
    fun poseOf(clip: AnimationClip, bone: String, seconds: Float): BonePose? {
        val body = clip.bones[bone] ?: return null
        val time = when {
            clip.length <= 0f -> 0f
            clip.loop -> seconds % clip.length
            else -> min(seconds, clip.length)
        }
        val rotation = at(body.rotation, time, 0f)
        val position = at(body.position, time, 0f)
        val scale = at(body.scale, time, 1f)
        return BonePose(
            rotation = floatArrayOf(-rotation[0], rotation[1], -rotation[2]),
            position = floatArrayOf(position[0], -position[1], position[2]),
            scale = scale.copyOf(),
        )
    }

    /** The clip the catalogue asks for; many items carry idle, walk and run, and the first is often the wrong one. */
    fun pickClip(clips: Map<String, AnimationClip>, name: String?): AnimationClip? {
        if (!name.isNullOrEmpty()) clips[name]?.let { return it }
        clips.entries.firstOrNull { it.key.lowercase().contains("idle") }?.let { return it.value }
        return clips.values.firstOrNull()
    }

    /** An emote may hold a wind-up and a loop; without the catalogue's name the first in the file would play. */
    fun emoteClip(clips: Map<String, AnimationClip>, name: String?): AnimationClip? {
        if (!name.isNullOrEmpty()) clips[name]?.let { return it }
        if (clips.isEmpty()) return null
        return clips.values.firstOrNull { it.loop } ?: clips.values.first()
    }

    private fun wholePixels(value: Float): Int {
        val rounded = value.roundToInt()
        return if (rounded > 0 && abs(value - rounded) < 0.001f) rounded else 0
    }

    private fun sameScale(imageWidth: Int, textureWidth: Float, imageHeight: Int, textureHeight: Float): Boolean {
        val width = wholePixels(textureWidth)
        val height = wholePixels(textureHeight)
        if (width <= 0 || height <= 0 || imageWidth % width != 0 || imageHeight % height != 0) return false
        val across = imageWidth / width
        return across >= 2 && across == imageHeight / height
    }

    /**
     * Mirror of the mod's TextureFrames.of: a frame is as tall as the unwrap even
     * when the strip is wider, so the count comes from the height first.
     */
    fun atlasFrames(imageWidth: Int, imageHeight: Int, textureWidth: Float, textureHeight: Float): Int {
        if (imageWidth <= 0 || imageHeight <= 0 || textureWidth <= 0f || textureHeight <= 0f) return 1
        if (sameScale(imageWidth, textureWidth, imageHeight, textureHeight)) return 1
        val whole = wholePixels(textureHeight)
        if (whole > 0 && imageHeight % whole == 0) {
            val count = imageHeight / whole
            if (count in 2..ATLAS_MOST) return count
            if (count == 1) return 1
        }
        val frameHeight = imageWidth * textureHeight / textureWidth
        if (frameHeight <= 0f) return 1
        val count = (imageHeight / frameHeight).roundToInt()
        if (count < 2 || count > ATLAS_MOST) return 1
        if (abs(imageHeight.toFloat() / count - frameHeight) > ATLAS_SLACK * frameHeight) return 1
        return count
    }

    /** Mirror of the mod's TextureFrames.widthShare: a strip wider by a non-whole factor keeps the unwrap at its left edge. */
    fun atlasWidthShare(imageWidth: Int, textureWidth: Float): Float {
        if (imageWidth <= 0 || textureWidth <= 0f) return 1f
        val times = imageWidth / textureWidth
        if (abs(times - times.roundToInt()) < 0.0001f) return 1f
        val share = textureWidth / imageWidth
        return if (share > 0f && share <= 1f) share else 1f
    }
}

/**
 * The clips of an emote in the order the mod plays them: the wind-up, then the
 * loop until the player moves. A clip the artist kept as a backup never plays.
 */
class EmoteSequence(val intro: AnimationClip?, val main: AnimationClip) {
    fun clipAt(seconds: Float): AnimationClip = if (intro != null && seconds < intro.length) intro else main

    fun timeAt(seconds: Float): Float = when {
        intro == null -> seconds
        seconds < intro.length -> seconds
        else -> seconds - intro.length
    }

    companion object {
        private val SPLIT = Regex("[^a-z0-9]+")

        private fun words(name: String): List<String> = name.lowercase().split(SPLIT)

        private fun backup(name: String): Boolean = words(name).any { it.contains("bkp") }

        fun of(clips: Map<String, AnimationClip>, chosen: AnimationClip?): EmoteSequence? {
            if (chosen == null) return null
            val others = clips.entries.filter { it.value !== chosen && !backup(it.key) }
            if (chosen.endless) {
                // The wind-up plays once whatever its own flag says.
                val intro = others.firstOrNull { entry ->
                    val said = words(entry.key)
                    "start" in said || "pre" in said || "first" in said
                }
                return EmoteSequence(intro?.value, chosen)
            }
            val loops = others.filter { "loop" in words(it.key) }
            val loop = loops.firstOrNull { it.value.endless } ?: loops.firstOrNull()
            return if (loop != null) EmoteSequence(chosen, loop.value) else EmoteSequence(null, chosen)
        }

        /** One half of a paired emote alone: no wind-up or loop is borrowed from the other scenes in its file. */
        fun solo(clip: AnimationClip?): EmoteSequence? = clip?.let { of(mapOf(it.name to it), it) }
    }
}
