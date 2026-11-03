package net.millida.launcher

import android.os.Process
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Where the window layer reports that the Java machine exited or aborted.
 *
 * The call never returns: behind it stands the C library's exit, which must not run in
 * this process, or the aborted thread, which has nothing to go back to. The end is
 * written down, the screen leaves, and the process is ended from here, because a Java
 * machine left half-alive keeps its sound, its connection to the server and the
 * processor for itself.
 */
object ExitTrap {
    private const val TAG = "millida-game"
    private const val LEAVE_WAIT_MS = 2_000L

    private val tripped = AtomicBoolean(false)

    @Volatile
    private var listener: ((code: Int, signal: Boolean) -> GameResult?)? = null

    @Volatile
    private var screen: WeakReference<GameActivity>? = null

    /** [listener] writes the end down and answers with what to show after a crash, or null to just leave. */
    fun watch(listener: (code: Int, signal: Boolean) -> GameResult?) {
        this.listener = listener
    }

    fun attach(activity: GameActivity) {
        screen = WeakReference(activity)
    }

    fun detach(activity: GameActivity) {
        if (screen?.get() === activity) screen = null
    }

    @JvmStatic
    fun trapped(code: Int, signal: Boolean) {
        // An abort during an exit, or the other way round: the first one is already ending the process.
        if (!tripped.compareAndSet(false, true)) parkForever()
        val result = runCatching { listener?.invoke(code, signal) }
            .onFailure { Log.e(TAG, "конец игры не записан", it) }
            .getOrNull()
        leave(result)
        Process.killProcess(Process.myPid())
        parkForever()
    }

    private fun leave(result: GameResult?) {
        val activity = screen?.get() ?: return
        val left = CountDownLatch(1)
        activity.runOnUiThread {
            runCatching { activity.end(result) }
                .onFailure { Log.e(TAG, "экран игры не закрылся", it) }
            left.countDown()
        }
        if (!left.await(LEAVE_WAIT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "экран игры не успел закрыться")
    }

    private fun parkForever(): Nothing {
        while (true) LockSupport.park()
    }
}
