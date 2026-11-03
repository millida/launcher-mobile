package net.millida.launcher.ui.shop.chest

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ChestGl"
private const val IDLE_DROP_MS = 60_000L

/**
 * One GL context for every 3D chest on screen (the desktop's chestGl.ts): a
 * dozen chests in the shop must not each hold a context, and a fresh context
 * recompiles every shader, so an emptied shop keeps it for a minute.
 *
 * Each chest draws into its own TextureView surface from the one render
 * thread, paced by that thread's Choreographer.
 */
internal object ChestHost {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var shared: ChestGlShared? = null
    private var appContext: Context? = null
    private val surfaces = LinkedHashMap<Long, ChestSurface>()
    private var ticking = false
    private val main = Handler(Looper.getMainLooper())
    private val rigs = ConcurrentHashMap<String, ChestRigModel>()
    private val broken = ConcurrentHashMap.newKeySet<String>()
    private var failed = false
    private val dropIdle = Runnable { if (surfaces.isEmpty()) shutdownContext() }

    /** False once the device could not give a GL context: chests then stay flat pictures. */
    val available: Boolean get() = !failed

    private fun ensureThread(context: Context): Handler {
        appContext = context.applicationContext
        handler?.let { return it }
        val t = HandlerThread("chest-gl").also { it.start() }
        thread = t
        return Handler(t.looper).also { handler = it }
    }

    fun post(context: Context, block: () -> Unit) {
        ensureThread(context).post(block)
    }

    fun attach(context: Context, id: Long, texture: SurfaceTexture, width: Int, height: Int, scene: ChestGlScene, onFail: () -> Unit) {
        post(context) {
            handler?.removeCallbacks(dropIdle)
            if (!ensureContext()) {
                main.post(onFail)
                return@post
            }
            val surface = try {
                EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            } catch (error: IllegalArgumentException) {
                EGL14.EGL_NO_SURFACE
            }
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "chest surface was refused: 0x" + Integer.toHexString(EGL14.eglGetError()))
                main.post(onFail)
                return@post
            }
            if (EGL14.eglMakeCurrent(display, surface, surface, this.context)) EGL14.eglSwapInterval(display, 0)
            scene.resize(width, height)
            surfaces[id] = ChestSurface(surface, texture, scene, onFail)
            startTicking()
        }
    }

    fun resize(context: Context, id: Long, width: Int, height: Int) = post(context) { surfaces[id]?.scene?.resize(width, height) }

    fun update(context: Context, id: Long, block: (ChestGlScene) -> Unit) = post(context) { surfaces[id]?.let { block(it.scene) } }

    fun pause(context: Context, id: Long, paused: Boolean) = post(context) {
        surfaces[id]?.paused = paused
        if (!paused) startTicking()
    }

    /** The view's texture is released here, after its EGL surface is gone. */
    fun detach(context: Context, id: Long, texture: SurfaceTexture) {
        post(context) {
            val s = surfaces.remove(id)
            if (s != null && display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, this.context)
                EGL14.eglDestroySurface(display, s.egl)
            }
            texture.release()
            if (surfaces.isEmpty()) {
                handler?.removeCallbacks(dropIdle)
                handler?.postDelayed(dropIdle, IDLE_DROP_MS)
            }
        }
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            ticking = false
            val sh = shared ?: return
            val now = frameTimeNanos / 1_000_000.0
            var any = false
            for ((id, s) in surfaces.entries.toList()) {
                if (s.paused) continue
                any = true
                if (!EGL14.eglMakeCurrent(display, s.egl, s.egl, context)) {
                    lost()
                    return
                }
                val drawn = try {
                    s.scene.frame(now, sh)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "chest frame failed", error)
                    surfaces.remove(id)
                    EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                    EGL14.eglDestroySurface(display, s.egl)
                    main.post(s.onFail)
                    continue
                }
                if (drawn && !EGL14.eglSwapBuffers(display, s.egl)) {
                    val code = EGL14.eglGetError()
                    if (code == EGL14.EGL_CONTEXT_LOST) {
                        lost()
                        return
                    }
                }
            }
            if (any) startTicking()
        }
    }

    private fun startTicking() {
        if (ticking || surfaces.isEmpty()) return
        ticking = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /** The context was lost: every chest falls back to its picture, as the desktop does. */
    private fun lost() {
        Log.w(TAG, "chest context lost")
        val fails = surfaces.values.map { it.onFail }
        surfaces.values.forEach { EGL14.eglDestroySurface(display, it.egl) }
        surfaces.clear()
        shared = null
        shutdownContext()
        fails.forEach { main.post(it) }
    }

    private fun ensureContext(): Boolean {
        if (failed) return false
        if (context != EGL14.EGL_NO_CONTEXT && shared != null) return true
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) return fail("no display")
            config = chooseConfig(4) ?: chooseConfig(0) ?: return fail("no config")
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) return fail("no context")
            pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) return fail("make current")
            shared = ChestGlShared(::loadRig)
            return true
        } catch (error: RuntimeException) {
            Log.w(TAG, "chest GL did not start", error)
            return fail(error.message ?: "error")
        }
    }

    private fun fail(why: String): Boolean {
        Log.w(TAG, "chest GL unavailable: $why")
        failed = true
        shutdownContext()
        return false
    }

    private fun chooseConfig(samples: Int): EGLConfig? {
        val attrs = mutableListOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
        )
        if (samples > 0) attrs += listOf(EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, samples)
        attrs += EGL14.EGL_NONE
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, configs, 0, 1, count, 0) || count[0] == 0) return null
        return configs[0]
    }

    private fun shutdownContext() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            try {
                shared?.release()
            } catch (error: RuntimeException) {
                Log.w(TAG, "chest GL release failed", error)
            }
        }
        shared = null
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    /** The Blockbench file of a model from the app's assets, parsed once per run; a broken file stays broken. */
    private fun loadRig(file: String): ChestRigModel? {
        rigs[file]?.let { return it }
        if (file in broken) return null
        val ctx = appContext ?: return null
        return try {
            val text = ctx.assets.open("chests/$file.bbmodel").bufferedReader().use { it.readText() }
            chestRigModel(parseBbModel(text)).also { rigs[file] = it }
        } catch (error: Exception) {
            Log.w(TAG, "chest model $file is broken", error)
            broken += file
            null
        }
    }

    private class ChestSurface(val egl: EGLSurface, val texture: SurfaceTexture, val scene: ChestGlScene, val onFail: () -> Unit) {
        var paused = false
    }
}
