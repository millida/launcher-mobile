package net.millida.launcher.core

import kotlinx.coroutines.CancellationException
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.hosting.HostingError
import net.millida.launcher.core.hosting.guard
import net.millida.launcher.core.hosting.hostingFailure
import net.millida.launcher.core.hosting.statusText
import net.millida.launcher.core.http.HttpError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HostingErrorsTest {

    private data class Case(val error: Throwable, val expected: String, val why: String)

    private val fallback = "сервис хостинга не ответил, попробуй позже"

    @Test
    fun `код ответа переводится в совет игроку`() {
        val cases = listOf(
            401 to "войди в аккаунт заново",
            402 to "пополни счёт",
            403 to "не хватает прав",
            404 to "уже удалено",
            409 to "занято",
            413 to "слишком большой",
            429 to "подожди немного",
            503 to "обновляется",
        )
        for ((code, part) in cases) {
            val text = statusText(code)
            assertTrue("код $code: игрок должен прочитать «$part», а видит «$text»", text.contains(part))
            assertFalse("код $code: голое число вместо совета", text.contains(code.toString()))
        }
        val unknown = statusText(418)
        assertTrue("незнакомый код назван числом, чтобы поддержка могла найти причину: $unknown", unknown.contains("418"))
        assertTrue("незнакомый код всё равно подсказывает, что делать: $unknown", unknown.contains("повтори"))
    }

    @Test
    fun `сбой превращается в одну понятную фразу с действием`() {
        val cases = listOf(
            Case(Unauthorized(), "Запуск: ${statusText(401)}", "протухшая сессия — это вход заново, а не «Unauthorized»"),
            Case(IOException("сервер ответил 503"), "Запуск: ${statusText(503)}", "фраза MillidaAuth без тела — единственное место, где жив код"),
            Case(HttpError(404, "https://api.example/x"), "Запуск: ${statusText(404)}", "ошибка загрузчика тоже несёт код"),
            Case(IOException("HTTP 429 Too Many Requests"), "Запуск: ${statusText(429)}", "код из строки OkHttp узнаётся без учёта регистра"),
            Case(IOException("сервер ответил 599"), "Запуск: ${statusText(599)}", "незнакомый код всё равно не теряется"),
            Case(IOException("Сервер уже запущен"), "Запуск: Сервер уже запущен", "живое объяснение от службы показывается как есть"),
            Case(IOException("  Нода перегружена  "), "Запуск: Нода перегружена", "пробелы вокруг текста службы убираются"),
            Case(IOException(""), "Запуск: $fallback", "пустое сообщение не превращается в «Запуск: »"),
            Case(IOException(null as String?), "Запуск: $fallback", "сообщение null не печатается словом null"),
            Case(IOException("Forbidden"), "Запуск: $fallback", "английское имя статуса игроку ничего не говорит"),
            Case(IOException("internal server error"), "Запуск: $fallback", "шум узнаётся без учёта регистра"),
            Case(IOException("ThrottlerException: Too Many Requests"), "Запуск: $fallback", "внутреннее имя класса службы не показывается"),
            Case(IllegalStateException("error"), "Запуск: $fallback", "голое «error» — тоже шум"),
        )
        for (case in cases) {
            val failure = hostingFailure("Запуск", case.error)
            assertEquals("${case.error}: ${case.why}", case.expected, failure.message)
            assertSame("${case.error}: исходная причина потеряна — без неё не разобрать сбой по логам", case.error, failure.cause)
        }
    }

    @Test
    fun `уже готовая ошибка хостинга не оборачивается второй раз`() {
        val ready = HostingError("Остановка: не хватает прав")
        val failure = hostingFailure("Запуск", ready)
        assertSame("готовая ошибка обёрнута заново — игрок увидит «Запуск: Остановка: …»", ready, failure)
    }

    @Test
    fun `guard отдаёт результат, переводит сбой и не глотает отмену`() {
        assertEquals("успешный блок вернул не свой результат", 42, guard("Действие") { 42 })

        try {
            guard<Unit>("Сохранение") { throw IOException("сервер ответил 413") }
            fail("сбой внутри guard пропал — экран скажет «Готово», хотя ничего не сохранилось")
        } catch (error: HostingError) {
            assertEquals("сбой не переведён в слова", "Сохранение: ${statusText(413)}", error.message)
        }

        val cancelled = CancellationException("экран закрыт")
        try {
            guard<Unit>("Сохранение") { throw cancelled }
            fail("отмена проглочена guard")
        } catch (error: HostingError) {
            fail("отмена корутины превратилась в ошибку для игрока: ${error.message}")
        } catch (error: CancellationException) {
            assertSame("отмена подменена другой", cancelled, error)
        }
    }
}
