package net.millida.launcher.core

import net.millida.launcher.core.account.Account
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.account.FirstRunGameStep
import net.millida.launcher.core.account.LoginGate
import net.millida.launcher.core.account.OfflineAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LoginGateTest {

    private data class Case(
        val name: String,
        val active: AccountKind?,
        val signedIn: Boolean,
        val firstRun: String,
        val hasGame: Boolean,
        val expected: LoginGate.Start,
        val why: String,
    )

    @Test
    fun `куда открывается лаунчер — как на ПК`() {
        val cases = listOf(
            Case("новый игрок", null, false, "", false, LoginGate.Start.SIGN_IN, "без входа в Millida лобби не открывается"),
            Case("все аккаунты удалены", null, false, FirstRunGameStep.DONE, false, LoginGate.Start.SIGN_IN, "играть не под кем, значит снова вход"),
            Case("вход Millida живой", AccountKind.MILLIDA, true, FirstRunGameStep.DONE, false, LoginGate.Start.LOBBY, "вернувшийся игрок с Millida играет как раньше"),
            Case("сессия Millida слетела", AccountKind.MILLIDA, false, FirstRunGameStep.DONE, false, LoginGate.Start.SESSION_LOST, "полузалогиненная оболочка без токена: друзья, кошелёк и хостинг молча мертвы"),
            Case("старый офлайн-ник без Millida", AccountKind.OFFLINE, false, "", true, LoginGate.Start.LOBBY, "у кого уже есть офлайн-аккаунт, тот играет без входа, как на ПК"),
            Case("лицензия без Millida", AccountKind.MICROSOFT, false, "", true, LoginGate.Start.LOBBY, "лицензия тоже открывает лобби без входа в Millida"),
            Case("шаг выбора не закончен", AccountKind.MILLIDA, true, FirstRunGameStep.PENDING, false, LoginGate.Start.GAME_STEP, "закрыл приложение на шаге «Игровой аккаунт» — шаг возвращается"),
            Case("шаг не закончен, аккаунт есть", AccountKind.MILLIDA, true, FirstRunGameStep.PENDING, true, LoginGate.Start.LOBBY, "аккаунт для игры и есть то, о чём просит шаг"),
        )
        for (case in cases) {
            assertEquals(
                "${case.name}: ${case.why}",
                case.expected,
                LoginGate.atStart(case.active, case.signedIn, case.firstRun, case.hasGame),
            )
        }
    }

    @Test
    fun `играть гостем можно только тому, кто входил в Millida`() {
        assertEquals("новичок обязан войти в Millida: гость — не обход аккаунта", false, LoginGate.guestAllowed(millidaEver = false))
        assertEquals("вернувшийся игрок может зайти гостем, как на ПК", true, LoginGate.guestAllowed(millidaEver = true))
    }

    @Test
    fun `вход возвращается, когда удалён последний аккаунт`() {
        val offline = Account("offline:steve", AccountKind.OFFLINE, "Steve", OfflineAuth.offlineUuid("Steve"))
        assertEquals("без аккаунтов играть не под кем — нужен вход", true, LoginGate.afterRemoval(emptyList()))
        assertEquals("остался офлайн-ник — игрок остаётся в лаунчере", false, LoginGate.afterRemoval(listOf(offline)))
    }

    @Test
    fun `ник гостя — PlayerNNNN и проходит проверку офлайн-ника`() {
        val random = Random(7)
        repeat(500) {
            val nick = LoginGate.guestNick(random)
            assertTrue("$nick: гость играет офлайн-ником, иначе «Играть гостем» упадёт на проверке ника", OfflineAuth.validNick(nick))
            assertTrue("$nick: как на ПК, Player и четыре цифры", Regex("^Player[1-9]\\d{3}$").matches(nick))
        }
    }
}
