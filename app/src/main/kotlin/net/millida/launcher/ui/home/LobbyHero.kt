package net.millida.launcher.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.millida.launcher.UiState
import net.millida.launcher.core.profile.PlayTime
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.TourTarget
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.tourTarget

/** The colour of the «Сегодня играем» plate by what is picked, the desktop's `.lobby-mode-card.kind-*`. */
internal enum class LobbyModeKind(val plate: Color, val art: Color) {
    BUILD(MillidaPixel.modeBuild, MillidaPixel.modeBuildArt),
    PREMIUM(MillidaPixel.modePremium, MillidaPixel.modePremiumArt),
    SERVER(MillidaPixel.modeServer, MillidaPixel.modeServerArt),
}

private val MODE_FRAME = PixelFrame(
    outline = MillidaPixel.modeOutline,
    outlineWidth = 3.dp,
    top = MillidaPixel.modeTop,
    topWidth = 3.dp,
    bottom = MillidaPixel.modeBottom,
    bottomWidth = 5.dp,
)

@Composable
internal fun BuildHero(
    profile: Profile,
    state: UiState,
    lobby: LobbyState,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    kind: LobbyModeKind = LobbyModeKind.BUILD,
) {
    val colors = millida
    Column(
        Modifier
            .fillMaxWidth()
            .pixelSurface(colors.surface, MillidaCut.plate, colors.plateFrame())
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 16.dp),
    ) {
        ModePlate(profile, lobby, kind, Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        LobbyPlayButton(
            state = state,
            playedSeconds = lobby.totalSeconds,
            onPlay = onPlay,
            onCancel = onCancel,
            modifier = Modifier.tourTarget(TourTarget.PLAY),
        )
        Spacer(Modifier.height(10.dp))
        MButtonRow {
            MSecondaryButton("Сборка", onClick = onOpen, icon = MIcons.settings, size = MButtonSize.Small)
        }
    }
}

/**
 * The «Сегодня играем» plate itself, the desktop's `.lobby-mode-card`: the
 * build's art, the caption, its name and the loader line with the hours.
 */
@Composable
internal fun ModePlate(
    profile: Profile,
    lobby: LobbyState,
    kind: LobbyModeKind,
    modifier: Modifier = Modifier,
    artSize: Dp = 64.dp,
    iconSize: Dp = 38.dp,
) {
    val seconds = playedSeconds(profile, lobby)
    val hours = if (seconds >= PlayTime.SHOWN_FROM_SECONDS) PlayTime.format(seconds) else null
    val titleShadow = with(LocalDensity.current) { Shadow(MillidaPixel.textShadow, Offset(0f, 2.dp.toPx()), 0f) }
    Row(
        modifier
            .pixelSurface(kind.plate, MillidaCut.plate, MODE_FRAME)
            .padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BuildArt(
            profile = profile,
            meta = lobby.metas[profile.id],
            coverPath = lobby.covers[profile.id],
            coverStamp = lobby.coverStamps[profile.id] ?: 0L,
            modifier = Modifier.size(artSize),
            iconSize = iconSize,
            tone = kind.art,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "СЕГОДНЯ ИГРАЕМ",
                style = MillidaType.caption.copy(fontWeight = FontWeight.ExtraBold),
                color = MillidaPixel.modeCaption,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                profile.name,
                style = MillidaType.sectionTitle.copy(fontWeight = FontWeight.Black, shadow = titleShadow),
                color = MillidaPixel.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    loaderLine(profile),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MillidaType.small,
                    color = MillidaPixel.modeMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hours != null) {
                    Spacer(Modifier.width(10.dp))
                    MIcon(MIcons.clock, size = 14.dp, color = MillidaPixel.modeMeta)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        hours,
                        style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
                        color = MillidaPixel.modeMeta,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private const val EMPTY_BLINK_MS = 800L

/**
 * Nothing picked yet: the desktop's amber plate that blinks in steps to call
 * for a pick, with the caption and «Ещё не выбрано» in two lines.
 */
@Composable
internal fun EmptyModePlate(modifier: Modifier = Modifier, artSize: Dp = 64.dp) {
    val reduced = LocalReducedMotion.current
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(reduced) {
        lit = false
        if (reduced) return@LaunchedEffect
        while (true) {
            delay(EMPTY_BLINK_MS)
            lit = !lit
        }
    }
    val titleShadow = with(LocalDensity.current) { Shadow(MillidaPixel.textShadow, Offset(0f, 2.dp.toPx()), 0f) }
    Row(
        modifier
            .pixelSurface(if (lit) MillidaPixel.modeEmptyBlink else MillidaPixel.modeEmpty, MillidaCut.plate, MODE_FRAME)
            .padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(artSize)
                .pixelSurface(MillidaPixel.modeEmptyArt, MillidaCut.control),
            contentAlignment = Alignment.Center,
        ) {
            MIcon(MIcons.chest, size = artSize * EMPTY_ICON_SHARE, color = MillidaPixel.modeEmptyIcon)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "СЕГОДНЯ ИГРАЕМ",
                style = MillidaType.caption.copy(fontWeight = FontWeight.ExtraBold),
                color = MillidaPixel.modeCaption,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Ещё не выбрано",
                style = MillidaType.sectionTitle.copy(fontWeight = FontWeight.Black, shadow = titleShadow),
                color = MillidaPixel.white,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val EMPTY_ICON_SHARE = 0.55f

@Composable
internal fun FirstBuildHero(state: UiState, onCreate: () -> Unit) {
    val colors = millida
    Column(
        Modifier
            .fillMaxWidth()
            .pixelSurface(colors.surface, MillidaCut.plate, colors.plateFrame())
            .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(76.dp)
                .pixelSurface(colors.accentDeep, MillidaCut.plate, dots = true),
            contentAlignment = Alignment.Center,
        ) {
            MIcon(MIcons.chest, size = 46.dp)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Создай первую сборку",
            style = MillidaType.sectionTitle,
            color = colors.fg,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (state.busy == null) {
            MPrimaryButton(
                text = "Создать сборку",
                onClick = onCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .tourTarget(TourTarget.PLAY),
                icon = MIcons.plus,
                size = MButtonSize.Large,
            )
        } else {
            PlayBlock(state = state, onPlay = onCreate, modifier = Modifier.tourTarget(TourTarget.PLAY))
        }
    }
}
