package net.millida.launcher.ui.shop

import net.millida.launcher.RevealCard
import net.millida.launcher.RewardKind
import net.millida.launcher.ui.Rarity
import kotlin.math.max
import kotlin.math.min

/** One case of an opening: its rewards (the main one first), what it was and its rarity. */
internal class RevealOpen(val cards: List<RevealCard>, val kind: String, val rarity: Rarity?)

internal const val OPEN_ITEM = "item"
internal const val ANSWER_TIMEOUT_MS = 15_000L

/** How many hits a chest takes before its answer is known (chestDrops.ts CHEST_DROPS). */
internal fun chestHits(tier: String): Int = when (tier.uppercase()) {
    "RARE" -> 4
    "EPIC", "LEGEND", "LEGENDARY" -> 5
    else -> 3
}

internal fun RevealCard.rarityOf(): Rarity? = Rarity.of(rarity) ?: Rarity.of(item?.rarity)

/** The rank the reveal sorts and sounds by: an item's rarity, currency counts as the lowest. */
internal fun RevealCard.revealRank(): Int = if (item != null && (kind == RewardKind.ITEM || kind == RewardKind.FRAGMENTS)) rarityOf()?.ordinal ?: 0 else 0

/** Steps from the floor to the result inclusive; a result under the floor or not an item is the floor alone. */
internal fun revealLadder(floor: Rarity, final: Rarity?): List<Rarity> {
    val from = floor.ordinal
    val to = final?.ordinal ?: -1
    if (to <= from) return listOf(floor)
    return Rarity.entries.subList(from, to + 1)
}

/** The step after hit [hit] of [need]: rises land on the last hits, the last hit is always the result. */
internal fun ladderAt(ladder: List<Rarity>, hit: Int, need: Int): Int {
    val top = ladder.size - 1
    if (hit <= 0) return 0
    return max(0, min(top, top - (need - hit)))
}

/** What the chest climbs to: the best item of the opening, cheap misses and currency do not count. */
internal fun finalRarity(opens: List<RevealOpen>): Rarity? =
    opens.filter { it.kind == OPEN_ITEM }.mapNotNull { it.rarity ?: it.cards.firstOrNull()?.rarityOf() }.maxByOrNull { it.ordinal }

/** ×10 card order: the best item last, the rest as they came. */
internal fun dealOrder(opens: List<RevealOpen>): List<Int> {
    var best = -1
    var rank = -1
    opens.forEachIndexed { i, o ->
        val r = if (o.kind == OPEN_ITEM) (o.rarity ?: o.cards.firstOrNull()?.rarityOf())?.ordinal ?: -1 else -1
        if (r > rank) {
            rank = r
            best = i
        }
    }
    val all = opens.indices.toList()
    if (best < 0) return all
    return all.filter { it != best } + best
}

internal enum class HitView { Tap, Wait, Stalled }

internal fun hitView(hits: Int, need: Int, answered: Boolean, late: Boolean): HitView = when {
    hits < need -> HitView.Tap
    late && !answered -> HitView.Stalled
    else -> HitView.Wait
}

/** The main reward of a case for its ×10 card: the item (or the cheap miss item), else the first sum. */
internal fun RevealOpen.main(): RevealCard? = cards.firstOrNull { it.item != null } ?: cards.firstOrNull()
