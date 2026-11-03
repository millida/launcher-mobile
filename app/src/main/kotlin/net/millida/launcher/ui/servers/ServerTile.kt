package net.millida.launcher.ui.servers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.core.rating.ServerArt
import net.millida.launcher.core.rating.versionRangeLabel
import net.millida.launcher.core.servers.OwnServers
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.categoryLabel
import net.millida.launcher.ui.catalog.plainText
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.rememberPressed

private const val MEDIA_RATIO = 16f / 9f
private val AVATAR_SIZE = 36.dp
private val FALLBACK_LOGO = 64.dp
private val FALLBACK_BLOCK = 48.dp
private val AVATAR_BLOCK = 22.dp
private val CHIP_INSET = 8.dp
private val TILE_SKELETON_MEDIA = 160.dp

/**
 * The desktop's catalogue server tile, laid out like a stream in the Twitch
 * directory: a 16:9 picture with the live online count on it, the versions in
 * its corner, the server avatar and name below. Touch has no hover, so
 * «Играть» stays on the picture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ServerTile(
    server: RatedServer,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    joining: Boolean = false,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Nav)
    val address = server.joinAddress
    val joinable = address.isNotEmpty() || OwnServers.of(server.slug, address) != null
    val line = remember(server) { tileLine(server) }
    Column(
        modifier
            .fillMaxWidth()
            .pixelSurface(if (pressed) colors.surface2 else colors.surface, MillidaCut.plate, colors.plateFrame())
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onLongClickLabel = tr("Скопировать адрес"),
                onLongClick = if (address.isNotEmpty()) onCopy else null,
                onClick = onOpen,
            )
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(MEDIA_RATIO)
                .clip(MillidaShape.control),
        ) {
            TileMedia(server)
            LiveChip(server, Modifier.align(Alignment.TopStart).padding(CHIP_INSET))
            versionRangeLabel(server.versionMajors)?.let { span ->
                TileChip(span, Modifier.align(Alignment.BottomStart).padding(CHIP_INSET))
            }
            if (joinable) {
                Box(Modifier.align(Alignment.BottomEnd).padding(CHIP_INSET)) {
                    MPrimaryButton(
                        text = tr("Играть"),
                        onClick = onPlay,
                        enabled = server.up,
                        icon = MIcons.play,
                        size = MButtonSize.Small,
                        loading = joining,
                    )
                }
            }
        }
        Row(
            Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TileAvatar(server)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    server.name,
                    style = MillidaType.rowTitle,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (line.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        line,
                        style = MillidaType.small,
                        color = colors.fgSubtle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ServerTileSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        MSkeleton(height = TILE_SKELETON_MEDIA)
        Spacer(Modifier.height(10.dp))
        MSkeleton(height = 36.dp)
    }
}

private fun tileLine(server: RatedServer): String {
    val license = when {
        server.license.equals("CRACKED", ignoreCase = true) -> tr("Вход по нику")
        server.license.equals("LICENSE", ignoreCase = true) -> tr("Лицензия")
        else -> ""
    }
    val about = server.summary.ifEmpty { plainText(server.description).replace('\n', ' ').trim() }.ifEmpty { server.motd.trim() }
    return listOfNotNull(server.categories.firstOrNull()?.let(::categoryLabel), license, about)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

/** The banner; without one the logo over a blurred copy of itself; without either a block on the server's tint. */
@Composable
private fun BoxScope.TileMedia(server: RatedServer) {
    val banner = server.bannerUrl?.trim().orEmpty()
    val logo = server.logoUrl?.trim().orEmpty()
    var bannerFailed by remember(banner) { mutableStateOf(false) }
    var logoFailed by remember(logo) { mutableStateOf(false) }
    when {
        banner.isNotEmpty() && !bannerFailed -> AsyncImage(
            model = banner,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onError = { bannerFailed = true },
        )
        logo.isNotEmpty() && !logoFailed -> {
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(18.dp),
                contentScale = ContentScale.Crop,
                onError = { logoFailed = true },
            )
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(FALLBACK_LOGO)
                    .clip(MillidaShape.control),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None,
            )
        }
        else -> BlockArt(server, Modifier.fillMaxSize(), FALLBACK_BLOCK)
    }
}

@Composable
private fun TileAvatar(server: RatedServer) {
    val logo = server.logoUrl?.trim().orEmpty()
    var logoFailed by remember(logo) { mutableStateOf(false) }
    val shape = Modifier
        .size(AVATAR_SIZE)
        .clip(MillidaShape.control)
    if (logo.isNotEmpty() && !logoFailed) {
        AsyncImage(
            model = logo,
            contentDescription = null,
            modifier = shape,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None,
            onError = { logoFailed = true },
        )
    } else {
        BlockArt(server, shape, AVATAR_BLOCK)
    }
}

@Composable
private fun BlockArt(server: RatedServer, modifier: Modifier, block: Dp) {
    val key = ServerArt.keyOf(server)
    val tints = MillidaPixel.serverTints
    Box(
        modifier.drawBehind { drawRect(tints[ServerArt.tint(key) % tints.size]) },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ServerArt.blockAsset(ServerArt.block(key)),
            contentDescription = null,
            modifier = Modifier.size(block),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    }
}

@Composable
private fun LiveChip(server: RatedServer, modifier: Modifier) {
    val colors = millida
    Row(
        modifier
            .background(MillidaPixel.modalScrim, MillidaShape.control)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (server.up) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(colors.accent),
            )
            Text(onlineText(server), style = MillidaType.pixelSmall, color = MillidaPixel.white, maxLines = 1)
        } else {
            Text(tr("офлайн"), style = MillidaType.pixelSmall, color = MillidaPixel.whiteSoft, maxLines = 1)
        }
    }
}

@Composable
private fun TileChip(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier
            .background(MillidaPixel.modalScrim, MillidaShape.control)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        style = MillidaType.pixelSmall,
        color = MillidaPixel.white,
        maxLines = 1,
    )
}
