package net.millida.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.hub.HeroSlot
import net.millida.launcher.core.hub.Hub
import net.millida.launcher.core.hub.HubMode
import net.millida.launcher.core.hub.PopularVersion
import net.millida.launcher.core.hub.PresetLoader
import net.millida.launcher.core.hub.Rotation
import net.millida.launcher.core.premium.Premium
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.core.premium.PremiumTier
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MSectionHeader
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida

/** The OneBlock client pack of our catalogue: its card in a feed means «play the event». */
internal const val ONEBLOCK_PACK = "oneblock-metalabs"

private const val MODES_SHOWN = 8
private const val FEED_LIMIT = 10
private const val EXCLUSIVES_SHOWN = 2
private const val WEEK_SIZE = 3

/** What the hub sections under the lobby need, gathered once by the home screen. */
internal class HubFeed(
    val hub: HubState,
    val exclusives: List<PremiumPack>,
    val free: List<PremiumPack>,
    val plan: PremiumTier?,
    val premiumWait: Boolean,
    /** OneBlock's own pack may start on this phone; without it the event is not offered. */
    val ownPlayable: Boolean,
)

internal class HubActions(
    val playOwn: () -> Unit,
    val openPack: (PremiumPack) -> Unit,
    val openCatalog: () -> Unit,
    val openMode: (HubMode) -> Unit,
    val createVersion: (String) -> Unit,
    val retry: () -> Unit,
)

/**
 * The «Во что играем» sections, in the desktop hub's order, after the lobby's
 * own blocks: the card to try today, the hero of the day, «Рекомендуем»,
 * «Режимы» and, for a player without builds, the popular versions. [party]
 * is the place reserved for playing together with friends.
 */
internal fun LazyListScope.hubSections(
    feed: HubFeed,
    actions: HubActions,
    hasBuilds: Boolean,
    party: (@Composable () -> Unit)? = null,
) {
    if (party != null) {
        item(key = "hub:party", contentType = "hub-party") { party() }
    }
    item(key = "hub:recommend", contentType = "hub-recommend") {
        RecommendCard(feed, actions)
    }
    item(key = "hub:hero", contentType = "hub-hero") {
        HeroOfDay(feed, actions)
    }
    item(key = "hub:foryou", contentType = "hub-shelf") {
        ForYouShelf(feed, actions)
    }
    item(key = "hub:modes", contentType = "hub-modes") {
        ModesSection(feed.hub, feed.ownPlayable, actions)
    }
    if (!hasBuilds && feed.hub.popular.isNotEmpty()) {
        item(key = "hub:popular", contentType = "hub-popular") {
            PopularVersions(feed.hub.popular, actions.createVersion)
        }
    }
}

/** Our pinned packs, one per launch in turn: what the desktop lobby recommends. */
internal fun recommendedPack(hub: HubState): PremiumPack? {
    val catalog = Premium.forPhone(hub.catalog.orEmpty())
    val pinned = RECOMMENDED_SLUGS.mapNotNull { slug -> catalog.firstOrNull { it.slug == slug } }
    if (pinned.isEmpty()) return null
    return pinned[hub.recommendTurn.mod(pinned.size)]
}

private val RECOMMENDED_SLUGS = listOf(ONEBLOCK_PACK, "arcania")

/**
 * «Попробуй сегодня»: the [recommendedPack] in the feed. The OneBlock pack
 * starts the event itself.
 */
@Composable
private fun RecommendCard(feed: HubFeed, actions: HubActions) {
    val colors = millida
    val pack = recommendedPack(feed.hub) ?: return
    val own = pack.slug == ONEBLOCK_PACK
    val open = { if (own) actions.playOwn() else actions.openPack(pack) }
    MCard(onClick = open, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 96.dp, height = 64.dp)
                    .clip(MillidaShape.control)
                    .background(colors.surface2),
            ) {
                if (own) OneBlockArt() else MRemoteImage(pack.coverUrl, MIcons.chest, Modifier.fillMaxSize(), shape = MillidaShape.control)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (own) "Эксклюзивный ивент" else "Попробуй сегодня",
                    style = MillidaType.caption,
                    color = if (own) colors.warning else colors.accentText,
                    maxLines = 1,
                )
                Text(pack.title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(pack.loader, pack.mcVersion).joinToString(" · ").ifEmpty { pack.tagline.orEmpty() },
                    style = MillidaType.small,
                    color = colors.fgSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            MTextButton(if (own) "Играть" else "Открыть", onClick = open, trailingIcon = MIcons.chevronRight)
        }
    }
}

/** One hero a day — Arcania, OneBlock or the week's pick — the next one when today's has nothing to show. */
@Composable
private fun HeroOfDay(feed: HubFeed, actions: HubActions) {
    val arcania = feed.exclusives.firstOrNull(Premium::isArcania)
    val week = Rotation.weekPicks(
        (feed.exclusives + feed.free).filter { !it.coverUrl.isNullOrBlank() }.distinctBy { it.id },
        Rotation.weekNumber(feed.hub.day),
        WEEK_SIZE,
    )
    val slot = Rotation.heroQueue(feed.hub.heroIndex).firstOrNull { slot ->
        when (slot) {
            HeroSlot.ARCANIA -> arcania != null
            HeroSlot.ONEBLOCK -> feed.ownPlayable
            HeroSlot.WEEK -> week.size == WEEK_SIZE
        }
    }
    when (slot) {
        HeroSlot.ARCANIA -> arcania?.let { HeroPremium(it, feed.plan) { actions.openPack(it) } }
        HeroSlot.WEEK -> HeroWeek(week, actions.openPack)
        HeroSlot.ONEBLOCK -> OneBlockBanner(online = feed.hub.ownOnline, onPlay = actions.playOwn)
        null -> Unit
    }
}

/** The premium pack across the whole slot: its cover, the price and «Открыть». */
@Composable
private fun HeroPremium(pack: PremiumPack, plan: PremiumTier?, onOpen: () -> Unit) {
    val colors = millida
    val price = Premium.priceLabel(pack, plan)
    MCard(onClick = onOpen, contentPadding = 10.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(168.dp)
                .clip(MillidaShape.control)
                .background(colors.surface2),
        ) {
            MRemoteImage(pack.coverUrl, MIcons.crown, Modifier.fillMaxSize(), shape = MillidaShape.control)
            Box(Modifier.padding(8.dp)) { HubTagBadge(HubTag.PREMIUM, "Премиум") }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pack.title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (price.isNotEmpty()) Text(price, style = MillidaType.pixel, color = colors.accentText, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            MPrimaryButton(text = "Открыть", onClick = onOpen, icon = MIcons.chevronRight, size = MButtonSize.Medium)
        }
    }
}

/** «Подборка недели»: three covers side by side, each opens its pack. */
@Composable
private fun HeroWeek(packs: List<PremiumPack>, onOpen: (PremiumPack) -> Unit) {
    val colors = millida
    Column(Modifier.fillMaxWidth()) {
        MSectionHeader("Подборка недели")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            packs.forEach { pack ->
                MCard(modifier = Modifier.weight(1f), onClick = { onOpen(pack) }, contentPadding = 8.dp) {
                    MRemoteImage(
                        pack.coverUrl,
                        MIcons.chest,
                        Modifier
                            .fillMaxWidth()
                            .height(88.dp),
                        shape = MillidaShape.control,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(pack.title, style = MillidaType.small, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * «Рекомендуем»: our event first, then the paid packs (Arcania first, the
 * other one changes daily), then our free packs in the day's order, and
 * «Ещё» into the catalogue.
 */
@Composable
private fun ForYouShelf(feed: HubFeed, actions: HubActions) {
    val day = feed.hub.day
    val arcania = feed.exclusives.firstOrNull(Premium::isArcania)
    val others = Rotation.ofDay(feed.exclusives.filter { it.id != arcania?.id }, day, salt = 1)
    val head = (listOfNotNull(arcania) + others).take(EXCLUSIVES_SHOWN)
    val taken = head.mapTo(HashSet()) { it.id }
    val packs = Rotation.ofDay(
        feed.free.filter { it.id !in taken && it.slug != ONEBLOCK_PACK && !it.coverUrl.isNullOrBlank() },
        day,
        salt = 2,
    ).take(FEED_LIMIT)
    val loading = feed.hub.catalog == null && !feed.hub.catalogFailed
    Column(Modifier.fillMaxWidth()) {
        MSectionHeader("Рекомендуем", action = "Все", onAction = actions.openCatalog)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (feed.ownPlayable) {
                item(key = "own") {
                    HubCoverCard(
                        title = "OneBlock",
                        meta = Hub.onlineLabel(feed.hub.ownOnline) ?: "Выживи на одном блоке",
                        tag = HubTag.OWN,
                        tagText = "Наш сервер",
                        onClick = actions.playOwn,
                        art = { OneBlockArt() },
                    )
                }
            }
            if (head.isEmpty() && feed.premiumWait) {
                item(key = "excl-wait") { MSkeleton(height = 160.dp, modifier = Modifier.width(HUB_CARD_WIDTH)) }
            }
            items(head, key = { "excl:${it.id}" }) { pack ->
                HubCoverCard(
                    title = pack.title,
                    meta = Premium.priceLabel(pack, feed.plan).ifEmpty { "Премиум" },
                    tag = HubTag.EXCLUSIVE,
                    tagText = "Arcania Labs",
                    cover = pack.coverUrl,
                    onClick = { actions.openPack(pack) },
                )
            }
            if (loading) {
                items(3, key = { "feed-wait-$it" }) { MSkeleton(height = 160.dp, modifier = Modifier.width(HUB_CARD_WIDTH)) }
            }
            items(packs, key = { "pack:${it.id}" }) { pack ->
                HubCoverCard(
                    title = pack.title,
                    meta = listOfNotNull(pack.loader, pack.mcVersion).joinToString(" · ").ifEmpty { pack.tagline },
                    tag = HubTag.PACK,
                    tagText = "Сборка",
                    cover = pack.coverUrl,
                    onClick = { actions.openPack(pack) },
                )
            }
            item(key = "more") { HubMoreCard("Ещё", "Весь каталог", MIcons.chevronRight, actions.openCatalog) }
        }
        if (feed.hub.catalogFailed && feed.hub.catalog == null) {
            Spacer(Modifier.height(10.dp))
            MErrorCard("Сборки не загрузились", onRetry = actions.retry)
        }
    }
}

/** «Режимы»: the own event as a banner, the busiest modes, «Остальные» opens the rest in place. */
@Composable
private fun ModesSection(hub: HubState, ownPlayable: Boolean, actions: HubActions) {
    var all by rememberSaveable { mutableStateOf(false) }
    val modes = hub.modes
    Column(Modifier.fillMaxWidth()) {
        MSectionHeader("Режимы")
        when {
            modes == null && hub.modesFailed -> MErrorCard("Режимы не загрузились", onRetry = actions.retry)
            modes == null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MSkeleton(height = 150.dp, modifier = Modifier.weight(1f))
                MSkeleton(height = 150.dp, modifier = Modifier.weight(1f))
            }
            modes.isEmpty() -> MErrorCard("Режимов пока нет", onRetry = actions.retry)
            else -> {
                val own = modes.firstOrNull { it.code == Hub.OWN_MODE }?.takeIf { ownPlayable }
                val rest = modes.filter { it.code != Hub.OWN_MODE }
                val shown = if (all) rest else rest.take(MODES_SHOWN - 1)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (own != null) OneBlockBanner(online = hub.ownOnline ?: own.online, onPlay = actions.playOwn)
                    PairRows(shown) { mode, modifier ->
                        ModeTile(mode, hub.art[mode.code], onClick = { actions.openMode(mode) }, modifier = modifier)
                    }
                    val hidden = rest.size - (MODES_SHOWN - 1)
                    if (hidden > 0) {
                        MTextButton(
                            text = if (all) "Свернуть" else "Остальные · ${modesCount(hidden)}",
                            onClick = { all = !all },
                            modifier = Modifier.fillMaxWidth(),
                            icon = if (all) MIcons.chevronUp else MIcons.grid,
                        )
                    }
                }
            }
        }
    }
}

private fun modesCount(count: Int): String {
    val tail = count % 100
    val word = when {
        tail in 11..14 -> "режимов"
        count % 10 == 1 -> "режим"
        count % 10 in 2..4 -> "режима"
        else -> "режимов"
    }
    return "$count $word"
}

/** For a player without builds: the versions played most in the launcher, one tap to a new build. */
@Composable
private fun PopularVersions(versions: List<PopularVersion>, onCreate: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        MSectionHeader("Популярные версии")
        MButtonRow {
            versions.forEach { version ->
                MChip(
                    text = version.mc + if (version.loader == PresetLoader.FABRIC) "" else " " + presetLoaderName(version.loader),
                    selected = false,
                    onClick = { onCreate(version.mc) },
                )
            }
        }
    }
}

private fun presetLoaderName(loader: PresetLoader): String = when (loader) {
    PresetLoader.VANILLA -> "Vanilla"
    PresetLoader.FABRIC -> "Fabric"
    PresetLoader.FORGE -> "Forge"
    PresetLoader.NEOFORGE -> "NeoForge"
    PresetLoader.QUILT -> "Quilt"
}
