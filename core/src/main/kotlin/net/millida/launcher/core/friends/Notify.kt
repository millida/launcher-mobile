package net.millida.launcher.core.friends

import net.millida.launcher.core.i18n.tr

import net.millida.launcher.core.i18n.trPlural
/**
 * What a friend event may do to the player: ring, show quietly or stay away.
 * Kinds, levels, storage keys and defaults are the desktop ones
 * (src/state/notifyPrefs.ts), so one account behaves the same on both.
 */
enum class NotifyLevel(val wire: String) {
    SOUND("sound"),
    SILENT("silent"),
    OFF("off"),
}

enum class NotifyKind(val wire: String, val key: String, val fallback: NotifyLevel) {
    MSG("msg", "m-notify-msg", NotifyLevel.SOUND),
    ROOM("room", "m-notify-room", NotifyLevel.SOUND),
    PLAY("play", "m-notify-play", NotifyLevel.SILENT),
    ONLINE("online", "m-notify-online", NotifyLevel.SILENT),
    REQUEST("request", "m-notify-request", NotifyLevel.SOUND),
}

object NotifyLevels {

    fun parse(kind: NotifyKind, raw: String?): NotifyLevel =
        NotifyLevel.entries.firstOrNull { it.wire == raw } ?: kind.fallback
}

fun Map<NotifyKind, NotifyLevel>.levelOf(kind: NotifyKind): NotifyLevel = this[kind] ?: kind.fallback

fun Map<NotifyKind, NotifyLevel>.shown(kind: NotifyKind): Boolean = levelOf(kind) != NotifyLevel.OFF

fun Map<NotifyKind, NotifyLevel>.audible(kind: NotifyKind): Boolean = levelOf(kind) == NotifyLevel.SOUND

enum class PresenceKind { PLAY, ONLINE }

data class PresenceEvents(val started: List<Friend>, val cameOnline: List<Friend>)

/**
 * One game session of a friend rings once. Presence blinks for reasons that
 * have nothing to do with playing (a late beat, a minimised window), and every
 * blink used to read as a new start: friends got the same card two and three
 * times. Shared with the desktop (src/state/presenceNotify.ts).
 */
class PresenceMemo {
    internal val playing = HashMap<String, Long>()
    internal val online = HashMap<String, Long>()

    fun clear() {
        playing.clear()
        online.clear()
    }
}

object PresenceNotify {

    const val REPEAT_MS = 10 * 60_000L

    private const val MAX_NAMED = 2

    /**
     * Going online and starting a game inside one tick is one event. Presence on
     * the site alone is a token hitting the API, not a person, so it never rings.
     */
    fun events(before: List<Friend>, now: List<Friend>, memo: PresenceMemo? = null, at: Long): PresenceEvents {
        val wasPlaying = before.associate { it.userId to it.playing }
        val wasOnline = before.associate { it.userId to it.online }
        val started = now.filter { friend ->
            friend.playing && wasPlaying[friend.userId] == false && !(memo != null && fresh(memo.playing, friend.userId, at))
        }
        val startedIds = started.mapTo(HashSet()) { it.userId }
        val cameOnline = now.filter { friend ->
            friend.online &&
                friend.place != FriendPlace.WEB &&
                wasOnline[friend.userId] == false &&
                friend.userId !in startedIds &&
                !(memo != null && fresh(memo.online, friend.userId, at))
        }
        if (memo != null) {
            remember(memo.playing, now.filter { it.playing }, at)
            remember(memo.online, now.filter { it.online && it.place != FriendPlace.WEB }, at)
        }
        return PresenceEvents(started, cameOnline)
    }

    fun title(list: List<Friend>): String {
        val names = list.map { it.nickname.ifEmpty { tr("Друг") } }
        if (names.size == 1) return names[0]
        val shown = names.take(MAX_NAMED).joinToString(", ")
        return if (names.size > MAX_NAMED) tr("{0} и ещё {1}", shown, names.size - MAX_NAMED) else shown
    }

    fun text(list: List<Friend>, kind: PresenceKind): String {
        if (list.size > 1) {
            val n = list.size
            return if (kind == PresenceKind.PLAY) {
                trPlural(n, tr("{0} друг в игре", n), tr("{0} друга в игре", n), tr("{0} друзей в игре", n))
            } else {
                trPlural(n, tr("{0} друг в сети", n), tr("{0} друга в сети", n), tr("{0} друзей в сети", n))
            }
        }
        if (kind == PresenceKind.ONLINE) return tr("В сети")
        val friend = list[0]
        val where = listOf(friend.serverName, friend.serverIp, friend.build).firstOrNull { !it.isNullOrEmpty() }.orEmpty()
        return if (where.isNotEmpty()) tr("Играет · {where}", where) else tr("Зашёл в игру")
    }

    private fun fresh(memo: Map<String, Long>, userId: String, at: Long): Boolean {
        val ts = memo[userId] ?: return false
        return at - ts < REPEAT_MS
    }

    private fun remember(memo: MutableMap<String, Long>, list: List<Friend>, at: Long) {
        val live = list.mapTo(HashSet()) { it.userId }
        list.forEach { memo[it.userId] = at }
        memo.entries.removeAll { (userId, ts) -> userId !in live && at - ts >= REPEAT_MS }
    }
}
