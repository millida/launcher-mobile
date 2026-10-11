package net.millida.launcher.core

import net.millida.launcher.core.runtime.LibraryCompatibility
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCompatibilityTest {

    @Test
    fun `версия игры новее пакета объясняется словами`() {
        data class Case(val game: String, val pack: String, val blocked: Boolean, val why: String)

        val cases = listOf(
            Case("3.3.3", "3.3.6", false, "старее пакета — работает"),
            Case("3.3.6", "3.3.6", false, "ровно та же линия"),
            Case("3.4.1", "3.3.6", true, "26.x требует 3.4: методов в пакете нет"),
            Case("4.0.0", "3.3.6", true, "следующая большая версия тоже не подойдёт"),
            Case("", "3.3.6", false, "версия неизвестна — не мешаем запуску"),
            Case("3.4.1", "", false, "пакет не сообщил версию — не мешаем запуску"),
        )

        for (case in cases) {
            val problem = LibraryCompatibility.problem(case.game, case.pack)
            if (case.blocked) {
                assertNotNull("игра ${case.game} и пакет ${case.pack}: ${case.why}", problem)
                assertTrue(
                    "в тексте должны быть обе версии, иначе пользователю непонятно",
                    problem!!.contains(case.game) && problem.contains(case.pack),
                )
                assertTrue(
                    "текст обязан подсказывать, что делать",
                    problem.contains("1.21.4") || problem.contains("обнови"),
                )
            } else {
                assertNull("игра ${case.game} и пакет ${case.pack}: ${case.why}", problem)
            }
        }
    }
}
