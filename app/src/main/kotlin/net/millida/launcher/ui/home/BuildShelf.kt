package net.millida.launcher.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MDangerButton
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MSectionHeader
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelCorners
import net.millida.launcher.ui.TourTarget
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed
import net.millida.launcher.ui.tourTarget

private val CARD_WIDTH = 160.dp
private val BUILD_GLYPH = 44.dp

private fun shelfGroupOf(profile: Profile, lobby: LobbyState): String =
    lobby.metas[profile.id]?.group?.trim()?.lowercase().orEmpty()

/** How many list items [buildShelves] adds: «Мои сборки» plus one per named group. */
internal fun buildShelfCount(profiles: List<Profile>, lobby: LobbyState): Int {
    if (profiles.isEmpty()) return 0
    return 1 + profiles.map { shelfGroupOf(it, lobby) }.filter { it.isNotEmpty() }.distinct().size
}

internal fun LazyListScope.buildShelves(
    profiles: List<Profile>,
    lobby: LobbyState,
    activeId: String?,
    onSelect: (Profile) -> Unit,
    onMenu: (Profile) -> Unit,
    onCreate: () -> Unit,
) {
    if (profiles.isEmpty()) return
    val grouped = profiles.groupBy { shelfGroupOf(it, lobby) }
    val loose = grouped[""].orEmpty()
    item(key = "shelf:mine", contentType = "shelf") {
        BuildShelf(
            title = "Мои сборки",
            builds = loose,
            lobby = lobby,
            activeId = activeId,
            withNew = true,
            onSelect = onSelect,
            onMenu = onMenu,
            onCreate = onCreate,
            modifier = Modifier.tourTarget(TourTarget.BUILDS),
        )
    }
    grouped.keys
        .filter { it.isNotEmpty() }
        .sorted()
        .forEach { key ->
            val builds = grouped[key].orEmpty()
            val title = builds.firstNotNullOfOrNull { lobby.metas[it.id]?.group?.trim() } ?: key
            item(key = "shelf:group:$key", contentType = "shelf") {
                BuildShelf(
                    title = title,
                    builds = builds,
                    lobby = lobby,
                    activeId = activeId,
                    withNew = false,
                    onSelect = onSelect,
                    onMenu = onMenu,
                    onCreate = onCreate,
                )
            }
        }
}

@Composable
private fun BuildShelf(
    title: String,
    builds: List<Profile>,
    lobby: LobbyState,
    activeId: String?,
    withNew: Boolean,
    onSelect: (Profile) -> Unit,
    onMenu: (Profile) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        MSectionHeader(title)
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (withNew) {
                item(key = "new", contentType = "new") {
                    NewBuildTile(onCreate, Modifier.then(itemMotion()))
                }
            }
            items(builds, key = { "build:${it.id}" }, contentType = { "build" }) { profile ->
                BuildCard(
                    profile = profile,
                    lobby = lobby,
                    selected = profile.id == activeId,
                    onSelect = { onSelect(profile) },
                    onMenu = { onMenu(profile) },
                    modifier = itemMotion(),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BuildCard(
    profile: Profile,
    lobby: LobbyState,
    selected: Boolean,
    onSelect: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Nav)
    val progress = rememberPressProgress(pressed, MillidaMotion.tilePress, MillidaMotion.steps(2))
    val fill = when {
        selected -> colors.accentToday
        pressed -> colors.surface2
        else -> colors.surface
    }
    val hours = hoursLabel(playedSeconds(profile, lobby))
    Column(
        modifier
            .width(CARD_WIDTH)
            .pressShift(progress)
            .pixelSurface(fill, MillidaCut.plate, colors.plateFrame())
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onLongClickLabel = "Меню сборки",
                onLongClick = onMenu,
                onClick = onSelect,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            BuildArt(
                profile = profile,
                meta = lobby.metas[profile.id],
                coverPath = lobby.covers[profile.id],
                coverStamp = lobby.coverStamps[profile.id] ?: 0L,
                modifier = Modifier.fillMaxSize(),
                iconSize = BUILD_GLYPH,
                cut = MillidaCut.plate,
                corners = PixelCorners.Top,
            )
            MoreButton(onMenu, Modifier.align(Alignment.TopEnd))
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 12.dp)) {
            Text(
                profile.name,
                style = MillidaType.rowTitle.copy(fontSize = 14.5.sp),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                loaderLine(profile),
                style = MillidaType.small,
                color = colors.fgSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hours != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MIcon(MIcons.clock, size = 12.dp, color = colors.fgSubtle)
                    Spacer(Modifier.width(4.dp))
                    Text(hours, style = MillidaType.pixelSmall, color = colors.accentText, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun MoreButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Click)
    Box(
        modifier
            .size(44.dp)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Меню сборки", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .pixelSurface(if (pressed) MillidaPixel.sole else MillidaPixel.textShadow, MillidaCut.badge),
            contentAlignment = Alignment.Center,
        ) {
            MIcon(MIcons.more, size = 16.dp, color = MillidaPixel.white)
        }
    }
}

@Composable
private fun NewBuildTile(onCreate: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Nav)
    val progress = rememberPressProgress(pressed, MillidaMotion.tilePress, MillidaMotion.steps(2))
    Column(
        modifier
            .width(CARD_WIDTH)
            .pressShift(progress)
            .pixelSurface(if (pressed) colors.segmentFillOn else colors.surface2, MillidaCut.plate, colors.plateFrame())
            .clickable(interaction, indication = null, role = Role.Button, onClick = onCreate),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(BUILD_GLYPH)
                    .pixelSurface(colors.accent, MillidaCut.control, dots = true),
                contentAlignment = Alignment.Center,
            ) {
                MIcon(MIcons.plus, size = BUILD_GLYPH, color = colors.onAccent)
            }
        }
        Text(
            "Новая сборка",
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 12.dp),
            style = MillidaType.rowTitle.copy(fontSize = 14.5.sp),
            color = colors.fg,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
internal fun BuildMenuSheet(
    profile: Profile,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    MBottomSheet(onDismiss = onDismiss, title = profile.name) {
        MListRow(
            title = "Играть",
            subtitle = loaderLine(profile),
            icon = MIcons.play,
            onClick = onPlay,
            plate = false,
        )
        MListRow(
            title = "Открыть",
            subtitle = "Моды, миры, настройки",
            icon = MIcons.menuGear,
            onClick = onOpen,
            plate = false,
        )
        Spacer(Modifier.height(14.dp))
        MDangerButton(
            text = "Удалить",
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            icon = MIcons.menuTrash,
            size = MButtonSize.Medium,
        )
    }
}
