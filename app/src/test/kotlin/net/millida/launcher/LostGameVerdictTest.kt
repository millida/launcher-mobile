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

    @Test
    fun `выход из меню игры не считается вылетом`() {
        data class Case(val reason: Int?, val status: Int?, val log: String?, val quit: Boolean, val why: String)

        val cases = listOf(
            Case(1, 0, "[12:00] [Render thread/INFO]: Stopping!", true, "«Выйти из игры» в меню: процесс сам вышел с кодом 0"),
            Case(1, 0, "java.lang.UnsatisfiedLinkError: Unable to load library 'flite'", true,
                "ошибка диктора в журнале пережита игрой: выход с кодом 0 остаётся выходом, а не вылетом"),
            Case(1, 0, null, true, "журнала нет — код 0 говорит сам за себя"),
            Case(1, 0, "---- Minecraft Crash Report ----\nDescription: Unexpected error", false, "после отчёта о падении код 0 — всё равно вылет"),
            Case(1, 1, "[12:00] [Render thread/INFO]: Stopping!", false, "ненулевой код — не выход по желанию игрока"),
            Case(2, 0, "", false, "сигнал от системы с нулевым статусом — не выход из меню"),
            Case(null, null, "", false, "Android до 11 записей не даёт — гадать нельзя"),
        )
        for (case in cases) {
            assertEquals(case.why, case.quit, quitFromGame(case.reason, case.status, case.log))
        }
    }

    // Причины и подписи — из записей Android о выходе процесса игры за 13.10.2026.
    @Test
    fun `игру, смахнутую игроком, вылетом не считаем`() {
        data class Case(val reason: Int?, val description: String?, val byPlayer: Boolean, val why: String)

        val cases = listOf(
            Case(10, "[REMOVE TASK] remove task", true, "смахнул из недавних"),
            Case(10, "[STOP APP] fully stop net.millida.launcher/0 by user request", true, "«Остановить» в настройках"),
            Case(10, null, true, "причина «по просьбе пользователя» говорит сама, подпись не нужна"),
            Case(13, "SwipeUpClean", true, "чистильщик задач Xiaomi после смахивания"),
            Case(13, "OneKeyClean", true, "кнопка «Очистить всё»"),
            Case(13, "[Swipe-up clean] manual_swipUpClean", true, "тот же чистильщик у другого производителя"),
            Case(16, null, true, "приложение обновилось поверх игры"),
            Case(13, "PowerKeeper: kill background", false, "система сама выгрузила игру — это потеря, а не выход"),
            Case(13, null, false, "«прочее» без подписи — не знаем, значит не выход"),
            Case(6, "user request after error: Input dispatching timed out", false, "закрыл в окне «не отвечает» — зависание"),
            Case(2, null, false, "сигнал от системы"),
            Case(3, null, false, "нехватка памяти"),
            Case(null, null, false, "Android до 11 записей не даёт"),
        )
        for (case in cases) {
            assertEquals(case.why, case.byPlayer, closedByPlayer(case.reason, case.description))
        }
    }

    // Подписи — из записей Android о выходе процесса игры за 19-26.10.2026.
    @Test
    fun `игру закрыл чистильщик производителя - не вылет, а подсказка`() {
        data class Case(val reason: Int?, val description: String?, val cleaned: Boolean, val why: String)

        val cases = listOf(
            Case(13, "[UNKNOWN] o-kill", true, "OPPO, realme, OnePlus"),
            Case(13, "stop com.x o-stop", true, "OnePlus: остановка по o-stop"),
            Case(13, "ZuiMemoryCleaner_Recents", true, "Lenovo"),
            Case(13, "GarbageClean", true, "Xiaomi"),
            Case(13, "cached idle & background restricted", true, "системный предел фоновых процессов"),
            Case(13, "PowerKeeper: kill background", false, "незнакомая подпись остаётся потерей"),
            Case(3, "o-kill", false, "нехватка памяти от lmkd — не чистильщик"),
            Case(null, null, false, "Android до 11"),
        )
        for (case in cases) {
            assertEquals(case.why, case.cleaned, killedByCleaner(case.reason, case.description))
        }
    }
}
