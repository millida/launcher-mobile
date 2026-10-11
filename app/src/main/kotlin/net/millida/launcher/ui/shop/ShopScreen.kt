package net.millida.launcher.ui.shop

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.ui.character.WearRef
import net.millida.launcher.Screen
import net.millida.launcher.ShopSection
import net.millida.launcher.ShopState
import net.millida.launcher.UiState
import net.millida.launcher.core.rubies.RubiesClient
import net.millida.launcher.core.rubies.RubyItem
import net.millida.launcher.core.rubies.RubyPack
import net.millida.launcher.core.rubies.ShopCard
import net.millida.launcher.core.rubies.ShopSource
import net.millida.launcher.core.rubies.WeeklyAny
import net.millida.launcher.core.rubies.WeeklyPath
import net.millida.launcher.core.rubies.WishList
import net.millida.launcher.core.rubies.Wishes
import net.millida.launcher.core.rubies.WorkshopOffer
import net.millida.launcher.core.rubies.XrayOffer
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MDialog
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.MScreen
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.openLinkOrNotice
import net.millida.launcher.ui.screenPadding

private const val KEY_WALLET = "wallet"
private const val KEY_DAILY = "daily"
private const val KEY_PACKS = "packs"
private const val SHELF_COLUMNS = 2
private const val GRID_FULL = 8
private const val GRID_HALF = 4
private const val GRID_HALF_FIT_MAX = 5
private const val GRID_HALF_FIT_MIN = 3
private const val HERO_SIDE_SPAN = 2
private const val XRAY_SPAN = 4
private val GRID_FULL_FROM = 1080.dp
private val GRID_CELL_MIN = 120.dp

/**
 * The desktop shop's one grid: eight cells across, four once the page is no
 * wider than 1080dp, and every section takes the count that leaves its rows
 * full. A phone on its side never gets a cell narrower than a readable card.
 */
@Immutable
private class ShopGrid(private val across: Int, private val most: Int) {
    val full: Boolean get() = across >= GRID_FULL
    val columns: Int get() = minOf(across, most)

    fun fit(count: Int): Int {
        val fitted = when {
            full -> bestColumns(count, GRID_FULL, GRID_HALF)
            count <= GRID_HALF_FIT_MAX -> maxOf(1, count)
            else -> bestColumns(count, GRID_HALF, GRID_HALF_FIT_MIN)
        }
        return fitted.coerceAtMost(most)
    }

    private fun bestColumns(count: Int, widest: Int, narrowest: Int): Int {
        if (count <= narrowest) return maxOf(1, count)
        if (count <= widest) return count
        var best = widest
        var waste = Int.MAX_VALUE
        for (cells in widest downTo narrowest) {
            val empty = (count + cells - 1) / cells * cells - count
            if (empty < waste) {
                waste = empty
                best = cells
            }
        }
        return best
    }
}

@Composable
@ReadOnlyComposable
private fun shopGrid(): ShopGrid? {
    if (!LocalWideLayout.current) return null
    val room = LocalConfiguration.current.screenWidthDp.dp - screenPadding * 2
    val across = if (room > GRID_FULL_FROM) GRID_FULL else GRID_HALF
    return ShopGrid(across, maxOf(1, (room / GRID_CELL_MIN).toInt()))
}

private sealed interface ShopPrompt {
    data class Buy(val card: ShopCard, val source: ShopSource) : ShopPrompt
    data class Shortfall(val card: ShopCard, val missing: Long) : ShopPrompt
    data class Pack(val pack: RubyPack) : ShopPrompt
    data class PackShort(val pack: RubyPack, val missingKopecks: Long) : ShopPrompt
    data class Craft(val offer: WorkshopOffer) : ShopPrompt
    data class Weekly(val item: RubyItem) : ShopPrompt
    data class Xray(val offer: XrayOffer) : ShopPrompt
    data class Fragments(val item: RubyItem, val price: Long, val celebrate: Boolean) : ShopPrompt
    data class Boost(val path: WeeklyPath, val missing: Int, val price: Long) : ShopPrompt
    data class TopUp(val rubies: Long) : ShopPrompt
}

private class FeedIndex {
    var next = 0
    val at = HashMap<String, Int>()

    fun reset(start: Int) {
        next = start
        at.clear()
    }
}

private fun LazyListScope.slot(feed: FeedIndex, key: String, content: @Composable LazyItemScope.() -> Unit) {
    feed.at[key] = feed.next
    feed.next += 1
    item(key = key, contentType = key.substringBefore('-'), content = content)
}

@Composable
fun ShopScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val shop by model.shop.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val feed = remember { FeedIndex() }
    var prompt by remember { mutableStateOf<ShopPrompt?>(null) }
    var oddsOpen by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.millidaSignedIn) { model.shop.open(state.millidaSignedIn) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.shop.resume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val link = shop.link
    LaunchedEffect(link?.serial) {
        if (link != null) {
            model.openLinkOrNotice(context, link.url)
            model.shop.consumeLink(link.serial)
        }
    }

    fun scrollTo(key: String) {
        val index = feed.at[key] ?: return
        scope.launch { listState.animateScrollToItem(index) }
    }

    val onBuy: (ShopCard, ShopSource) -> Unit = { card, source ->
        val balance = shop.balance
        prompt = if (balance != null && card.price > balance) {
            ShopPrompt.Shortfall(card, card.price - balance)
        } else {
            ShopPrompt.Buy(card, source)
        }
    }
    val onPack: (RubyPack) -> Unit = { pack ->
        val wallet = shop.walletKopecks
        val short = if (wallet != null) pack.kopecks - wallet else 0L
        prompt = if (short > 0) ShopPrompt.PackShort(pack, (short + 99) / 100 * 100) else ShopPrompt.Pack(pack)
    }
    val rubyPrompt: (Long, ShopPrompt) -> Unit = { price, next ->
        val balance = shop.balance
        if (balance != null && price > balance) {
            val missing = price - balance
            model.shop.tellShort("Не хватает ${groupDigits(missing)} ${rubyWord(missing)}")
            scrollTo(KEY_PACKS)
        } else {
            prompt = next
        }
    }

    val wide = LocalWideLayout.current
    val grid = shopGrid()
    val night = rememberNightFlips(shop.store.data?.nightMarket?.endsAt.orEmpty())
    val cosmetics by model.cosmeticWardrobe.state.collectAsStateWithLifecycle()
    val wishes = remember(shop.store.data, shop.workshop.data, shop.balance, cosmetics.catalog) {
        shop.store.data?.let { day ->
            Wishes.of(day, shop.workshop.data, shop.balance ?: day.balance) { code ->
                cosmetics.catalog.byId(code)?.let { RubyItem(code = it.id, name = it.name, slot = it.slot, rarity = it.rarity, preview = it.preview) }
            }
        }
    }

    MScreen(
        modifier = modifier,
        listState = listState,
        spacing = 12.dp,
        actions = {
            if (wide && state.millidaSignedIn) {
                StripWallet(
                    balance = shop.balance,
                    shards = shop.shards,
                    onTopUp = { scrollTo(KEY_PACKS) },
                )
            }
        },
    ) {
        feed.reset(0)
        if (!state.millidaSignedIn) {
            slot(feed, "gate") {
                MEmptyState(
                    icon = MIcons.ruby,
                    title = "Войди, чтобы покупать и копить",
                    modifier = Modifier.animateItem(),
                    actionText = "Войти",
                    onAction = model::loginMillida,
                    actionIcon = MIcons.login,
                )
            }
            val guestPacks = shop.rules?.packs?.items.orEmpty()
            slot(feed, KEY_PACKS) {
                when {
                    guestPacks.isNotEmpty() -> PacksCard(
                        guestPacks,
                        null,
                        shop::isBusy,
                        onBuy = { model.loginMillida() },
                        modifier = Modifier.animateItem(),
                        columns = grid?.fit(guestPacks.size) ?: SHELF_COLUMNS,
                    )
                    shop.rulesFailed -> MErrorCard(
                        "Пакеты рубинов не загрузились — нажми «Повторить»",
                        { model.shop.open(signedIn = false) },
                        Modifier.animateItem(),
                    )
                    else -> MSkeleton(height = 220.dp, modifier = Modifier.animateItem())
                }
            }
        } else {
            shopFeed(
                feed,
                shop,
                model,
                onBuy,
                onPack,
                scrollTo = ::scrollTo,
                onOdds = { oddsOpen = true },
                onPlus = { plusOpen = true },
                onPrompt = { prompt = it },
                onRubyPrompt = rubyPrompt,
                grid = grid,
                night = night,
                wishes = wishes,
                walletInFeed = !wide,
            )
        }
    }

    ShopDialogs(prompt, shop, model, onScrollToPacks = { scrollTo(KEY_PACKS) }, onDismiss = { prompt = null })

    if (oddsOpen) {
        OddsSheet(
            rules = shop.rules,
            pity = shop.chests.data?.pityLeft,
            failed = shop.rulesFailed,
            onRetry = { model.shop.retry(ShopSection.CHESTS) },
            onDismiss = { oddsOpen = false },
        )
    }

    if (plusOpen) {
        MBottomSheet(onDismiss = { plusOpen = false }, title = "Подписка PLUS") {
            PlusOffer(
                plus = shop.plus.data,
                daily = shop.daily.data,
                busy = shop.isBusy("plus"),
                onSubscribe = model.shop::subscribePlus,
                onManage = model.shop::managePlus,
            )
        }
    }

    val opening = shop.opening
    if (opening != null) {
        val odds = shop.rules?.chests?.items?.firstOrNull { it.tier.equals(opening.chest.tier, ignoreCase = true) }
        ChestScene(
            opening = opening,
            odds = odds,
            oddsFailed = shop.rulesFailed,
            pityAfter = shop.rules?.chests?.pityAfter ?: 0,
            pityLegendAfter = shop.rules?.chests?.pityLegendAfter ?: 0,
            onStart = model.shop::startOpening,
            onNext = model.shop::nextChest,
            onClose = model.shop::closeOpening,
            onWardrobe = {
                val fresh = opening.result?.drops.orEmpty().mapNotNull { drop ->
                    val item = drop.item ?: return@mapNotNull null
                    val won = (drop.kind == "ITEM" && !drop.duplicate) || (drop.kind == "FRAGMENTS" && drop.completed)
                    if (won) WearRef(item.code, drop.variant?.name.orEmpty()) else null
                }
                model.shop.closeOpening()
                model.cosmeticWardrobe.wearIntent(fresh)
                model.open(Screen.SKINS)
            },
            onRetryOdds = { model.shop.retry(ShopSection.CHESTS) },
            topUp = ChestTopUp(
                done = shop.completedFragments,
                busy = shop::isBusy,
                onTopUp = { card -> card.item?.let { rubyPrompt(card.topUp, ShopPrompt.Fragments(it, card.topUp, celebrate = false)) } },
            ),
        )
    }

    val xray = shop.xray
    if (xray != null && opening == null) {
        XrayScene(xray, onOpened = model.shop::finishXray)
    }

    val reveal = shop.reveal
    if (reveal != null && opening == null && xray == null) {
        RewardScene(
            reveal = reveal,
            onDone = { model.shop.dismissReveal(reveal.serial) },
            onOpenChests = { chests ->
                model.shop.dismissReveal(reveal.serial)
                model.shop.openChests(chests)
            },
        )
    }
}

private fun LazyListScope.shopFeed(
    feed: FeedIndex,
    shop: ShopState,
    model: LauncherViewModel,
    onBuy: (ShopCard, ShopSource) -> Unit,
    onPack: (RubyPack) -> Unit,
    scrollTo: (String) -> Unit,
    onOdds: () -> Unit,
    onPlus: () -> Unit,
    onPrompt: (ShopPrompt) -> Unit,
    onRubyPrompt: (Long, ShopPrompt) -> Unit,
    grid: ShopGrid?,
    night: NightFlipState,
    wishes: WishList?,
    walletInFeed: Boolean,
) {
    val shopModel = model.shop
    val isBusy: (String) -> Boolean = shop::isBusy

    if (walletInFeed) {
        slot(feed, KEY_WALLET) {
            WalletHeader(
                balance = shop.balance,
                shards = shop.shards,
                failed = shop.store.error != null,
                onTopUp = { scrollTo(KEY_PACKS) },
                modifier = Modifier.animateItem(),
            )
        }
    }

    val daily = shop.daily
    slot(feed, KEY_DAILY) {
        val status = daily.data
        when {
            status != null -> DailyPassCard(
                status = status,
                plusPriceKopecks = shop.plus.data?.priceKopecks,
                claiming = shop.isBusy("daily"),
                onClaim = shopModel::claimDaily,
                onPlus = onPlus,
                onElapsed = { shopModel.retry(ShopSection.DAILY) },
                modifier = Modifier.animateItem(),
            )
            daily.error != null -> MErrorCard(daily.error, { shopModel.retry(ShopSection.DAILY) }, Modifier.animateItem())
            else -> MSkeleton(height = 300.dp, modifier = Modifier.animateItem())
        }
    }
    if (daily.data != null && daily.error != null) {
        slot(feed, "daily-error") {
            MErrorCard(daily.error, { shopModel.retry(ShopSection.DAILY) }, Modifier.animateItem())
        }
    }

    slot(feed, "chests") {
        MyChestsCard(
            chests = shop.pendingChests,
            loading = shop.chests.loading || daily.loading,
            error = shop.chests.error,
            busy = shop.opening != null,
            onRetry = { shopModel.retry(ShopSection.CHESTS) },
            onOpen = shopModel::openChests,
            onOdds = onOdds,
            onHowTo = { scrollTo(KEY_DAILY) },
            modifier = Modifier.animateItem(),
        )
    }

    storeFeed(feed, shop, model, onBuy, onXray = { offer -> onRubyPrompt(offer.price, ShopPrompt.Xray(offer)) }, grid = grid, flips = night)

    when (val weekly = shop.weekly.data) {
        is WeeklyAny.Parcel -> slot(feed, "weekly") {
            WeeklyCard(weekly.parcel, busy = shop.isBusy("weekly"), onPick = { onPrompt(ShopPrompt.Weekly(it)) }, modifier = Modifier.animateItem())
        }
        is WeeklyAny.Path -> slot(feed, "weekly-path") {
            WeeklyPathCard(
                path = weekly.path,
                claimBusy = shop.isBusy("weekly"),
                boostBusy = shop.isBusy("weekly-boost"),
                onClaim = shopModel::claimWeeklyStep,
                onBoost = { weekly.path.boost?.let { boost -> onRubyPrompt(boost.price, ShopPrompt.Boost(weekly.path, boost.missing, boost.price)) } },
                onElapsed = { shopModel.retry(ShopSection.WEEKLY) },
                modifier = Modifier.animateItem(),
            )
        }
        WeeklyAny.Unknown -> Unit
        null -> if (shop.weekly.error == null) {
            slot(feed, "weekly-skeleton") { MSkeleton(height = 140.dp, modifier = Modifier.animateItem()) }
        }
    }
    shop.weekly.error?.let { error ->
        slot(feed, "weekly-error") {
            MErrorCard(error, { shopModel.retry(ShopSection.WEEKLY) }, Modifier.animateItem())
        }
    }

    val quests = shop.quests.data.orEmpty()
    if (quests.isNotEmpty()) {
        slot(feed, "quests") {
            QuestsCard(quests, isBusy, onClaim = shopModel::claimQuest, modifier = Modifier.animateItem())
        }
    }
    shop.quests.error?.let { error ->
        slot(feed, "quests-error") {
            MErrorCard(error, { shopModel.retry(ShopSection.QUESTS) }, Modifier.animateItem())
        }
    }

    val workshop = shop.workshop.data
    if (workshop != null) {
        if (workshop.workshop.items.isNotEmpty() || workshop.fragments.isNotEmpty()) {
            slot(feed, "workshop") {
                WorkshopCard(
                    workshop,
                    isBusy,
                    onCraft = { onPrompt(ShopPrompt.Craft(it)) },
                    modifier = Modifier.animateItem(),
                    onTopUp = { started -> onRubyPrompt(started.topUp, ShopPrompt.Fragments(started.item, started.topUp, celebrate = true)) },
                    columns = grid?.fit(workshop.workshop.items.size) ?: SHELF_COLUMNS,
                )
            }
        }
    } else if (shop.workshop.error == null) {
        slot(feed, "workshop-skeleton") { MSkeleton(height = 220.dp, modifier = Modifier.animateItem()) }
    }
    shop.workshop.error?.let { error ->
        slot(feed, "workshop-error") {
            MErrorCard(error, { shopModel.retry(ShopSection.WORKSHOP) }, Modifier.animateItem())
        }
    }

    val progress = shop.progress.data
    if (progress != null) {
        if (progress.items.isNotEmpty() || progress.hourItems.isNotEmpty()) {
            slot(feed, "progress") {
                ProgressCard(progress, isBusy, onClaim = shopModel::claimProgress, modifier = Modifier.animateItem())
            }
        }
    } else if (shop.progress.error == null) {
        slot(feed, "progress-skeleton") { MSkeleton(height = 180.dp, modifier = Modifier.animateItem()) }
    }
    shop.progress.error?.let { error ->
        slot(feed, "progress-error") {
            MErrorCard(error, { shopModel.retry(ShopSection.PROGRESS) }, Modifier.animateItem())
        }
    }

    val packs = packsOf(shop)
    val packsFailed = !shop.store.loading && (shop.store.error != null || shop.rulesFailed)
    slot(feed, KEY_PACKS) {
        when {
            packs.isNotEmpty() -> PacksCard(
                packs,
                shop.walletKopecks,
                isBusy,
                onBuy = onPack,
                modifier = Modifier.animateItem(),
                columns = grid?.fit(packs.size) ?: SHELF_COLUMNS,
            )
            packsFailed -> MErrorCard(
                "Пакеты рубинов не загрузились — нажми «Повторить»",
                { shopModel.retry(ShopSection.STORE) },
                Modifier.animateItem(),
            )
            else -> MSkeleton(height = 220.dp, modifier = Modifier.animateItem())
        }
    }

    slot(feed, "plus") {
        val plus = shop.plus
        when {
            plus.data != null -> PlusCard(
                plus = plus.data,
                daily = shop.daily.data,
                busy = shop.isBusy("plus"),
                onSubscribe = shopModel::subscribePlus,
                onManage = shopModel::managePlus,
                modifier = Modifier.animateItem(),
            )
            plus.error != null -> MErrorCard(plus.error, { shopModel.retry(ShopSection.PLUS) }, Modifier.animateItem())
            else -> MSkeleton(height = 200.dp, modifier = Modifier.animateItem())
        }
    }

    if (wishes != null && wishes.items.isNotEmpty()) {
        val wishlist = shop.store.data?.wishlist.orEmpty()
        val wishColumns = grid?.fit(wishes.items.size) ?: SHELF_COLUMNS
        slot(feed, "wish-head") {
            WishlistHead(
                list = wishes,
                busy = shop.isBusy("topup"),
                onTopUp = { rubies -> onPrompt(ShopPrompt.TopUp(rubies)) },
                modifier = Modifier.animateItem(),
            )
        }
        wishes.items.chunked(wishColumns).forEachIndexed { index, row ->
            slot(feed, "wish-row-$index") {
                WishRow(
                    entries = row,
                    wishlist = wishlist,
                    balance = shop.balance,
                    shards = shop.shards,
                    isBusy = isBusy,
                    onBuy = onBuy,
                    onCraft = { entry -> onPrompt(ShopPrompt.Craft(WorkshopOffer(entry.item, entry.cost))) },
                    onWish = shopModel::wish,
                    modifier = Modifier.animateItem(),
                    columns = wishColumns,
                )
            }
        }
    }

    slot(feed, "creator") {
        val creator = shop.creator
        when {
            creator.data != null -> CreatorCodeCard(
                status = creator.data,
                busy = shop.isBusy("creator"),
                onApply = shopModel::setCreator,
                onRemove = shopModel::clearCreator,
                onBecome = shopModel::becomeCreator,
                modifier = Modifier.animateItem(),
            )
            creator.error != null -> MErrorCard(creator.error, { shopModel.retry(ShopSection.CREATOR) }, Modifier.animateItem())
            else -> MSkeleton(height = 180.dp, modifier = Modifier.animateItem())
        }
    }
}

/** The pack list comes with the storefront; the bonus over the base rate comes with the rules. */
private fun packsOf(shop: ShopState): List<RubyPack> {
    val fromRules = shop.rules?.packs?.items.orEmpty()
    val fromStore = shop.store.data?.packs.orEmpty()
    if (fromStore.isEmpty()) return fromRules
    val bonus = fromRules.associate { it.code to it.bonus }
    return fromStore.map { pack -> if (pack.bonus > 0) pack else pack.copy(bonus = bonus[pack.code] ?: 0) }
}

private fun LazyListScope.storeFeed(
    feed: FeedIndex,
    shop: ShopState,
    model: LauncherViewModel,
    onBuy: (ShopCard, ShopSource) -> Unit,
    onXray: (XrayOffer) -> Unit,
    grid: ShopGrid?,
    flips: NightFlipState,
) {
    val columns = SHELF_COLUMNS
    val shopModel = model.shop
    val store = shop.store
    val day = store.data
    val isBusy: (String) -> Boolean = shop::isBusy
    val refresh = { shopModel.retry(ShopSection.STORE) }
    if (day == null) {
        slot(feed, "store-head") {
            ShelfHeader("Витрина дня", null, null, onElapsed = {}, modifier = Modifier.animateItem())
        }
        slot(feed, "store-body") {
            if (store.error != null) {
                MErrorCard(store.error, refresh, Modifier.animateItem())
            } else {
                StoreSkeleton(Modifier.animateItem())
            }
        }
        return
    }
    val plan = day.day
    val wishlist = day.wishlist
    val onWish: (String, Boolean) -> Unit = shopModel::wish
    slot(feed, "store-head") {
        ShelfHeader("Витрина дня", "Новая через", plan.refreshAt, onElapsed = refresh, modifier = Modifier.animateItem())
    }
    if (store.error != null) {
        slot(feed, "store-error") { MErrorCard(store.error, refresh, Modifier.animateItem()) }
    }
    val featured = plan.featured
    val gift = day.gift
    val deal = plan.deal
    val balance = shop.balance
    val giftTile: @Composable (Modifier) -> Unit = { tileModifier ->
        if (gift != null) {
            GiftTile(
                gift = gift,
                busy = shop.isBusy("gift"),
                onClaim = shopModel::claimGift,
                onElapsed = refresh,
                modifier = tileModifier,
            )
        }
    }
    val items = plan.items.sortedByDescending { net.millida.launcher.ui.Rarity.of(it.item.rarity)?.ordinal ?: -1 }
    if (grid != null) {
        wideShowcase(
            feed = feed,
            grid = grid,
            featured = featured,
            deal = deal,
            items = items,
            hasGift = gift != null,
            giftTile = giftTile,
            wishlist = wishlist,
            balance = balance,
            isBusy = isBusy,
            onBuy = onBuy,
            onWish = onWish,
            onTry = { card ->
                model.cosmeticWardrobe.wearIntent(listOf(WearRef(card.item.code, card.item.variant)))
                model.open(Screen.SKINS)
            },
        )
    } else {
        if (gift != null && (featured != null || deal == null)) {
            slot(feed, "store-gift") {
                giftTile(Modifier.fillMaxWidth().animateItem())
            }
        }
        if (featured != null) {
            slot(feed, "store-featured") {
                FeaturedCard(
                    card = featured,
                    wished = featured.item.code in wishlist,
                    busy = shop.isBusy("buy:" + featured.item.code),
                    balance = balance,
                    onBuy = { onBuy(featured, ShopSource.FEATURED) },
                    onTry = {
                        model.cosmeticWardrobe.wearIntent(listOf(WearRef(featured.item.code, featured.item.variant)))
                        model.open(Screen.SKINS)
                    },
                    onWish = { on -> onWish(featured.item.code, on) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (gift != null && featured == null && deal != null) {
            slot(feed, "store-deal") {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).animateItem(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    giftTile(Modifier.weight(1f).fillMaxHeight())
                    ItemTile(
                        card = deal,
                        wished = deal.item.code in wishlist,
                        busy = shop.isBusy("buy:" + deal.item.code),
                        balance = balance,
                        onBuy = { onBuy(deal, ShopSource.DEAL) },
                        onWish = { on -> onWish(deal.item.code, on) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
        val dealLeads = deal != null && !(gift != null && featured == null)
        val shelf = (if (dealLeads && deal != null) listOf(deal to ShopSource.DEAL) else emptyList()) + items.map { it to ShopSource.DAY }
        shelf.chunked(columns).forEachIndexed { index, row ->
            slot(feed, "store-day-$index") {
                ShelfRow(row, balance, wishlist, isBusy, onBuy, onWish, Modifier.animateItem(), columns)
            }
        }
    }
    if (featured == null && deal == null && items.isEmpty() && gift == null) {
        slot(feed, "store-empty") {
            MEmptyState(
                icon = MIcons.shop,
                title = "Витрина обновляется",
                modifier = Modifier.animateItem(),
                actionText = "Обновить",
                onAction = refresh,
                actionIcon = MIcons.refresh,
                primary = false,
            )
        }
    }
    val xray = day.xray?.takeIf { it.id.isNotBlank() }
    if (xray != null || plan.forYou.isNotEmpty()) {
        slot(feed, "foryou-head") {
            ShelfHeader("Для тебя", "Новые через", plan.refreshAt, onElapsed = {}, modifier = Modifier.animateItem())
        }
        val xrayTile: @Composable (Modifier) -> Unit = { tileModifier ->
            if (xray != null) {
                XrayCard(
                    offer = xray,
                    balance = shop.balance,
                    busy = shop.isBusy("xray:" + xray.id) || shop.xray != null,
                    onBuy = { onXray(xray) },
                    onElapsed = refresh,
                    modifier = tileModifier,
                )
            }
        }
        val forYou = plan.forYou.sortedByDescending { net.millida.launcher.ui.Rarity.of(it.item.rarity)?.ordinal ?: -1 }
        if (grid != null) {
            wideForYou(feed, grid, xray != null, xrayTile, forYou, wishlist, balance, isBusy, onBuy, onWish)
        } else {
            if (xray != null) {
                slot(feed, "foryou-xray") {
                    xrayTile(Modifier.animateItem())
                }
            }
            forYou.map { it to ShopSource.FOR_YOU }.chunked(columns).forEachIndexed { index, row ->
                slot(feed, "foryou-row-$index") {
                    ShelfRow(row, balance, wishlist, isBusy, onBuy, onWish, Modifier.animateItem(), columns)
                }
            }
        }
    }
    val night = day.nightMarket
    if (night != null && night.cards.isNotEmpty() && grid != null) {
        val head: @Composable (Modifier) -> Unit = { tileModifier ->
            NightMarketHead(
                endsAt = night.endsAt,
                closed = night.cards.count { it.item.code !in flips.codes },
                onOpenAll = { flips.open(night.cards.map { it.item.code }) },
                onElapsed = refresh,
                modifier = tileModifier,
                stackedClock = true,
            )
        }
        wideNight(feed, grid, night.cards, flips, head, balance, isBusy, onBuy)
    } else if (night != null && night.cards.isNotEmpty()) {
        slot(feed, "night-head") {
            NightMarketHead(
                endsAt = night.endsAt,
                closed = night.cards.count { it.item.code !in flips.codes },
                onOpenAll = { flips.open(night.cards.map { it.item.code }) },
                onElapsed = refresh,
                modifier = Modifier.animateItem(),
            )
        }
        night.cards.chunked(columns).forEachIndexed { index, row ->
            slot(feed, "night-row-$index") {
                NightRow(
                    cards = row,
                    flipped = flips.codes,
                    balance = balance,
                    isBusy = isBusy,
                    onFlip = { card -> flips.open(listOf(card.item.code)) },
                    onBuy = { card -> onBuy(card, ShopSource.NIGHT) },
                    modifier = Modifier.animateItem(),
                    columns = columns,
                )
            }
        }
    }
}

/**
 * The desktop showcase: on a full row the free gift, the item of the day and
 * the deal sit side by side as 2 + 4 + 2 cells; on a half row the item of the
 * day takes a row of its own and the gift and the deal share the next one.
 */
private fun LazyListScope.wideShowcase(
    feed: FeedIndex,
    grid: ShopGrid,
    featured: ShopCard?,
    deal: ShopCard?,
    items: List<ShopCard>,
    hasGift: Boolean,
    giftTile: @Composable (Modifier) -> Unit,
    wishlist: List<String>,
    balance: Long?,
    isBusy: (String) -> Boolean,
    onBuy: (ShopCard, ShopSource) -> Unit,
    onWish: (String, Boolean) -> Unit,
    onTry: (ShopCard) -> Unit,
) {
    val featuredTile: @Composable (Modifier) -> Unit = { tileModifier ->
        if (featured != null) {
            FeaturedCard(
                card = featured,
                wished = featured.item.code in wishlist,
                busy = isBusy("buy:" + featured.item.code),
                balance = balance,
                onBuy = { onBuy(featured, ShopSource.FEATURED) },
                onTry = { onTry(featured) },
                onWish = { on -> onWish(featured.item.code, on) },
                modifier = tileModifier,
            )
        }
    }
    val dealTile: @Composable (Modifier) -> Unit = { tileModifier ->
        if (deal != null) {
            ItemTile(
                card = deal,
                wished = deal.item.code in wishlist,
                busy = isBusy("buy:" + deal.item.code),
                balance = balance,
                onBuy = { onBuy(deal, ShopSource.DEAL) },
                onWish = { on -> onWish(deal.item.code, on) },
                modifier = tileModifier,
            )
        }
    }
    if (grid.full) {
        val giftSpan = if (hasGift) HERO_SIDE_SPAN else 0
        val dealSpan = if (deal != null) HERO_SIDE_SPAN else 0
        val featuredSpan = if (featured != null) GRID_FULL - giftSpan - dealSpan else 0
        val restSpan = GRID_FULL - giftSpan - dealSpan - featuredSpan
        if (giftSpan + dealSpan + featuredSpan > 0) {
            slot(feed, "store-hero") {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).animateItem(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (giftSpan > 0) giftTile(Modifier.weight(giftSpan.toFloat()).fillMaxHeight())
                    if (featuredSpan > 0) featuredTile(Modifier.weight(featuredSpan.toFloat()).fillMaxHeight())
                    if (dealSpan > 0) dealTile(Modifier.weight(dealSpan.toFloat()).fillMaxHeight())
                    if (restSpan > 0) Spacer(Modifier.weight(restSpan.toFloat()))
                }
            }
        }
    } else {
        if (featured != null) {
            slot(feed, "store-featured") {
                featuredTile(Modifier.animateItem())
            }
        }
        if (hasGift || deal != null) {
            slot(feed, "store-deal") {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).animateItem(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (hasGift) giftTile(Modifier.weight(1f).fillMaxHeight())
                    if (deal != null) dealTile(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
    val across = grid.fit(items.size)
    items.map { it to ShopSource.DAY }.chunked(across).forEachIndexed { index, row ->
        slot(feed, "store-day-$index") {
            ShelfRow(row, balance, wishlist, isBusy, onBuy, onWish, Modifier.animateItem(), across)
        }
    }
}

/**
 * «Для тебя» as on the desktop: on a full row the X-ray case is half the row
 * with the first four cards beside it; on a half row it takes a row of its own.
 */
private fun LazyListScope.wideForYou(
    feed: FeedIndex,
    grid: ShopGrid,
    hasXray: Boolean,
    xrayTile: @Composable (Modifier) -> Unit,
    forYou: List<ShopCard>,
    wishlist: List<String>,
    balance: Long?,
    isBusy: (String) -> Boolean,
    onBuy: (ShopCard, ShopSource) -> Unit,
    onWish: (String, Boolean) -> Unit,
) {
    val cards = forYou.map { it to ShopSource.FOR_YOU }
    val across = if (hasXray) grid.columns else grid.fit(cards.size)
    val beside = if (hasXray && grid.full) cards.take(GRID_FULL - XRAY_SPAN) else emptyList()
    if (hasXray) {
        slot(feed, "foryou-xray") {
            if (grid.full) {
                Row(Modifier.fillMaxWidth().animateItem(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    xrayTile(Modifier.weight(XRAY_SPAN.toFloat()))
                    for ((card, source) in beside) {
                        ItemTile(
                            card = card,
                            wished = card.item.code in wishlist,
                            busy = isBusy("buy:" + card.item.code),
                            balance = balance,
                            onBuy = { onBuy(card, source) },
                            onWish = { on -> onWish(card.item.code, on) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(GRID_FULL - XRAY_SPAN - beside.size) { Spacer(Modifier.weight(1f)) }
                }
            } else {
                xrayTile(Modifier.animateItem())
            }
        }
    }
    cards.drop(beside.size).chunked(across).forEachIndexed { index, row ->
        slot(feed, "foryou-row-$index") {
            ShelfRow(row, balance, wishlist, isBusy, onBuy, onWish, Modifier.animateItem(), across)
        }
    }
}

/**
 * The night market as one grid, the desktop's night block: the head is a lead
 * cell that fills what the first row of cards leaves free.
 */
private fun LazyListScope.wideNight(
    feed: FeedIndex,
    grid: ShopGrid,
    cards: List<ShopCard>,
    flips: NightFlipState,
    head: @Composable (Modifier) -> Unit,
    balance: Long?,
    isBusy: (String) -> Boolean,
    onBuy: (ShopCard, ShopSource) -> Unit,
) {
    val across = grid.columns
    val leadSpan = if (cards.size % across == 0) across else across - cards.size % across
    val first = cards.take(across - leadSpan)
    slot(feed, "night-head") {
        NightRow(
            cards = first,
            flipped = flips.codes,
            balance = balance,
            isBusy = isBusy,
            onFlip = { card -> flips.open(listOf(card.item.code)) },
            onBuy = { card -> onBuy(card, ShopSource.NIGHT) },
            modifier = Modifier.animateItem(),
            columns = across,
            lead = head,
            leadSpan = leadSpan,
        )
    }
    cards.drop(first.size).chunked(across).forEachIndexed { index, row ->
        slot(feed, "night-row-$index") {
            NightRow(
                cards = row,
                flipped = flips.codes,
                balance = balance,
                isBusy = isBusy,
                onFlip = { card -> flips.open(listOf(card.item.code)) },
                onBuy = { card -> onBuy(card, ShopSource.NIGHT) },
                modifier = Modifier.animateItem(),
                columns = across,
            )
        }
    }
}

/** Not enough rubies, wherever the price was: top up exactly the missing ones or go to the packs. */
@Composable
internal fun RubyShortfallDialog(
    subject: String,
    missing: Long,
    onTopUp: (Int) -> Unit,
    onPacks: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = millida
    val rubies = maxOf(missing, RubiesClient.EXACT_MIN_RUBIES.toLong())
    MDialog(
        title = "Не хватает рубинов",
        onDismiss = onDismiss,
        body = {
            Text(
                "На $subject не хватает ${groupDigits(missing)} ${rubyWord(missing)}.",
                style = MillidaType.body,
                color = colors.fgMuted,
            )
            Text(
                "Докупить ровно ${groupDigits(rubies)} за ${rubles(kopecksForRubies(rubies))} со счёта Millida.",
                style = MillidaType.small,
                color = colors.fgSubtle,
            )
        },
        actions = {
            MTextButton("Отмена", onClick = onDismiss)
            MSecondaryButton("Пакеты", onClick = {
                onDismiss()
                onPacks()
            })
            MPrimaryButton("Докупить", onClick = {
                onTopUp(rubies.toInt())
                onDismiss()
            })
        },
    )
}

@Composable
private fun ShopDialogs(
    prompt: ShopPrompt?,
    shop: ShopState,
    model: LauncherViewModel,
    onScrollToPacks: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (prompt) {
        null -> Unit
        is ShopPrompt.Buy -> MConfirmDialog(
            title = "Купить вещь",
            text = "«${prompt.card.item.name}» за ${groupDigits(prompt.card.price)} ${rubyWord(prompt.card.price)}",
            confirm = "Купить",
            onConfirm = {
                model.shop.buy(prompt.card, prompt.source)
                onDismiss()
            },
            onDismiss = onDismiss,
            danger = false,
        )
        is ShopPrompt.Shortfall -> RubyShortfallDialog(
            subject = "«${prompt.card.item.name}»",
            missing = prompt.missing,
            onTopUp = model.shop::topUp,
            onPacks = onScrollToPacks,
            onDismiss = onDismiss,
        )
        is ShopPrompt.Pack -> MConfirmDialog(
            title = "Оплатить «${prompt.pack.title}»",
            text = "${groupDigits(prompt.pack.rubies)} ${rubyWord(prompt.pack.rubies)} за ${rubles(prompt.pack.kopecks)} со счёта Millida",
            confirm = "Оплатить",
            onConfirm = {
                model.shop.buyPack(prompt.pack)
                onDismiss()
            },
            onDismiss = onDismiss,
            danger = false,
        )
        is ShopPrompt.PackShort -> WalletShortDialog(
            text = "На счёте Millida не хватает ${rubles(prompt.missingKopecks)}. Пополни на сайте и вернись за пакетом.",
            onTopUp = {
                model.shop.openWalletTopUp()
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        is ShopPrompt.Craft -> MConfirmDialog(
            title = "Обменять осколки",
            text = "${groupDigits(prompt.offer.cost)} ${shardWord(prompt.offer.cost)} → половина фрагментов: ${prompt.offer.item.name}. Остаток докупается за рубины",
            confirm = "Обменять",
            onConfirm = {
                model.shop.craft(prompt.offer)
                onDismiss()
            },
            onDismiss = onDismiss,
            danger = false,
        )
        is ShopPrompt.Xray -> MConfirmDialog(
            title = "Открыть кейс",
            text = "«${prompt.offer.item.name}» за ${groupDigits(prompt.offer.price)} ${rubyWord(prompt.offer.price)}",
            confirm = "Купить и открыть",
            onConfirm = {
                model.shop.buyXray(prompt.offer)
                onDismiss()
            },
            onDismiss = onDismiss,
            danger = false,
        )
        is ShopPrompt.Fragments -> MConfirmDialog(
            title = "Докупить фрагменты",
            text = "${prompt.item.name}: докупить недостающие фрагменты за ${groupDigits(prompt.price)} ${rubyWord(prompt.price)}",
            confirm = "Докупить",
            onConfirm = {
                model.shop.completeFragments(prompt.item, prompt.celebrate)
                onDismiss()
            },
            onDismiss = onDismiss,
            danger = false,
        )
        is ShopPrompt.Boost -> MConfirmDialog(
            title = "Докупить звёзды",
            text = "${prompt.missing} зв. за ${groupDigits(prompt.price)} ${rubyWord(prompt.price)}",
            confirm = "Докупить",
            onConfirm = {
                model.shop.boostWeekly(prompt.path)
                onDismiss()
            },
            onDismiss = onDismiss,
            danger = false,
        )
        is ShopPrompt.TopUp -> MConfirmDialog(
            title = "Докупить недостающее",
            text = "${groupDigits(prompt.rubies)} ${rubyWord(prompt.rubies)} за ${rubles(kopecksForRubies(prompt.rubies))}",
            confirm = "Оплатить",
            onConfirm = {
                model.shop.topUp(prompt.rubies.toInt())
                onDismiss()
            },
            onDismiss = onDismiss,
            danger = false,
        )
        is ShopPrompt.Weekly -> MConfirmDialog(
            title = "Забрать посылку",
            text = "«${prompt.item.name}» — навсегда. Две другие вещи уйдут.",
            confirm = "Забрать",
            onConfirm = {
                model.shop.claimWeekly(prompt.item)
                onDismiss()
            },
            onDismiss = onDismiss,
            danger = false,
        )
    }
    if (shop.walletShort) {
        WalletShortDialog(
            text = "На счёте Millida не хватает денег. Пополни на сайте и вернись — покупка подождёт.",
            onTopUp = model.shop::openWalletTopUp,
            onDismiss = model.shop::dismissWalletShort,
        )
    }
}

@Composable
private fun WalletShortDialog(text: String, onTopUp: () -> Unit, onDismiss: () -> Unit) {
    MDialog(
        title = "Пополни счёт",
        onDismiss = onDismiss,
        body = { Text(text, style = MillidaType.body, color = millida.fgMuted) },
        actions = {
            MTextButton("Отмена", onClick = onDismiss)
            MPrimaryButton("Пополнить", onClick = onTopUp, icon = MIcons.externalLink)
        },
    )
}
