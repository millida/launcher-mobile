package net.millida.launcher

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import net.millida.launcher.core.i18n.tr
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLAudioManager
import org.libsdl.app.SDLControllerManager
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SDL3's Android half, driven by the game screen instead of an SDLActivity.
 * The library is loaded by the Android runtime first, so its JNI_OnLoad binds
 * to this process's ART and the game's own JVM later opens the very same
 * library through LWJGL. Surface changes arrive in the order SDL's own
 * SurfaceView would send them; SDL then rebuilds its EGL surface itself.
 *
 * No call into SDL is made on the screen thread. Every entry of its Android
 * half takes one lock, and the game thread holds that lock for the whole of
 * a buffer swap (Android_GLES_SwapWindow). A swap that stalls — a heavy world,
 * a screen that went off, a surface on its way out — therefore stopped the
 * screen thread on the next touch, and five seconds later the system showed
 * «Millida не отвечает» (13.10.2026: 105 installs a day on this window layer).
 * One thread of our own makes the calls in the order they were asked for; a
 * surface change waits for it, but only so long.
 */
object SdlHost : SDLActivity.Host {

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    var loaded = false
        private set

    @Volatile
    private var windowOpenedAt = 0L

    @Volatile
    private var relativeListener: ((Boolean) -> Unit)? = null

    @Volatile
    var relative = false
        private set

    @Volatile
    private var paused = false

    @Volatile
    private var width = 0

    @Volatile
    private var height = 0

    private val calls: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "millida-sdl").apply { isDaemon = true }
    }

    /** A call into SDL, made in turn on the thread that owns them; the caller does not wait. */
    fun post(name: String, block: () -> Unit) {
        calls.execute {
            runCatching(block).onFailure { android.util.Log.w("millida-input", "SDL: $name не прошло: ${it.message}") }
        }
    }

    /** As [post], but the caller waits up to [waitMs]; false when SDL was still busy by then. */
    private fun call(name: String, waitMs: Long, block: () -> Unit): Boolean {
        val done = CountDownLatch(1)
        calls.execute {
            try {
                block()
            } catch (failure: Throwable) {
                android.util.Log.w("millida-input", "SDL: $name не прошло: ${failure.message}")
            } finally {
                done.countDown()
            }
        }
        return runCatching { done.await(waitMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun detachActivity(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    fun onRelativeMouse(listener: ((Boolean) -> Unit)?) {
        relativeListener = listener
    }

    /**
     * After this SDL is ready for SDL_Init from the game: all three Java halves
     * have set themselves up, which is what SDL waits for before it agrees to
     * start on Android.
     */
    fun load(library: File, note: (String) -> Unit): String {
        SDLActivity.install(this)
        System.load(library.absolutePath)
        SDLActivity.nativeSetupJNI()
        SDLAudioManager.nativeSetupJNI()
        SDLControllerManager.nativeSetupJNI()
        loaded = true
        val version = SDLActivity.nativeGetVersion()
        note("SDL $version подключён к экрану игры")
        if (width > 0 && height > 0) announceSize()
        return version
    }

    /** False when SDL did not take the surface in time: it will, once the game thread lets go of its lock. */
    fun surfaceChanged(surface: Surface, width: Int, height: Int): Boolean {
        this.surface = surface
        this.width = width
        this.height = height
        if (!loaded) return true
        return call(tr("поверхность"), SURFACE_WAIT_MS) {
            val returning = paused
            if (returning) SDLActivity.onNativeSurfaceCreated()
            announceSize()
            SDLActivity.onNativeSurfaceChanged()
            SDLActivity.onNativeResize()
            if (returning) {
                paused = false
                SDLActivity.nativeResume()
            }
            SDLActivity.nativeFocusChanged(true)
        }
    }

    /**
     * The game thread is asked to let go of the surface first: SDL releases
     * its context on the next event pump and waits up to half a second for it
     * before destroying the EGL surface. It then waits for the resume.
     *
     * False when the game thread did not let go in time. The system then takes
     * the surface away regardless, which is what ends a swap stuck on it, and
     * SDL finishes the release after that.
     */
    fun surfaceDestroyed(): Boolean {
        val leaving = surface
        if (!loaded) {
            surface = null
            return true
        }
        return call(tr("поверхность"), RELEASE_WAIT_MS) {
            SDLActivity.nativeFocusChanged(false)
            if (!paused) {
                paused = true
                SDLActivity.nativePause()
            }
            SDLActivity.onNativeSurfaceDestroyed()
            // A new surface may have arrived while SDL was busy with this one.
            if (surface === leaving) surface = null
        }
    }

    /** SDL_EVENT_QUIT: lwjgl3ify reports it as a close request and the game saves the world and exits on its own. */
    fun requestQuit(): Boolean {
        if (!loaded) return false
        post("закрытие") { SDLActivity.nativeSendQuit() }
        return true
    }

    /** The game has had its window long enough to draw the first frames. */
    fun windowShown(afterMs: Long): Boolean {
        val opened = windowOpenedAt
        return opened > 0 && SystemClock.elapsedRealtime() - opened >= afterMs
    }

    private fun announceSize() {
        val metrics = activityRef?.get()?.resources?.displayMetrics
        val density = metrics?.density ?: 1f
        SDLActivity.nativeSetScreenResolution(width, height, width, height, density, refreshRate())
    }

    @Suppress("DEPRECATION")
    private fun refreshRate(): Float {
        val activity = activityRef?.get() ?: return 60f
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay
        return display?.refreshRate ?: 60f
    }

    override fun activity(): Activity? = activityRef?.get()

    override fun surface(): Surface? = surface

    override fun relativeMouse(enabled: Boolean) {
        relative = enabled
        relativeListener?.invoke(enabled)
    }

    override fun windowOpened() {
        if (windowOpenedAt == 0L) windowOpenedAt = SystemClock.elapsedRealtime()
    }

    private const val SURFACE_WAIT_MS = 1_000L

    /** SDL itself waits half a second for the game thread here; past twice that the game thread is stuck. */
    private const val RELEASE_WAIT_MS = 1_500L
}
