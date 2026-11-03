package net.millida.launcher.ui.shop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.i18n.trPlural
import net.millida.launcher.core.rubies.MonthOffer
import net.millida.launcher.core.rubies.PlusStatus
import net.millida.launcher.core.rubies.RubyPack
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MDayClock
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPlusMark
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.PlusTier
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.rememberPressed
import net.millida.launcher.core.media.UiSoundEvent
import kotlin.math.abs
import kotlin.math.roundToLong

/** What the PLUS pass gives over 28 days: the service's season track, mirrored for the offer tile. */
internal object PlusPass {
    const val RUBIES = 2100L
    const val RARE = 3
    const val EPIC = 5
    const val LEGEND = 1
    const val CHESTS = RARE + EPIC + LEGEND
    const val SHARD_BOOST = "1,5"
    const val PLUS_PCT = 10
    const val DIAMOND_PCT = 15
    const val FREE_RUBIES = 230L
    const val FREE_CHESTS = 6
}

private const val EXAMPLE_PRICE = 1000L

internal fun tierPct(tier: PlusTier): Int = if (tier == PlusTier.DIAMOND) PlusPass.DIAMOND_PCT else PlusPass.PLUS_PCT

/** The subscription's price in rubles, "" while the status has not come. */
internal fun plusPriceText(plus: PlusStatus?, tier: PlusTier): String {
    val kopecks = plus?.priceOf(tier.name) ?: 0
    return if (kopecks > 0) rubles(kopecks) else ""
}

private fun cutPrice(price: Long, pct: Int): Long = maxOf(10L, (price * (100 - pct) / 100.0 / 10.0).roundToLong() * 10)

/** The tile's mode: a subscriber extends or upgrades, a newcomer picks a tier. */
internal sealed interface PlusTileMode {
    data object Pick : PlusTileMode
    data object Upgrade : PlusTileMode
    data object Extend : PlusTileMode
}

internal fun plusTileMode(tier: PlusTier?): PlusTileMode = when (tier) {
    PlusTier.DIAMOND -> PlusTileMode.Extend
    PlusTier.PLUS -> PlusTileMode.Upgrade
    null -> PlusTileMode.Pick
}

/**
 * Millida PLUS as a bundle, the desktop's first tile of «Пополнить рубины»: a
 * gold frame with a ribbon, the tier switch with prices, and the parts —
 * ◆2 100, the pass chests by level, −10% on everything, shards ×1,5 and
 * fragments. Diamond is the same rewards at once and −15%.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlusBundle(
    tier: PlusTier?,
    plus: PlusStatus?,
    busy: Boolean,
    onBuy: (PlusTier) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    nick: String = "Steve",
) {
    val colors = millida
    val mode = plusTileMode(tier)
    var inside by remember { mutableStateOf(false) }
    var pick by rememberSaveable { mutableStateOf(PlusTier.PLUS) }
    val diamond = if (mode == PlusTileMode.Pick) pick == PlusTier.DIAMOND else true
    val shown = if (diamond) PlusTier.DIAMOND else PlusTier.PLUS
    val tone = if (diamond) Rarity.RARE.color else Rarity.LEGENDARY.color
    val label = when (mode) {
        PlusTileMode.Extend -> tr("Продлить Diamond")
        PlusTileMode.Upgrade -> listOf("Diamond", plusPriceText(plus, PlusTier.DIAMOND)).filter { it.isNotEmpty() }.joinToString(" · ")
        PlusTileMode.Pick -> plusPriceText(plus, shown).let { if (it.isEmpty()) tr("Купить") else tr("Купить · {it}", it) }
    }
    val buy = {
        when (mode) {
            PlusTileMode.Extend -> onManage()
            PlusTileMode.Upgrade -> onBuy(PlusTier.DIAMOND)
            PlusTileMode.Pick -> onBuy(shown)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .pixelSurface(
                tone.mixSrgb(colors.surface, 0.14f),
                MillidaCut.plate,
                PixelFrame(outline = tone, outlineWidth = 2.dp, bottom = MillidaPixel.tileBottom, bottomWidth = 5.dp),
            )
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .pixelSurface(tone, MillidaCut.badge)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Text(
                if (diamond) tr("Всё сразу — в день покупки") else tr("Выгоднее, чем просто рубины"),
                style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black),
                color = Rarity.ink,
                maxLines = 1,
            )
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = buy)) {
                MPlusMark(tier = shown, height = if (diamond) 28.dp else 34.dp, shine = true)
            }
            if (mode == PlusTileMode.Pick) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TierSwitch(PlusTier.PLUS, plusPriceText(plus, PlusTier.PLUS), pick == PlusTier.PLUS) { pick = PlusTier.PLUS }
                    TierSwitch(PlusTier.DIAMOND, plusPriceText(plus, PlusTier.DIAMOND), pick == PlusTier.DIAMOND) { pick = PlusTier.DIAMOND }
                }
            }
            Text(
                if (diamond) tr("сразу: ◆{0} + {CHESTS} сундуков", groupDigits(PlusPass.RUBIES), PlusPass.CHESTS) else tr("по дням, 28 дней"),
                style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
                color = if (diamond) colors.fg else colors.fgMuted,
            )
            NickFlip(nick)
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BundlePart(Rarity.MYTHIC.color, now = diamond) {
                ShopAsset(PLUS_RUBIES_ART, 64.dp)
                PartNumber("◆" + groupDigits(PlusPass.RUBIES))
                PartCaption(tr("рубинов"))
            }
            BundlePart(Rarity.EPIC.color, now = diamond) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                    PassChest("RARE", PlusPass.RARE, 34.dp)
                    PassChest("EPIC", PlusPass.EPIC, 34.dp)
                    PassChest("LEGEND", PlusPass.LEGEND, 40.dp)
                }
                PartNumber(tr("{CHESTS} сундуков", PlusPass.CHESTS))
                Box(
                    Modifier
                        .pixelSurface(if (inside) Rarity.EPIC.color else MillidaPixel.revealShade, MillidaCut.badge)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { inside = !inside }
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    Text(tr("что внутри"), style = MillidaType.tabLabel.copy(fontWeight = FontWeight.ExtraBold), color = if (inside) Rarity.ink else colors.fg, maxLines = 1)
                }
            }
            BundlePart(Rarity.UNCOMMON.color) {
                Text("−${tierPct(shown)}%", style = MillidaType.pixelLarge, color = colors.fg, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        groupDigits(EXAMPLE_PRICE),
                        style = MillidaType.pixelSmall.copy(textDecoration = TextDecoration.LineThrough),
                        color = colors.fgFaint,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                    MIcon(MIcons.ruby, size = 13.dp)
                    Spacer(Modifier.width(2.dp))
                    Text(groupDigits(cutPrice(EXAMPLE_PRICE, tierPct(shown))), style = MillidaType.pixelSmall, color = colors.fg, maxLines = 1)
                }
                PartCaption(tr("на всё в магазине"))
            }
            BundlePart(Rarity.RARE.color) {
                CurrencyArt("shard", 48.dp)
                PartNumber("×" + PlusPass.SHARD_BOOST)
                PartCaption(tr("осколки"))
            }
            BundlePart(Rarity.LEGENDARY.color) {
                CurrencyArt(fragmentArt(Rarity.EPIC), 48.dp)
                PartNumber(tr("фрагменты"))
                PartCaption(tr("вещей"))
            }
        }
        if (inside) PassChestsInside()
        MPrimaryButton(
            label,
            onClick = buy,
            modifier = Modifier.fillMaxWidth(),
            icon = if (diamond) MIcons.zap else MIcons.crown,
            size = MButtonSize.Large,
            loading = busy,
        )
    }
}

private class PassDrop(val tier: String, val count: Int, val shards: IntRange, val items: Int, val rubies: IntRange?)

/** What the pass chests usually hold (chestDrops.ts CHEST_DROPS): shards, fragments of how many items, rubies. */
private val PASS_DROPS = listOf(
    PassDrop("RARE", PlusPass.RARE, 10..20, 1, 15..30),
    PassDrop("EPIC", PlusPass.EPIC, 25..40, 2, 30..60),
    PassDrop("LEGEND", PlusPass.LEGEND, 60..90, 3, 60..120),
).filter { it.count > 0 }

@Composable
private fun PassChestsInside() {
    val colors = millida
    Column(
        Modifier
            .fillMaxWidth()
            .pixelSurface(MillidaPixel.revealPlate, MillidaCut.plate)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (drop in PASS_DROPS) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChestArt(drop.tier, side = 30.dp)
                Text("×${drop.count}", modifier = Modifier.width(30.dp), style = MillidaType.small.copy(fontWeight = FontWeight.Black), color = colors.fg, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CurrencyArt("shard", 16.dp)
                    Text("${drop.shards.first}–${drop.shards.last}", style = MillidaType.small.copy(fontWeight = FontWeight.ExtraBold), color = colors.fg, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CurrencyArt(fragmentArt(chestRarity(drop.tier)), 16.dp)
                    Text(trPlural(drop.items, tr("{count} вещь", drop.items), tr("{count} вещи", drop.items), tr("{count} вещей", drop.items)), style = MillidaType.small.copy(fontWeight = FontWeight.ExtraBold), color = colors.fg, maxLines = 1)
                }
                val rubies = drop.rubies
                if (rubies != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CurrencyArt("ruby", 16.dp)
                        Text("${rubies.first}–${rubies.last}", style = MillidaType.small.copy(fontWeight = FontWeight.ExtraBold), color = colors.fg, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun TierSwitch(tier: PlusTier, price: String, on: Boolean, onPick: () -> Unit) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Click)
    val tone = if (tier == PlusTier.DIAMOND) Rarity.RARE.color else Rarity.LEGENDARY.color
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .pixelSurface(
                when {
                    on -> tone.mixSrgb(colors.surface2, 0.30f)
                    pressed -> colors.raised
                    else -> colors.surface2
                },
                MillidaCut.control,
                PixelFrame(outline = if (on) tone else Color.Transparent, outlineWidth = 2.dp),
            )
            .selectable(on, interaction, null, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(if (tier == PlusTier.DIAMOND) MIcons.zap else MIcons.crown, size = 16.dp, color = tone)
        Text(
            if (tier == PlusTier.DIAMOND) "Diamond" else "PLUS",
            style = MillidaType.button.copy(fontSize = 13.sp, fontWeight = FontWeight.Black),
            color = colors.fg,
            maxLines = 1,
        )
        if (price.isNotEmpty()) Text(price, style = MillidaType.small, color = colors.fgMuted, maxLines = 1)
    }
}

@Composable
private fun PassChest(tier: String, count: Int, side: Dp) {
    Box(contentAlignment = Alignment.BottomEnd) {
        ChestArt(tier, side = side)
        Text("×$count", style = MillidaType.pixelSmall, color = millida.fg, maxLines = 1)
    }
}

@Composable
private fun BundlePart(tone: Color, now: Boolean = false, content: @Composable () -> Unit) {
    val colors = millida
    Box {
        Column(
            Modifier
                .heightIn(min = 112.dp)
                .width(118.dp)
                .pixelSurface(tone.mixSrgb(colors.surface2, 0.12f), MillidaCut.plate, colors.plateFrame())
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) { content() }
        if (now) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-6).dp)
                    .pixelSurface(Rarity.RARE.color, MillidaCut.badge)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MIcon(MIcons.zap, size = 11.dp, color = Rarity.ink)
                Text(tr("сразу"), style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black), color = Rarity.ink, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PartNumber(text: String) {
    Text(
        text,
        style = MillidaType.rowTitle.copy(fontWeight = FontWeight.Black, fontSize = 15.sp),
        color = millida.fg,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

@Composable
private fun PartCaption(text: String) {
    Text(text, style = MillidaType.small, color = millida.fgSubtle, textAlign = TextAlign.Center, maxLines = 2)
}

/** Packs by price and the code of the cheapest ruby by live prices. */
internal fun sortPacks(packs: List<RubyPack>): Pair<List<RubyPack>, String?> {
    val list = packs.sortedBy { it.kopecks }
    val best = list.filter { it.kopecks > 0 }.maxByOrNull { it.rubies.toDouble() / it.kopecks }?.code
    return list to best
}

/** What the same rubies cost at the rate of the nearest regular pack, to the ruble: the crossed-out price of a one-time offer. */
internal fun usualKopecks(rubies: Long, packs: List<RubyPack>): Long {
    val usual = packs.filter { !it.oneTime && it.rubies > 0 }
    val near = usual.minByOrNull { abs(it.rubies - rubies) } ?: return 0
    return (rubies * near.kopecks / near.rubies.toDouble() / 100.0).roundToLong() * 100
}

/** The desktop's ruby packs: a one-time starter first with its ribbon, then the packs from small to large, «выгодно» on the best rate. */
@Composable
internal fun PackGrid(
    packs: List<RubyPack>?,
    columns: Int,
    isBusy: (String) -> Boolean,
    onBuy: (RubyPack) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (packs == null) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(columns) { MSkeleton(height = 170.dp, modifier = Modifier.weight(1f)) }
        }
        return
    }
    val starter = packs.firstOrNull { it.oneTime }
    val (list, best) = sortPacks(packs.filterNot { it.oneTime })
    val tiles = listOfNotNull(starter) + list
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { pack ->
                    val rank = list.indexOf(pack).coerceAtLeast(0)
                    PackTile(
                        pack = pack,
                        promo = pack == starter,
                        was = if (pack == starter) usualKopecks(pack.rubies, list) else 0,
                        best = pack.code == best,
                        art = if (pack == starter) STARTER_ART else packArtAt(rank, list.size),
                        busy = isBusy("pack:" + pack.code),
                        onBuy = { onBuy(pack) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun PackTile(
    pack: RubyPack,
    promo: Boolean,
    was: Long,
    best: Boolean,
    art: String,
    busy: Boolean,
    onBuy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .pixelSurface(
                    if (promo) Rarity.LEGENDARY.color.mixSrgb(colors.surface2, 0.14f) else colors.surface2,
                    MillidaCut.plate,
                    if (promo) PixelFrame(outline = Rarity.LEGENDARY.color, outlineWidth = 2.dp, bottom = MillidaPixel.tileBottom, bottomWidth = 4.dp) else colors.plateFrame(),
                )
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !busy, role = Role.Button, onClick = onBuy)
                .padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.height(100.dp), contentAlignment = Alignment.Center) {
                ShopAsset(art, 96.dp)
            }
            MPrimaryButton(
                "+${groupDigits(pack.rubies)}",
                onClick = onBuy,
                modifier = Modifier.fillMaxWidth(),
                icon = MIcons.ruby,
                size = MButtonSize.Small,
                loading = busy,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (was > pack.kopecks) {
                    Text(
                        rubles(was),
                        style = MillidaType.small.copy(textDecoration = TextDecoration.LineThrough),
                        color = colors.fgFaint,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(rubles(pack.kopecks), style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = colors.fgMuted, maxLines = 1)
            }
        }
        val ribbon = when {
            promo -> tr("Акция · один раз")
            best -> tr("выгодно")
            else -> null
        }
        if (ribbon != null) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-8).dp)
                    .pixelSurface(Rarity.LEGENDARY.color, MillidaCut.badge)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(ribbon, style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black), color = Rarity.ink, maxLines = 1)
            }
        }
    }
}

/**
 * The month offer, right before «Скидки дня»: a one-time pack until the end of
 * the month — the heap of rubies, «Один раз», the countdown and the price with
 * the usual one crossed out.
 */
@Composable
internal fun MonthOfferCard(
    offer: MonthOffer,
    packs: List<RubyPack>,
    busy: Boolean,
    onBuy: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val was = usualKopecks(offer.rubies, packs)
    val ends = remember(offer.endsAt) { parseInstant(offer.endsAt)?.toEpochMilli() }
    Row(
        modifier
            .fillMaxWidth()
            .pixelSurface(
                Rarity.MYTHIC.color.mixSrgb(colors.surface, 0.14f),
                MillidaCut.plate,
                PixelFrame(outline = Rarity.MYTHIC.color, outlineWidth = 2.dp, bottom = MillidaPixel.tileBottom, bottomWidth = 5.dp),
            )
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !busy, role = Role.Button, onClick = onBuy)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 112.dp, height = 96.dp)
                .pixelSurface(MillidaPixel.revealShade, MillidaCut.control),
            contentAlignment = Alignment.Center,
        ) {
            ShopAsset(MONTH_ART, 104.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .pixelSurface(Rarity.MYTHIC.color, MillidaCut.badge)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(tr("Один раз"), style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black), color = MillidaPixel.white, maxLines = 1)
                }
                if (ends != null) MDayClock(ends, onEnd = onEnd)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(groupDigits(offer.rubies), style = MillidaType.pixelLarge.copy(fontSize = 30.sp), color = colors.fg, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                MIcon(MIcons.ruby, size = 30.dp)
            }
            Text(offer.title, style = MillidaType.small, color = colors.fgSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (was > offer.kopecks) {
                    Text(
                        rubles(was),
                        style = MillidaType.small.copy(textDecoration = TextDecoration.LineThrough),
                        color = colors.fgFaint,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                MPrimaryButton(rubles(offer.kopecks), onClick = onBuy, size = MButtonSize.Medium, loading = busy)
            }
        }
    }
}

/** The two tiers side by side in the purchase window: PLUS gives the rewards day by day, Diamond all at once. */
@Composable
internal fun PlanCard(tier: PlusTier, price: String, waiting: Boolean, busy: Boolean, onBuy: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    val diamond = tier == PlusTier.DIAMOND
    val tone = if (diamond) Rarity.RARE.color else Rarity.LEGENDARY.color
    Column(
        modifier
            .pixelSurface(tone.mixSrgb(colors.surface2, 0.14f), MillidaCut.plate, PixelFrame(outline = tone, outlineWidth = 2.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (diamond) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.zap, size = 14.dp, color = tone)
                Spacer(Modifier.width(4.dp))
                Text(tr("сразу всё"), style = MillidaType.small.copy(fontWeight = FontWeight.Black), color = tone)
            }
        }
        MPlusMark(tier = tier, height = if (diamond) 30.dp else 40.dp, shine = true)
        Text(
            tr("◆{0} из пропуска + {CHESTS} сундуков + осколки ×{SHARD_BOOST} + −{1}% на всё + фрагменты вещей", groupDigits(PlusPass.RUBIES), PlusPass.CHESTS, PlusPass.SHARD_BOOST, tierPct(tier)),
            style = MillidaType.small,
            color = colors.fgMuted,
            textAlign = TextAlign.Center,
        )
        Text(
            if (diamond) tr("+ сразу всё") else tr("награды по дням, 28 дней"),
            style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
            color = if (diamond) tone else colors.fgSubtle,
        )
        val label = when {
            waiting -> tr("Открыть оплату")
            price.isEmpty() -> if (diamond) "Diamond" else "PLUS"
            else -> (if (diamond) "Diamond " else "PLUS ") + price
        }
        if (diamond) {
            MSecondaryButton(label, onClick = onBuy, modifier = Modifier.fillMaxWidth(), icon = MIcons.zap, loading = busy)
        } else {
            MPrimaryButton(label, onClick = onBuy, modifier = Modifier.fillMaxWidth(), icon = MIcons.crown, loading = busy)
        }
    }
}
