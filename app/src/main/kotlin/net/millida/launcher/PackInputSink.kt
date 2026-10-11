package net.millida.launcher

import net.millida.launcher.runtime.GameInputSink
import org.lwjgl.glfw.CallbackBridge
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Input for the ready-made window layer: events go into the queue of its native
 * library, and the game pulls them out itself on every frame.
 *
 * Every call leaves the caller's thread: the native side attaches the calling
 * thread to the game virtual machine and may run game code on it, so a garbage
 * collection pause there would freeze the screen thread. One thread keeps the
 * order of events as they were sent.
 */
object PackInputSink : GameInputSink {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "millida-input").apply { isDaemon = true }
    }

    /**
     * The native side registers these methods only once the game virtual machine
     * is up, so a touch that arrives earlier must not take the process down.
     */
    private fun post(name: String, onFailure: ((Throwable) -> Unit)? = null, block: () -> Unit) {
        worker.execute {
            runCatching(block).onFailure {
                android.util.Log.w("millida-input", "$name не прошло: ${it.message}")
                onFailure?.invoke(it)
            }
        }
    }

    override fun key(code: Int, scancode: Int, pressed: Boolean) {
        post("событие") {
            if (pressed) CallbackBridge.nativeSetUseInputStackQueue(true)
            CallbackBridge.sendKeyPress(code, scancode, CallbackBridge.getCurrentMods(), pressed)
        }
    }

    override fun repeat(code: Int, scancode: Int) {
        post("повтор") {
            CallbackBridge.sendKeyRepeat(code, scancode, CallbackBridge.getCurrentMods())
        }
    }

    /** The native side takes one UTF-16 unit, so a character outside it would arrive cut in half. */
    override fun char(codePoint: Int) {
        if (codePoint > Char.MAX_VALUE.code) return
        post("событие") {
            CallbackBridge.sendChar(codePoint.toChar(), CallbackBridge.getCurrentMods())
        }
    }

    override fun mouseButton(button: Int, pressed: Boolean) {
        post("кнопка") {
            if (pressed) CallbackBridge.nativeSetUseInputStackQueue(true)
            CallbackBridge.sendMouseButton(button, pressed)
        }
    }

    override fun cursorPosition(x: Float, y: Float) {
        post("событие") {
            CallbackBridge.sendCursorPos(x, y)
        }
    }

    /** The ready-made layer keeps the absolute position and adds the move to it. */
    override fun cursorDelta(x: Float, y: Float) {
        post("событие") { CallbackBridge.sendCursorDelta(x, y) }
    }

    override fun scroll(horizontal: Double, vertical: Double) {
        post("событие") {
            CallbackBridge.sendScroll(horizontal, vertical)
        }
    }

    override fun screenSize(width: Int, height: Int) {
        post("событие") {
            CallbackBridge.sendUpdateWindowSize(width, height)
            CallbackBridge.sendCursorPos(width / 2f, height / 2f)
        }
    }

    fun windowSize(width: Int, height: Int, onFailure: (Throwable) -> Unit) {
        post("размер окна", onFailure) { CallbackBridge.sendUpdateWindowSize(width, height) }
    }

    /**
     * The queue has to be turned on again once the game has started: without it
     * the window layer only keeps the current state of keys and buttons and
     * never calls the game back, so nothing but cursor movement gets through.
     */
    fun arm() {
        post("очередь событий") { CallbackBridge.nativeSetUseInputStackQueue(true) }
    }

    override fun focus(focused: Boolean) {
        post("событие") {
            CallbackBridge.nativeSetWindowAttrib(CallbackBridge.GLFW_FOCUSED, if (focused) 1 else 0)
            CallbackBridge.nativeSetWindowAttrib(CallbackBridge.GLFW_HOVERED, if (focused) 1 else 0)
        }
    }
}
