package net.millida.launcher.core.account

/**
 * Right after a Millida sign-in a new player adds a licence or an offline nick to
 * play with: the Millida login itself is no longer handed out as a game account
 * (desktop state/firstRun.ts). A player who signed in to Millida before keeps playing
 * as they did; a step left unfinished comes back even though that sign-in already
 * marked them as returning.
 */
object FirstRunGameStep {
    const val PENDING = "game"
    const val DONE = "1"

    fun due(returning: Boolean, state: String, hasGame: Boolean): Boolean {
        if (hasGame) return false
        if (state == PENDING) return true
        return !returning && state != DONE
    }

    fun isGameAccount(kind: AccountKind): Boolean = kind == AccountKind.MICROSOFT || kind == AccountKind.OFFLINE
}
