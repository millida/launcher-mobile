package net.millida.launcher.core.rubies

import kotlinx.serialization.Serializable

@Serializable
data class RubyItem(
    val code: String = "",
    val name: String = "",
    val slot: String = "",
    val rarity: String = "",
    val preview: String? = null,
    val previewUrl: String? = null,
    val variant: String = "",
    val color: String = "",
    val tintFrom: String = "",
) {
    val image: String? get() = preview?.takeIf { it.isNotBlank() } ?: previewUrl?.takeIf { it.isNotBlank() }
}

@Serializable
data class RubyBalance(val balance: Long = 0, val earned: Long = 0, val spent: Long = 0)

@Serializable
data class RubyReward(
    val kind: String = "",
    val amount: Long = 0,
    val tier: String = "",
    val rarity: String = "",
    val item: RubyItem? = null,
    val owned: Boolean = false,
    val shards: Long = 0,
    val source: String = "",
    val day: Int = 0,
    val chestId: String = "",
)

@Serializable
data class TrackDay(
    val day: Int = 0,
    val free: List<RubyReward> = emptyList(),
    val plus: List<RubyReward> = emptyList(),
    val freeState: String = "",
    val plusState: String = "",
)

@Serializable
data class DailySeason(
    val day: Int = 0,
    val cells: Int = 0,
    val endsAt: String = "",
    val nextUnlockAt: String? = null,
)

@Serializable
data class PendingChest(
    val id: String = "",
    val tier: String = "",
    val source: String = "",
    val title: String = "",
    val createdAt: String = "",
    val day: Int = 0,
)

@Serializable
data class DailyStatus(
    val claimedToday: Boolean = false,
    val streak: Int = 0,
    val cycleDay: Int = 0,
    val todayRubies: Long = 0,
    val todayChest: String? = null,
    val plus: Boolean = false,
    val track: List<TrackDay> = emptyList(),
    val season: DailySeason? = null,
    val chests: List<PendingChest> = emptyList(),
    val nextAt: String? = null,
)

data class DailyClaim(
    val status: DailyStatus,
    val granted: List<RubyReward>,
    val balance: Long,
)

enum class TrackRow(val wire: String) { FREE("free"), PLUS("plus") }

@Serializable
data class PityLeft(val epic: Int = 0, val legend: Int = 0)

@Serializable
data class ChestList(val pending: List<PendingChest> = emptyList(), val pityLeft: PityLeft? = null)

@Serializable
data class ChestVariant(val name: String = "", val color: String? = null, val from: String? = null)

@Serializable
data class ChestDrop(
    val kind: String = "",
    val amount: Long = 0,
    val rarity: String = "",
    val item: RubyItem? = null,
    val variant: ChestVariant? = null,
    val duplicate: Boolean = false,
    val shards: Long = 0,
    val rubies: Long = 0,
    val have: Int = 0,
    val need: Int = 0,
    val completed: Boolean = false,
    val topUp: Long = 0,
)

@Serializable
data class ChestOpenResult(
    val chestId: String = "",
    val tier: String = "",
    val hits: Int = 0,
    val drops: List<ChestDrop> = emptyList(),
    val rarity: String = "",
    val item: RubyItem? = null,
    val duplicate: Boolean = false,
    val rubies: Long = 0,
    val shards: Long = 0,
    val pity: Boolean = false,
    val pityLeft: PityLeft? = null,
)

@Serializable
data class RarityChance(
    val rarity: String = "",
    val title: String = "",
    val bp: Int = 0,
)

@Serializable
data class ShardRange(val min: Int = 0, val max: Int = 0)

@Serializable
data class RubyChance(val pct: Double = 0.0, val min: Int = 0, val max: Int = 0)

@Serializable
data class ChestOdds(
    val tier: String = "",
    val title: String = "",
    val hits: Int = 0,
    val items: Int = 0,
    val shards: ShardRange? = null,
    val chances: List<RarityChance> = emptyList(),
    val wholePct: Double = 0.0,
    val rubies: RubyChance? = null,
)

@Serializable
data class ChestRules(
    val items: List<ChestOdds> = emptyList(),
    val pityAfter: Int = 0,
    val pityLegendAfter: Int = 0,
)

@Serializable
data class RubyPack(
    val code: String = "",
    val title: String = "",
    val rubies: Long = 0,
    val kopecks: Long = 0,
    val bonus: Long = 0,
)

@Serializable
data class PackRules(val rubiesPerRuble: Int = 0, val items: List<RubyPack> = emptyList())

@Serializable
data class RubyRules(val chests: ChestRules = ChestRules(), val packs: PackRules = PackRules())

@Serializable
data class FragmentProgress(val have: Int = 0, val need: Int = 0, val off: Long = 0, val topUp: Long = 0)

@Serializable
data class ShopCard(
    val item: RubyItem = RubyItem(),
    val price: Long = 0,
    val basePrice: Long = 0,
    val discountPct: Int = 0,
    val owned: Boolean = false,
    val leavesAt: String = "",
    val fragments: FragmentProgress? = null,
)

@Serializable
data class ShopDayPlan(
    val featured: ShopCard? = null,
    val deal: ShopCard? = null,
    val items: List<ShopCard> = emptyList(),
    val forYou: List<ShopCard> = emptyList(),
    val refreshAt: String = "",
)

@Serializable
data class NightMarket(val endsAt: String = "", val cards: List<ShopCard> = emptyList())

@Serializable
data class ShopGift(
    val kind: String = "",
    val amount: Long = 0,
    val item: RubyItem? = null,
    val claimed: Boolean = false,
    val refreshAt: String = "",
)

@Serializable
data class ShopDay(
    val balance: Long = 0,
    val shards: Long = 0,
    val plus: Boolean = false,
    val day: ShopDayPlan = ShopDayPlan(),
    val nightMarket: NightMarket? = null,
    val packs: List<RubyPack> = emptyList(),
    val wishlist: List<String> = emptyList(),
    val gift: ShopGift? = null,
    val xray: XrayOffer? = null,
)

/** Where a card was bought matters: the price is checked against that shelf. */
enum class ShopSource(val wire: String) {
    DAY("day"),
    DEAL("deal"),
    FEATURED("featured"),
    FOR_YOU("forYou"),
    NIGHT("night"),

    /** Any shop item at its base price, bought from the try-on rather than from a shelf of the day. */
    WARDROBE("wardrobe"),
}

@Serializable
data class PurchaseResult(val balance: Long = 0, val granted: List<RubyItem> = emptyList())

@Serializable
data class WishResult(val wishlist: List<String> = emptyList())

/** An exact top-up is priced at 100/7 kopecks a ruby, so the kopecks come back fractional. */
@Serializable
data class PackPurchase(
    val pack: String = "",
    val rubies: Long = 0,
    val kopecks: Double = 0.0,
    val balance: Long = 0,
)

data class GiftClaim(val balance: Long, val shards: Long, val granted: List<RubyReward>)

@Serializable
data class ShardDay(val earned: Long = 0, val limit: Long = 0, val resetsAt: String = "")

@Serializable
data class WorkshopFragment(val item: RubyItem = RubyItem(), val have: Int = 0, val need: Int = 0, val topUp: Long = 0)

@Serializable
data class WorkshopOffer(val item: RubyItem = RubyItem(), val cost: Long = 0, val owned: Boolean = false)

@Serializable
data class WorkshopShelf(val items: List<WorkshopOffer> = emptyList(), val rotatesAt: String = "")

@Serializable
data class Workshop(
    val shards: Long = 0,
    val cap: Long = 0,
    val today: ShardDay? = null,
    val fragments: List<WorkshopFragment> = emptyList(),
    val workshop: WorkshopShelf = WorkshopShelf(),
)

@Serializable
data class CraftFragments(val amount: Int = 0, val have: Int = 0, val need: Int = 0, val topUp: Long = 0)

@Serializable
data class CraftResult(val shards: Long = 0, val item: RubyItem = RubyItem(), val fragments: CraftFragments? = null)

@Serializable
data class WeeklyParcel(
    val hours: Double = 0.0,
    val need: Double = 0.0,
    val ready: Boolean = false,
    val choices: List<RubyItem>? = null,
    val claimedAt: String? = null,
    val resetsAt: String = "",
)

@Serializable
data class PlaytimeReward(
    val code: String = "",
    val title: String = "",
    val hours: Int = 0,
    val hoursDone: Int = 0,
    val chest: String? = null,
    val rubies: Long = 0,
    val done: Boolean = false,
    val claimed: Boolean = false,
    val granted: Boolean? = null,
)

@Serializable
data class HourItem(val hours: Int = 0, val item: RubyItem = RubyItem(), val owned: Boolean = false)

@Serializable
data class EconomyProgress(
    val hours: Double = 0.0,
    val hourItems: List<HourItem> = emptyList(),
    val points: Long = 0,
    val items: List<PlaytimeReward> = emptyList(),
)

@Serializable
data class PlusStatus(
    val active: Boolean = false,
    val paidUntil: String? = null,
    val canceled: Boolean = false,
    val priceKopecks: Long = 0,
    val shardBoost: Double = 0.0,
)
