package net.millida.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessorStatusTest {

    @Test
    fun `файл статуса шагов Forge читается в прогресс`() {
        data class Case(val lines: List<String>, val expected: ProcessorStatus, val why: String)
        listOf(
            Case(emptyList(), ProcessorStatus(0, 0, "", false, null), "драйвер ещё ничего не написал — ноль, не конец"),
            Case(
                listOf("start 1 5 net.minecraftforge:binarypatcher"),
                ProcessorStatus(0, 5, "net.minecraftforge:binarypatcher", false, null),
                "начатый первый шаг ещё не сделан",
            ),
            Case(
                listOf("start 1 5 a", "done 1 5", "start 2 5 b"),
                ProcessorStatus(1, 5, "b", false, null),
                "готовые шаги двигают полосу, текущий шаг показывается по имени",
            ),
            Case(listOf("skip 3 7"), ProcessorStatus(3, 7, "", false, null), "пропущенный шаг тоже засчитан"),
            Case(
                listOf("start 2 4 tool with spaces in name"),
                ProcessorStatus(1, 4, "tool with spaces in name", false, null),
                "имя шага с пробелами не режется",
            ),
            Case(
                listOf("start 4 4 last", "done 4 4", "ok"),
                ProcessorStatus(4, 4, "last", true, null),
                "ok завершает установку без ошибки",
            ),
            Case(
                listOf("start 3 5 jarsplitter", "fail java.io.IOException: нет места"),
                ProcessorStatus(2, 5, "jarsplitter", true, "java.io.IOException: нет места"),
                "провал завершает установку и несёт причину для игрока",
            ),
            Case(listOf("fail"), ProcessorStatus(0, 0, "", true, "без описания"), "провал без текста всё равно объясняется"),
            Case(
                listOf("start 2 6 a", "мусор", "", "start x y"),
                ProcessorStatus(0, 6, "", false, null),
                "битая строка start не роняет чтение и не обнуляет известный итог",
            ),
        ).forEach { case ->
            assertEquals("${case.lines}: ${case.why}", case.expected, readProcessorStatus(case.lines))
        }
    }
}
