package net.millida.launcher.core

import net.millida.launcher.core.friends.Friend
import net.millida.launcher.core.friends.FriendPlace
import net.millida.launcher.core.friends.NotifyKind
import net.millida.launcher.core.friends.NotifyLevel
import net.millida.launcher.core.friends.NotifyLevels
import net.millida.launcher.core.friends.PresenceKind
import net.millida.launcher.core.friends.PresenceMemo
import net.millida.launcher.core.friends.PresenceNotify
import net.millida.launcher.core.friends.audible
import net.millida.launcher.core.friends.shown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Friend notifications follow the desktop rules row for row
 * (src/state/notifyPrefs.ts, src/state/presenceNotify.ts): the same account
 * must ring the same way on both, and a friend must not get the same
 * "зашёл в игру" twice for one session.
 */
class FriendNotifyTest {

    private fun friend(
        id: String,
        online: Boolean = false,
        playing: Boolean = false,
        place: FriendPlace = FriendPlace.LAUNCHER,
        nick: String = id,
        server: String? = null,
    ) = Friend(userId = id, nickname = nick, online = online, playing = playing, place = place, serverName = server)

    @Test
    fun `уровень из настроек читается как на десктопе`() {
        data class Case(val why: String, val kind: NotifyKind, val raw: String?, val want: NotifyLevel)
        val cases = listOf(
            Case("пусто — сообщения со звуком по умолчанию", NotifyKind.MSG, null, NotifyLevel.SOUND),
            Case("пусто — «в игре» без звука по умолчанию", NotifyKind.PLAY, null, NotifyLevel.SILENT),
            Case("пусто — «в сети» без звука по умолчанию", NotifyKind.ONLINE, null, NotifyLevel.SILENT),
            Case("пусто — заявки со звуком", NotifyKind.REQUEST, null, NotifyLevel.SOUND),
            Case("пусто — группы со звуком", NotifyKind.ROOM, null, NotifyLevel.SOUND),
            Case("выключено читается как выключено", NotifyKind.MSG, "off", NotifyLevel.OFF),
            Case("мусор не выключает уведомления", NotifyKind.MSG, "loud", NotifyLevel.SOUND),
            Case("регистр важен — как в десктопе", NotifyKind.PLAY, "SOUND", NotifyLevel.SILENT),
        )
        for (case in cases) {
            assertEquals(case.why, case.want, NotifyLevels.parse(case.kind, case.raw))
        }
    }

    @Test
    fun `ключи хранения совпадают с десктопом`() {
        val keys = NotifyKind.entries.associate { it.wire to it.key }
        assertEquals(
            "ключи m-notify-* общие с десктопом: переименование сбросит выбор игрока",
            mapOf(
                "msg" to "m-notify-msg",
                "room" to "m-notify-room",
                "play" to "m-notify-play",
                "online" to "m-notify-online",
                "request" to "m-notify-request",
            ),
            keys,
        )
    }

    @Test
    fun `показ и звук зависят только от уровня`() {
        val levels = mapOf(NotifyKind.MSG to NotifyLevel.SILENT, NotifyKind.ROOM to NotifyLevel.OFF)
        assertTrue("без звука — всё равно показываем", levels.shown(NotifyKind.MSG))
        assertFalse("без звука — не звеним", levels.audible(NotifyKind.MSG))
        assertFalse("выключено — не показываем", levels.shown(NotifyKind.ROOM))
        assertTrue("нет записи — действует значение по умолчанию", levels.audible(NotifyKind.REQUEST))
    }

    @Test
    fun `события присутствия решаются по десктопным правилам`() {
        data class Case(
            val why: String,
            val before: List<Friend>,
            val now: List<Friend>,
            val started: List<String>,
            val online: List<String>,
        )
        val cases = listOf(
            Case(
                "зашёл в игру — одна карточка «в игре»",
                listOf(friend("a", online = true)),
                listOf(friend("a", online = true, playing = true)),
                listOf("a"),
                emptyList(),
            ),
            Case(
                "вошёл в сеть и сразу в игру за один опрос — одно событие, не два",
                listOf(friend("a")),
                listOf(friend("a", online = true, playing = true)),
                listOf("a"),
                emptyList(),
            ),
            Case(
                "в сети только на сайте — это токен, а не человек, не звеним",
                listOf(friend("a")),
                listOf(friend("a", online = true, place = FriendPlace.WEB)),
                emptyList(),
                emptyList(),
            ),
            Case(
                "открыл лаунчер — «в сети»",
                listOf(friend("a")),
                listOf(friend("a", online = true)),
                emptyList(),
                listOf("a"),
            ),
            Case(
                "новый друг, которого не было в списке, не считается заходом",
                emptyList(),
                listOf(friend("b", online = true, playing = true)),
                emptyList(),
                emptyList(),
            ),
            Case(
                "уже играл — повторно не звеним",
                listOf(friend("a", online = true, playing = true)),
                listOf(friend("a", online = true, playing = true)),
                emptyList(),
                emptyList(),
            ),
        )
        for (case in cases) {
            val events = PresenceNotify.events(case.before, case.now, null, at = 0)
            assertEquals(case.why, case.started, events.started.map { it.userId })
            assertEquals(case.why, case.online, events.cameOnline.map { it.userId })
        }
    }

    @Test
    fun `одна игровая сессия друга звенит один раз`() {
        val memo = PresenceMemo()
        val idle = listOf(friend("a", online = true))
        val playing = listOf(friend("a", online = true, playing = true))
        val minute = 60_000L
        assertEquals("первый заход звенит", 1, PresenceNotify.events(idle, playing, memo, at = 0).started.size)
        PresenceNotify.events(playing, idle, memo, at = minute)
        assertEquals(
            "мигание присутствия через минуту — та же сессия, карточка не повторяется",
            0,
            PresenceNotify.events(idle, playing, memo, at = 2 * minute).started.size,
        )
        PresenceNotify.events(playing, idle, memo, at = 3 * minute)
        assertEquals(
            "через 10 минут без игры — новый заход, звеним снова",
            1,
            PresenceNotify.events(idle, playing, memo, at = 3 * minute + PresenceNotify.REPEAT_MS).started.size,
        )
    }

    @Test
    fun `карточка пачки называет друзей коротко`() {
        val one = listOf(friend("a", nick = "Кирпич", playing = true, server = "Выживание"))
        val three = listOf(friend("a", nick = "A"), friend("b", nick = "B"), friend("c", nick = "C"))
        assertEquals("Кирпич", PresenceNotify.title(one))
        assertEquals("Играет · Выживание", PresenceNotify.text(one, PresenceKind.PLAY))
        assertEquals("Зашёл в игру", PresenceNotify.text(listOf(friend("a")), PresenceKind.PLAY))
        assertEquals("В сети", PresenceNotify.text(listOf(friend("a")), PresenceKind.ONLINE))
        assertEquals("больше двух — «и ещё N»", "A, B и ещё 1", PresenceNotify.title(three))
        assertEquals("3 друга в игре", PresenceNotify.text(three, PresenceKind.PLAY))
        assertEquals("5 друзей в сети", PresenceNotify.text(List(5) { friend("f$it") }, PresenceKind.ONLINE))
        assertEquals("пустой ник — «Друг»", "Друг", PresenceNotify.title(listOf(friend("a", nick = ""))))
    }
}
