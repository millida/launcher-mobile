package net.millida.launcher.core.friends

/**
 * Pixel emoji of the eltaller47 set, the desktop launcher's only chat emoji
 * (src/lib/eltaller.tsx). A message carries an emoji as the plain-text code
 * `:elt_07:`, so the server knows nothing about them; the pictures ship in the
 * app assets as byte copies of the desktop files (EltallerTest).
 */
object Eltaller {

    const val COUNT = 55

    /** Up to this many emoji with nothing else are drawn large, like the desktop `.elt-only`. */
    const val BIG_MAX = 3

    const val RECENT_MAX = 8

    const val ASSET_DIR = "emoji/eltaller"

    val ids: List<String> = (1..COUNT).map { it.toString().padStart(2, '0') }

    private val CODE = Regex(":elt_([0-9]{2}):")

    sealed interface Piece {
        data class Words(val text: String) : Piece
        data class Emoji(val id: String) : Piece
    }

    data class Parsed(val pieces: List<Piece>, val big: Boolean) {
        val hasEmoji: Boolean get() = pieces.any { it is Piece.Emoji }
    }

    fun code(id: String): String = ":elt_$id:"

    fun assetPath(id: String): String = "$ASSET_DIR/$id.webp"

    fun isKnown(id: String): Boolean = id.length == 2 && id.toIntOrNull() in 1..COUNT

    fun parse(text: String): Parsed {
        val plain = Parsed(if (text.isEmpty()) emptyList() else listOf(Piece.Words(text)), big = false)
        if (!text.contains(":elt_")) return plain
        val pieces = mutableListOf<Piece>()
        var last = 0
        var pics = 0
        for (match in CODE.findAll(text)) {
            val id = match.groupValues[1]
            if (!isKnown(id)) continue
            if (match.range.first > last) pieces += Piece.Words(text.substring(last, match.range.first))
            pieces += Piece.Emoji(id)
            pics++
            last = match.range.last + 1
        }
        if (pics == 0) return plain
        if (last < text.length) pieces += Piece.Words(text.substring(last))
        val big = pics <= BIG_MAX && pieces.all { it is Piece.Emoji || (it as Piece.Words).text.isBlank() }
        return Parsed(pieces, big)
    }

    /** For surfaces that only take text (system notification, list subtitle): each code becomes the emoji it imitates. */
    fun plain(text: String): String {
        if (!text.contains(":elt_")) return text
        return CODE.replace(text) { match -> ALIASES[match.groupValues[1]] ?: match.value }
    }

    /** Picker order: recent first, then the rest of the set, each emoji once. */
    fun withRecent(recent: List<String>): List<String> = recent + ids.filter { it !in recent }

    fun cleanRecent(raw: List<String>): List<String> = raw.filter { it in ids }.distinct().take(RECENT_MAX)

    fun remembered(recent: List<String>, id: String): List<String> = cleanRecent(listOf(id) + recent.filter { it != id })

    /** Reaction art id for a reaction symbol, or null to show the symbol itself. */
    fun reactionArt(emoji: String): String? = REACTION_ART[emoji]

    /**
     * The server checks reactions against its own fixed symbol set, so the
     * symbol stays on the wire and only its picture is a pixel twin
     * (REACTION_ART in the desktop src/components/Chat.tsx).
     */
    private val REACTION_ART: Map<String, String> = mapOf(
        "\uD83D\uDC4D" to "23",
        "\uD83D\uDC4E" to "25",
        "\u2764\uFE0F" to "22",
        "\uD83D\uDD25" to "50",
        "\uD83D\uDE02" to "27",
        "\uD83D\uDE2E" to "55",
        "\uD83D\uDE22" to "38",
        "\uD83C\uDF89" to "17",
    )

    /** The fixed reaction row of the message menu, in the desktop order. */
    val reactions: List<String> get() = REACTION_ART.keys.toList()

    /** The `emoji` field of manifest.json, pinned by EltallerTest against the shipped file. */
    val ALIASES: Map<String, String> = mapOf(
        "01" to "\uD83E\uDD7A",
        "02" to "\uD83D\uDC40",
        "03" to "\uD83D\uDE11",
        "04" to "\uD83D\uDC4F",
        "05" to "\uD83D\uDCA9",
        "06" to "\uD83D\uDE25",
        "07" to "\uD83E\uDD2F",
        "08" to "\uD83E\uDD2A",
        "09" to "\uD83E\uDD26\u200D\u2642\uFE0F",
        "10" to "\uD83D\uDE34",
        "11" to "\uD83D\uDE21",
        "12" to "\uD83D\uDE09",
        "13" to "\uD83D\uDE14",
        "14" to "\uD83E\uDD2B",
        "15" to "\uD83D\uDE13",
        "16" to "\uD83D\uDE2B",
        "17" to "\uD83E\uDD73",
        "18" to "\uD83E\uDEE1",
        "19" to "\uD83D\uDE16",
        "20" to "\uD83D\uDC4B",
        "21" to "\uD83D\uDC4C",
        "22" to "\u2764\uFE0F",
        "23" to "\uD83D\uDC4D",
        "24" to "\uD83C\uDF57",
        "25" to "\uD83D\uDC4E",
        "26" to "\uD83D\uDE10",
        "27" to "\uD83D\uDE02",
        "28" to "\uD83E\uDD14",
        "29" to "\uD83D\uDC85",
        "30" to "\uD83D\uDE35\u200D\uD83D\uDCAB",
        "31" to "\uD83E\uDD29",
        "32" to "\uD83D\uDC8B",
        "33" to "\uD83D\uDE44",
        "34" to "\u2728",
        "35" to "\uD83E\uDEE0",
        "36" to "\uD83E\uDD28",
        "37" to "\uD83E\uDD72",
        "38" to "\uD83D\uDE2D",
        "39" to "\uD83E\uDEE3",
        "40" to "\uD83D\uDE0E",
        "41" to "\uD83D\uDE04",
        "42" to "\uD83E\uDD15",
        "43" to "\uD83D\uDE08",
        "44" to "\uD83D\uDE4F",
        "45" to "\uD83E\uDD2D",
        "46" to "\uD83D\uDE0D",
        "47" to "\uD83D\uDE33",
        "48" to "\uD83D\uDE31",
        "49" to "\u263A\uFE0F",
        "50" to "\uD83D\uDD25",
        "51" to "\uD83D\uDE2E\u200D\uD83D\uDCA8",
        "52" to "\uD83D\uDE42",
        "53" to "\uD83D\uDE28",
        "54" to "\uD83D\uDE36",
        "55" to "\uD83D\uDE2E",
    )
}
