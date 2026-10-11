package net.millida.launcher.ui.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.core.rubies.ShopCard
import net.millida.launcher.core.rubies.ShopSource
import net.millida.launcher.core.rubies.WishEntry
import net.millida.launcher.core.rubies.WishList
import net.millida.launcher.core.rubies.WishStatus
import net.millida.launcher.core.rubies.Wishes
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRarityFrame
import net.millida.launcher.ui.MRarityPlate
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida

/** The head of «Хочу»: the sum for everything that is bought with rubies, and the exact top-up for what is missing. */
@Composable
internal fun WishlistHead(
    list: WishList,
    busy: Boolean,
    onTopUp: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Хочу · ${list.items.size}",
                modifier = Modifier.weight(1f),
                style = MillidaType.sectionTitle,
                color = colors.fg,
                maxLines = 1,
            )
            MIcon(MIcons.ruby, size = 18.dp)
            Spacer(Modifier.width(5.dp))
            Text(groupDigits(list.total), style = MillidaType.pixel, color = colors.fg, maxLines = 1)
            Spacer(Modifier.width(5.dp))
            Text("всё сразу", style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
        }
        if (list.missing > 0) {
            val rubies = Wishes.topUpRubies(list.missing)
            MPrimaryButton(
                "Докупить · ${rubles(kopecksForRubies(rubies))}",
                onClick = { onTopUp(rubies) },
                modifier = Modifier.fillMaxWidth(),
                loading = busy,
            )
        } else if (list.total > 0) {
            MBadge("Хватает", color = colors.accentText, background = colors.accentSoft, icon = MIcons.check)
        }
    }
}

@Composable
internal fun WishRow(
    entries: List<WishEntry>,
    wishlist: List<String>,
    balance: Long?,
    shards: Long?,
    isBusy: (String) -> Boolean,
    onBuy: (ShopCard, ShopSource) -> Unit,
    onCraft: (WishEntry) -> Unit,
    onWish: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 2,
) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (entry in entries) {
            WishTile(
                entry = entry,
                wished = entry.item.code in wishlist,
                balance = balance,
                shards = shards,
                isBusy = isBusy,
                onBuy = onBuy,
                onCraft = { onCraft(entry) },
                onWish = { on -> onWish(entry.item.code, on) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        repeat(columns - entries.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** One item from «Хочу» and its single next step: buy, exchange, wait or play. */
@Composable
private fun WishTile(
    entry: WishEntry,
    wished: Boolean,
    balance: Long?,
    shards: Long?,
    isBusy: (String) -> Boolean,
    onBuy: (ShopCard, ShopSource) -> Unit,
    onCraft: () -> Unit,
    onWish: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val item = entry.item
    val rarity = Rarity.of(item.rarity)
    val now by rememberNow(60_000L)
    MRarityFrame(rarity = rarity, modifier = modifier, contentPadding = 10.dp) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ItemArt(item, side = 92.dp)
                if (entry.status == WishStatus.SHOP) {
                    MBadge("В магазине", color = colors.accentText, background = colors.accentSoft, modifier = Modifier.align(Alignment.TopStart))
                }
                if (entry.status != WishStatus.OWNED) {
                    WishButton(wished, onWish, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp))
                }
            }
            Text(
                item.name,
                style = MillidaType.rowTitle.copy(fontSize = 14.sp, lineHeight = 17.sp),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (rarity != null) MRarityPlate(rarity)
            Spacer(Modifier.weight(1f))
            val card = entry.card
            when (entry.status) {
                WishStatus.SHOP -> if (card != null) {
                    PriceTag(card.price, card.basePrice)
                    BuyButton(
                        card.price,
                        balance,
                        isBusy("buy:" + item.code),
                        { onBuy(card, entry.source ?: ShopSource.DAY) },
                        Modifier.fillMaxWidth(),
                        MButtonSize.Small,
                    )
                }
                WishStatus.WORKSHOP -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MIcon(MIcons.shard, size = 15.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(groupDigits(entry.cost), style = MillidaType.pixel, color = colors.fg)
                    }
                    val enough = shards != null && entry.cost <= shards
                    val busy = isBusy("craft:" + item.code)
                    if (enough) {
                        MPrimaryButton("Обменять", onClick = onCraft, modifier = Modifier.fillMaxWidth(), size = MButtonSize.Small, loading = busy)
                    } else {
                        MSecondaryButton("Обменять", onClick = onCraft, modifier = Modifier.fillMaxWidth(), size = MButtonSize.Small, loading = busy)
                    }
                }
                WishStatus.RETURNS -> WishState(
                    MIcons.clock,
                    entry.returnsAt?.let { "через ${Wishes.daysUntil(it, now)} дн." } ?: "Вернётся",
                )
                WishStatus.EARN -> WishState(MIcons.trophy, "За игру")
                WishStatus.OWNED -> MBadge("Есть", color = colors.accentText, background = colors.accentSoft, icon = MIcons.check)
            }
        }
    }
}

@Composable
private fun WishState(icon: MillidaIcon, text: String) {
    val colors = millida
    Row(verticalAlignment = Alignment.CenterVertically) {
        MIcon(icon, size = 14.dp, color = colors.fgSubtle)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
    }
}
