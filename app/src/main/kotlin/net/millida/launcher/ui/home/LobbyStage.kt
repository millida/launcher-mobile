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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.account.Account
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.cosmetics.CosmeticItem
import net.millida.launcher.core.cosmetics.EmoteBubble
import net.millida.launcher.core.cosmetics.EmotePlay
import net.millida.launcher.core.cosmetics.EmoteShow
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
import net.millida.launcher.ui.SkinPose
import net.millida.launcher.ui.SkinStage
import net.millida.launcher.ui.TourTarget
import net.millida.launcher.ui.character.CharacterLook
import net.millida.launcher.ui.character.StageOutfit
import net.millida.launcher.ui.character.rememberStageOutfit
import net.millida.launcher.ui.headAvatarUrl
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface
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
private val GROUND_UNDER_FEET = 24.dp
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
            label = "Друзья",
        )
        Spacer(Modifier.width(8.dp))
        MIconButton(
            icon = MIcons.settings,
            onClick = onSettings,
            modifier = Modifier.tourTarget(TourTarget.SETTINGS),
            label = "Настройки",
        )
    }
}

/**
 * The landscape lobby's top right corner, the desktop's `.lb-btns`: separate
 * buttons over the scene - friends with who is online and their requests,
 * the account that drops its menu, the messages with the unread count, «Ещё»
 * for the sections the desktop keeps elsewhere, and the settings gear. It
 * takes the widest [CornerDensity] that fits the width it is given.
 */
@Composable
internal fun WideLobbyTopRow(
    state: UiState,
    onAccount: () -> Unit,
    onFriends: () -> Unit,
    onMessages: () -> Unit,
    onMore: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
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
                badge = state.incomingRequests,
                heads = density.heads,
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
            if (state.millidaSignedIn) {
                CornerButton(onClick = onMessages, label = "Сообщения", badge = state.unreadMessages) {
                    MIcon(MIcons.chat, size = 22.dp, color = millida.fg)
                }
            }
            CornerButton(onClick = onMore, label = "Ещё", badge = 0) {
                MIcon(MIcons.more, size = 22.dp, color = millida.fg)
            }
            CornerButton(
                onClick = onSettings,
                label = "Настройки",
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
    val heads: Int,
    val friendsCaption: Boolean,
    val accountName: Boolean,
    val accountLine: Boolean,
) {
    FULL(CORNER_HEADS, friendsCaption = true, accountName = true, accountLine = true),
    SHORT(1, friendsCaption = true, accountName = true, accountLine = false),
    ICONS(1, friendsCaption = false, accountName = false, accountLine = false),
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

private val CORNER_HEIGHT = 52.dp
private val CORNER_HEAD = 30.dp
private val CORNER_HEAD_OVERLAP = 10.dp
private const val CORNER_HEADS = 3

/** Faces of who is online, overlapping as a party, and their number; grey heads when nobody is. */
@Composable
private fun FriendsCorner(
    state: UiState,
    onClick: () -> Unit,
    modifier: Modifier,
    badge: Int,
    heads: Int = CORNER_HEADS,
    caption: Boolean = true,
) {
    val colors = millida
    val online = state.friends.filter { it.online || it.playing }
    CornerButton(onClick = onClick, label = "Друзья, в сети ${online.size}", badge = badge, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val faces = online.take(heads).map { it.avatarUrl ?: headAvatarUrl(it.nickname) }
            val shown = faces.ifEmpty { List(heads.coerceAtMost(2)) { null } }
            Box {
                shown.forEachIndexed { index, url ->
                    MAvatar(
                        url = url,
                        modifier = Modifier.padding(start = (CORNER_HEAD - CORNER_HEAD_OVERLAP) * index),
                        size = CORNER_HEAD,
                        dimmed = faces.isEmpty(),
                    )
                }
            }
            Spacer(Modifier.width(if (caption) 10.dp else 6.dp))
            Column {
                Text(
                    online.size.toString(),
                    style = MillidaType.rowTitle.copy(fontSize = 18.sp, lineHeight = 18.sp, fontWeight = FontWeight.Black),
                    color = colors.accentText,
                    maxLines = 1,
                )
                if (caption) {
                    Text("в сети", style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun CornerButton(
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
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Аккаунт", onClick = onClick)
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
                    account?.nick?.takeIf { it.isNotBlank() } ?: "Войти",
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

private fun accountLine(account: Account?): String = when (account?.kind) {
    null -> "Аккаунт не выбран"
    AccountKind.MILLIDA -> "Аккаунт Millida"
    AccountKind.MICROSOFT -> "Лицензия Microsoft"
    AccountKind.OFFLINE -> "Без лицензии"
}

/**
 * The stage spread over the whole landscape lobby, the desktop's full-window
 * scene: the figure is [figureHeight] tall and its feet stand [feet] above
 * the bottom edge, clear of the play dock.
 */
@Immutable
internal class StageWindow(val figureHeight: Dp, val feet: Dp)

@Composable
internal fun LobbyStage(
    state: UiState,
    look: CharacterLook,
    totalSeconds: Long,
    onOpen: () -> Unit,
    window: StageWindow? = null,
) {
    val colors = millida
    val bubble = remember { BubbleGate() }
    var speech by remember { mutableStateOf<Speech?>(null) }
    val outfit by rememberLobbyOutfit(state, look) { item -> bubble.next(item)?.let { speech = it } }
    val sky = colors.accentDeep
    val ground = colors.accent.mixSrgb(MillidaPixel.serverDark, 0.38f)
    val grass = colors.accent
    val dirt = colors.accent.mixSrgb(MillidaPixel.serverDark, 0.22f)
    val groundHeight = if (window == null) GROUND_HEIGHT else window.feet + GROUND_UNDER_FEET
    val frame = if (window == null) {
        Modifier
            .fillMaxWidth()
            .height(STAGE_HEIGHT)
            .tourTarget(TourTarget.CHARACTER)
            .pixelSurface(sky, MillidaCut.plate)
    } else {
        Modifier
            .fillMaxSize()
            .background(sky)
    }
    Box(
        frame
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
            },
    ) {
        val live = LiveWallpaper.of(state.liveWallpaper)
        if (live != null) {
            LiveWallpaperLayer(live, state.wallpaperMotion, Modifier.fillMaxSize())
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
            speech?.let { shown ->
                key(shown.key) {
                    EmoteSpeech(
                        id = shown.id,
                        onGone = { if (speech === shown) speech = null },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(x = BUBBLE_X, y = BUBBLE_Y),
                    )
                }
            }
            PlaytimeBadge(
                totalSeconds,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )
        } else {
            val scale = window.figureHeight / FIGURE_HEIGHT
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = window.feet)
                    .size(width = FIGURE_WIDTH * scale, height = window.figureHeight),
            ) {
                LobbyFigure(
                    look,
                    outfit,
                    onOpen,
                    Modifier
                        .fillMaxSize()
                        .tourTarget(TourTarget.CHARACTER),
                )
                speech?.let { shown ->
                    key(shown.key) {
                        EmoteSpeech(
                            id = shown.id,
                            onGone = { if (speech === shown) speech = null },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(x = BUBBLE_X * scale, y = (BUBBLE_Y - FIGURE_TOP) * scale),
                        )
                    }
                }
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

/** The hours played through the launcher with the way to the next tier, the desktop's `.lobby-hours`. */
@Composable
internal fun PlaytimeBadge(totalSeconds: Long, modifier: Modifier = Modifier) {
    if (totalSeconds < PlayTime.SHOWN_FROM_SECONDS) return
    val tier = PlayTime.tier(totalSeconds)
    Row(
        modifier
            .pixelSurface(MillidaPixel.textShadow, MillidaCut.badge)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MIcon(MIcons.trophy, size = 16.dp)
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
    val model: LauncherViewModel = viewModel()
    val wardrobe = model.cosmeticWardrobe
    val cosmetics by wardrobe.state.collectAsState()
    val reduced = LocalReducedMotion.current
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
    LaunchedEffect(showable, wornEmote, reduced, signedIn) {
        showing = null
        if (reduced || !signedIn || wornEmote || showable.isEmpty()) return@LaunchedEffect
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

/** The way to the next playtime tier, a bare strip as on the desktop: the number lives in the label beside it. */
@Composable
private fun TierStrip(progress: Float) {
    val fill = millida.accent
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(TIER_STRIP_WIDTH, TIER_STRIP_HEIGHT)
            .drawBehind {
                drawRect(MillidaPixel.divider)
                drawRect(fill, size = Size(floor(size.width * progress.coerceIn(0f, 1f)), size.height))
            },
    )
}

/** Where the bubble sits: right of the head at eye level, its tail to the face (the desktop's EmoteBubble). */
private val BUBBLE_X = 64.dp
private val BUBBLE_Y = 25.dp
private val BUBBLE_CELL = 3.dp
private val BUBBLE_EMOJI = 44.dp
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
            transformOrigin = TransformOrigin(0.2f, 1f)
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
                .padding(8.dp + BUBBLE_CELL),
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
            TAIL_ROWS.forEachIndexed { row, cells ->
                cells.forEachIndexed { column, kind ->
                    drawRect(
                        if (kind == 'd') MillidaPixel.bubbleInk else MillidaPixel.bubbleFill,
                        Offset(column * cell, row * cell),
                        Size(cell, cell),
                    )
                }
            }
        }
    }
}
