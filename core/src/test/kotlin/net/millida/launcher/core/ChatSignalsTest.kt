package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.friends.ChatReaction
import net.millida.launcher.core.friends.ChatReactions
import net.millida.launcher.core.friends.ChatTyping
import net.millida.launcher.core.friends.Eltaller
import net.millida.launcher.core.friends.FriendNicks
import net.millida.launcher.core.friends.FriendProfileCard
import net.millida.launcher.core.friends.FriendProfileStats
import net.millida.launcher.core.friends.FriendsClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reactions, the "печатает…" line and the friend's stats block are worded and
 * counted as the desktop launcher does it (src/components/Chat.tsx,
 * src/state/friends.ts): both clients show the same conversation side by side.
 */
class ChatSignalsTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun client(): FriendsClient {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, "token")
        return FriendsClient(MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/')))
    }

    @Test
    fun `реакция переключается сразу, как на десктопе`() {
        val like = "👍"
        val fire = "🔥"
        data class Case(val why: String, val before: List<ChatReaction>, val emoji: String, val want: List<ChatReaction>)
        val cases = listOf(
            Case("первая реакция встаёт в конец со счётом 1 и пометкой «моя»", emptyList(), like, listOf(ChatReaction(like, 1, true))),
            Case(
                "чужая такая же — счёт растёт, реакция становится моей",
                listOf(ChatReaction(like, 2, false)),
                like,
                listOf(ChatReaction(like, 3, true)),
            ),
            Case("моя единственная снимается целиком, пустой чип не висит", listOf(ChatReaction(like, 1, true)), like, emptyList()),
            Case(
                "моя при чужих — счёт падает, остальные не трогаются",
                listOf(ChatReaction(fire, 1, false), ChatReaction(like, 3, true)),
                like,
                listOf(ChatReaction(fire, 1, false), ChatReaction(like, 2, false)),
            ),
        )
        for (case in cases) {
            assertEquals(case.why, case.want, ChatReactions.toggled(case.before, case.emoji))
        }
    }

    @Test
    fun `ряд реакций меню — восемь символов в порядке десктопа, у каждого пиксельный двойник`() {
        val reactions = Eltaller.reactions
        assertEquals("сервер принимает ровно этот набор — лишний символ он отклонит", 8, reactions.size)
        assertEquals("порядок ряда как в REACTIONS десктопа: первым «палец вверх»", "👍", reactions.first())
        assertEquals("последним «хлопушка»", "🎉", reactions.last())
        assertTrue("каждая реакция рисуется картинкой, без системного эмодзи", reactions.all { Eltaller.reactionArt(it) != null })
    }

    @Test
    fun `строка «печатает» собирается словами десктопа`() {
        val cases = listOf(
            Triple(emptyList<String>(), null, "никто не печатает — строки нет вовсе"),
            Triple(listOf("Vasya"), "Vasya печатает…", "один — единственное число"),
            Triple(listOf("Vasya", "Petya"), "Vasya, Petya печатают…", "двое — через запятую, множественное"),
            Triple(listOf("A", "B", "C", "D"), "A, B и ещё 2 печатают…", "больше двух — остальные числом, строка не растёт"),
        )
        for ((names, want, why) in cases) {
            assertEquals(why, want, ChatTyping.line(names))
        }
    }

    @Test
    fun `служебный ник узнаётся как на десктопе`() {
        val cases = listOf(
            Triple("user_1234567", true, "выданный сайтом ник никто не наберёт — просим задать свой"),
            Triple("  SKINS_000000 ", true, "регистр и пробелы по краям не спасают служебный ник"),
            Triple("guest_12345", false, "меньше шести цифр — уже похоже на ник человека"),
            Triple("user_abc123", false, "буквы в хвосте — ник, выбранный человеком"),
            Triple("Steve", false, "обычный ник"),
            Triple(null, false, "ника ещё нет — ветку «служебный» не показываем раньше времени"),
        )
        for ((nick, want, why) in cases) {
            assertEquals(why, want, FriendNicks.isTechnical(nick))
        }
    }

    @Test
    fun `статистика профиля — строки и формулировки десктопа`() {
        val now = 1_800_000_000L
        val full = FriendProfileCard(
            userId = "u1",
            totalSeconds = 3 * 3600 + 25 * 60,
            sessions = 12,
            lastBuild = "Выживание 1.21",
            lastServer = "mc.example.net",
            lastServerName = "Пример",
            lastPlayedAt = (now - 86_400 - 3600) * 1000,
        )
        assertEquals(
            "полный профиль: порядок строк и формат как в FriendStats",
            listOf(
                "Всего в игре" to "3 ч 25 м",
                "Запусков" to "12",
                "Последняя сборка" to "Выживание 1.21",
                "Последний сервер" to "Пример · вчера",
            ),
            FriendProfileStats.rows(full, now),
        )
        assertEquals(
            "без имени сервера показывается адрес, без даты — без хвоста",
            listOf("Последний сервер" to "mc.example.net"),
            FriendProfileStats.rows(FriendProfileCard(userId = "u1", lastServer = "mc.example.net"), now),
        )
        assertTrue("пустой профиль — строк нет, экран пишет «Пока без игр»", FriendProfileStats.rows(FriendProfileCard("u1"), now).isEmpty())

        val playtime = listOf(30L to "меньше минуты", 25L * 60 to "25 мин", 100L * 3600 + 60 to "100 ч")
        for ((seconds, want) in playtime) {
            assertEquals("наигранное $seconds с", want, FriendProfileStats.playtime(seconds))
        }
        val ago = listOf(now - 3600 to "сегодня", now - 10 * 86_400 to "10 дн назад", now - 90 * 86_400 to "3 мес назад", now - 800 * 86_400 to "2 г назад")
        for ((ts, want) in ago) {
            assertEquals("давность ${now - ts} с", want, FriendProfileStats.whenText(ts, now))
        }
    }

    @Test
    fun `реакция уходит на сообщение, печатающие читаются из опроса`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"m1","me":false,"text":"hi","ts":1,"reactions":[{"emoji":"👍","count":1,"mine":true}]}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"now":5,"typing":["u1",""],"roomTyping":[{"roomId":"r1","users":["u2","u3"]},{"users":["x"]}]}""",
            ),
        )
        val api = client()

        val reacted = api.react("m1", "👍")
        val request = server.takeRequest()
        val poll = api.poll(0)

        assertEquals("реакция — на /react самого сообщения", "/v2/friends/chat/message/m1/react", request.path)
        assertTrue("символ реакции уходит как есть: сервер сверяет его со своим набором", request.body.readUtf8().contains("\"emoji\""))
        assertEquals("ответ — сообщение с пересчитанными реакциями", 1, reacted.reactions.single().count)
        assertEquals("пустой id печатающего отбрасывается", listOf("u1"), poll.typing)
        assertEquals("группа без id не попадает в список", mapOf("r1" to listOf("u2", "u3")), poll.roomTyping)
    }
}
