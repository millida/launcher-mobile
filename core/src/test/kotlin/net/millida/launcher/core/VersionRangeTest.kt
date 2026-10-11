package net.millida.launcher.core

import net.millida.launcher.core.rating.versionRangeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A server that answers "26.1" and "26.2" is one server on a range of versions,
 * but two badges side by side read as a mistake - a tester reported exactly
 * that. One badge with the edges of the range is the whole rule, and it is the
 * kind of rule that quietly breaks on a version like 1.21.10.
 */
class VersionRangeTest {

    @Test
    fun `список версий сводится к одному человеческому ярлыку`() {
        val cases = listOf(
            // вход                              -> ярлык, зачем закреплено
            listOf("1.21.4") to "1.21.4",
            listOf("26.2", "26.1") to "26.1-26.2",
            listOf("26.2", "26.1", "26.2") to "26.1-26.2",
            listOf(" 26.1 ", "26.2") to "26.1-26.2",
            listOf("1.21.9", "1.21.10") to "1.21.9-1.21.10",
            listOf("1.7.10", "1.21.4", "1.16.5") to "1.7.10-1.21.4",
        )
        cases.forEach { (input, expected) ->
            assertEquals("версии $input должны читаться как «$expected»", expected, versionRangeLabel(input))
        }
    }

    @Test
    fun `мусор не показывается игроку как версия`() {
        listOf(
            emptyList(),
            listOf(""),
            listOf("   "),
            listOf("снапшот"),
            listOf("1.21.x"),
            listOf("26.1-26.2"),
        ).forEach { input ->
            assertNull("«$input» не версия, бейджа быть не должно", versionRangeLabel(input))
        }
    }

    @Test
    fun `нечитаемое отбрасывается, а понятное остаётся`() {
        assertEquals(
            "одна понятная версия рядом с мусором всё равно показывается",
            "1.21.4",
            versionRangeLabel(listOf("снапшот", "1.21.4", "")),
        )
    }

    @Test
    fun `версии сравниваются числами, а не строками`() {
        assertEquals(
            "по строкам «1.21.10» оказалось бы раньше «1.21.9», и диапазон вышел бы наизнанку",
            "1.21.9-1.21.10",
            versionRangeLabel(listOf("1.21.10", "1.21.9")),
        )
    }
}
