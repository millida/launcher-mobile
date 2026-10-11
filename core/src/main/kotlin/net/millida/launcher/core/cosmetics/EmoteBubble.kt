package net.millida.launcher.core.cosmetics

/**
 * The speech bubble the lobby figure shows over its head while it plays an
 * emote, the desktop's EmoteBubble.tsx: an eltaller emoji of the emote's mood,
 * not on every emote and not more often than once in 8-15 seconds, because a
 * bubble over each one gets tiresome.
 */
object EmoteBubble {
    const val HOLD_MS = 2_500L
    const val OUT_MS = 180L
    const val GAP_MIN_MS = 8_000L
    const val GAP_MAX_MS = 15_000L
    const val CHANCE = 0.75

    enum class Mood { HAPPY, SAD, LOVE, ANGRY, THINK, COOL, WOW, SLEEPY }

    private val EMOJI: Map<Mood, List<String>> = mapOf(
        Mood.HAPPY to listOf("17", "41", "27", "31", "04", "50", "34", "23", "21", "12", "52"),
        Mood.SAD to listOf("01", "06", "13", "38", "37", "15"),
        Mood.LOVE to listOf("22", "46", "32", "49"),
        Mood.ANGRY to listOf("11", "43", "19"),
        Mood.THINK to listOf("28", "02", "36", "33"),
        Mood.COOL to listOf("40", "18", "29"),
        Mood.WOW to listOf("07", "48", "55", "47", "31"),
        Mood.SLEEPY to listOf("10", "51", "16"),
    )

    /** Checked in this order, as the desktop's LobbyCharacter.emoteMood does: the first match wins. */
    private val RULES: List<Pair<Mood, Regex>> = listOf(
        Mood.SAD to Regex("sad|cry|tear|груст|плач|слез|печал|рыда"),
        Mood.LOVE to Regex("love|heart|kiss|hug|любов|серд|поцел|обним"),
        Mood.ANGRY to Regex("angry|rage|mad|зл|ярост|бешен"),
        Mood.THINK to Regex("think|look|hmm|дума|смотр|огляд"),
        Mood.COOL to Regex("cool|swag|крут|стил"),
        Mood.SLEEPY to Regex("sleep|yawn|tired|сон|сп(ит|ать)|зев|устал"),
        Mood.WOW to Regex("wow|shock|scare|surpr|удив|испуг|шок"),
    )

    fun mood(name: String): Mood {
        val lower = name.lowercase()
        return RULES.firstOrNull { (_, rule) -> rule.containsMatchIn(lower) }?.first ?: Mood.HAPPY
    }

    fun emojiPool(mood: Mood): List<String> = EMOJI.getValue(mood)

    /** One emoji of the mood, never the one shown last time when the mood has another. */
    fun emoji(mood: Mood, except: String?, pick: (Int) -> Int): String {
        val list = emojiPool(mood)
        val pool = if (list.size > 1) list.filter { it != except } else list
        return pool[pick(pool.size).coerceIn(0, pool.size - 1)]
    }

    /** Whether this emote gets a bubble; [roll] is a uniform draw in 0..1. */
    fun due(now: Long, nextAt: Long, roll: Double): Boolean = now >= nextAt && roll <= CHANCE

    /** When the next bubble may show at the earliest; [roll] is a uniform draw in 0..1. */
    fun nextAt(now: Long, roll: Double): Long =
        now + GAP_MIN_MS + (roll.coerceIn(0.0, 1.0) * (GAP_MAX_MS - GAP_MIN_MS)).toLong()
}
