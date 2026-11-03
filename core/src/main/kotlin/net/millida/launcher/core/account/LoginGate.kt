package net.millida.launcher.core.account

import kotlin.random.Random

/**
 * The Millida sign-in is required once, as on the desktop (App.tsx boot, screens/Login):
 * a new player meets it before the lobby and only then picks a licence or an offline
 * nick, while a licence or an offline nick added earlier keeps opening the lobby.
 * The phone has no separate guest kind: a guest plays under an offline «PlayerNNNN»,
 * so the desktop's rule for a guest who never signed in has nothing to catch here.
 */
object LoginGate {
    enum class Start { LOBBY, SIGN_IN, SESSION_LOST, GAME_STEP }

    fun atStart(active: AccountKind?, signedIn: Boolean, firstRun: String, hasGame: Boolean): Start = when {
        active == null -> Start.SIGN_IN
        active == AccountKind.MILLIDA && !signedIn -> Start.SESSION_LOST
        firstRun == FirstRunGameStep.PENDING && !hasGame -> Start.GAME_STEP
        else -> Start.LOBBY
    }

    /** Guest play is a shortcut for someone who signed in before, never a way around the account. */
    fun guestAllowed(millidaEver: Boolean): Boolean = millidaEver

    /** With the last account gone there is nothing left to play with (AccountMenu). */
    fun afterRemoval(remaining: List<Account>): Boolean = remaining.isEmpty()

    fun guestNick(random: Random = Random.Default): String = GUEST_PREFIX + random.nextInt(GUEST_FROM, GUEST_UNTIL)

    private const val GUEST_PREFIX = "Player"
    private const val GUEST_FROM = 1000
    private const val GUEST_UNTIL = 9999
}
