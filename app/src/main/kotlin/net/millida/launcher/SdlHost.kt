package net.millida.launcher

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLAudioManager
import org.libsdl.app.SDLControllerManager
import java.io.File
import java.lang.ref.WeakReference

/**
 * SDL3's Android half, driven by the game screen instead of an SDLActivity.
 * The library is loaded by the Android runtime first, so its JNI_OnLoad binds
 * to this process's ART and the game's own JVM later opens the very same
 * library through LWJGL. Surface changes arrive in the order SDL's own
 * SurfaceView would send them; SDL then rebuilds its EGL surface itself.
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

    fun surfaceChanged(surface: Surface, width: Int, height: Int) {
        val returning = paused
        this.surface = surface
        this.width = width
        this.height = height
        if (!loaded) return
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

    /**
     * The game thread is asked to let go of the surface first: SDL releases
     * its context on the next event pump and waits up to half a second for it
     * before destroying the EGL surface. It then waits for the resume.
     */
    fun surfaceDestroyed() {
        if (!loaded) {
            surface = null
            return
        }
        SDLActivity.nativeFocusChanged(false)
        if (!paused) {
            paused = true
            SDLActivity.nativePause()
        }
        SDLActivity.onNativeSurfaceDestroyed()
        surface = null
    }

    /** SDL_EVENT_QUIT: lwjgl3ify reports it as a close request and the game saves the world and exits on its own. */
    fun requestQuit(): Boolean {
        if (!loaded) return false
        SDLActivity.nativeSendQuit()
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
}
