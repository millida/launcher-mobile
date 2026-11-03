package net.millida.launcher

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import net.millida.launcher.core.runtime.BootPicture
import net.millida.launcher.core.runtime.Picture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Reads the game's surface back, scaled down to a handful of pixels: whoever draws into
 * it — the pack's window layer, SDL, any renderer — what the player sees is what is in
 * the surface, and nothing else tells a loading game from a black screen.
 */
internal class PictureProbe {
    // No alpha channel on purpose: the game leaves the alpha of its frames at zero, the screen
    // ignores it, and a copy that kept it would read every pixel back as transparent black.
    private val bitmap = Bitmap.createBitmap(BootPicture.SAMPLE_WIDTH, BootPicture.SAMPLE_HEIGHT, Bitmap.Config.RGB_565)
    private val pixels = IntArray(BootPicture.SAMPLE_WIDTH * BootPicture.SAMPLE_HEIGHT)
    private val handler = Handler(Looper.getMainLooper())

    // Up to Android 12 the copy is made on the calling thread and waits for the system's
    // drawing thread, which a loading game keeps busy: it must never be the main one.
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "millida-picture").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)

    suspend fun look(surface: Surface?): Picture {
        if (surface == null || !surface.isValid) return Picture.HIDDEN
        // A copy that never came back must not pile more of them up behind itself.
        if (!busy.compareAndSet(false, true)) return Picture.UNKNOWN
        return withTimeoutOrNull(LOOK_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                worker.execute {
                    try {
                        PixelCopy.request(surface, bitmap, { result ->
                            val picture = read(result)
                            busy.set(false)
                            if (continuation.isActive) continuation.resume(picture)
                        }, handler)
                    } catch (gone: IllegalArgumentException) {
                        // The surface was taken away between the check and the request.
                        busy.set(false)
                        if (continuation.isActive) continuation.resume(Picture.HIDDEN)
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "картинка игры не прочиталась: ${error.message}")
                        busy.set(false)
                        if (continuation.isActive) continuation.resume(Picture.UNKNOWN)
                    }
                }
            }
        } ?: Picture.UNKNOWN
    }

    fun close() {
        worker.shutdown()
    }

    private fun read(result: Int): Picture = when (result) {
        PixelCopy.SUCCESS -> {
            bitmap.getPixels(pixels, 0, BootPicture.SAMPLE_WIDTH, 0, 0, BootPicture.SAMPLE_WIDTH, BootPicture.SAMPLE_HEIGHT)
            BootPicture.of(pixels)
        }
        PixelCopy.ERROR_SOURCE_NO_DATA -> Picture.DARK
        PixelCopy.ERROR_SOURCE_INVALID -> Picture.HIDDEN
        else -> Picture.UNKNOWN
    }

    private companion object {
        const val TAG = "millida-game"
        const val LOOK_TIMEOUT_MS = 2_000L
    }
}
