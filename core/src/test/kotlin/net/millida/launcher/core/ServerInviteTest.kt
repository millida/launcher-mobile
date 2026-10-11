package net.millida.launcher.core

import net.millida.launcher.core.friends.ServerInvite
import net.millida.launcher.core.friends.ServerInvites
import net.millida.launcher.core.hosting.HostingServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Позвать играть" is a chat message the desktop and the site turn into a
 * "Зайти" card, so the text format is a contract with them
 * (src/lib/invite.ts): one changed character and the friend sees raw JSON.
 */
class ServerInviteTest {

    @Test
    fun `приглашение кодируется в формат десктопа`() {
        assertEquals(
            "маркер и порядок полей как у encodeInvite",
            "⟪mc-invite⟫{\"addr\":\"play.example.net\",\"name\":\"Мой сервер\",\"version\":\"1.21.4\"}",
            ServerInvites.encode("play.example.net", "Мой сервер", "1.21.4"),
        )
        assertEquals(
            "кривая версия не уходит другу — без неё он запустит выбранное",
            "⟪mc-invite⟫{\"addr\":\"a.net\",\"name\":\"A\"}",
            ServerInvites.encode("a.net", "A", "1.21-rc1"),
        )
    }

    @Test
    fun `разбор приглашения`() {
        data class Case(val why: String, val text: String, val want: ServerInvite?)
        val cases = listOf(
            Case(
                "своё же приглашение читается обратно",
                ServerInvites.encode("a.net:25565", "Сервер", "1.20.1"),
                ServerInvite("a.net:25565", "Сервер", "1.20.1"),
            ),
            Case("обычный текст — не приглашение", "го играть", null),
            Case("битый JSON — не приглашение", "⟪mc-invite⟫{addr", null),
            Case("без адреса — не приглашение", "⟪mc-invite⟫{\"name\":\"x\"}", null),
            Case("без имени — «Сервер»", "⟪mc-invite⟫{\"addr\":\"a.net\"}", ServerInvite("a.net", "Сервер", null)),
            Case(
                "мусорная версия отбрасывается",
                "⟪mc-invite⟫{\"addr\":\"a.net\",\"name\":\"A\",\"version\":\"latest\"}",
                ServerInvite("a.net", "A", null),
            ),
        )
        for (case in cases) {
            assertEquals(case.why, case.want, ServerInvites.parse(case.text))
        }
    }

    @Test
    fun `адрес проверяется как на десктопе`() {
        val cases = listOf(
            "play.millida.net" to true,
            "mc.example.org:25565" to true,
            "1.2.3.4:19132" to true,
            "" to false,
            "http://evil" to false,
            "a b" to false,
            "-bad.net" to false,
        )
        for ((addr, want) in cases) {
            assertEquals("адрес «$addr»", want, ServerInvites.isServerAddr(addr))
        }
    }

    @Test
    fun `зовём на запущенный сервер с адресом`() {
        val off = HostingServerState(id = "1", name = "Старый", status = "STOPPED", address = "old.net")
        val on = HostingServerState(id = "2", name = "Живой", status = "RUNNING", address = "live.net")
        val bare = HostingServerState(id = "3", status = "RUNNING")
        assertEquals("запущенный важнее", "2", ServerInvites.pickServer(listOf(off, on, bare))?.id)
        assertEquals("нет запущенного — любой с адресом", "1", ServerInvites.pickServer(listOf(bare, off))?.id)
        assertNull("без адреса звать некуда — идём создавать", ServerInvites.pickServer(listOf(bare)))
        assertEquals("имя длиннее 48 режется", 48, ServerInvites.parse(ServerInvites.forServer(on.copy(name = "x".repeat(80))))?.name?.length)
        assertEquals("без имени — «Мой сервер»", "Мой сервер", ServerInvites.serverTitle(bare))
    }

    @Test
    fun `позвать из лобби`() {
        assertEquals("Го играть: Выживание", ServerInvites.lobbyCall("Выживание"))
        assertEquals("Го играть", ServerInvites.lobbyCall(null))
        assertEquals("Го играть", ServerInvites.lobbyCall("  "))
    }
}
