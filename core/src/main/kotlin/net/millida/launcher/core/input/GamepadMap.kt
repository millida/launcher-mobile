package net.millida.launcher.core.input

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.millida.launcher.core.hud.HudKeys
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.mojang.MojangJson

@Serializable
enum class PadBindingType { KEY, MOUSE, WHEEL }

@Serializable
data class PadBinding(val type: PadBindingType, val code: Int)

/** A gamepad button by the Android key code it arrives with, and the name the player sees. */
data class PadButton(val keyCode: Int, val label: String)

/**
 * What each gamepad button does in the game. Only the player's changes are
 * stored; a button without one keeps the layout every launcher version had,
 * so an update that adds a button gives it a sane default.
 */
@Serializable
data class GamepadMap(val bindings: Map<Int, PadBinding> = emptyMap()) {

    fun binding(keyCode: Int): PadBinding? = bindings[keyCode] ?: DEFAULTS[keyCode]

    fun with(keyCode: Int, binding: PadBinding): GamepadMap =
        if (keyCode in BUTTON_CODES && valid(binding)) copy(bindings = bindings + (keyCode to binding)) else this

    val customized: Boolean
        get() = bindings.isNotEmpty()

    fun encode(): String = MojangJson.encodeToString(this)

    companion object {
        const val BUTTON_A = 96
        const val BUTTON_B = 97
        const val BUTTON_X = 99
        const val BUTTON_Y = 100
        const val BUTTON_L1 = 102
        const val BUTTON_R1 = 103
        const val BUTTON_L2 = 104
        const val BUTTON_R2 = 105
        const val BUTTON_THUMBL = 106
        const val BUTTON_THUMBR = 107
        const val BUTTON_START = 108
        const val BUTTON_SELECT = 109

        val buttons: List<PadButton>
            get() = listOf(
                PadButton(BUTTON_A, "A"),
                PadButton(BUTTON_B, "B"),
                PadButton(BUTTON_X, "X"),
                PadButton(BUTTON_Y, "Y"),
                PadButton(BUTTON_L1, "L1"),
                PadButton(BUTTON_R1, "R1"),
                PadButton(BUTTON_L2, "L2"),
                PadButton(BUTTON_R2, "R2"),
                PadButton(BUTTON_THUMBL, tr("Левый стик")),
                PadButton(BUTTON_THUMBR, tr("Правый стик")),
                PadButton(BUTTON_START, "Start"),
                PadButton(BUTTON_SELECT, "Select"),
            )

        private val BUTTON_CODES = setOf(
            BUTTON_A, BUTTON_B, BUTTON_X, BUTTON_Y, BUTTON_L1, BUTTON_R1,
            BUTTON_L2, BUTTON_R2, BUTTON_THUMBL, BUTTON_THUMBR, BUTTON_START, BUTTON_SELECT,
        )

        val DEFAULTS: Map<Int, PadBinding> = mapOf(
            BUTTON_A to PadBinding(PadBindingType.KEY, HudKeys.SPACE),
            BUTTON_B to PadBinding(PadBindingType.KEY, HudKeys.LEFT_SHIFT),
            BUTTON_X to PadBinding(PadBindingType.KEY, HudKeys.Q),
            BUTTON_Y to PadBinding(PadBindingType.KEY, HudKeys.E),
            BUTTON_START to PadBinding(PadBindingType.KEY, HudKeys.ESCAPE),
            BUTTON_SELECT to PadBinding(PadBindingType.KEY, HudKeys.TAB),
            BUTTON_THUMBL to PadBinding(PadBindingType.KEY, HudKeys.LEFT_CONTROL),
            BUTTON_THUMBR to PadBinding(PadBindingType.KEY, HudKeys.F5),
            BUTTON_L1 to PadBinding(PadBindingType.WHEEL, HudKeys.SCROLL_UP),
            BUTTON_R1 to PadBinding(PadBindingType.WHEEL, HudKeys.SCROLL_DOWN),
            BUTTON_L2 to PadBinding(PadBindingType.MOUSE, HudKeys.MOUSE_RIGHT),
            BUTTON_R2 to PadBinding(PadBindingType.MOUSE, HudKeys.MOUSE_LEFT),
        )

        /** Everything a button may be set to: the editor's keys, three mouse buttons and the wheel. */
        val choices: List<PadBinding>
            get() = listOf(
                PadBinding(PadBindingType.MOUSE, HudKeys.MOUSE_LEFT),
                PadBinding(PadBindingType.MOUSE, HudKeys.MOUSE_RIGHT),
                PadBinding(PadBindingType.MOUSE, HudKeys.MOUSE_MIDDLE),
                PadBinding(PadBindingType.WHEEL, HudKeys.SCROLL_UP),
                PadBinding(PadBindingType.WHEEL, HudKeys.SCROLL_DOWN),
            ) + HudKeys.bindable.map { (code, _) -> PadBinding(PadBindingType.KEY, code) }

        fun label(binding: PadBinding): String = when (binding.type) {
            PadBindingType.KEY -> HudKeys.name(binding.code)
            PadBindingType.MOUSE -> when (binding.code) {
                HudKeys.MOUSE_LEFT -> tr("Удар (ЛКМ)")
                HudKeys.MOUSE_RIGHT -> tr("Действие (ПКМ)")
                else -> tr("Средняя кнопка мыши")
            }
            PadBindingType.WHEEL -> if (binding.code > 0) tr("Слот назад") else tr("Слот вперёд")
        }

        /** Stored text is from an older build or edited by hand: whatever does not fit is dropped. */
        fun decode(text: String): GamepadMap {
            if (text.isBlank()) return GamepadMap()
            val parsed = runCatching { MojangJson.decodeFromString(serializer(), text) }.getOrNull() ?: return GamepadMap()
            return GamepadMap(parsed.bindings.filter { (key, value) -> key in BUTTON_CODES && valid(value) })
        }

        private fun valid(binding: PadBinding): Boolean = when (binding.type) {
            PadBindingType.KEY -> HudKeys.isBindable(binding.code)
            PadBindingType.MOUSE -> binding.code in HudKeys.MOUSE_LEFT..HudKeys.MOUSE_MIDDLE
            PadBindingType.WHEEL -> binding.code == HudKeys.SCROLL_UP || binding.code == HudKeys.SCROLL_DOWN
        }
    }
}
