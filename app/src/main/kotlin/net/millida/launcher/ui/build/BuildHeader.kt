package net.millida.launcher.ui.build

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.UiState
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.home.BuildArt
import net.millida.launcher.ui.home.LobbyState
import net.millida.launcher.ui.home.PlayBlock
import net.millida.launcher.ui.home.playedLabel
import net.millida.launcher.ui.home.playedSeconds
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame

private val HERO_ART = 70.dp
private val HERO_ACTIONS_WIDTH = 260.dp

@Composable
internal fun BuildHeader(
    profile: Profile,
    state: UiState,
    lobby: LobbyState,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = millida
    Column(
        Modifier
            .fillMaxWidth()
            .pixelSurface(colors.surface, MillidaCut.plate, colors.plateFrame())
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BuildArt(
                profile = profile,
                meta = lobby.metas[profile.id],
                coverPath = lobby.covers[profile.id],
                coverStamp = lobby.coverStamps[profile.id] ?: 0L,
                modifier = Modifier.size(76.dp),
                iconSize = 44.dp,
                cut = MillidaCut.plate,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.name,
                    style = MillidaType.sectionTitle.copy(fontSize = 19.sp, lineHeight = 23.sp),
                    color = colors.fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                BuildFacts(profile, state, lobby)
            }
        }
        Spacer(Modifier.height(14.dp))
        PlayBlock(state = state, onPlay = onPlay, onCancel = onCancel)
    }
}

/**
 * The desktop's `.inst-hero` running across a roomy window: the build's art,
 * its name with the facts under it, and the launch button at the far end.
 */
@Composable
internal fun BuildHero(
    profile: Profile,
    state: UiState,
    lobby: LobbyState,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = millida
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BuildArt(
            profile = profile,
            meta = lobby.metas[profile.id],
            coverPath = lobby.covers[profile.id],
            coverStamp = lobby.coverStamps[profile.id] ?: 0L,
            modifier = Modifier.size(HERO_ART),
            iconSize = 40.dp,
            cut = MillidaCut.plate,
        )
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.name,
                style = MillidaType.sectionTitle.copy(fontSize = 25.sp, lineHeight = 28.sp),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            BuildFacts(profile, state, lobby)
        }
        Spacer(Modifier.width(18.dp))
        PlayBlock(
            state = state,
            onPlay = onPlay,
            modifier = Modifier.width(HERO_ACTIONS_WIDTH),
            onCancel = onCancel,
        )
    }
}

@Composable
private fun BuildFacts(profile: Profile, state: UiState, lobby: LobbyState) {
    val colors = millida
    val seconds = playedSeconds(profile, lobby)
    val group = (state.buildMeta?.group ?: lobby.metas[profile.id]?.group).orEmpty().trim()
    MButtonRow {
        MBadge(profile.loader.label, colors.fgMuted, colors.chipFill)
        MBadge(profile.gameVersion, colors.fgMuted, colors.chipFill)
        if (group.isNotEmpty()) MBadge(group, colors.accentText, colors.accentSoft)
    }
    if (seconds >= 60) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.clock, size = 14.dp, color = colors.fgSubtle)
            Spacer(Modifier.width(6.dp))
            Text(playedLabel(seconds), style = MillidaType.pixelSmall, color = colors.accentText, maxLines = 1)
        }
    }
}
