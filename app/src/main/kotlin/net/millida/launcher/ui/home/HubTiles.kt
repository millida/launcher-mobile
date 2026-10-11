package net.millida.launcher.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.millida.launcher.Prefs
import net.millida.launcher.core.hub.HubTileCopy
import net.millida.launcher.ui.MHeroCard
import net.millida.launcher.ui.MHeroTone
import net.millida.launcher.ui.TourTarget
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.tourTarget

/**
 * The desktop tile pictures (public/lobby, HubTile.tsx ART) standing on the
 * tile bottom. [shift] moves the picture off the centre by a share of its own
 * width, the desktop's `--ht-art-x` plus a half.
 */
private enum class TileArt(val path: String, val ratio: Float, val shift: Float) {
    SHOP("lobby/skins.png", 508f / 512f, 0.22f),
    WARDROBE("lobby/capes.png", 328f / 512f, 0f),
    SERVER("lobby/duo.webp", 1483f / 1000f, -0.02f),
}

@Composable
private fun BoxScope.TileArtImage(art: TileArt) {
    AssetImage(
        path = art.path,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxHeight()
            .wrapContentWidth(unbounded = true)
            .aspectRatio(art.ratio, matchHeightConstraintsFirst = true)
            .graphicsLayer { translationX = size.width * art.shift },
        scale = ContentScale.Fit,
        alignment = Alignment.BottomCenter,
        pixelated = false,
    )
}

/** One section tile of the lobby: its words, colours, picture, guide mark and where it leads. */
private class HubTileSpec(
    val copy: HubTileCopy.Copy,
    val tone: MHeroTone,
    val art: TileArt,
    val target: TourTarget,
    val onClick: () -> Unit,
)

/** The three tiles in the desktop's order: shop, my skin, play with friends. */
@Composable
private fun rememberHubTileSpecs(
    hostedCount: Int,
    shopAlert: Boolean,
    bonusReady: Boolean,
    onShop: () -> Unit,
    onCharacter: () -> Unit,
    onHosting: () -> Unit,
): List<HubTileSpec> {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    var skinSeen by remember(prefs) { mutableStateOf(prefs.lobbySkinSeen) }
    val shop = HubTileCopy.of(HubTileCopy.Kind.SHOP, bonusReady, shopAlert, skinSeen, hostedCount)
    val wardrobe = HubTileCopy.of(HubTileCopy.Kind.WARDROBE, bonusReady, shopAlert, skinSeen, hostedCount)
    val server = HubTileCopy.of(HubTileCopy.Kind.SERVER, bonusReady, shopAlert, skinSeen, hostedCount)
    return listOf(
        HubTileSpec(shop, MHeroTone.Shop, TileArt.SHOP, TourTarget.SHOP, onShop),
        HubTileSpec(wardrobe, MHeroTone.Wardrobe, TileArt.WARDROBE, TourTarget.WARDROBE) {
            if (!skinSeen) {
                prefs.lobbySkinSeen = true
                skinSeen = true
            }
            onCharacter()
        },
        HubTileSpec(server, MHeroTone.server(millida.accent), TileArt.SERVER, TourTarget.SERVER, onHosting),
    )
}

@Composable
private fun HubTileCard(spec: HubTileSpec, modifier: Modifier, height: Dp, overflow: Dp = TILE_OVERFLOW) {
    MHeroCard(
        title = spec.copy.title,
        modifier = modifier.tourTarget(spec.target),
        subtitle = spec.copy.subtitle,
        tone = spec.tone,
        height = height,
        overflow = overflow,
        ribbon = spec.copy.ribbon,
        alert = spec.copy.alert,
        onClick = spec.onClick,
        art = { TileArtImage(spec.art) },
    )
}

private val TILE_OVERFLOW = 20.dp

@Composable
internal fun HubTiles(
    hostedCount: Int,
    shopAlert: Boolean,
    bonusReady: Boolean,
    onShop: () -> Unit,
    onCharacter: () -> Unit,
    onHosting: () -> Unit,
) {
    val (shop, wardrobe, server) = rememberHubTileSpecs(hostedCount, shopAlert, bonusReady, onShop, onCharacter, onHosting)
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HubTileCard(shop, Modifier.weight(1f), height = 104.dp)
            HubTileCard(wardrobe, Modifier.weight(1f), height = 104.dp)
        }
        HubTileCard(server, Modifier, height = 96.dp)
    }
}

/**
 * The landscape lobby's left edge, the desktop's `.lobby-left`: the three
 * tiles stacked [width] wide, each [height] tall with the picture's heads
 * [overflow] over its top edge.
 */
@Composable
internal fun HubTileColumn(
    hostedCount: Int,
    shopAlert: Boolean,
    bonusReady: Boolean,
    onShop: () -> Unit,
    onCharacter: () -> Unit,
    onHosting: () -> Unit,
    width: Dp,
    height: Dp,
    overflow: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
) {
    val tiles = rememberHubTileSpecs(hostedCount, shopAlert, bonusReady, onShop, onCharacter, onHosting)
    Column(
        modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        tiles.forEach { tile -> HubTileCard(tile, Modifier, height = height, overflow = overflow) }
    }
}
