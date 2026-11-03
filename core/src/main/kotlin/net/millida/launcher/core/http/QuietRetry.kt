package net.millida.launcher.core.http

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * A read that may be asked again without harm: a timeout or a dropped link is tried once more,
 * quietly, before anyone is told. A refusal of the request itself (4xx other than 408/429) is not
 * a reason to wait, and neither is a failure of reading the answer.
 */
suspend fun <T> quietRetry(times: Int = 2, pauseMs: Long = 1_500L, block: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            val refused = generateSequence<Throwable>(error) { it.cause }.take(6)
                .any { it is net.millida.launcher.core.account.Unauthorized || it is HttpError && it.code in 400..499 && it.code != 408 && it.code != 429 }
            if (refused || attempt >= times) throw error
            attempt++
            delay(pauseMs * attempt)
        }
    }
}
