package net.millida.launcher.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.servers.ServerStatus
import net.millida.launcher.core.servers.ServerStatusCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A list of servers must not turn into a socket storm. Every rule that keeps it
 * quiet is pinned: answers are held for a while, the same address is asked once
 * at a time, only a few sockets are open at once, and silence is never
 * remembered as an answer.
 */
class ServerStatusCacheTest {

    private fun status(online: Int) = ServerStatus(
        online = online,
        max = 100,
        version = "1.21.4",
        motd = "Millida",
        iconBase64 = null,
        latencyMs = 20,
    )

    @Test
    fun `ответ держится и не запрашивается заново`() = runBlocking {
        val calls = AtomicInteger()
        val cache = ServerStatusCache(ttlMs = 10_000, clock = { 1_000 }) {
            calls.incrementAndGet()
            status(7)
        }

        val first = cache.status("play.millida.net")
        val second = cache.status("play.millida.net")

        assertEquals("второй запрос отвечен из памяти", 1, calls.get())
        assertEquals(first, second)
        assertEquals("состояние доступно без сети", 7, cache.cached("play.millida.net")?.online)
    }

    @Test
    fun `просроченный ответ спрашивается заново`() = runBlocking {
        val calls = AtomicInteger()
        var now = 0L
        val cache = ServerStatusCache(ttlMs = 5_000, clock = { now }) {
            calls.incrementAndGet()
            status(calls.get())
        }

        cache.status("srv")
        now = 4_000
        cache.status("srv")
        assertEquals("внутри срока ответ прежний", 1, calls.get())

        now = 6_000
        val fresh = cache.status("srv")
        assertEquals("после срока сервер опрошен снова", 2, calls.get())
        assertEquals("вернулось свежее состояние", 2, fresh?.online)
        assertEquals("свежий ответ занял место старого", 2, cache.cached("srv")?.online)

        now = 12_000
        assertNull("протухшее состояние не выдаётся как готовое", cache.cached("srv"))
        assertEquals("но и лишнего запроса при этом не случилось", 2, calls.get())
    }

    @Test
    fun `один и тот же адрес не опрашивается двумя сокетами разом`() = runBlocking {
        val calls = AtomicInteger()
        val cache = ServerStatusCache(ttlMs = 10_000) {
            calls.incrementAndGet()
            delay(50)
            status(1)
        }

        val answers = coroutineScope {
            List(8) { async { cache.status("srv") } }.awaitAll()
        }

        assertEquals("восемь карточек — один запрос", 1, calls.get())
        assertTrue("все получили ответ", answers.all { it != null })
    }

    @Test
    fun `больше разрешённого числа сокетов одновременно не открывается`() = runBlocking {
        val now = AtomicInteger()
        val peak = AtomicInteger()
        val cache = ServerStatusCache(ttlMs = 10_000, concurrency = 3) {
            val current = now.incrementAndGet()
            peak.updateAndGet { best -> maxOf(best, current) }
            delay(30)
            now.decrementAndGet()
            status(1)
        }

        val list = (1..20).map { "srv-$it.millida.net" }
        val answers = cache.statuses(list)

        assertEquals("ответили все двадцать", 20, answers.size)
        assertTrue("одновременных опросов не больше трёх, было ${peak.get()}", peak.get() <= 3)
    }

    @Test
    fun `молчание сервера не запоминается как ответ`() = runBlocking {
        val calls = AtomicInteger()
        val cache = ServerStatusCache(ttlMs = 10_000) {
            calls.incrementAndGet()
            throw IOException("сервер не ответил")
        }

        assertNull("молчание — это не состояние", cache.status("dead"))
        assertNull("и в память оно не попадает", cache.cached("dead"))
        cache.status("dead")
        assertEquals("следующий заход снова пробует достучаться", 2, calls.get())
    }
}
