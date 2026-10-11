package net.millida.launcher.runtime

import android.view.Surface
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

object RuntimeBridge {
    const val EVENT_CURSOR = 1
    const val EVENT_MOUSE_BUTTON = 2
    const val EVENT_KEY = 3
    const val EVENT_CHAR = 4
    const val EVENT_SCROLL = 5
    const val EVENT_WINDOW_SIZE = 6
    const val EVENT_FOCUS = 7
    const val EVENT_CURSOR_DELTA = 8
    const val EVENT_CLOSE = 9

    private val loaded = AtomicBoolean(false)
    private val attached = AtomicBoolean(false)

    fun load() {
        if (loaded.get()) return
        synchronized(this) {
            if (loaded.get()) return
            System.loadLibrary("millida_bridge")
            loaded.set(true)
        }
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        load()
        nativeAttachSurface(surface, width, height)
        attached.set(true)
    }

    fun detachSurface() {
        if (!loaded.get()) return
        attached.set(false)
        nativeDetachSurface()
    }

    /** The game builds its context from the surface, so starting before one exists means a black screen. */
    suspend fun awaitSurface(timeoutMs: Long = 15_000): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (attached.get()) return true
            delay(100)
            waited += 100
        }
        return attached.get()
    }

    fun pushEvent(kind: Int, a: Int = 0, b: Int = 0, c: Int = 0, d: Int = 0) {
        if (!loaded.get()) return
        nativePushEvent(kind, a, b, c, d)
    }

    fun running(): Boolean = loaded.get() && nativeIsRunning()

    fun requestClose() {
        if (!loaded.get()) return
        nativePushEvent(EVENT_CLOSE, 0, 0, 0, 0)
    }

    /**
     * Android's reserved signals 33-41 left at their default disposition kill
     * the game; called around every step that loads native code, and the
     * watcher it starts keeps checking for the rest of the process.
     */
    fun guardSignals(moment: String) {
        load()
        nativeGuardSignals(moment)
    }

    fun putEnv(key: String, value: String) {
        load()
        nativePutEnv(key, value)
    }

    fun startGame(
        javaHome: String,
        jvmArgs: Array<String>,
        mainClass: String,
        gameArgs: Array<String>,
        runtimeDir: String,
        workDir: String,
        logPath: String,
    ): Boolean {
        load()
        return nativeStartGame(javaHome, jvmArgs, mainClass, gameArgs, runtimeDir, workDir, logPath)
    }

    private external fun nativeAttachSurface(surface: Surface, width: Int, height: Int)
    private external fun nativeDetachSurface()
    private external fun nativePushEvent(kind: Int, a: Int, b: Int, c: Int, d: Int)
    private external fun nativeIsRunning(): Boolean
    private external fun nativePutEnv(key: String, value: String)
    private external fun nativeGuardSignals(moment: String)
    private external fun nativeStartGame(
        javaHome: String,
        jvmArgs: Array<String>,
        mainClass: String,
        gameArgs: Array<String>,
        runtimeDir: String,
        workDir: String,
        logPath: String,
    ): Boolean
}
