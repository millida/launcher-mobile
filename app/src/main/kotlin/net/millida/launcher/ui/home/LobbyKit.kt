package net.millida.launcher.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.UiState
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.profile.BuildMeta
import net.millida.launcher.core.profile.PlayTime
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MButton
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MButtonVariant
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MLocalImage
import net.millida.launcher.ui.MPixelLoader
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MProgressBar
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelCorners
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed
import kotlin.math.roundToInt

private val BUILD_TONES = listOf(
    MillidaPixel.serverDark,
    MillidaPixel.wardrobeOutline,
    MillidaPixel.shopBand,
    MillidaPixel.dailyBand,
    MillidaPixel.wardrobeBand,
)

internal fun buildTone(profileId: String): Color = BUILD_TONES[(profileId.hashCode() and Int.MAX_VALUE) % BUILD_TONES.size]

internal fun buildIcon(profile: Profile, meta: BuildMeta?): MillidaIcon {
    val chosen = meta?.icon?.trim().orEmpty()
    if (chosen.isNotEmpty()) MIcons.byDesktopId(chosen)?.let { return it }
    return when (profile.loader) {
        LoaderKind.VANILLA -> MIcons.grassBlock
        LoaderKind.FABRIC, LoaderKind.QUILT -> MIcons.box
        LoaderKind.FORGE, LoaderKind.NEOFORGE -> MIcons.hammer
    }
}

internal fun loaderLine(profile: Profile): String = "${profile.loader.label} · ${profile.gameVersion}"

internal fun hoursLabel(seconds: Long): String? {
    if (seconds < 3600) return null
    return "${seconds / 3600} ч"
}

internal fun playedLabel(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    return when {
        hours > 0 -> "$hours ч $minutes мин"
        minutes > 0 -> "$minutes мин"
        else -> "меньше минуты"
    }
}

internal fun playedSeconds(profile: Profile, lobby: LobbyState): Long =
    maxOf(lobby.playtime[profile.id] ?: 0L, profile.playtimeSec)

@Composable
internal fun BuildArt(
    profile: Profile,
    meta: BuildMeta?,
    coverPath: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 32.dp,
    cut: Dp = MillidaCut.control,
    corners: PixelCorners = PixelCorners.All,
    coverStamp: Long = 0L,
    tone: Color = buildTone(profile.id),
) {
    val cover = coverPath?.takeIf { it.isNotBlank() }
    Box(
        modifier.pixelSurface(tone, cut, dots = cover == null, corners = corners),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            key(cover, coverStamp) { MLocalImage(cover, Modifier.fillMaxSize()) }
        } else {
            MIcon(buildIcon(profile, meta), size = iconSize, color = MillidaPixel.white)
        }
    }
}

@Composable
internal fun LazyItemScope.itemMotion(): Modifier {
    if (LocalReducedMotion.current) return Modifier
    return Modifier.animateItem(
        fadeInSpec = tween(MillidaMotion.base, easing = MillidaMotion.steps(4)),
        placementSpec = tween(MillidaMotion.base, easing = MillidaMotion.steps(4)),
        fadeOutSpec = tween(MillidaMotion.fast, easing = MillidaMotion.steps(2)),
    )
}

@Composable
internal fun PlayBlock(
    state: UiState,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    val colors = millida
    val busy = state.busy
    Column(modifier.fillMaxWidth()) {
        if (busy == null) {
            MPrimaryButton(
                text = "Играть",
                onClick = onPlay,
                modifier = Modifier.fillMaxWidth(),
                icon = MIcons.play,
                size = MButtonSize.Large,
            )
        } else {
            MButton(
                text = busy,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                variant = MButtonVariant.Running,
                size = MButtonSize.Large,
                icon = MIcons.clock,
            )
            Spacer(Modifier.height(10.dp))
            MProgressBar(state.progress.takeIf { it > 0f })
            if (state.progressNote.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    state.progressNote,
                    style = MillidaType.small,
                    color = colors.fgFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.preparingGame && onCancel != null) {
                Spacer(Modifier.height(6.dp))
                MTextButton("Отменить запуск", onClick = onCancel)
            }
        }
    }
}

private const val FILL_ALPHA = 0.35f
private const val HOURS_ALPHA = 0.5f

/**
 * The lobby «Играть», the desktop's #playBtn: the hours played sit under the
 * word, and while the game is being prepared the button itself carries the
 * stage, the percent and its fill. A second tap during preparation cancels it,
 * and so does the cross in its corner.
 */
@Composable
internal fun LobbyPlayButton(
    state: UiState,
    playedSeconds: Long,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
) {
    val busy = state.busy
    if (busy == null) {
        PlayIdleButton(PlayTime.inGame(playedSeconds), onPlay, modifier)
    } else {
        PlayProgressButton(
            stage = state.progressNote.ifBlank { busy },
            fraction = state.progress.coerceIn(0f, 1f),
            onCancel = if (state.preparingGame) onCancel else null,
            modifier = modifier,
            fillHeight = fillHeight,
        )
    }
}

@Composable
private fun PlayIdleButton(hoursLine: String?, onPlay: () -> Unit, modifier: Modifier) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Click)
    val progress = rememberPressProgress(pressed)
    val fill = if (pressed) MillidaPixel.pressShade.compositeOver(colors.accent) else colors.accent
    val size = MButtonSize.Large
    Row(
        modifier
            .fillMaxWidth()
            .pressShift(progress)
            .heightIn(min = size.height)
            .pixelSurface(fill, MillidaCut.control, if (pressed) PixelFrame.ButtonPressed else PixelFrame.Button, dots = true)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onPlay)
            .padding(start = size.padding, end = size.padding, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        MIcon(MIcons.play, size = size.icon, color = colors.onAccent)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "Играть",
                style = MillidaType.button.copy(fontSize = size.fontSize),
                color = colors.onAccent,
                maxLines = 1,
            )
            if (hoursLine != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    hoursLine,
                    style = MillidaType.small.copy(fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.onAccent.copy(alpha = HOURS_ALPHA),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PlayProgressButton(stage: String, fraction: Float, onCancel: (() -> Unit)?, modifier: Modifier, fillHeight: Boolean) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    val shown by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (reduced) snap<Float>() else tween<Float>(MillidaMotion.base),
        label = "play-fill",
    )
    val fillColor = colors.accent.copy(alpha = FILL_ALPHA)
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
                .heightIn(min = MButtonSize.Large.height)
                .pixelSurface(colors.surface2, MillidaCut.control, PixelFrame.Button)
                .drawBehind { drawRect(fillColor, size = Size(size.width * shown, size.height)) }
                .then(
                    if (onCancel != null) {
                        Modifier.clickable(role = Role.Button, onClickLabel = "Отменить запуск", onClick = onCancel)
                    } else {
                        Modifier
                    },
                )
                .padding(start = 18.dp, end = if (onCancel != null) 56.dp else 18.dp, top = 6.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stage,
                    style = MillidaType.small.copy(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.fgMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MPixelLoader(color = colors.fg)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${(fraction * 100).roundToInt()}%",
                        style = MillidaType.button.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold),
                        color = colors.fg,
                        maxLines = 1,
                    )
                }
            }
        }
        if (onCancel != null) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp, bottom = 3.dp)
                    .size(36.dp)
                    .pixelSurface(colors.surface, MillidaCut.badge, colors.plateFrame())
                    .clickable(role = Role.Button, onClickLabel = "Отменить запуск", onClick = onCancel)
                    .semantics { contentDescription = "Отменить запуск" },
                contentAlignment = Alignment.Center,
            ) {
                MIcon(MIcons.close, size = 14.dp, color = colors.fgMuted)
            }
        }
    }
}

@Composable
internal fun LoaderChip(kind: LoaderKind, selected: Boolean, unsupported: Boolean, onClick: () -> Unit) {
    Box {
        MChip(
            text = kind.label,
            selected = selected && !unsupported,
            onClick = onClick,
            modifier = Modifier.padding(top = 6.dp, end = 8.dp),
            enabled = !unsupported,
        )
        if (unsupported) {
            Text(
                "Нет",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-1).dp)
                    .pixelSurface(Rarity.LEGENDARY.color, MillidaCut.badge)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                style = MillidaType.tabLabel.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Black),
                color = Rarity.ink,
                maxLines = 1,
            )
        }
    }
}
