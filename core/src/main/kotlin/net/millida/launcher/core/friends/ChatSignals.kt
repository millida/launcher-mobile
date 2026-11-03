package net.millida.launcher.core.friends

import kotlin.math.roundToLong

import net.millida.launcher.core.i18n.tr
/**
 * A reaction is drawn the moment it is tapped; the server's answer follows and
 * corrects the count if the other side pressed the same one at that moment
 * (toggleChatReaction in the desktop src/state/friends.ts).
 */
object ChatReactions {

    fun toggled(current: List<ChatReaction>, emoji: String): List<ChatReaction> {
        val mine = current.firstOrNull { it.emoji == emoji }?.mine == true
        val next = current
            .map { if (it.emoji == emoji) it.copy(count = it.count + if (mine) -1 else 1, mine = !mine) else it }
            .filter { it.count > 0 }
        return if (!mine && current.none { it.emoji == emoji }) next + ChatReaction(emoji, 1, mine = true) else next
    }
}

/** The "печатает…" line under the conversation, worded as the desktop src/components/Chat.tsx does it. */
object ChatTyping {

    private const val NAMED = 2

    fun line(names: List<String>): String? {
        if (names.isEmpty()) return null
        val shown = names.take(NAMED).joinToString(", ")
        return when {
            names.size > NAMED -> tr("{0} и ещё {1} печатают…", shown, names.size - NAMED)
            names.size > 1 -> tr("{0} печатают…", shown)
            else -> tr("{0} печатает…", shown)
        }
    }
}

/** A nick the site handed out by itself (`user_1234567`): nobody types it, so friends cannot find the player by it. */
object FriendNicks {

    private val TECHNICAL = Regex("^(skins|smx|guest|user)_\\d{6,}$", RegexOption.IGNORE_CASE)

    fun isTechnical(nick: String?): Boolean = TECHNICAL.matches(nick.orEmpty().trim())
}

/** The stats block of a friend's profile, row for row as FriendStats in the desktop Chat.tsx. */
object FriendProfileStats {

    private const val DAY_SECONDS = 86_400.0

    fun rows(card: FriendProfileCard, nowSeconds: Long): List<Pair<String, String>> = buildList {
        card.totalSeconds?.let { add(tr("Всего в игре") to playtime(it)) }
        card.sessions?.let { add(tr("Запусков") to it.toString()) }
        card.lastBuild?.takeIf { it.isNotBlank() }?.let { add(tr("Последняя сборка") to it) }
        val server = card.lastServerName?.takeIf { it.isNotBlank() } ?: card.lastServer?.takeIf { it.isNotBlank() }
        if (server != null) {
            val ago = card.lastPlayedAt?.let { whenText((it / 1000.0).roundToLong(), nowSeconds) }.orEmpty()
            add(tr("Последний сервер") to if (ago.isEmpty()) server else "$server · $ago")
        }
    }

    fun joinable(card: FriendProfileCard): Boolean = card.playing && !card.serverIp.isNullOrBlank()

    fun playtime(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        if (total < 60) return tr("меньше минуты")
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        if (hours == 0L) return tr("{minutes} мин", minutes)
        return if (hours >= 100) tr("{hours} ч", hours) else tr("{hours} ч {minutes} м", hours, minutes)
    }

    fun whenText(tsSeconds: Long, nowSeconds: Long): String {
        if (tsSeconds <= 0) return ""
        val days = (nowSeconds - tsSeconds) / DAY_SECONDS
        if (days < 1) return tr("сегодня")
        if (days < 2) return tr("вчера")
        if (days < 30) return tr("{0} дн назад", days.roundToLong())
        val months = (days / 30).roundToLong()
        return if (months < 12) tr("{months} мес назад", months) else tr("{0} г назад", (days / 365).roundToLong())
    }
}
