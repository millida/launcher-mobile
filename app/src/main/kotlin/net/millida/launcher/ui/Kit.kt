package net.millida.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import net.millida.launcher.core.media.UiSoundEvent
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt

internal const val DISABLED_ALPHA = 0.45f

enum class MButtonVariant { Primary, Secondary, Ghost, Danger, Running }

enum class MButtonSize(val height: Dp, val padding: Dp, val fontSize: TextUnit, val icon: Dp) {
    Small(44.dp, 14.dp, 13.5.sp, 16.dp),
    Medium(48.dp, 20.dp, 14.5.sp, 18.dp),
    Large(56.dp, 28.dp, 16.sp, 20.dp),
}

@Immutable
private class ButtonLook(
    val fill: Color,
    val ink: Color,
    val frame: PixelFrame,
    val dots: Boolean,
    val sinks: Boolean,
)

private fun buttonLook(variant: MButtonVariant, pressed: Boolean, loading: Boolean, c: MillidaColors): ButtonLook {
    if (loading) return ButtonLook(c.surface2, c.fg, PixelFrame.Button, dots = false, sinks = false)
    val bevel = if (pressed) PixelFrame.ButtonPressed else PixelFrame.Button
    return when (variant) {
        MButtonVariant.Primary -> ButtonLook(
            fill = if (pressed) MillidaPixel.pressShade.compositeOver(c.accent) else c.accent,
            ink = c.onAccent,
            frame = bevel,
            dots = true,
            sinks = true,
        )
        MButtonVariant.Secondary -> ButtonLook(
            fill = if (pressed) MillidaPixel.pressShade.compositeOver(c.surface2) else c.surface2,
            ink = c.fg,
            frame = bevel,
            dots = false,
            sinks = true,
        )
        MButtonVariant.Ghost -> ButtonLook(
            fill = if (pressed) c.surface2 else Color.Transparent,
            ink = if (pressed) c.fg else c.fgMuted,
            frame = PixelFrame.None,
            dots = false,
            sinks = false,
        )
        MButtonVariant.Danger -> ButtonLook(
            fill = if (pressed) c.danger else c.dangerSoft,
            ink = if (pressed) MillidaPixel.white else c.danger,
            frame = PixelFrame.Danger,
            dots = false,
            sinks = true,
        )
        MButtonVariant.Running -> ButtonLook(
            fill = c.accentSoft,
            ink = c.accentText,
            frame = PixelFrame.running(c.accent),
            dots = false,
            sinks = true,
        )
    }
}

@Composable
fun MButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: MButtonVariant = MButtonVariant.Secondary,
    size: MButtonSize = MButtonSize.Medium,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: MillidaIcon? = null,
    contentColor: Color = Color.Unspecified,
    sound: UiSoundEvent = UiSoundEvent.Click,
    trailingIcon: MillidaIcon? = null,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val active = enabled && !loading
    val pressed by rememberPressed(interaction, sound)
    val down = pressed && active
    val look = buttonLook(variant, down, loading, colors)
    val progress = rememberPressProgress(down && look.sinks)
    val fill by animateColorAsState(look.fill, tween(MillidaMotion.fast), label = "button-fill")
    val ink = when {
        loading -> look.ink
        variant == MButtonVariant.Danger && down -> look.ink
        contentColor.isSpecified -> contentColor
        else -> look.ink
    }
    val sole = if (variant == MButtonVariant.Ghost) 0.dp else 3.dp
    Row(
        modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .pressShift(progress)
            .heightIn(min = size.height)
            .pixelSurface(fill, MillidaCut.control, look.frame, dots = look.dots)
            .clickable(interaction, indication = null, enabled = active, role = Role.Button, onClick = onClick)
            .padding(start = size.padding, end = size.padding, bottom = sole),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            MPixelLoader(color = ink)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            MIcon(icon, size = size.icon, color = ink)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MillidaType.button.copy(fontSize = size.fontSize),
            color = ink,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailingIcon != null && !loading) {
            Spacer(Modifier.width(6.dp))
            MIcon(trailingIcon, size = size.icon, color = ink)
        }
    }
}

@Composable
fun MPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: MillidaIcon? = null,
    size: MButtonSize = MButtonSize.Medium,
    loading: Boolean = false,
) {
    MButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        variant = MButtonVariant.Primary,
        size = size,
        enabled = enabled,
        loading = loading,
        icon = icon,
    )
}

@Composable
fun MSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: MillidaIcon? = null,
    size: MButtonSize = MButtonSize.Medium,
    loading: Boolean = false,
) {
    MButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        variant = MButtonVariant.Secondary,
        size = size,
        enabled = enabled,
        loading = loading,
        icon = icon,
    )
}

@Composable
fun MDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: MillidaIcon? = null,
    size: MButtonSize = MButtonSize.Small,
) {
    MButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        variant = MButtonVariant.Danger,
        size = size,
        enabled = enabled,
        icon = icon,
        sound = UiSoundEvent.Click,
    )
}

@Composable
fun MTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: MillidaIcon? = null,
    color: Color = Color.Unspecified,
    trailingIcon: MillidaIcon? = null,
) {
    MButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        variant = MButtonVariant.Ghost,
        size = MButtonSize.Small,
        enabled = enabled,
        icon = icon,
        contentColor = color,
        trailingIcon = trailingIcon,
    )
}

@Composable
fun MPixelLoader(modifier: Modifier = Modifier, color: Color = millida.accent) {
    val reduced = LocalReducedMotion.current
    val phase: State<Float>? = if (reduced) {
        null
    } else {
        rememberInfiniteTransition(label = "loader").animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "loader-phase",
        )
    }
    Canvas(modifier.size(width = 18.dp, height = 12.dp)) {
        val block = max(1f, 4.dp.roundToPx().toFloat())
        val gap = floor((size.width - block * 3f) / 2f)
        val hop = 3.dp.roundToPx().toFloat()
        val active = phase?.value?.toInt()?.coerceIn(0, 2) ?: -1
        for (index in 0 until 3) {
            val lift = if (index == active) hop else 0f
            drawRect(
                color = color,
                topLeft = Offset(index * (block + gap), size.height - block - lift),
                size = Size(block, block),
            )
        }
    }
}

@Composable
private fun IconPlate(
    icon: MillidaIcon,
    onClick: () -> Unit,
    modifier: Modifier,
    fill: Color,
    ink: Color,
    frame: PixelFrame,
    pressedFrame: PixelFrame,
    dots: Boolean,
    enabled: Boolean,
    badge: Int,
    label: String?,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Click)
    val down = pressed && enabled
    val progress = rememberPressProgress(down)
    Box(modifier.size(44.dp).alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        Box(
            Modifier
                .fillMaxSize()
                .pressShift(progress)
                .pixelSurface(
                    fill = if (down) MillidaPixel.pressShade.compositeOver(fill) else fill,
                    cut = MillidaCut.control,
                    frame = if (down) pressedFrame else frame,
                    dots = dots,
                )
                .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
                .padding(bottom = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            MIcon(icon, size = 20.dp, color = ink)
        }
        if (badge > 0) {
            MCounterBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp))
        }
    }
}

@Composable
fun MIconButton(
    icon: MillidaIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = millida.fgSubtle,
    background: Color = millida.surface2,
    enabled: Boolean = true,
    badge: Int = 0,
    label: String? = null,
) {
    val colors = millida
    IconPlate(
        icon = icon,
        onClick = onClick,
        modifier = modifier,
        fill = background,
        ink = tint,
        frame = colors.plateFrame(),
        pressedFrame = PixelFrame(top = MillidaPixel.pressTop, bottom = colors.plateBottom, bottomWidth = 2.dp),
        dots = false,
        enabled = enabled,
        badge = badge,
        label = label,
    )
}

@Composable
fun MBackButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "Назад") {
    val colors = millida
    IconPlate(
        icon = MIcons.back,
        onClick = onClick,
        modifier = modifier,
        fill = colors.accent,
        ink = colors.onAccent,
        frame = PixelFrame.Button,
        pressedFrame = PixelFrame.ButtonPressed,
        dots = true,
        enabled = true,
        badge = 0,
        label = label,
    )
}

@Composable
fun MChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: MillidaIcon? = null,
    count: Int? = null,
    enabled: Boolean = true,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Click)
    val down = pressed && enabled
    val progress = rememberPressProgress(down)
    val fill = when {
        selected -> colors.accentSoft
        down -> colors.segmentFillOn
        else -> colors.chipFill
    }
    val ink = if (selected) colors.accentText else colors.fgMuted
    Row(
        modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .pressShift(progress, 1.dp)
            .heightIn(min = 44.dp)
            .pixelSurface(fill, MillidaCut.control, if (selected) PixelFrame.Chip else PixelFrame.None)
            .selectable(selected, interaction, null, enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            MIcon(icon, size = 16.dp, color = ink)
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text,
            style = MillidaType.button.copy(
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            ),
            color = ink,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), style = MillidaType.pixelSmall, color = if (selected) colors.accentText else colors.fgFaint)
        }
    }
}

@Composable
fun MField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    leadingIcon: MillidaIcon? = null,
    clearable: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    maxLines: Int = if (singleLine) 1 else 6,
    autoCorrectEnabled: Boolean? = null,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val showClear = clearable && enabled && value.isNotEmpty()
    val textStyle = MillidaType.body.copy(fontSize = 15.sp, lineHeight = 21.sp, color = colors.fg)
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .heightIn(min = 48.dp)
            .pixelSurface(colors.inset, MillidaCut.control, colors.wellFrame(focused))
            .padding(start = if (leadingIcon != null) 12.dp else 16.dp, end = if (showClear) 2.dp else 16.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            MIcon(leadingIcon, size = 18.dp, color = colors.fgSubtle)
            Spacer(Modifier.width(10.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp)
                .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)),
            enabled = enabled,
            textStyle = textStyle,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = autoCorrectEnabled,
                keyboardType = keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = if (onImeAction == null) {
                KeyboardActions.Default
            } else {
                KeyboardActions(
                    onDone = { onImeAction() },
                    onSearch = { onImeAction() },
                    onGo = { onImeAction() },
                    onSend = { onImeAction() },
                )
            },
            singleLine = singleLine,
            maxLines = maxLines,
            interactionSource = interaction,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = textStyle,
                            color = colors.fgFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (showClear) {
            val clearInteraction = remember { MutableInteractionSource() }
            val clearPressed by rememberPressed(clearInteraction, UiSoundEvent.Click)
            val clearPress = rememberPressProgress(clearPressed)
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(
                        interactionSource = clearInteraction,
                        indication = null,
                        onClickLabel = "Очистить",
                        role = Role.Button,
                    ) { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                MIcon(MIcons.close, size = 14.dp, color = colors.fgSubtle, modifier = Modifier.pressShift(clearPress, 1.dp))
            }
        }
    }
}

@Composable
private fun ToggleTrack(checked: Boolean) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    val knob by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(if (reduced) 1 else MillidaMotion.fast, easing = MillidaMotion.steps(2)),
        label = "toggle-knob",
    )
    val frame = if (checked) {
        PixelFrame(top = MillidaPixel.toggleOnTop, bottom = MillidaPixel.toggleOnBottom)
    } else {
        colors.wellFrame()
    }
    Box(
        Modifier
            .size(width = 38.dp, height = 22.dp)
            .pixelSurface(if (checked) colors.accent else colors.inset, MillidaCut.badge, frame),
    ) {
        Box(
            Modifier
                .offset { IntOffset((3.dp + 16.dp * knob).roundToPx(), 3.dp.roundToPx()) }
                .size(16.dp)
                .pixelSurface(
                    fill = if (checked) knobOn(colors.accent) else colors.fgSubtle,
                    cut = 2.dp,
                    frame = PixelFrame(bottom = MillidaPixel.knobShade, bottomWidth = 2.dp),
                ),
        )
    }
}

@Composable
fun MToggle(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    rememberPressed(interaction, UiSoundEvent.Toggle)
    Box(
        modifier
            .size(width = 52.dp, height = 44.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .toggleable(checked, interaction, null, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        contentAlignment = Alignment.Center,
    ) {
        ToggleTrack(checked)
    }
}

@Composable
fun MSwitchRow(
    title: String,
    hint: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    rememberPressed(interaction, UiSoundEvent.Toggle)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(checked, interaction, null, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = millida.fg)
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = millida.fgFaint)
            }
        }
        Spacer(Modifier.width(14.dp))
        ToggleTrack(checked)
    }
}

@Composable
fun MSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MillidaType.caption,
        color = millida.fgSubtle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun MSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MillidaType.sectionTitle,
            color = millida.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null && onAction != null) {
            MTextButton(action, onClick = onAction, color = millida.accentText, trailingIcon = MIcons.chevronRight)
        }
    }
}

@Composable
fun MProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = millida.accent,
    indeterminate: Boolean = progress == null,
) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    val shown: Float? = if (indeterminate) null else (progress?.takeIf { !it.isNaN() }?.coerceIn(0f, 1f) ?: 0f)
    val fraction by animateFloatAsState(
        targetValue = shown ?: 0f,
        animationSpec = tween(MillidaMotion.slow, easing = LinearEasing),
        label = "progress",
    )
    val sweep: State<Float>? = if (shown == null && !reduced) {
        rememberInfiniteTransition(label = "progress-sweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = MillidaMotion.steps(14))),
            label = "progress-sweep-phase",
        )
    } else {
        null
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(shown ?: 0f, 0f..1f) }
            .pixelSurface(colors.inset, 2.dp),
    ) {
        val w = size.width
        val h = size.height
        val shine = max(1f, 2.dp.roundToPx().toFloat())
        if (shown == null) {
            val block = floor(w * 0.3f)
            val phase = sweep?.value ?: 0.35f
            val x = floor((w + block) * phase - block)
            drawRect(color, Offset(x, 0f), Size(block, h))
            drawRect(MillidaPixel.barShine, Offset(x, 0f), Size(block, shine))
        } else {
            val filled = floor(w * fraction)
            drawRect(color, Offset.Zero, Size(filled, h))
            drawRect(MillidaPixel.barShine, Offset.Zero, Size(filled, shine))
        }
        val notch = max(1f, 2.dp.roundToPx().toFloat())
        for (index in 1 until 10) {
            val x = floor(w * index / 10f - notch / 2f)
            drawRect(MillidaPixel.divider, Offset(x, 0f), Size(notch, h))
        }
    }
}

@Composable
internal fun rememberShimmer(): State<Float>? {
    if (LocalReducedMotion.current) return null
    return rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = MillidaMotion.steps(13))),
        label = "shimmer-phase",
    )
}

internal fun Modifier.shimmer(phase: State<Float>?, color: Color): Modifier = drawBehind {
    val value = phase?.value ?: return@drawBehind
    val band = floor(size.width * 0.28f)
    val x = floor((size.width + band) * value - band)
    drawRect(color, Offset(x, 0f), Size(band, size.height))
}

@Composable
fun MSkeleton(height: Dp = 64.dp, modifier: Modifier = Modifier) {
    val colors = millida
    val phase = rememberShimmer()
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .pixelSurface(colors.surface2, MillidaCut.plate, colors.plateFrame())
            .shimmer(phase, colors.skeletonShine),
    )
}

@Composable
fun MSkeletonRow(modifier: Modifier = Modifier) {
    val colors = millida
    val phase = rememberShimmer()
    Row(
        modifier
            .fillMaxWidth()
            .pixelSurface(colors.surface, MillidaCut.plate, colors.plateFrame())
            .shimmer(phase, colors.skeletonShine)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .pixelSurface(colors.surface2, MillidaCut.badge),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(0.55f)
                    .height(12.dp)
                    .pixelSurface(colors.surface2, 2.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.35f)
                    .height(10.dp)
                    .pixelSurface(colors.surface2, 2.dp),
            )
        }
    }
}

@Composable
fun MBadge(
    text: String,
    color: Color,
    background: Color,
    modifier: Modifier = Modifier,
    icon: MillidaIcon? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .heightIn(min = 24.dp)
            .pixelSurface(background, MillidaCut.badge)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            MIcon(icon, size = 13.dp, color = color)
            Spacer(Modifier.width(5.dp))
        }
        if (leading != null) {
            leading()
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            color = color,
            style = MillidaType.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MCounterBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    CounterPlate(if (count > 99) "99+" else count.toString(), modifier)
}

@Composable
fun MAlertBadge(modifier: Modifier = Modifier) {
    CounterPlate("!", modifier)
}

@Composable
private fun CounterPlate(text: String, modifier: Modifier) {
    Box(
        modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .pixelSurface(MillidaPixel.counter, 3.dp, PixelFrame(bottom = MillidaPixel.pressBottom, bottomWidth = 2.dp))
            .padding(start = 5.dp, end = 5.dp, bottom = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black, fontSize = 11.5.sp),
            color = MillidaPixel.counterInk,
            maxLines = 1,
        )
    }
}

@Composable
fun MRemoteImage(
    url: String?,
    icon: MillidaIcon,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    shape: Shape = MillidaShape.control,
    tint: Color = millida.fgFaint,
    background: Color = millida.surface2,
    pixelated: Boolean = false,
) {
    val address = url?.trim().orEmpty()
    var loaded by remember(address) { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .clip(shape)
            .drawBehind { drawRect(background) },
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) MIcon(icon, size = size * 0.45f, color = tint)
        if (address.isNotEmpty()) {
            AsyncImage(
                model = address,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = if (pixelated) FilterQuality.None else FilterQuality.Low,
                onSuccess = { loaded = true },
                onError = { loaded = false },
            )
        }
    }
}

@Composable
fun MLocalImage(path: String, modifier: Modifier = Modifier) {
    if (path.isEmpty()) return
    SubcomposeAsyncImage(
        model = java.io.File(path),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = {},
        error = {},
    )
}

// onValueChangeFinished fires once per gesture so a setting is written when the finger lifts.
@Composable
fun MSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = millida
    val range by rememberUpdatedState(valueRange)
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    val stepCount by rememberUpdatedState(steps)
    var width by remember { mutableIntStateOf(0) }
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span <= 0f) 0f else ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val thumb = 22.dp

    fun valueAt(x: Float, total: Int, thumbPx: Float): Float {
        val usable = total - thumbPx
        val share = if (usable <= 0f) 0f else ((x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
        val snapped = if (stepCount > 0) round(share * (stepCount + 1)) / (stepCount + 1) else share
        return range.start + snapped * (range.endInclusive - range.start)
    }

    val gestures = if (enabled) {
        Modifier
            .pointerInput(Unit) {
                val thumbPx = thumb.toPx()
                detectTapGestures { position ->
                    change(valueAt(position.x, size.width, thumbPx))
                    finish?.invoke()
                }
            }
            .pointerInput(Unit) {
                val thumbPx = thumb.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { position -> change(valueAt(position.x, size.width, thumbPx)) },
                    onDragEnd = { finish?.invoke() },
                    onDragCancel = { finish?.invoke() },
                ) { pointer, _ ->
                    pointer.consume()
                    change(valueAt(pointer.position.x, size.width, thumbPx))
                }
            }
    } else {
        Modifier
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .onSizeChanged { width = it.width }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                if (enabled) {
                    setProgress { target ->
                        change(target.coerceIn(range))
                        finish?.invoke()
                        true
                    }
                }
            }
            .then(gestures),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(8.dp),
        ) {
            val thumbPx = thumb.toPx()
            val usable = max(0f, size.width - thumbPx)
            val center = floor(thumbPx / 2f + usable * fraction)
            drawRect(colors.inset)
            drawRect(colors.accent, Offset.Zero, Size(center, size.height))
            drawRect(MillidaPixel.barShine, Offset.Zero, Size(center, max(1f, 2.dp.roundToPx().toFloat())))
            if (stepCount in 1..20) {
                val tick = max(1f, 2.dp.roundToPx().toFloat())
                for (index in 1..stepCount) {
                    val x = floor(thumbPx / 2f + usable * index / (stepCount + 1) - tick / 2f)
                    drawRect(MillidaPixel.divider, Offset(x, 0f), Size(tick, size.height))
                }
            }
        }
        Box(
            Modifier
                .offset {
                    val thumbPx = thumb.roundToPx()
                    IntOffset(((width - thumbPx).coerceAtLeast(0) * fraction).roundToInt(), 0)
                }
                .size(thumb)
                .pixelSurface(
                    fill = MillidaPixel.white,
                    cut = MillidaCut.badge,
                    frame = PixelFrame(
                        outline = colors.accent,
                        outlineWidth = 2.dp,
                        bottom = MillidaPixel.knobShade,
                        bottomWidth = 3.dp,
                    ),
                ),
        )
    }
}

@Composable
private fun SegmentItem(
    text: String,
    on: Boolean,
    badge: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (on) null else UiSoundEvent.Nav)
    val progress = rememberPressProgress(pressed && !on)
    Row(
        modifier
            .pressShift(progress, 1.dp)
            .heightIn(min = 44.dp)
            .pixelSurface(
                fill = if (on) colors.segmentFillOn else colors.segmentFill,
                cut = MillidaCut.control,
                frame = if (on) PixelFrame.Chip else PixelFrame.None,
            )
            .selectable(on, interaction, null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            style = MillidaType.button.copy(
                fontSize = 13.5.sp,
                fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold,
            ),
            color = if (on) colors.fg else colors.fgSubtle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (badge > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .pixelSurface(colors.accent, 3.dp)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (badge > 99) "99+" else badge.toString(),
                    style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black),
                    color = colors.onAccent,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun <T> MSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    badge: (T) -> Int = { 0 },
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            SegmentItem(
                text = label(option),
                on = option == selected,
                badge = badge(option),
                modifier = Modifier.weight(1f),
                onClick = { if (option != selected) onSelect(option) },
            )
        }
    }
}

@Composable
private fun HubTab(
    text: String,
    icon: MillidaIcon?,
    on: Boolean,
    badge: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (on) null else UiSoundEvent.Nav)
    val down = pressed && !on
    val progress = rememberPressProgress(down, MillidaMotion.tilePress, MillidaMotion.steps(2))
    val ink = if (on) colors.onAccent else colors.fgMuted
    Box(modifier) {
        Row(
            Modifier
                .pressShift(progress, 1.dp)
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .pixelSurface(
                    fill = when {
                        on -> colors.accent
                        down -> colors.segmentFillOn
                        else -> Color.Transparent
                    },
                    cut = MillidaCut.control,
                    frame = if (on) PixelFrame(bottom = MillidaPixel.tabOnBottom, bottomWidth = 3.dp) else PixelFrame.None,
                    dots = on,
                )
                .selectable(on, interaction, null, role = Role.Tab, onClick = onClick)
                .padding(start = 14.dp, end = 16.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                MIcon(icon, size = 20.dp, color = ink)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MillidaType.button.copy(fontSize = 14.5.sp),
                color = ink,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge > 0) {
            MCounterBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-6).dp))
        }
    }
}

@Composable
fun <T> MTabs(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    icon: (T) -> MillidaIcon? = { null },
    badge: (T) -> Int = { 0 },
    scrollable: Boolean = false,
) {
    val colors = millida
    Row(
        modifier
            .fillMaxWidth()
            .pixelSurface(colors.inset, MillidaCut.control)
            .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { tab ->
            HubTab(
                text = label(tab),
                icon = icon(tab),
                on = tab == selected,
                badge = badge(tab),
                modifier = if (scrollable) Modifier else Modifier.weight(1f),
                onClick = { if (tab != selected) onSelect(tab) },
            )
        }
    }
}

@Composable
fun MCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    background: Color = millida.surface,
    contentPadding: Dp = 16.dp,
    frame: PixelFrame? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (onClick != null) UiSoundEvent.Nav else null)
    val down = pressed && onClick != null
    val target = when {
        !down -> background
        background == colors.surface -> colors.surface2
        else -> MillidaPixel.pressShade.compositeOver(background)
    }
    val fill by animateColorAsState(target, tween(80), label = "card-fill")
    Column(
        modifier
            .fillMaxWidth()
            .pixelSurface(fill, MillidaCut.plate, frame ?: colors.plateFrame())
            .then(
                if (onClick != null) {
                    Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(contentPadding),
        content = content,
    )
}
