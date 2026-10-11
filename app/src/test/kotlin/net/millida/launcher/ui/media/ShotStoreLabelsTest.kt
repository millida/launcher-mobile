package net.millida.launcher.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ShotStoreLabelsTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `число снимков склоняется`() {
        data class Case(val count: Int, val expected: String)
        listOf(
            Case(0, "0 снимков"),
            Case(1, "1 снимок"),
            Case(2, "2 снимка"),
            Case(4, "4 снимка"),
            Case(5, "5 снимков"),
            Case(11, "11 снимков"),
            Case(14, "14 снимков"),
            Case(21, "21 снимок"),
            Case(102, "102 снимка"),
        ).forEach { case ->
            assertEquals("${case.count}: подпись галереи", case.expected, shotsCount(case.count))
        }
    }

    @Test
    fun `начало дня`() {
        val evening = at(2026, 10, 6, 21, 45)

        assertEquals("снимки группируются по полуночи местного времени", at(2026, 10, 6), startOfDay(evening))
        assertEquals("полночь остаётся полночью", at(2026, 10, 6), startOfDay(at(2026, 10, 6)))
    }

    @Test
    fun `подпись дня в галерее`() {
        val now = at(2026, 10, 6, 14, 30)
        data class Case(val day: Long, val expected: String, val why: String)
        listOf(
            Case(0, "Без даты", "снимок без даты не попадает в 1970 год"),
            Case(-1, "Без даты", "отрицательная метка тоже без даты"),
            Case(at(2026, 10, 6), "Сегодня", "сегодняшний день"),
            Case(at(2026, 10, 5), "Вчера", "вчерашний день"),
            Case(at(2026, 3, 8), "8 марта", "в этом году без года"),
            Case(at(2025, 12, 31), "31 декабря 2025", "прошлый год с годом"),
        ).forEach { case ->
            assertEquals(case.why, case.expected, dayLabel(case.day, now))
        }
    }

    @Test
    fun `переход на летнее время не путает вчера и сегодня`() {
        val now = at(2026, 3, 30, 10, 0)
        val label = dayLabel(startOfDay(at(2026, 3, 29, 12, 0)), now)

        assertTrue("сутки длиной 23 часа всё равно читаются как вчера, получено «$label»", label == "Вчера")
    }
}
