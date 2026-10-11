package net.millida.launcher.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import net.millida.launcher.UiState
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.core.profile.PlayTime
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.PixelCorners
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.TourTarget
import net.millida.launcher.ui.character.CharacterLook
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed
import net.millida.launcher.ui.tourTarget

private val EDGE = 16.dp
private val DOCK_ROW = 76.dp
private val DOCK_PAD_TOP = 8.dp
private val DOCK_PAD_BOTTOM = 10.dp
private val DOCK_GAP = 6.dp
private val DOCK_SIDE_PAD = 8.dp
private val PLAY_WIDTH = 168.dp
private val PLAY_WIDTH_NARROW = 136.dp
private val PLATE_MAX = 320.dp
private val PLATE_MIN = 180.dp
private val PLATE_ART = 56.dp
private val PLATE_ICON = 32.dp
private val TILE_WIDTH = 172.dp
private val TILE_WIDTH_NARROW = 148.dp
private val TILE_OVERFLOW = 16.dp
private val TILE_GAP = 8.dp
private val TILE_MIN = 58.dp
private val TILE_MAX = 104.dp
private val NARROW_WINDOW = 720.dp
private val TOP_ROW_ROOM = 76.dp
private val HOURS_ROOM = 34.dp
private val FEET_GAP = 6.dp
private val FIGURE_MIN = 140.dp
private val FIGURE_MAX = 420.dp
private const val FIGURE_HALF_WIDTH_SHARE = 0.3f
private val MENU_GAP = 8.dp
private val CORNER_ROW_HEIGHT = 52.dp

/** How high the «Играть» dock stands above the navigation bar: what the lobby's toasts rise over. */
internal val WIDE_LOBBY_DOCK_HEIGHT = DOCK_ROW + DOCK_PAD_TOP + DOCK_PAD_BOTTOM

/** What the landscape lobby needs from the home screen, gathered so the scene is one call. */
internal class WideLobbyActions(
    val onPick: () -> Unit,
    val onPlay: () -> Unit,
    val onCancel: () -> Unit,
    val onCharacter: () -> Unit,
    val onShop: () -> Unit,
    val onHosting: () -> Unit,
    val onFriends: () -> Unit,
    val onMessages: () -> Unit,
    val onMore: () -> Unit,
    val onSettings: () -> Unit,
    val onRecommend: (PremiumPack) -> Unit,
)

/**
 * The desktop lobby in landscape, the Brawl Stars scene: the stage fills the
 * window with the figure in the middle, the section tiles stand down the left
 * edge, the separate corner buttons sit top right, and «Сегодня играем» with
 * «Играть» lie in one dock pinned to the bottom. Everything else - the
 * builds, servers and the catalog - is behind the plate, as on the desktop.
 * «Рекомендуем» stands on the right where the window leaves it room, and
 * the account plate drops [accountMenu] under the corner buttons.
 */
@Composable
internal fun WideLobby(
    state: UiState,
    lobby: LobbyState,
    look: CharacterLook,
    active: Profile?,
    hostedCount: Int,
    shopAlert: Boolean,
    bonusReady: Boolean,
    recommend: PremiumPack?,
    actions: WideLobbyActions,
    accountMenu: @Composable (maxHeight: Dp, close: () -> Unit, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = menuOpen) { menuOpen = false }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val top = with(density) { safe.getTop(density).toDp() }
    val bottom = with(density) { safe.getBottom(density).toDp() }
    val start = with(density) { safe.getLeft(density, direction).toDp() }
    val end = with(density) { safe.getRight(density, direction).toDp() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val narrow = maxWidth < NARROW_WINDOW
        val tileWidth = if (narrow) TILE_WIDTH_NARROW else TILE_WIDTH
        val playWidth = if (narrow) PLAY_WIDTH_NARROW else PLAY_WIDTH
        val dockHeight = DOCK_ROW + DOCK_PAD_TOP + DOCK_PAD_BOTTOM + bottom
        val hoursShown = lobby.totalSeconds >= PlayTime.SHOWN_FROM_SECONDS
        val feet = dockHeight + if (hoursShown) HOURS_ROOM else FEET_GAP
        val figure = (maxHeight - feet - top - TOP_ROW_ROOM).coerceIn(FIGURE_MIN, FIGURE_MAX)
        val tileRoom = maxHeight - top - bottom - EDGE * 2
        val tileHeight = ((tileRoom - TILE_GAP * 2) / 3 - TILE_OVERFLOW).coerceIn(TILE_MIN, TILE_MAX)
        val (startSide, endSide) = when (direction) {
            LayoutDirection.Ltr -> start to end
            LayoutDirection.Rtl -> end to start
        }
        val sideRoom = startSide + EDGE + tileWidth + EDGE
        val dockChrome = DOCK_SIDE_PAD * 2 + DOCK_GAP + playWidth
        val centredPlate = maxWidth - sideRoom * 2 - dockChrome
        val centred = centredPlate >= PLATE_MIN
        val plateWidth = if (centred) {
            centredPlate.coerceAtMost(PLATE_MAX)
        } else {
            (maxWidth - sideRoom - endSide - EDGE - dockChrome).coerceIn(PLATE_MIN, PLATE_MAX)
        }

        LobbyStage(
            state = state,
            look = look,
            totalSeconds = lobby.totalSeconds,
            onOpen = actions.onCharacter,
            window = StageWindow(figureHeight = figure, feet = feet),
        )
        HubTileColumn(
            hostedCount = hostedCount,
            shopAlert = shopAlert,
            bonusReady = bonusReady,
            onShop = actions.onShop,
            onCharacter = actions.onCharacter,
            onHosting = actions.onHosting,
            width = tileWidth,
            height = tileHeight,
            overflow = TILE_OVERFLOW,
            gap = TILE_GAP,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = startSide + EDGE, top = top + EDGE - TILE_OVERFLOW / 2),
        )
        val bandTop = top + EDGE + CORNER_ROW_HEIGHT + EDGE
        val band = maxHeight - dockHeight - bandTop
        val recRoom = maxWidth / 2 - figure * FIGURE_HALF_WIDTH_SHARE - endSide - EDGE
        if (recommend != null && band >= LOBBY_REC_HEIGHT && recRoom >= LOBBY_REC_MIN_WIDTH) {
            LobbyRecommend(
                pack = recommend,
                width = recRoom.coerceAtMost(LOBBY_REC_WIDTH),
                onOpen = { actions.onRecommend(recommend) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = endSide + EDGE, top = bandTop + (band - LOBBY_REC_HEIGHT) / 2),
            )
        }
        WideLobbyTopRow(
            state = state,
            onAccount = { menuOpen = !menuOpen },
            onFriends = actions.onFriends,
            onMessages = actions.onMessages,
            onMore = actions.onMore,
            onSettings = actions.onSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = endSide + EDGE, top = top + EDGE)
                .widthIn(max = (maxWidth - sideRoom - endSide - EDGE).coerceAtLeast(CORNER_ROW_HEIGHT)),
        )
        if (hoursShown) {
            PlaytimeBadge(
                lobby.totalSeconds,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = dockHeight + FEET_GAP),
            )
        }
        LobbyDock(
            state = state,
            lobby = lobby,
            active = active,
            plateWidth = plateWidth,
            playWidth = playWidth,
            actions = actions,
            modifier = if (centred) {
                Modifier.align(Alignment.BottomCenter)
            } else {
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = endSide + EDGE)
            },
        )
        if (menuOpen) {
            val menuTop = top + EDGE + CORNER_ROW_HEIGHT + MENU_GAP
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { menuOpen = false },
                    ),
            )
            accountMenu(
                maxHeight - menuTop - bottom - EDGE,
                { menuOpen = false },
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = endSide + EDGE, top = menuTop),
            )
        }
    }
}

/**
 * «Сегодня играем» and «Играть» as one piece on a shared dark plate whose
 * bottom runs off the window edge, the desktop's `.lobby-row`. The plate
 * opens the library; «Играть» only starts the game.
 */
@Composable
private fun LobbyDock(
    state: UiState,
    lobby: LobbyState,
    active: Profile?,
    plateWidth: Dp,
    playWidth: Dp,
    actions: WideLobbyActions,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Open)
    val progress = rememberPressProgress(pressed)
    val plate = Modifier
        .width(plateWidth)
        .height(DOCK_ROW)
        .tourTarget(TourTarget.BUILDS)
        .pressShift(progress)
        .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Выбрать, во что играть", onClick = actions.onPick)
    Row(
        modifier
            .pixelSurface(
                fill = MillidaPixel.lobbyDock,
                cut = MillidaCut.plate,
                frame = PixelFrame(outline = MillidaPixel.lobbyDockOutline, outlineWidth = 3.dp),
                corners = PixelCorners.Top,
            )
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(start = DOCK_SIDE_PAD, end = DOCK_SIDE_PAD, top = DOCK_PAD_TOP, bottom = DOCK_PAD_BOTTOM),
        horizontalArrangement = Arrangement.spacedBy(DOCK_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active == null) {
            EmptyModePlate(plate, artSize = PLATE_ART)
        } else {
            ModePlate(active, lobby, LobbyModeKind.BUILD, plate, artSize = PLATE_ART, iconSize = PLATE_ICON)
        }
        Box(
            Modifier
                .width(playWidth)
                .height(DOCK_ROW),
        ) {
            LobbyPlayButton(
                state = state,
                playedSeconds = lobby.totalSeconds,
                onPlay = actions.onPlay,
                onCancel = actions.onCancel,
                modifier = Modifier
                    .fillMaxSize()
                    .tourTarget(TourTarget.PLAY),
                fillHeight = true,
            )
        }
    }
}
