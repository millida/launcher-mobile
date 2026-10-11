package net.millida.launcher.ui.shop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.millida.launcher.RewardKind
import net.millida.launcher.core.cosmetics.DuoEmotes
import net.millida.launcher.core.rubies.RubyPack
import net.millida.launcher.core.rubies.ShopCard
import net.millida.launcher.core.rubies.ShopGift
import net.millida.launcher.core.rubies.ShopSource
import net.millida.launcher.core.rubies.Workshop
import net.millida.launcher.core.rubies.WorkshopFragment
import net.millida.launcher.core.rubies.WorkshopOffer
import net.millida.launcher.core.rubies.XrayOffer
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MProgressBar
import net.millida.launcher.ui.MRarityFrame
import net.millida.launcher.ui.MRarityPlate
import net.millida.launcher.ui.MRubyAmount
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin

private const val DROP_STEPS = 6
private const val DROP_STEP_MS = 90L
private const val BUMP_MS = 240

internal fun slotName(slot: String): String = when (slot.uppercase()) {
    "HAT", "HEAD", "HELMET" -> "Голова"
    "WINGS" -> "Крылья"
    "CAPE", "CLOAK" -> "Плащ"
    "BACK", "BACKPACK" -> "Спина"
    "EARS" -> "Уши"
    "GLASSES", "FACE", "MASK" -> "Лицо"
    "PET" -> "Питомец"
    "EMOTE" -> "Эмоция"
    "AURA", "EFFECT", "PARTICLE" -> "Эффект"
    "BODY", "SHIRT" -> "Тело"
    "SHOES", "FEET" -> "Обувь"
    "HAND" -> "В руку"
    else -> ""
}

@Composable
internal fun WalletHeader(
    balance: Long?,
    shards: Long?,
    failed: Boolean,
    onTopUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    MCard(modifier = modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when {
                    balance != null -> CountingRubies(balance)
                    failed -> Text("—", style = MillidaType.pixelLarge, color = colors.fgSubtle, maxLines = 1)
                    else -> MSkeleton(height = 28.dp, modifier = Modifier.width(120.dp))
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MIcon(MIcons.shard, size = 14.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (shards == null) "—" else "${groupDigits(shards)} ${shardWord(shards)}",
                        style = MillidaType.small,
                        color = colors.fgSubtle,
                        maxLines = 1,
                    )
                }
            }
            MPrimaryButton("Пополнить", onClick = onTopUp, icon = MIcons.plus)
        }
    }
}

/**
 * The wallet in the landscape strip, the desktop's `.sh-wallet2` next to
 * «← Лобби»: rubies and shards as plates and «Пополнить» beside them.
 */
@Composable
internal fun StripWallet(balance: Long?, shards: Long?, onTopUp: () -> Unit) {
    val colors = millida
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (balance != null) {
            MRubyAmount(balance, plate = true)
        } else {
            MSkeleton(height = 40.dp, modifier = Modifier.width(90.dp))
        }
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .pixelSurface(colors.surface2, MillidaCut.control, colors.plateFrame())
                .padding(start = 10.dp, end = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MIcon(MIcons.shard, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                if (shards == null) "—" else groupDigits(shards),
                style = MillidaType.pixel,
                color = colors.fg,
                maxLines = 1,
                softWrap = false,
            )
        }
        MPrimaryButton("Пополнить", onClick = onTopUp, icon = MIcons.plus, size = MButtonSize.Small)
    }
}

@Composable
private fun CountingRubies(balance: Long) {
    val shown by rememberCounter(balance)
    val reduced = LocalReducedMotion.current
    val bump = remember { Animatable(1f) }
    val previous = remember { mutableLongStateOf(balance) }
    LaunchedEffect(balance) {
        val grew = balance > previous.longValue
        previous.longValue = balance
        if (grew && !reduced) {
            bump.snapTo(0f)
            bump.animateTo(1f, tween(BUMP_MS, easing = MillidaMotion.steps(3)))
        }
    }
    MRubyAmount(
        shown,
        modifier = Modifier.graphicsLayer {
            val lift = sin(PI.toFloat() * bump.value)
            scaleX = 1f + 0.1f * lift
            scaleY = 1f + 0.1f * lift
        },
        style = MillidaType.pixelLarge,
    )
}

@Composable
internal fun ShelfTimer(label: String, iso: String, onElapsed: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    val at = remember(iso) { parseInstant(iso)?.toEpochMilli() } ?: return
    val now by rememberNow(20_000L)
    val left = at - now
    val elapsed by rememberUpdatedState(onElapsed)
    LaunchedEffect(at) { refreshWhenDue(at) { elapsed() } }
    Row(
        modifier
            .pixelSurface(colors.chipFill, MillidaCut.badge)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(MIcons.clock, size = 12.dp, color = colors.fgSubtle)
        Spacer(Modifier.width(5.dp))
        Text("$label ", style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
        Text(leftLabel(max(0L, left)), style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = colors.fg, maxLines = 1)
    }
}

@Composable
internal fun ShelfHeader(title: String, timerLabel: String?, timerAt: String?, onElapsed: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MillidaType.sectionTitle,
            color = millida.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (timerLabel != null && !timerAt.isNullOrBlank()) ShelfTimer(timerLabel, timerAt, onElapsed)
    }
}

@Composable
internal fun PriceTag(price: Long, base: Long, modifier: Modifier = Modifier, big: Boolean = false) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    val off = base > price
    val shown = remember(price, base) { mutableLongStateOf(if (off && !reduced) base else price) }
    LaunchedEffect(price, base) {
        if (!off || reduced) {
            shown.longValue = price
            return@LaunchedEffect
        }
        for (step in 1..DROP_STEPS) {
            delay(DROP_STEP_MS)
            shown.longValue = if (step >= DROP_STEPS) {
                price
            } else {
                ((base - (base - price) * step / DROP_STEPS.toDouble()) / 10.0).roundToLong() * 10
            }
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MIcon(MIcons.ruby, size = if (big) 20.dp else 15.dp)
        Spacer(Modifier.width(5.dp))
        Text(
            groupDigits(shown.longValue),
            style = if (big) MillidaType.pixelLarge else MillidaType.pixel,
            color = colors.fg,
            maxLines = 1,
        )
        if (off) {
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

@Composable
internal fun OwnedMark(modifier: Modifier = Modifier) {
    val colors = millida
    MBadge("Куплено", color = colors.accentText, background = colors.accentSoft, modifier = modifier, icon = MIcons.check)
}

@Composable
internal fun WishButton(wished: Boolean, onWish: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    MIconButton(
        icon = if (wished) MIcons.heart else MIcons.heartOff,
        onClick = { onWish(!wished) },
        modifier = modifier,
        tint = if (wished) MillidaPixel.counter else colors.fgSubtle,
        background = colors.surface,
        label = if (wished) "Убрать из «Хочу»" else "Хочу",
    )
}

@Composable
internal fun DiscountBadge(percent: Int, modifier: Modifier = Modifier, big: Boolean = false) {
    Box(
        modifier
            .pixelSurface(MillidaPixel.counter, MillidaCut.badge, PixelFrame(bottom = MillidaPixel.pressBottom, bottomWidth = 2.dp))
            .padding(horizontal = if (big) 8.dp else 6.dp, vertical = 3.dp),
    ) {
        Text(
            "−$percent%",
            style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black, fontSize = if (big) 14.sp else 11.5.sp),
            color = MillidaPixel.white,
            maxLines = 1,
        )
    }
}

/** Calm when the rubies do not cover the price: the tap then says how many are missing and leads to the packs. */
@Composable
internal fun BuyButton(
    price: Long,
    balance: Long?,
    busy: Boolean,
    onBuy: () -> Unit,
    modifier: Modifier = Modifier,
    size: MButtonSize = MButtonSize.Medium,
) {
    if (balance != null && price <= balance) {
        MPrimaryButton("Купить", onClick = onBuy, modifier = modifier, size = size, loading = busy)
    } else {
        MSecondaryButton("Купить", onClick = onBuy, modifier = modifier, size = size, loading = busy)
    }
}

@Composable
internal fun FeaturedCard(
    card: ShopCard,
    wished: Boolean,
    busy: Boolean,
    balance: Long?,
    onBuy: () -> Unit,
    onTry: () -> Unit,
    onWish: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val item = card.item
    val rarity = Rarity.of(item.rarity)
    MRarityFrame(rarity = rarity, modifier = modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                ItemArt(item, side = 116.dp)
                if (card.discountPct > 0) DiscountBadge(card.discountPct, Modifier.align(Alignment.TopStart), big = true)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        item.name,
                        modifier = Modifier.weight(1f),
                        style = MillidaType.tileTitle.copy(fontSize = 18.sp, lineHeight = 21.sp),
                        color = colors.fg,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!card.owned) WishButton(wished, onWish, Modifier.offset(x = 4.dp, y = (-4).dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rarity != null) MRarityPlate(rarity)
                    val slot = slotName(item.slot)
                    if (slot.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(slot, style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
                    }
                }
                FragmentsLine(card, rarity)
                PriceTag(card.price, card.basePrice, big = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (card.owned) {
                        OwnedMark()
                    } else {
                        BuyButton(card.price, balance, busy, onBuy, Modifier.weight(1f))
                    }
                    MSecondaryButton("Примерить", onClick = onTry, modifier = if (card.owned) Modifier else Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FragmentsLine(card: ShopCard, rarity: Rarity?) {
    val fragments = card.fragments ?: return
    if (card.owned || fragments.need <= 0) return
    val colors = millida
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        MProgressBar(fragments.have.toFloat() / fragments.need, color = rarity?.color ?: colors.accent)
        Text(
            "Фрагменты ${fragments.have}/${fragments.need}" + if (fragments.off > 0) " · −${groupDigits(fragments.off)}" else "",
            style = MillidaType.small,
            color = colors.fgSubtle,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ItemTile(
    card: ShopCard,
    wished: Boolean,
    busy: Boolean,
    balance: Long?,
    onBuy: () -> Unit,
    onWish: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val item = card.item
    val rarity = Rarity.of(item.rarity)
    MRarityFrame(rarity = rarity, modifier = modifier, contentPadding = 10.dp) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ItemArt(item, side = 92.dp)
                if (card.discountPct > 0) DiscountBadge(card.discountPct, Modifier.align(Alignment.TopStart))
                if (!card.owned) WishButton(wished, onWish, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp))
            }
            Text(
                item.name,
                style = MillidaType.rowTitle.copy(fontSize = 14.sp, lineHeight = 17.sp),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.fragments != null && !card.owned) {
                FragmentsLine(card, rarity)
            } else if (rarity != null) {
                MRarityPlate(rarity)
            }
            if (DuoEmotes.isDuo(item.code)) {
                MBadge("Вдвоём", color = colors.fgMuted, background = colors.surface2, icon = MIcons.users)
            }
            PriceTag(card.price, card.basePrice)
            if (card.owned) {
                OwnedMark()
            } else {
                BuyButton(card.price, balance, busy, onBuy, Modifier.fillMaxWidth(), MButtonSize.Small)
            }
        }
    }
}

/** Two tiles a row: a phone has room for two readable cards, not four stamps. */
@Composable
internal fun ShelfRow(
    cards: List<Pair<ShopCard, ShopSource>>,
    balance: Long?,
    wishlist: List<String>,
    isBusy: (String) -> Boolean,
    onBuy: (ShopCard, ShopSource) -> Unit,
    onWish: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 2,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((card, source) in cards) {
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
        repeat(columns - cards.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
internal fun GiftTile(gift: ShopGift, busy: Boolean, onClaim: () -> Unit, onElapsed: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    Column(
        modifier
            .pixelSurface(
                fill = colors.accent.mixSrgb(colors.surface, 0.14f),
                cut = MillidaCut.plate,
                frame = PixelFrame(outline = colors.accent, outlineWidth = 2.dp, bottom = MillidaPixel.tileBottom, bottomWidth = 5.dp),
            )
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("БЕСПЛАТНО", style = MillidaType.caption, color = colors.accentText, maxLines = 1)
        RewardGlyph(gift.kind, side = 72.dp, item = gift.item, alpha = if (gift.claimed) 0.45f else 1f)
        Text(
            when (gift.kind) {
                RewardKind.RUBIES -> "+${groupDigits(gift.amount)} ${rubyWord(gift.amount)}"
                RewardKind.SHARDS -> "+${groupDigits(gift.amount)} ${shardWord(gift.amount)}"
                else -> gift.item?.name ?: "Подарок"
            },
            style = MillidaType.rowTitle.copy(fontSize = 14.sp),
            color = colors.fg,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        if (gift.claimed) {
            ShelfTimer("Завтра", gift.refreshAt, onElapsed)
        } else {
            MPrimaryButton("Забрать", onClick = onClaim, modifier = Modifier.fillMaxWidth(), size = MButtonSize.Small, loading = busy)
        }
    }
}

@Composable
internal fun StoreSkeleton(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MSkeleton(height = 150.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MSkeleton(height = 230.dp, modifier = Modifier.weight(1f))
            MSkeleton(height = 230.dp, modifier = Modifier.weight(1f))
        }
    }
}

/** Packs are paid from the Millida wallet; "Выгодно" sits on the cheapest ruby by live prices, not on what we want to sell. */
@Composable
internal fun PacksCard(
    packs: List<RubyPack>,
    walletKopecks: Long?,
    isBusy: (String) -> Boolean,
    onBuy: (RubyPack) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 2,
) {
    val colors = millida
    val best = packs.filter { it.kopecks > 0 }.maxByOrNull { it.rubies.toDouble() / it.kopecks }?.code
    MCard(modifier = modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Рубины", modifier = Modifier.weight(1f), style = MillidaType.sectionTitle, color = colors.fg)
            if (walletKopecks != null) {
                Text("На счёте ${rubles(walletKopecks)}", style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (pair in packs.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (pack in pair) {
                        PackTile(pack, best = pack.code == best, busy = isBusy("pack:" + pack.code), onBuy = { onBuy(pack) }, modifier = Modifier.weight(1f))
                    }
                    repeat(columns - pair.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PackTile(pack: RubyPack, best: Boolean, busy: Boolean, onBuy: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    val bonus = if (pack.bonus > 0) pack.bonus else max(0L, pack.rubies - pack.kopecks * 7 / 100)
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .pixelSurface(colors.surface2, MillidaCut.plate, colors.plateFrame())
                .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MIcon(MIcons.ruby, size = 44.dp)
            MRubyAmount(pack.rubies)
            Text(
                if (bonus > 0) "+${groupDigits(bonus)} сверху" else pack.title,
                style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
                color = if (bonus > 0) colors.accentText else colors.fgSubtle,
                maxLines = 1,
            )
            MSecondaryButton(rubles(pack.kopecks), onClick = onBuy, modifier = Modifier.fillMaxWidth(), size = MButtonSize.Small, loading = busy)
        }
        if (best) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-6).dp, y = (-8).dp)
                    .pixelSurface(Rarity.LEGENDARY.color, MillidaCut.badge)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text("Выгодно", style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black), color = Rarity.ink, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun WorkshopCard(
    workshop: Workshop,
    isBusy: (String) -> Boolean,
    onCraft: (WorkshopOffer) -> Unit,
    modifier: Modifier = Modifier,
    onTopUp: (WorkshopFragment) -> Unit = {},
    columns: Int = 2,
) {
    val colors = millida
    MCard(modifier = modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Мастерская", modifier = Modifier.weight(1f), style = MillidaType.sectionTitle, color = colors.fg)
            MIcon(MIcons.shard, size = 16.dp)
            Spacer(Modifier.width(5.dp))
            Text(groupDigits(workshop.shards), style = MillidaType.pixel, color = colors.fg)
        }
        val today = workshop.today
        val rotates = dayMonth(workshop.workshop.rotatesAt)
        val line = buildList {
            if (today != null && today.limit > 0) add("сегодня ${today.earned} из ${today.limit}")
            if (rotates != null) add("новые $rotates")
        }
        if (line.isNotEmpty()) {
            Text(line.joinToString(" · ").replaceFirstChar { it.uppercase() }, style = MillidaType.small, color = colors.fgFaint)
        }
        for (started in workshop.fragments.take(3)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ItemArt(started.item, side = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(started.item.name, style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MProgressBar(
                        if (started.need > 0) started.have.toFloat() / started.need else 0f,
                        color = Rarity.of(started.item.rarity)?.color ?: colors.accent,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("${started.have}/${started.need}", style = MillidaType.pixelSmall, color = colors.fgSubtle)
                if (started.topUp > 0) {
                    Spacer(Modifier.width(8.dp))
                    MPrimaryButton(
                        groupDigits(started.topUp),
                        onClick = { onTopUp(started) },
                        icon = MIcons.ruby,
                        size = MButtonSize.Small,
                        loading = isBusy("fragments:" + started.item.code),
                    )
                }
            }
        }
        val offers = workshop.workshop.items
        if (offers.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (pair in offers.chunked(columns)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (offer in pair) {
                            WorkshopTile(
                                offer = offer,
                                affordable = workshop.shards >= offer.cost,
                                busy = isBusy("craft:" + offer.item.code),
                                onCraft = { onCraft(offer) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - pair.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkshopTile(offer: WorkshopOffer, affordable: Boolean, busy: Boolean, onCraft: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    val rarity = Rarity.of(offer.item.rarity)
    MRarityFrame(rarity = rarity, modifier = modifier, contentPadding = 10.dp) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ItemArt(offer.item, side = 76.dp)
            }
            Text(
                offer.item.name,
                style = MillidaType.rowTitle.copy(fontSize = 14.sp, lineHeight = 17.sp),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.shard, size = 15.dp)
                Spacer(Modifier.width(5.dp))
                Text(groupDigits(offer.cost), style = MillidaType.pixel, color = if (affordable) colors.fg else colors.fgFaint)
            }
            if (offer.owned) {
                MBadge("Есть", color = colors.accentText, background = colors.accentSoft, icon = MIcons.check)
            } else {
                MSecondaryButton(
                    "Обменять",
                    onClick = onCraft,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = affordable,
                    size = MButtonSize.Small,
                    loading = busy,
                )
            }
        }
    }
}

/**
 * The X-ray case leads "Для тебя": the item inside is shown before paying and
 * the price is fixed. After a purchase the next case comes on a timer.
 */
@Composable
internal fun XrayCard(
    offer: XrayOffer,
    balance: Long?,
    busy: Boolean,
    onBuy: () -> Unit,
    onElapsed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val item = offer.item
    val rarity = Rarity.of(item.rarity)
    val now by rememberNow(20_000L)
    val nextAt = remember(offer.nextAt) { parseInstant(offer.nextAt)?.toEpochMilli() }
    val waiting = nextAt != null && nextAt > now
    MRarityFrame(rarity = rarity, modifier = modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chest3D(
                tier = offer.tier,
                mode = if (waiting) ChestMode.Closed else ChestMode.Ready,
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.eye, size = 14.dp, color = colors.fgSubtle)
                Spacer(Modifier.width(6.dp))
                Text("РЕНТГЕН-КЕЙС", style = MillidaType.caption, color = colors.fgSubtle, maxLines = 1)
            }
            if (waiting) {
                Text(
                    "Открыт: ${item.name}",
                    style = MillidaType.rowTitle.copy(fontSize = 15.sp),
                    color = colors.fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                offer.nextAt?.let { ShelfTimer("Новый через", it, onElapsed) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ItemArt(item, side = 52.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            item.name,
                            style = MillidaType.rowTitle.copy(fontSize = 15.sp),
                            color = colors.fg,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (rarity != null) MRarityPlate(rarity)
                    }
                }
                PriceTag(offer.price, offer.price, big = true)
                BuyButton(offer.price, balance, busy, onBuy, Modifier.fillMaxWidth())
                ShelfTimer("Сменится через", offer.expiresAt, onElapsed)
            }
        }
    }
}
