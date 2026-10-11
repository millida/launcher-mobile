package net.millida.launcher.ui.social

import net.millida.launcher.core.friends.Friend
import net.millida.launcher.core.friends.FriendPlace
import net.millida.launcher.core.friends.ruPlural
import net.millida.launcher.ui.MPresence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

private val russian: Locale = Locale.forLanguageTag("ru")
private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", russian)
private val dayMonthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", russian)
private val shortDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", russian)
private val fullDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", russian)

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val SECONDS_EPOCH_LIMIT = 1_000_000_000_000L

internal fun Friend.presence(): MPresence = when {
    playing -> MPresence.Playing
    online -> MPresence.Online
    else -> MPresence.Offline
}

internal fun Friend.shownName(): String = nickname.ifEmpty { displayName }.ifEmpty { "Игрок" }

internal fun Friend.joinAddress(): String? = serverIp?.trim()?.takeIf { playing && it.isNotEmpty() }

internal fun Friend.statusLine(now: Long = System.currentTimeMillis()): String {
    if (playing) {
        val where = serverName?.takeIf { it.isNotBlank() } ?: serverIp?.takeIf { it.isNotBlank() }
        return if (where != null) "В игре · $where" else text.ifEmpty { "В игре" }
    }
    if (online) return text.ifEmpty { if (place == FriendPlace.WEB) "На сайте" else "В сети" }
    if (text.isNotEmpty()) return text
    val seen = lastSeen ?: return "Был давно"
    return "Был " + agoText(seen, now)
}

/** The mark comes in seconds or in milliseconds depending on the endpoint, so it is read by size. */
internal fun agoText(mark: Long, now: Long = System.currentTimeMillis()): String {
    val at = if (mark < SECONDS_EPOCH_LIMIT) mark * 1000 else mark
    val gap = max(0L, now - at)
    return when {
        gap < MINUTE_MS -> "только что"
        gap < HOUR_MS -> (gap / MINUTE_MS).let { "$it ${plural(it, "минуту", "минуты", "минут")} назад" }
        gap < DAY_MS -> (gap / HOUR_MS).let { "$it ${plural(it, "час", "часа", "часов")} назад" }
        gap < 7 * DAY_MS -> (gap / DAY_MS).let { if (it == 1L) "вчера" else "$it ${plural(it, "день", "дня", "дней")} назад" }
        else -> Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(dayMonthFormat)
    }
}

/** Whole hours read better in a list than "4 ч 0 м"; minutes only below an hour. */
internal fun hoursText(seconds: Long): String {
    val hours = seconds / 3600
    if (hours >= 1) return "$hours ч"
    return "${max(1L, (seconds / 60.0).roundToLong())} мин"
}

internal fun plural(count: Long, one: String, few: String, many: String): String = ruPlural(count, one, few, many)

internal fun localDay(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

internal fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String = when {
    day == today -> "Сегодня"
    day == today.minusDays(1) -> "Вчера"
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
        today.minusDays(1) -> "вчера"
        else -> day.format(shortDayFormat).trimEnd('.')
    }
}
