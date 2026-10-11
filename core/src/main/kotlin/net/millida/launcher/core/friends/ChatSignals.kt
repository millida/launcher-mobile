package net.millida.launcher.core.friends

import kotlin.math.roundToLong

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
        val rest = if (names.size > NAMED) " и ещё ${names.size - NAMED}" else ""
        val verb = if (names.size > 1) "печатают" else "печатает"
        return "$shown$rest $verb…"
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
        card.totalSeconds?.let { add("Всего в игре" to playtime(it)) }
        card.sessions?.let { add("Запусков" to it.toString()) }
        card.lastBuild?.takeIf { it.isNotBlank() }?.let { add("Последняя сборка" to it) }
        val server = card.lastServerName?.takeIf { it.isNotBlank() } ?: card.lastServer?.takeIf { it.isNotBlank() }
        if (server != null) {
            val ago = card.lastPlayedAt?.let { whenText((it / 1000.0).roundToLong(), nowSeconds) }.orEmpty()
            add("Последний сервер" to if (ago.isEmpty()) server else "$server · $ago")
        }
    }

    fun joinable(card: FriendProfileCard): Boolean = card.playing && !card.serverIp.isNullOrBlank()

    fun playtime(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        if (total < 60) return "меньше минуты"
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        if (hours == 0L) return "$minutes мин"
        return if (hours >= 100) "$hours ч" else "$hours ч $minutes м"
    }

    fun whenText(tsSeconds: Long, nowSeconds: Long): String {
        if (tsSeconds <= 0) return ""
        val days = (nowSeconds - tsSeconds) / DAY_SECONDS
        if (days < 1) return "сегодня"
        if (days < 2) return "вчера"
        if (days < 30) return "${days.roundToLong()} дн назад"
        val months = (days / 30).roundToLong()
        return if (months < 12) "$months мес назад" else "${(days / 365).roundToLong()} г назад"
    }
}
