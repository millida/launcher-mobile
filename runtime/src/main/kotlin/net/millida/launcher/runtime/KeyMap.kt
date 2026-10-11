package net.millida.launcher.runtime

import android.view.KeyEvent
import net.millida.launcher.core.input.GlfwCodes
import net.millida.launcher.core.input.KeyCodes

sealed interface PadAction {
    data class Key(val code: Int) : PadAction
    data class Mouse(val button: Int) : PadAction
    data class Wheel(val notches: Double) : PadAction
}

object KeyMap {
    const val UNKNOWN = GlfwCodes.UNKNOWN

    const val GLFW_RELEASE = 0
    const val GLFW_PRESS = 1
    const val GLFW_REPEAT = 2

    fun glfw(keyCode: Int): Int = KeyCodes.glfw(keyCode)

    fun gamepad(keyCode: Int): PadAction? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> PadAction.Key(GlfwKeys.SPACE)
        KeyEvent.KEYCODE_BUTTON_B -> PadAction.Key(GlfwKeys.LEFT_SHIFT)
        KeyEvent.KEYCODE_BUTTON_X -> PadAction.Key(GlfwKeys.Q)
        KeyEvent.KEYCODE_BUTTON_Y -> PadAction.Key(GlfwKeys.E)
        KeyEvent.KEYCODE_BUTTON_START -> PadAction.Key(GlfwKeys.ESCAPE)
        KeyEvent.KEYCODE_BUTTON_SELECT -> PadAction.Key(GlfwKeys.TAB)
        KeyEvent.KEYCODE_BUTTON_THUMBL -> PadAction.Key(GlfwKeys.LEFT_CONTROL)
        KeyEvent.KEYCODE_BUTTON_THUMBR -> PadAction.Key(GlfwKeys.F5)
        KeyEvent.KEYCODE_BUTTON_L1 -> PadAction.Wheel(1.0)
        KeyEvent.KEYCODE_BUTTON_R1 -> PadAction.Wheel(-1.0)
        KeyEvent.KEYCODE_BUTTON_L2 -> PadAction.Mouse(GameSurfaceView.BUTTON_RIGHT)
        KeyEvent.KEYCODE_BUTTON_R2 -> PadAction.Mouse(GameSurfaceView.BUTTON_LEFT)
        else -> null
    }

    fun mouseButton(androidButton: Int): Int? = KeyCodes.mouseButton(androidButton)
}
