package net.millida.launcher.core.friends

/**
 * Status of the presence beat. The server measures the interval between beats
 * itself and credits playtime for every one of them, so "playing" is a claim
 * that costs real hours: a flag stuck on after a launch that never produced a
 * process kept farming them until the daily cap (dark_eremite, 18.08.2026 -
 * 18 h of game time locally against 70 h counted by the site).
 *
 * Hence the rule: only the core knows whether a game process is alive, so
 * without the core the answer is always "lobby", whatever the caller asked for.
 * These rules are shared with the desktop launcher (src/lib/presence.ts) and
 * must stay identical: the site already carried a double-status bug once.
 */
enum class BeatStatus(val wire: String) {
    PLAYING("playing"),
    LOBBY("lobby"),
}

object Presence {

    fun beatStatus(asked: String?, hasSession: Boolean, coreAvailable: Boolean): BeatStatus {
        if (!coreAvailable) return BeatStatus.LOBBY
        if (asked == "playing") return BeatStatus.PLAYING
        val idle = asked.isNullOrEmpty() || asked == "lobby"
        return if (hasSession && idle) BeatStatus.PLAYING else BeatStatus.LOBBY
    }
}
