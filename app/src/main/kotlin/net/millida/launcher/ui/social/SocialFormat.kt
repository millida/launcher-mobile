package net.millida.launcher.ui.social

import net.millida.launcher.core.friends.Friend
import net.millida.launcher.core.friends.FriendPlace
import net.millida.launcher.core.i18n.I18n
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.i18n.trPlural
import net.millida.launcher.ui.MPresence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToLong

private val clockFormat: DateTimeFormatter get() = DateTimeFormatter.ofPattern("HH:mm", I18n.javaLocale)
private val dayMonthFormat: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMMM", I18n.javaLocale)
private val shortDayFormat: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMM", I18n.javaLocale)
private val fullDayFormat: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMMM yyyy", I18n.javaLocale)

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val SECONDS_EPOCH_LIMIT = 1_000_000_000_000L

internal fun Friend.presence(): MPresence = when {
    playing -> MPresence.Playing
    online -> MPresence.Online
    else -> MPresence.Offline
}

internal fun Friend.shownName(): String = nickname.ifEmpty { displayName }.ifEmpty { tr("Игрок") }

internal fun Friend.joinAddress(): String? = serverIp?.trim()?.takeIf { playing && it.isNotEmpty() }

internal fun Friend.statusLine(now: Long = System.currentTimeMillis()): String {
    if (playing) {
        val where = serverName?.takeIf { it.isNotBlank() } ?: serverIp?.takeIf { it.isNotBlank() }
        return if (where != null) tr("В игре · {where}", where) else text.ifEmpty { tr("В игре") }
    }
    if (online) return text.ifEmpty { if (place == FriendPlace.WEB) tr("На сайте") else tr("В сети") }
    if (text.isNotEmpty()) return text
    val seen = lastSeen ?: return tr("Был давно")
    return tr("Был {0}", agoText(seen, now))
}

/** The mark comes in seconds or in milliseconds depending on the endpoint, so it is read by size. */
internal fun agoText(mark: Long, now: Long = System.currentTimeMillis()): String {
    val at = if (mark < SECONDS_EPOCH_LIMIT) mark * 1000 else mark
    val gap = max(0L, now - at)
    return when {
        gap < MINUTE_MS -> tr("только что")
        gap < HOUR_MS -> (gap / MINUTE_MS).let { plural(it, tr("{it} минуту назад", it), tr("{it} минуты назад", it), tr("{it} минут назад", it)) }
        gap < DAY_MS -> (gap / HOUR_MS).let { plural(it, tr("{it} час назад", it), tr("{it} часа назад", it), tr("{it} часов назад", it)) }
        gap < 7 * DAY_MS -> (gap / DAY_MS).let { if (it == 1L) tr("вчера") else plural(it, tr("{it} день назад", it), tr("{it} дня назад", it), tr("{it} дней назад", it)) }
        else -> Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(dayMonthFormat)
    }
}

/** Whole hours read better in a list than "4 ч 0 м"; minutes only below an hour. */
internal fun hoursText(seconds: Long): String {
    val hours = seconds / 3600
    if (hours >= 1) return tr("{hours} ч", hours)
    return tr("{0} мин", max(1L, (seconds / 60.0).roundToLong()))
}

internal fun plural(count: Long, one: String, few: String, many: String): String = trPlural(count, one, few, many)

internal fun localDay(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

internal fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String = when {
    day == today -> tr("Сегодня")
    day == today.minusDays(1) -> tr("Вчера")
    day.year == today.year -> day.format(dayMonthFormat)
    else -> day.format(fullDayFormat)
}

internal fun clockText(ts: Long): String =
    if (ts <= 0) "" else Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(clockFormat)

internal fun chatWhen(ts: Long?): String {
    if (ts == null || ts <= 0) return ""
    val day = localDay(ts)
    val today = LocalDate.now()
    return when (day) {
        today -> clockText(ts)
        today.minusDays(1) -> tr("вчера")
        else -> day.format(shortDayFormat).trimEnd('.')
    }
}
