package net.millida.launcher.runtime

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

interface GameInputSink {
    fun key(code: Int, scancode: Int, pressed: Boolean)
    fun repeat(code: Int, scancode: Int)
    fun char(codePoint: Int)
    fun mouseButton(button: Int, pressed: Boolean)
    fun cursorPosition(x: Float, y: Float)
    fun cursorDelta(x: Float, y: Float)
    fun scroll(horizontal: Double, vertical: Double)
    fun screenSize(width: Int, height: Int)
    fun focus(focused: Boolean)
}

object OwnInputSink : GameInputSink {
    override fun key(code: Int, scancode: Int, pressed: Boolean) {
        val action = if (pressed) KeyMap.GLFW_PRESS else KeyMap.GLFW_RELEASE
        RuntimeBridge.pushEvent(RuntimeBridge.EVENT_KEY, code, action, scancode)
    }

    override fun repeat(code: Int, scancode: Int) {
        RuntimeBridge.pushEvent(RuntimeBridge.EVENT_KEY, code, KeyMap.GLFW_REPEAT, scancode)
    }

    override fun char(codePoint: Int) {
        RuntimeBridge.pushEvent(RuntimeBridge.EVENT_CHAR, codePoint)
    }

    override fun mouseButton(button: Int, pressed: Boolean) {
        RuntimeBridge.pushEvent(RuntimeBridge.EVENT_MOUSE_BUTTON, button, if (pressed) 1 else 0)
    }

    override fun cursorPosition(x: Float, y: Float) {
        RuntimeBridge.pushEvent(RuntimeBridge.EVENT_CURSOR, x.toInt(), y.toInt())
    }

    override fun cursorDelta(x: Float, y: Float) {
        RuntimeBridge.pushEvent(RuntimeBridge.EVENT_CURSOR_DELTA, x.toInt(), y.toInt())
    }

    override fun scroll(horizontal: Double, vertical: Double) {
        RuntimeBridge.pushEvent(RuntimeBridge.EVENT_SCROLL, horizontal.toInt(), vertical.toInt())
    }

    override fun screenSize(width: Int, height: Int) {
        RuntimeBridge.pushEvent(RuntimeBridge.EVENT_WINDOW_SIZE, width, height)
    }

    override fun focus(focused: Boolean) {
        RuntimeBridge.pushEvent(RuntimeBridge.EVENT_FOCUS, if (focused) 1 else 0)
    }
}

class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    var sink: GameInputSink = OwnInputSink
    var onSurfaceReady: ((Surface, Int, Int) -> Unit)? = null
    var onSurfaceGone: (() -> Unit)? = null
    var onSurfaceFailure: ((Throwable) -> Unit)? = null

    /** A physical keyboard or mouse was just used: the on-screen controls step aside for it. */
    var onDesktopInput: (() -> Unit)? = null

    private val heldKeys = HashSet<Int>()
    private val padKeys = HashSet<Int>()
    private val padButtons = HashSet<Int>()
    private val mouseButtons = HashSet<Int>()
    private var hatX = 0
    private var lookX = 0f
    private var lookY = 0f
    private var lookRestX = 0f
    private var lookRestY = 0f
    private var lookLast = 0L
    private var looking = false

    private val lookStep = object : Runnable {
        override fun run() {
            if (lookX == 0f && lookY == 0f) {
                looking = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val seconds = (now - lookLast).coerceIn(1L, MAX_LOOK_STEP_MS) / 1000f
            lookLast = now
            lookRestX += curve(lookX) * LOOK_SPEED * seconds
            lookRestY += curve(lookY) * LOOK_SPEED * seconds
            val moveX = lookRestX.toInt()
            val moveY = lookRestY.toInt()
            lookRestX -= moveX
            lookRestY -= moveY
            if (moveX != 0 || moveY != 0) sink.cursorDelta(moveX.toFloat(), moveY.toFloat())
            postOnAnimation(this)
        }
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        try {
            val external = onSurfaceReady
            if (external != null) {
                external(holder.surface, width, height)
            } else {
                RuntimeBridge.attachSurface(holder.surface, width, height)
                sink.screenSize(width, height)
                sink.focus(true)
            }
        } catch (error: Throwable) {
            Log.e("millida-runtime", "поверхность не отдана игре", error)
            onSurfaceFailure?.invoke(error)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        try {
            val external = onSurfaceGone
            if (external != null) {
                external()
            } else {
                sink.focus(false)
                RuntimeBridge.detachSurface()
            }
        } catch (error: Throwable) {
            Log.e("millida-runtime", "поверхность не отпущена", error)
        }
    }

    fun takeFocus() {
        if (!hasFocus()) requestFocus()
    }

    fun capturePointer() {
        if (hasPointerCapture()) return
        takeFocus()
        requestPointerCapture()
    }

    fun releaseCapture() {
        if (hasPointerCapture()) releasePointerCapture()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(event, down = true) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(event, down = false) || super.onKeyUp(keyCode, event)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val text = event.characters ?: return super.onKeyMultiple(keyCode, repeatCount, event)
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            sink.char(codePoint)
            index += Character.charCount(codePoint)
        }
        return true
    }

    private fun handleKey(event: KeyEvent, down: Boolean): Boolean {
        val pad = KeyMap.gamepad(event.keyCode)
        if (pad != null) {
            if (!down || event.repeatCount == 0) padAction(pad, down)
            return true
        }
        val code = KeyMap.glfw(event.keyCode)
        if (code == KeyMap.UNKNOWN) return false
        if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD)) onDesktopInput?.invoke()
        if (!down) {
            if (heldKeys.remove(code)) sink.key(code, code, false)
            return true
        }
        if (event.repeatCount > 0 && code in heldKeys) {
            sink.repeat(code, code)
        } else {
            heldKeys += code
            sink.key(code, code, true)
        }
        typed(event)
        return true
    }

    /**
     * The game takes text from its own character channel, separately from keys,
     * exactly as on a desktop. Shortcuts with Ctrl type nothing; AltGr does, so
     * Alt is not filtered.
     */
    private fun typed(event: KeyEvent) {
        if ((event.metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON)) != 0) return
        val unicode = event.getUnicodeChar(event.metaState)
        if ((unicode and KeyCharacterMap.COMBINING_ACCENT) != 0) return
        if (unicode < 32 || unicode == 127) return
        sink.char(unicode)
    }

    private fun padAction(action: PadAction, down: Boolean) {
        when (action) {
            is PadAction.Key -> padKey(action.code, down)
            is PadAction.Mouse -> padButton(action.button, down)
            is PadAction.Wheel -> if (down) sink.scroll(0.0, action.notches)
        }
    }

    private fun padKey(code: Int, down: Boolean) {
        if (down) {
            if (padKeys.add(code)) sink.key(code, code, true)
        } else if (padKeys.remove(code)) {
            sink.key(code, code, false)
        }
    }

    private fun padButton(button: Int, down: Boolean) {
        if (down) {
            if (padButtons.add(button)) sink.mouseButton(button, true)
        } else if (padButtons.remove(button)) {
            sink.mouseButton(button, false)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.actionMasked == MotionEvent.ACTION_MOVE) {
            joystick(event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun joystick(event: MotionEvent) {
        val moveX = axis(event, MotionEvent.AXIS_X)
        val moveY = axis(event, MotionEvent.AXIS_Y)
        padKey(GlfwKeys.W, moveY < -STICK_ON)
        padKey(GlfwKeys.S, moveY > STICK_ON)
        padKey(GlfwKeys.A, moveX < -STICK_ON)
        padKey(GlfwKeys.D, moveX > STICK_ON)

        look(axis(event, MotionEvent.AXIS_Z), axis(event, MotionEvent.AXIS_RZ))

        trigger(BUTTON_LEFT, max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)))
        trigger(BUTTON_RIGHT, max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)))

        val hat = event.getAxisValue(MotionEvent.AXIS_HAT_X).roundToInt()
        if (hat != hatX) {
            hatX = hat
            if (hat != 0) sink.scroll(0.0, if (hat < 0) 1.0 else -1.0)
        }
    }

    private fun axis(event: MotionEvent, axis: Int): Float {
        val flat = event.device?.getMotionRange(axis, event.source)?.flat ?: 0f
        val value = event.getAxisValue(axis)
        return if (abs(value) < max(flat, DEAD_ZONE)) 0f else value
    }

    /** A trigger presses past the middle and lets go well before it, so a shaky finger does not click. */
    private fun trigger(button: Int, value: Float) {
        if (value > TRIGGER_ON) padButton(button, true) else if (value < TRIGGER_OFF) padButton(button, false)
    }

    private fun look(x: Float, y: Float) {
        lookX = x
        lookY = y
        if (!looking && (x != 0f || y != 0f)) {
            looking = true
            lookLast = SystemClock.uptimeMillis()
            postOnAnimation(lookStep)
        }
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) return false
        onDesktopInput?.invoke()
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                var moveX = event.x
                var moveY = event.y
                for (index in 0 until event.historySize) {
                    moveX += event.getHistoricalX(index)
                    moveY += event.getHistoricalY(index)
                }
                if (moveX != 0f || moveY != 0f) sink.cursorDelta(moveX, moveY)
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                val button = KeyMap.mouseButton(event.actionButton)
                if (button != null && mouseButtons.add(button)) sink.mouseButton(button, true)
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = KeyMap.mouseButton(event.actionButton)
                if (button != null && mouseButtons.remove(button)) sink.mouseButton(button, false)
            }
            MotionEvent.ACTION_SCROLL -> {
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                // GLFW counts a horizontal wheel to the left as positive, Android to the right.
                if (vertical != 0f || horizontal != 0f) sink.scroll(-horizontal.toDouble(), vertical.toDouble())
            }
            else -> return false
        }
        return true
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        if (!hasCapture) releaseMouse()
    }

    /**
     * A key or button whose release goes to another window would stay held in
     * the game for good: the character keeps walking, the pickaxe keeps swinging.
     */
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) releaseAll()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) releaseAll()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    private fun releaseMouse() {
        mouseButtons.toList().forEach { sink.mouseButton(it, false) }
        mouseButtons.clear()
    }

    private fun releaseAll() {
        heldKeys.toList().forEach { sink.key(it, it, false) }
        heldKeys.clear()
        padKeys.toList().forEach { sink.key(it, it, false) }
        padKeys.clear()
        padButtons.toList().forEach { sink.mouseButton(it, false) }
        padButtons.clear()
        releaseMouse()
        lookX = 0f
        lookY = 0f
        looking = false
        removeCallbacks(lookStep)
    }

    companion object {
        const val BUTTON_LEFT = 0
        const val BUTTON_RIGHT = 1
        const val BUTTON_MIDDLE = 2

        private const val DEAD_ZONE = 0.15f
        private const val STICK_ON = 0.5f
        private const val TRIGGER_ON = 0.55f
        private const val TRIGGER_OFF = 0.35f

        private const val LOOK_SPEED = 900f
        private const val MAX_LOOK_STEP_MS = 50L

        private fun curve(value: Float): Float = value * abs(value)
    }
}

object GlfwKeys {
    const val SPACE = 32
    const val A = 65
    const val D = 68
    const val E = 69
    const val Q = 81
    const val S = 83
    const val T = 84
    const val W = 87
    const val ESCAPE = 256
    const val ENTER = 257
    const val TAB = 258
    const val BACKSPACE = 259
    const val F5 = 294
    const val LEFT_SHIFT = 340
    const val LEFT_CONTROL = 341
}
