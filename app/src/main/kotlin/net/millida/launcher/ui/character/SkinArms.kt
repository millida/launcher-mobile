package net.millida.launcher.ui.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import net.millida.launcher.ui.TextureLoad
import net.millida.launcher.ui.rememberTexture

private const val SHEET_UNITS = 64
private const val OPAQUE = 255
private const val ARMS_VARIANT = "arms"

/** Left, top, width, height on the 64 grid: the columns a four-pixel arm paints and a three-pixel arm leaves empty. */
private val SLIM_GAPS: Array<IntArray> = arrayOf(
    intArrayOf(50, 16, 2, 4),
    intArrayOf(54, 20, 2, 12),
    intArrayOf(42, 48, 2, 4),
    intArrayOf(46, 52, 2, 12),
)

/**
 * The texture does not say which arm model it was drawn for, so the empty
 * columns decide: a slim skin leaves them transparent, or flat black or white
 * when the editor could not keep alpha. A 64x32 sheet predates slim arms.
 */
internal fun Bitmap.slimArms(): Boolean? {
    val side = width
    if (side < SHEET_UNITS || side % SHEET_UNITS != 0) return null
    if (height * 2 == side) return false
    if (height != side) return null
    val unit = side / SHEET_UNITS
    val pixels = IntArray(side * side)
    getPixels(pixels, 0, side, 0, 0, side, side)
    val holes = SLIM_GAPS.any { gap -> !pixels.every(side, unit, gap) { Color.alpha(it) == OPAQUE } }
    if (holes) return true
    val black = SLIM_GAPS.all { gap -> pixels.every(side, unit, gap) { it == Color.BLACK } }
    val white = SLIM_GAPS.all { gap -> pixels.every(side, unit, gap) { it == Color.WHITE } }
    return black || white
}

internal fun slimArms(png: ByteArray): Boolean? {
    val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
    return try {
        bitmap.slimArms()
    } finally {
        bitmap.recycle()
    }
}

/** Reads the arm model off the same cached texture the figure draws, so a preview never loads a skin twice. */
@Composable
internal fun rememberSlimArms(url: String?): Boolean? {
    val arms by rememberTexture(url, ARMS_VARIANT) { bitmap -> bitmap.slimArms() }
    return when (val loaded = arms) {
        is TextureLoad.Ready -> loaded.value
        else -> null
    }
}

private inline fun IntArray.every(side: Int, unit: Int, gap: IntArray, test: (Int) -> Boolean): Boolean {
    val left = gap[0] * unit
    val top = gap[1] * unit
    val right = left + gap[2] * unit
    val bottom = top + gap[3] * unit
    for (y in top until bottom) {
        for (x in left until right) {
            if (!test(this[y * side + x])) return false
        }
    }
    return true
}
