package net.millida.launcher.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import net.millida.launcher.UiState
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.LocalCompactUi
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelCorners
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.TourTarget
import net.millida.launcher.ui.character.CharacterLook
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed
import net.millida.launcher.ui.tourTarget
import kotlin.math.ceil

private val CORNER_ROW_MIN = 46.dp
private val CORNER_ROW_CHROME = 12.dp
private val DOCK_ROW_MIN = 60.dp

/** 68 compact dp at 0.72 is 49 physical dp: the «Играть» row keeps its thumb target. */
private val DOCK_ROW_MIN_COMPACT = 92.dp
private val PLATE_CHROME = 22.dp
private val PLATE_ART = 40.dp
private val PLATE_ICON = 26.dp

/**
 * The dock row grows with the system font: the «Сегодня играем» plate holds
 * three lines of text (caption, title and facts) in its padding, the gaps
 * between them and a few dp for rounding to whole pixels, so its last line is
 * never cut by the row's bottom.
 */
@Composable
@ReadOnlyComposable
private fun dockRowHeight(): Dp =
    (linesHeight(listOf(MillidaType.caption.lineHeight, MillidaType.sectionTitle.lineHeight, MillidaType.small.lineHeight)) + PLATE_CHROME)
        .coerceAtLeast(if (LocalCompactUi.current) DOCK_ROW_MIN_COMPACT else DOCK_ROW_MIN)

/** The corner row with the account's name over its kind: two lines in the plate's padding, never under its square size. */
@Composable
@ReadOnlyComposable
private fun cornerRowHeight(): Dp =
    (linesHeight(listOf(MillidaType.rowTitle.lineHeight, MillidaType.small.lineHeight)) + CORNER_ROW_CHROME)
        .coerceAtLeast(CORNER_ROW_MIN)

/**
 * The height of [lines] of text with the system font. Each line is converted
 * on its own: Android 14 scales large text less than small text, so one sum
 * of their sizes converted at once comes out too short at a large font.
 */
@Composable
@ReadOnlyComposable
internal fun linesHeight(lines: List<TextUnit>): Dp = with(LocalDensity.current) {
    var total = 0.dp
    for (line in lines) total += line.toDp()
    total
}

/** How high the «Играть» dock stands above the navigation bar: what the lobby's toasts rise over. */
@Composable
@ReadOnlyComposable
internal fun wideLobbyDockHeight(): Dp {
    val screen = LocalConfiguration.current
    val scale = if (LocalCompactUi.current) 1f else WideLobbyGrid.uiScale(screen.screenWidthDp.toFloat(), screen.screenHeightDp.toFloat())
    return WideLobbyGrid.dockHeight(dockRowHeight().value, 0f).dp * scale
}

/**
 * The lobby drawn [scale] times larger with the system font's own curve: only
 * the dp grow, so a large font on Android 14 keeps scaling the way the rest
 * of the app does.
 */
private class ScaledDensity(private val base: Density, scale: Float) : Density {
    override val density: Float = base.density * scale
    override val fontScale: Float = base.fontScale
    override fun Dp.toSp(): TextUnit = with(base) { this@toSp.toSp() }
    override fun TextUnit.toDp(): Dp = with(base) { this@toDp.toDp() }
}

/** What the landscape lobby needs from the home screen, gathered so the scene is one call. */
internal class WideLobbyActions(
    val onPick: () -> Unit,
    val onPlay: () -> Unit,
    val onCancel: () -> Unit,
    val onCharacter: () -> Unit,
    val onShop: () -> Unit,
    val onHosting: () -> Unit,
    val onFriends: () -> Unit,
    val onSettings: () -> Unit,
    val onRecommend: (PremiumPack) -> Unit,
    val onBanner: (LobbyBanner) -> Unit,
    val onChest: () -> Unit,
    val onParty: () -> Unit,
    val onInvite: () -> Unit,
    val onHelp: () -> Unit,
    val onFeedback: () -> Unit,
)

/**
 * The desktop lobby in landscape, laid out by [WideLobbyGrid]: the section
 * tiles down the start rail, the figure in the middle of the stage between
 * the rails, the corner buttons and the banner carousel ([banners], first
 * slide [bannerStart]) on the end rail, and «Сегодня играем» with «Играть»
 * in one dock along the bottom, «Играть» under the right thumb. Everything
 * else - the builds, servers and the catalog - is behind the plate, as on the
 * desktop. «Попробуй сегодня» takes the banner's place when the API switched
 * the banner off, and the account plate drops [accountMenu] under the corner
 * buttons. Messages and the rest of «Ещё» are reached through the friends
 * button and the settings. A tablet gets the same composition larger.
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
    banners: List<LobbyBanner>,
    bannerStart: Int,
    actions: WideLobbyActions,
    accountMenu: @Composable (maxHeight: Dp, close: () -> Unit, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val base = LocalDensity.current
        val scale = if (LocalCompactUi.current) 1f else WideLobbyGrid.uiScale(maxWidth.value, maxHeight.value)
        val density = remember(base, scale) { if (scale > 1f) ScaledDensity(base, scale) else base }
        CompositionLocalProvider(LocalDensity provides density) {
            WideLobbyScene(state, lobby, look, active, hostedCount, shopAlert, bonusReady, recommend, banners, bannerStart, actions, accountMenu)
        }
    }
}

@Composable
private fun WideLobbyScene(
    state: UiState,
    lobby: LobbyState,
    look: CharacterLook,
    active: Profile?,
    hostedCount: Int,
    shopAlert: Boolean,
    bonusReady: Boolean,
    recommend: PremiumPack?,
    banners: List<LobbyBanner>,
    bannerStart: Int,
    actions: WideLobbyActions,
    accountMenu: @Composable (maxHeight: Dp, close: () -> Unit, modifier: Modifier) -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = menuOpen) { menuOpen = false }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val top = with(density) { safe.getTop(density).toDp() }
    val bottom = with(density) { safe.getBottom(density).toDp() }
    val left = with(density) { safe.getLeft(density, direction).toDp() }
    val right = with(density) { safe.getRight(density, direction).toDp() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (startInset, endInset) = when (direction) {
            LayoutDirection.Ltr -> left to right
            LayoutDirection.Rtl -> right to left
        }
        val compact = LocalCompactUi.current
        val dockRow = dockRowHeight()
        val type = GridType(
            dockRow = dockRow.value,
            cornerRow = cornerRowHeight().value,
            bannerHeight = lobbyBannerHeight(LOBBY_BANNER_WIDTH, banners.size).value,
            recHeight = lobbyRecHeight().value,
        )
        val windowWidth = maxWidth.value
        val windowHeight = maxHeight.value
        val grid = remember(windowWidth, windowHeight, startInset, endInset, top, bottom, type, compact) {
            WideLobbyGrid.of(
                GridWindow(windowWidth, windowHeight, startInset.value, endInset.value, top.value, bottom.value, compact),
                type,
                bubbleReach = { frame -> stageBubbleReach(frame.dp).value },
                bubbleLift = { frame -> stageBubbleLift(frame.dp).value },
                bubbleSpan = STAGE_BUBBLE_SPAN.value,
            )
        }
        val figure = grid.figure
        val window = StageWindow(
            figureHeight = figure.frame.dp,
            feet = figure.feet.dp,
            centre = figure.centre.dp,
            bubbleRise = (windowHeight - figure.feet - figure.frame - grid.tiles.top).dp,
        )
        val speech = rememberStageSpeech()

        LobbyStage(
            state = state,
            look = look,
            totalSeconds = lobby.totalSeconds,
            onOpen = actions.onCharacter,
            window = window,
            speech = speech,
        )
        HubTileColumn(
            hostedCount = hostedCount,
            shopAlert = shopAlert,
            bonusReady = bonusReady,
            onShop = actions.onShop,
            onCharacter = actions.onCharacter,
            onHosting = actions.onHosting,
            width = grid.tileWidth.dp,
            height = grid.tileHeight.dp,
            overflow = (if (compact) WideLobbyGrid.COMPACT_TILE_OVERFLOW else WideLobbyGrid.TILE_OVERFLOW).dp,
            gap = WideLobbyGrid.TILE_GAP.dp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .cachedLayer()
                .padding(start = grid.tiles.start.dp, top = grid.tiles.top.dp),
        )
        // The banner, or «Попробуй сегодня» in its place, hangs between the
        // corner buttons and the dock at the desktop's size, shrunk as a whole
        // where a phone is lower than that: a card cut by the dock or one that
        // silently disappears both read as a mistake.
        val bannerShown = banners.isNotEmpty() && grid.bannerFits
        val recShown = !bannerShown && recommend != null && grid.recFits
        if (bannerShown || recShown) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .cachedLayer()
                    .padding(end = (windowWidth - grid.sky.end).dp, top = grid.sky.top.dp)
                    .size(width = grid.sky.width.dp, height = grid.sky.height.coerceAtLeast(0f).dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (bannerShown) {
                    LobbyBannerCarousel(
                        banners = banners,
                        start = bannerStart,
                        width = LOBBY_BANNER_WIDTH,
                        onOpen = actions.onBanner,
                        modifier = Modifier.scaledDown(grid.bannerScale),
                    )
                } else if (recommend != null) {
                    LobbyRecommend(
                        pack = recommend,
                        width = LOBBY_REC_WIDTH,
                        onOpen = { actions.onRecommend(recommend) },
                        modifier = Modifier.scaledDown(grid.recScale),
                    )
                }
            }
        }
        WideLobbyTopRow(
            state = state,
            onAccount = { menuOpen = !menuOpen },
            onFriends = actions.onFriends,
            onSettings = actions.onSettings,
            onFeedback = if (compact) actions.onFeedback else null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .cachedLayer()
                .padding(end = (windowWidth - grid.corner.end).dp, top = grid.corner.top.dp)
                .widthIn(max = grid.corner.width.dp),
        )
        LobbyDock(
            state = state,
            lobby = lobby,
            active = active,
            playWidth = grid.playWidth.dp,
            row = dockRow,
            actions = actions,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .cachedLayer()
                .padding(start = grid.dock.start.dp)
                .width(grid.dock.width.dp),
        )
        if (compact) {
            PartyChips(
                onParty = actions.onParty,
                onInvite = actions.onInvite,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = (windowHeight - grid.dock.top + 6f).dp),
            )
        }
        if (compact) {
            CornerButton(
                onClick = actions.onHelp,
                label = tr("Помощь"),
                badge = 0,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = (windowWidth - grid.corner.end).dp, bottom = (bottom.value + 8f).dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MIcon(MIcons.headset, size = 20.dp, color = millida.fg)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.Text(tr("Помощь"), style = MillidaType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold), color = millida.fg, maxLines = 1)
                }
            }
        }
        StageSpeechLayer(speech, window)
        if (menuOpen) {
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
                (windowHeight - grid.menuTop - bottom.value - WideLobbyGrid.GUTTER).dp,
                { menuOpen = false },
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = (windowWidth - grid.corner.end).dp, top = grid.menuTop.dp),
            )
        }
    }
}

/**
 * The element is rendered once into an offscreen layer and then only blitted: the lobby redraws every
 * frame for the wave scene, and re-running every tile's cut-corner clips and shadows 30 times a second
 * was the larger half of the GPU time (Redmi Note 13, Adreno 610: 19 ms -> 10 ms a frame, p50 of the
 * frame 34 -> 21 ms). A layer clips to its bounds and the tiles' soles and shadows hang past theirs, so
 * the layer is [slack] larger on every side and the layout reports the original size.
 */
private fun Modifier.cachedLayer(slack: Dp = 16.dp): Modifier =
    this
        .layout { measurable, constraints ->
            val extra = slack.roundToPx()
            val placeable = measurable.measure(constraints.offset(extra * 2, extra * 2))
            layout(placeable.width - extra * 2, placeable.height - extra * 2) { placeable.place(-extra, -extra) }
        }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .padding(slack)

/**
 * Draws the content at [scale] of the size it measures at and takes only that
 * much room, so a desktop card keeps its proportions and type relative to
 * each other on a window lower than the desktop's.
 */
private fun Modifier.scaledDown(scale: Float): Modifier = if (scale >= 1f) {
    this
} else {
    layout { measurable, _ ->
        val placeable = measurable.measure(Constraints())
        layout(ceil(placeable.width * scale).toInt(), ceil(placeable.height * scale).toInt()) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
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
    playWidth: Dp,
    row: Dp,
    actions: WideLobbyActions,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Open)
    val progress = rememberPressProgress(pressed)
    val plate = Modifier
        .height(row)
        .tourTarget(TourTarget.BUILDS)
        .pressShift(progress)
        .clickable(interaction, indication = null, role = Role.Button, onClickLabel = tr("Выбрать, во что играть"), onClick = actions.onPick)
    Row(
        modifier
            .pixelSurface(
                fill = MillidaPixel.lobbyDock,
                cut = MillidaCut.plate,
                frame = PixelFrame(outline = MillidaPixel.lobbyDockOutline, outlineWidth = 3.dp),
                corners = PixelCorners.Top,
            )
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(
                start = WideLobbyGrid.DOCK_SIDE_PAD.dp,
                end = WideLobbyGrid.DOCK_SIDE_PAD.dp,
                top = WideLobbyGrid.DOCK_PAD_TOP.dp,
                bottom = WideLobbyGrid.DOCK_PAD_BOTTOM.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(WideLobbyGrid.DOCK_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val filling = Modifier.weight(1f).then(plate)
        if (active == null) {
            EmptyModePlate(filling, artSize = PLATE_ART)
        } else {
            ModePlate(active, lobby, LobbyModeKind.BUILD, filling, artSize = PLATE_ART, iconSize = PLATE_ICON)
        }
        Box(
            Modifier
                .width(playWidth)
                .height(row),
        ) {
            val chest = LocalCompactUi.current
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LobbyPlayButton(
                    state = state,
                    playedSeconds = lobby.totalSeconds,
                    onPlay = actions.onPlay,
                    onCancel = actions.onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .tourTarget(TourTarget.PLAY),
                    fillHeight = true,
                )
                if (chest) ChestButton(actions.onChest)
            }
        }
    }
}

/** The desktop's `.play-chest`: the amber strip under «Играть» that leads to the shop's chests. */
@Composable
private fun ChestButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Click)
    val progress = rememberPressProgress(pressed)
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pressShift(progress)
            .pixelSurface(MillidaPixel.modeEmptyIcon, MillidaCut.control, PixelFrame.Button)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        MIcon(MIcons.chest, size = 18.dp, color = MillidaPixel.textShadow.copy(alpha = 0.85f))
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Text(
            tr("Открыть сундук"),
            style = MillidaType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
            color = MillidaPixel.textShadow.copy(alpha = 0.85f),
            maxLines = 1,
        )
    }
}

/** The desktop's `.lchip` pair over the dock: «Найти пати» and «Позвать друга». */
@Composable
private fun PartyChips(onParty: () -> Unit, onInvite: () -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CornerButton(onClick = onParty, label = tr("Найти пати"), badge = 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.search, size = 16.dp, color = millida.fg)
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                androidx.compose.material3.Text(tr("Найти пати"), style = MillidaType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold), color = millida.fg, maxLines = 1)
            }
        }
        CornerButton(onClick = onInvite, label = tr("Позвать друга"), badge = 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.friends, size = 16.dp, color = millida.fg)
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                androidx.compose.material3.Text(tr("Позвать друга"), style = MillidaType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold), color = millida.fg, maxLines = 1)
            }
        }
    }
}
