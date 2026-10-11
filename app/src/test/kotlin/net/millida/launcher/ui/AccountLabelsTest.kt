package net.millida.launcher.ui

import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.skins.CapeOffer
import net.millida.launcher.ui.character.capeProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountLabelsTest {

    @Test
    fun `вид аккаунта словами`() {
        assertEquals("Без лицензии", accountKindLabel(AccountKind.OFFLINE))
        assertEquals("Лицензия Microsoft", accountKindLabel(AccountKind.MICROSOFT))
        assertEquals("Аккаунт Millida", accountKindLabel(AccountKind.MILLIDA))
        assertEquals(
            "у каждого вида аккаунта своя подпись, иначе игрок не отличит лицензию от пиратки",
            AccountKind.entries.size,
            AccountKind.entries.map { accountKindLabel(it) }.toSet().size,
        )
    }

    @Test
    fun `прогресс плаща из гардероба`() {
        data class Case(val cape: CapeOffer, val expected: String, val why: String)
        val base = CapeOffer(id = "c", name = "Плащ", url = "https://cdn/c.png")
        listOf(
            Case(base.copy(progressCurrent = 3, progressTarget = 10, progressUnit = "часов"), "3 из 10 часов", "счётчик прогресса"),
            Case(base.copy(progressCurrent = 1, progressTarget = 5), "1 из 5", "без единиц нет висящего пробела"),
            Case(base.copy(requirement = "Пригласи друга"), "Пригласи друга", "без счётчика показывается условие"),
            Case(base.copy(progressTarget = 5, requirement = "Пригласи друга"), "0 из 5", "счётчик важнее текста условия"),
            Case(base, "Пока закрыт", "без условий плащ просто закрыт"),
        ).forEach { case ->
            assertEquals(case.why, case.expected, capeProgress(case.cape))
        }
    }
}
