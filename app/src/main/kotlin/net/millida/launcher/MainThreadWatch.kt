package net.millida.launcher

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Where the screen thread of the game process stands when it stops answering.
 *
 * «Millida не отвечает» came up on 381 installs in a day (13.10.2026) and Android
 * kept no thread dump for any of them: the desktop virtual machine takes SIGQUIT,
 * the signal the system asks for a dump with. So the process asks its own screen
 * thread to answer once a second, and when four requests in a row go unanswered
 * it reads that thread's stack — a second before the system's five-second limit
 * for a touch, while the stack still shows the call that holds it.
 *
 * Counting unanswered requests instead of the time since the last answer keeps a
 * process the system froze in the background from looking stuck when it wakes.
 */
class MainThreadWatch(private val onStuck: (stuckMs: Long, trace: Array<StackTraceElement>) -> Unit) {
    private val main = Looper.getMainLooper()
    private val handler = Handler(main)
    private val answered = AtomicBoolean(true)
    private val answer = Runnable { answered.set(true) }

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "millida-watch", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
    }

    private fun loop() {
        var missed = 0
        var reports = 0
        while (running) {
            if (answered.getAndSet(false)) {
                missed = 0
                handler.post(answer)
            } else {
                missed += 1
                if (missed == STUCK_TICKS && reports < MAX_REPORTS) {
                    reports += 1
                    runCatching { onStuck(missed * TICK_MS, main.thread.stackTrace) }
                }
            }
            try {
                Thread.sleep(TICK_MS)
            } catch (interrupted: InterruptedException) {
                return
            }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val STUCK_TICKS = 4
        const val MAX_REPORTS = 3
    }
}
