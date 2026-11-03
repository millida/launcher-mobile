package net.millida.launcher.ui.shop

import androidx.compose.foundation.layout.Box
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.ui.WIDE_ROOMY_HEIGHT_DP
import net.millida.launcher.ui.WideTopBar
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import net.millida.launcher.core.i18n.trPlural
import net.millida.launcher.ui.MButtonSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import net.millida.launcher.ui.shop.chest.ChestLive
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.MillidaIcon
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.MillidaCut
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.ShopSection
import net.millida.launcher.ShopState
import net.millida.launcher.UiState
import net.millida.launcher.DailyTarget
import net.millida.launcher.core.rubies.CaseMode
import net.millida.launcher.core.rubies.RubiesClient
import net.millida.launcher.core.rubies.QuestState
import net.millida.launcher.core.rubies.RubyItem
import net.millida.launcher.core.rubies.RubyPack
import net.millida.launcher.core.rubies.SetColorway
import net.millida.launcher.core.rubies.ShopCard
import net.millida.launcher.core.rubies.ShopCase
import net.millida.launcher.core.rubies.ShopSet
import net.millida.launcher.core.rubies.ShopSource
import net.millida.launcher.core.rubies.WeeklyAny
import net.millida.launcher.core.rubies.WorkshopOffer
import net.millida.launcher.core.rubies.questState
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.effectiveScreenHeightDp
import net.millida.launcher.ui.effectiveScreenWidthDp
import net.millida.launcher.ui.MBalanceBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MDialog
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPlusChip
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MScreen
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSecHead
import net.millida.launcher.ui.MShardBalance
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MTextLink
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PlusTier
import net.millida.launcher.ui.character.WearRef
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.openLinkOrNotice
import net.millida.launcher.ui.screenPadding
import net.millida.launcher.ui.character.CharacterLook
import net.millida.launcher.ui.character.characterLookOf
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val KEY_RUBIES = "rubies-head"
private const val KEY_PASS = "pass-head"
private const val SHARD_ITEMS = 6
private val PACK_CELL = 150.dp
private val CASE_CELL = 170.dp
private val SHARD_CELL = 130.dp
private val UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM").withZone(ZoneId.of("Europe/Moscow"))

private sealed interface ShopPrompt {
    data class Buy(val card: ShopCard, val source: ShopSource) : ShopPrompt
    data class Pack(val pack: RubyPack) : ShopPrompt
    data class PackShort(val pack: RubyPack, val missingKopecks: Long) : ShopPrompt
    data class Craft(val offer: WorkshopOffer) : ShopPrompt
    data class Fragments(val item: RubyItem, val price: Long) : ShopPrompt
    data class SetBuy(val set: ShopSet, val way: SetColorway) : ShopPrompt
    data class CaseOpen(val case: ShopCase, val mode: CaseMode) : ShopPrompt
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

/** How many cells of at least [cell] fit across the page: never fewer than two, never more than [most]. */
@Composable
private fun columnsOf(cell: Dp, most: Int): Int {
    val room = effectiveScreenWidthDp().dp - screenPadding * 2
    return (room / cell).toInt().coerceIn(2, most)
}

internal fun untilText(iso: String?): String? = parseInstant(iso)?.let { UNTIL.format(it) }

/** The cell list comes with the storefront; the bonus over the base rate comes with the rules. */
internal fun packsOf(shop: ShopState): List<RubyPack>? {
    val fromStore = shop.store.data?.packs.orEmpty()
    if (fromStore.isNotEmpty()) return fromStore
    return shop.rules?.packs?.items?.takeIf { it.isNotEmpty() }
}

/**
 * The shop as the desktop's one feed (`ShopFeed`): «Пополнить рубины» with the
 * PLUS bundle, «Пропуск», the month offer, «Скидки дня» with the night market
 * and «Хочу», «Бонусы», Telegram and Discord, «Наборы», «Ящики», «За осколки»
 * and the creator banners. Rubies, shards, «+» and the PLUS chip sit in the head.
 */
@Composable
fun ShopScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    ShopPage(state, model, modifier, embedded = false)
}

/**
 * The feed alone, for the wardrobe's «Магазин» tab (the desktop's `ShopFeed chip`):
 * the wardrobe has no shop head, so the PLUS chip and the rubies move into the
 * «Пополнить рубины» head instead.
 */
@Composable
fun ShopFeed(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    ShopPage(state, model, modifier, embedded = true)
}

@Composable
private fun ShopPage(state: UiState, model: LauncherViewModel, modifier: Modifier, embedded: Boolean) {
    val shop by model.shop.state.collectAsStateWithLifecycle()
    val licence by model.licence.state.collectAsStateWithLifecycle()
    val look = characterLookOf(state, licence)
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val feed = remember { FeedIndex() }
    var prompt by remember { mutableStateOf<ShopPrompt?>(null) }
    var oddsOpen by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }
    var setOpen by remember { mutableStateOf<String?>(null) }
    var caseLook by remember { mutableStateOf<ShopCase?>(null) }
    val setPicks = remember { mutableStateMapOf<String, String>() }

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

    var stageTab by rememberSaveable { mutableStateOf("RUBIES") }
    val staged = LocalWideLayout.current && !embedded && state.millidaSignedIn && shop.store.data != null &&
        LocalConfiguration.current.screenHeightDp < WIDE_ROOMY_HEIGHT_DP

    fun scrollTo(key: String) {
        if (staged) {
            stageTab = if (key == KEY_PASS) "PASS" else "RUBIES"
            return
        }
        val index = feed.at[key] ?: return
        scope.launch { listState.animateScrollToItem(index) }
    }

    val toPacks = { scrollTo(KEY_RUBIES) }
    val short: (Long) -> Unit = { missing ->
        val count = groupDigits(missing)
        model.shop.tellShort(trPlural(missing, tr("Не хватает {0} рубина", count), tr("Не хватает {0} рубинов", count), tr("Не хватает {0} рубинов", count)))
        toPacks()
    }
    val rubyPrompt: (Long, ShopPrompt) -> Unit = { price, next ->
        val balance = shop.balance
        if (balance != null && price > balance) {
            short(price - balance)
        } else {
            prompt = next
        }
    }
    val wear: (List<WearRef>) -> Unit = { refs ->
        model.cosmeticWardrobe.wearIntent(refs)
        model.open(Screen.SKINS)
    }
    val onPlus: () -> Unit = { if (shop.tier != null) model.open(Screen.PLUS) else plusOpen = true }
    val acts = ShopActs(
        sale = SaleActs(
            balance = shop.balance,
            isBusy = shop::isBusy,
            onBuy = { card, source -> rubyPrompt(card.price, ShopPrompt.Buy(card, source)) },
            onWear = { item -> wear(listOf(WearRef(item.code, item.variant))) },
        ),
        sets = SetActs(
            balance = shop.balance,
            isBusy = shop::isBusy,
            onBuy = { set, way -> rubyPrompt(way.price, ShopPrompt.SetBuy(set, way)) },
            onWear = { way -> wear(way.items.map { WearRef(it.item.code, it.item.variant) }) },
        ),
        cases = CaseActs(
            balance = shop.balance,
            shards = shop.shards,
            busy = shop.caseRun != null,
            ofDayPct = shop.cases.data?.ofDayPct ?: 0,
            refreshAt = shop.cases.data?.refreshAt.orEmpty(),
            onOpen = { case, mode ->
                val price = case.priceOf(mode)
                when {
                    mode == CaseMode.FREE -> model.shop.openCase(case, mode)
                    case.paidInShards && (shop.shards ?: Long.MAX_VALUE) < price -> model.shop.tellShort(tr("Не хватает осколков"))
                    case.paidInShards -> prompt = ShopPrompt.CaseOpen(case, mode)
                    else -> rubyPrompt(price, ShopPrompt.CaseOpen(case, mode))
                }
            },
            onLook = { caseLook = it },
            onEnd = { model.shop.retry(ShopSection.CASES) },
        ),
        onPack = { pack ->
            val wallet = shop.walletKopecks
            val missing = if (wallet != null) pack.kopecks - wallet else 0L
            prompt = if (missing > 0) ShopPrompt.PackShort(pack, (missing + 99) / 100 * 100) else ShopPrompt.Pack(pack)
        },
        onPlusSheet = { plusOpen = true },
        onManage = { model.open(Screen.PLUS) },
        onCraft = { offer -> prompt = ShopPrompt.Craft(offer) },
        onSet = { set -> setOpen = set.id },
        onScroll = ::scrollTo,
        onOdds = { oddsOpen = true },
        picks = setPicks,
    )

    val wide = LocalWideLayout.current
    val until = untilText(shop.plus.data?.paidUntil)
    val rubiesAside: @Composable RowScope.() -> Unit = {
        if (embedded) {
            MPlusChip(shop.tier, onClick = onPlus, until = until)
            MBalanceBadge(MIcons.ruby, shop.balance, tr("Рубины"))
        }
    }
    val wardrobe = model.cosmeticWardrobe
    LaunchedEffect(state.account?.id, state.millidaSignedIn, state.gameProfile?.uuid) {
        val current = wardrobe.state.value
        val accountId = state.account?.id.orEmpty()
        if (current.accountId != accountId || current.signedIn != state.millidaSignedIn || !current.catalogReady) {
            wardrobe.load(accountId, state.millidaSignedIn, state.gameProfile?.uuid)
        }
    }
    val figure = remember(look.skinUrl, look.slim, wardrobe) { ShopFigure(look.skinUrl, look.slim, wardrobe) }
    CompositionLocalProvider(LocalShopFigure provides figure) {
        if (staged) {
            Column(modifier.fillMaxSize().background(millida.bg)) {
                WideTopBar(
                    title = null,
                    onBack = null,
                    actions = { ShopWallet(shop, until, onTopUp = toPacks, onPlus = onPlus) },
                )
                ShopStage(shop, model, acts, look.nick.ifBlank { "Steve" }, stageTab, { stageTab = it }, Modifier.weight(1f))
            }
        } else MScreen(
            modifier = modifier,
            listState = listState,
            spacing = 12.dp,
            actions = {
                if (wide && state.millidaSignedIn && !embedded) {
                    ShopWallet(shop, until, onTopUp = toPacks, onPlus = onPlus)
                }
            },
        ) {
            feed.reset(0)
            if (!state.millidaSignedIn) {
                slot(feed, "gate") {
                    MEmptyState(
                        icon = MIcons.ruby,
                        title = tr("Войди, чтобы покупать и копить"),
                        modifier = Modifier.animateItem(),
                        actionText = tr("Войти"),
                        onAction = model::loginMillida,
                        actionIcon = MIcons.login,
                    )
                }
                slot(feed, KEY_RUBIES) {
                    val packs = shop.rules?.packs?.items
                    if (packs == null && shop.rulesFailed) {
                        MErrorCard(tr("Пакеты рубинов не загрузились — нажми «Повторить»"), { model.shop.open(signedIn = false) }, Modifier.animateItem())
                    } else {
                        PackGrid(packs, columnsOf(PACK_CELL, 6), { false }, onBuy = { model.loginMillida() }, modifier = Modifier.animateItem())
                    }
                }
            } else {
                if (!wide && !embedded) {
                    slot(feed, "wallet") {
                        ShopWallet(shop, until, onTopUp = toPacks, onPlus = onPlus, modifier = Modifier.animateItem())
                    }
                }
                shopFeed(feed, shop, model, acts, wide, rubiesAside, look.nick.ifBlank { "Steve" })
            }
        }
    }

    ShopDialogs(prompt, shop, model, onDismiss = { prompt = null })

    if (plusOpen) {
        PlusSheet(shop, model, look, onDismiss = { plusOpen = false })
    }

    val openSet = shop.sets.data?.sets?.firstOrNull { it.id == setOpen }
    if (openSet != null) {
        SetSheet(
            set = openSet,
            picked = setPicks[openSet.id],
            acts = acts.sets,
            onPick = { setPicks[openSet.id] = it },
            onDismiss = { setOpen = null },
        )
    }

    caseLook?.let { case ->
        CaseContentsSheet(case, load = model.shop::caseContents, onDismiss = { caseLook = null })
    }

    if (oddsOpen) {
        OddsSheet(
            rules = shop.rules,
            pity = shop.chests.data?.pityLeft,
            failed = shop.rulesFailed,
            onRetry = { model.shop.retry(ShopSection.CHESTS) },
            onDismiss = { oddsOpen = false },
        )
    }

    val opening = shop.opening
    if (opening != null) {
        ChestScene(
            opening = opening,
            onStart = model.shop::startOpening,
            onNext = model.shop::nextChest,
            onClose = model.shop::closeOpening,
            onFail = { text ->
                model.shop.closeOpening()
                model.shop.tellShort(text)
            },
            onWardrobe = {
                val fresh = opening.result?.drops.orEmpty().mapNotNull { drop ->
                    val item = drop.item ?: return@mapNotNull null
                    val won = (drop.kind == "ITEM" && !drop.duplicate) || (drop.kind == "FRAGMENTS" && drop.completed)
                    if (won) WearRef(item.code, drop.variant?.name.orEmpty()) else null
                }
                model.shop.closeOpening()
                wear(fresh)
            },
            onRetry = model.shop::retryOpening,
            topUp = ChestTopUp(
                done = shop.completedFragments,
                busy = shop::isBusy,
                onTopUp = { card -> card.item?.let { rubyPrompt(card.topUp, ShopPrompt.Fragments(it, card.topUp)) } },
            ),
        )
    }

    val run = shop.caseRun
    if (run != null && opening == null) {
        CaseScene(
            run = run,
            balance = shop.balance,
            shards = shop.shards,
            onRetry = model.shop::retryCase,
            onAgain = model.shop::againCase,
            onShort = { missing ->
                model.shop.closeCase()
                if (run.case.paidInShards) model.shop.tellShort(tr("Не хватает осколков")) else short(missing)
            },
            onWardrobe = {
                val fresh = run.fresh.map { WearRef(it.code, it.variant) }
                model.shop.closeCase()
                wear(fresh)
            },
            onFail = { text ->
                model.shop.closeCase()
                model.shop.tellShort(text)
            },
            onClose = model.shop::closeCase,
        )
    }

    val reveal = shop.reveal
    if (reveal != null && opening == null && run == null) {
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

internal class ShopActs(
    val sale: SaleActs,
    val sets: SetActs,
    val cases: CaseActs,
    val onPack: (RubyPack) -> Unit,
    val onPlusSheet: () -> Unit,
    val onManage: () -> Unit,
    val onCraft: (WorkshopOffer) -> Unit,
    val onSet: (ShopSet) -> Unit,
    val onScroll: (String) -> Unit,
    val onOdds: () -> Unit,
    val picks: Map<String, String>,
)

/** The desktop's shop head (`sh-wallet2`): rubies, shards, «+» to the packs and the PLUS chip with its end date. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShopWallet(shop: ShopState, until: String?, onTopUp: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MBalanceBadge(MIcons.ruby, shop.balance, tr("Рубины"))
        MShardBalance(shop.shards ?: shop.workshop.data?.shards)
        MIconButton(MIcons.plus, onClick = onTopUp, tint = colors.onAccent, background = colors.accent, label = tr("Пополнить"))
        MPlusChip(shop.tier, onClick = onPlus, until = until)
    }
}

private fun LazyListScope.shopFeed(
    feed: FeedIndex,
    shop: ShopState,
    model: LauncherViewModel,
    acts: ShopActs,
    wide: Boolean,
    rubiesAside: @Composable RowScope.() -> Unit,
    nick: String,
) {
    val shopModel = model.shop
    val store = shop.store
    val day = store.data
    if (day == null) {
        slot(feed, "store-wait") {
            if (store.error != null) {
                MErrorCard(store.error, { shopModel.retry(ShopSection.STORE) }, Modifier.animateItem())
            } else {
                Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MSkeleton(height = 520.dp)
                    MSkeleton(height = 300.dp)
                }
            }
        }
        return
    }

    slot(feed, KEY_RUBIES) {
        MSecHead(tr("Пополнить рубины"), Modifier.animateItem(), icon = MIcons.ruby, aside = rubiesAside)
    }
    if (shop.plus.data != null) {
        slot(feed, "plus-bundle") {
            PlusBundle(
                tier = shop.tier,
                plus = shop.plus.data,
                busy = shop.isBusy("plus"),
                onBuy = { acts.onPlusSheet() },
                onManage = acts.onManage,
                modifier = Modifier.animateItem(),
                nick = nick,
            )
        }
    }
    slot(feed, "packs") {
        PackGrid(packsOf(shop), columnsOf(PACK_CELL, 6), shop::isBusy, onBuy = acts.onPack, modifier = Modifier.animateItem())
    }

    passFeed(feed, shop, model, acts)

    day.monthOffer?.let { offer ->
        slot(feed, "month-offer") {
            MonthOfferCard(
                offer = offer,
                packs = day.packs,
                busy = shop.isBusy("pack:" + offer.code),
                onBuy = { acts.onPack(RubyPack(code = offer.code, title = offer.title, rubies = offer.rubies, kopecks = offer.kopecks, oneTime = true)) },
                onEnd = { shopModel.retry(ShopSection.STORE) },
                modifier = Modifier.animateItem(),
            )
        }
    }

    val refresh = { shopModel.retry(ShopSection.STORE) }
    val cards = dayCards(day)
    slot(feed, "day-head") {
        MSecHead(tr("Скидки дня"), Modifier.animateItem(), aside = { SecClock(day.day.refreshAt, refresh) })
    }
    if (cards.isNotEmpty()) {
        slot(feed, "day-rail") { SaleRail(cards, tr("Скидки дня"), acts.sale, Modifier.animateItem(), auto = true) }
    }
    val night = day.nightMarket
    if (night != null && night.cards.isNotEmpty()) {
        slot(feed, "night-head") {
            MSecHead(tr("Ночной рынок"), Modifier.animateItem(), aside = { SecClock(night.endsAt, refresh) })
        }
        slot(feed, "night-rail") { SaleRail(night.cards.map { it to ShopSource.NIGHT }, tr("Ночной рынок"), acts.sale, Modifier.animateItem()) }
    }
    val wishes = wishedCards(day)
    if (wishes.isNotEmpty()) {
        slot(feed, "wish-head") { MSecHead(tr("Хочу"), Modifier.animateItem(), icon = MIcons.heart) }
        slot(feed, "wish-rail") { SaleRail(wishes, tr("Хочу"), acts.sale, Modifier.animateItem()) }
    }

    val play = shop.play.data
    if (play != null && play.limit > 0) {
        slot(feed, "bonus-head") { MSecHead(tr("Бонусы"), Modifier.animateItem()) }
        slot(feed, "bonus") {
            val status = shop.daily.data
            val ready = status?.let { readyTargets(passColumns(it)) }.orEmpty()
            BonusesBlock(
                view = play,
                viewAt = shop.playAt,
                busy = shop.isBusy("play"),
                giftReady = ready.size,
                giftBusy = shop.isBusy("daily"),
                passDay = status?.season?.day ?: 0,
                onClaim = shopModel::claimPlayChest,
                onGift = { if (ready.isNotEmpty()) shopModel.claimDaily(if (ready.size > 1) DailyTarget.All else ready.first()) },
                onPass = { acts.onScroll(KEY_PASS) },
                wide = wide,
                modifier = Modifier.animateItem(),
            )
        }
    }

    shop.telegram?.let { telegram ->
        slot(feed, "telegram") {
            TelegramBlock(telegram, shop.isBusy("telegram"), onOpen = shopModel::openChecked, onClaim = shopModel::claimTelegram, modifier = Modifier.animateItem())
        }
    }
    shop.discord?.let { discord ->
        slot(feed, "discord") {
            DiscordBlock(
                discord,
                busy = shop.isBusy("discord"),
                linkBusy = shop.isBusy("discord-link"),
                onLink = shopModel::linkDiscord,
                onOpen = shopModel::openChecked,
                onClaim = shopModel::claimDiscord,
                modifier = Modifier.animateItem(),
            )
        }
    }

    val sets = shop.sets
    if (sets.data?.sets?.isNotEmpty() != false && !(sets.data == null && sets.error != null)) {
        slot(feed, "sets-head") { MSecHead(tr("Наборы"), Modifier.animateItem()) }
        slot(feed, "sets") {
            SetsRail(sets.data?.sets, acts.picks, acts.sets, onOpen = acts.onSet, modifier = Modifier.animateItem())
        }
    }

    val cases = shop.cases
    if (cases.data?.cases?.isNotEmpty() != false && !(cases.data == null && cases.error != null)) {
        slot(feed, "cases-head") { MSecHead(tr("Ящики"), Modifier.animateItem()) }
        slot(feed, "cases") { CasesGrid(cases.data, columnsOf(CASE_CELL, 4), acts.cases, Modifier.animateItem()) }
    }

    val workshop = shop.workshop.data
    val offers = workshop?.workshop?.items.orEmpty().filterNot { it.owned }.take(SHARD_ITEMS)
    if (workshop != null && offers.isNotEmpty()) {
        slot(feed, "shards-head") { MSecHead(tr("За осколки"), Modifier.animateItem()) }
        slot(feed, "shards") {
            ShardsGrid(offers, workshop.shards, columnsOf(SHARD_CELL, 6), shop::isBusy, acts.onCraft, Modifier.animateItem())
        }
    }

    slot(feed, "banners") {
        val become: @Composable (Modifier) -> Unit = { bannerModifier -> CreatorBecomeBanner(onBecome = shopModel::becomeCreator, modifier = bannerModifier) }
        val support: @Composable (Modifier) -> Unit = { bannerModifier ->
            CreatorSupportBanner(
                status = shop.creator.data,
                busy = shop.isBusy("creator"),
                onApply = shopModel::setCreator,
                onRemove = shopModel::clearCreator,
                modifier = bannerModifier,
            )
        }
        if (wide) {
            Row(Modifier.fillMaxWidth().animateItem(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                become(Modifier.weight(1f))
                support(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth().animateItem(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                become(Modifier)
                support(Modifier)
            }
        }
    }
}

/** «Пропуск»: the season pass with one «Забрать», and «Мои сундуки» under it while there are any. */
private fun LazyListScope.passFeed(feed: FeedIndex, shop: ShopState, model: LauncherViewModel, acts: ShopActs) {
    val shopModel = model.shop
    val daily = shop.daily
    slot(feed, KEY_PASS) { MSecHead(tr("Пропуск"), Modifier.animateItem()) }
    slot(feed, "pass") {
        val status = daily.data
        when {
            status != null -> DailyPassCard(
                status = status,
                plusPriceKopecks = shop.plus.data?.priceKopecks,
                claiming = shop.isBusy("daily"),
                onClaim = shopModel::claimDaily,
                onPlus = { if (shop.tier != null) acts.onManage() else acts.onPlusSheet() },
                onElapsed = { shopModel.retry(ShopSection.DAILY) },
                modifier = Modifier.animateItem(),
                extra = passExtra(shop, model),
            )
            daily.error != null -> MErrorCard(daily.error, { shopModel.retry(ShopSection.DAILY) }, Modifier.animateItem())
            else -> MSkeleton(height = 300.dp, modifier = Modifier.animateItem())
        }
    }
    if (shop.pendingChests.isNotEmpty() || shop.chests.error != null) {
        slot(feed, "chests") {
            MyChestsCard(
                chests = shop.pendingChests,
                loading = shop.chests.loading,
                error = shop.chests.error,
                busy = shop.opening != null,
                onRetry = { shopModel.retry(ShopSection.CHESTS) },
                onOpen = shopModel::openChests,
                onOdds = acts.onOdds,
                onHowTo = { acts.onScroll(KEY_PASS) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** The pass's gift takes a ready week star first, then a pack quest, when no pass cell is ready. */
internal fun passExtra(shop: ShopState, model: LauncherViewModel): PassExtra? {
    val path = (shop.weekly.data as? WeeklyAny.Path)?.path
    val step = path?.steps?.firstOrNull { !it.claimed && path.stars >= it.at }
    if (step != null) return PassExtra(shop.isBusy("weekly")) { model.shop.claimWeeklyStep(step.at) }
    val quest = shop.quests.data.orEmpty().firstOrNull { questState(it) == QuestState.Claim } ?: return null
    return PassExtra(shop.isBusy("quest:" + quest.code)) { model.shop.claimQuest(quest) }
}

@Composable
private fun ShardsGrid(
    offers: List<WorkshopOffer>,
    shards: Long,
    columns: Int,
    isBusy: (String) -> Boolean,
    onCraft: (WorkshopOffer) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        offers.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { offer ->
                    ShardTile(offer, shards, isBusy("craft:" + offer.item.code), onCraft = { onCraft(offer) }, modifier = Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun ShardTile(offer: WorkshopOffer, shards: Long, busy: Boolean, onCraft: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    val rarity = net.millida.launcher.ui.Rarity.of(offer.item.rarity)
    net.millida.launcher.ui.MRarityFrame(rarity = rarity, modifier = modifier, onClick = if (busy) null else onCraft, contentPadding = 10.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PlayerShot(listOf(offer.item), rarity?.color ?: colors.accent, Modifier.fillMaxWidth().aspectRatio(0.75f).pixelSurface(colors.inset, MillidaCut.control)) {
                ItemArt(offer.item, side = 84.dp)
            }
            Text(offer.item.name, style = MillidaType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = colors.fg, maxLines = 1)
            PriceButton(offer.cost, 0, shards, busy, onClick = onCraft, shards = true)
        }
    }
}

@Composable
private fun PlusSheet(shop: ShopState, model: LauncherViewModel, look: CharacterLook, onDismiss: () -> Unit) {
    val wide = LocalWideLayout.current
    MBottomSheet(onDismiss = onDismiss, title = "Millida PLUS") {
        StatusCompare(look.nick.ifBlank { "Steve" }, look.skinUrl, look.slim)
        Spacer(Modifier.height(12.dp))
        val plan: @Composable (PlusTier, Modifier) -> Unit = { tier, planModifier ->
            PlanCard(
                tier = tier,
                price = plusPriceText(shop.plus.data, tier),
                waiting = model.shop.paymentWaiting(tier),
                busy = shop.isBusy("plus"),
                onBuy = {
                    model.shop.subscribePlus(tier)
                    onDismiss()
                },
                modifier = planModifier,
            )
        }
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                plan(PlusTier.PLUS, Modifier.weight(1f))
                plan(PlusTier.DIAMOND, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                plan(PlusTier.PLUS, Modifier.fillMaxWidth())
                plan(PlusTier.DIAMOND, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(4.dp))
        MTextLink(tr("Что даёт PLUS"), onClick = {
            onDismiss()
            model.open(Screen.PLUS)
        }, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

/**
 * One confirmation for every purchase, the desktop's BuySheet: a big picture
 * of what comes, its name, the price on the main button and «Отмена».
 */
@Composable
private fun BuySheet(
    name: String,
    confirm: String,
    icon: MillidaIcon?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    art: @Composable BoxScope.() -> Unit,
) {
    val colors = millida
    MBottomSheet(onDismiss = onDismiss) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val height = minOf(280.dp, maxWidth * 4f / 3f, (effectiveScreenHeightDp() * 0.45f).dp)
            Box(
                Modifier
                    .height(height)
                    .aspectRatio(0.75f)
                    .pixelSurface(colors.inset, MillidaCut.plate),
                contentAlignment = Alignment.Center,
                content = art,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            name,
            modifier = Modifier.fillMaxWidth(),
            style = MillidaType.sectionTitle.copy(fontSize = 22.sp, fontWeight = FontWeight.Black),
            color = colors.fg,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MPrimaryButton(confirm, onClick = onConfirm, modifier = Modifier.weight(1.6f), icon = icon, size = MButtonSize.Large)
            MSecondaryButton(tr("Отмена"), onClick = onDismiss, modifier = Modifier.weight(1f), size = MButtonSize.Large)
        }
    }
}

@Composable
private fun ShopDialogs(prompt: ShopPrompt?, shop: ShopState, model: LauncherViewModel, onDismiss: () -> Unit) {
    when (prompt) {
        null -> Unit
        is ShopPrompt.Buy -> {
            val item = prompt.card.item
            val tone = Rarity.of(item.rarity)?.color ?: millida.accent
            BuySheet(
                name = item.name,
                confirm = tr("Купить · {it}", groupDigits(prompt.card.price)),
                icon = MIcons.ruby,
                onConfirm = {
                    model.shop.buy(prompt.card, prompt.source)
                    onDismiss()
                },
                onDismiss = onDismiss,
            ) {
                PlayerShot(listOf(item), tone, Modifier.fillMaxSize()) { ItemArt(item, side = 160.dp) }
            }
        }
        is ShopPrompt.Pack -> {
            val packs = packsOf(shop).orEmpty()
            val (list, _) = sortPacks(packs.filterNot { it.oneTime })
            val art = if (prompt.pack.oneTime) STARTER_ART else packArtAt(list.indexOfFirst { it.code == prompt.pack.code }.coerceAtLeast(0), list.size)
            BuySheet(
                name = "${groupDigits(prompt.pack.rubies)} ${rubyWord(prompt.pack.rubies)}",
                confirm = rubles(prompt.pack.kopecks),
                icon = null,
                onConfirm = {
                    model.shop.buyPack(prompt.pack)
                    onDismiss()
                },
                onDismiss = onDismiss,
            ) {
                ShopAsset(art, 200.dp)
            }
        }
        is ShopPrompt.PackShort -> WalletShortDialog(
            text = tr("На счёте Millida не хватает {0}. Пополни на сайте и вернись за пакетом.", rubles(prompt.missingKopecks)),
            onTopUp = {
                model.shop.openWalletTopUp()
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        is ShopPrompt.Craft -> MConfirmDialog(
            title = tr("Обменять осколки"),
            text = groupDigits(prompt.offer.cost).let { cost ->
                trPlural(
                    prompt.offer.cost,
                    tr("{0} осколок → половина фрагментов: {name}. Остаток докупается за рубины", cost, prompt.offer.item.name),
                    tr("{0} осколка → половина фрагментов: {name}. Остаток докупается за рубины", cost, prompt.offer.item.name),
                    tr("{0} осколков → половина фрагментов: {name}. Остаток докупается за рубины", cost, prompt.offer.item.name),
                )
            },
            confirm = tr("Обменять"),
            onConfirm = {
                model.shop.craft(prompt.offer)
                onDismiss()
            },
            onDismiss = onDismiss,
            danger = false,
        )
        is ShopPrompt.Fragments -> MConfirmDialog(
            title = tr("Докупить фрагменты"),
            text = groupDigits(prompt.price).let { price ->
                trPlural(
                    prompt.price,
                    tr("{name}: докупить недостающие фрагменты за {0} рубин", prompt.item.name, price),
                    tr("{name}: докупить недостающие фрагменты за {0} рубина", prompt.item.name, price),
                    tr("{name}: докупить недостающие фрагменты за {0} рубинов", prompt.item.name, price),
                )
            },
            confirm = tr("Докупить"),
            onConfirm = {
                model.shop.completeFragments(prompt.item, celebrate = false)
                onDismiss()
            },
            onDismiss = onDismiss,
            danger = false,
        )
        is ShopPrompt.SetBuy -> BuySheet(
            name = prompt.set.title,
            confirm = tr("Купить · {it}", groupDigits(prompt.way.price)),
            icon = MIcons.ruby,
            onConfirm = {
                model.shop.buySet(prompt.set, prompt.way)
                onDismiss()
            },
            onDismiss = onDismiss,
        ) {
            PlayerShot(prompt.way.items.map { it.item }, themeTone(prompt.set.theme), Modifier.fillMaxSize()) {
                ItemArt(prompt.way.items.maxByOrNull { it.price }?.item ?: return@PlayerShot, side = 160.dp)
            }
        }
        is ShopPrompt.CaseOpen -> {
            val look = caseChest(prompt.case.id)
            BuySheet(
                name = prompt.case.title + if (prompt.mode == CaseMode.TEN) " ×10" else "",
                confirm = groupDigits(prompt.case.priceOf(prompt.mode)).let { if (prompt.mode == CaseMode.TEN) tr("Открыть ×10 · {0}", it) else tr("Открыть · {0}", it) },
                icon = if (prompt.case.paidInShards) MIcons.shard else MIcons.ruby,
                onConfirm = {
                    model.shop.openCase(prompt.case, prompt.mode)
                    onDismiss()
                },
                onDismiss = onDismiss,
            ) {
                ShotScene(caseTone(prompt.case), Modifier.fillMaxSize())
                ChestLive(look.tier, side = 170.dp, tint = look.tint)
            }
        }
    }
    if (shop.walletShort) {
        WalletShortDialog(
            text = tr("На счёте Millida не хватает денег. Пополни на сайте и вернись — покупка подождёт."),
            onTopUp = model.shop::openWalletTopUp,
            onDismiss = model.shop::dismissWalletShort,
        )
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
        title = tr("Не хватает рубинов"),
        onDismiss = onDismiss,
        body = {
            Text(
                groupDigits(missing).let { count ->
                    trPlural(missing, tr("На {subject} не хватает {0} рубина.", subject, count), tr("На {subject} не хватает {0} рубинов.", subject, count), tr("На {subject} не хватает {0} рубинов.", subject, count))
                },
                style = MillidaType.body,
                color = colors.fgMuted,
            )
            Text(
                tr("Докупить ровно {0} за {1} со счёта Millida.", groupDigits(rubies), rubles(kopecksForRubies(rubies))),
                style = MillidaType.small,
                color = colors.fgSubtle,
            )
        },
        actions = {
            MTextButton(tr("Отмена"), onClick = onDismiss)
            MSecondaryButton(tr("Пакеты"), onClick = {
                onDismiss()
                onPacks()
            })
            MPrimaryButton(tr("Докупить"), onClick = {
                onTopUp(rubies.toInt())
                onDismiss()
            })
        },
    )
}

@Composable
private fun WalletShortDialog(text: String, onTopUp: () -> Unit, onDismiss: () -> Unit) {
    MDialog(
        title = tr("Пополни счёт"),
        onDismiss = onDismiss,
        body = { Text(text, style = MillidaType.body, color = millida.fgMuted) },
        actions = {
            MTextButton(tr("Отмена"), onClick = onDismiss)
            MPrimaryButton(tr("Пополнить"), onClick = onTopUp, icon = MIcons.externalLink)
        },
    )
}
