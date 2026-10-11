package net.millida.launcher.core.cosmetics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** ARGB pixels of a picture, row by row. */
class Pixels(val data: IntArray, val width: Int, val height: Int) {
    val valid: Boolean get() = width > 0 && height > 0 && data.size >= width * height
}

/**
 * What one worn item takes away from the rest of the outfit, decided as the mod
 * decides it (desktop src/lib/cosmeticCover.ts): hidden bones, the chosen side
 * of a one-sided piece, skin masks and the regular cape giving way to a worn one.
 */
object CosmeticCover {
    private val SIDE_ORDER = listOf("left", "right", "front", "back")
    private const val MASK_SIZE = 64
    private const val DARK = 128
    private const val FULLY = 0.95f
    private const val MOSTLY = 0.6f

    private class SkinPart(
        val name: String,
        val wide: List<IntArray>,
        val slim: List<IntArray>? = null,
        val overlay: Boolean,
        val layer: String? = null,
    )

    private fun rect(x0: Int, y0: Int, x1: Int, y1: Int) = intArrayOf(x0, y0, x1, y1)

    private val SKIN_PARTS = listOf(
        SkinPart("HEAD", listOf(rect(8, 0, 24, 8), rect(0, 8, 32, 16)), overlay = false, layer = "HAT"),
        SkinPart("HAT", listOf(rect(40, 0, 56, 8), rect(32, 8, 64, 16)), overlay = true),
        SkinPart("BODY", listOf(rect(20, 16, 36, 20), rect(16, 20, 40, 32)), overlay = false, layer = "JACKET"),
        SkinPart("JACKET", listOf(rect(20, 32, 36, 36), rect(16, 36, 40, 48)), overlay = true),
        SkinPart(
            "RIGHT_ARM",
            listOf(rect(44, 16, 52, 20), rect(40, 20, 56, 32)),
            listOf(rect(44, 16, 50, 20), rect(40, 20, 54, 32)),
            overlay = false,
            layer = "RIGHT_SLEEVE",
        ),
        SkinPart(
            "RIGHT_SLEEVE",
            listOf(rect(44, 32, 52, 36), rect(40, 36, 56, 48)),
            listOf(rect(44, 32, 50, 36), rect(40, 36, 54, 48)),
            overlay = true,
        ),
        SkinPart(
            "LEFT_ARM",
            listOf(rect(36, 48, 44, 52), rect(32, 52, 48, 64)),
            listOf(rect(36, 48, 42, 52), rect(32, 52, 46, 64)),
            overlay = false,
            layer = "LEFT_SLEEVE",
        ),
        SkinPart(
            "LEFT_SLEEVE",
            listOf(rect(52, 48, 60, 52), rect(48, 52, 64, 64)),
            listOf(rect(52, 48, 58, 52), rect(48, 52, 62, 64)),
            overlay = true,
        ),
        SkinPart("RIGHT_LEG", listOf(rect(4, 16, 12, 20), rect(0, 20, 16, 32)), overlay = false, layer = "RIGHT_PANTS"),
        SkinPart("RIGHT_PANTS", listOf(rect(4, 32, 12, 36), rect(0, 36, 16, 48)), overlay = true),
        SkinPart("LEFT_LEG", listOf(rect(20, 48, 28, 52), rect(16, 52, 32, 64)), overlay = false, layer = "LEFT_PANTS"),
        SkinPart("LEFT_PANTS", listOf(rect(4, 48, 12, 52), rect(0, 52, 16, 64)), overlay = true),
    )

    private fun normalise(value: String?): String? = value?.trim()?.lowercase()?.ifEmpty { null }

    /** Hide rules are written against the item code, not against a colourway card. */
    fun coverId(item: CosmeticItem): String = item.baseId.ifEmpty { item.id }

    fun hideRules(value: JsonElement?): Map<String, Set<String>> {
        val rules = value as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, Set<String>>()
        for ((key, bones) in rules) {
            val names = LinkedHashSet<String>()
            when (bones) {
                is JsonArray -> bones.forEach { bone ->
                    val name = (bone as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                    if (name.isNotEmpty()) names.add(name)
                }
                is JsonObject -> bones.forEach { (bone, on) ->
                    if ((on as? JsonPrimitive)?.booleanOrNull == true) names.add(bone)
                }
                else -> Unit
            }
            if (names.isNotEmpty()) out[key] = names
        }
        return out
    }

    /** Bones of [item] the rest of the outfit covers, lower-cased for a case-blind match. */
    fun hiddenBones(outfit: List<CosmeticItem>, item: CosmeticItem): Set<String> {
        val target = coverId(item)
        val bones = HashSet<String>()
        for (other in outfit) {
            if (coverId(other) == target) continue
            hideRules(other.hides)[target]?.forEach { bones.add(it.lowercase()) }
        }
        return bones
    }

    fun chosenSide(options: Set<String>, worn: String?, declared: String?): String? {
        if (options.isEmpty()) return null
        normalise(worn)?.takeIf { it in options }?.let { return it }
        normalise(declared)?.takeIf { it in options }?.let { return it }
        SIDE_ORDER.firstOrNull { it in options }?.let { return it }
        return options.first()
    }

    /** Which bones of one piece stay out of the frame; bones come parents first, as in the rig. */
    fun hiddenJoints(bones: List<RigBone>, coveredBones: Set<String>, declaredSide: String?): BooleanArray {
        val options = LinkedHashSet<String>()
        for (bone in bones) normalise(bone.side)?.let { options.add(it) }
        val side = chosenSide(options, null, declaredSide)
        val hidden = BooleanArray(bones.size)
        bones.forEachIndexed { at, bone ->
            val own = normalise(bone.side)
            hidden[at] = bone.name.lowercase() in coveredBones ||
                (side != null && own != null && own != side) ||
                (bone.parent in 0 until at && hidden[bone.parent])
        }
        return hidden
    }

    fun maskUrl(item: CosmeticItem, slim: Boolean, side: String?): String? {
        val masks = LinkedHashMap<String, String>()
        for ((key, value) in item.masks) {
            if (value.isNotBlank()) masks[key.lowercase()] = value.trim()
        }
        fun anySide(model: String): String? =
            masks.keys.filter { it.startsWith("$model.") }.sorted().firstOrNull()?.let { masks[it] }
        val model = if (slim) "alex" else "steve"
        val other = if (slim) "steve" else "alex"
        val wanted = normalise(side)
        return wanted?.let { masks["$model.$it"] } ?: masks[model] ?: anySide(model) ?: masks[other] ?: anySide(other)
    }

    /** Skin masks of the outfit; an emote's own mask counts only while it plays. */
    fun maskUrls(outfit: List<CosmeticItem>, slim: Boolean, playingEmote: CosmeticItem?): List<String> {
        val urls = ArrayList<String>()
        playingEmote?.let { maskUrl(it, slim, it.side) }?.let { urls.add(it) }
        for (item in outfit) {
            if (item.isEmote) continue
            val url = maskUrl(item, slim, item.side) ?: continue
            if (url !in urls) urls.add(url)
        }
        return urls.sorted()
    }

    fun hidesRegularCape(outfit: List<CosmeticItem>): Boolean = outfit.any { it.slot == "CAPE" }

    private fun covered(pixel: Int): Boolean =
        (pixel ushr 24) != 0 &&
            ((pixel shr 16) and 0xFF) < DARK &&
            ((pixel shr 8) and 0xFF) < DARK &&
            (pixel and 0xFF) < DARK

    fun maskHidesAnything(mask: Pixels): Boolean {
        for (at in 0 until mask.width * mask.height) {
            if (covered(mask.data[at])) return true
        }
        return false
    }

    /** Parts of the body a mask covers whole, which the game then leaves undrawn. */
    fun coveredParts(mask: Pixels, slim: Boolean): Set<String> {
        val parts = LinkedHashSet<String>()
        if (!mask.valid) return parts
        for (part in SKIN_PARTS) {
            var seen = 0
            var hidden = 0
            for (area in (if (slim) part.slim else null) ?: part.wide) {
                for (y in area[1] until area[3]) {
                    val maskY = y * mask.height / MASK_SIZE
                    for (x in area[0] until area[2]) {
                        val maskX = x * mask.width / MASK_SIZE
                        seen++
                        if (covered(mask.data[maskY * mask.width + maskX])) hidden++
                    }
                }
            }
            if (seen > 0 && hidden >= seen * (if (part.overlay) MOSTLY else FULLY)) parts.add(part.name)
        }
        return parts
    }

    /** Cuts the skin under the outfit in place: the masks' dark pixels, then every part a mask covers whole. */
    fun cutSkin(skin: Pixels, masks: List<Pixels>, slim: Boolean): Boolean {
        if (!skin.valid) return false
        // A legacy 64x32 skin is the top half of the layout, not the layout scaled down.
        val layoutHeight = if (skin.width == skin.height * 2) skin.height * 2 else skin.height
        var changed = false
        val parts = LinkedHashSet<String>()
        for (mask in masks) {
            if (!mask.valid || !maskHidesAnything(mask)) continue
            changed = true
            for (y in 0 until skin.height) {
                val maskY = y * mask.height / layoutHeight
                for (x in 0 until skin.width) {
                    val maskX = x * mask.width / skin.width
                    if (covered(mask.data[maskY * mask.width + maskX])) skin.data[y * skin.width + x] = 0
                }
            }
            parts.addAll(coveredParts(mask, slim))
        }
        // Since 1.21.2 each overlay part hangs under its body part, so hiding the body part hides its overlay too.
        for (part in SKIN_PARTS) {
            val layer = part.layer
            if (layer != null && part.name in parts) parts.add(layer)
        }
        for (part in SKIN_PARTS) {
            if (part.name !in parts) continue
            for (area in (if (slim) part.slim else null) ?: part.wide) {
                val fromY = area[1] * layoutHeight / MASK_SIZE
                val toY = minOf(skin.height, area[3] * layoutHeight / MASK_SIZE)
                val fromX = area[0] * skin.width / MASK_SIZE
                val toX = area[2] * skin.width / MASK_SIZE
                for (y in fromY until toY) {
                    for (x in fromX until toX) skin.data[y * skin.width + x] = 0
                }
            }
        }
        return changed
    }
}
