package net.millida.launcher.core.servers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * A list of thirty servers used to open thirty sockets at once and ask each of
 * them again on every scroll. The answers are held for a short while, the same
 * address is never asked twice at the same moment, and only a few sockets are
 * open at a time.
 */
class ServerStatusCache(
    private val ttlMs: Long = 30_000,
    concurrency: Int = 4,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pinger: suspend (String) -> ServerStatus = { ServerPing.ping(it) },
) {

    private data class Entry(val status: ServerStatus, val at: Long)

    private val gate = Semaphore(concurrency.coerceAtLeast(1))
    private val lock = Mutex()
    private val fresh = HashMap<String, Entry>()
    private val inFlight = HashMap<String, CompletableDeferred<ServerStatus?>>()

    fun cached(address: String): ServerStatus? {
        val entry = synchronized(fresh) { fresh[address] } ?: return null
        return entry.status.takeIf { clock() - entry.at < ttlMs }
    }

    /** Returns null when the server did not answer: silence is not a status. */
    suspend fun status(address: String, refresh: Boolean = false): ServerStatus? {
        if (!refresh) cached(address)?.let { return it }

        val waiter = lock.withLock {
            inFlight[address]?.let { return@withLock it to false }
            val started = CompletableDeferred<ServerStatus?>()
            inFlight[address] = started
            started to true
        }
        val (deferred, mine) = waiter
        if (!mine) return deferred.await()

        val answer = try {
            gate.withPermit { pinger(address) }
        } catch (silence: Throwable) {
            null
        }
        if (answer != null) {
            synchronized(fresh) { fresh[address] = Entry(answer, clock()) }
        }
        lock.withLock { inFlight.remove(address) }
        deferred.complete(answer)
        return answer
    }

    /** The whole list at once, still under the same few sockets. */
    suspend fun statuses(addresses: List<String>, refresh: Boolean = false): Map<String, ServerStatus> {
        val answers = HashMap<String, ServerStatus>()
        coroutineScope {
            addresses.distinct().forEach { address ->
                launch {
                    status(address, refresh)?.let { answer ->
                        synchronized(answers) { answers[address] = answer }
                    }
                }
            }
        }
        return answers
    }

    fun forget(address: String) {
        synchronized(fresh) { fresh.remove(address) }
    }

    fun clear() {
        synchronized(fresh) { fresh.clear() }
    }
}
