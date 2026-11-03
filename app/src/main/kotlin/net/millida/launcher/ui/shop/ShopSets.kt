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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.rubies.SetColorway
import net.millida.launcher.core.rubies.ShopSet
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCardRail
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame

private const val SET_ICONS = 5
private const val COLLAGE = 4
private val SET_CARD_WIDTH = 200.dp

/** A set's theme tone, the desktop's THEME_TONE. */
internal fun themeTone(theme: String): Color = when (theme.lowercase()) {
    "flame" -> Rarity.MYTHIC.color
    "dark" -> Rarity.EPIC.color
    "future" -> Rarity.RARE.color
    "cozy" -> Rarity.UNCOMMON.color
    else -> Rarity.COMMON.color
}

/** RRGGBB from the service as a colour for a colourway dot; a broken value draws the theme tone. */
internal fun serviceColor(raw: String?, fallback: Color): Color {
    val hex = raw?.trim()?.removePrefix("#").orEmpty()
    if (hex.length != 6) return fallback
    val value = hex.toLongOrNull(16) ?: return fallback
    return Color(0xFF000000L or value)
}

internal class SetActs(
    val balance: Long?,
    val isBusy: (String) -> Boolean,
    val onBuy: (ShopSet, SetColorway) -> Unit,
    val onWear: (SetColorway) -> Unit,
)

/** The most expensive pieces of a colourway: the collage shows them. */
private fun topPieces(way: SetColorway) = way.items.sortedByDescending { it.price }.take(COLLAGE).map { it.item }

@Composable
private fun SetCollage(way: SetColorway, tone: Color, height: Dp, modifier: Modifier = Modifier) {
    val colors = millida
    val pieces = topPieces(way)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .pixelSurface(tone.mixSrgb(colors.inset, 0.22f), MillidaCut.plate),
        contentAlignment = Alignment.Center,
    ) {
        PlayerShot(way.items.map { it.item }, tone, Modifier.matchParentSize()) {
            when (pieces.size) {
                0 -> MIcon(MIcons.shirt, size = height * 0.4f, color = tone)
                1 -> ItemArt(pieces[0], side = height * 0.8f)
                else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    pieces.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { ItemArt(it, side = height * 0.44f) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetPriceButton(set: ShopSet, way: SetColorway, acts: SetActs, size: MButtonSize = MButtonSize.Small) {
    if (way.complete) {
        MSecondaryButton(tr("Надеть"), onClick = { acts.onWear(way) }, modifier = Modifier.fillMaxWidth(), size = size)
    } else {
        PriceButton(way.price, way.fullPrice, acts.balance, acts.isBusy("set:" + set.id), onClick = { acts.onBuy(set, way) }, size = size)
    }
}

/** The desktop's set card: the set as a picture, its pieces as icons, one price button; a tap opens the try-on. */
@Composable
internal fun SetCard(set: ShopSet, way: SetColorway, acts: SetActs, onOpen: () -> Unit) {
    val colors = millida
    val tone = themeTone(set.theme)
    Column(
        Modifier
            .fillMaxWidth()
            .pixelSurface(colors.surface, MillidaCut.plate, PixelFrame(outline = tone.mixSrgb(colors.surface, 0.5f), outlineWidth = 2.dp, bottom = MillidaPixel.tileBottom, bottomWidth = 4.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = set.title, onClick = onOpen)) {
            SetCollage(way, tone, 150.dp)
            if (!way.complete && way.discountPct > 0) DiscountBadge(way.discountPct, Modifier.align(Alignment.TopStart).padding(6.dp))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .pixelSurface(colors.bg.copy(alpha = MillidaPixel.railArrowAlpha), MillidaCut.badge)
                    .padding(5.dp),
            ) { MIcon(MIcons.eye, size = 14.dp, color = colors.fg) }
        }
        Text(set.title, style = MillidaType.rowTitle.copy(fontWeight = FontWeight.Black), color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(
            Modifier.semantics { contentDescription = tr("Вещей: {size}", way.items.size) },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            way.items.take(SET_ICONS).forEach { piece ->
                Box(
                    Modifier
                        .size(28.dp)
                        .pixelSurface(colors.surface2, MillidaCut.badge)
                        .alpha(if (piece.owned) 0.45f else 1f),
                    contentAlignment = Alignment.Center,
                ) { ItemArt(piece.item, side = 24.dp) }
            }
        }
        SetPriceButton(set, way, acts)
    }
}

/** «Наборы»: one rail of set cards; a tap opens the try-on with colourways, the pieces and the price. */
@Composable
internal fun SetsRail(sets: List<ShopSet>?, picks: Map<String, String>, acts: SetActs, onOpen: (ShopSet) -> Unit, modifier: Modifier = Modifier) {
    if (sets == null) {
        MSkeleton(height = 300.dp, modifier = modifier)
        return
    }
    MCardRail(entries = sets, key = { it.id }, label = tr("Наборы"), modifier = modifier, cardWidth = SET_CARD_WIDTH) { set ->
        val way = set.colorways.firstOrNull { it.name == picks[set.id] } ?: set.defaultWay
        if (way != null) SetCard(set, way, acts) { onOpen(set) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SetSheet(
    set: ShopSet,
    picked: String?,
    acts: SetActs,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = millida
    val way = set.colorways.firstOrNull { it.name == picked } ?: set.defaultWay ?: return
    val tone = themeTone(set.theme)
    MBottomSheet(onDismiss = onDismiss, title = set.title) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                SetCollage(way, tone, 220.dp)
                if (!way.complete && way.discountPct > 0) DiscountBadge(way.discountPct, Modifier.align(Alignment.TopStart).padding(8.dp), big = true)
                if (set.ofDay) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .pixelSurface(Rarity.LEGENDARY.color, MillidaCut.badge)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) { Text(tr("Набор дня"), style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black), color = Rarity.ink, maxLines = 1) }
                }
            }
            if (set.colorways.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    set.colorways.forEach { option ->
                        val on = option.name == way.name
                        Box(
                            Modifier
                                .size(34.dp)
                                .pixelSurface(
                                    serviceColor(option.color, tone),
                                    MillidaCut.badge,
                                    PixelFrame(outline = if (on) colors.fg else Color.Transparent, outlineWidth = 3.dp),
                                )
                                .selectable(on, remember { MutableInteractionSource() }, null, role = Role.RadioButton) { onPick(option.name) }
                                .semantics { contentDescription = option.name.ifEmpty { tr("Основная") } },
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                way.items.forEach { piece ->
                    Row(
                        Modifier
                            .pixelSurface(colors.surface2, MillidaCut.control, colors.plateFrame())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ItemArt(piece.item, side = 32.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(piece.item.name, style = MillidaType.small.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp), color = colors.fg, maxLines = 1)
                        if (piece.owned) {
                            Spacer(Modifier.width(6.dp))
                            MIcon(MIcons.check, size = 14.dp, color = colors.accentText)
                        }
                    }
                }
            }
            SetPriceButton(set, way, acts, MButtonSize.Large)
        }
    }
}
