package net.millida.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import net.millida.launcher.core.runtime.DeviceGraphics
import net.millida.launcher.core.runtime.GpuFamily

object GraphicsProbe {
    private const val VULKAN_1_1 = 0x00401000

    @Volatile
    private var cached: DeviceGraphics? = null

    fun read(context: Context): DeviceGraphics = cached ?: probe(context).also { if (it.gpu != GpuFamily.UNKNOWN) cached = it }

    private fun probe(context: Context): DeviceGraphics {
        val names = runCatching { glNames() }.getOrNull()
        val vulkan11 = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_1)
        return DeviceGraphics(GpuFamily.of(names?.first, names?.second), vulkan11)
    }

    private fun glNames(): Pair<String?, String?>? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val wanted = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
        val config = configs[0] ?: return null
        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) return null
        val surface = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        // The default display is not terminated: the system's own drawing shares it in this process.
        return try {
            if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                null
            } else {
                GLES20.glGetString(GLES20.GL_VENDOR) to GLES20.glGetString(GLES20.GL_RENDERER)
            }
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }
}
