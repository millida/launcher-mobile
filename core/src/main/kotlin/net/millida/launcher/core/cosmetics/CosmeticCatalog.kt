package net.millida.launcher.core.cosmetics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

data class CosmeticVariant(
    val name: String,
    val color: String = "",
    val texture: String = "",
    val preview: String = "",
    val code: String = "",
    val title: String = "",
    val rarity: String = "",
    val price: Int? = null,
)

/**
 * One card of the mod's wardrobe. A colourway is a card of its own (code
 * `CODE~name`, [baseId] the item it came from); the service and the mod still
 * speak in the pair (item, colourway), and [CosmeticCatalog] turns one into the
 * other at the border.
 */
data class CosmeticItem(
    val id: String,
    val name: String,
    val slot: String,
    val model: String = "",
    val texture: String = "",
    val access: String = "",
    val channel: String = "",
    val staffOnly: Boolean = false,
    val priceRubies: Int? = null,
    val preview: String = "",
    val animation: String = "",
    val variants: List<CosmeticVariant> = emptyList(),
    val rarity: String = "",
    val baseId: String = "",
    val tint: String = "",
    val tintFrom: String = "",
    val hides: JsonElement? = null,
    val masks: Map<String, String> = emptyMap(),
    val side: String = "",
) {
    val free: Boolean get() = access == "FREE"

    /** Sold for rubies right now; an item a channel hands out is not. */
    val onSale: Boolean get() = access == "PURCHASE" && (priceRubies ?: 0) > 0

    val isEmote: Boolean get() = slot == "EMOTE"
}

data class WornCosmetic(val id: String, val slot: String, val variant: String = "")

const val VARIANT_SEP = '~'

fun variantCode(code: String, name: String): String = code + VARIANT_SEP + name

/** Card code to the item code and the colourway name; an item without colourways has an empty one. */
fun splitVariantCode(id: String): Pair<String, String> {
    val at = id.indexOf(VARIANT_SEP)
    return if (at < 0) id to "" else id.substring(0, at) to id.substring(at + 1)
}

private val TITLE_WORDS: List<Pair<Regex, String>> = listOf(
    Regex("rainbow") to "радуги",
    Regex("prism|chroma") to "призмы",
    Regex("galaxy|cosmic|space|star") to "галактики",
    Regex("holo") to "голограммы",
    Regex("aurora") to "северного сияния",
    Regex("void") to "пустоты",
    Regex("aether") to "эфира",
    Regex("herobrine") to "Херобрина",
    Regex("wither") to "иссушения",
    Regex("(^|_)ender|end($|_)") to "Края",
    Regex("portal") to "портала",
    Regex("soul") to "душ",
    Regex("magma|lava") to "магмы",
    Regex("fire|flame|blaze") to "огня",
    Regex("(^|_)ice($|_)|frost|snow") to "инея",
    Regex("lightning|thunder|storm") to "грозы",
    Regex("neon") to "неона",
    Regex("glow|light($|_)") to "сияния",
    Regex("blood") to "крови",
    Regex("acid|toxic|poison") to "яда",
    Regex("diamond") to "алмаза",
    Regex("emerald") to "изумруда",
    Regex("amethyst") to "аметиста",
    Regex("netherite") to "незерита",
    Regex("obsidian") to "обсидиана",
    Regex("copper") to "меди",
    Regex("iron|silver|steel") to "луны",
    Regex("gold") to "солнца",
    Regex("crimson|scarlet|ruby") to "багрянца",
    Regex("red") to "заката",
    Regex("orange|amber") to "пламени",
    Regex("yellow|lemon") to "полудня",
    Regex("lime") to "лайма",
    Regex("green|mint|forest") to "леса",
    Regex("teal|cyan|aqua|turquoise") to "лагуны",
    Regex("light_blue|sky") to "неба",
    Regex("navy|dark_blue") to "шторма",
    Regex("blue") to "глубин",
    Regex("purple|violet|lavender") to "сумерек",
    Regex("magenta|pink|rose") to "сакуры",
    Regex("white|pearl") to "снега",
    Regex("black|dark|shadow") to "ночи",
    Regex("gray|grey|ash") to "пепла",
    Regex("brown|wood|choco") to "коры",
)

private val TITLE_SPARE = listOf(
    "рассвета", "бури", "тумана", "прилива", "полуночи", "грёз", "вьюги", "зари", "миража", "эха", "бездны", "зенита",
)

/**
 * Names of every colourway of an item, unique within it. The service sends its
 * own titles; this is the same rule for an older service that does not.
 */
fun variantTitles(baseName: String, names: List<String>): List<String> {
    val used = HashSet<String>()
    return names.mapIndexed { index, name ->
        if (index == 0) return@mapIndexed baseName
        val text = name.lowercase()
        val words = TITLE_WORDS.filter { (pattern, _) -> pattern.containsMatchIn(text) }.map { it.second }
        val spare = TITLE_SPARE.drop(index % TITLE_SPARE.size) + TITLE_SPARE
        val word = (words + spare).firstOrNull { it !in used } ?: (index + 1).toString()
        used.add(word)
        "$baseName $word"
    }
}

private val PREFERRED_VARIANTS = listOf("white", "default", "normal", "classic", "base", "light")

/** The colour shown before the player picks one: the same order the catalogue previews are rendered in. */
fun defaultVariant(list: List<CosmeticVariant>): CosmeticVariant? {
    if (list.isEmpty()) return null
    for (wanted in PREFERRED_VARIANTS) {
        list.firstOrNull { it.name.lowercase() == wanted }?.let { return it }
    }
    return list.first()
}

/** The texture a card is drawn with on the figure. */
fun CosmeticItem.wornTexture(chosen: String = ""): String {
    val picked = variants.firstOrNull { it.name == chosen } ?: defaultVariant(variants)
    return picked?.texture?.ifEmpty { null } ?: texture
}

/**
 * The catalogue as cards: every colourway of an item with two or more of them
 * becomes a card with its own name, rank and price. The pair the service knows
 * is rebuilt by [toServer], [fromServer] and [ownedCodes].
 */
class CosmeticCatalog private constructor(
    val items: List<CosmeticItem>,
    private val variantNames: Map<String, List<String>>,
) {
    fun byId(id: String): CosmeticItem? = items.firstOrNull { it.id == id }

    fun toServer(worn: WornCosmetic): WornCosmetic {
        val (code, variant) = splitVariantCode(worn.id)
        return if (variant.isNotEmpty()) worn.copy(id = code, variant = variant) else worn
    }

    fun fromServer(worn: WornCosmetic): WornCosmetic {
        val names = variantNames[worn.id] ?: return worn
        val name = if (worn.variant in names) worn.variant else names.first()
        return worn.copy(id = variantCode(worn.id, name), variant = name)
    }

    /** An older service sends no per-colourway rights: owning an item then means owning every colour of it. */
    fun ownedCodes(codes: List<String>, variants: Map<String, List<String>>?): List<String> = codes.flatMap { code ->
        val names = variantNames[code] ?: return@flatMap listOf(code)
        val mine = if (variants != null) variants[code].orEmpty() else names
        mine.map { variantCode(code, it) }
    }

    companion object {
        val EMPTY = CosmeticCatalog(emptyList(), emptyMap())

        fun of(raw: List<CosmeticItem>): CosmeticCatalog {
            val names = LinkedHashMap<String, List<String>>()
            val out = ArrayList<CosmeticItem>()
            for (item in raw) {
                val list = item.variants.filter { it.name.isNotEmpty() }
                if (list.size < 2) {
                    out.add(item)
                    continue
                }
                names[item.id] = list.map { it.name }
                val titles = variantTitles(item.name, list.map { it.name })
                list.forEachIndexed { index, variant ->
                    val ownRender = variant.preview.isNotEmpty()
                    val tinted = !ownRender && index > 0 && variant.color.isNotEmpty()
                    out.add(
                        item.copy(
                            id = variant.code.ifEmpty { variantCode(item.id, variant.name) },
                            name = variant.title.ifEmpty { titles.getOrNull(index)?.ifEmpty { null } ?: item.name },
                            variants = listOf(variant),
                            rarity = variant.rarity.ifEmpty { item.rarity },
                            priceRubies = when {
                                variant.price == null -> item.priceRubies
                                variant.price > 0 -> variant.price
                                else -> null
                            },
                            baseId = item.id,
                            preview = if (ownRender) variant.preview else item.preview,
                            tint = if (tinted) variant.color else item.tint,
                            tintFrom = if (tinted) list.first().color else item.tintFrom,
                        ),
                    )
                }
            }
            return CosmeticCatalog(out, names)
        }

        /** Parses `/cosmetics/catalog`; a row without an id or a slot cannot be worn and is dropped. */
        fun parseItems(element: JsonElement?): List<CosmeticItem> {
            val rows = when (element) {
                is JsonArray -> element
                is JsonObject -> element["items"] as? JsonArray ?: return emptyList()
                else -> return emptyList()
            }
            return rows.mapNotNull { row -> (row as? JsonObject)?.let(::parseItem) }
        }

        private fun parseItem(row: JsonObject): CosmeticItem? {
            val id = row.text("id")
            val slot = row.text("slot")
            if (id.isEmpty() || slot.isEmpty()) return null
            return CosmeticItem(
                id = id,
                name = row.text("name").ifEmpty { id },
                slot = slot,
                model = row.text("model"),
                texture = row.text("texture"),
                access = row.text("access"),
                channel = row.text("channel"),
                staffOnly = (row["staffOnly"] as? JsonPrimitive)?.booleanOrNull == true,
                priceRubies = row.whole("priceRubies"),
                preview = row.text("preview"),
                animation = row.text("animation"),
                variants = (row["variants"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseVariant) },
                rarity = row.text("rarity"),
                baseId = row.text("baseId"),
                tint = row.text("tint"),
                tintFrom = row.text("tintFrom"),
                hides = row["hides"]?.takeIf { it !is JsonNull },
                masks = (row["masks"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                    val url = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                    if (url.isEmpty()) null else key to url
                }.toMap(),
                side = row.text("side"),
            )
        }

        private fun parseVariant(row: JsonObject): CosmeticVariant? {
            val name = row.text("name")
            if (name.isEmpty()) return null
            return CosmeticVariant(
                name = name,
                color = row.text("color"),
                texture = row.text("texture"),
                preview = row.text("preview"),
                code = row.text("code"),
                title = row.text("title"),
                rarity = row.text("rarity"),
                price = row.whole("price"),
            )
        }
    }
}

internal fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.whole(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toInt()

/**
 * Two levels, as in the desktop wardrobe: a body section, then a chip inside it.
 * Slots the launcher does not know yet go to the body's "other" chip, so a new
 * catalogue slot is never lost.
 */
data class CosmeticChip(val key: String, val name: String, val slots: List<String>)

data class CosmeticSection(val key: String, val name: String, val chips: List<CosmeticChip>)

object CosmeticSections {
    const val EMOTES = "emote"

    val ALL: List<CosmeticSection> = listOf(
        CosmeticSection(
            "head",
            "Голова",
            listOf(
                CosmeticChip("HAT", "Шляпы", listOf("HAT")),
                CosmeticChip("HEAD", "Причёски", listOf("HEAD")),
                CosmeticChip("FACE", "Лицо", listOf("FACE")),
                CosmeticChip("EARS", "Уши и рога", listOf("EARS")),
            ),
        ),
        CosmeticSection(
            "body",
            "Тело",
            listOf(
                CosmeticChip("FULL_BODY", "Костюмы", listOf("FULL_BODY")),
                CosmeticChip("TOP", "Верх", listOf("TOP", "ACCESSORY")),
                CosmeticChip("PANTS", "Низ", listOf("PANTS", "SKIRT", "WAIST")),
                CosmeticChip("SHOES", "Обувь", listOf("SHOES", "FEET")),
                CosmeticChip("ARMS", "Руки", listOf("ARMS", "HAND", "SHOULDERS", "SHOULDER")),
            ),
        ),
        CosmeticSection(
            "back",
            "Спина",
            listOf(
                CosmeticChip("WINGS", "Крылья", listOf("WINGS")),
                CosmeticChip("BACK", "За спиной", listOf("BACK")),
            ),
        ),
        CosmeticSection("pet", "Питомцы", listOf(CosmeticChip("PET", "Питомцы", listOf("PET")))),
        CosmeticSection(
            EMOTES,
            "Эмоции",
            listOf(
                CosmeticChip("EMOTE", "Эмоции", listOf("EMOTE")),
                CosmeticChip("EFFECT", "Ауры", listOf("EFFECT", "AURA", "ICON")),
            ),
        ),
    )

    private val KNOWN: Set<String> = setOf("CAPE") + ALL.flatMap { section -> section.chips.flatMap { it.slots } }

    /** Sections with what the catalogue really has; while it loads every section shows, so the bar does not jump. */
    fun of(slots: Collection<String>): List<CosmeticSection> {
        if (slots.isEmpty()) return ALL
        val unknown = slots.filter { it !in KNOWN }.distinct()
        return ALL.map { section ->
            val extra = if (section.key == "body" && unknown.isNotEmpty()) listOf(CosmeticChip("OTHER", "Другое", unknown)) else emptyList()
            section.copy(chips = (section.chips + extra).filter { chip -> chip.slots.any { it in slots } })
        }.filter { it.chips.isNotEmpty() }
    }
}
