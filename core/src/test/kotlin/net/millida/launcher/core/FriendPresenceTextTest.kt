package net.millida.launcher.core

import net.millida.launcher.core.friends.Dialog
import net.millida.launcher.core.friends.DialogKind
import net.millida.launcher.core.friends.Friend
import net.millida.launcher.core.friends.FriendPlace
import net.millida.launcher.core.friends.PresenceKind
import net.millida.launcher.core.friends.PresenceMemo
import net.millida.launcher.core.friends.PresenceNotify
import net.millida.launcher.core.friends.ruPlural
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendPresenceTextTest {

    private fun buddy(
        id: String,
        nick: String = id,
        online: Boolean = false,
        playing: Boolean = false,
        place: FriendPlace = FriendPlace.LAUNCHER,
        serverName: String? = null,
        serverIp: String? = null,
        build: String? = null,
    ) = Friend(
        userId = id,
        nickname = nick,
        online = online,
        playing = playing,
        place = place,
        serverName = serverName,
        serverIp = serverIp,
        build = build,
    )

    private data class PluralCase(val count: Long, val expected: String, val why: String)

    @Test
    fun `русское множественное число по правилам языка`() {
        val cases = listOf(
            PluralCase(0, "друзей", "ноль — родительный множественного"),
            PluralCase(1, "друг", "один"),
            PluralCase(2, "друга", "два — паукальная форма"),
            PluralCase(4, "друга", "верхняя граница паукальной формы"),
            PluralCase(5, "друзей", "пять — уже множественное"),
            PluralCase(11, "друзей", "11 — исключение, хотя кончается на 1"),
            PluralCase(12, "друзей", "12 — исключение, хотя кончается на 2"),
            PluralCase(14, "друзей", "14 — последнее исключение второго десятка"),
            PluralCase(21, "друг", "21 — снова «друг»"),
            PluralCase(22, "друга", "22 — снова «друга»"),
            PluralCase(101, "друг", "101 — сотни не мешают"),
            PluralCase(111, "друзей", "111 — исключение работает и в сотнях"),
            PluralCase(1_000, "друзей", "круглое число"),
        )
        cases.forEach { case ->
            assertEquals(
                "${case.count}: ${case.why} — иначе уведомление читается неграмотно",
                case.expected,
                ruPlural(case.count, "друг", "друга", "друзей"),
            )
        }
    }

    @Test
    fun `карточка пачки склоняет число друзей`() {
        assertEquals("21 друг в игре", PresenceNotify.text(List(21) { buddy("f$it") }, PresenceKind.PLAY))
        assertEquals("11 друзей в игре", PresenceNotify.text(List(11) { buddy("f$it") }, PresenceKind.PLAY))
        assertEquals("2 друга в сети", PresenceNotify.text(List(2) { buddy("f$it") }, PresenceKind.ONLINE))
        assertEquals("двое — оба по имени, без «и ещё»", "A, B", PresenceNotify.title(listOf(buddy("a", "A"), buddy("b", "B"))))
    }

    private data class WhereCase(val friend: Friend, val expected: String, val why: String)

    @Test
    fun `где играет друг берётся из первого непустого поля`() {
        val cases = listOf(
            WhereCase(buddy("a", serverName = "Выживание", serverIp = "play.x", build = "Fabric"), "Играет · Выживание", "имя сервера понятнее адреса"),
            WhereCase(buddy("a", serverIp = "play.x", build = "Fabric"), "Играет · play.x", "без имени — адрес сервера"),
            WhereCase(buddy("a", serverName = "", serverIp = "", build = "Fabric 1.20.1"), "Играет · Fabric 1.20.1", "пустые строки пропускаются, остаётся сборка"),
            WhereCase(buddy("a", serverName = "", serverIp = null, build = ""), "Зашёл в игру", "ничего не известно — без висящей точки"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, PresenceNotify.text(listOf(case.friend), PresenceKind.PLAY))
        }
    }

    @Test
    fun `память сессий забывает ушедших друзей только через десять минут`() {
        val memo = PresenceMemo()
        val minute = 60_000L
        val idle = listOf(buddy("a", online = true))
        val playing = listOf(buddy("a", online = true, playing = true))
        PresenceNotify.events(idle, playing, memo, at = 0)
        assertTrue("зашедший в игру запомнен", memo.playing.containsKey("a"))

        PresenceNotify.events(playing, emptyList(), memo, at = minute)
        assertTrue("пропавший на минуту друг ещё помнится — это мигание, а не выход", memo.playing.containsKey("a"))

        PresenceNotify.events(emptyList(), emptyList(), memo, at = PresenceNotify.REPEAT_MS)
        assertFalse("спустя окно повтора память чистится, иначе она растёт бесконечно", memo.playing.containsKey("a"))

        PresenceNotify.events(idle, playing, memo, at = PresenceNotify.REPEAT_MS + minute)
        memo.clear()
        assertTrue("сброс при выходе из аккаунта стирает всё", memo.playing.isEmpty() && memo.online.isEmpty())
    }

    @Test
    fun `присутствие только на сайте не запоминается как вход`() {
        val memo = PresenceMemo()
        PresenceNotify.events(listOf(buddy("a")), listOf(buddy("a", online = true, place = FriendPlace.WEB)), memo, at = 0)
        assertFalse("сайт — это токен, а не человек; запомнив его, мы бы заглушили настоящий вход", memo.online.containsKey("a"))
        val events = PresenceNotify.events(listOf(buddy("a")), listOf(buddy("a", online = true)), memo, at = 1)
        assertEquals("настоящий вход сразу после сайта звенит", listOf("a"), events.cameOnline.map { it.userId })
    }

    private data class PlaceCase(val wire: String?, val expected: FriendPlace, val why: String)

    @Test
    fun `место друга читается строго по слову сервера`() {
        val cases = listOf(
            PlaceCase("game", FriendPlace.GAME, "в игре"),
            PlaceCase("launcher", FriendPlace.LAUNCHER, "в лаунчере"),
            PlaceCase("web", FriendPlace.WEB, "только на сайте"),
            PlaceCase("", FriendPlace.OFFLINE, "пустое слово — не в сети, а не совпадение с OFFLINE по пустой строке"),
            PlaceCase(null, FriendPlace.OFFLINE, "поля нет"),
            PlaceCase("GAME", FriendPlace.OFFLINE, "регистр сервера фиксирован, чужое написание не угадываем"),
            PlaceCase("mobile", FriendPlace.OFFLINE, "новое место из будущей версии сервера не роняет разбор"),
        )
        cases.forEach { case -> assertEquals("«${case.wire}»: ${case.why}", case.expected, FriendPlace.of(case.wire)) }
    }

    @Test
    fun `ключ треда совпадает с ключом сайта`() {
        assertEquals("friends:u1", Dialog(DialogKind.DM, "u1", "Steve").threadKey)
        assertEquals("friends:room-r1", Dialog(DialogKind.ROOM, "r1", "Клан").threadKey)
    }
}
