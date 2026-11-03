package net.millida.launcher.ui.shop

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.rubies.RubyItem
import net.millida.launcher.core.rubies.ShopCard
import net.millida.launcher.core.rubies.ShopDay
import net.millida.launcher.core.rubies.ShopSource
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCardRail
import net.millida.launcher.ui.MDayClock
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRarityFrame
import net.millida.launcher.ui.MRarityPlate
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface

internal val RAIL_TILE_WIDTH = 168.dp

internal class SaleActs(
    val balance: Long?,
    val isBusy: (String) -> Boolean,
    val onBuy: (ShopCard, ShopSource) -> Unit,
    val onWear: (RubyItem) -> Unit,
)

/** The day's cards in the desktop's order: the item of the day, the deal, the day's shelf, then «для тебя». */
internal fun dayCards(day: ShopDay): List<Pair<ShopCard, ShopSource>> = buildList {
    day.day.featured?.let { add(it to ShopSource.FEATURED) }
    day.day.deal?.let { add(it to ShopSource.DEAL) }
    day.day.items.forEach { add(it to ShopSource.DAY) }
    day.day.forYou.forEach { add(it to ShopSource.FOR_YOU) }
}

/** «Хочу» shows only wished cards still on sale today, the desktop's short row. */
internal fun wishedCards(day: ShopDay): List<Pair<ShopCard, ShopSource>> {
    val wished = day.wishlist.toSet()
    if (wished.isEmpty()) return emptyList()
    val night = day.nightMarket?.cards.orEmpty().map { it to ShopSource.NIGHT }
    val seen = HashSet<String>()
    return (dayCards(day) + night).filter { (card) -> card.item.code in wished && !card.owned && seen.add(card.item.code) }
}

/**
 * The desktop's sale card: the picture with −N% in the corner, the rarity, the
 * name and one price button; an owned item says «Надеть». The item of the day
 * wears a gold frame.
 */
@Composable
internal fun SaleTile(card: ShopCard, source: ShopSource, acts: SaleActs, modifier: Modifier = Modifier, timer: Boolean = false) {
    val colors = millida
    val item = card.item
    val rarity = Rarity.of(item.rarity)
    val busy = acts.isBusy("buy:" + item.code)
    MRarityFrame(
        rarity = if (source == ShopSource.FEATURED) Rarity.LEGENDARY else rarity,
        modifier = modifier.fillMaxWidth(),
        onClick = if (card.owned || busy) null else ({ acts.onBuy(card, source) }),
        contentPadding = 10.dp,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f), contentAlignment = Alignment.Center) {
                PlayerShot(listOf(item), rarity?.color ?: colors.accent, Modifier.matchParentSize().pixelSurface(colors.inset, MillidaCut.control)) {
                    ItemArt(item, side = 96.dp)
                }
                if (card.discountPct > 0 && !card.owned) DiscountBadge(card.discountPct, Modifier.align(Alignment.TopStart))
                if (source == ShopSource.FOR_YOU) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .pixelSurface(colors.surface2, MillidaCut.badge)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(tr("для тебя"), style = MillidaType.tabLabel, color = colors.fgMuted, maxLines = 1)
                    }
                }
                val leaves = remember(card.leavesAt) { parseInstant(card.leavesAt)?.toEpochMilli() }
                if (timer && leaves != null) MDayClock(leaves, Modifier.align(Alignment.BottomStart))
            }
            if (rarity != null) MRarityPlate(rarity)
            Text(
                item.name,
                style = MillidaType.rowTitle.copy(fontSize = 14.sp, lineHeight = 17.sp),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.owned) {
                MSecondaryButton(tr("Надеть"), onClick = { acts.onWear(item) }, modifier = Modifier.fillMaxWidth(), size = MButtonSize.Small)
            } else {
                PriceButton(card.price, card.basePrice, acts.balance, busy, onClick = { acts.onBuy(card, source) })
            }
        }
    }
}

/** «◆1 020» with the price before discounts crossed out beside it; calm when the rubies do not cover it. */
@Composable
internal fun PriceButton(
    price: Long,
    base: Long,
    balance: Long?,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shards: Boolean = false,
    enabled: Boolean = true,
    size: MButtonSize = MButtonSize.Small,
) {
    val colors = millida
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val icon = if (shards) MIcons.shard else MIcons.ruby
        val affordable = balance == null || price <= balance
        if (affordable) {
            MPrimaryButton(groupDigits(price), onClick = onClick, modifier = Modifier.weight(1f), icon = icon, size = size, loading = busy, enabled = enabled)
        } else {
            MSecondaryButton(groupDigits(price), onClick = onClick, modifier = Modifier.weight(1f), icon = icon, size = size, loading = busy, enabled = enabled)
        }
        if (base > price) {
            Spacer(Modifier.width(6.dp))
            Text(
                groupDigits(base),
                style = MillidaType.pixelSmall.copy(textDecoration = TextDecoration.LineThrough),
                color = colors.fgFaint,
                maxLines = 1,
            )
        }
    }
}

/** A row of sale cards scrolled sideways, the desktop's `CardRail`. */
@Composable
internal fun SaleRail(
    cards: List<Pair<ShopCard, ShopSource>>,
    label: String,
    acts: SaleActs,
    modifier: Modifier = Modifier,
    auto: Boolean = false,
) {
    MCardRail(
        entries = cards,
        key = { (card, source) -> source.wire + card.item.code },
        label = label,
        modifier = modifier,
        cardWidth = RAIL_TILE_WIDTH,
        auto = auto,
    ) { (card, source) ->
        SaleTile(card, source, acts, timer = auto)
    }
}

@Composable
internal fun SecClock(iso: String, onEnd: () -> Unit) {
    val at = remember(iso) { parseInstant(iso)?.toEpochMilli() } ?: return
    MDayClock(at, onEnd = onEnd)
}

@Composable
internal fun SecTitleNote(text: String) {
    Text(text, style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = millida.fgSubtle, maxLines = 1)
}
