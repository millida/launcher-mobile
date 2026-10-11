package net.millida.launcher

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.cabinet.SignInRequired
import net.millida.launcher.core.friends.FriendsErrors
import net.millida.launcher.core.rubies.ChestDrop
import net.millida.launcher.core.rubies.ChestGone
import net.millida.launcher.core.rubies.ChestList
import net.millida.launcher.core.rubies.ChestOpenResult
import net.millida.launcher.core.rubies.CraftResult
import net.millida.launcher.core.rubies.CreatorClient
import net.millida.launcher.core.rubies.CreatorError
import net.millida.launcher.core.rubies.CreatorStatus
import net.millida.launcher.core.rubies.DailyStatus
import net.millida.launcher.core.rubies.EconomyProgress
import net.millida.launcher.core.rubies.PackQuest
import net.millida.launcher.core.rubies.PendingChest
import net.millida.launcher.core.rubies.PlaytimeReward
import net.millida.launcher.core.rubies.PlusStatus
import net.millida.launcher.core.rubies.PurchaseResult
import net.millida.launcher.core.rubies.RubiesClient
import net.millida.launcher.core.rubies.RubiesError
import net.millida.launcher.core.rubies.RubyItem
import net.millida.launcher.core.rubies.RubyPack
import net.millida.launcher.core.rubies.RubyReward
import net.millida.launcher.core.rubies.RubyRules
import net.millida.launcher.core.rubies.ShopCard
import net.millida.launcher.core.rubies.ShopDay
import net.millida.launcher.core.rubies.ShopSource
import net.millida.launcher.core.rubies.TrackRow
import net.millida.launcher.core.rubies.WalletShort
import net.millida.launcher.core.rubies.WeeklyAny
import net.millida.launcher.core.rubies.WeeklyPath
import net.millida.launcher.core.rubies.WelcomeGift
import net.millida.launcher.core.rubies.Workshop
import net.millida.launcher.core.rubies.WorkshopOffer
import net.millida.launcher.core.rubies.XrayOffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ShopLoad<T>(val data: T? = null, val loading: Boolean = false, val error: String? = null)

enum class ShopSection { DAILY, CHESTS, STORE, WORKSHOP, WEEKLY, PROGRESS, PLUS, QUESTS, CREATOR }

sealed interface DailyTarget {
    data class Cell(val day: Int, val row: TrackRow) : DailyTarget
    data object All : DailyTarget
}

data class RevealCard(
    val kind: String,
    val amount: Long = 0,
    val tier: String = "",
    val rarity: String = "",
    val item: RubyItem? = null,
    val plus: Boolean = false,
    val have: Int = 0,
    val need: Int = 0,
    val gain: Int = 0,
    val completed: Boolean = false,
    val duplicate: Boolean = false,
    val shards: Long = 0,
    val topUp: Long = 0,
)

data class RewardReveal(
    val serial: Long,
    val kicker: String,
    val cards: List<RevealCard>,
    val chests: List<PendingChest> = emptyList(),
)

data class ChestOpening(
    val serial: Long,
    val queue: List<PendingChest>,
    val started: Boolean = false,
    val result: ChestOpenResult? = null,
    val error: String? = null,
    val opened: Int = 0,
    val fresh: Boolean = false,
) {
    val chest: PendingChest get() = queue.first()
    val left: Int get() = queue.size - 1
    val waiting: Boolean get() = started && result == null && error == null
    val cards: List<RevealCard> get() = result?.drops.orEmpty().map(::dropCard)
}

data class ShopLink(val serial: Long, val url: String)

/** Busy key of the wardrobe's purchase, so the try-on bar shows it running. */
const val WARDROBE_PURCHASE = "wardrobe-purchase"

/** The case shakes until the purchase answers: the item arrives only with the server's confirmation. */
data class XrayRun(val serial: Long, val tier: String, val item: RubyItem? = null)

data class ShopState(
    val signedIn: Boolean = false,
    val loadedAt: Long = 0,
    val balance: Long? = null,
    val shards: Long? = null,
    val walletKopecks: Long? = null,
    val rules: RubyRules? = null,
    val daily: ShopLoad<DailyStatus> = ShopLoad(),
    val chests: ShopLoad<ChestList> = ShopLoad(),
    val store: ShopLoad<ShopDay> = ShopLoad(),
    val workshop: ShopLoad<Workshop> = ShopLoad(),
    val weekly: ShopLoad<WeeklyAny> = ShopLoad(),
    val progress: ShopLoad<EconomyProgress> = ShopLoad(),
    val plus: ShopLoad<PlusStatus> = ShopLoad(),
    val quests: ShopLoad<List<PackQuest>> = ShopLoad(),
    val creator: ShopLoad<CreatorStatus> = ShopLoad(),
    val busy: Set<String> = emptySet(),
    val reveal: RewardReveal? = null,
    val opening: ChestOpening? = null,
    val link: ShopLink? = null,
    val walletShort: Boolean = false,
    val rulesFailed: Boolean = false,
    val xray: XrayRun? = null,
    val completedFragments: Set<String> = emptySet(),
    val welcome: RewardReveal? = null,
) {
    /** The bonus answer and the chest list both carry unopened chests: one list, oldest first. */
    val pendingChests: List<PendingChest>
        get() {
            val seen = HashSet<String>()
            return (daily.data?.chests.orEmpty() + chests.data?.pending.orEmpty())
                .filter { it.id.isNotBlank() && seen.add(it.id) }
                .sortedBy { it.createdAt }
        }

    val dailyReady: Int
        get() {
            val status = daily.data ?: return 0
            return status.track.count { it.freeState == "ready" } + status.track.count { it.plusState == "ready" }
        }

    val badge: Int get() = dailyReady + pendingChests.size

    fun isBusy(key: String): Boolean = key in busy
}

class ShopModel(
    private val container: Container,
    private val scope: CoroutineScope,
    private val notice: (String, Boolean) -> Unit,
    private val onSessionLost: () -> Unit,
    private val onInventoryChanged: () -> Unit,
) {
    private val client = RubiesClient(container.millidaAuth)
    private val creators = CreatorClient(container.millidaAuth)
    private val mutable = MutableStateFlow(ShopState())
    val state: StateFlow<ShopState> = mutable.asStateFlow()

    private val tickets = ConcurrentHashMap<String, Long>()
    private val ticketSeq = AtomicLong()
    private val serials = AtomicLong()

    @Volatile
    private var payLink: ShopLink? = null

    @Volatile
    private var payLinkAt = 0L

    @Volatile
    private var awaitingPayment = false

    @Volatile
    private var primedAt = 0L

    @Volatile
    private var welcomeTried = false

    /** Only what the badge needs, so a ready bonus or an unopened chest shows before the shop is opened. */
    fun prime(signedIn: Boolean) {
        if (!signedIn) {
            signOut()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val current = mutable.value
        val latest = maxOf(current.loadedAt, primedAt)
        if (current.signedIn && latest != 0L && now - latest <= STALE_MS) return
        primedAt = now
        mutable.update { it.copy(signedIn = true) }
        loadDaily()
        loadChests()
        claimWelcomeOnce()
    }

    fun open(signedIn: Boolean) {
        if (!signedIn) {
            signOut()
            loadRules()
            return
        }
        val current = mutable.value
        val stale = SystemClock.elapsedRealtime() - current.loadedAt > STALE_MS
        if (!current.signedIn || current.loadedAt == 0L || stale) refresh()
    }

    fun refresh() {
        mutable.update { it.copy(signedIn = true, loadedAt = SystemClock.elapsedRealtime()) }
        loadBalance()
        loadWallet()
        loadDaily()
        loadChests()
        loadStore()
        loadWorkshop()
        loadWeekly()
        loadProgress()
        loadPlus()
        loadQuests()
        loadCreator()
        if (mutable.value.rules == null) loadRules()
    }

    /** Back from the browser: a payment or a wallet top-up may have landed while we were away. */
    fun resume() {
        val current = mutable.value
        if (!current.signedIn) return
        if (awaitingPayment) {
            awaitingPayment = false
            loadPlus()
            loadDaily()
            loadBalance()
            loadWallet()
            return
        }
        if (SystemClock.elapsedRealtime() - current.loadedAt > STALE_MS) refresh()
    }

    fun retry(section: ShopSection) {
        when (section) {
            ShopSection.DAILY -> loadDaily()
            ShopSection.CHESTS -> {
                loadChests()
                if (mutable.value.rules == null) loadRules()
            }
            ShopSection.STORE -> {
                loadStore()
                loadBalance()
                if (mutable.value.rules == null) loadRules()
            }
            ShopSection.WORKSHOP -> loadWorkshop()
            ShopSection.WEEKLY -> loadWeekly()
            ShopSection.PROGRESS -> loadProgress()
            ShopSection.PLUS -> loadPlus()
            ShopSection.QUESTS -> loadQuests()
            ShopSection.CREATOR -> loadCreator()
        }
    }

    fun claimDaily(target: DailyTarget) {
        perform("daily", "Награда не забралась — повтори") {
            val claim = io {
                when (target) {
                    is DailyTarget.Cell -> client.claimDaily(target.day, target.row)
                    DailyTarget.All -> client.claimAll()
                }
            }
            retire("daily")
            val chestIds = claim.granted.filter { it.kind == RewardKind.CHEST }.map { it.chestId }.filter { it.isNotBlank() }
            val cards = claim.granted.sortedBy { if (it.source == "plus") 1 else 0 }.map(::rewardCard)
            mutable.update { s ->
                s.copy(
                    daily = ShopLoad(claim.status),
                    balance = claim.balance,
                    reveal = reveal(KICKER_DAILY, cards, claim.status.chests.filter { it.id in chestIds }),
                )
            }
            if (cards.isEmpty()) notice("Здесь уже всё забрано — новая клетка откроется завтра", false)
            if (claim.granted.any { it.kind == RewardKind.SHARDS || it.kind == RewardKind.FRAGMENTS }) loadWorkshop()
            if (claim.granted.any { it.kind == RewardKind.ITEM }) onInventoryChanged()
        }
    }

    fun openChests(list: List<PendingChest>) {
        if (list.isEmpty()) return
        mutable.update { s -> if (s.opening != null) s else s.copy(opening = ChestOpening(serials.incrementAndGet(), list)) }
    }

    fun startOpening() {
        var target: ChestOpening? = null
        mutable.update { s ->
            val current = s.opening
            if (current == null || current.started) {
                target = null
                s
            } else {
                val next = current.copy(started = true)
                target = next
                s.copy(opening = next)
            }
        }
        val opening = target ?: return
        val chest = opening.chest
        scope.launch {
            try {
                val result = io { client.openChest(chest.id) }
                val fresh = result.drops.any { isFresh(it) }
                var detached = false
                mutable.update { s ->
                    val trimmed = s.withoutChest(chest.id)
                    val current = trimmed.opening
                    if (current == null || current.serial != opening.serial) {
                        detached = true
                        trimmed
                    } else {
                        detached = false
                        trimmed.copy(opening = current.copy(result = result, opened = current.opened + 1, fresh = current.fresh || fresh))
                    }
                }
                if (detached) settleDetached(result.drops.map(::dropCard), fresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "сундук ${chest.id} не открылся: ${error.message}")
                val lost = error.isSessionLost()
                if (lost) onSessionLost()
                val text = textOf(error, "Сундук не открылся — попробуй ещё раз")
                var detached = false
                mutable.update { s ->
                    val base = if (error is ChestGone) s.withoutChest(chest.id) else s
                    val current = base.opening
                    if (current == null || current.serial != opening.serial) {
                        detached = true
                        base
                    } else {
                        detached = false
                        base.copy(opening = current.copy(error = text))
                    }
                }
                if (detached && !lost && mutable.value.signedIn) {
                    notice(text, true)
                    loadChests()
                }
            }
        }
    }

    private fun settleDetached(cards: List<RevealCard>, fresh: Boolean) {
        if (!mutable.value.signedIn) return
        val shown = reveal(KICKER_CHEST, cards)
        var placed = false
        mutable.update { s ->
            placed = s.reveal == null
            if (placed) s.copy(reveal = shown) else s
        }
        if (!placed) notice("Сундук открылся — награда уже у тебя", false)
        loadBalance()
        loadWorkshop()
        loadStore()
        loadChests()
        if (fresh) onInventoryChanged()
    }

    fun nextChest() {
        val current = mutable.value.opening ?: return
        if (current.waiting) return
        val rest = current.queue.drop(1)
        if (rest.isEmpty()) {
            closeOpening()
            return
        }
        mutable.update {
            it.copy(opening = ChestOpening(serials.incrementAndGet(), rest, opened = current.opened, fresh = current.fresh))
        }
    }

    /** A roll in flight is detached, not cancelled: its chest leaves the lists so it cannot be tapped twice. */
    fun closeOpening() {
        var closed: ChestOpening? = null
        mutable.update { s ->
            val current = s.opening
            closed = current
            when {
                current == null -> s
                current.waiting -> s.copy(opening = null).withoutChest(current.chest.id)
                else -> s.copy(opening = null)
            }
        }
        val current = closed ?: return
        if (current.opened > 0) {
            loadBalance()
            loadWorkshop()
            loadStore()
            if (!current.waiting) loadChests()
            if (current.fresh) onInventoryChanged()
        }
    }

    fun buy(card: ShopCard, source: ShopSource) {
        val code = card.item.code
        perform("buy:$code", "Покупка не прошла — повтори") {
            val result = purchase(code, source)
            val granted = result.granted.ifEmpty { listOf(card.item) }
            mutable.update { s -> s.copy(reveal = reveal(KICKER_BOUGHT, granted.map { itemCard(it) })) }
            loadStore()
            onInventoryChanged()
        }
    }

    /**
     * The try-on bill, bought from the wardrobe at the shop's base price. Pieces
     * go one by one, as on the desktop: the service charges and grants each in
     * one transaction, so a refused piece must not undo the ones already paid
     * for. [onBought] gets the codes the service confirmed, and only those.
     */
    fun buyFromWardrobe(items: List<RubyItem>, onBought: (List<String>) -> Unit) {
        if (items.isEmpty()) return
        perform(WARDROBE_PURCHASE, "Покупка не прошла — повтори") {
            val bought = ArrayList<String>()
            val refused = ArrayList<String>()
            for (item in items) {
                try {
                    purchase(item.code, ShopSource.WARDROBE)
                    bought.add(item.code)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "гардероб: ${item.code} не купилась: ${error.message}")
                    if (error.isSessionLost()) {
                        onSessionLost()
                        refused.add(textOf(error, ""))
                        break
                    }
                    refused.add("${item.name} (${textOf(error, "отказ").replaceFirstChar { it.lowercase() }})")
                }
            }
            if (bought.isNotEmpty()) {
                loadStore()
                onInventoryChanged()
                onBought(bought)
            }
            if (refused.isNotEmpty()) notice("Не удалось купить: " + refused.joinToString(", "), true)
        }
    }

    /** Reads the balance alone, for a screen that shows a price outside the shop. */
    fun refreshBalance() {
        loadBalance()
    }

    private suspend fun purchase(code: String, source: ShopSource): PurchaseResult {
        val result = io { client.buy(code, source) }
        mutable.update { s -> s.copy(balance = result.balance, store = s.store.copy(data = s.store.data?.markOwned(code))) }
        return result
    }

    fun wish(code: String, on: Boolean) {
        val key = "wish:$code"
        if (!take(key)) return
        mutable.update { s -> s.copy(store = s.store.copy(data = s.store.data?.withWish(code, on))) }
        launchTaken(key, "«Хочу» не сохранилось — повтори", rollback = {
            mutable.update { s -> s.copy(store = s.store.copy(data = s.store.data?.withWish(code, !on))) }
        }) {
            val list = io { client.wish(code, on) }
            mutable.update { s -> s.copy(store = s.store.copy(data = s.store.data?.copy(wishlist = list))) }
            if (on) notice("Добавлено в «Хочу»", false)
        }
    }

    fun claimGift() {
        perform("gift", "Подарок не забрался — повтори") {
            val claim = io { client.claimGift() }
            mutable.update { s ->
                s.copy(
                    balance = claim.balance,
                    shards = claim.shards,
                    store = s.store.copy(data = s.store.data?.let { day -> day.copy(gift = day.gift?.copy(claimed = true)) }),
                    reveal = reveal(KICKER_GIFT, claim.granted.map(::rewardCard)),
                )
            }
            if (claim.granted.isEmpty()) notice("Подарок уже забран — новый завтра", false)
            loadStore()
            if (claim.granted.any { it.kind == RewardKind.SHARDS }) loadWorkshop()
            if (claim.granted.any { it.kind == RewardKind.ITEM }) onInventoryChanged()
        }
    }

    fun buyPack(pack: RubyPack) {
        perform("pack:${pack.code}", "Пакет не оплатился — повтори") {
            val bought = io { client.buyPack(pack.code) }
            mutable.update { s ->
                s.copy(balance = bought.balance, reveal = reveal(KICKER_CREDITED, listOf(RevealCard(RewardKind.RUBIES, amount = bought.rubies))))
            }
            loadWallet()
        }
    }

    fun topUp(rubies: Int) {
        perform("topup", "Рубины не докупились — повтори") {
            val bought = io { client.topUp(rubies.coerceAtLeast(RubiesClient.EXACT_MIN_RUBIES)) }
            mutable.update { s ->
                s.copy(balance = bought.balance, reveal = reveal(KICKER_CREDITED, listOf(RevealCard(RewardKind.RUBIES, amount = bought.rubies))))
            }
            loadWallet()
        }
    }

    fun craft(offer: WorkshopOffer) {
        val code = offer.item.code
        perform("craft:$code", "Осколки не обменялись — повтори") {
            val made = io { client.craft(code) }
            mutable.update { s ->
                s.copy(
                    shards = made.shards,
                    workshop = s.workshop.copy(data = s.workshop.data?.copy(shards = made.shards)),
                    reveal = reveal(KICKER_WORKSHOP, listOf(craftCard(made, offer))),
                )
            }
            loadWorkshop()
            loadStore()
        }
    }

    fun claimWeekly(item: RubyItem) {
        perform("weekly", "Посылка не забралась — повтори") {
            val granted = io { client.claimWeekly(item.code) }
            mutable.update { s -> s.copy(reveal = reveal(KICKER_WEEKLY, listOf(itemCard(granted.takeIf { it.code.isNotBlank() } ?: item)))) }
            loadWeekly()
            onInventoryChanged()
        }
    }

    fun claimWeeklyStep(at: Int) {
        perform("weekly", "Награда недели не забралась — повтори") {
            val claim = io { client.claimWeeklyStep(at) }
            val granted = claim.granted
            val prize = granted.item
            val card = if (granted.kind == RewardKind.ITEM && prize != null) {
                itemCard(prize)
            } else {
                RevealCard(RewardKind.SHARDS, amount = granted.amount)
            }
            mutable.update { s ->
                s.copy(
                    weekly = ShopLoad(WeeklyAny.Path(claim.path)),
                    balance = claim.balance,
                    shards = claim.shards,
                    reveal = reveal(KICKER_WEEKLY_PATH, listOf(card)),
                )
            }
            loadWorkshop()
            if (granted.kind == RewardKind.ITEM) onInventoryChanged()
        }
    }

    /** The price was on the button and confirmed; the path moves only after the service takes the payment. */
    fun boostWeekly(path: WeeklyPath) {
        val boost = path.boost ?: return
        if (!boost.available || boost.missing <= 0) return
        perform("weekly-boost", "Звёзды не докупились — повтори") {
            val result = io { client.boostWeekly(boost.missing) }
            mutable.update { s ->
                s.copy(
                    weekly = ShopLoad(WeeklyAny.Path(result.path)),
                    balance = result.balance,
                    reveal = reveal(KICKER_WEEKLY_PATH, listOf(RevealCard(RewardKind.STARS, amount = boost.missing.toLong()))),
                )
            }
        }
    }

    fun buyXray(offer: XrayOffer) {
        val key = "xray:${offer.id}"
        if (!take(key)) return
        val run = XrayRun(serials.incrementAndGet(), offer.tier)
        mutable.update { it.copy(xray = run) }
        launchTaken(key, "Кейс не купился — повтори", rollback = {
            mutable.update { s -> if (s.xray?.serial == run.serial) s.copy(xray = null) else s }
        }) {
            val bought = io { client.buyXray(offer.id) }
            val item = bought.item.takeIf { it.code.isNotBlank() } ?: offer.item
            var attached = false
            mutable.update { s ->
                val current = s.xray
                attached = current?.serial == run.serial
                s.copy(balance = bought.balance, xray = if (attached && current != null) current.copy(item = item) else current)
            }
            if (!attached) settleXray(item)
        }
    }

    /** Called once the lid is up: only then the item takes the screen. */
    fun finishXray(serial: Long) {
        var item: RubyItem? = null
        mutable.update { s ->
            val current = s.xray
            if (current == null || current.serial != serial) {
                s
            } else {
                item = current.item
                s.copy(xray = null)
            }
        }
        settleXray(item ?: return)
    }

    private fun settleXray(item: RubyItem) {
        val shown = reveal(KICKER_XRAY, listOf(itemCard(item)))
        var placed = false
        mutable.update { s ->
            placed = s.reveal == null
            if (placed) s.copy(reveal = shown) else s
        }
        if (!placed) notice("Кейс открыт: ${item.name}", false)
        loadStore()
        onInventoryChanged()
    }

    fun claimQuest(quest: PackQuest) {
        perform("quest:${quest.code}", "Награда не забралась — повтори") {
            val claim = io { client.claimQuest(quest.code) }
            val updated = claim.quest.takeIf { it.code.isNotBlank() }
            if (updated != null) {
                mutable.update { s ->
                    s.copy(quests = s.quests.copy(data = s.quests.data?.map { if (it.code == updated.code) updated else it }))
                }
            } else {
                loadQuests()
            }
            val got = claim.item
            notice(if (got != null) "Получено: ${got.name}" else "Задание выполнено", false)
            loadWorkshop()
            onInventoryChanged()
        }
    }

    /**
     * "Докупить фрагменты": one purchase for the chest summary, the started items
     * and the newcomer gift. The item is the player's only after the service answers.
     */
    fun completeFragments(item: RubyItem, celebrate: Boolean) {
        val code = item.code
        perform("fragments:$code", "Фрагменты не докупились — повтори") {
            val result = io { client.completeFragments(code) }
            val got = result.granted.firstOrNull() ?: item
            mutable.update { s ->
                s.copy(
                    balance = result.balance,
                    completedFragments = s.completedFragments + code,
                    reveal = if (celebrate && s.reveal == null) reveal(KICKER_FRAGMENTS, listOf(itemCard(got))) else s.reveal,
                )
            }
            loadWorkshop()
            loadProgress()
            loadStore()
            onInventoryChanged()
        }
    }

    fun setCreator(code: String, onError: (String) -> Unit, onDone: () -> Unit) {
        val live = mutable.value.creator.data?.live == true
        if (!take(KEY_CREATOR)) return
        scope.launch {
            try {
                val answer = io { creators.set(code, live) }
                mutable.update { s -> s.copy(creator = ShopLoad(CreatorStatus(live = true, current = answer.support))) }
                onDone()
                val bonus = answer.bonus?.takeIf { it.tier.isNotBlank() }
                val shown = if (bonus != null) {
                    reveal("Ты поддерживаешь ${answer.name}", listOf(RevealCard(RewardKind.CHEST, tier = bonus.tier)))
                } else {
                    reveal(KICKER_CREATOR, listOf(RevealCard(RewardKind.CREATOR, item = RubyItem(name = answer.name, previewUrl = answer.avatarUrl))))
                }
                mutable.update { s -> if (s.reveal == null) s.copy(reveal = shown) else s }
                if (bonus != null) {
                    loadDaily()
                    loadChests()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "магазин: код автора не принят: ${error.message}")
                if (error.isSessionLost()) onSessionLost()
                onError(if (error is CreatorError) error.message.orEmpty() else textOf(error, CREATOR_FALLBACK))
            } finally {
                mutable.update { it.copy(busy = it.busy - KEY_CREATOR) }
            }
        }
    }

    fun clearCreator() {
        perform(KEY_CREATOR, CREATOR_FALLBACK) {
            io { creators.clear() }
            mutable.update { s -> s.copy(creator = ShopLoad(CreatorStatus(live = true, current = null))) }
        }
    }

    fun becomeCreator() {
        mutable.update { it.copy(link = ShopLink(serials.incrementAndGet(), CreatorClient.CREATORS_URL)) }
    }

    fun tellShort(text: String) = notice(text, true)

    fun dismissWelcome(serial: Long) = mutable.update { if (it.welcome?.serial == serial) it.copy(welcome = null) else it }

    /**
     * Once per device, like the desktop splash: a newcomer gets half the
     * fragments of an item. The mark is written only after the service answered,
     * so a dropped connection does not lose the gift.
     */
    private fun claimWelcomeOnce() {
        if (welcomeTried || container.vault.read(WELCOME_KEY) != null) return
        welcomeTried = true
        scope.launch {
            try {
                val gift = io { client.claimWelcome() }
                container.vault.write(WELCOME_KEY, "1")
                val card = welcomeCard(gift) ?: return@launch
                mutable.update { s -> s.copy(welcome = reveal(KICKER_WELCOME, listOf(card))) }
                loadWorkshop()
                onInventoryChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "магазин: подарок новичку не забрался: ${error.message}")
                if (error.isSessionLost()) onSessionLost()
            }
        }
    }

    fun claimProgress(reward: PlaytimeReward) {
        perform("progress:${reward.code}", "Награда не забралась — повтори") {
            val updated = io { client.claimProgress(reward.code) }
            val granted = updated.granted != false
            val cards: List<RevealCard> = if (!granted) {
                emptyList()
            } else {
                buildList {
                    chestTierOfName(updated.chest)?.let { add(RevealCard(RewardKind.CHEST, tier = it)) }
                    if (updated.rubies > 0) add(RevealCard(RewardKind.RUBIES, amount = updated.rubies))
                }
            }
            if (!granted) notice("Эта награда уже получена", false)
            mutable.update { s ->
                s.copy(
                    progress = s.progress.copy(
                        data = s.progress.data?.let { p -> p.copy(items = p.items.map { if (it.code == updated.code) updated else it }) },
                    ),
                    reveal = reveal(KICKER_PLAYTIME, cards),
                )
            }
            loadChests()
            loadDaily()
            if (updated.rubies > 0) loadBalance()
        }
    }

    /** A fresh unpaid link is reused for a while: a new subscription per tap left a trail of "awaiting payment". */
    fun subscribePlus() {
        val cached = payLink
        if (cached != null && SystemClock.elapsedRealtime() - payLinkAt < PAY_LINK_TTL_MS) {
            awaitingPayment = true
            mutable.update { it.copy(link = ShopLink(serials.incrementAndGet(), cached.url)) }
            return
        }
        perform("plus", "Подписка не оформилась — повтори") {
            val url = io { client.subscribePlus() }
            val link = ShopLink(serials.incrementAndGet(), url)
            payLink = link
            payLinkAt = SystemClock.elapsedRealtime()
            awaitingPayment = true
            mutable.update { it.copy(link = link) }
        }
    }

    fun managePlus() {
        awaitingPayment = true
        mutable.update { it.copy(link = ShopLink(serials.incrementAndGet(), PLUS_MANAGE_URL)) }
    }

    fun openWalletTopUp() {
        awaitingPayment = true
        mutable.update { it.copy(walletShort = false, link = ShopLink(serials.incrementAndGet(), container.wallet.topUpUrl())) }
    }

    fun dismissWalletShort() = mutable.update { it.copy(walletShort = false) }

    fun consumeLink(serial: Long) = mutable.update { if (it.link?.serial == serial) it.copy(link = null) else it }

    fun dismissReveal(serial: Long) = mutable.update { if (it.reveal?.serial == serial) it.copy(reveal = null) else it }

    private fun signOut() {
        tickets.clear()
        welcomeTried = false
        payLink = null
        awaitingPayment = false
        primedAt = 0L
        if (mutable.value != ShopState()) mutable.value = ShopState()
    }

    private fun loadBalance() = quiet("balance", { client.balance() }) { s, v -> s.copy(balance = v.balance) }

    private fun loadWallet() = quiet("wallet", { container.wallet.balance() }) { s, v -> s.copy(walletKopecks = v.availableKopecks) }

    private fun loadRules() {
        mutable.update { it.copy(rulesFailed = false) }
        quiet("rules", { client.rules() }, onError = { it.copy(rulesFailed = true) }) { s, v -> s.copy(rules = v) }
    }

    private fun loadDaily() = load("daily", { client.daily() }, { it.daily }, { s, v -> s.copy(daily = v) })

    private fun loadChests() = load("chests", { client.chests() }, { it.chests }, { s, v -> s.copy(chests = v) })

    private fun loadStore() = load(
        "store",
        { client.shop() },
        { it.store },
        { s, v -> s.copy(store = v) },
        also = { s, day -> s.copy(balance = day.balance, shards = day.shards) },
    )

    private fun loadWorkshop() = load(
        "workshop",
        { client.workshop() },
        { it.workshop },
        { s, v -> s.copy(workshop = v) },
        also = { s, shop -> s.copy(shards = shop.shards) },
    )

    private fun loadWeekly() = load("weekly", { client.weekly() }, { it.weekly }, { s, v -> s.copy(weekly = v) })

    private fun loadQuests() = load("quests", { client.quests() }, { it.quests }, { s, v -> s.copy(quests = v) })

    private fun loadCreator() = load("creator", { creators.load() }, { it.creator }, { s, v -> s.copy(creator = v) })

    /** The big moment of a fresh PLUS; when another reveal holds the screen the line says it instead. */
    private fun celebratePlus() {
        val shown = reveal(KICKER_PLUS, listOf(RevealCard(RewardKind.PLUS)))
        var placed = false
        mutable.update { s ->
            placed = s.reveal == null
            if (placed) s.copy(reveal = shown) else s
        }
        if (!placed) notice("PLUS подключён — забирай награды золотой строки", false)
    }

    private fun loadProgress() = load("progress", { client.progress() }, { it.progress }, { s, v -> s.copy(progress = v) })

    private fun loadPlus() {
        val before = mutable.value.plus.data?.active
        load(
            "plus",
            { client.plus() },
            { it.plus },
            { s, v -> s.copy(plus = v) },
            after = { status ->
                if (before == false && status.active) {
                    celebratePlus()
                    loadDaily()
                }
            },
        )
    }

    private fun <T> load(
        key: String,
        read: suspend () -> T,
        get: (ShopState) -> ShopLoad<T>,
        put: (ShopState, ShopLoad<T>) -> ShopState,
        also: (ShopState, T) -> ShopState = { s, _ -> s },
        after: (T) -> Unit = {},
    ): Job {
        val ticket = retire(key)
        mutable.update { put(it, get(it).copy(loading = true)) }
        return scope.launch {
            try {
                val value = io { read() }
                var fresh = false
                mutable.update { s ->
                    fresh = tickets[key] == ticket
                    if (fresh) also(put(s, ShopLoad(data = value)), value) else s
                }
                if (fresh) after(value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "магазин: $key не загрузился: ${error.message}")
                if (error.isSessionLost()) onSessionLost()
                val text = textOf(error, LOAD_FALLBACK)
                mutable.update { s -> if (tickets[key] == ticket) put(s, get(s).copy(loading = false, error = text)) else s }
            }
        }
    }

    /** Figures the page can live without for a moment: a failure is logged, the screen keeps its last value. */
    private fun <T> quiet(
        key: String,
        read: suspend () -> T,
        onError: (ShopState) -> ShopState = { it },
        apply: (ShopState, T) -> ShopState,
    ): Job {
        val ticket = retire(key)
        return scope.launch {
            try {
                val value = io { read() }
                mutable.update { s -> if (tickets[key] == ticket) apply(s, value) else s }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "магазин: $key не загрузился: ${error.message}")
                if (error.isSessionLost()) onSessionLost()
                mutable.update { s -> if (tickets[key] == ticket) onError(s) else s }
            }
        }
    }

    private fun retire(key: String): Long {
        val ticket = ticketSeq.incrementAndGet()
        tickets[key] = ticket
        return ticket
    }

    private fun take(key: String): Boolean {
        var taken = false
        mutable.update { s ->
            taken = key !in s.busy
            if (taken) s.copy(busy = s.busy + key) else s
        }
        return taken
    }

    private fun perform(key: String, fallback: String, block: suspend () -> Unit) {
        if (!take(key)) return
        launchTaken(key, fallback, rollback = {}, block = block)
    }

    private fun launchTaken(key: String, fallback: String, rollback: () -> Unit, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "магазин: $key не удалось: ${error.message}")
                rollback()
                when {
                    error is WalletShort -> mutable.update { it.copy(walletShort = true) }
                    else -> {
                        if (error.isSessionLost()) onSessionLost()
                        notice(textOf(error, fallback), true)
                    }
                }
            } finally {
                mutable.update { it.copy(busy = it.busy - key) }
            }
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun reveal(kicker: String, cards: List<RevealCard>, chests: List<PendingChest> = emptyList()): RewardReveal? =
        if (cards.isEmpty()) null else RewardReveal(serials.incrementAndGet(), kicker, cards, chests)

    private companion object {
        const val TAG = "millida-shop"
        const val STALE_MS = 60_000L
        const val PAY_LINK_TTL_MS = 15 * 60_000L
        const val PLUS_MANAGE_URL = "https://millida.net/profile#plus"
        const val LOAD_FALLBACK = "Не загрузилось — нажми «Повторить»"
        const val KICKER_DAILY = "Бонус за вход"
        const val KICKER_BOUGHT = "Куплено"
        const val KICKER_GIFT = "Подарок дня"
        const val KICKER_CREDITED = "Начислено"
        const val KICKER_WORKSHOP = "Мастерская"
        const val KICKER_WEEKLY = "Посылка недели"
        const val KICKER_PLAYTIME = "Награда за игру"
        const val KICKER_CHEST = "Сундук"
        const val KICKER_XRAY = "Рентген-кейс"
        const val KICKER_FRAGMENTS = "Фрагменты докуплены"
        const val KICKER_WEEKLY_PATH = "Недельный путь"
        const val KICKER_WELCOME = "Подарок"
        const val KICKER_PLUS = "Подписка"
        const val KICKER_CREATOR = "Код автора"
        const val KEY_CREATOR = "creator"
        const val CREATOR_FALLBACK = "Не получилось, попробуй позже"
        const val WELCOME_KEY = "shop-welcome-claimed"
    }
}

object RewardKind {
    const val RUBIES = "RUBIES"
    const val SHARDS = "SHARDS"
    const val CHEST = "CHEST"
    const val FRAGMENTS = "FRAGMENTS"
    const val ITEM = "ITEM"
    const val STARS = "STARS"
    const val PLUS = "PLUS"
    const val CREATOR = "CREATOR"
}

/** The newcomer card: fragments with their bar, or the whole item when the service gave it outright. */
internal fun welcomeCard(gift: WelcomeGift): RevealCard? {
    if (!gift.fresh) return null
    val item = gift.item ?: return null
    val fragments = gift.fragments
    return if (fragments != null && fragments.need > 0) {
        RevealCard(
            kind = RewardKind.FRAGMENTS,
            amount = fragments.have.toLong(),
            rarity = item.rarity,
            item = item,
            have = fragments.have,
            need = fragments.need,
            gain = fragments.have,
            topUp = fragments.topUp,
        )
    } else {
        itemCard(item)
    }
}

private fun isFresh(drop: ChestDrop): Boolean =
    (drop.kind == RewardKind.ITEM && !drop.duplicate && drop.item != null) || (drop.kind == RewardKind.FRAGMENTS && drop.completed)

/** A variant in a drop carries its colours next to the item: the card needs them on the item to repaint the preview. */
private fun dropCard(drop: ChestDrop): RevealCard {
    val variant = drop.variant
    val item = drop.item?.let { base ->
        if (variant?.from.isNullOrBlank() || variant?.color.isNullOrBlank()) {
            base
        } else {
            base.copy(color = variant?.color.orEmpty(), tintFrom = variant?.from.orEmpty())
        }
    }
    return RevealCard(
        kind = drop.kind,
        amount = drop.amount,
        rarity = drop.rarity.ifEmpty { item?.rarity.orEmpty() },
        item = item,
        have = drop.have,
        need = drop.need,
        gain = if (drop.kind == RewardKind.FRAGMENTS) drop.amount.toInt() else 0,
        completed = drop.completed,
        duplicate = drop.duplicate,
        shards = drop.shards,
        topUp = if (drop.kind == RewardKind.FRAGMENTS && item != null && !drop.completed) drop.topUp else 0,
    )
}

private fun rewardCard(reward: RubyReward): RevealCard {
    val plus = reward.source == "plus"
    if (reward.kind == RewardKind.ITEM && reward.item == null && reward.shards > 0) {
        return RevealCard(RewardKind.SHARDS, amount = reward.shards, plus = plus)
    }
    return RevealCard(
        kind = reward.kind,
        amount = reward.amount,
        tier = reward.tier,
        rarity = reward.rarity.ifEmpty { reward.item?.rarity.orEmpty() },
        item = reward.item,
        plus = plus,
        gain = if (reward.kind == RewardKind.FRAGMENTS) reward.amount.toInt() else 0,
    )
}

private fun itemCard(item: RubyItem): RevealCard = RevealCard(RewardKind.ITEM, rarity = item.rarity, item = item)

/** The workshop trades shards for fragments, never the whole item: the card shows the bar and the top-up price. */
internal fun craftCard(made: CraftResult, offer: WorkshopOffer): RevealCard {
    val item = made.item.takeIf { it.code.isNotBlank() } ?: offer.item
    val fragments = made.fragments
    if (fragments == null || fragments.need <= 0) return itemCard(item)
    val completed = fragments.have >= fragments.need
    return RevealCard(
        kind = RewardKind.FRAGMENTS,
        amount = fragments.amount.toLong(),
        rarity = item.rarity,
        item = item,
        have = fragments.have,
        need = fragments.need,
        gain = fragments.amount,
        completed = completed,
        topUp = if (completed) 0 else fragments.topUp,
    )
}

/** The playtime service names the chest in words; the art needs its tier. */
internal fun chestTierOfName(name: String?): String? {
    val word = name?.trim()?.lowercase().orEmpty()
    return when {
        word.isEmpty() -> null
        word.startsWith("легенд") -> "LEGEND"
        word.startsWith("эпич") -> "EPIC"
        word.startsWith("редк") -> "RARE"
        else -> "COMMON"
    }
}

private fun ShopState.withoutChest(id: String): ShopState = copy(
    daily = daily.copy(data = daily.data?.let { it.copy(chests = it.chests.filterNot { chest -> chest.id == id }) }),
    chests = chests.copy(data = chests.data?.let { it.copy(pending = it.pending.filterNot { chest -> chest.id == id }) }),
)

private fun ShopCard.ownedIf(code: String): ShopCard = if (item.code == code) copy(owned = true) else this

private fun ShopDay.markOwned(code: String): ShopDay = copy(
    day = day.copy(
        featured = day.featured?.ownedIf(code),
        deal = day.deal?.ownedIf(code),
        items = day.items.map { it.ownedIf(code) },
        forYou = day.forYou.map { it.ownedIf(code) },
    ),
    nightMarket = nightMarket?.let { night -> night.copy(cards = night.cards.map { it.ownedIf(code) }) },
)

private fun ShopDay.withWish(code: String, on: Boolean): ShopDay =
    copy(wishlist = if (on) wishlist.filterNot { it == code } + code else wishlist.filterNot { it == code })


private fun Throwable.isSessionLost(): Boolean =
    generateSequence(this) { current -> current.cause?.takeIf { it !== current } }
        .take(8)
        .any { it is Unauthorized || it is SignInRequired }

private fun textOf(error: Throwable, fallback: String): String = when {
    error.isSessionLost() -> "Вход Millida истёк — войди заново"
    error is RubiesError -> error.message?.takeIf { it.isNotBlank() } ?: fallback
    else -> FriendsErrors.text(error, fallback)
}
