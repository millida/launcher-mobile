package net.millida.launcher.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.millida.launcher.core.hud.HudAction
import net.millida.launcher.core.hud.HudControl
import net.millida.launcher.core.hud.HudIcons
import net.millida.launcher.core.hud.HudKeys
import net.millida.launcher.core.hud.HudKind
import net.millida.launcher.runtime.GameInputSink

private const val REPEAT_DELAY_MS = 500L
private const val REPEAT_PERIOD_MS = 33L
internal const val DOUBLE_TAP_MS = 300L

/** The wheel walks the hotbar: at key speed it would fly past every slot. */
private const val WHEEL_PERIOD_MS = 150L

internal fun hudIcon(id: String?): MillidaIcon? = when (id) {
    HudIcons.PAUSE -> MIcons.pause
    HudIcons.CHAT -> MIcons.chat
    HudIcons.CAMERA -> MIcons.camera
    HudIcons.PLAYERS -> MIcons.users
    HudIcons.MORE -> MIcons.more
    HudIcons.JUMP -> MIcons.arrowUp
    HudIcons.SNEAK -> MIcons.arrowDown
    HudIcons.UP -> MIcons.chevronUp
    HudIcons.DOWN -> MIcons.chevronDown
    HudIcons.CLOSE -> MIcons.close
    else -> null
}

@Composable
internal fun HudActionButton(
    control: HudControl,
    lookMode: Boolean,
    opacity: Float,
    onAction: (HudAction) -> Unit,
) {
    var held by remember(control.id) { mutableStateOf(false) }
    val label = if (control.action == HudAction.POINTER_MODE) {
        if (lookMode) "Курсор" else "Обзор"
    } else {
        control.label
    }
    val icon = when (control.action) {
        HudAction.MENU -> MIcons.pause
        HudAction.KEYBOARD, HudAction.CHAT -> MIcons.chat
        HudAction.POINTER_MODE, HudAction.NONE -> null
    }
    HudFace(
        control = control,
        label = label,
        held = held,
        opacity = opacity,
        icon = icon,
        gesture = Modifier.pointerInput(control.id, control.action) {
            detectTapGestures(
                onPress = {
                    held = true
                    try {
                        tryAwaitRelease()
                    } finally {
                        held = false
                    }
                },
                onTap = { onAction(control.action) },
            )
        },
    )
}

/**
 * A key or a mouse button. Held by default; a toggle flips on every tap; a
 * latch is held while the finger is down and stays down after a double tap
 * until the next tap.
 */
@Composable
internal fun HudPressable(control: HudControl, opacity: Float, sink: GameInputSink) {
    var held by remember(control.id) { mutableStateOf(false) }
    var latched by remember(control.id) { mutableStateOf(false) }
    var lastPress by remember(control.id) { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val keys = control.keys
    val repeats = control.kind == HudKind.KEY && !control.toggle && !control.latch && keys.all { HudKeys.repeats(it) }

    fun send(down: Boolean) {
        when {
            control.kind == HudKind.MOUSE -> sink.mouseButton(control.code, down)
            down -> keys.forEach { sink.key(it, it, true) }
            else -> keys.asReversed().forEach { sink.key(it, it, false) }
        }
    }

    DisposableEffect(control.id, keys, control.kind) {
        onDispose {
            if (held) {
                held = false
                latched = false
                send(false)
            }
        }
    }

    HudFace(
        control = control,
        label = control.label,
        held = held,
        opacity = opacity,
        latched = latched,
        gesture = Modifier.pointerInput(control.id, control.toggle, control.latch, keys) {
            when {
                control.toggle -> detectTapGestures(
                    onTap = {
                        held = !held
                        send(held)
                    }
                )
                control.latch -> detectTapGestures(
                    onPress = {
                        if (latched) {
                            latched = false
                            held = false
                            send(false)
                            tryAwaitRelease()
                        } else {
                            val now = SystemClock.uptimeMillis()
                            val second = now - lastPress < DOUBLE_TAP_MS
                            lastPress = now
                            held = true
                            send(true)
                            var lifted = false
                            try {
                                lifted = tryAwaitRelease()
                            } finally {
                                if (second && lifted) {
                                    latched = true
                                } else {
                                    held = false
                                    send(false)
                                }
                            }
                        }
                    }
                )
                else -> detectTapGestures(
                    onPress = {
                        held = true
                        send(true)
                        val repeat: Job? = if (repeats) {
                            scope.launch {
                                delay(REPEAT_DELAY_MS)
                                while (isActive) {
                                    keys.forEach { sink.repeat(it, it) }
                                    delay(REPEAT_PERIOD_MS)
                                }
                            }
                        } else {
                            null
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            repeat?.cancel()
                            if (held) {
                                held = false
                                send(false)
                            }
                        }
                    }
                )
            }
        },
    )
}

/** Jump; a double tap also switches the launcher's flying controls, as the game does with flight. */
@Composable
internal fun HudJump(control: HudControl, opacity: Float, sink: GameInputSink, onFly: () -> Unit) {
    var held by remember(control.id) { mutableStateOf(false) }
    var lastPress by remember(control.id) { mutableLongStateOf(0L) }
    val fly by rememberUpdatedState(onFly)
    val keys = control.keys

    fun send(down: Boolean) {
        if (down) keys.forEach { sink.key(it, it, true) } else keys.asReversed().forEach { sink.key(it, it, false) }
    }

    DisposableEffect(control.id, keys) {
        onDispose {
            if (held) {
                held = false
                send(false)
            }
        }
    }

    HudFace(
        control = control,
        label = control.label,
        held = held,
        opacity = opacity,
        gesture = Modifier.pointerInput(control.id, keys) {
            detectTapGestures(
                onPress = {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPress < DOUBLE_TAP_MS) {
                        lastPress = 0L
                        fly()
                    } else {
                        lastPress = now
                    }
                    held = true
                    send(true)
                    try {
                        tryAwaitRelease()
                    } finally {
                        if (held) {
                            held = false
                            send(false)
                        }
                    }
                }
            )
        },
    )
}

@Composable
internal fun HudWheel(control: HudControl, opacity: Float, sink: GameInputSink) {
    var held by remember(control.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notches = if (control.code < 0) -1.0 else 1.0
    HudFace(
        control = control,
        label = control.label,
        held = held,
        opacity = opacity,
        gesture = Modifier.pointerInput(control.id, control.code) {
            detectTapGestures(
                onPress = {
                    held = true
                    sink.scroll(0.0, notches)
                    val repeat = scope.launch {
                        delay(REPEAT_DELAY_MS)
                        while (isActive) {
                            sink.scroll(0.0, notches)
                            delay(WHEEL_PERIOD_MS)
                        }
                    }
                    try {
                        tryAwaitRelease()
                    } finally {
                        repeat.cancel()
                        held = false
                    }
                }
            )
        },
    )
}

private class HudLook(
    val fill: Color,
    val ink: Color,
    val frame: PixelFrame,
    val dots: Boolean,
    val printed: Boolean,
)

private fun hudLook(kind: HudKind, held: Boolean, colors: MillidaColors): HudLook = when {
    held -> HudLook(colors.accent, colors.onAccent, PixelFrame.ButtonPressed, dots = true, printed = false)
    kind == HudKind.MOUSE -> HudLook(colors.accent.copy(alpha = 0.55f), MillidaPixel.white, PixelFrame.Button, dots = false, printed = true)
    kind == HudKind.ACTION -> HudLook(colors.raised.copy(alpha = 0.82f), colors.fg, colors.plateFrame(), dots = false, printed = false)
    else -> HudLook(colors.inset.copy(alpha = 0.72f), colors.fg, PixelFrame.Button, dots = false, printed = false)
}

internal fun hudCut(kind: HudKind): Dp = when (kind) {
    HudKind.ACTION -> MillidaCut.badge
    HudKind.STICK, HudKind.FLOAT_STICK -> MillidaCut.plate
    else -> MillidaCut.control
}

@Composable
internal fun HudFace(
    control: HudControl,
    label: String,
    held: Boolean,
    opacity: Float,
    gesture: Modifier,
    icon: MillidaIcon? = null,
    latched: Boolean = false,
) {
    val own = hudIcon(control.icon)
    Box(
        Modifier
            .fillMaxSize()
            .then(gesture),
    ) {
        if (own != null) {
            HudIconFace(control, own, held, opacity, latched)
        } else {
            HudLabel(control, label, icon, held, opacity, latched)
        }
    }
}

@Composable
private fun HudIconFace(control: HudControl, icon: MillidaIcon, held: Boolean, opacity: Float, latched: Boolean) {
    val look = hudLook(control.kind, held, millida)
    val progress = rememberPressProgress(held)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (held) 1f else opacity }
            .pressShift(progress)
            .pixelSurface(look.fill, hudCut(control.kind), look.frame, dots = look.dots || latched),
        contentAlignment = Alignment.Center,
    ) {
        MIcon(icon, size = (minOf(control.width, control.height) * 0.5f).dp, color = look.ink)
    }
}

@Composable
private fun HudLabel(
    control: HudControl,
    label: String,
    icon: MillidaIcon?,
    held: Boolean,
    opacity: Float,
    latched: Boolean,
) {
    val look = hudLook(control.kind, held, millida)
    val progress = rememberPressProgress(held)
    val shadowOffset = with(LocalDensity.current) { 2.dp.toPx() }
    Row(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (held) 1f else opacity }
            .pressShift(progress)
            .pixelSurface(look.fill, hudCut(control.kind), look.frame, dots = look.dots || latched)
            .padding(start = 6.dp, end = 6.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            MIcon(icon, size = 16.dp, color = look.ink)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = look.ink,
            style = MillidaType.button.copy(
                fontSize = (11 + 3 * control.scale.coerceAtMost(1.4f)).sp,
                shadow = if (look.printed) Shadow(MillidaPixel.textShadow, Offset(0f, shadowOffset), 0f) else null,
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}
