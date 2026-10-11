package net.millida.launcher.core

import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.friends.FriendsError
import net.millida.launcher.core.friends.FriendsErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class FriendsErrorsTest {

    private data class Case(val error: Throwable?, val expected: String, val why: String)

    private val fallback = "Не удалось открыть переписку"

    @Test
    fun `любой сбой друзей превращается в фразу для игрока`() {
        val cases = listOf(
            Case(null, fallback, "без ошибки остаётся только то, что делал вызывающий"),
            Case(IOException(""), fallback, "пустое сообщение ничего не объясняет"),
            Case(IOException("   "), fallback, "пробелы — тоже пустое сообщение"),
            Case(Unauthorized(), "Сессия Millida закончилась — войди в аккаунт заново", "истёкшая сессия зовёт войти заново, а не «отказано»"),
            Case(IOException("Unauthorized"), "Сессия Millida закончилась — войди в аккаунт заново", "голое Unauthorized от сервера — та же истёкшая сессия"),
            Case(IOException("Unable to resolve host \"api.millida.net\""), "Нет связи с Millida — проверь интернет и повтори", "нет DNS — значит нет интернета, а не ошибка Millida"),
            Case(IOException("Connection refused"), "Нет связи с Millida — проверь интернет и повтори", "соединение не установилось"),
            Case(IOException("нет связи с сервером"), "Нет связи с Millida — проверь интернет и повтори", "наша же формулировка обрыва"),
            Case(SocketTimeoutException("timeout"), "Millida не ответила вовремя — повтори попытку", "таймаут OkHttp читается как «не успела»"),
            Case(IOException("Read timed out"), "Millida не ответила вовремя — повтори попытку", "таймаут чтения"),
            Case(IOException("сервер ответил 429"), "Слишком много попыток подряд — подожди немного и повтори", "429 — подождать, а не «ошибка»"),
            Case(IOException("ThrottlerException: Too Many Requests"), "Слишком много попыток подряд — подожди немного и повтори", "текст троттлера Nest"),
            Case(IOException("too many requests"), "Слишком много попыток подряд — подожди немного и повтори", "голый текст 429 в любом регистре"),
            Case(IOException("сервер ответил 404"), "Не найдено — возможно, это уже удалено", "голый код заменяется объяснением"),
            Case(IOException("HTTP 413"), "Файл слишком большой — уменьши размер и повтори", "код в формате HTTP тоже распознаётся"),
            Case(IOException("сервер ответил 502"), "Millida временно недоступна — повтори через минуту", "502 — это недоступность, а не ошибка игрока"),
            Case(IOException("сервер ответил 599"), "Сбой на стороне Millida — повтори через минуту", "неизвестный 5xx — всё равно сбой на нашей стороне"),
            Case(IOException("сервер ответил 418"), "$fallback (код 418)", "неизвестный 4xx не выдумывает причину, но оставляет код для поддержки"),
            Case(IOException("Not Found"), fallback, "английский шум сервера заменяется смыслом действия"),
            Case(IOException("Internal Server Error"), fallback, "шум без кода — запасной текст"),
            Case(IOException("error"), fallback, "слово error ничего не говорит игроку"),
            Case(IOException("Переписка — только с друзьями"), "Переписка — только с друзьями", "объяснение сервера лучше нашего"),
            Case(IOException("  Ник уже занят  "), "Ник уже занят", "текст сервера показывается без краевых пробелов"),
        )
        cases.forEach { case ->
            assertEquals(
                "ошибка «${case.error?.message}»: ${case.why}",
                case.expected,
                FriendsErrors.text(case.error, fallback),
            )
        }
    }

    @Test
    fun `обёртка сохраняет причину для диагностики`() {
        val cause = IOException("сервер ответил 404")
        val wrapped = FriendsErrors.wrap(cause, fallback)
        assertEquals("игрок видит фразу, а не код", "Не найдено — возможно, это уже удалено", wrapped.message)
        assertSame("исходная причина не теряется — по ней разбирают баг", cause, wrapped.cause)
    }

    @Test
    fun `ошибка друзей без причины создаётся с текстом`() {
        val error = FriendsError("Неверный идентификатор игрока")
        assertEquals("Неверный идентификатор игрока", error.message)
        assertNull("без причины cause пуст", error.cause)
    }
}
