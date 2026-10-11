package net.millida.launcher.ui.shop

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.core.rubies.NightFlips
import net.millida.launcher.core.rubies.ShopCard
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRarityFrame
import net.millida.launcher.ui.MRarityPlate
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import kotlin.math.max

private const val FLIPS_STORE = "shop"
private const val FLIPS_KEY = "night-flipped"
private const val FLIP_MS = 420
private const val HALF_TURN = 180f
private const val QUARTER_TURN = 90f

/** Turned cards survive a restart for the same market: the reveal is a view of known data, not a roll. */
internal class NightFlipStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FLIPS_STORE, Context.MODE_PRIVATE)

    fun read(endsAt: String): Set<String> = NightFlips.decode(prefs.getString(FLIPS_KEY, null), endsAt)

    fun write(endsAt: String, codes: Set<String>) {
        prefs.edit().putString(FLIPS_KEY, NightFlips.encode(endsAt, codes)).apply()
    }
}

@Stable
internal class NightFlipState(private val store: NightFlipStore, private val endsAt: String) {
    var codes: Set<String> by mutableStateOf(store.read(endsAt))
        private set

    fun open(more: Collection<String>) {
        val next = codes + more
        if (next == codes) return
        codes = next
        store.write(endsAt, next)
    }
}

@Composable
internal fun rememberNightFlips(endsAt: String): NightFlipState {
    val context = LocalContext.current
    val store = remember(context) { NightFlipStore(context) }
    return remember(store, endsAt) { NightFlipState(store, endsAt) }
}

@Composable
internal fun NightMarketHead(
    endsAt: String,
    closed: Int,
    onOpenAll: () -> Unit,
    onElapsed: () -> Unit,
    modifier: Modifier = Modifier,
    stackedClock: Boolean = false,
) {
    val colors = millida
    val at = remember(endsAt) { parseInstant(endsAt)?.toEpochMilli() }
    val now by rememberNow(20_000L)
    val elapsed by rememberUpdatedState(onElapsed)
    if (at != null) LaunchedEffect(at) { refreshWhenDue(at) { elapsed() } }
    MCard(modifier = modifier, contentPadding = 14.dp) {
        Text("Ночной рынок", style = MillidaType.sectionTitle, color = colors.fg, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        if (stackedClock) {
            Text(leftLabel(max(0L, (at ?: now) - now)), style = MillidaType.pixelLarge, color = colors.fg, maxLines = 1)
            Text("до закрытия", style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(leftLabel(max(0L, (at ?: now) - now)), style = MillidaType.pixelLarge, color = colors.fg, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text("до закрытия", style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (closed > 1) {
            MPrimaryButton("Открыть все", onClick = onOpenAll, modifier = Modifier.fillMaxWidth())
        } else {
            Text("Скидки только для тебя", style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
        }
    }
}

/**
 * A row of night cards: face down until tapped, the same height whichever side shows.
 * A [lead] takes [leadSpan] cells before the cards, the desktop's market head
 * that fills whatever the first row of cards leaves free.
 */
@Composable
internal fun NightRow(
    cards: List<ShopCard>,
    flipped: Set<String>,
    balance: Long?,
    isBusy: (String) -> Boolean,
    onFlip: (ShopCard) -> Unit,
    onBuy: (ShopCard) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    lead: (@Composable (Modifier) -> Unit)? = null,
    leadSpan: Int = 0,
) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (lead != null && leadSpan > 0) lead(Modifier.weight(leadSpan.toFloat()).fillMaxHeight())
        for (card in cards) {
            NightCard(
                card = card,
                open = card.item.code in flipped,
                balance = balance,
                busy = isBusy("buy:" + card.item.code),
                onFlip = { onFlip(card) },
                onBuy = { onBuy(card) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        repeat(columns - cards.size - (if (lead != null) leadSpan else 0)) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun NightCard(
    card: ShopCard,
    open: Boolean,
    balance: Long?,
    busy: Boolean,
    onFlip: () -> Unit,
    onBuy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val turn = remember(card.item.code) { Animatable(if (open) HALF_TURN else 0f) }
    LaunchedEffect(open, reduced) {
        val target = if (open) HALF_TURN else 0f
        if (reduced) turn.snapTo(target) else turn.animateTo(target, tween(FLIP_MS))
    }
    val faceUp = turn.value >= QUARTER_TURN
    Box(
        modifier.graphicsLayer {
            rotationY = if (faceUp) turn.value - HALF_TURN else turn.value
            cameraDistance = 12f * density
        },
    ) {
        if (faceUp) {
            NightFace(card, balance, busy, onBuy, Modifier.fillMaxSize())
        } else {
            NightBack(onFlip, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun NightBack(onFlip: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    Column(
        modifier
            .heightIn(min = 240.dp)
            .pixelSurface(colors.surface2, MillidaCut.plate, colors.plateFrame())
            .clickable(role = Role.Button, onClick = onFlip)
            .semantics { contentDescription = "Открыть карту" }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MIcon(MIcons.moon, size = 40.dp, color = colors.accentText)
        Spacer(Modifier.height(10.dp))
        Text("Открыть", style = MillidaType.rowTitle, color = colors.fg, maxLines = 1)
    }
}

@Composable
private fun NightFace(card: ShopCard, balance: Long?, busy: Boolean, onBuy: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    val item = card.item
    val rarity = Rarity.of(item.rarity)
    MRarityFrame(rarity = rarity, modifier = modifier, contentPadding = 10.dp) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ItemArt(item, side = 92.dp)
                if (card.discountPct > 0) DiscountBadge(card.discountPct, Modifier.align(Alignment.TopStart), big = true)
            }
            Text(
                item.name,
                style = MillidaType.rowTitle.copy(fontSize = 14.sp, lineHeight = 17.sp),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (rarity != null) MRarityPlate(rarity)
            PriceTag(card.price, card.basePrice)
            if (card.owned) {
                OwnedMark()
            } else {
                BuyButton(card.price, balance, busy, onBuy, Modifier.fillMaxWidth(), MButtonSize.Small)
            }
        }
    }
}
