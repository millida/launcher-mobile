package net.millida.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LostGameVerdictTest {

    @Test
    fun `игра без отчёта о конце считается по записи Android и логу`() {
        data class Case(
            val native: Boolean,
            val reason: Int?,
            val status: Int?,
            val logHasCause: Boolean,
            val expected: Pair<String, String?>,
            val why: String,
        )

        val cases = listOf(
            Case(false, 1, 1, true, "exit_1" to null, "Minecraft сам вышел после краш-репорта — причину решает лог, а не «убит системой»"),
            Case(false, 1, 255, false, "exit_255" to "game_exit", "выход с кодом без причины в логе — отдельный класс, не killed"),
            Case(false, 2, 9, false, "signal_9" to "killed", "сигнал от системы — это и есть убийство"),
            Case(false, 3, 0, false, "low_memory" to "out_of_memory", "нехватка памяти называется прямо"),
            Case(false, 5, 11, false, "native_crash" to "native_crash", "нативный краш по записи Android, даже без нашего файла"),
            Case(true, 2, 6, false, "native_crash" to "native_crash", "наш файл нативного краша важнее сигнала"),
            Case(false, 6, 0, false, "anr" to "anr", "зависание интерфейса — отдельный класс"),
            Case(false, null, null, false, "process_died" to "killed", "Android до 11 записей не даёт — прежний вердикт"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, lostGameVerdict(case.native, case.reason, case.status, case.logHasCause))
        }
    }
}
