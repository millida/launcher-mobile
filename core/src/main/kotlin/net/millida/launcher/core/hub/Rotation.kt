package net.millida.launcher.core.hub

import java.time.Instant
import java.time.ZoneId

enum class HeroSlot { ARCANIA, ONEBLOCK, WEEK }

data class HeroPick(val index: Int, val stored: String)

/**
 * Rotation of the hub, the desktop `rotation.ts`: «today» changes once a day,
 * the hero slot once a day, never on every start. It is deterministic, so all
 * players see the same cards of the day.
 */
object Rotation {
    val HERO_ORDER: List<HeroSlot> = listOf(HeroSlot.ARCANIA, HeroSlot.ONEBLOCK, HeroSlot.WEEK)

    private const val MASK = 0xFFFFFFFFL

    /** The number of the local day: it changes at the player's midnight. */
    fun dayNumber(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Long =
        now.atZone(zone).toLocalDate().toEpochDay()

    /** The week starts on Monday, the same count the desktop uses for «Подборка недели». */
    fun weekNumber(day: Long): Long = Math.floorDiv(day + 3, 7L)

    /**
     * A position in a list of [size] for a day and a card's salt: neighbouring
     * days do not walk the list in order, and two cards of one day differ.
     * The arithmetic is the desktop's unsigned 32-bit one.
     */
    fun pickIndex(day: Long, size: Int, salt: Int): Int {
        if (size <= 0) return 0
        var x = (day * 2654435761L + salt * 40503L) and MASK
        x = (x xor (x ushr 15)) and MASK
        x = (x * 2246822519L) and MASK
        x = (x xor (x ushr 13)) and MASK
        return (x % size).toInt()
    }

    /** [size] distinct items for the week, starting at the week's position; fewer when the list is shorter. */
    fun <T> weekPicks(items: List<T>, week: Long, size: Int): List<T> {
        if (items.isEmpty() || size <= 0) return emptyList()
        val start = pickIndex(week, items.size, salt = 7)
        return (0 until minOf(size, items.size)).map { items[(start + it) % items.size] }
    }

    /** The list turned to the day's position: the same order for everyone today, a new one tomorrow. */
    fun <T> ofDay(items: List<T>, day: Long, salt: Int): List<T> {
        if (items.size < 2) return items
        val start = pickIndex(day, items.size, salt)
        return items.drop(start) + items.take(start)
    }

    /**
     * One hero a day: the stored «date:index» keeps today's slot, a new date
     * moves to the next one, nothing stored starts with the first.
     */
    fun heroPick(stored: String?, today: String): HeroPick {
        val day = stored?.substringBefore(':', "").orEmpty()
        val was = stored?.substringAfter(':', "")?.toIntOrNull()?.takeIf { it >= 0 } ?: 0
        val index = when {
            day == today -> was
            day.isNotEmpty() -> was + 1
            else -> 0
        } % HERO_ORDER.size
        return HeroPick(index, "$today:$index")
    }

    /** The slots in show order: today's first, the rest as a fallback when today's has nothing to show. */
    fun heroQueue(index: Int): List<HeroSlot> = HERO_ORDER.indices.map { HERO_ORDER[(index + it) % HERO_ORDER.size] }
}
