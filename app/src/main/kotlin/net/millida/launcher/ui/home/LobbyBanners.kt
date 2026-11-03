package net.millida.launcher.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import net.millida.launcher.core.hub.LobbyPromo
import net.millida.launcher.core.hub.PromoReady
import net.millida.launcher.core.hub.PromoSlide
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelCutShape
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed
import net.millida.launcher.ui.shop.PlusBadge
import kotlin.math.abs
import kotlin.math.round

/** One slide of the lobby banner and what it carries to open its target. */
@Immutable
internal sealed interface LobbyBanner {
    val key: String

    data object Discord : LobbyBanner {
        override val key = "discord"
    }

    data object Telegram : LobbyBanner {
        override val key = "telegram"
    }

    data class OwnEvent(val online: Int?) : LobbyBanner {
        override val key = "own"
    }

    data class Pack(val pack: PremiumPack) : LobbyBanner {
        override val key = "pack:" + pack.id
    }

    data class Invite(val plusDays: Int) : LobbyBanner {
        override val key = "invite"
    }
}

/**
 * The banner's slides from what the lobby knows, in the desktop's order. The
 * API can switch the whole banner off ([promoOn]); the lobby then shows
 * «Попробуй сегодня» in its place.
 */
internal fun lobbyBanners(
    promoOn: Boolean,
    ownPlayable: Boolean,
    ownOnline: Int?,
    arcania: PremiumPack?,
    invitePlusDays: Int?,
): List<LobbyBanner> {
    if (!promoOn) return emptyList()
    val plusDays = invitePlusDays?.takeIf { it > 0 }
    val ready = PromoReady(ownEvent = ownPlayable, arcania = arcania != null, invite = plusDays != null)
    return LobbyPromo.slides(ready).mapNotNull { slide ->
        when (slide) {
            PromoSlide.DISCORD -> null
            PromoSlide.TELEGRAM -> null
            PromoSlide.OWN_EVENT -> LobbyBanner.OwnEvent(ownOnline)
            PromoSlide.ARCANIA -> arcania?.let { LobbyBanner.Pack(it) }
            PromoSlide.INVITE -> plusDays?.let { LobbyBanner.Invite(it) }
        }
    }
}

internal val LOBBY_BANNER_WIDTH = 300.dp
internal val LOBBY_BANNER_HEIGHT = 164.dp
private val CO_SIZE = 11.sp
private val CO_LINE = 13.sp
private val BANNER_CHROME = 47.dp
private const val TITLE_SP = 26f
private const val TITLE_CHARS = 15
private const val TITLE_EM_WIDTH = 0.7f
private val TEXT_SIDE = 14.dp
private val DOT = 8.dp
private val DOT_ON = 20.dp
private val DOT_GAP = 5.dp
private val DOT_TOUCH = 8.dp
private val DOTS_ROOM = DOT + DOT_TOUCH * 2
private val SWIPE = 40.dp
private val SLIDE_SHIFT = 26.dp
private const val SLIDE_MS = 260
private val LOGO = 84.dp

/**
 * The title's size in sp for a slide [widthDp] wide: the desktop's 26 while
 * the longest title ([TITLE_CHARS] capitals) fits one line, smaller when the
 * slide is narrow or the system font is large, so a title never wraps or loses
 * its end. The physical size never passes the desktop's.
 */
internal fun bannerTitleSp(widthDp: Float, fontScale: Float): Float {
    val inner = (widthDp - TEXT_SIDE.value * 2f).coerceAtLeast(0f)
    val fitsDp = inner / (TITLE_CHARS * TITLE_EM_WIDTH)
    return minOf(TITLE_SP, fitsDp / fontScale.coerceAtLeast(0.5f))
}

/**
 * How tall a slide stands for [width] with the system font: the caption and
 * two title lines with the paddings around them, never under the
 * desktop's [LOBBY_BANNER_HEIGHT]. Every slide takes exactly this height, the
 * height of the art slides, so the pager never changes size between slides.
 */
@ReadOnlyComposable
@Composable
internal fun lobbyBannerCardHeight(width: Dp): Dp = with(LocalDensity.current) {
    val title = bannerTitleSp(width.value, fontScale).sp.toDp()
    (CO_LINE.toDp() + title * 2 + BANNER_CHROME).coerceAtLeast(LOBBY_BANNER_HEIGHT)
}

/** The whole banner for [width]: the slide and the pager dots under it when there is more than one. */
@ReadOnlyComposable
@Composable
internal fun lobbyBannerHeight(width: Dp, count: Int): Dp = lobbyBannerCardHeight(width) + if (count > 1) DOTS_ROOM else 0.dp

private val BANNER_FRAME = PixelFrame(
    outline = MillidaPixel.lobbyRecOutline,
    outlineWidth = 3.dp,
    top = MillidaPixel.lobbyRecTop,
    topWidth = 3.dp,
    bottom = MillidaPixel.lobbyRecBottom,
    bottomWidth = 5.dp,
)

/**
 * The desktop's lobby banner (Recommend.tsx, `.lrec-wrap`): one slide at a
 * time, turning every [LobbyPromo.ROTATE_MS]. A finger on it holds the slide,
 * a swipe or a dot turns it by hand and restarts the wait. It stops while the
 * app is not in front; with less motion asked for the slides change without
 * sliding.
 */
@Composable
internal fun LobbyBannerCarousel(
    banners: List<LobbyBanner>,
    start: Int,
    width: Dp,
    onOpen: (LobbyBanner) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (banners.isEmpty()) return
    val reduced = LocalReducedMotion.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var step by rememberSaveable { mutableIntStateOf(0) }
    var direction by remember { mutableIntStateOf(1) }
    var turnedByHand by remember { mutableIntStateOf(0) }
    var holding by remember { mutableStateOf(false) }
    val count = banners.size
    val index = LobbyPromo.at(count, start, step)
    val shown = banners[index]
    val cardHeight = lobbyBannerCardHeight(width)
    val titleSp = bannerTitleSp(width.value, LocalDensity.current.fontScale)
    val turn = rememberUpdatedState { delta: Int ->
        direction = if (delta >= 0) 1 else -1
        step += delta
        turnedByHand++
    }
    LaunchedEffect(count, turnedByHand, resumed, holding) {
        if (!resumed || holding || count < 2) return@LaunchedEffect
        while (true) {
            delay(LobbyPromo.ROTATE_MS)
            direction = 1
            step++
        }
    }
    Column(
        modifier
            .width(width)
            .pointerInput(count) {
                val swipe = SWIPE.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    holding = true
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                val moved = change.position.x - down.position.x
                                // A swipe must not also press the slide under the finger.
                                if (count > 1 && abs(moved) > swipe) {
                                    change.consume()
                                    turn.value(if (moved < 0) 1 else -1)
                                }
                                break
                            }
                        }
                    } finally {
                        holding = false
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val appear = remember(shown.key, step) { Animatable(if (reduced) 1f else 0f) }
        LaunchedEffect(shown.key, step) {
            if (!reduced) appear.animateTo(1f, tween(SLIDE_MS, easing = MillidaMotion.steps(4)))
        }
        val from = direction
        Box(
            Modifier.graphicsLayer {
                val progress = appear.value
                alpha = progress
                translationX = round((1f - progress) * SLIDE_SHIFT.toPx() * from)
            },
        ) {
            LobbyBannerCard(shown, cardHeight, titleSp, onOpen = { onOpen(shown) })
        }
        if (count > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(DOT_GAP), verticalAlignment = Alignment.CenterVertically) {
                banners.forEachIndexed { at, banner ->
                    val on = at == index
                    val dotWidth by animateDpAsState(
                        targetValue = if (on) DOT_ON else DOT,
                        animationSpec = if (reduced) snap<Dp>() else tween<Dp>(MillidaMotion.fast, easing = MillidaMotion.steps(3)),
                        label = "banner-dot",
                    )
                    Box(
                        Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = { if (!on) turn.value(at - index) },
                            )
                            .semantics { contentDescription = tr("Слайд {0}", at + 1) }
                            .padding(vertical = DOT_TOUCH),
                    ) {
                        Box(
                            Modifier
                                .size(width = dotWidth, height = DOT)
                                .background(if (on) MillidaPixel.white else MillidaPixel.bannerDot),
                        )
                    }
                }
            }
        }
    }
}

/** The colours of one slide: the night plate and the second title line. */
private class BannerTone(val night: Color, val em: Color)

@Composable
private fun toneOf(banner: LobbyBanner): BannerTone {
    val colors = millida
    return when (banner) {
        LobbyBanner.Discord -> BannerTone(MillidaPixel.bannerDiscordNight, MillidaPixel.bannerDiscordEm)
        LobbyBanner.Telegram -> BannerTone(MillidaPixel.bannerTelegramNight, MillidaPixel.bannerTelegramEm)
        is LobbyBanner.Invite -> BannerTone(colors.inset, Rarity.LEGENDARY.color)
        is LobbyBanner.OwnEvent, is LobbyBanner.Pack -> BannerTone(MillidaPixel.bannerPackNight, MillidaPixel.bannerPackLava)
    }
}

/** One slide: the whole card is what is pressed, it carries no button of its own. */
@Composable
private fun LobbyBannerCard(banner: LobbyBanner, height: Dp, titleSp: Float, onOpen: () -> Unit) {
    val tone = toneOf(banner)
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Open)
    val progress = rememberPressProgress(pressed)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .pressShift(progress)
            .pixelSurface(tone.night, MillidaCut.plate, BANNER_FRAME)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = bannerAction(banner), onClick = onOpen),
    ) {
        when (banner) {
            LobbyBanner.Discord -> ChatSlide(tone, titleSp, MIcons.discord, tr("Войсы и пати"), tr("в Discord"))
            LobbyBanner.Telegram -> ChatSlide(tone, titleSp, MIcons.telegram, tr("Новости первыми"), tr("в Telegram"))
            is LobbyBanner.Invite -> InviteSlide(tone, titleSp, banner.plusDays)
            is LobbyBanner.OwnEvent -> OwnEventSlide(banner.online)
            is LobbyBanner.Pack -> PackSlide(tone, banner.pack)
        }
    }
}

private fun bannerAction(banner: LobbyBanner): String = when (banner) {
    LobbyBanner.Discord -> tr("Открыть Discord")
    LobbyBanner.Telegram -> tr("Открыть Telegram")
    is LobbyBanner.Invite -> tr("Пригласить друга")
    is LobbyBanner.OwnEvent -> tr("Играть")
    is LobbyBanner.Pack -> tr("Открыть {title}", banner.pack.title)
}

@Composable
private fun BoxScope.ChatSlide(tone: BannerTone, titleSp: Float, logo: MillidaIcon, title: String, em: String) {
    MIcon(
        logo,
        size = LOGO,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 40.dp, end = 12.dp),
    )
    BannerText(tone, titleSp, co = null, title, em)
}

@Composable
private fun BoxScope.InviteSlide(tone: BannerTone, titleSp: Float, plusDays: Int) {
    PlusBadge(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 14.dp, bottom = 30.dp),
    )
    BannerText(tone, titleSp, tr("+{plusDays} дн. PLUS за каждого друга", plusDays), tr("Бесплатный PLUS"), tr("за друга"))
}

@Composable
private fun BannerText(tone: BannerTone, titleSp: Float, co: String?, title: String, em: String) {
    val shadow = with(LocalDensity.current) { Shadow(MillidaPixel.bannerTitleShadow, Offset(0f, 3.dp.toPx()), 0f) }
    val titleStyle = MillidaType.tileTitle.copy(fontSize = titleSp.sp, lineHeight = titleSp.sp, shadow = shadow)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = TEXT_SIDE, end = TEXT_SIDE, top = 14.dp, bottom = 26.dp),
    ) {
        Text(
            co?.uppercase().orEmpty(),
            style = MillidaType.caption.copy(fontSize = CO_SIZE, lineHeight = CO_LINE, fontWeight = FontWeight.ExtraBold),
            color = MillidaPixel.bannerCo,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(7.dp))
        Text(title.uppercase(), style = titleStyle, color = MillidaPixel.bannerTitle)
        Text(em.uppercase(), style = titleStyle, color = tone.em)
    }
}

/** The own event over its designer art, as the desktop shows its own server: a tap starts it right away. */
@Composable
private fun BoxScope.OwnEventSlide(online: Int?) {
    Box(
        Modifier
            .matchParentSize()
            .padding(3.dp)
            .clip(PixelCutShape(MillidaCut.plate)),
    ) {
        OneBlockArt(withRig = true)
    }
    if (online != null && online > 0) {
        Text(
            tr("{online} играют", online),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, end = 10.dp, bottom = 14.dp)
                .pixelSurface(MillidaPixel.bannerPackOnline, MillidaCut.control)
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 7.dp),
            style = MillidaType.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = MillidaPixel.whiteSoft,
            maxLines = 1,
        )
    }
}

/** A pack from the catalogue over its cover, the desktop's PackPromo: opens its page. */
@Composable
private fun BoxScope.PackSlide(tone: BannerTone, pack: PremiumPack) {
    MRemoteImage(
        pack.coverUrl,
        MIcons.chest,
        Modifier
            .matchParentSize()
            .padding(3.dp),
        size = LOBBY_BANNER_HEIGHT,
        shape = PixelCutShape(MillidaCut.plate),
        background = tone.night,
    )
    Text(
        pack.title,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 14.dp, top = 14.dp, end = 14.dp),
        style = MillidaType.caption.copy(fontSize = CO_SIZE, lineHeight = CO_LINE, fontWeight = FontWeight.ExtraBold),
        color = MillidaPixel.bannerTitle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
