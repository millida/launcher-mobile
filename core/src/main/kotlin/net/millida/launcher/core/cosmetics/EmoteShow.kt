package net.millida.launcher.core.cosmetics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a lobby holds an emote on the figure. The stage starts an emote from
 * its first frame every time the outfit is rebuilt (the worn pieces or the arms
 * arrive while it plays), so the wait follows the play the stage is showing
 * rather than the moment the first one appeared.
 */
object EmoteShow {
    /**
     * Waits up to [buildWaitMs] for a play other than [stale], then until one has
     * run [lengthMs] without being replaced. Returns the play that ran through, or
     * null when none was built in time.
     */
    suspend fun <T : Any> playThrough(plays: Flow<T?>, stale: T?, buildWaitMs: Long, lengthMs: (T) -> Long): T? {
        var play = withTimeoutOrNull(buildWaitMs) { plays.filterNotNull().first { it !== stale } } ?: return null
        while (true) {
            val running = play
            play = withTimeoutOrNull(lengthMs(running)) { plays.filterNotNull().first { it !== running } } ?: return running
        }
    }
}
