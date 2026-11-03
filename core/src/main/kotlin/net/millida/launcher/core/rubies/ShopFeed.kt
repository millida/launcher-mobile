package net.millida.launcher.core.rubies

import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URISyntaxException

/** A one-time pack until the end of the Moscow month; null in the shop answer once bought. */
@Serializable
data class MonthOffer(
    val code: String = "",
    val title: String = "",
    val rubies: Long = 0,
    val kopecks: Long = 0,
    val oneTime: Boolean = false,
    val endsAt: String = "",
)

@Serializable
data class PlusOffer(val tier: String = "", val priceKopecks: Long = 0, val days: Int = 0)

@Serializable
data class SetPiece(val item: RubyItem = RubyItem(), val price: Long = 0, val owned: Boolean = false)

@Serializable
data class SetColorway(
    val name: String = "",
    val color: String? = null,
    val items: List<SetPiece> = emptyList(),
    val fullPrice: Long = 0,
    val price: Long = 0,
    val discountPct: Int = 0,
    val have: Int = 0,
    val plusPrice: Long? = null,
) {
    val complete: Boolean get() = items.isNotEmpty() && have >= items.size
}

@Serializable
data class ShopSet(
    val id: String = "",
    val title: String = "",
    val theme: String = "",
    val ofDay: Boolean = false,
    val colorways: List<SetColorway> = emptyList(),
) {
    /** The colourway shown first: the one the player owns most of, else the first. */
    val defaultWay: SetColorway?
        get() = colorways.fold(null as SetColorway?) { best, way -> if (best == null || way.have > best.have) way else best }
}

@Serializable
data class SetsDay(val sets: List<ShopSet> = emptyList(), val refreshAt: String = "", val plusTier: String? = null)

@Serializable
data class SetPurchase(val balance: Long = 0, val price: Long = 0, val granted: List<RubyItem> = emptyList())

@Serializable
data class CaseOdds(val rarity: String = "", val weight: Int = 0, val left: Int = 0, val total: Int = 0)

@Serializable
data class CasePrize(val kind: String = "", val min: Long = 0, val max: Long = 0, val chancePct: Double = 0.0)

@Serializable
data class CaseGuarantee(val minRarity: String = "", val label: String = "")

/** Ten cases for the price of nine, one charge; the ten hold at least [floor]. */
@Serializable
data class CaseMulti(val count: Int = 0, val price: Long = 0, val basePrice: Long = 0, val floor: String = "")

@Serializable
data class ShopCase(
    val id: String = "",
    val title: String = "",
    val price: Long = 0,
    val basePrice: Long = 0,
    val plusPrice: Long? = null,
    val ofDay: Boolean = false,
    val color: String = "",
    val odds: List<CaseOdds> = emptyList(),
    val pityLeft: Int = 0,
    val pity: Int = 0,
    val pityFrom: String = "",
    val left: Int = 0,
    val total: Int = 0,
    val cover: List<RubyItem> = emptyList(),
    val missPct: Double? = null,
    val cheapItem: Boolean = false,
    val bonus: List<CasePrize> = emptyList(),
    val currency: String = CURRENCY_RUBIES,
    val consolation: List<CasePrize> = emptyList(),
    val sets: List<String> = emptyList(),
    val guarantee: CaseGuarantee? = null,
    val multi: CaseMulti? = null,
    val freeAvailable: Boolean = false,
) {
    val paidInShards: Boolean get() = currency == CURRENCY_SHARDS
    val exhausted: Boolean get() = total > 0 && left <= 0

    fun priceOf(mode: CaseMode): Long = when (mode) {
        CaseMode.FREE -> 0
        CaseMode.TEN -> multi?.price ?: price
        CaseMode.ONE -> price
    }

    companion object {
        const val CURRENCY_RUBIES = "rubies"
        const val CURRENCY_SHARDS = "shards"
    }
}

enum class CaseMode { ONE, TEN, FREE }

@Serializable
data class CaseFeedDrop(val nick: String = "", val item: RubyItem = RubyItem(), val caseId: String = "", val at: String = "")

@Serializable
data class CasesDay(
    val cases: List<ShopCase> = emptyList(),
    val drops: List<CaseFeedDrop> = emptyList(),
    val refreshAt: String = "",
    val ofDayPct: Int = 30,
) {
    /** The free first case sits on one card: the case of the day when it qualifies, else the first that does. */
    val freeCaseId: String?
        get() = (cases.firstOrNull { it.ofDay && it.freeAvailable } ?: cases.firstOrNull { it.freeAvailable })?.id
}

@Serializable
data class CaseContentItem(val item: RubyItem = RubyItem(), val owned: Boolean = false, val chance: Double = 0.0)

@Serializable
data class CaseContents(
    val id: String = "",
    val items: List<CaseContentItem> = emptyList(),
    val missPct: Double? = null,
    val consolation: List<CasePrize> = emptyList(),
)

@Serializable
data class CaseFragments(val have: Int = 0, val need: Int = 0)

@Serializable
data class CaseReward(
    val kind: String = "",
    val item: RubyItem? = null,
    val amount: Long = 0,
    val frag: CaseFragments? = null,
    val dup: Boolean = false,
    val rarityName: String = "",
)

@Serializable
data class CaseOpenView(
    val rewards: List<CaseReward> = emptyList(),
    val kind: String = "",
    val rarity: String = "",
    val top: Boolean = false,
)

@Serializable
data class CaseOpened(
    val balance: Long = 0,
    val price: Long = 0,
    val kind: String = "",
    val rewards: List<CaseReward> = emptyList(),
    val amount: Long = 0,
    val shards: Long? = null,
    val rubies: Long = 0,
    val item: RubyItem? = null,
    val rarityName: String = "",
    val top: Boolean = false,
    val pityLeft: Int = 0,
    val count: Int = 1,
    val opens: List<CaseOpenView> = emptyList(),
    val best: String = "",
    val multiFloorUsed: Boolean = false,
) {
    /** Rewards of the answer: the list, else the one flat reward an older service sends. */
    val rewardList: List<CaseReward>
        get() {
            if (rewards.isNotEmpty()) return rewards
            val flat = kind.ifEmpty { KIND_ITEM }
            if (flat == KIND_ITEM || flat == KIND_CHEAP) {
                return item?.let { listOf(CaseReward(kind = flat, item = it, amount = amount)) }.orEmpty()
            }
            return listOf(CaseReward(kind = flat, amount = amount))
        }

    /** Cases one by one: ten for ×10, one otherwise, built from the flat fields when `opens` is missing. */
    val openList: List<CaseOpenView>
        get() {
            if (opens.isNotEmpty()) return opens
            val list = rewardList
            return listOf(CaseOpenView(list, kind.ifEmpty { KIND_ITEM }, list.firstOrNull()?.item?.rarity.orEmpty(), top))
        }

    companion object {
        const val KIND_ITEM = "item"
        const val KIND_CHEAP = "cheap"
        const val KIND_RUBIES = "rubies"
        const val KIND_SHARDS = "shards"
        const val KIND_FRAGMENTS = "fragments"
    }
}

/** An hour of play a day is a common chest, up to [limit]; [nextInS] null means nothing more today. */
@Serializable
data class PlayChests(
    val earned: Int = 0,
    val claimed: Int = 0,
    val ready: Int = 0,
    val limit: Int = 0,
    val nextInS: Long? = null,
    val chest: String = "",
)

@Serializable
data class PlayChestClaim(
    val earned: Int = 0,
    val claimed: Int = 0,
    val ready: Int = 0,
    val limit: Int = 0,
    val nextInS: Long? = null,
    val chest: String = "",
    val chestId: String = "",
) {
    val view: PlayChests get() = PlayChests(earned, claimed, ready, limit, nextInS, chest)
}

@Serializable
data class TelegramReward(
    val available: Boolean = false,
    val amount: Long = 0,
    val claimed: Boolean = false,
    val linked: Boolean = false,
    val botUrl: String = "",
    val channelUrl: String = "",
    val granted: Boolean = false,
    val balance: Long? = null,
)

@Serializable
data class DiscordReward(
    val available: Boolean = false,
    val amount: Long = 0,
    val claimed: Boolean = false,
    val linked: Boolean = false,
    val inviteUrl: String = "",
    val granted: Boolean = false,
    val balance: Long? = null,
)

enum class SocialStep { HIDDEN, LINK, JOIN }

fun telegramStep(view: TelegramReward?): SocialStep = socialStep(view?.claimed, view?.available, view?.amount, view?.linked)

fun discordStep(view: DiscordReward?): SocialStep = socialStep(view?.claimed, view?.available, view?.amount, view?.linked)

private fun socialStep(claimed: Boolean?, available: Boolean?, amount: Long?, linked: Boolean?): SocialStep = when {
    claimed == null || claimed -> SocialStep.HIDDEN
    available != true || (amount ?: 0) <= 0 -> SocialStep.HIDDEN
    linked == true -> SocialStep.JOIN
    else -> SocialStep.LINK
}

@Serializable
data class PromoResult(
    val code: String = "",
    val rubies: Long = 0,
    val chest: String? = null,
    val item: String? = null,
    val balance: Long = 0,
)

@Serializable
data class MoneyBalance(val kopecks: Long = -1, val displayMinor: Long = 0, val displayCurrency: String = "", val rate: Double = 0.0)

/** The account holds rubles only: [MoneyBalance.kopecks] is what a purchase is checked against. */
@Serializable
data class MoneyView(
    val region: String = "",
    val displayCurrency: String = "",
    val balance: MoneyBalance = MoneyBalance(),
    val rubPerUsd: Double = 0.0,
) {
    val valid: Boolean
        get() = displayCurrency in MONEY_CURRENCIES && balance.kopecks >= 0 && balance.displayCurrency in MONEY_CURRENCIES && rubPerUsd > 0
}

private val MONEY_CURRENCIES = setOf("RUB", "USD")

/** Links from the service open in a browser: only the hosts each reward really uses. */
fun telegramLink(url: String?): String? = httpsOn(url, setOf("t.me"))

fun discordInvite(url: String?): String? = httpsOn(url, setOf("discord.gg", "discord.com"))

fun discordLinkPage(url: String?): String? = httpsOn(url, setOf("discord.com"))

private fun httpsOn(url: String?, hosts: Set<String>): String? {
    val text = url?.trim().orEmpty()
    if (text.isEmpty()) return null
    val uri = try {
        URI(text)
    } catch (malformed: URISyntaxException) {
        return null
    }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return null
    val host = uri.host?.lowercase() ?: return null
    return if (host in hosts) text else null
}

private const val REQUEST_ID_BYTES = 16
private val requestRandom = java.security.SecureRandom()

/** One key per case opening: the service replays a purchase by it, so a resend never charges twice. */
fun freshRequestId(): String {
    val bytes = ByteArray(REQUEST_ID_BYTES)
    requestRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

@Serializable
data class MilliQuota(val day: Int = 0, val month: Int = 0)

/** What PLUS changes in Milli, the AI pack builder: the limits and what she can add to a pack. */
@Serializable
data class MilliLimits(
    val free: MilliQuota = MilliQuota(),
    val plus: MilliQuota = MilliQuota(),
    val plusExtras: List<String> = emptyList(),
    val plusModes: List<String> = emptyList(),
) {
    val known: Boolean get() = plus.day > 0
}

@Serializable
data class LauncherStats(val downloads: Long = -1, val online: Long? = null)
