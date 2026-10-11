package net.millida.launcher.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.millida.launcher.core.hud.HudControl
import net.millida.launcher.core.hud.HudKeys
import net.millida.launcher.runtime.GameInputSink
import kotlin.math.abs
import kotlin.math.roundToInt

private const val KNOB_SHARE = 0.36f
private const val DEAD_SHARE = 0.3f
private const val AXIS_SHARE = 0.35f
private const val FIXED_SPRINT_SHARE = 0.85f
private const val FLOAT_SPRINT_SHARE = 0.9f
private const val LOCK_MS = 500L
private const val REST_ALPHA = 0.6f

internal fun stickKeys(offset: Offset, radius: Float, sprintShare: Float, sprint: Boolean): Set<Int> {
    val length = offset.getDistance()
    if (length < radius * DEAD_SHARE) return emptySet()
    val keys = LinkedHashSet<Int>()
    if (offset.y < -radius * AXIS_SHARE) keys += HudKeys.W
    if (offset.y > radius * AXIS_SHARE) keys += HudKeys.S
    if (offset.x < -radius * AXIS_SHARE) keys += HudKeys.A
    if (offset.x > radius * AXIS_SHARE) keys += HudKeys.D
    if (HudKeys.W in keys && (sprint || length > radius * sprintShare)) keys += HudKeys.LEFT_CONTROL
    return keys
}

private fun clampTo(offset: Offset, radius: Float): Offset {
    val length = offset.getDistance()
    return if (length > radius) offset * (radius / length) else offset
}

private class StickKeys(private val sink: GameInputSink) {
    var pressed: Set<Int> = emptySet()
        private set

    fun apply(next: Set<Int>) {
        (pressed - next).forEach { sink.key(it, it, false) }
        (next - pressed).forEach { sink.key(it, it, true) }
        pressed = next
    }
}

@Composable
private fun StickPlate(sizeDp: Float, alpha: Float, modifier: Modifier = Modifier) {
    val colors = millida
    Box(
        modifier
            .size(sizeDp.dp)
            .graphicsLayer { this.alpha = alpha }
            .pixelSurface(
                fill = colors.inset.copy(alpha = 0.5f),
                cut = MillidaCut.plate,
                frame = PixelFrame(outline = colors.fg.copy(alpha = 0.28f), outlineWidth = 2.dp),
            ),
    )
}

@Composable
private fun StickKnob(sizeDp: Float, active: Boolean, sprint: Boolean, opacity: Float, modifier: Modifier = Modifier) {
    val colors = millida
    Box(
        modifier
            .size(sizeDp.dp)
            .graphicsLayer { alpha = if (active) 1f else opacity }
            .pixelSurface(
                fill = when {
                    sprint -> colors.accent
                    active -> colors.fg
                    else -> colors.fg.copy(alpha = 0.78f)
                },
                cut = MillidaCut.control,
                frame = if (active) PixelFrame.ButtonPressed else PixelFrame.Button,
                dots = sprint,
            ),
    )
}

@Composable
internal fun HudStick(control: HudControl, opacity: Float, sink: GameInputSink) {
    val radius = with(LocalDensity.current) { control.width.dp.toPx() / 2f }
    val knobRadius = radius * KNOB_SHARE
    var knob by remember(control.id) { mutableStateOf(Offset.Zero) }
    val keys = remember(control.id, sink) { StickKeys(sink) }
    var pressed by remember(control.id) { mutableStateOf(emptySet<Int>()) }

    fun apply(next: Set<Int>) {
        keys.apply(next)
        pressed = next
    }

    DisposableEffect(control.id) {
        onDispose { keys.apply(emptySet()) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(control.id) {
                awaitPointerEventScope {
                    var finger: PointerId? = null
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val tracked = finger
                            val found = if (tracked == null) {
                                event.changes.firstOrNull { it.pressed && !it.previousPressed }?.also { finger = it.id }
                            } else {
                                event.changes.firstOrNull { it.id == tracked }
                            }
                            val change = found ?: continue
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            if (change.pressed) {
                                knob = clampTo(change.position - centre, radius)
                                apply(stickKeys(knob, radius, FIXED_SPRINT_SHARE, sprint = false))
                            } else {
                                finger = null
                                knob = Offset.Zero
                                apply(emptySet())
                            }
                            change.consume()
                        }
                    } finally {
                        knob = Offset.Zero
                        apply(emptySet())
                    }
                }
            },
    ) {
        StickPlate(control.width, opacity, Modifier.fillMaxSize())
        StickKnob(
            sizeDp = control.width * KNOB_SHARE,
            active = pressed.isNotEmpty(),
            sprint = HudKeys.LEFT_CONTROL in pressed,
            opacity = opacity,
            modifier = Modifier
                .align(Alignment.Center)
                .offset {
                    val travel = (radius - knobRadius) / radius
                    IntOffset((knob.x * travel).roundToInt(), (knob.y * travel).roundToInt())
                },
        )
    }
}

/**
 * The stick that comes to the thumb: it appears where the finger lands in its
 * zone and rests dimmed at its own place otherwise. Pushing to the very edge
 * forward or a double tap runs; holding at the top edge locks running until
 * the next touch.
 *
 * [zoneOrigin] and [rest] are in pixels of the screen; [rest] is the centre of the resting stick.
 */
@Composable
internal fun HudFloatStick(
    control: HudControl,
    opacity: Float,
    sink: GameInputSink,
    zoneOrigin: Offset,
    rest: Offset,
    haptics: Boolean,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val buzzOn by rememberUpdatedState(haptics)
    val radius = with(LocalDensity.current) { control.width.dp.toPx() / 2f }
    val knobRadius = radius * KNOB_SHARE
    val keys = remember(control.id, sink) { StickKeys(sink) }
    var origin by remember(control.id) { mutableStateOf<Offset?>(null) }
    var knob by remember(control.id) { mutableStateOf(Offset.Zero) }
    var pressed by remember(control.id) { mutableStateOf(emptySet<Int>()) }
    var locked by remember(control.id) { mutableStateOf(false) }
    var lastDown by remember(control.id) { mutableLongStateOf(0L) }
    val restLocal = rest - zoneOrigin

    fun apply(next: Set<Int>) {
        keys.apply(next)
        pressed = next
    }

    DisposableEffect(control.id) {
        onDispose { keys.apply(emptySet()) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(control.id, radius) {
                awaitPointerEventScope {
                    var finger: PointerId? = null
                    var sprintTap = false
                    var lockJob: Job? = null
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val tracked = finger
                            if (tracked == null) {
                                val down = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                                down.consume()
                                finger = down.id
                                locked = false
                                sprintTap = down.uptimeMillis - lastDown < DOUBLE_TAP_MS
                                lastDown = down.uptimeMillis
                                origin = down.position
                                knob = Offset.Zero
                                apply(emptySet())
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == tracked } ?: continue
                            change.consume()
                            val start = origin ?: change.position
                            if (change.pressed) {
                                knob = clampTo(change.position - start, radius)
                                apply(stickKeys(knob, radius, FLOAT_SPRINT_SHARE, sprintTap))
                                val atTop = knob.y < -radius * FLOAT_SPRINT_SHARE && abs(knob.x) < radius * AXIS_SHARE
                                if (atTop && lockJob == null) {
                                    lockJob = scope.launch {
                                        delay(LOCK_MS)
                                        locked = true
                                        if (buzzOn) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    }
                                } else if (!atTop) {
                                    lockJob?.cancel()
                                    lockJob = null
                                    locked = false
                                }
                            } else {
                                finger = null
                                lockJob?.cancel()
                                lockJob = null
                                if (locked) {
                                    knob = Offset(0f, -radius)
                                    apply(setOf(HudKeys.W, HudKeys.LEFT_CONTROL))
                                } else {
                                    knob = Offset.Zero
                                    origin = null
                                    apply(emptySet())
                                }
                            }
                        }
                    } finally {
                        lockJob?.cancel()
                        locked = false
                        origin = null
                        knob = Offset.Zero
                        apply(emptySet())
                    }
                }
            },
    ) {
        val active = origin != null
        val sprint = HudKeys.LEFT_CONTROL in pressed
        val plateAlpha = if (active) opacity else opacity * REST_ALPHA
        StickPlate(
            sizeDp = control.width,
            alpha = plateAlpha,
            modifier = Modifier.offset {
                val centre = origin ?: restLocal
                IntOffset((centre.x - radius).roundToInt(), (centre.y - radius).roundToInt())
            },
        )
        StickKnob(
            sizeDp = control.width * KNOB_SHARE,
            active = active,
            sprint = sprint,
            opacity = plateAlpha,
            modifier = Modifier.offset {
                val centre = origin ?: restLocal
                val travel = (radius - knobRadius) / radius
                IntOffset(
                    (centre.x + knob.x * travel - knobRadius).roundToInt(),
                    (centre.y + knob.y * travel - knobRadius).roundToInt(),
                )
            },
        )
        if (locked) {
            val iconSize = 20.dp
            val iconHalf = with(LocalDensity.current) { iconSize.toPx() / 2f }
            MIcon(
                MIcons.lock,
                size = iconSize,
                color = millida.accentText,
                modifier = Modifier.offset {
                    val centre = origin ?: restLocal
                    IntOffset((centre.x - iconHalf).roundToInt(), (centre.y - radius - iconHalf * 3f).roundToInt())
                },
            )
        }
    }
}
