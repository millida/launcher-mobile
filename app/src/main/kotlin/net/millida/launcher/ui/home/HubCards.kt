package net.millida.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.millida.launcher.core.hub.Hub
import net.millida.launcher.core.hub.HubMode
import net.millida.launcher.core.hub.ModeArt
import net.millida.launcher.core.hub.ModeArts
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida

internal val HUB_CARD_WIDTH = 176.dp
private val HUB_COVER_HEIGHT = 104.dp

/** The tag on a card's art: what the card is, in one or two words. */
internal enum class HubTag { PREMIUM, EXCLUSIVE, OWN, PACK, WEEK }

/** A picture shipped with the app: the designer's mode and banner art. */
@Composable
internal fun AssetImage(
    path: String,
    modifier: Modifier = Modifier,
    scale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    pixelated: Boolean = true,
) {
    AsyncImage(
        model = "file:///android_asset/$path",
        contentDescription = null,
        modifier = modifier,
        contentScale = scale,
        alignment = alignment,
        filterQuality = if (pixelated) FilterQuality.None else FilterQuality.Low,
    )
}

@Composable
internal fun HubTagBadge(tag: HubTag, text: String) {
    val colors = millida
    when (tag) {
        HubTag.PREMIUM, HubTag.EXCLUSIVE -> MBadge(text, colors.warning, colors.warningSoft, icon = MIcons.crown)
        HubTag.OWN -> MBadge(text, colors.accentText, colors.accentSoft, icon = MIcons.play)
        HubTag.PACK, HubTag.WEEK -> MBadge(text, colors.fgMuted, colors.surface2)
    }
}

/** A row card of the hub: the art, the tag on it, the name and one line under it. */
@Composable
internal fun HubCoverCard(
    title: String,
    meta: String?,
    tag: HubTag,
    tagText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: String? = null,
    art: (@Composable BoxScope.() -> Unit)? = null,
) {
    val colors = millida
    MCard(modifier = modifier.width(HUB_CARD_WIDTH), onClick = onClick, contentPadding = 10.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(HUB_COVER_HEIGHT)
                .clip(MillidaShape.control)
                .background(colors.surface2),
        ) {
            if (art != null) {
                art()
            } else {
                MRemoteImage(
                    url = cover,
                    icon = MIcons.image,
                    modifier = Modifier.fillMaxSize(),
                    shape = MillidaShape.control,
                )
            }
            Box(Modifier.padding(6.dp)) { HubTagBadge(tag, tagText) }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            meta.orEmpty(),
            style = MillidaType.small,
            color = colors.fgSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The «Ещё» cell at the end of a row. */
@Composable
internal fun HubMoreCard(title: String, meta: String, icon: MillidaIcon, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    MCard(modifier = modifier.width(HUB_CARD_WIDTH / 2 + 24.dp), onClick = onClick, contentPadding = 10.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(HUB_COVER_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            MIcon(icon, size = 32.dp, color = colors.accentText)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1)
        Text(meta, style = MillidaType.small, color = colors.fgSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** OneBlock's face: the background and logo of the designer's banner, the same as on mcru.me. */
@Composable
internal fun BoxScope.OneBlockArt(withRig: Boolean = false) {
    AssetImage(ModeArts.ONEBLOCK_BACKGROUND, Modifier.fillMaxSize(), pixelated = false)
    if (withRig) {
        AssetImage(
            ModeArts.ONEBLOCK_RIG,
            Modifier
                .align(Alignment.BottomEnd)
                .fillMaxHeight()
                .padding(top = 8.dp),
            scale = ContentScale.Fit,
            alignment = Alignment.BottomEnd,
            pixelated = false,
        )
    }
    AssetImage(
        ModeArts.ONEBLOCK_LOGO,
        Modifier
            .align(if (withRig) Alignment.CenterStart else Alignment.Center)
            .padding(horizontal = 14.dp)
            .fillMaxWidth(if (withRig) 0.52f else 0.8f)
            .fillMaxHeight(0.6f),
        scale = ContentScale.Fit,
        pixelated = false,
    )
}

/** The own event on two cells: the designer's banner, «Играть» and the live online. */
@Composable
internal fun OneBlockBanner(online: Int?, onPlay: () -> Unit, modifier: Modifier = Modifier, height: Dp = 168.dp) {
    val colors = millida
    MCard(modifier = modifier, onClick = onPlay, contentPadding = 10.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(MillidaShape.control)
                .background(colors.surface2),
        ) {
            OneBlockArt(withRig = true)
            Box(Modifier.padding(8.dp)) { HubTagBadge(HubTag.OWN, "Наш сервер") }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MPrimaryButton(text = "Играть", onClick = onPlay, icon = MIcons.play, size = MButtonSize.Medium)
            Spacer(Modifier.width(12.dp))
            Hub.onlineLabel(online)?.let { OnlineLine(it) }
        }
    }
}

@Composable
internal fun OnlineLine(text: String) {
    val colors = millida
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .background(colors.accent),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
    }
}

/**
 * A mode tile: the mode's mosaic with its rig standing on the lower edge, the
 * name and the live online under it. A code without art shows its name alone.
 */
@Composable
internal fun ModeTile(mode: HubMode, art: ModeArt?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    MCard(modifier = modifier, onClick = onClick, contentPadding = 10.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(MillidaShape.control)
                .background(colors.surface2),
        ) {
            if (art != null) {
                AssetImage(art.background, Modifier.fillMaxSize())
                val rig = art.rig
                if (rig != null) {
                    AssetImage(
                        rig,
                        Modifier
                            .align(Alignment.BottomEnd)
                            .fillMaxHeight(0.92f),
                        scale = ContentScale.Fit,
                        alignment = Alignment.BottomEnd,
                        pixelated = false,
                    )
                } else {
                    AssetImage(
                        art.icon,
                        Modifier
                            .align(Alignment.Center)
                            .size(64.dp),
                        scale = ContentScale.Fit,
                    )
                }
            } else {
                MIcon(MIcons.blocks, size = 36.dp, color = colors.fgFaint, modifier = Modifier.align(Alignment.Center))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(mode.title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
            Hub.onlineLabel(mode.online)?.let { OnlineLine(it) }
        }
    }
}

/** Two cells in a row; a lone last cell keeps half the width. */
@Composable
internal fun <T> PairRows(items: List<T>, gap: Dp = 10.dp, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        items.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                pair.forEach { cell(it, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
