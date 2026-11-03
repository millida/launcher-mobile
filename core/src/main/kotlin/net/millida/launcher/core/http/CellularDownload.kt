package net.millida.launcher.core.http

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Downloads over a metered network cost the player money, so a large one is
 * asked about first. Fifty megabytes is where a start stops being a couple of
 * mods and becomes the game itself.
 */
object CellularDownload {
    const val THRESHOLD_BYTES = 50L * 1024 * 1024

    fun needsConsent(bytes: Long): Boolean = bytes > THRESHOLD_BYTES

    /** Whole megabytes, rounded up, so the question never shows less than will be spent. */
    fun megabytes(bytes: Long): Long = ((bytes.coerceAtLeast(0) + MEGABYTE - 1) / MEGABYTE)

    /** Bytes per second as the notification shows it: kilobytes below a megabyte, one decimal above. */
    fun speed(bytesPerSecond: Double): Pair<String, Boolean> {
        val safe = if (bytesPerSecond.isFinite()) bytesPerSecond.coerceAtLeast(0.0) else 0.0
        return if (safe >= MEGABYTE) {
            String.format(Locale.ROOT, "%.1f", safe / MEGABYTE) to true
        } else {
            (safe / 1024).roundToLong().toString() to false
        }
    }

    private const val MEGABYTE = 1024L * 1024
}
