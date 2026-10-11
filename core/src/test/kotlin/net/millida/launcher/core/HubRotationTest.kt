package net.millida.launcher.core

import net.millida.launcher.core.hub.Rotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HubRotationTest {

    @Test
    fun `день меняется в полночь игрока, а не по Гринвичу`() {
        val lateEvening = Instant.parse("2026-10-05T22:30:00Z")
        assertEquals(
            "в Москве уже 6 октября — карточки дня должны смениться",
            LocalDate.of(2026, 10, 6).toEpochDay(),
            Rotation.dayNumber(lateEvening, ZoneId.of("Europe/Moscow")),
        )
        assertEquals(
            "по UTC ещё 5 октября",
            LocalDate.of(2026, 10, 5).toEpochDay(),
            Rotation.dayNumber(lateEvening, ZoneId.of("UTC")),
        )
    }

    private data class WeekCase(val earlier: LocalDate, val later: LocalDate, val sameWeek: Boolean, val why: String)

    @Test
    fun `неделя начинается с понедельника и до 1970 года тоже`() {
        val cases = listOf(
            WeekCase(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 5), false, "воскресенье и понедельник — разные недели"),
            WeekCase(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11), true, "с понедельника по воскресенье — одна подборка"),
            WeekCase(LocalDate.of(1969, 12, 28), LocalDate.of(1969, 12, 29), false, "деление вниз, а не к нулю: иначе неделя у нуля длится две"),
            WeekCase(LocalDate.of(1969, 12, 29), LocalDate.of(1970, 1, 4), true, "неделя через 1 января 1970 не рвётся"),
        )
        cases.forEach { case ->
            val a = Rotation.weekNumber(case.earlier.toEpochDay())
            val b = Rotation.weekNumber(case.later.toEpochDay())
            assertEquals("${case.earlier} и ${case.later}: ${case.why}", case.sameWeek, a == b)
            assertTrue("${case.earlier} и ${case.later}: неделя не может идти назад", b >= a)
        }
    }

    @Test
    fun `позиция дня всегда внутри списка, даже для дней до эпохи`() {
        val sizes = listOf(1, 2, 3, 7, 13)
        for (day in -400L..400L) {
            for (size in sizes) {
                val index = Rotation.pickIndex(day, size, salt = 3)
                assertTrue("день $day, длина $size: позиция $index вне списка уронит экран", index in 0 until size)
            }
        }
    }

    @Test
    fun `порядок дня — поворот списка, одинаковый весь день`() {
        val items = listOf("a", "b", "c", "d", "e")
        for (day in 0L..30L) {
            val turned = Rotation.ofDay(items, day, salt = 1)
            val start = items.indexOf(turned.first())
            assertEquals("день $day: порядок сохраняется, меняется только начало", items.drop(start) + items.take(start), turned)
            assertEquals("день $day: два запроса в один день дают одно и то же", turned, Rotation.ofDay(items, day, salt = 1))
        }
        assertEquals("пустой список не ломается", emptyList<String>(), Rotation.ofDay(emptyList<String>(), 5, 1))
        assertEquals("один элемент не крутится", listOf("a"), Rotation.ofDay(listOf("a"), 5, 1))
    }

    @Test
    fun `подборка недели идёт подряд по кругу и не выдумывает элементы`() {
        val items = listOf("a", "b", "c", "d", "e")
        val picks = Rotation.weekPicks(items, 2909, 3)
        val start = items.indexOf(picks.first())
        assertEquals("подряд от позиции недели", List(3) { items[(start + it) % items.size] }, picks)
        assertEquals("нулевой размер — пусто", emptyList<String>(), Rotation.weekPicks(items, 2909, 0))
        assertEquals("пустой каталог — пусто", emptyList<String>(), Rotation.weekPicks(emptyList<String>(), 2909, 3))
        assertEquals("просят больше, чем есть, — каждая сборка один раз", items.toSet(), Rotation.weekPicks(items, 2909, 10).toSet())
        assertEquals(5, Rotation.weekPicks(items, 2909, 10).size)
    }

    private data class HeroCase(val stored: String?, val index: Int, val why: String)

    @Test
    fun `сохранённый герой дня читается терпимо к порче`() {
        val today = "2026-10-06"
        val cases = listOf(
            HeroCase("2026-10-06", 0, "без двоеточия дата не распознаётся — начинаем с первого"),
            HeroCase("2026-10-06:abc", 0, "тот же день с испорченным номером — первый слот"),
            HeroCase(":2", 0, "без даты — первый слот"),
            HeroCase("2026-10-05:7", 2, "номер больше числа слотов сворачивается по кругу"),
            HeroCase("", 0, "пустая строка — как ничего"),
        )
        cases.forEach { case ->
            val pick = Rotation.heroPick(case.stored, today)
            assertEquals("«${case.stored}»: ${case.why}", case.index, pick.index)
            assertEquals("«${case.stored}»: сохраняется сегодняшний выбор", "$today:${case.index}", pick.stored)
        }
    }
}
