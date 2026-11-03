package net.millida.launcher.ui.home

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.account.Account
import net.millida.launcher.core.cosmetics.CosmeticItem
import net.millida.launcher.core.cosmetics.EmoteBubble
import net.millida.launcher.core.cosmetics.EmotePlay
import net.millida.launcher.core.cosmetics.EmoteShow
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.media.LiveWallpaper
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.profile.PlayTime
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MAvatar
import net.millida.launcher.ui.MCounterBadge
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MLocalImage
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.RoomyFrame
import net.millida.launcher.ui.SkinPose
import net.millida.launcher.ui.SkinStage
import net.millida.launcher.ui.TourTarget
import net.millida.launcher.ui.character.CharacterLook
import net.millida.launcher.ui.character.StageOutfit
import net.millida.launcher.ui.character.rememberStageOutfit
import net.millida.launcher.ui.headAvatarUrl
import net.millida.launcher.ui.accountKindLabel
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.settings.rememberLookModel
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed
import net.millida.launcher.ui.social.EltImage
import net.millida.launcher.ui.tourTarget
import kotlin.math.floor
import kotlin.random.Random

private val STAGE_HEIGHT = 300.dp
private val GROUND_HEIGHT = 46.dp
private val FIGURE_WIDTH = 190.dp
private val FIGURE_HEIGHT = 226.dp
private val FIGURE_TOP = STAGE_HEIGHT - 22.dp - FIGURE_HEIGHT

private val SKY_DOTS = floatArrayOf(0.08f, 0.18f, 0.22f, 0.46f, 0.37f, 0.12f, 0.61f, 0.30f, 0.74f, 0.09f, 0.88f, 0.41f, 0.53f, 0.55f)

@Composable
internal fun LobbyTopRow(
    state: UiState,
    onAccount: () -> Unit,
    onFriends: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            AccountPlate(state.account, onAccount, Modifier.tourTarget(TourTarget.ACCOUNT))
        }
        Spacer(Modifier.width(10.dp))
        MIconButton(
            icon = MIcons.friends,
            onClick = onFriends,
            modifier = Modifier.tourTarget(TourTarget.FRIENDS),
            badge = state.friendsBadge,
            label = tr("Друзья"),
        )
        Spacer(Modifier.width(8.dp))
        MIconButton(
            icon = MIcons.settings,
            onClick = onSettings,
            modifier = Modifier.tourTarget(TourTarget.SETTINGS),
            label = tr("Настройки"),
        )
    }
}

/**
 * The landscape lobby's top right corner, the desktop's `.lb-btns`: separate
 * buttons over the scene - friends with who is online, their requests and
 * unread messages (the chat lives behind it), the account that drops its
 * menu, and the settings gear that also leads to the sections the portrait
 * «Ещё» holds. It takes the widest [CornerDensity] that fits the width it is
 * given.
 */
@Composable
internal fun WideLobbyTopRow(
    state: UiState,
    onAccount: () -> Unit,
    onFriends: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onFeedback: (() -> Unit)? = null,
) {
    FirstFitting(CornerDensity.entries, modifier) { density ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FriendsCorner(
                state,
                onFriends,
                Modifier.tourTarget(TourTarget.FRIENDS),
                badge = state.friendsBadge,
                caption = density.friendsCaption,
            )
            AccountPlate(
                state.account,
                onAccount,
                Modifier.tourTarget(TourTarget.ACCOUNT),
                dropsMenu = density.accountName,
                showsName = density.accountName,
                showsLine = density.accountLine,
            )
            if (onFeedback != null) {
                CornerButton(onClick = onFeedback, label = tr("Что думаешь о лаунчере?"), badge = 0) {
                    MIcon(MIcons.gift, size = 22.dp, color = millida.accentText)
                }
            }
            CornerButton(
                onClick = onSettings,
                label = tr("Настройки"),
                badge = 0,
                modifier = Modifier.tourTarget(TourTarget.SETTINGS),
            ) {
                MIcon(MIcons.settings, size = 22.dp, color = millida.fg)
            }
        }
    }
}

/**
 * How much of the corner row a window has room for, widest first: as the
 * desktop's titlebar narrows it drops the account's second line and then
 * the names, so the row shrinks instead of running into the section tiles.
 */
private enum class CornerDensity(
    val friendsCaption: Boolean,
    val accountName: Boolean,
    val accountLine: Boolean,
) {
    FULL(friendsCaption = true, accountName = true, accountLine = true),
    SHORT(friendsCaption = true, accountName = true, accountLine = false),
    ICONS(friendsCaption = false, accountName = false, accountLine = false),
}

/**
 * Lays out the first of [options] whose [content] fits the incoming width
 * unsqueezed, and the last one squeezed when none does. Later options are
 * composed only while the earlier ones overflow.
 */
@Composable
private fun <T> FirstFitting(options: List<T>, modifier: Modifier, content: @Composable (T) -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val free = loose.copy(maxWidth = Constraints.Infinity)
        var picked: List<Placeable> = emptyList()
        for ((index, option) in options.withIndex()) {
            val last = index == options.lastIndex
            picked = subcompose(index) { content(option) }.map { it.measure(if (last) loose else free) }
            val width = picked.maxOfOrNull { it.width } ?: 0
            if (last || width <= constraints.maxWidth) break
        }
        val width = (picked.maxOfOrNull { it.width } ?: 0).coerceAtMost(constraints.maxWidth)
        val height = picked.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            picked.forEach { it.place(0, 0) }
        }
    }
}

private val CORNER_HEIGHT = 46.dp
private val CORNER_HEAD = 28.dp

/** One face of who is online and their number, a grey head when nobody is: a single head keeps the corner narrow. */
@Composable
private fun FriendsCorner(
    state: UiState,
    onClick: () -> Unit,
    modifier: Modifier,
    badge: Int,
    caption: Boolean = true,
) {
    val online = state.friends.filter { it.online || it.playing }
    CornerButton(onClick = onClick, label = tr("Друзья, в сети {size}", online.size), badge = badge, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (online.isEmpty()) {
                MAvatar(url = null, size = CORNER_HEAD, dimmed = true)
            } else {
                online.take(3).forEachIndexed { index, friend ->
                    if (index > 0) Spacer(Modifier.width(4.dp))
                    MAvatar(url = friend.avatarUrl ?: headAvatarUrl(friend.nickname), size = CORNER_HEAD)
                }
            }
        }
    }
}

@Composable
internal fun CornerButton(
    onClick: () -> Unit,
    label: String,
    badge: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Nav)
    val progress = rememberPressProgress(pressed)
    Box(modifier) {
        Box(
            Modifier
                .pressShift(progress)
                .heightIn(min = CORNER_HEIGHT)
                .widthIn(min = CORNER_HEIGHT)
                .pixelSurface(if (pressed) colors.surface2 else colors.surface, MillidaCut.control, colors.plateFrame())
                .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = label }
                .padding(start = 12.dp, end = 12.dp, top = 5.dp, bottom = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        MCounterBadge(
            badge,
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-6).dp),
        )
    }
}

@Composable
private fun AccountPlate(
    account: Account?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dropsMenu: Boolean = false,
    showsName: Boolean = true,
    showsLine: Boolean = true,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Nav)
    val progress = rememberPressProgress(pressed)
    Row(
        modifier
            .pressShift(progress)
            .heightIn(min = 52.dp)
            .pixelSurface(if (pressed) colors.surface2 else colors.surface, MillidaCut.control, colors.plateFrame())
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = tr("Аккаунт"), onClick = onClick)
            .padding(start = 6.dp, end = if (showsName) 14.dp else 6.dp, top = 5.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MAvatar(
            url = account?.nick?.takeIf { it.isNotBlank() }?.let { headAvatarUrl(it) },
            size = 40.dp,
            ring = colors.surface,
        )
        if (showsName) {
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    account?.nick?.takeIf { it.isNotBlank() } ?: tr("Войти"),
                    style = MillidaType.rowTitle.copy(fontSize = 16.sp, fontWeight = FontWeight.Black),
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showsLine) {
                    Text(
                        accountLine(account),
                        style = MillidaType.small,
                        color = colors.fgSubtle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (dropsMenu) {
            Spacer(Modifier.width(8.dp))
            MIcon(MIcons.chevronDown, size = 14.dp, color = colors.fgSubtle)
        }
    }
}

private fun accountLine(account: Account?): String = account?.let { accountKindLabel(it.kind) } ?: tr("Аккаунт не выбран")

/**
 * The stage spread over the whole landscape lobby, the desktop's full-window
 * scene: the figure is [figureHeight] tall, its middle stands [centre] from
 * the start edge and its feet [feet] above the bottom edge, clear of the play
 * dock. Its bubble rises at most [bubbleRise] over the top of the figure's
 * frame, a negative rise keeping it that far below, so it never goes off the
 * window or under the corner buttons.
 */
@Immutable
internal class StageWindow(val figureHeight: Dp, val feet: Dp, val centre: Dp, val bubbleRise: Dp)

/**
 * How far right of the figure's middle the bubble of a figure [figureHeight]
 * tall reaches at the top of its pop, so the lobby keeps the corner buttons
 * and the cards clear of it.
 */
internal fun stageBubbleReach(figureHeight: Dp): Dp {
    val side = BUBBLE_SIDE.value
    val right = side * (BUBBLE_ORIGIN_X + (1f - BUBBLE_ORIGIN_X) * BUBBLE_OVER) - side / 2f
    return BUBBLE_X * (figureHeight / FIGURE_HEIGHT) + right.dp
}

/** How far above the top of the frame of a figure [figureHeight] tall its bubble starts. */
internal fun stageBubbleLift(figureHeight: Dp): Dp = (FIGURE_TOP - BUBBLE_Y) * (figureHeight / FIGURE_HEIGHT)

/** How tall the bubble stands at the top of its pop, its tail included. */
internal val STAGE_BUBBLE_SPAN: Dp get() = BUBBLE_SIDE * BUBBLE_OVER + BUBBLE_CELL * (TAIL_ROWS.size - 1)

/** Where the landscape figure's frame stands in the lobby, so the figure and its bubble share one place. */
private fun Modifier.stageFrame(window: StageWindow): Modifier {
    val width = FIGURE_WIDTH * (window.figureHeight / FIGURE_HEIGHT)
    return offset(x = window.centre - width / 2)
        .padding(bottom = window.feet)
        .size(width = width, height = window.figureHeight)
}

/** The emote bubble the figure says now and then, and which emoji it shows, by the desktop's pacing. */
@Stable
internal class StageSpeech {
    private val gate = BubbleGate()
    private var shown by mutableStateOf<Speech?>(null)

    fun onEmote(item: CosmeticItem) {
        gate.next(item)?.let { shown = it }
    }

    @Composable
    fun Bubble(modifier: Modifier) {
        val current = shown ?: return
        key(current.key) {
            EmoteSpeech(
                id = current.id,
                onGone = { if (shown === current) shown = null },
                modifier = modifier,
            )
        }
    }
}

@Composable
internal fun rememberStageSpeech(): StageSpeech = remember { StageSpeech() }

/**
 * The landscape figure's bubble over the frame [window] places the figure in.
 * The lobby draws it as its own top layer: inside the stage it lay under the
 * corner buttons and the cards the head rises between.
 */
@Composable
internal fun BoxScope.StageSpeechLayer(speech: StageSpeech, window: StageWindow) {
    val scale = window.figureHeight / FIGURE_HEIGHT
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .stageFrame(window),
    ) {
        speech.Bubble(
            Modifier
                .align(Alignment.TopCenter)
                .offset(x = BUBBLE_X * scale, y = maxOf((BUBBLE_Y - FIGURE_TOP) * scale, -window.bubbleRise)),
        )
    }
}

/**
 * The lobby stage. With a [window] it is the landscape scene and leaves the
 * [speech] bubble to the caller's [StageSpeechLayer].
 */
@Composable
internal fun LobbyStage(
    state: UiState,
    look: CharacterLook,
    totalSeconds: Long,
    onOpen: () -> Unit,
    window: StageWindow? = null,
    speech: StageSpeech = rememberStageSpeech(),
) {
    val colors = millida
    val outfit by rememberLobbyOutfit(state, look) { item -> speech.onEmote(item) }
    val sky = colors.accentDeep
    val ground = colors.accent.mixSrgb(MillidaPixel.serverDark, 0.38f)
    val grass = colors.accent
    val dirt = colors.accent.mixSrgb(MillidaPixel.serverDark, 0.22f)
    val groundHeight = GROUND_HEIGHT
    // Landscape spreads the desktop's own scene over the window; the portrait
    // stage keeps its grass plate.
    val scene = if (window == null) {
        Modifier
            .fillMaxWidth()
            .height(STAGE_HEIGHT)
            .tourTarget(TourTarget.CHARACTER)
            .pixelSurface(sky, MillidaCut.plate)
            .drawBehind {
                val cell = floor(6.dp.toPx())
                for (index in SKY_DOTS.indices step 2) {
                    drawRect(
                        MillidaPixel.whiteSoft.copy(alpha = 0.10f),
                        Offset(floor(size.width * SKY_DOTS[index]), floor((size.height - groundHeight.toPx()) * SKY_DOTS[index + 1])),
                        Size(cell, cell),
                    )
                }
                val groundTop = floor(size.height - groundHeight.toPx())
                drawRect(ground, Offset(0f, groundTop), Size(size.width, size.height - groundTop))
                drawRect(grass, Offset(0f, groundTop), Size(size.width, floor(6.dp.toPx())))
                var x = floor(cell * 1.5f)
                var row = 0
                while (x < size.width) {
                    val y = groundTop + cell * (2 + row % 3)
                    drawRect(dirt, Offset(x, y), Size(cell, cell))
                    x += cell * (5 + row % 4)
                    row++
                }
            }
    } else {
        Modifier.fillMaxSize()
    }
    Box(scene) {
        if (window != null) {
            // The glow, rays and floor never change, but drawn inline they were rasterised again on
            // every wave/spark frame. Their own offscreen layer is rendered once and then only blitted
            // (Redmi Note 13, idle lobby, with the tile layers: GPU 18-20 ms -> 10 ms a frame, p50 32 -> 21 ms).
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .stageScene(colors.accent, window.feet + window.figureHeight * RoomyFrame.FEET_SHARE, window.centre),
            )
        }
        val live = LiveWallpaper.of(state.liveWallpaper)
        if (live != null) {
            LiveWallpaperLayer(live, state.wallpaperMotion, Modifier.fillMaxSize())
        } else if (window != null && state.wallpaperPath.isEmpty()) {
            StageSceneMotion(state.wallpaperMotion, Modifier.fillMaxSize())
        } else if (state.wallpaperPath.isNotEmpty()) {
            MLocalImage(path = state.wallpaperPath, modifier = Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MillidaPixel.textShadow),
            )
        }
        if (window == null) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LobbyFigure(look, outfit, onOpen, Modifier.size(width = FIGURE_WIDTH, height = FIGURE_HEIGHT))
            }
            speech.Bubble(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = BUBBLE_X, y = BUBBLE_Y),
            )
            PlaytimeBadge(
                totalSeconds,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )
        } else {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .stageFrame(window),
            ) {
                LobbyFigure(
                    look,
                    outfit,
                    onOpen,
                    Modifier
                        .fillMaxSize()
                        .tourTarget(TourTarget.CHARACTER),
                )
            }
        }
    }
}

@Composable
private fun LobbyFigure(look: CharacterLook, outfit: StageOutfit?, onOpen: () -> Unit, modifier: Modifier) {
    SkinStage(
        skinUrl = look.skinUrl,
        slim = look.slim,
        modifier = modifier,
        capeUrl = look.capeUrl,
        pose = SkinPose.Still,
        interactive = true,
        onTap = onOpen,
        outfit = outfit,
        // The frame fits the body only, as on the desktop: arms of an emote, wings
        // and props reach past it into the scene, which clips them at its own edge.
        clipToFrame = false,
    )
}

private val BADGE_SIDE_PAD = 8.dp
private val BADGE_GAP = 6.dp
private val BADGE_ICON = 16.dp

/** The hours played through the launcher with the way to the next tier, the desktop's `.lobby-hours`. */
@Composable
internal fun PlaytimeBadge(totalSeconds: Long, modifier: Modifier = Modifier) {
    if (totalSeconds < PlayTime.SHOWN_FROM_SECONDS) return
    val tier = PlayTime.tier(totalSeconds)
    Row(
        modifier
            .pixelSurface(MillidaPixel.textShadow, MillidaCut.badge)
            .padding(horizontal = BADGE_SIDE_PAD, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BADGE_GAP),
    ) {
        MIcon(MIcons.trophy, size = BADGE_ICON)
        Text(PlayTime.format(totalSeconds), style = MillidaType.pixel, color = MillidaPixel.white, maxLines = 1)
        if (tier.next != null) TierStrip(tier.progress)
    }
}

/**
 * Emotes the figure plays now and then in the lobby: only ones that read from
 * the front while standing, the desktop's list (LobbyCharacter.tsx). Flights,
 * lying and running take the figure out of the frame.
 */
private val LOBBY_EMOTES = listOf(
    "67",
    "EXCLAIMED_POINT",
    "ANIME_LOVE",
    "WAVE_R",
    "DAB",
    "CLAP",
    "YES",
    "KISS",
    "FLOSS",
    "WHIP_NAE",
    "SHUFFLE_DANCE",
    "VIBE_DANCE",
    "HYPERPOP_DANCE",
    "TAUNT_DANCE",
    "PIGLIN_VICTORY",
    "EVIL_LAUGH",
    "BOW",
    "WHEW",
)

private const val FIRST_SHOW_MIN_MS = 1_200L
private const val FIRST_SHOW_MAX_MS = 2_500L
private const val SHOW_GAP_MIN_MS = 2_500L
private const val SHOW_GAP_MAX_MS = 5_000L
private const val BUILD_WAIT_MS = 6_000L
private const val MILLIS = 1_000f

/**
 * The lobby figure wears what the account wears, like the desktop lobby: the
 * home screen is the showcase of what the player put on. A worn emote plays;
 * otherwise one from the list plays between pauses. A piece that does not
 * arrive is left out quietly here - the character screen is where it is reported.
 */
@Composable
private fun rememberLobbyOutfit(state: UiState, look: CharacterLook, onEmoteStart: (CosmeticItem) -> Unit): State<StageOutfit?> {
    // The studio preview has no application to build the view model from; the
    // figure stands there in the bare skin.
    if (LocalInspectionMode.current) return remember { mutableStateOf<StageOutfit?>(null) }
    val model: LauncherViewModel = viewModel()
    val wardrobe = model.cosmeticWardrobe
    val cosmetics by wardrobe.state.collectAsState()
    val reduced = LocalReducedMotion.current
    val lookPrefs by rememberLookModel().state.collectAsState()
    val dance = lookPrefs.characterDance
    val accountId = state.account?.id.orEmpty()
    val signedIn = state.millidaSignedIn
    val uuid = state.gameProfile?.uuid
    LaunchedEffect(accountId, signedIn, uuid) {
        if (signedIn && state.gameProfile == null) model.loadSkins()
        val current = wardrobe.state.value
        val stale = current.accountId != accountId || current.signedIn != signedIn || !current.catalogReady
        if (stale || (uuid != null && !current.wornReady)) wardrobe.load(accountId, signedIn, uuid)
    }
    val worn = remember(cosmetics.worn, cosmetics.catalog) { cosmetics.worn.mapNotNull { cosmetics.catalog.byId(it.id) } }
    val wornEmote = worn.any { it.isEmote }
    val showable = remember(cosmetics.catalog) {
        LOBBY_EMOTES.mapNotNull { code -> cosmetics.catalog.byId(code)?.takeIf { it.model.isNotEmpty() } }
    }
    var showing by remember { mutableStateOf<CosmeticItem?>(null) }
    val dressed = showing?.let { worn + it } ?: worn
    val outfit = rememberStageOutfit(dressed, look.slim, wardrobe) {}
    val emoteStarted by rememberUpdatedState(onEmoteStart)
    LaunchedEffect(showable, wornEmote, reduced, signedIn, dance) {
        showing = null
        if (reduced || !dance || !signedIn || wornEmote || showable.isEmpty()) return@LaunchedEffect
        delay(Random.nextLong(FIRST_SHOW_MIN_MS, FIRST_SHOW_MAX_MS))
        val bag = ArrayDeque<CosmeticItem>()
        var shown: EmotePlay? = null
        while (true) {
            if (bag.isEmpty()) bag.addAll(showable.shuffled())
            val item = bag.removeFirst()
            showing = item
            val stale = shown
            var announced = false
            val plays = snapshotFlow { outfit.value?.emote }.onEach { play ->
                if (!announced && play != null && play !== stale) {
                    announced = true
                    emoteStarted(item)
                }
            }
            shown = EmoteShow.playThrough(plays, stale, BUILD_WAIT_MS) { play ->
                (play.showSeconds() * MILLIS).toLong()
            } ?: shown
            showing = null
            delay(Random.nextLong(SHOW_GAP_MIN_MS, SHOW_GAP_MAX_MS))
        }
    }
    return outfit
}

private val TIER_STRIP_WIDTH = 44.dp
private val TIER_STRIP_HEIGHT = 6.dp
private val TIER_STRIP_PAD = 4.dp

/** The way to the next playtime tier, a bare strip as on the desktop: the number lives in the label beside it. */
@Composable
private fun TierStrip(progress: Float) {
    val fill = millida.accent
    Box(
        Modifier
            .padding(start = TIER_STRIP_PAD)
            .size(TIER_STRIP_WIDTH, TIER_STRIP_HEIGHT)
            .drawBehind {
                drawRect(MillidaPixel.divider)
                val filled = floor(size.width * progress.coerceIn(0f, 1f))
                val from = if (layoutDirection == LayoutDirection.Rtl) size.width - filled else 0f
                drawRect(fill, topLeft = Offset(from, 0f), size = Size(filled, size.height))
            },
    )
}

/** Where the bubble sits: right of the head at eye level, its tail to the face (the desktop's EmoteBubble). */
private val BUBBLE_X = 64.dp
private val BUBBLE_Y = 25.dp
private val BUBBLE_CELL = 3.dp
private val BUBBLE_EMOJI = 44.dp
private val BUBBLE_PAD = 8.dp
private val BUBBLE_SIDE = BUBBLE_EMOJI + (BUBBLE_PAD + BUBBLE_CELL) * 2
private const val BUBBLE_ORIGIN_X = 0.2f
private const val BUBBLE_FROM = 0.3f
private const val BUBBLE_OVER = 1.12f
private const val BUBBLE_LEAVE = 0.5f
private const val BUBBLE_IN_HALF_MS = 130

private class Speech(val id: String, val key: Long)

/** Decides which emotes get a bubble and which emoji, by the desktop's pacing. */
private class BubbleGate {
    private var nextAt = 0L
    private var lastId: String? = null

    fun next(item: CosmeticItem): Speech? {
        val now = SystemClock.uptimeMillis()
        if (!EmoteBubble.due(now, nextAt, Random.nextDouble())) return null
        nextAt = EmoteBubble.nextAt(now, Random.nextDouble())
        val mood = EmoteBubble.mood("${item.name} ${item.animation}")
        val id = EmoteBubble.emoji(mood, lastId) { Random.nextInt(it) }
        lastId = id
        return Speech(id, now)
    }
}

/**
 * Rows of the stepped tail, top to bottom, in cells: the outline and the fill.
 * The top row lies over the bubble's bottom edge and opens it downwards.
 */
private val TAIL_ROWS = listOf("dwwwd", "dwwd", "dwd", "dd")

@Composable
private fun EmoteSpeech(id: String, onGone: () -> Unit, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(BUBBLE_FROM) }
    val fade = remember { Animatable(0f) }
    val gone by rememberUpdatedState(onGone)
    val originX = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f - BUBBLE_ORIGIN_X else BUBBLE_ORIGIN_X
    LaunchedEffect(Unit) {
        launch { fade.animateTo(1f, tween(BUBBLE_IN_HALF_MS, easing = MillidaMotion.steps(2))) }
        scale.animateTo(BUBBLE_OVER, tween(BUBBLE_IN_HALF_MS, easing = MillidaMotion.steps(2)))
        scale.animateTo(1f, tween(BUBBLE_IN_HALF_MS, easing = MillidaMotion.steps(2)))
        delay(EmoteBubble.HOLD_MS)
        launch { fade.animateTo(0f, tween(EmoteBubble.OUT_MS.toInt(), easing = MillidaMotion.steps(3))) }
        scale.animateTo(BUBBLE_LEAVE, tween(EmoteBubble.OUT_MS.toInt(), easing = MillidaMotion.steps(3)))
        gone()
    }
    Column(
        modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            alpha = fade.value
            transformOrigin = TransformOrigin(originX, 1f)
        },
    ) {
        Box(
            Modifier
                .pixelSurface(
                    MillidaPixel.bubbleFill,
                    MillidaCut.control,
                    PixelFrame(
                        outline = MillidaPixel.bubbleInk,
                        outlineWidth = BUBBLE_CELL,
                        top = MillidaPixel.white,
                        topWidth = 2.dp,
                        bottom = MillidaPixel.bubbleShade,
                        bottomWidth = BUBBLE_CELL,
                    ),
                )
                .padding(BUBBLE_PAD + BUBBLE_CELL),
        ) {
            EltImage(id, Modifier.size(BUBBLE_EMOJI))
        }
        Canvas(
            Modifier
                .padding(start = 12.dp)
                .offset(y = -BUBBLE_CELL)
                .size(BUBBLE_CELL * TAIL_ROWS.first().length, BUBBLE_CELL * TAIL_ROWS.size),
        ) {
            val cell = floor(BUBBLE_CELL.toPx())
            val last = TAIL_ROWS.first().length - 1
            val mirrored = layoutDirection == LayoutDirection.Rtl
            TAIL_ROWS.forEachIndexed { row, cells ->
                cells.forEachIndexed { column, kind ->
                    drawRect(
                        if (kind == 'd') MillidaPixel.bubbleInk else MillidaPixel.bubbleFill,
                        Offset((if (mirrored) last - column else column) * cell, row * cell),
                        Size(cell, cell),
                    )
                }
            }
        }
    }
}
