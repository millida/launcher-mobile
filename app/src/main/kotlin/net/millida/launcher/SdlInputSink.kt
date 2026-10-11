package net.millida.launcher

import net.millida.launcher.core.input.KeyCodes
import net.millida.launcher.runtime.GameInputSink
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLInputConnection

/**
 * The same input layer as the GLFW path, delivered to SDL3: the HUD, the
 * keyboard and the gamepad keep speaking GLFW codes, and only here they are
 * turned into what SDL's Android backend takes — Android key codes and the
 * whole mouse button state as Android bits.
 */
object SdlInputSink : GameInputSink {
    private const val ACTION_DOWN = 0
    private const val ACTION_UP = 1
    private const val ACTION_MOVE = 2
    private const val ACTION_HOVER_MOVE = 7
    private const val ACTION_SCROLL = 8

    @Volatile
    private var buttons = 0

    @Volatile
    private var cursorX = 0f

    @Volatile
    private var cursorY = 0f

    private inline fun guard(name: String, block: () -> Unit) {
        if (!SdlHost.loaded) return
        runCatching(block).onFailure { android.util.Log.w("millida-input", "SDL: $name не прошло: ${it.message}") }
    }

    override fun key(code: Int, scancode: Int, pressed: Boolean) {
        val android = KeyCodes.android(code) ?: return
        guard("клавиша") { if (pressed) SDLActivity.onNativeKeyDown(android) else SDLActivity.onNativeKeyUp(android) }
    }

    /** SDL marks a second press of a held key as a repeat itself. */
    override fun repeat(code: Int, scancode: Int) {
        val android = KeyCodes.android(code) ?: return
        guard("повтор") { SDLActivity.onNativeKeyDown(android) }
    }

    override fun char(codePoint: Int) {
        if (!Character.isValidCodePoint(codePoint)) return
        guard("символ") { SDLInputConnection.nativeCommitText(String(Character.toChars(codePoint)), 1) }
    }

    /**
     * While the game holds the cursor a click must not move it: the button
     * goes with a zero relative move, otherwise SDL would count the jump to
     * the remembered position as a turn of the camera.
     */
    override fun mouseButton(button: Int, pressed: Boolean) {
        val bit = KeyCodes.androidButtonBit(button) ?: return
        val state = if (pressed) buttons or bit else buttons and bit.inv()
        if (state == buttons) return
        buttons = state
        val action = if (pressed) ACTION_DOWN else ACTION_UP
        guard("кнопка") {
            if (SdlHost.relative) {
                SDLActivity.onNativeMouse(state, action, 0f, 0f, true)
            } else {
                SDLActivity.onNativeMouse(state, action, cursorX, cursorY, false)
            }
        }
    }

    override fun cursorPosition(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        guard("курсор") { SDLActivity.onNativeMouse(buttons, if (buttons == 0) ACTION_HOVER_MOVE else ACTION_MOVE, x, y, false) }
    }

    override fun cursorDelta(x: Float, y: Float) {
        guard("движение") { SDLActivity.onNativeMouse(buttons, ACTION_MOVE, x, y, true) }
    }

    /** GLFW counts a wheel to the left as positive, SDL to the right; up is positive in both. */
    override fun scroll(horizontal: Double, vertical: Double) {
        guard("колесо") { SDLActivity.onNativeMouse(0, ACTION_SCROLL, -horizontal.toFloat(), vertical.toFloat(), false) }
    }

    /** The size reaches SDL with the surface itself, see [SdlHost.surfaceChanged]. */
    override fun screenSize(width: Int, height: Int) = Unit

    override fun focus(focused: Boolean) {
        guard("фокус") { SDLActivity.nativeFocusChanged(focused) }
    }
}
