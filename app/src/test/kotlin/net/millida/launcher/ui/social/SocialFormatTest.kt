package net.millida.launcher.ui.social

import net.millida.launcher.core.friends.Friend
import net.millida.launcher.core.friends.FriendPlace
import net.millida.launcher.ui.MPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SocialFormatTest {

    private val now = 1_790_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `сколько времени назад`() {
        data class Case(val mark: Long, val expected: String, val why: String)
        listOf(
            Case(now - 30_000, "только что", "меньше минуты"),
            Case(now + hour, "только что", "часы телефона отстают — будущее не превращается в минус"),
            Case(now - minute, "1 минуту назад", "одна минута"),
            Case(now - 2 * minute, "2 минуты назад", "две минуты"),
            Case(now - 5 * minute, "5 минут назад", "пять минут"),
            Case(now - 21 * minute, "21 минуту назад", "21 склоняется как один"),
            Case(now - hour, "1 час назад", "один час"),
            Case(now - 3 * hour, "3 часа назад", "три часа"),
            Case(now - 11 * hour, "11 часов назад", "11 склоняется как много"),
            Case(now - day, "вчера", "ровно сутки — вчера"),
            Case(now - 3 * day, "3 дня назад", "три дня"),
            Case(now - 6 * day, "6 дней назад", "шесть дней"),
            Case((now - 5 * minute) / 1000, "5 минут назад", "метка в секундах распознаётся по величине"),
        ).forEach { case ->
            assertEquals("${case.mark}: ${case.why}", case.expected, agoText(case.mark, now))
        }
    }

    @Test
    fun `больше недели назад показывается дата`() {
        val mark = now - 30 * day
        val expected = Instant.ofEpochMilli(mark).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")))

        assertEquals("месяц назад читается датой, а не «30 дней назад»", expected, agoText(mark, now))
    }

    @Test
    fun `часы игры`() {
        data class Case(val seconds: Long, val expected: String, val why: String)
        listOf(
            Case(0, "1 мин", "ноль не показывается как «0 мин»"),
            Case(59, "1 мин", "меньше минуты округляется вверх до минуты"),
            Case(90, "2 мин", "полторы минуты округляются"),
            Case(3600, "1 ч", "ровный час"),
            Case(17_940L, "4 ч", "в списке целые часы читаются лучше, чем «4 ч 59 м»"),
        ).forEach { case ->
            assertEquals("${case.seconds}: ${case.why}", case.expected, hoursText(case.seconds))
        }
    }

    @Test
    fun `строка статуса друга`() {
        data class Case(val friend: Friend, val expected: String, val why: String)
        val base = Friend(userId = "1", nickname = "Alex")
        listOf(
            Case(base.copy(playing = true, serverName = "Example", serverIp = "mc.ex"), "В игре · Example", "имя сервера понятнее адреса"),
            Case(base.copy(playing = true, serverName = " ", serverIp = "mc.ex"), "В игре · mc.ex", "без имени показывается адрес"),
            Case(base.copy(playing = true), "В игре", "одиночная игра без сервера"),
            Case(base.copy(playing = true, text = "В игре · Выживание"), "В игре · Выживание", "готовая строка сервера без адреса берётся как есть"),
            Case(base.copy(online = true, place = FriendPlace.WEB), "На сайте", "в сети с сайта"),
            Case(base.copy(online = true, place = FriendPlace.LAUNCHER), "В сети", "в сети из лаунчера"),
            Case(base.copy(online = true, text = "Слушает музыку"), "Слушает музыку", "текст сервера для онлайна важнее шаблона"),
            Case(base.copy(text = "Не в сети · 2 ч назад"), "Не в сети · 2 ч назад", "офлайн с текстом сервера"),
            Case(base, "Был давно", "без отметки визита"),
            Case(base.copy(lastSeen = now - 2 * hour), "Был 2 часа назад", "отметка визита переводится в «назад»"),
        ).forEach { case ->
            assertEquals(case.why, case.expected, case.friend.statusLine(now))
        }
    }

    @Test
    fun `имя, присутствие и адрес друга`() {
        assertEquals("Alex", Friend(userId = "1", nickname = "Alex", displayName = "Шура").shownName())
        assertEquals("без ника показывается отображаемое имя", "Шура", Friend(userId = "1", nickname = "", displayName = "Шура").shownName())
        assertEquals("пустая строка в списке друзей недопустима", "Игрок", Friend(userId = "1", nickname = "").shownName())

        assertEquals("в игре важнее, чем просто в сети", MPresence.Playing, Friend(userId = "1", nickname = "a", playing = true).presence())
        assertEquals(MPresence.Online, Friend(userId = "1", nickname = "a", online = true).presence())
        assertEquals(MPresence.Offline, Friend(userId = "1", nickname = "a").presence())

        assertEquals("адрес для входа обрезается", "mc.ex", Friend(userId = "1", nickname = "a", playing = true, serverIp = " mc.ex ").joinAddress())
        assertNull("не в игре — присоединиться некуда", Friend(userId = "1", nickname = "a", serverIp = "mc.ex").joinAddress())
        assertNull("пустой адрес не ведёт к попытке подключения", Friend(userId = "1", nickname = "a", playing = true, serverIp = "  ").joinAddress())
    }

    @Test
    fun `подпись дня в ленте чата`() {
        val today = LocalDate.of(2026, 10, 6)
        data class Case(val day: LocalDate, val expected: String, val why: String)
        listOf(
            Case(today, "Сегодня", "сегодня"),
            Case(today.minusDays(1), "Вчера", "вчера"),
            Case(LocalDate.of(2026, 3, 8), "8 марта", "в этом году без года"),
            Case(LocalDate.of(2025, 12, 31), "31 декабря 2025", "прошлый год с годом"),
        ).forEach { case ->
            assertEquals(case.why, case.expected, dayLabel(case.day, today))
        }
    }

    @Test
    fun `пустые отметки времени не печатаются`() {
        assertEquals("", clockText(0))
        assertEquals("", chatWhen(null))
        assertEquals("", chatWhen(0))
        assertEquals("", chatWhen(-5))
    }
}
