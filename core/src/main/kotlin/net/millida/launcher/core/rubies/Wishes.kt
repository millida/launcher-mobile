package net.millida.launcher.core.rubies

import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max

/**
 * What a wished item waits on, in the order the grid shows them: what can be
 * taken now comes first. The service has no wishlist route, so the status is
 * read from the day's shop, the workshop and the catalogue, as the desktop
 * launcher does (src/components/shop/wish.ts).
 */
enum class WishStatus { SHOP, WORKSHOP, RETURNS, EARN, OWNED }

data class WishEntry(
    val item: RubyItem,
    val status: WishStatus,
    val card: ShopCard? = null,
    val source: ShopSource? = null,
    val returnsAt: String? = null,
    val cost: Long = 0,
)

data class WishList(val items: List<WishEntry>, val total: Long, val missing: Long)

object Wishes {
    private const val RUBIES_PER_RUBLE = 7L
    private const val DAY_MS = 86_400_000L

    fun shopCards(day: ShopDay): List<Pair<ShopCard, ShopSource>> = buildList {
        day.day.featured?.let { add(it to ShopSource.FEATURED) }
        day.day.deal?.let { add(it to ShopSource.DEAL) }
        day.day.items.forEach { add(it to ShopSource.DAY) }
        day.day.forYou.forEach { add(it to ShopSource.FOR_YOU) }
        day.nightMarket?.cards.orEmpty().forEach { add(it to ShopSource.NIGHT) }
    }

    fun of(day: ShopDay, workshop: Workshop?, balance: Long, catalog: (String) -> RubyItem?): WishList {
        val inShop = shopCards(day).associateBy { it.first.item.code }
        val inWork = workshop?.workshop?.items.orEmpty().associateBy { it.item.code }
        val items = day.wishlist.distinct().mapNotNull { code ->
            val shop = inShop[code]
            val work = inWork[code]
            when {
                shop != null -> {
                    val (card, source) = shop
                    WishEntry(card.item, if (card.owned) WishStatus.OWNED else WishStatus.SHOP, card = card, source = source)
                }
                work != null -> WishEntry(work.item, if (work.owned) WishStatus.OWNED else WishStatus.WORKSHOP, cost = work.cost)
                else -> catalog(code)?.let { WishEntry(it, WishStatus.RETURNS) }
            }
        }
        val total = items.filter { it.status == WishStatus.SHOP }.sumOf { it.card?.price ?: 0L }
        return WishList(sorted(items), total, max(0L, total - balance))
    }

    fun sorted(list: List<WishEntry>): List<WishEntry> = list.sortedWith(
        compareBy<WishEntry> { it.status.ordinal }.thenBy { entry ->
            entry.returnsAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: Long.MAX_VALUE
        },
    )

    /** The service prices an exact top-up per ruby into an integer field, so the count is rounded up to whole rubles. */
    fun topUpRubies(missing: Long): Long = max(RUBIES_PER_RUBLE, ceil(missing / RUBIES_PER_RUBLE.toDouble()).toLong() * RUBIES_PER_RUBLE)

    fun daysUntil(iso: String, now: Long): Long {
        val at = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return 1
        return max(1L, ceil((at - now) / DAY_MS.toDouble()).toLong())
    }
}
