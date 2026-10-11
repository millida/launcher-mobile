package net.millida.launcher.core.cosmetics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A Bedrock model read into quads by the rules the mod uses (and the desktop
 * launcher mirrors, src/lib/cosmeticGeometry.ts): height is measured down from
 * the attachment point, a box unwrap and a per-face unwrap are both accepted,
 * a negative face size mirrors the picture, and `mirror` swaps the x extremes of
 * a cube as the game does.
 */
class MeshQuad(
    /** Four corners, x y z each, in model pixels relative to the bone pivot. */
    val positions: FloatArray,
    /** Four u v pairs as fractions of the texture. */
    val uvs: FloatArray,
)

class MeshBone(
    val name: String,
    val parent: String?,
    /** In game space: x, y down from the neck, z to the back. */
    val pivot: FloatArray,
    /** Degrees exactly as in the file; only cube turns change sign. */
    val rotation: FloatArray,
    val quads: List<MeshQuad>,
    val side: String?,
)

class CosmeticMesh(val textureWidth: Float, val textureHeight: Float, val bones: List<MeshBone>)

object CosmeticGeometry {
    const val BODY_HEIGHT = 24f
    private const val DEFAULT_TEXTURE = 64f

    private enum class CubeFace(val corners: IntArray) {
        NORTH(intArrayOf(6, 2, 0, 4)),
        SOUTH(intArrayOf(3, 7, 5, 1)),
        EAST(intArrayOf(7, 6, 4, 5)),
        WEST(intArrayOf(2, 3, 1, 0)),
        UP(intArrayOf(7, 3, 2, 6)),
        DOWN(intArrayOf(4, 0, 1, 5)),
    }

    /**
     * Accepts the content of `minecraft:geometry` as well as the whole file:
     * the service sends the model wrapped, a pack file lies as it is.
     * [extraInflate] is the mod's per-slot growth that keeps clothing off the skin.
     */
    fun read(source: JsonElement?, extraInflate: Float = 0f): CosmeticMesh? {
        val root = source as? JsonObject ?: return null
        val wrapped = root["minecraft:geometry"] ?: root["geometry"]
        val geometry: JsonObject = when {
            wrapped is JsonArray -> wrapped.firstOrNull() as? JsonObject
            wrapped is JsonObject && "minecraft:geometry" in wrapped ->
                (wrapped["minecraft:geometry"] as? JsonArray)?.firstOrNull() as? JsonObject
            else -> root
        } ?: return null
        val description = geometry["description"] as? JsonObject
        val textureWidth = description?.get("texture_width").number()?.takeIf { it != 0f } ?: DEFAULT_TEXTURE
        val textureHeight = description?.get("texture_height").number()?.takeIf { it != 0f } ?: DEFAULT_TEXTURE
        val rawBones = (geometry["bones"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        if (rawBones.isEmpty()) return null

        val bones = rawBones.map { bone ->
            val rawPivot = bone["pivot"].numbers()
            val pivot = floatArrayOf(rawPivot.at(0), flipY(rawPivot.at(1)), rawPivot.at(2))
            val rotation = bone["rotation"].numbers()
            val cubes = (bone["cubes"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                .filter { cube -> cube["size"].numbers().any { it != 0f } }
            MeshBone(
                name = bone["name"].string().orEmpty(),
                parent = bone["parent"].string()?.ifEmpty { null },
                pivot = pivot,
                rotation = floatArrayOf(rotation.at(0), rotation.at(1), rotation.at(2)),
                quads = cubes.flatMap { cube -> cubeQuads(cube, bone, pivot, textureWidth, textureHeight, extraInflate) },
                side = bone["side"].string()?.trim()?.lowercase()?.ifEmpty { null },
            )
        }
        return CosmeticMesh(textureWidth, textureHeight, bones)
    }

    /**
     * Growth by slot, the mod's ladder (CosmeticSlot.extraInflate): it parts faces of
     * zero thickness, while the skin under clothing is removed by the mask.
     */
    fun inflateFor(slot: String): Float = when (slot) {
        "PANTS" -> 0.01f
        "TOP", "HEAD", "FACE" -> 0.02f
        "BACK", "SKIRT", "WAIST", "EARS" -> 0.03f
        "HAT", "FULL_BODY" -> 0.04f
        "ACCESSORY", "ARMS", "SHOES", "HAND", "FEET", "SHOULDER", "SHOULDERS" -> 0.05f
        else -> 0.01f
    }

    private fun flipY(y: Float): Float = BODY_HEIGHT - y

    private fun boxUv(face: CubeFace, u: Float, v: Float, size: List<Float>): FloatArray {
        val sx = abs(size.at(0))
        val sy = abs(size.at(1))
        val sz = abs(size.at(2))
        return when (face) {
            CubeFace.UP -> floatArrayOf(u + sz, v, u + sz + sx, v + sz)
            CubeFace.DOWN -> floatArrayOf(u + sz + sx, v + sz, u + sz + sx + sx, v)
            CubeFace.WEST -> floatArrayOf(u, v + sz, u + sz, v + sz + sy)
            CubeFace.NORTH -> floatArrayOf(u + sz, v + sz, u + sz + sx, v + sz + sy)
            CubeFace.EAST -> floatArrayOf(u + sz + sx, v + sz, u + sz + sx + sz, v + sz + sy)
            CubeFace.SOUTH -> floatArrayOf(u + sz + sx + sz, v + sz, u + sz + sx + sz + sx, v + sz + sy)
        }
    }

    // The format calls the face looking towards minus x "east": the names are the opposite of the game's directions.
    private fun fileKey(face: CubeFace): String = when (face) {
        CubeFace.EAST -> "west"
        CubeFace.WEST -> "east"
        else -> face.name.lowercase()
    }

    private fun defaultExtent(face: CubeFace, size: List<Float>): List<Float> = when (face) {
        CubeFace.UP, CubeFace.DOWN -> listOf(size.at(0), size.at(2))
        CubeFace.EAST, CubeFace.WEST -> listOf(size.at(2), size.at(1))
        else -> listOf(size.at(0), size.at(1))
    }

    /** Rectangle of a face in texture pixels, or null when the per-face unwrap leaves it out. */
    private fun faceRect(cube: JsonObject, face: CubeFace): FloatArray? {
        val uv = cube["uv"]
        val size = cube["size"].numbers()
        if (uv is JsonObject) {
            val body = uv[fileKey(face)] as? JsonObject ?: return null
            val start = (body["uv"] as? JsonArray ?: return null).let { array -> array.map { it.number() ?: 0f } }
            val extent = (body["uv_size"] as? JsonArray)?.map { it.number() ?: 0f } ?: defaultExtent(face, size)
            val u = start.at(0)
            val v = start.at(1)
            return floatArrayOf(u, v, u + extent.at(0), v + extent.at(1))
        }
        val start = if (uv is JsonArray) uv.map { it.number() ?: 0f } else emptyList()
        return boxUv(face, start.at(0), start.at(1), size)
    }

    private fun cubeQuads(
        cube: JsonObject,
        bone: JsonObject,
        pivot: FloatArray,
        textureWidth: Float,
        textureHeight: Float,
        extraInflate: Float,
    ): List<MeshQuad> {
        val origin = cube["origin"].numbers()
        val size = cube["size"].numbers()
        val inflate = (cube["inflate"].number() ?: 0f) + extraInflate
        val perFace = cube["uv"] is JsonObject
        val mirror = (cube["mirror"].flag() || bone["mirror"].flag()) && !perFace

        var x0 = origin.at(0) - inflate
        var x1 = origin.at(0) + size.at(0) + inflate
        val y0 = origin.at(1) - inflate
        val y1 = origin.at(1) + size.at(1) + inflate
        val z0 = origin.at(2) - inflate
        val z1 = origin.at(2) + size.at(2) + inflate
        if (mirror) {
            val swap = x0
            x0 = x1
            x1 = swap
        }

        val corners = Array(8) { index ->
            floatArrayOf(
                if (index / 4 == 0) x0 else x1,
                if ((index / 2) % 2 == 0) y0 else y1,
                if (index % 2 == 0) z0 else z1,
            )
        }
        // A cube turns about its own point before the height flip, as in the game; without it ears fold inwards.
        val spin = cube["rotation"].numbers()
        if (spin.any { it != 0f }) {
            val cubePivot = cube["pivot"].numbers()
            val angles = floatArrayOf(-spin.at(0), spin.at(1), -spin.at(2))
            for (corner in corners) turnInModel(corner, cubePivot, angles)
        }
        for (corner in corners) {
            corner[0] = corner[0] - pivot[0]
            corner[1] = flipY(corner[1]) - pivot[1]
            corner[2] = corner[2] - pivot[2]
        }

        val quads = ArrayList<MeshQuad>(6)
        for (face in CubeFace.entries) {
            val rect = faceRect(cube, face) ?: continue
            val u1 = rect[0] / textureWidth
            val v1 = rect[1] / textureHeight
            val u2 = rect[2] / textureWidth
            val v2 = rect[3] / textureHeight
            val uv = arrayOf(floatArrayOf(u2, v1), floatArrayOf(u1, v1), floatArrayOf(u1, v2), floatArrayOf(u2, v2))
            val positions = FloatArray(12)
            val uvs = FloatArray(8)
            for (at in 0 until 4) {
                // Swapping x reverses the winding, so the walk is reversed back together with
                // the unwrap corners: moving only the unwrap would mirror the picture twice.
                val from = if (mirror) 3 - at else at
                val corner = corners[face.corners[from]]
                positions[at * 3] = corner[0]
                positions[at * 3 + 1] = corner[1]
                positions[at * 3 + 2] = corner[2]
                uvs[at * 2] = uv[from][0]
                uvs[at * 2 + 1] = uv[from][1]
            }
            quads.add(MeshQuad(positions, uvs))
        }
        return quads
    }

    /** Turns a point about a point in the order x, y, z - the same as the game. */
    private fun turnInModel(point: FloatArray, pivot: List<Float>, degrees: FloatArray) {
        var x = point[0] - pivot.at(0)
        var y = point[1] - pivot.at(1)
        var z = point[2] - pivot.at(2)
        var c = cos(Math.toRadians(degrees[0].toDouble())).toFloat()
        var s = sin(Math.toRadians(degrees[0].toDouble())).toFloat()
        val y1 = y * c - z * s
        val z1 = y * s + z * c
        y = y1
        z = z1
        c = cos(Math.toRadians(degrees[1].toDouble())).toFloat()
        s = sin(Math.toRadians(degrees[1].toDouble())).toFloat()
        val x2 = x * c + z * s
        val z2 = -x * s + z * c
        x = x2
        z = z2
        c = cos(Math.toRadians(degrees[2].toDouble())).toFloat()
        s = sin(Math.toRadians(degrees[2].toDouble())).toFloat()
        val x3 = x * c - y * s
        val y3 = x * s + y * c
        point[0] = x3 + pivot.at(0)
        point[1] = y3 + pivot.at(1)
        point[2] = z + pivot.at(2)
    }
}

/** Where a piece sits on the figure: the part a root bone of the model is named after. */
enum class CosmeticAnchor { ROOT, HEAD, BODY, CAPE, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG }

class RigBone(
    val name: String,
    /** Index of the parent in the same list, or -1 when the bone hangs on a part of the player. */
    val parent: Int,
    val anchor: CosmeticAnchor?,
    /** Offset from the parent, or from the part's attachment point for a bone on the player. */
    val offset: FloatArray,
    val rotation: FloatArray,
    val quads: List<MeshQuad>,
    val side: String?,
)

class CosmeticRig(val bones: List<RigBone>)

/**
 * Attachment points are the mod's (src/lib/cosmeticPlacement.ts on desktop):
 * a hat in the launcher and a hat in the game have to sit the same, or the
 * player buys something other than what they saw.
 */
object CosmeticPlacement {
    private val ANCHOR_BY_BONE: Map<String, CosmeticAnchor> = mapOf(
        "root" to CosmeticAnchor.ROOT,
        "cape" to CosmeticAnchor.CAPE,
        "head" to CosmeticAnchor.HEAD,
        "body" to CosmeticAnchor.BODY,
        "arm_left" to CosmeticAnchor.LEFT_ARM,
        "left_arm" to CosmeticAnchor.LEFT_ARM,
        "arm_right" to CosmeticAnchor.RIGHT_ARM,
        "right_arm" to CosmeticAnchor.RIGHT_ARM,
        "leg_left" to CosmeticAnchor.LEFT_LEG,
        "left_leg" to CosmeticAnchor.LEFT_LEG,
        "leg_right" to CosmeticAnchor.RIGHT_LEG,
        "right_leg" to CosmeticAnchor.RIGHT_LEG,
    )

    /** Attachment point of each part in game space: x, y down from the neck, z to the back. */
    fun rest(anchor: CosmeticAnchor): FloatArray = when (anchor) {
        CosmeticAnchor.RIGHT_ARM -> floatArrayOf(-5f, 2f, 0f)
        CosmeticAnchor.LEFT_ARM -> floatArrayOf(5f, 2f, 0f)
        CosmeticAnchor.RIGHT_LEG -> floatArrayOf(-1.9f, 12f, 0f)
        CosmeticAnchor.LEFT_LEG -> floatArrayOf(1.9f, 12f, 0f)
        else -> floatArrayOf(0f, 0f, 0f)
    }

    private fun chainOf(bone: MeshBone, byName: Map<String, MeshBone>): List<MeshBone> {
        val chain = ArrayList<MeshBone>()
        val seen = HashSet<String>()
        var at: MeshBone? = bone
        while (at != null && seen.add(at.name)) {
            chain.add(at)
            at = at.parent?.let { byName[it] }
        }
        return chain
    }

    /**
     * Only a bone hanging straight on the model root takes a place on the body: a
     * bone of the same name deeper in the tree belongs to the artist. The cape is
     * the exception, since empty groups often stand between it and the root.
     */
    private fun anchorOfBone(bone: MeshBone, byName: Map<String, MeshBone>): CosmeticAnchor? {
        val own = ANCHOR_BY_BONE[bone.name.lowercase()] ?: return null
        val parent = bone.parent?.lowercase()
        if (parent == null || parent == "root") return own
        return if (own == CosmeticAnchor.CAPE && throughEmptyGroups(bone, byName)) own else null
    }

    private fun throughEmptyGroups(bone: MeshBone, byName: Map<String, MeshBone>): Boolean {
        var name = bone.parent
        var depth = 0
        while (depth < 16 && name != null && name.lowercase() != "root") {
            val parent = byName[name]
            if (parent == null || parent.quads.isNotEmpty()) return false
            name = parent.parent
            depth++
        }
        return true
    }

    fun anchorOf(bone: MeshBone, byName: Map<String, MeshBone>): CosmeticAnchor? =
        chainOf(bone, byName).firstNotNullOfOrNull { anchorOfBone(it, byName) }

    /** A bone on a part of the player is measured from that part; the chain above it in the file is skipped, as in the mod. */
    fun rig(mesh: CosmeticMesh): CosmeticRig {
        val byName = mesh.bones.associateBy { it.name }
        val indexByName = HashMap<String, Int>()
        val bones = ArrayList<RigBone>(mesh.bones.size)
        for (bone in mesh.bones) {
            val own = anchorOfBone(bone, byName)
            val parentBone = bone.parent?.let { byName[it] }
            val parentIndex = if (own != null || parentBone == null) -1 else indexByName[parentBone.name] ?: -1
            val anchor = own ?: if (parentIndex < 0) anchorOf(bone, byName) ?: CosmeticAnchor.ROOT else null
            val base = if (parentIndex >= 0 && parentBone != null) {
                floatArrayOf(
                    bone.pivot[0] - parentBone.pivot[0],
                    bone.pivot[1] - parentBone.pivot[1],
                    bone.pivot[2] - parentBone.pivot[2],
                )
            } else {
                bone.pivot.copyOf()
            }
            val rest = anchor?.let(::rest) ?: floatArrayOf(0f, 0f, 0f)
            val offset = if (parentIndex >= 0) base else floatArrayOf(base[0] - rest[0], base[1] - rest[1], base[2] - rest[2])
            indexByName[bone.name] = bones.size
            bones.add(
                RigBone(
                    name = bone.name,
                    parent = parentIndex,
                    anchor = if (parentIndex >= 0) null else anchor,
                    offset = offset,
                    rotation = bone.rotation.copyOf(),
                    quads = bone.quads,
                    side = bone.side,
                ),
            )
        }
        return CosmeticRig(bones)
    }
}

internal fun JsonElement?.number(): Float? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toFloat()

internal fun JsonElement?.numbers(): List<Float> =
    (this as? JsonArray)?.map { it.number() ?: 0f }.orEmpty()

internal fun List<Float>.at(index: Int): Float = getOrNull(index) ?: 0f

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.flag(): Boolean = (this as? JsonPrimitive)?.booleanOrNull == true
