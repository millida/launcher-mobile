package net.millida.launcher.core

import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.account.FirstRunGameStep
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstRunGameStepTest {

    private data class Case(val name: String, val returning: Boolean, val state: String, val hasGame: Boolean, val expected: Boolean, val why: String)

    @Test
    fun `шаг игрового аккаунта после входа в Millida — как на ПК`() {
        val cases = listOf(
            Case("новый игрок, первый вход", false, "", false, true, "новичок выбирает лицензию или офлайн-ник: вход Millida больше не игровой аккаунт"),
            Case("входил раньше", true, "", false, false, "старые игровые аккаунты Millida работают как прежде"),
            Case("шаг не закончен, вход снова", true, FirstRunGameStep.PENDING, false, true, "прошлый вход пометил игрока вернувшимся, но аккаунта для игры у него так и нет"),
            Case("игровой аккаунт уже есть", false, "", true, false, "вход из магазина или помощника не спрашивает второй раз"),
            Case("первый запуск пройден", false, FirstRunGameStep.DONE, false, false, "игрок уже прошёл первый запуск"),
            Case("шаг не закончен, но аккаунт появился", false, FirstRunGameStep.PENDING, true, false, "аккаунт и есть то, о чём просит шаг"),
        )
        for (case in cases) {
            assertEquals("${case.name}: ${case.why}", case.expected, FirstRunGameStep.due(case.returning, case.state, case.hasGame))
        }
    }

    @Test
    fun `игровой аккаунт — лицензия или офлайн-ник`() {
        val expected = mapOf(AccountKind.MICROSOFT to true, AccountKind.OFFLINE to true, AccountKind.MILLIDA to false)
        assertEquals("у каждого вида свой ответ: новый вид обязан решить, игровой ли он", AccountKind.entries.toSet(), expected.keys)
        for ((kind, game) in expected) {
            assertEquals("$kind: вход Millida не закрывает шаг выбора способа игры", game, FirstRunGameStep.isGameAccount(kind))
        }
    }
}
