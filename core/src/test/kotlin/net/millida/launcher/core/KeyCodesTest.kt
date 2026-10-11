package net.millida.launcher.core

import net.millida.launcher.core.input.AndroidKeys
import net.millida.launcher.core.input.CursorMapping
import net.millida.launcher.core.input.GlfwCodes
import net.millida.launcher.core.input.KeyCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A physical keyboard and mouse on the phone have to behave like on a desktop.
 * The numbers on the left are Android's KeyEvent codes, on the right what
 * GLFW reports for the same key on a computer.
 */
class KeyCodesTest {

    @Test
    fun `клавиши Android переводятся в клавиши GLFW`() {
        data class Case(val android: Int, val glfw: Int, val why: String)

        val cases = listOf(
            Case(29, 65, "A"),
            Case(54, 90, "Z"),
            Case(7, 48, "0 верхнего ряда"),
            Case(131, 290, "F1"),
            Case(142, 301, "F12 — F3 и F11 в игре нужны всегда"),
            Case(144, 320, "цифра 0 на цифровом блоке"),
            Case(153, 329, "цифра 9 на цифровом блоке"),
            Case(158, 330, "точка цифрового блока"),
            Case(159, 330, "запятая цифрового блока на европейской раскладке — та же клавиша"),
            Case(160, 335, "Enter цифрового блока отличается от основного"),
            Case(66, 257, "Enter"),
            Case(111, 256, "Esc — пауза и выход из меню"),
            Case(67, 259, "Backspace"),
            Case(112, 261, "Delete"),
            Case(124, 260, "Insert"),
            Case(122, 268, "Home"),
            Case(123, 269, "End"),
            Case(92, 266, "Page Up"),
            Case(93, 267, "Page Down"),
            Case(115, 280, "Caps Lock"),
            Case(116, 281, "Scroll Lock"),
            Case(143, 282, "Num Lock"),
            Case(120, 283, "Print Screen"),
            Case(121, 284, "Pause"),
            Case(82, 348, "Menu"),
            Case(59, 340, "левый Shift"),
            Case(60, 344, "правый Shift отдельно от левого"),
            Case(113, 341, "левый Ctrl"),
            Case(114, 345, "правый Ctrl"),
            Case(57, 342, "левый Alt"),
            Case(58, 346, "правый Alt — AltGr"),
            Case(117, 343, "левый Win"),
            Case(118, 347, "правый Win"),
            Case(19, 265, "стрелка вверх"),
            Case(68, 96, "тильда — консоль модов"),
        )
        cases.forEach { case -> assertEquals(case.why, case.glfw, KeyCodes.glfw(case.android)) }
        assertEquals("кнопка Back не клавиша игры: её ловит меню лаунчера", GlfwCodes.UNKNOWN, KeyCodes.glfw(4))
    }

    @Test
    fun `обратный перевод для SDL3 даёт ту же клавишу`() {
        for (android in listOf(29, 7, 131, 142, 144, 66, 111, 59, 60, 113, 114, 57, 58, 82, 120)) {
            val glfw = KeyCodes.glfw(android)
            assertEquals("клавиша $android должна вернуться в себя: SDL3 получает код Android", android, KeyCodes.android(glfw))
        }
        assertEquals("запятая цифрового блока возвращается точкой: у GLFW одна клавиша на обе", AndroidKeys.NUMPAD_DOT, KeyCodes.android(GlfwCodes.KP_DECIMAL))
        assertNull("неизвестная клавиша GLFW ничего не нажимает", KeyCodes.android(9_999))
    }

    @Test
    fun `кнопки мыши`() {
        data class Case(val android: Int, val glfw: Int?, val why: String)

        val cases = listOf(
            Case(1, 0, "левая"),
            Case(2, 1, "правая — без неё не поставить блок"),
            Case(4, 2, "средняя — выбор блока"),
            Case(8, 3, "боковая «назад» — на неё вешают способности моды"),
            Case(16, 4, "боковая «вперёд»"),
            Case(32, null, "стилус — не мышь"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.glfw, KeyCodes.mouseButton(case.android))
            case.glfw?.let { assertEquals("${case.why}: обратно в бит Android для SDL3", case.android, KeyCodes.androidButtonBit(it)) }
        }
    }

    @Test
    fun `курсор переводится в пиксели окна и не уходит за край`() {
        data class Case(val x: Float, val y: Float, val view: Pair<Int, Int>, val window: Pair<Int, Int>, val expected: Pair<Float, Float>, val why: String)

        val cases = listOf(
            Case(100f, 50f, 2400 to 1080, 2400 to 1080, 100f to 50f, "окно во весь экран — точка в точку"),
            Case(1200f, 540f, 2400 to 1080, 1200 to 540, 600f to 270f, "картинка в половинном разрешении — координаты делятся"),
            Case(-30f, 2000f, 2400 to 1080, 2400 to 1080, 0f to 1079f, "мышь у края экрана — курсор на краю окна"),
            Case(10f, 10f, 0 to 0, 2400 to 1080, 0f to 0f, "окно ещё без размера — без деления на ноль"),
        )
        cases.forEach { case ->
            assertEquals(
                case.why,
                case.expected,
                CursorMapping.toWindow(case.x, case.y, case.view.first, case.view.second, case.window.first, case.window.second),
            )
        }
    }
}
