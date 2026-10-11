package net.millida.launcher

import net.millida.launcher.core.friends.ChatAttachment
import net.millida.launcher.core.friends.ChatMessage
import net.millida.launcher.core.friends.ChatTarget
import net.millida.launcher.core.friends.Friend
import net.millida.launcher.core.friends.FriendPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SocialModelHelpersTest {

    @Test
    fun `ключи строк списка различают сущности`() {
        val keys = listOf(
            friendKey("42"),
            requestKey("42"),
            blockKey("42"),
            inviteKey(ChatTarget.Dm("42")),
            inviteKey(ChatTarget.Room("42")),
            threadKey(ChatTarget.Dm("42")),
            threadKey(ChatTarget.Room("42")),
            ADD_FRIEND_KEY,
        )

        assertEquals(
            "одинаковый id у друга, заявки и комнаты не должен давать один ключ — ленивый список упадёт на повторе",
            keys.size,
            keys.toSet().size,
        )
        assertEquals("dm:42", threadKey(ChatTarget.Dm("42")))
        assertEquals("room:7", threadKey(ChatTarget.Room("7")))
        assertEquals("приглашение привязано к ветке чата", "invite:room:7", inviteKey(ChatTarget.Room("7")))
    }

    @Test
    fun `пустое обновление присутствия не трогает список`() {
        val current = listOf(Friend(userId = "a", nickname = "Alex", online = true))

        assertSame("пустой ответ опроса не должен перерисовывать список друзей", current, mergePresence(current, emptyList()))
    }

    @Test
    fun `присутствие обновляет только знакомых друзей и не теряет известное`() {
        val alex = Friend(
            userId = "a",
            nickname = "Alex",
            gameNick = "AlexMC",
            lastSeen = 100L,
            unread = 3,
            lastMessageAt = 500L,
        )
        val bob = Friend(userId = "b", nickname = "Bob", online = true)
        val live = Friend(
            userId = "a",
            nickname = "ignored",
            online = true,
            playing = true,
            place = FriendPlace.GAME,
            text = "В игре · Выживание",
            serverIp = "mc.example.net",
            serverName = "Example",
            build = "1.21.4",
            unread = 0,
        )
        val stranger = Friend(userId = "z", nickname = "Zed", online = true)

        val merged = mergePresence(listOf(alex, bob), listOf(live, stranger))

        assertEquals("чужой из опроса не добавляется в список друзей", listOf("a", "b"), merged.map { it.userId })
        val updated = merged[0]
        assertEquals("ник друга не переписывается присутствием", "Alex", updated.nickname)
        assertEquals(true, updated.playing)
        assertEquals(FriendPlace.GAME, updated.place)
        assertEquals("mc.example.net", updated.serverIp)
        assertEquals("Example", updated.serverName)
        assertEquals("1.21.4", updated.build)
        assertEquals("непрочитанные берутся из свежих данных, даже ноль", 0, updated.unread)
        assertEquals("игровой ник не стирается, если опрос его не прислал", "AlexMC", updated.gameNick)
        assertEquals("время последнего визита не стирается пустым значением", 100L, updated.lastSeen)
        assertEquals("время последнего сообщения не стирается", 500L, updated.lastMessageAt)
        assertSame("друг без свежих данных остаётся тем же объектом", bob, merged[1])
    }

    @Test
    fun `превью ответа на сообщение`() {
        val long = "x".repeat(500)
        val message = ChatMessage(
            id = "m1",
            text = long,
            me = true,
            fromNick = "Alex",
            deleted = false,
            attachment = ChatAttachment(url = "https://cdn/v.ogg", kind = "voice"),
        )

        val preview = message.preview()

        assertEquals("m1", preview.id)
        assertEquals(true, preview.me)
        assertEquals("автор берётся из ника", "Alex", preview.from)
        assertEquals("длинный текст режется, чтобы плашка ответа не раздувала сообщение", 120, preview.text.length)
        assertEquals("тип вложения нужен, чтобы показать «Голосовое»", "voice", preview.kind)
        assertNotEquals(long, preview.text)
    }
}
