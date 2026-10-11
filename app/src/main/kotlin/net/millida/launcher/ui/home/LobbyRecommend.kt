package net.millida.launcher.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.catalog.compactCount
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed
import kotlin.math.round

internal val LOBBY_REC_WIDTH = 320.dp
internal val LOBBY_REC_MIN_WIDTH = 260.dp
internal val LOBBY_REC_HEIGHT = 118.dp
private val REC_ART = 96.dp
private val REC_SLIDE = 24.dp

private val REC_FRAME = PixelFrame(
    outline = MillidaPixel.lobbyRecOutline,
    outlineWidth = 3.dp,
    top = MillidaPixel.lobbyRecTop,
    topWidth = 3.dp,
    bottom = MillidaPixel.lobbyRecBottom,
    bottomWidth = 5.dp,
)

private val GO_FRAME = PixelFrame(bottom = MillidaPixel.lobbyRecGoBottom, bottomWidth = 3.dp)

/**
 * «Рекомендуем» of the desktop lobby (`.lobby-rec`): the dark plate on the
 * right between the corner buttons and «Играть», the pack's art, the caption,
 * its name, one line of facts and «Попробовать». It slides in from the right
 * on every new pick.
 */
@Composable
internal fun LobbyRecommend(
    pack: PremiumPack,
    width: Dp,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    val own = pack.slug == ONEBLOCK_PACK
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Open)
    val progress = rememberPressProgress(pressed)
    val appear = remember(pack.id) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(pack.id) {
        if (!reduced) appear.animateTo(1f, tween(320, easing = MillidaMotion.steps(4)))
    }
    val titleShadow = with(LocalDensity.current) { Shadow(MillidaPixel.textShadow, Offset(0f, 2.dp.toPx()), 0f) }
    val facts = listOfNotNull(pack.loader, pack.mcVersion).filter { it.isNotBlank() }.joinToString(" · ")
    Row(
        modifier
            .width(width)
            .height(LOBBY_REC_HEIGHT)
            .graphicsLayer {
                val shown = appear.value
                alpha = shown
                translationX = round((1f - shown) * REC_SLIDE.toPx())
            }
            .pressShift(progress)
            .pixelSurface(if (pressed) MillidaPixel.lobbyRecPressed else MillidaPixel.lobbyRec, MillidaCut.plate, REC_FRAME)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Попробовать ${pack.title}", onClick = onOpen)
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(REC_ART)
                .clip(MillidaShape.mini)
                .pixelSurface(MillidaPixel.lobbyRecArt, MillidaCut.control),
        ) {
            if (own) OneBlockArt() else MRemoteImage(pack.coverUrl, MIcons.chest, Modifier.fillMaxSize(), shape = MillidaShape.mini)
            if (own) {
                Box(Modifier.padding(4.dp)) { HubTagBadge(HubTag.EXCLUSIVE, "Эксклюзив") }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Text(
                if (own) "ЭКСКЛЮЗИВНЫЙ ИВЕНТ" else "ПОПРОБУЙ СЕГОДНЯ",
                style = MillidaType.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.06.em),
                color = MillidaPixel.lobbyRecLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                pack.title,
                style = MillidaType.sectionTitle.copy(fontSize = 20.sp, lineHeight = 22.sp, fontWeight = FontWeight.Black, shadow = titleShadow),
                color = MillidaPixel.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (facts.isNotEmpty()) {
                    Text(
                        facts,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MillidaType.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = MillidaPixel.modeCaption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val downloads = pack.downloads?.takeIf { it > 0 }
                if (downloads != null) {
                    if (facts.isNotEmpty()) {
                        Text(" · ", style = MillidaType.small.copy(fontSize = 12.sp), color = MillidaPixel.modeCaption, maxLines = 1)
                    }
                    MIcon(MIcons.download, size = 12.dp, color = MillidaPixel.modeCaption)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        compactCount(downloads),
                        style = MillidaType.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = MillidaPixel.modeCaption,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .pixelSurface(colors.accent, MillidaCut.control, GO_FRAME)
                    .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Попробовать",
                    style = MillidaType.small.copy(fontSize = 13.sp, lineHeight = 13.sp, fontWeight = FontWeight.ExtraBold),
                    color = colors.onAccent,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                MIcon(MIcons.chevronRight, size = 12.dp, color = colors.onAccent)
            }
        }
    }
}
