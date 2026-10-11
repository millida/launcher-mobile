package net.millida.launcher.core.cosmetics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import net.millida.launcher.core.Hashing
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.io.IOException

data class OutfitSkin(
    /** The texture the skin is put back on from when its wardrobe card is gone. */
    val url: String,
    val slim: Boolean,
    /** The account wardrobe card: worn by id, without sending the picture again. */
    val wardrobeId: String = "",
)

/** An empty [url] means no cape. */
data class OutfitCape(val url: String = "", val wardrobeId: String = "")

data class Outfit(
    val id: String,
    val name: String,
    val skin: OutfitSkin,
    val cape: OutfitCape,
    val cosmetics: List<WornCosmetic>,
)

data class Look(val skin: OutfitSkin, val cape: OutfitCape, val cosmetics: List<WornCosmetic>)

/**
 * Saved looks - skin, arms, cape and items - put on with one tap, as on the
 * desktop launcher (src/state/outfits.ts). The server keeps only what is worn
 * now, so looks live on the device, separately for every account: another
 * account has other skins and rights, and a borrowed look would half fail.
 */
object Outfits {
    const val LIMIT = 12
    const val NAME_MAX = 24
    private const val NAME_PREFIX = "Образ "
    private val SPACES = Regex("\\s+")

    fun parse(raw: JsonElement?): List<Outfit> {
        val rows = raw as? JsonArray ?: return emptyList()
        val out = ArrayList<Outfit>()
        for (row in rows) {
            val item = row as? JsonObject ?: continue
            val id = item.text("id")
            val name = item.text("name")
            val skin = item["skin"] as? JsonObject ?: continue
            val url = skin.text("url")
            if (id.isEmpty() || url.isEmpty()) continue
            val cape = item["cape"] as? JsonObject
            out.add(
                Outfit(
                    id = id,
                    name = name.take(NAME_MAX),
                    skin = OutfitSkin(
                        url = url,
                        slim = (skin["slim"] as? JsonPrimitive)?.booleanOrNull == true,
                        wardrobeId = skin.text("wardrobeId"),
                    ),
                    cape = OutfitCape(cape?.text("url").orEmpty(), cape?.text("wardrobeId").orEmpty()),
                    cosmetics = (item["cosmetics"] as? JsonArray).orEmpty().mapNotNull { entry ->
                        val worn = entry as? JsonObject ?: return@mapNotNull null
                        val code = worn.text("id")
                        val slot = worn.text("slot")
                        if (code.isEmpty() || slot.isEmpty()) null else WornCosmetic(code, slot, worn.text("variant"))
                    },
                ),
            )
            if (out.size >= LIMIT) break
        }
        return out
    }

    fun encode(list: List<Outfit>): JsonArray = buildJsonArray {
        for (outfit in list) {
            addJsonObject {
                put("id", outfit.id)
                put("name", outfit.name)
                putJsonObject("skin") {
                    put("url", outfit.skin.url)
                    put("slim", outfit.skin.slim)
                    if (outfit.skin.wardrobeId.isNotEmpty()) put("wardrobeId", outfit.skin.wardrobeId)
                }
                putJsonObject("cape") {
                    put("url", outfit.cape.url)
                    if (outfit.cape.wardrobeId.isNotEmpty()) put("wardrobeId", outfit.cape.wardrobeId)
                }
                putJsonArray("cosmetics") {
                    for (worn in outfit.cosmetics) {
                        addJsonObject {
                            put("id", worn.id)
                            put("slot", worn.slot)
                            if (worn.variant.isNotEmpty()) put("variant", worn.variant)
                        }
                    }
                }
            }
        }
    }

    /** "Образ N" with a free number: after removing "Образ 2" there is no second "Образ 3". */
    fun nextName(list: List<Outfit>): String {
        val taken = list.map { it.name }.toSet()
        val free = (1..list.size + 1).firstOrNull { "$NAME_PREFIX$it" !in taken } ?: (list.size + 1)
        return "$NAME_PREFIX$free"
    }

    /** At the limit the list stays as it is; the screen tells why. */
    fun add(list: List<Outfit>, look: Look, id: String): List<Outfit> {
        if (list.size >= LIMIT) return list
        return list + Outfit(id, nextName(list), look.skin, look.cape, look.cosmetics)
    }

    /** An empty name is refused: a nameless tile cannot be told from its neighbour. */
    fun rename(list: List<Outfit>, id: String, name: String): List<Outfit> {
        val clean = name.replace(SPACES, " ").trim().take(NAME_MAX)
        if (clean.isEmpty()) return list
        return list.map { if (it.id == id) it.copy(name = clean) else it }
    }

    fun remove(list: List<Outfit>, id: String): List<Outfit> = list.filterNot { it.id == id }

    /** Order of items does not matter: the server answers with what is worn in its own order. */
    fun same(outfit: Outfit, look: Look): Boolean {
        if (outfit.skin.url != look.skin.url || outfit.skin.slim != look.skin.slim) return false
        if (outfit.cape.url != look.cape.url) return false
        if (outfit.cosmetics.size != look.cosmetics.size) return false
        fun mark(worn: WornCosmetic) = worn.slot + "|" + worn.id + "|" + worn.variant
        val mine = outfit.cosmetics.map(::mark).toSet()
        return look.cosmetics.all { mark(it) in mine }
    }

    /** Items of a look split into what the player may wear now and what they no longer have. */
    fun splitWearable(list: List<WornCosmetic>, canWear: (String) -> Boolean): Pair<List<WornCosmetic>, List<WornCosmetic>> =
        list.partition { canWear(it.id) }
}

/** One file per account, named by a digest of the id: no id can leave the folder or share a file with another. */
class OutfitStore(private val dir: File) {
    private fun fileOf(accountId: String): File =
        File(dir, "outfits-" + Hashing.sha1(accountId.ifEmpty { GUEST }.toByteArray(Charsets.UTF_8)) + ".json")

    private companion object {
        const val GUEST = "guest"
    }

    fun load(accountId: String): List<Outfit> {
        val file = fileOf(accountId)
        if (!file.isFile) return emptyList()
        return try {
            Outfits.parse(MojangJson.parseToJsonElement(file.readText()))
        } catch (broken: Exception) {
            throw IOException("сохранённые образы не читаются — сохрани их заново", broken)
        }
    }

    fun save(accountId: String, list: List<Outfit>) {
        try {
            fileOf(accountId).atomicWriteText(Outfits.encode(list).toString())
        } catch (failed: Exception) {
            throw IOException("образ не сохранился — освободи память телефона и повтори", failed)
        }
    }
}
