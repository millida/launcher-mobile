package net.millida.launcher.runtime

import net.millida.launcher.core.input.GamepadMap
import net.millida.launcher.core.input.GlfwCodes
import net.millida.launcher.core.input.PadBindingType

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

    /** The player's own layout from the settings; buttons it leaves alone keep the defaults. */
    fun gamepad(keyCode: Int, map: GamepadMap = GamepadMap()): PadAction? {
        val binding = map.binding(keyCode) ?: return null
        return when (binding.type) {
            PadBindingType.KEY -> PadAction.Key(binding.code)
            PadBindingType.MOUSE -> PadAction.Mouse(binding.code)
            PadBindingType.WHEEL -> PadAction.Wheel(binding.code.toDouble())
        }
    }

    fun mouseButton(androidButton: Int): Int? = KeyCodes.mouseButton(androidButton)
}
