package net.millida.launcher.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.millida.launcher.core.hud.Hotbar
import net.millida.launcher.core.hud.HudKeys
import net.millida.launcher.core.input.AndroidKeys
import net.millida.launcher.core.input.CursorMapping
import net.millida.launcher.core.input.KeyCodes
import net.millida.launcher.runtime.GameInputSink
import net.millida.launcher.runtime.GameSurfaceView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

private const val CLICK_MS = 70L
private const val TAP_MS = 200L
private const val MENU_LONG_PRESS_MS = 400L
private const val TOUCH_SLOP_DP = 9f
private const val SCROLL_STEP_DP = 28f
private const val MENU_FRAME_GAP_MS = 40L
private const val CURSOR_JUMP_PX = 6f
private const val DROP_PRESS_MS = 450L
private const val DROP_REPEAT_FROM_MS = 1_000L
private const val DROP_REPEAT_MS = 250L
private const val RING_TICKS = 16
private const val RING_RADIUS_DP = 30f
private const val RING_TICK_DP = 4f

/**
 * Pointer events of the game's own menus in one ordered line. A menu works out
 * what is under the cursor once per frame, so a press that lands in the same
 * frame as a long cursor jump hits whatever was hovered before; such a press
 * waits a couple of frames. Small moves are not delayed, and a release can
 * never overtake its press because a single consumer runs the line.
 */
internal class MenuPointer(private val sink: GameInputSink) {
    private val steps = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val pressed = HashSet<Int>()
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var jumpedAt = 0L

    suspend fun run() {
        for (step in steps) step()
    }

    fun move(x: Float, y: Float) {
        steps.trySend {
            val jumped = lastX.isNaN() || abs(x - lastX) + abs(y - lastY) > CURSOR_JUMP_PX
            sink.cursorPosition(x, y)
            lastX = x
            lastY = y
            if (jumped) jumpedAt = SystemClock.uptimeMillis()
        }
    }

    fun press(button: Int) {
        steps.trySend {
            val wait = MENU_FRAME_GAP_MS - (SystemClock.uptimeMillis() - jumpedAt)
            if (wait > 0) delay(wait)
            pressed += button
            sink.mouseButton(button, true)
        }
    }

    fun release(button: Int) {
        steps.trySend {
            if (pressed.remove(button)) sink.mouseButton(button, false)
        }
    }

    fun click(button: Int) {
        press(button)
        steps.trySend { delay(CLICK_MS) }
        release(button)
    }

    fun scroll(notches: Double) {
        steps.trySend { sink.scroll(0.0, notches) }
    }

    /** The line dies with the screen, and a button it left down would stay held in the game. */
    fun releaseAll() {
        pressed.forEach { sink.mouseButton(it, false) }
        pressed.clear()
    }
}

private enum class TouchRole { LOOK, HOTBAR, MENU, DEAD }

private class Touch(val id: PointerId, val start: Offset, val startedAt: Long, var role: TouchRole) {
    var moved = false
    var holding = false
    var dragging = false
    var longDone = false
    var scrolling = false
    var dropping = false
    var slot: Int? = null
    var hotbarTravel = 0f
    var timer: Job? = null
}

internal data class HoldRing(val centre: Offset, val from: Float, val durationMs: Int, val held: Boolean)

private fun buttonMask(buttons: PointerButtons): Int {
    var mask = 0
    if (buttons.isPrimaryPressed) mask = mask or AndroidKeys.BUTTON_PRIMARY
    if (buttons.isSecondaryPressed) mask = mask or AndroidKeys.BUTTON_SECONDARY
    if (buttons.isTertiaryPressed) mask = mask or AndroidKeys.BUTTON_TERTIARY
    if (buttons.isBackPressed) mask = mask or AndroidKeys.BUTTON_BACK
    if (buttons.isForwardPressed) mask = mask or AndroidKeys.BUTTON_FORWARD
    return mask
}

private val MOUSE_BITS = listOf(
    AndroidKeys.BUTTON_PRIMARY,
    AndroidKeys.BUTTON_SECONDARY,
    AndroidKeys.BUTTON_TERTIARY,
    AndroidKeys.BUTTON_BACK,
    AndroidKeys.BUTTON_FORWARD,
).mapNotNull { bit -> KeyCodes.mouseButton(bit)?.let { bit to it } }

/**
 * The screen behind the buttons. Every finger is followed on its own, so one
 * thumb can look around while the other holds a block or walks. The gesture is
 * read from where the finger started: a finger that began in a game menu never
 * turns into a click in the world once the menu closes under it.
 */
@Composable
internal fun GameTouchArea(
    sink: GameInputSink,
    lookMode: Boolean,
    sensitivity: Float,
    lookTap: Int,
    holdDelay: Int,
    guiScale: Int,
    forceUnicode: Boolean,
    haptics: Boolean,
    onCursor: (Offset) -> Unit,
    onFirstTouch: () -> Unit,
    onMouseLook: () -> Unit,
    onMouse: () -> Unit,
    onFinger: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current
    val slop = with(density) { TOUCH_SLOP_DP.dp.toPx() }
    val scrollStep = with(density) { SCROLL_STEP_DP.dp.toPx() }
    val look by rememberUpdatedState(lookMode)
    val lookSensitivity by rememberUpdatedState(sensitivity)
    val tapButton by rememberUpdatedState(lookTap)
    val holdMs by rememberUpdatedState(holdDelay.toLong())
    val wantedScale by rememberUpdatedState(guiScale)
    val unicode by rememberUpdatedState(forceUnicode)
    val buzzOn by rememberUpdatedState(haptics)
    val cursorTo by rememberUpdatedState(onCursor)
    val firstTouch by rememberUpdatedState(onFirstTouch)
    val mouseLook by rememberUpdatedState(onMouseLook)
    val mouseUsed by rememberUpdatedState(onMouse)
    val fingerUsed by rememberUpdatedState(onFinger)
    val menu = remember(sink) { MenuPointer(sink) }
    LaunchedEffect(menu) { menu.run() }
    val rings = remember { mutableStateMapOf<PointerId, HoldRing>() }

    fun buzz(kind: Int) {
        if (buzzOn) view.performHapticFeedback(kind)
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(sink, menu) {
                val touches = HashMap<PointerId, Touch>()
                var mouseDown = 0
                var leftHolders = 0
                var scrollRest = 0f
                var selectedSlot = 0

                fun holdLeft(down: Boolean) {
                    if (down) {
                        if (leftHolders++ == 0) sink.mouseButton(GameSurfaceView.BUTTON_LEFT, true)
                    } else if (leftHolders > 0) {
                        leftHolders--
                        if (leftHolders == 0) sink.mouseButton(GameSurfaceView.BUTTON_LEFT, false)
                    }
                }

                fun tapKey(code: Int) {
                    sink.key(code, code, true)
                    sink.key(code, code, false)
                }

                fun hotbarScale(): Int = Hotbar.scale(size.width, size.height, wantedScale, unicode)

                fun stopMenu(touch: Touch) {
                    touch.timer?.cancel()
                    if (touch.dragging) {
                        touch.dragging = false
                        menu.release(GameSurfaceView.BUTTON_LEFT)
                    }
                }

                // The game took or gave back the cursor while this finger was down.
                fun drop(touch: Touch) {
                    touch.timer?.cancel()
                    rings.remove(touch.id)
                    when (touch.role) {
                        TouchRole.MENU -> stopMenu(touch)
                        TouchRole.LOOK -> if (touch.holding) {
                            touch.holding = false
                            holdLeft(false)
                        }
                        TouchRole.HOTBAR, TouchRole.DEAD -> Unit
                    }
                    touch.role = TouchRole.DEAD
                }

                fun beginLook(touch: Touch) {
                    val waitForRing = minOf(TAP_MS, holdMs)
                    touch.timer = scope.launch {
                        delay(waitForRing)
                        if (touch.moved || touch.role != TouchRole.LOOK) return@launch
                        val rest = holdMs - waitForRing
                        rings[touch.id] = HoldRing(touch.start, waitForRing.toFloat() / holdMs, rest.toInt(), held = false)
                        delay(rest)
                        if (touch.moved || touch.role != TouchRole.LOOK) return@launch
                        touch.holding = true
                        holdLeft(true)
                        rings[touch.id] = HoldRing(touch.start, 1f, 0, held = true)
                        buzz(HapticFeedbackConstants.LONG_PRESS)
                    }
                }

                fun beginHotbar(touch: Touch, scale: Int) {
                    touch.slot = Hotbar.slotAt(touch.start.x, touch.start.y, size.width, size.height, scale)
                    touch.timer = scope.launch {
                        delay(DROP_PRESS_MS)
                        if (touch.moved || touch.role != TouchRole.HOTBAR || touch.slot != selectedSlot) return@launch
                        touch.dropping = true
                        tapKey(HudKeys.Q)
                        buzz(HapticFeedbackConstants.LONG_PRESS)
                        delay(DROP_REPEAT_FROM_MS - DROP_PRESS_MS)
                        while (isActive) {
                            tapKey(HudKeys.Q)
                            delay(DROP_REPEAT_MS)
                        }
                    }
                }

                fun beginMenu(touch: Touch) {
                    val others = touches.values.filter { it !== touch && it.role == TouchRole.MENU }
                    if (others.isNotEmpty()) {
                        others.forEach {
                            stopMenu(it)
                            it.scrolling = true
                        }
                        touch.scrolling = true
                        scrollRest = 0f
                        return
                    }
                    cursorTo(touch.start)
                    menu.move(touch.start.x, touch.start.y)
                    touch.timer = scope.launch {
                        delay(MENU_LONG_PRESS_MS)
                        if (touch.moved || touch.scrolling || touch.role != TouchRole.MENU) return@launch
                        touch.longDone = true
                        menu.click(GameSurfaceView.BUTTON_RIGHT)
                        buzz(HapticFeedbackConstants.LONG_PRESS)
                    }
                }

                fun begin(change: PointerInputChange) {
                    firstTouch()
                    fingerUsed()
                    val position = change.position
                    val scale = hotbarScale()
                    val role = when {
                        !look -> TouchRole.MENU
                        Hotbar.contains(position.x, position.y, size.width, size.height, scale) -> TouchRole.HOTBAR
                        else -> TouchRole.LOOK
                    }
                    val touch = Touch(change.id, position, change.uptimeMillis, role)
                    touches[change.id] = touch
                    when (role) {
                        TouchRole.LOOK -> beginLook(touch)
                        TouchRole.HOTBAR -> beginHotbar(touch, scale)
                        TouchRole.MENU -> beginMenu(touch)
                        TouchRole.DEAD -> Unit
                    }
                }

                fun move(touch: Touch, change: PointerInputChange) {
                    val delta = change.position - change.previousPosition
                    val beyond = !touch.moved && (change.position - touch.start).getDistance() > slop
                    when (touch.role) {
                        TouchRole.LOOK -> {
                            if (delta != Offset.Zero) {
                                sink.cursorDelta(delta.x * lookSensitivity, delta.y * lookSensitivity)
                            }
                            if (beyond) {
                                touch.moved = true
                                if (!touch.holding) {
                                    touch.timer?.cancel()
                                    rings.remove(touch.id)
                                }
                            }
                            if (touch.holding) rings[touch.id]?.let { rings[touch.id] = it.copy(centre = change.position) }
                        }
                        TouchRole.HOTBAR -> {
                            if (beyond) {
                                touch.moved = true
                                touch.timer?.cancel()
                            }
                            touch.hotbarTravel += delta.x
                            val step = Hotbar.slotPixels(hotbarScale())
                            while (touch.hotbarTravel >= step) {
                                sink.scroll(0.0, -1.0)
                                selectedSlot = (selectedSlot + 1) % Hotbar.SLOTS
                                touch.hotbarTravel -= step
                            }
                            while (touch.hotbarTravel <= -step) {
                                sink.scroll(0.0, 1.0)
                                selectedSlot = (selectedSlot + Hotbar.SLOTS - 1) % Hotbar.SLOTS
                                touch.hotbarTravel += step
                            }
                        }
                        TouchRole.MENU -> {
                            if (touch.scrolling) {
                                val fingers = touches.values.count { it.role == TouchRole.MENU && it.scrolling }.coerceAtLeast(1)
                                scrollRest += delta.y / fingers
                                while (scrollRest >= scrollStep) {
                                    menu.scroll(1.0)
                                    scrollRest -= scrollStep
                                }
                                while (scrollRest <= -scrollStep) {
                                    menu.scroll(-1.0)
                                    scrollRest += scrollStep
                                }
                                return
                            }
                            if (beyond) {
                                touch.moved = true
                                touch.timer?.cancel()
                                if (!touch.longDone) {
                                    menu.move(touch.start.x, touch.start.y)
                                    menu.press(GameSurfaceView.BUTTON_LEFT)
                                    touch.dragging = true
                                }
                            }
                            if (delta != Offset.Zero) {
                                cursorTo(change.position)
                                menu.move(change.position.x, change.position.y)
                            }
                        }
                        TouchRole.DEAD -> Unit
                    }
                }

                fun end(touch: Touch, change: PointerInputChange) {
                    touch.timer?.cancel()
                    rings.remove(touch.id)
                    val position = change.position
                    when (touch.role) {
                        TouchRole.LOOK -> when {
                            touch.holding -> {
                                touch.holding = false
                                holdLeft(false)
                            }
                            !touch.moved && change.uptimeMillis - touch.startedAt < TAP_MS -> {
                                val button = tapButton
                                if (button != GameSurfaceView.BUTTON_LEFT || leftHolders == 0) {
                                    scope.launch {
                                        sink.mouseButton(button, true)
                                        delay(CLICK_MS)
                                        sink.mouseButton(button, false)
                                    }
                                }
                            }
                        }
                        TouchRole.HOTBAR -> if (!touch.moved && !touch.dropping) {
                            touch.slot?.let { slot ->
                                tapKey(HudKeys.slot(slot))
                                selectedSlot = slot
                                buzz(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        }
                        TouchRole.MENU -> {
                            when {
                                touch.scrolling -> Unit
                                touch.dragging -> {
                                    touch.dragging = false
                                    menu.release(GameSurfaceView.BUTTON_LEFT)
                                }
                                !touch.longDone -> {
                                    menu.move(position.x, position.y)
                                    menu.click(GameSurfaceView.BUTTON_LEFT)
                                }
                            }
                            cursorTo(position)
                        }
                        TouchRole.DEAD -> Unit
                    }
                }

                awaitPointerEventScope {
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val mouse = event.changes.firstOrNull { it.type == PointerType.Mouse }
                            if (mouse != null) {
                                mouseUsed()
                                if (event.type == PointerEventType.Scroll) {
                                    val vertical = mouse.scrollDelta.y
                                    val horizontal = mouse.scrollDelta.x
                                    // Compose reports down and right as positive; GLFW wants up and left.
                                    if (vertical != 0f || horizontal != 0f) sink.scroll((-horizontal).toDouble(), (-vertical).toDouble())
                                } else if (look) {
                                    if (event.type == PointerEventType.Press) mouseLook()
                                } else {
                                    val position = mouse.position
                                    val now = buttonMask(event.buttons)
                                    // The game moves its own cursor (it centres it on leaving the camera), so a
                                    // click that did not repeat the pointer position would land where the game
                                    // last put it rather than under the system arrow.
                                    val clicked = now != mouseDown
                                    if (clicked || position != mouse.previousPosition || event.type == PointerEventType.Enter) {
                                        val (x, y) = CursorMapping.toWindow(
                                            position.x, position.y, size.width, size.height, size.width, size.height,
                                        )
                                        cursorTo(Offset(x, y))
                                        sink.cursorPosition(x, y)
                                    }
                                    MOUSE_BITS.forEach { (bit, button) ->
                                        if ((now and bit) != (mouseDown and bit)) sink.mouseButton(button, (now and bit) != 0)
                                    }
                                    mouseDown = now
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            touches.values.forEach { touch ->
                                val stale = when (touch.role) {
                                    TouchRole.MENU -> look
                                    TouchRole.LOOK, TouchRole.HOTBAR -> !look
                                    TouchRole.DEAD -> false
                                }
                                if (stale) drop(touch)
                            }

                            for (change in event.changes) {
                                val touch = touches[change.id]
                                if (touch == null) {
                                    if (change.pressed && !change.previousPressed) {
                                        begin(change)
                                        change.consume()
                                    }
                                    continue
                                }
                                if (change.pressed) {
                                    move(touch, change)
                                } else {
                                    end(touch, change)
                                    touches.remove(change.id)
                                }
                                change.consume()
                            }
                        }
                    } finally {
                        touches.values.forEach { it.timer?.cancel() }
                        touches.clear()
                        if (leftHolders > 0) sink.mouseButton(GameSurfaceView.BUTTON_LEFT, false)
                        leftHolders = 0
                        menu.releaseAll()
                        rings.clear()
                        MOUSE_BITS.forEach { (bit, button) ->
                            if ((mouseDown and bit) != 0) sink.mouseButton(button, false)
                        }
                    }
                }
            },
    ) {
        rings.forEach { (id, ring) ->
            key(id) { HoldRingMark(ring) }
        }
    }
}

/** Squares around the finger fill up while holding turns into breaking, so the wait is never a guess. */
@Composable
private fun HoldRingMark(ring: HoldRing) {
    val colors = millida
    val progress = remember { Animatable(ring.from) }
    LaunchedEffect(ring.held) {
        if (ring.held) {
            progress.snapTo(1f)
        } else {
            progress.animateTo(1f, tween(ring.durationMs, easing = LinearEasing))
        }
    }
    val radius = with(LocalDensity.current) { RING_RADIUS_DP.dp.toPx() }
    val tick = with(LocalDensity.current) { RING_TICK_DP.dp.toPx() }
    val lit = colors.accent
    val dim = colors.fg.copy(alpha = 0.35f)
    Canvas(Modifier.fillMaxSize()) {
        val filled = (progress.value * RING_TICKS).toInt()
        for (index in 0 until RING_TICKS) {
            val angle = -PI / 2 + 2 * PI * index / RING_TICKS
            val x = ring.centre.x + (cos(angle) * radius).toFloat()
            val y = ring.centre.y + (sin(angle) * radius).toFloat()
            drawRect(
                color = if (index < filled) lit else dim,
                topLeft = Offset(round(x - tick / 2f), round(y - tick / 2f)),
                size = Size(tick, tick),
            )
        }
    }
}
