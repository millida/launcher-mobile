package net.millida.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.media.UiSoundEvent
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.millida.launcher.ui.shop.leftLabel
import net.millida.launcher.ui.shop.rememberNow
import java.util.concurrent.ConcurrentHashMap

private const val SCREEN_TITLE_KEY = "m-screen-title"
private val WIDE_STRIP_HEIGHT = 56.dp
private val WIDE_LIST_TOP = 12.dp
private val NO_STRIP_ACTIONS: @Composable RowScope.() -> Unit = {}
private const val RARITY_CYCLE_MS = 24_000f
private const val TWINKLE_MS = 1_600f
private const val PRISM_STEP_MS = 400f
private const val GLINT_MS = 3_000f
private const val RAY_SLANT = 0.268f

@Composable
fun MScreen(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    spacing: Dp = 10.dp,
    stripCenter: (@Composable RowScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val colors = millida
    val wide = LocalWideLayout.current
    // The desktop strip holds only «← Лобби» and a screen's own controls, the
    // title is the page's head; a phone on its side keeps it in the strip.
    val pageHead = wide && title != null && stripCenter == null &&
        LocalConfiguration.current.screenHeightDp >= WIDE_ROOMY_HEIGHT_DP
    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        if (wide) {
            val lifted by remember(listState) { derivedStateOf { listState.canScrollBackward } }
            WideTopBar(
                title = if (pageHead) null else title,
                onBack = onBack,
                subtitle = if (pageHead) null else subtitle,
                divider = lifted,
                center = stripCenter,
                actions = if (pageHead) NO_STRIP_ACTIONS else actions,
            )
        } else if (onBack != null) {
            val lifted by remember(listState) { derivedStateOf { listState.canScrollBackward } }
            MTopBar(
                title = title.orEmpty(),
                onBack = onBack,
                subtitle = subtitle,
                divider = lifted,
                actions = actions,
            )
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(
                start = screenPadding,
                end = screenPadding,
                top = if (wide) WIDE_LIST_TOP else if (onBack != null) 6.dp else screenPadding,
                bottom = 28.dp + wideBottomInset(),
            ),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            if (title != null && ((!wide && onBack == null) || pageHead)) {
                item(key = SCREEN_TITLE_KEY, contentType = SCREEN_TITLE_KEY) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            ScreenTitle(title, subtitle)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions,
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
fun MTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    divider: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    if (LocalWideLayout.current) {
        WideTopBar(
            title = title,
            onBack = onBack,
            modifier = modifier,
            subtitle = subtitle,
            divider = divider,
            actions = actions,
        )
        return
    }
    val colors = millida
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.bg)
            .drawBehind {
                if (divider) {
                    val line = max(1f, 2.dp.roundToPx().toFloat())
                    drawRect(colors.plateBottom, Offset(0f, size.height - line), Size(size.width, line))
                }
            }
            .heightIn(min = 64.dp)
            .padding(horizontal = screenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            MBackButton(onBack)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MillidaType.barTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MillidaType.small,
                    color = colors.fgFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier.padding(start = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/**
 * The desktop's strip above every screen but the lobby: the green «← Лобби»
 * with the friends' counter on its corner, the screen's name beside it, the
 * screen's own actions and balance at the right end, and the hub's tabs in
 * the middle where a screen has them. Without its own [onBack] a screen goes
 * where [LocalWideBack] says one step back leads.
 */
@Composable
fun WideTopBar(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    divider: Boolean = false,
    center: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = millida
    val back = LocalWideBack.current
    val leave = onBack ?: back?.onBack
    Box(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
            .drawBehind {
                val line = max(1f, (if (divider) 2.dp else 1.dp).roundToPx().toFloat())
                drawRect(if (divider) colors.plateBottom else colors.border, Offset(0f, size.height - line), Size(size.width, line))
            }
            .heightIn(min = WIDE_STRIP_HEIGHT)
            .padding(horizontal = screenPadding, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leave != null) {
                Box {
                    MPrimaryButton(
                        text = back?.label ?: tr("Лобби"),
                        onClick = leave,
                        icon = MIcons.chevronLeft,
                        size = MButtonSize.Small,
                    )
                    MCounterBadge(
                        back?.badge ?: 0,
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 8.dp, y = (-6).dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                if (title != null && center == null) {
                    Text(
                        title,
                        style = MillidaType.barTitle,
                        color = colors.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MillidaType.small,
                            color = colors.fgFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(
                Modifier.padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
        if (center != null) {
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                content = center,
            )
        }
    }
}

@Composable
fun MEmptyState(
    icon: MillidaIcon,
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    actionIcon: MillidaIcon? = null,
    primary: Boolean = true,
) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    val pop = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) pop.animateTo(1f, tween(360, easing = MillidaMotion.steps(3)))
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(72.dp)
                .graphicsLayer {
                    val scale = 0.85f + 0.15f * pop.value
                    scaleX = scale
                    scaleY = scale
                }
                .pixelSurface(colors.surface2, MillidaCut.plate, colors.plateFrame()),
            contentAlignment = Alignment.Center,
        ) {
            MIcon(icon, size = 36.dp, color = colors.fgSubtle)
        }
        Text(
            title,
            style = MillidaType.rowTitle.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
            color = colors.fg,
            textAlign = TextAlign.Center,
        )
        if (actionText != null && onAction != null) {
            MButton(
                text = actionText,
                onClick = onAction,
                variant = if (primary) MButtonVariant.Primary else MButtonVariant.Secondary,
                icon = actionIcon,
            )
        }
    }
}

@Composable
fun MSignInGate(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = tr("Войди в Millida"),
    actionText: String = tr("Войти"),
) {
    MEmptyState(
        icon = MIcons.key,
        title = title,
        modifier = modifier,
        actionText = actionText,
        onAction = onSignIn,
        actionIcon = MIcons.login,
    )
}

@Composable
fun MErrorCard(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    retryText: String = tr("Повторить"),
) {
    val colors = millida
    Column(
        modifier
            .fillMaxWidth()
            .pixelSurface(
                fill = colors.surface,
                cut = MillidaCut.plate,
                frame = colors.plateFrame().copy(left = colors.danger, leftWidth = 4.dp),
            )
            .padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            MIcon(MIcons.alert, size = 20.dp, color = colors.danger)
            Spacer(Modifier.width(10.dp))
            Text(message, modifier = Modifier.weight(1f), style = MillidaType.body, color = colors.fg)
        }
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            MButton(
                text = retryText,
                onClick = onRetry,
                variant = MButtonVariant.Secondary,
                size = MButtonSize.Small,
                icon = MIcons.refresh,
            )
        }
    }
}

@Composable
fun MBusyCard(
    label: String,
    progress: Float?,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val colors = millida
    val share = progress?.takeIf { !it.isNaN() && it > 0f }?.coerceAtMost(1f)
    // Time left from the pace so far: shown only once the pace means something.
    val startedAt = remember(label) { android.os.SystemClock.elapsedRealtime() }
    val eta = share?.takeIf { it in 0.03f..0.97f }?.let {
        val elapsedSec = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000f
        if (elapsedSec < 20f) null else (elapsedSec * (1f - it) / it / 60f).roundToInt().coerceAtLeast(1)
    }
    Column(
        modifier
            .fillMaxWidth()
            .pixelSurface(colors.surface, MillidaCut.plate, colors.plateFrame())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MPixelLoader()
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MillidaType.rowTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (share != null) {
                Spacer(Modifier.width(8.dp))
                Text("${(share * 100).roundToInt()}%", style = MillidaType.pixel, color = colors.accentText)
            }
        }
        Spacer(Modifier.height(12.dp))
        MProgressBar(share)
        if (eta != null) {
            Spacer(Modifier.height(6.dp))
            Text(tr("Осталось около {0} мин", eta), style = MillidaType.small, color = colors.fgMuted, maxLines = 1)
        }
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                note,
                style = MillidaType.small,
                color = colors.fgFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun MListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: MillidaIcon? = null,
    onClick: (() -> Unit)? = null,
    plate: Boolean = true,
    badge: Int = 0,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (onClick != null) UiSoundEvent.Nav else null)
    val down = pressed && onClick != null
    val base = if (plate) colors.surface else Color.Transparent
    val fill by animateColorAsState(if (down) colors.surface2 else base, tween(80), label = "row-fill")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (plate) 64.dp else 56.dp)
            .pixelSurface(
                fill = fill,
                cut = if (plate) MillidaCut.plate else MillidaCut.control,
                frame = if (plate) colors.plateFrame() else PixelFrame.None,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = if (plate) 14.dp else 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        } else if (icon != null) {
            Box(
                Modifier
                    .size(40.dp)
                    .pixelSurface(if (plate) colors.surface2 else colors.inset, MillidaCut.control),
                contentAlignment = Alignment.Center,
            ) {
                MIcon(icon, size = 24.dp, color = colors.fgMuted)
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MillidaType.rowTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MillidaType.small,
                    color = colors.fgSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing,
            )
        } else {
            if (badge > 0) {
                Spacer(Modifier.width(8.dp))
                MCounterBadge(badge)
            }
            if (onClick != null) {
                Spacer(Modifier.width(10.dp))
                MIcon(MIcons.chevronRight, size = 16.dp, color = colors.fgFaint)
            }
        }
    }
}

@Composable
fun MInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    mono: Boolean = false,
) {
    val colors = millida
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MillidaType.small.copy(fontSize = 13.sp),
            color = colors.fgSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            modifier = Modifier.weight(1.2f, fill = false),
            style = if (mono) MillidaType.pixel else MillidaType.body.copy(fontWeight = FontWeight.Bold),
            color = if (valueColor.isSpecified) valueColor else colors.fg,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun MDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(millida.border),
    )
}

enum class MPresence { Offline, Online, Playing }

@Composable
fun MAvatar(
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    presence: MPresence? = null,
    plus: Boolean = false,
    ring: Color = millida.surface,
    icon: MillidaIcon = MIcons.user,
    dimmed: Boolean = false,
) {
    val colors = millida
    Box(modifier.size(size)) {
        MRemoteImage(
            url = url,
            icon = icon,
            size = size,
            shape = MillidaShape.avatar,
            pixelated = true,
            modifier = if (dimmed) Modifier.alpha(DIMMED_AVATAR_ALPHA) else Modifier,
        )
        if (plus) {
            Box(
                Modifier
                    .size(size)
                    .clip(MillidaShape.avatar)
                    .pixelFrame(PixelFrame(outline = colors.accent, outlineWidth = 2.dp), MillidaCut.badge),
            )
        }
        if (presence != null) {
            val dot = (size * 0.3f).coerceIn(10.dp, 16.dp)
            val tone = when (presence) {
                MPresence.Playing -> colors.accent
                MPresence.Online -> colors.info
                MPresence.Offline -> colors.fgFaint
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 3.dp)
                    .size(dot)
                    .pixelSurface(ring, 3.dp)
                    .padding(2.dp)
                    .pixelSurface(tone, 2.dp),
            )
        }
    }
}

private const val DIMMED_AVATAR_ALPHA = 0.55f

/** Presence inside a status line: a square dot shown only while the person is online. */
@Composable
fun MStatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(7.dp)
            .background(color),
    )
}

@Composable
private fun rememberRarityClock(active: Boolean): State<Float>? {
    if (!active || LocalReducedMotion.current) return null
    return rememberInfiniteTransition(label = "rarity").animateFloat(
        initialValue = 0f,
        targetValue = RARITY_CYCLE_MS,
        // Steps of the prism's own 400 ms: a linear clock woke the whole lobby 60 times a second
        // for a colour that changes 2.5 times (measured on the Redmi Note 13: all frames of the idle lobby).
        animationSpec = infiniteRepeatable(tween(RARITY_CYCLE_MS.toInt(), easing = MillidaMotion.steps((RARITY_CYCLE_MS / PRISM_STEP_MS).toInt()))),
        label = "rarity-clock",
    )
}

private fun prismAt(timeMs: Float, shift: Int): Color {
    val palette = MillidaPixel.prism
    val index = ((timeMs / PRISM_STEP_MS).toInt() + shift) % palette.size
    return palette[index]
}

@Composable
fun MRarityPlate(rarity: Rarity, modifier: Modifier = Modifier) {
    val colors = millida
    val clock = rememberRarityClock(rarity == Rarity.RELIC)
    val fill = when {
        rarity.solidPlate -> rarity.color
        rarity == Rarity.RELIC -> Rarity.relicBackground
        else -> rarity.color.mixSrgb(colors.inset, 0.22f)
    }
    val ink = if (rarity.solidPlate) Rarity.ink else rarity.color
    Row(
        modifier
            .pixelSurface(fill, MillidaCut.badge)
            .padding(start = 5.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .drawBehind {
                    val time = clock?.value
                    drawRect(if (rarity == Rarity.RELIC && time != null) prismAt(time, 0) else ink)
                },
        )
        Spacer(Modifier.width(5.dp))
        Text(
            rarity.plate.uppercase(),
            style = MillidaType.caption.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.06.em),
            color = ink,
            maxLines = 1,
        )
    }
}

private fun twinkle(timeMs: Float, delayMs: Float): Float {
    val local = ((timeMs - delayMs) % TWINKLE_MS + TWINKLE_MS) % TWINKLE_MS / TWINKLE_MS
    return when {
        local < 0.83f -> 0f
        local < 0.88f -> 1f
        local < 0.91f -> 0.6f
        else -> 0f
    }
}

private fun DrawScope.drawSparks(rarity: Rarity, timeMs: Float?) {
    val unit = max(1f, 3.dp.roundToPx().toFloat())
    val xs = floatArrayOf(0.14f, 0.88f, 0.26f)
    val ys = floatArrayOf(0.16f, 0.30f, 0.52f)
    val count = if (rarity == Rarity.EPIC) 2 else 3
    for (index in 0 until count) {
        val visibility = if (rarity.animated && timeMs != null) twinkle(timeMs, index * 800f) else 1f
        if (visibility <= 0f) continue
        val small = rarity.animated && timeMs != null && visibility < 1f
        val cell = if (small) max(1f, floor(unit / 2f)) else unit
        val x = floor(size.width * xs[index] - cell / 2f)
        val y = floor(size.height * ys[index] - cell / 2f)
        val armColor = if (rarity == Rarity.RELIC && timeMs != null) prismAt(timeMs, index) else rarity.color
        val arm = armColor.copy(alpha = MillidaPixel.sparkArm * visibility)
        drawRect(arm, Offset(x - cell, y), Size(cell * 3f, cell))
        drawRect(arm, Offset(x, y - cell), Size(cell, cell * 3f))
        drawRect(MillidaPixel.white.copy(alpha = visibility), Offset(x, y), Size(cell, cell))
    }
}

private fun DrawScope.drawGlint(rarity: Rarity, timeMs: Float) {
    val local = (timeMs % GLINT_MS) / GLINT_MS
    if (local >= 0.18f) return
    val step = floor(local / 0.03f).toInt().coerceIn(0, 5)
    val position = 1.6f - step * 0.35f
    val image = size.width * 2.8f
    val offset = (size.width - image) * position
    val strong = if (rarity == Rarity.MYTHIC) rarity.color.copy(alpha = MillidaPixel.mythicGlint) else MillidaPixel.glintStrong
    val soft = if (rarity == Rarity.MYTHIC) MillidaPixel.glintMythicSoft else MillidaPixel.glintSoft
    drawSlantBand(offset + image * 0.40f, offset + image * 0.47f, strong)
    drawSlantBand(offset + image * 0.51f, offset + image * 0.54f, soft)
}

private fun DrawScope.drawSlantBand(from: Float, to: Float, color: Color) {
    val lean = size.height * RAY_SLANT / 2f
    val path = Path()
    path.moveTo(from + lean, 0f)
    path.lineTo(to + lean, 0f)
    path.lineTo(to - lean, size.height)
    path.lineTo(from - lean, size.height)
    path.close()
    drawPath(path, color)
}

@Composable
fun MRarityFrame(
    rarity: Rarity?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 8.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (onClick != null) UiSoundEvent.Nav else null)
    val down = pressed && onClick != null
    val progress = rememberPressProgress(down, MillidaMotion.tilePress, MillidaMotion.steps(2))
    val clock = rememberRarityClock(rarity != null && rarity.animated)
    val base = when {
        selected -> colors.accentToday
        rarity == null -> colors.surface2
        rarity == Rarity.RELIC -> Rarity.relicBackground
        rarity == Rarity.LEGENDARY || rarity == Rarity.MYTHIC -> rarity.color.mixSrgb(colors.surface2, 0.14f)
        rarity == Rarity.RARE || rarity == Rarity.EPIC -> rarity.color.mixSrgb(colors.surface2, 0.09f)
        else -> colors.surface2
    }
    val fill = if (down && rarity != Rarity.RELIC) (rarity?.color ?: colors.fgFaint).mixSrgb(base, 0.2f) else base
    Box(
        modifier
            .pressShift(progress)
            .pixelSurface(fill, MillidaCut.plate, PixelFrame(top = colors.plateTop))
            .then(
                if (onClick != null) {
                    Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .drawWithContent {
                drawContent()
                val rank = rarity ?: return@drawWithContent
                val time = clock?.value
                val step = stepFor(MillidaCut.plate)
                val relic = rank == Rarity.RELIC && time != null
                val bottomBar = if (relic && time != null) prismAt(time, 3) else rank.color
                val topBar = if (relic && time != null) prismAt(time, 0) else rank.color
                drawPixelFrame(
                    PixelFrame(
                        top = if (rank.sparkles) topBar else Color.Transparent,
                        topWidth = 5.dp,
                        bottom = bottomBar,
                        bottomWidth = if (rank >= Rarity.RARE) 4.dp else 3.dp,
                    ),
                    step,
                    step,
                )
                if (rank.sparkles) drawSparks(rank, time)
                if (rank.animated && time != null) drawGlint(rank, time)
            }
            .padding(contentPadding),
        content = content,
    )
}

internal fun groupDigits(value: Long): String {
    val digits = abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(" ").reversed()
    return if (value < 0) "-$grouped" else grouped
}

@Composable
fun MRubyAmount(
    amount: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MillidaType.pixel,
    plate: Boolean = false,
) {
    val colors = millida
    Row(
        modifier.then(
            if (plate) {
                Modifier
                    .heightIn(min = 40.dp)
                    .pixelSurface(colors.surface2, MillidaCut.control, colors.plateFrame())
                    .padding(start = 10.dp, end = 12.dp, bottom = 2.dp)
            } else {
                Modifier
            },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(MIcons.ruby, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(groupDigits(amount), style = style, color = colors.fg, maxLines = 1, softWrap = false)
    }
}

@Composable
fun MRubyAmount(
    amount: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MillidaType.pixel,
    plate: Boolean = false,
) {
    MRubyAmount(amount.toLong(), modifier, style, plate)
}

@Immutable
data class MHeroTone(
    val plate: Color,
    val pressed: Color,
    val outline: Color,
    val band: Color,
    val rays: Color,
) {
    companion object {
        val Shop = MHeroTone(
            MillidaPixel.shopPlate,
            MillidaPixel.shopPressed,
            MillidaPixel.shopOutline,
            MillidaPixel.shopBand,
            MillidaPixel.shopRays,
        )
        val Daily = MHeroTone(
            MillidaPixel.dailyPlate,
            MillidaPixel.dailyPressed,
            MillidaPixel.dailyOutline,
            MillidaPixel.dailyBand,
            MillidaPixel.dailyRays,
        )
        val Wardrobe = MHeroTone(
            MillidaPixel.wardrobePlate,
            MillidaPixel.wardrobePressed,
            MillidaPixel.wardrobeOutline,
            MillidaPixel.wardrobeBand,
            MillidaPixel.wardrobeRays,
        )

        fun server(accent: Color): MHeroTone = MHeroTone(
            plate = accent.mixSrgb(MillidaPixel.serverDark, 0.62f),
            pressed = accent.mixSrgb(MillidaPixel.serverDark, 0.72f),
            outline = accent.mixSrgb(Color.Black, 0.18f),
            band = accent.mixSrgb(MillidaPixel.serverBandDark, 0.26f),
            rays = accent.copy(alpha = 0.34f),
        )
    }
}

internal fun DrawScope.drawRays(center: Offset, radius: Float, color: Color, rotation: Float = 0f) {
    if (radius <= 0f) return
    val path = Path()
    for (index in 0 until 16) {
        val start = Math.toRadians(4.0 + index * 22.5 - 90.0) + rotation
        val end = Math.toRadians(13.0 + index * 22.5 - 90.0) + rotation
        path.moveTo(center.x, center.y)
        path.lineTo(center.x + radius * cos(start).toFloat(), center.y + radius * sin(start).toFloat())
        path.lineTo(center.x + radius * cos(end).toFloat(), center.y + radius * sin(end).toFloat())
        path.close()
    }
    drawPath(path, color)
}

internal fun hopAt(phase: Float): Float = when {
    phase < 0.7f -> 0f
    phase < 0.8f -> -3f
    phase < 0.9f -> -5f
    else -> -2f
}

@Composable
private fun AlertMarker(modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val phase: State<Float>? = if (reduced) {
        null
    } else {
        rememberInfiniteTransition(label = "alert").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            // hopAt() has four levels, so ten steps show the same hop and draw ten times a cycle, not sixty a second.
            animationSpec = infiniteRepeatable(tween(MillidaMotion.hop, easing = MillidaMotion.steps(10))),
            label = "alert-hop",
        )
    }
    Box(
        modifier
            .graphicsLayer { translationY = hopAt(phase?.value ?: 0f).dp.toPx() }
            .size(30.dp)
            .pixelSurface(
                fill = MillidaPixel.white,
                cut = MillidaCut.control,
                frame = PixelFrame(
                    outline = MillidaPixel.alertInk,
                    outlineWidth = 3.dp,
                    bottom = MillidaPixel.alertShade,
                    bottomWidth = 3.dp,
                ),
            )
            .padding(bottom = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "!",
            style = MillidaType.tileTitle.copy(fontSize = 20.sp, lineHeight = 22.sp),
            color = MillidaPixel.counter,
        )
    }
}

/** One line of [title] at the largest size from [COMPACT_TITLE_SP] down that fits the width, the desktop tile's caption. */
@Composable
private fun FittedTitle(title: String, style: TextStyle) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val room = constraints.maxWidth
        val size = remember(title, style, room, measurer) {
            (COMPACT_TITLE_SP downTo COMPACT_TITLE_MIN_SP).firstOrNull { points ->
                measurer.measure(title, style.copy(fontSize = points.sp, lineHeight = (points + 2).sp), maxLines = 1, softWrap = false).size.width <= room
            } ?: COMPACT_TITLE_MIN_SP
        }
        Text(
            title,
            style = style.copy(fontSize = size.sp, lineHeight = (size + 2).sp),
            color = MillidaPixel.white,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val HERO_BAND = Modifier.padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 11.dp)

// A short landscape tile keeps most of its picture above the caption, as the
// desktop tile does, so the band gives up some padding and type size there.
private val HERO_BAND_COMPACT = Modifier.padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 7.dp)

private val RIBBON_ON_BAND = Modifier.offset(x = (-8).dp, y = (-11).dp)

// The slim band's one-line title runs the whole width, so the ribbon stands on
// the picture just above the band instead of straddling the band's edge.
private val RIBBON_OVER_BAND = Modifier.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.place(-RIBBON_INSET.roundToPx(), -placeable.height - RIBBON_LIFT.roundToPx())
    }
}
private val RIBBON_INSET = 8.dp
private val RIBBON_LIFT = 2.dp

private const val COMPACT_TITLE_SP = 15
private const val COMPACT_TITLE_MIN_SP = 10

/**
 * The plate sits three pixels inside the outline with the same step: a smaller step would thin the line to nothing at the corner.
 * [compactBand] is the desktop lobby's slim caption: the title alone on one line, set as large as fits, so the picture above
 * keeps the characters down to their shoulders on a low landscape tile.
 */
@Composable
fun MHeroCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tone: MHeroTone = MHeroTone.server(millida.accent),
    height: Dp = 118.dp,
    overflow: Dp = 20.dp,
    ribbon: String? = null,
    alert: Boolean = false,
    onClick: (() -> Unit)? = null,
    titleMaxLines: Int = 1,
    compactBand: Boolean = false,
    art: (@Composable BoxScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (onClick != null) UiSoundEvent.Nav else null)
    val down = pressed && onClick != null
    val progress = rememberPressProgress(down, MillidaMotion.tilePress, MillidaMotion.steps(2))
    val shadowOffset = with(LocalDensity.current) { 2.dp.toPx() }
    val plateFill = if (down) tone.pressed else tone.plate
    Box(
        modifier
            .fillMaxWidth()
            .height(overflow + height)
            .pressShift(progress, MillidaMotion.tilePressOffset)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(height)
                .pixelSole(progress)
                .pixelSurface(tone.outline, MillidaCut.plate),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(height)
                .padding(3.dp)
                .clip(PixelCutShape(MillidaCut.plate))
                .drawBehind {
                    drawRect(plateFill)
                    drawRays(Offset(size.width * 0.66f, size.height * 0.28f), 200.dp.toPx(), tone.rays)
                    val step = stepFor(MillidaCut.plate)
                    drawPixelFrame(PixelFrame(top = MillidaPixel.tileTop, topWidth = 4.dp), step, step)
                },
        )
        if (art != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = 3.dp, end = 3.dp, bottom = 3.dp)
                    .clip(PixelCutShape(MillidaCut.plate, PixelCorners.Bottom)),
                content = art,
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(3.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .pixelSurface(
                        fill = tone.band,
                        cut = MillidaCut.plate,
                        frame = PixelFrame(
                            top = tone.outline,
                            topWidth = 3.dp,
                            bottom = MillidaPixel.tileBottom,
                            bottomWidth = 5.dp,
                        ),
                        corners = PixelCorners.Bottom,
                    )
                    .then(if (compactBand) HERO_BAND_COMPACT else HERO_BAND),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val titleShadow = Shadow(MillidaPixel.textShadow, Offset(0f, shadowOffset), 0f)
                if (compactBand) {
                    FittedTitle(title, MillidaType.tileTitle.copy(shadow = titleShadow))
                } else {
                    Text(
                        title,
                        style = MillidaType.tileTitle.copy(fontSize = 18.sp, lineHeight = 20.sp, shadow = titleShadow),
                        color = MillidaPixel.white,
                        maxLines = titleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
                        color = MillidaPixel.whiteSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (ribbon != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .then(if (compactBand) RIBBON_OVER_BAND else RIBBON_ON_BAND)
                        .pixelSurface(Rarity.LEGENDARY.color, MillidaCut.badge)
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                ) {
                    Text(
                        ribbon,
                        style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black),
                        color = Rarity.ink,
                        maxLines = 1,
                    )
                }
            }
        }
        if (alert) {
            AlertMarker(Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = overflow - 8.dp))
        }
    }
}

private val SECTION_CHIP_HEIGHT = 38.dp
private val DAY_CLOCK_HEIGHT = 30.dp
private const val DAY_CLOCK_TICK_MS = 20_000L
private val RAIL_CARD_WIDTH = 230.dp
private val RAIL_GAP = 12.dp
private const val RAIL_AUTO_MS = 4_000L
private val PLUS_CHIP_HEIGHT = 28.dp
private val PLUS_CHIP_MARK = 14.dp
private const val PLUS_GLOW_MS = 2_200
private const val PLUS_SHINE_MS = 3_600
private val BALANCE_HEIGHT = 38.dp

/**
 * The desktop shop's sticky anchor bar (`sv-nav`): one chip per section, the open
 * one in the accent. The caller keeps it in a sticky header and scrolls on a pick.
 */
@Composable
fun <T> MSectionChipBar(
    sections: List<T>,
    active: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: (T) -> MillidaIcon? = { null },
) {
    val colors = millida
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.bg)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sections.forEach { section ->
            SectionChip(label(section), icon(section), section == active) { onPick(section) }
        }
    }
}

@Composable
private fun SectionChip(text: String, icon: MillidaIcon?, on: Boolean, onClick: () -> Unit) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Click)
    val fill = when {
        on -> colors.accent
        pressed -> colors.raised
        else -> colors.surface2
    }
    val ink = when {
        on -> colors.onAccent
        pressed -> colors.fg
        else -> colors.fgMuted
    }
    Row(
        Modifier
            .heightIn(min = SECTION_CHIP_HEIGHT)
            .pixelSurface(fill, MillidaCut.control)
            .selectable(on, interaction, null, role = Role.Tab, onClick = onClick)
            .padding(start = 12.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) MIcon(icon, size = 18.dp, color = ink)
        Text(
            text,
            style = MillidaType.button.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold),
            color = ink,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** A section's head in the desktop shop's feed (`sv-sec > .sh-head`): a big title and, on the right, its timer or action. */
@Composable
fun MSecHead(
    title: String,
    modifier: Modifier = Modifier,
    icon: MillidaIcon? = null,
    aside: @Composable RowScope.() -> Unit = {},
) {
    val colors = millida
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) MIcon(icon, size = 22.dp, color = colors.fg)
            Text(
                title,
                style = MillidaType.sectionTitle.copy(fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        aside()
    }
}

/**
 * The desktop's `DayClock`: a clock and the calm time left, «5ч 12м». It ticks every
 * 20 seconds, as the desktop's, and wakes exactly at zero so the shelf turns over at once.
 */
@Composable
fun MDayClock(to: Long, modifier: Modifier = Modifier, onEnd: (() -> Unit)? = null) {
    val ticked by rememberNow(DAY_CLOCK_TICK_MS)
    val ended by rememberUpdatedState(onEnd)
    var woke by remember(to) { mutableLongStateOf(0L) }
    LaunchedEffect(to) {
        val left = to - System.currentTimeMillis()
        if (left > 0) {
            delay(left + 50)
            woke = System.currentTimeMillis()
            ended?.invoke()
        }
    }
    val now = max(ticked, woke)
    Row(
        modifier
            .height(DAY_CLOCK_HEIGHT)
            .pixelSurface(MillidaPixel.clockPlate, MillidaCut.control)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(MIcons.clock, size = 16.dp, color = MillidaPixel.white)
        Text(
            leftLabel(max(0L, to - now)),
            style = MillidaType.button.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum"),
            color = MillidaPixel.white,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The desktop's `CardRail`: one row of cards scrolled sideways with arrows at the
 * edges. With [auto] it moves on by a card every four seconds while nobody holds it,
 * and goes back to the start from the end; reduced motion keeps it still.
 */
@Composable
fun <T> MCardRail(
    entries: List<T>,
    key: (T) -> Any,
    label: String,
    modifier: Modifier = Modifier,
    cardWidth: Dp = RAIL_CARD_WIDTH,
    auto: Boolean = false,
    card: @Composable (T) -> Unit,
) {
    val state = rememberLazyListState()
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    var held by remember { mutableStateOf(false) }
    if (auto && !reduced) {
        LaunchedEffect(entries.size) {
            while (true) {
                delay(RAIL_AUTO_MS)
                if (held || state.isScrollInProgress) continue
                val next = state.firstVisibleItemIndex + 1
                if (!state.canScrollForward || next >= entries.size) state.animateScrollToItem(0) else state.animateScrollToItem(next)
            }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        held = event.changes.any { it.pressed }
                    }
                }
            },
    ) {
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(RAIL_GAP),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            items(entries, key = key) { entry ->
                Box(Modifier.width(cardWidth)) { card(entry) }
            }
        }
        if (state.canScrollBackward) {
            RailArrow(MIcons.arrowLeft, tr("Назад"), Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                scope.launch { state.animateScrollToItem(max(0, state.firstVisibleItemIndex - 1)) }
            }
        }
        if (state.canScrollForward) {
            RailArrow(MIcons.arrowRight, tr("Дальше"), Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                scope.launch { state.animateScrollToItem(minOf(entries.lastIndex, state.firstVisibleItemIndex + 1)) }
            }
        }
    }
}

@Composable
private fun RailArrow(icon: MillidaIcon, label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Nav)
    Box(
        modifier
            .size(width = 40.dp, height = 56.dp)
            .pixelSurface(if (pressed) colors.raised else colors.bg.copy(alpha = MillidaPixel.railArrowAlpha), MillidaCut.control)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        MIcon(icon, size = 20.dp, color = colors.fg)
    }
}

enum class PlusTier {
    PLUS,
    DIAMOND,
    ;

    companion object {
        fun of(raw: String?): PlusTier? = entries.firstOrNull { it.name == raw?.trim()?.uppercase() }
    }
}

@Immutable
data class PlusMarkTone(val sole: Color, val line: Color, val body: Color, val hi: Color, val lo: Color) {
    companion object {
        val Plus = PlusMarkTone(
            MillidaPixel.plusMarkSole,
            MillidaPixel.plusMarkLine,
            MillidaPixel.plusMarkBody,
            MillidaPixel.plusMarkHi,
            MillidaPixel.plusMarkLo,
        )
        val Diamond = PlusMarkTone(
            MillidaPixel.diamondMarkSole,
            MillidaPixel.diamondMarkLine,
            MillidaPixel.diamondMarkBody,
            MillidaPixel.diamondMarkHi,
            MillidaPixel.diamondMarkLo,
        )
        val Off = PlusMarkTone(
            MillidaPixel.offMarkSole,
            MillidaPixel.offMarkLine,
            MillidaPixel.offMarkBody,
            MillidaPixel.offMarkHi,
            MillidaPixel.offMarkLo,
        )
    }
}

private val PLUS_GLYPHS: Map<Char, List<String>> = mapOf(
    'P' to listOf("####.", "##.##", "##.##", "####.", "##...", "##..."),
    'L' to listOf("##...", "##...", "##...", "##...", "#####", "#####"),
    'U' to listOf("##.##", "##.##", "##.##", "##.##", "#####", ".###."),
    'S' to listOf(".####", "##...", ".###.", "...##", "...##", "####."),
    'D' to listOf("####.", "##.##", "##.##", "##.##", "##.##", "####."),
    'I' to listOf("##", "##", "##", "##", "##", "##"),
    'A' to listOf(".###.", "##.##", "##.##", "#####", "##.##", "##.##"),
    'M' to listOf("##...##", "###.###", "#######", "##.#.##", "##...##", "##...##"),
    'O' to listOf(".###.", "##.##", "##.##", "##.##", "##.##", ".###."),
    'N' to listOf("##..##", "###.##", "######", "##.###", "##..##", "##..##"),
)
private const val GLYPH_ROWS = 6
private const val GLYPH_PAD = 1
private const val GLYPH_SOLE = 1
private const val GLYPH_GAP_UNKNOWN = 3
private const val SHINE_WIDTH = 3
private const val SHINE_START = -6

private class PlusMarkCells(
    val width: Int,
    val height: Int,
    val outline: List<Long>,
    val sole: List<Long>,
    val body: List<Long>,
    val hi: List<Long>,
    val lo: List<Long>,
)

private fun cellKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

private fun cellX(key: Long): Int = (key shr 32).toInt()

private fun cellY(key: Long): Int = key.toInt()

private val plusMarkCache = ConcurrentHashMap<String, PlusMarkCells>()

/** The desktop's `PlusMark` grid: letters of squares, a one-cell outline with a sole below, a light top edge and a dark bottom one. */
private fun plusMarkCells(text: String): PlusMarkCells = plusMarkCache.getOrPut(text) {
    val cells = LinkedHashSet<Long>()
    var x = GLYPH_PAD
    for (ch in text) {
        val glyph = PLUS_GLYPHS[ch]
        if (glyph == null) {
            x += GLYPH_GAP_UNKNOWN
            continue
        }
        glyph.forEachIndexed { y, row ->
            row.forEachIndexed { i, c -> if (c == '#') cells += cellKey(x + i, y + GLYPH_PAD) }
        }
        x += glyph[0].length + 1
    }
    val width = x - 1 + GLYPH_PAD
    val height = GLYPH_ROWS + GLYPH_PAD * 2 + GLYPH_SOLE
    val ring = LinkedHashSet<Long>()
    for (key in cells) {
        for (dx in -1..1) for (dy in -1..1) ring += cellKey(cellX(key) + dx, cellY(key) + dy)
    }
    val sole = ring.filter { cellKey(cellX(it), cellY(it) + 1) !in ring }.map { cellKey(cellX(it), cellY(it) + 1) }
    val hi = ArrayList<Long>()
    val lo = ArrayList<Long>()
    val body = ArrayList<Long>()
    for (key in cells) {
        when {
            cellKey(cellX(key), cellY(key) - 1) !in cells -> hi += key
            cellKey(cellX(key), cellY(key) + 1) !in cells -> lo += key
            else -> body += key
        }
    }
    PlusMarkCells(width, height, ring.toList(), sole, body, hi, lo)
}

/**
 * The subscription's sign, the big block word «PLUS» or «DIAMOND» (desktop
 * `PlusMark`): one component for every place the brand shows. [height] sets the
 * size, the width follows the word; [shine] runs a glint over the letters.
 */
@Composable
fun MPlusMark(
    modifier: Modifier = Modifier,
    tier: PlusTier = PlusTier.PLUS,
    height: Dp = 24.dp,
    text: String? = null,
    tone: PlusMarkTone = if (tier == PlusTier.DIAMOND) PlusMarkTone.Diamond else PlusMarkTone.Plus,
    shine: Boolean = false,
) {
    val word = (text ?: tier.name).uppercase()
    val cells = remember(word) { plusMarkCells(word) }
    val reduced = LocalReducedMotion.current
    val sweep = if (shine && !reduced) {
        rememberInfiniteTransition(label = "plus-mark-shine").animateFloat(
            initialValue = SHINE_START.toFloat(),
            targetValue = (cells.width + SHINE_WIDTH).toFloat(),
            // The sweep is read as floor(), one cell at a time: draw only when it moves a cell.
            animationSpec = infiniteRepeatable(
                tween(PLUS_SHINE_MS, easing = MillidaMotion.steps(cells.width + SHINE_WIDTH - SHINE_START)),
            ),
            label = "plus-mark-sweep",
        )
    } else {
        null
    }
    val width = height * cells.width.toFloat() / cells.height.toFloat()
    Canvas(
        modifier
            .size(width = width, height = height)
            .semantics { contentDescription = word },
    ) {
        val cell = size.height / cells.height
        fun paint(list: List<Long>, color: Color) {
            for (key in list) drawRect(color, Offset(cellX(key) * cell, cellY(key) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
        paint(cells.sole, tone.sole)
        paint(cells.outline, tone.line)
        paint(cells.body, tone.body)
        paint(cells.hi, tone.hi)
        paint(cells.lo, tone.lo)
        val at = sweep?.value?.let { floor(it).toInt() } ?: return@Canvas
        val lit = (cells.body + cells.hi + cells.lo).filter { cellX(it) in at until at + SHINE_WIDTH }
        paint(lit, MillidaPixel.markShine)
    }
}

/**
 * The desktop's subscription chip (`PlusChip`): 28 tall, the pixel word and the
 * paid-until date. Without a subscription the word is grey with a small glowing
 * gold «+» beside it; a tap opens PLUS.
 */
@Composable
fun MPlusChip(tier: PlusTier?, onClick: () -> Unit, modifier: Modifier = Modifier, until: String? = null) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Click)
    val off = tier == null
    val fill = when {
        off -> if (pressed) colors.raised else colors.surface2
        tier == PlusTier.DIAMOND -> if (pressed) MillidaPixel.diamondChipPressed else MillidaPixel.diamondChip
        else -> if (pressed) MillidaPixel.plusChipPressed else MillidaPixel.plusChip
    }
    val markTone = when {
        off && pressed -> PlusMarkTone.Plus
        off -> PlusMarkTone.Off
        tier == PlusTier.DIAMOND -> PlusMarkTone.Diamond
        else -> PlusMarkTone.Plus
    }
    val spoken = when {
        off -> tr("Подключить PLUS")
        tier == PlusTier.DIAMOND -> "Diamond"
        else -> "PLUS"
    }
    Row(
        modifier
            .height(PLUS_CHIP_HEIGHT)
            .pixelSurface(fill, MillidaCut.badge)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(horizontal = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MPlusMark(tier = tier ?: PlusTier.PLUS, height = PLUS_CHIP_MARK, tone = markTone, shine = !off)
        if (off) {
            PlusAdd()
        } else if (!until.isNullOrEmpty()) {
            Text(
                tr("до {until}", until),
                style = MillidaType.small.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold),
                color = if (tier == PlusTier.DIAMOND) MillidaPixel.diamondChipInk else MillidaPixel.plusChipInk,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun PlusAdd() {
    val reduced = LocalReducedMotion.current
    val glow = if (reduced) {
        null
    } else {
        rememberInfiniteTransition(label = "plus-add").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(PLUS_GLOW_MS, easing = LinearEasing)),
            label = "plus-add-glow",
        )
    }
    Canvas(Modifier.size(12.dp)) {
        val phase = glow?.value ?: 0f
        val dim = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
        val halo = lerp(MillidaPixel.plusAddGlow, MillidaPixel.plusAddGlowDim, dim)
        val cell = size.width / 3f
        val spread = cell * 0.6f
        val haze = halo.copy(alpha = halo.alpha * PLUS_HAZE)
        drawRect(haze, Offset(cell - spread, -spread), Size(cell + spread * 2, size.height + spread * 2))
        drawRect(haze, Offset(-spread, cell - spread), Size(size.width + spread * 2, cell + spread * 2))
        drawRect(MillidaPixel.plusAdd, Offset(cell, 0f), Size(cell, size.height))
        drawRect(MillidaPixel.plusAdd, Offset(0f, cell), Size(size.width, cell))
    }
}

private const val PLUS_HAZE = 0.35f

/**
 * A currency balance plate from the desktop shop's head (`sh-bal`): the coin and the
 * number, «—» while the amount is still unknown.
 */
@Composable
fun MBalanceBadge(icon: MillidaIcon, amount: Long?, label: String, modifier: Modifier = Modifier) {
    val colors = millida
    Row(
        modifier
            .height(BALANCE_HEIGHT)
            .pixelSurface(
                colors.surface2,
                MillidaCut.control,
                PixelFrame(top = colors.plateTop, topWidth = 2.dp, bottom = colors.plateBottom, bottomWidth = 3.dp),
            )
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(icon, size = 20.dp)
        Text(
            amount?.let { groupDigits(it) } ?: "—",
            style = MillidaType.button.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.01).em,
                fontFeatureSettings = "tnum",
            ),
            color = colors.fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun MShardBalance(amount: Long?, modifier: Modifier = Modifier) {
    MBalanceBadge(MIcons.shard, amount, tr("Осколки"), modifier)
}

/** The desktop's `faint-note`: a quiet grey line under a block. */
@Composable
fun MFaintNote(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(
        text,
        modifier = modifier.padding(top = 10.dp),
        style = MillidaType.small.copy(fontSize = 12.5.sp, lineHeight = 20.sp),
        color = millida.fgSubtle,
        textAlign = textAlign,
    )
}

/**
 * An underlined grey text link (`acc-choice-buy`): for a way out that must not
 * compete with the buttons above it. The row keeps a finger-sized height.
 */
@Composable
fun MTextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Click)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MillidaType.body.copy(fontSize = 13.sp, textDecoration = TextDecoration.Underline),
            color = if (pressed) colors.fg else colors.fgMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
