package net.millida.launcher.ui.shop

import androidx.compose.foundation.layout.Box
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.plateFrame
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable

private enum class StageTab(val icon: MillidaIcon) {
    RUBIES(MIcons.ruby),
    PASS(MIcons.crown),
    SALES(MIcons.flame),
    BONUSES(MIcons.gift),
    SETS(MIcons.shirt),
    CASES(MIcons.chest),
    SHARDS(MIcons.shard),
}

@Composable
private fun StageTab.label(): String = when (this) {
    StageTab.RUBIES -> tr("Рубины")
    StageTab.PASS -> tr("Пропуск")
    StageTab.SALES -> tr("Скидки")
    StageTab.BONUSES -> tr("Бонусы")
    StageTab.SETS -> tr("Наборы")
    StageTab.CASES -> tr("Ящики")
    StageTab.SHARDS -> tr("Осколки")
}

/** One stage card: a fixed width, the full height of the row, scrolls up inside only when it is taller than the row. */
private class StageCard(val key: String, val width: Dp, val content: @Composable () -> Unit)

private val PACK_W = 132.dp
private val SALE_W = 172.dp
private val SET_W = 200.dp
private val CASE_W = 190.dp
private val SHARD_W = 132.dp
private val BLOCK_W = 330.dp
private val PASS_W = 560.dp

/**
 * The shop on a phone on its side, laid out like the Clash / Brawl Stars store:
 * categories down the left, the chosen category as one row of big cards that
 * scrolls sideways and settles on a card. Cards are composed lazily and their
 * pictures are snapshots, so a swipe moves bitmaps instead of rebuilding a page.
 */
@Composable
internal fun ShopStage(
    shop: ShopState,
    model: LauncherViewModel,
    acts: ShopActs,
    nick: String,
    tabName: String,
    onTab: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val day = shop.store.data ?: return
    val tab = StageTab.entries.firstOrNull { it.name == tabName } ?: StageTab.RUBIES
    val shopModel = model.shop
    val refresh = { shopModel.retry(ShopSection.STORE) }

    val cards: List<StageCard> = when (tab) {
        StageTab.RUBIES -> buildList {
            if (shop.plus.data != null) {
                add(StageCard("plus", BLOCK_W) {
                    PlusBundle(shop.tier, shop.plus.data, shop.isBusy("plus"), { acts.onPlusSheet() }, acts.onManage, Modifier, nick)
                })
            }
            day.monthOffer?.let { offer ->
                add(StageCard("month", BLOCK_W) {
                    MonthOfferCard(
                        offer = offer,
                        packs = day.packs,
                        busy = shop.isBusy("pack:" + offer.code),
                        onBuy = { acts.onPack(RubyPack(code = offer.code, title = offer.title, rubies = offer.rubies, kopecks = offer.kopecks, oneTime = true)) },
                        onEnd = refresh,
                    )
                })
            }
            val packs = packsOf(shop).orEmpty()
            val starter = packs.firstOrNull { it.oneTime }
            val (list, best) = sortPacks(packs.filterNot { it.oneTime })
            (listOfNotNull(starter) + list).forEach { pack ->
                add(StageCard("pack-" + pack.code, PACK_W) {
                    PackTile(
                        pack = pack,
                        promo = pack == starter,
                        was = if (pack == starter) usualKopecks(pack.rubies, list) else 0,
                        best = pack.code == best,
                        art = if (pack == starter) STARTER_ART else packArtAt(list.indexOf(pack).coerceAtLeast(0), list.size),
                        busy = shop.isBusy("pack:" + pack.code),
                        onBuy = { acts.onPack(pack) },
                    )
                })
            }
        }
        StageTab.PASS -> buildList {
            val status = shop.daily.data
            if (status != null) {
                add(StageCard("pass", PASS_W) {
                    DailyPassCard(
                        status = status,
                        plusPriceKopecks = shop.plus.data?.priceKopecks,
                        claiming = shop.isBusy("daily"),
                        onClaim = shopModel::claimDaily,
                        onPlus = { if (shop.tier != null) acts.onManage() else acts.onPlusSheet() },
                        onElapsed = { shopModel.retry(ShopSection.DAILY) },
                        extra = passExtra(shop, model),
                    )
                })
            } else {
                add(StageCard("pass-wait", BLOCK_W) {
                    val error = shop.daily.error
                    if (error != null) MErrorCard(error, { shopModel.retry(ShopSection.DAILY) }) else MSkeleton(height = 240.dp)
                })
            }
            if (shop.pendingChests.isNotEmpty() || shop.chests.error != null) {
                add(StageCard("chests", BLOCK_W) {
                    MyChestsCard(
                        chests = shop.pendingChests,
                        loading = shop.chests.loading,
                        error = shop.chests.error,
                        busy = shop.opening != null,
                        onRetry = { shopModel.retry(ShopSection.CHESTS) },
                        onOpen = shopModel::openChests,
                        onOdds = acts.onOdds,
                        onHowTo = {},
                    )
                })
            }
        }
        StageTab.SALES -> buildList {
            fun group(id: String, title: String, ends: String?, sources: List<Pair<ShopCard, ShopSource>>) {
                if (sources.isEmpty()) return
                add(StageCard("head-$id", 120.dp) { StageHead(title) { ends?.let { SecClock(it, refresh) } } })
                sources.forEach { (card, source) ->
                    add(StageCard("$id-${source.wire}${card.item.code}", SALE_W) { SaleTile(card, source, acts.sale, timer = id == "day") })
                }
            }
            group("day", tr("Скидки дня"), day.day.refreshAt, dayCards(day))
            val night = day.nightMarket
            if (night != null) group("night", tr("Ночной рынок"), night.endsAt, night.cards.map { it to ShopSource.NIGHT })
            group("wish", tr("Хочу"), null, wishedCards(day))
        }
        StageTab.BONUSES -> buildList {
            val play = shop.play.data
            if (play != null && play.limit > 0) {
                add(StageCard("bonus", PASS_W - 120.dp) {
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
                        onPass = {},
                        wide = false,
                    )
                })
            }
            shop.telegram?.let { telegram ->
                add(StageCard("telegram", BLOCK_W) {
                    TelegramBlock(telegram, shop.isBusy("telegram"), onOpen = shopModel::openChecked, onClaim = shopModel::claimTelegram)
                })
            }
            shop.discord?.let { discord ->
                add(StageCard("discord", BLOCK_W) {
                    DiscordBlock(
                        discord,
                        busy = shop.isBusy("discord"),
                        linkBusy = shop.isBusy("discord-link"),
                        onLink = shopModel::linkDiscord,
                        onOpen = shopModel::openChecked,
                        onClaim = shopModel::claimDiscord,
                    )
                })
            }
            add(StageCard("become", BLOCK_W) { CreatorBecomeBanner(onBecome = shopModel::becomeCreator) })
            add(StageCard("support", BLOCK_W) {
                CreatorSupportBanner(
                    status = shop.creator.data,
                    busy = shop.isBusy("creator"),
                    onApply = shopModel::setCreator,
                    onRemove = shopModel::clearCreator,
                )
            })
        }
        StageTab.SETS -> buildList {
            val sets = shop.sets.data?.sets.orEmpty()
            sets.forEach { set ->
                add(StageCard("set-" + set.id, SET_W) {
                    val way = set.colorways.firstOrNull { it.name == acts.picks[set.id] } ?: set.defaultWay
                    if (way != null) SetCard(set, way, acts.sets) { acts.onSet(set) }
                })
            }
            if (sets.isEmpty()) add(StageCard("sets-wait", BLOCK_W) { MSkeleton(height = 240.dp) })
        }
        StageTab.CASES -> buildList {
            val data = shop.cases.data
            if (data == null) {
                add(StageCard("cases-wait", BLOCK_W) { MSkeleton(height = 240.dp) })
            } else {
                data.cases.forEach { case ->
                    add(StageCard("case-" + case.id, CASE_W) { CaseCard(case, case.id == data.freeCaseId, acts.cases) })
                }
            }
        }
        StageTab.SHARDS -> buildList {
            val workshop = shop.workshop.data
            val offers = workshop?.workshop?.items.orEmpty().filterNot { it.owned }
            offers.forEach { offer ->
                add(StageCard("shard-" + offer.item.code, SHARD_W) {
                    ShardTile(offer, workshop?.shards ?: 0L, shop.isBusy("craft:" + offer.item.code), onCraft = { acts.onCraft(offer) })
                })
            }
            if (offers.isEmpty()) add(StageCard("shards-wait", BLOCK_W) { MSkeleton(height = 240.dp) })
        }
    }

    val rowState = rememberLazyListState()
    LaunchedEffect(tab) { rowState.scrollToItem(0) }

    Row(modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(104.dp)
                .fillMaxHeight()
                .background(colors.surface)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StageTab.entries.forEach { entry ->
                StageTabButton(entry.icon, entry.label(), entry == tab) { onTab(entry.name) }
            }
        }
        LazyRow(
            state = rowState,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            flingBehavior = rememberSnapFlingBehavior(rowState),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(cards, key = { it.key }, contentType = { it.key.substringBefore('-') }) { card ->
                Box(Modifier.width(card.width).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { card.content() }
                }
            }
        }
    }
}

@Composable
private fun StageHead(title: String, aside: @Composable () -> Unit) {
    val colors = millida
    Column(
        Modifier
            .fillMaxWidth()
            .pixelSurface(colors.surface2, MillidaCut.plate, colors.plateFrame())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MillidaType.sectionTitle, color = colors.fg)
        aside()
    }
}

@Composable
private fun StageTabButton(icon: MillidaIcon, label: String, on: Boolean, onClick: () -> Unit) {
    val colors = millida
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pixelSurface(if (on) colors.accent else colors.surface2, MillidaCut.control, if (on) PixelFrame.None else colors.plateFrame())
            .selectable(on, remember { MutableInteractionSource() }, null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MIcon(icon, size = 20.dp, color = if (on) colors.onAccent else colors.fgMuted)
        Text(label, style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Bold), color = if (on) colors.onAccent else colors.fg, maxLines = 1)
    }
}
