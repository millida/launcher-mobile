package net.millida.launcher.ui.social

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import kotlin.math.max
import kotlin.math.roundToInt

/** The chat upload travels as base64 inside JSON, and the server caps that body at 3 MB. */
private const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024
private const val MAX_SOURCE_BYTES = 40L * 1024 * 1024
private val SIDES = intArrayOf(2048, 1600)
private val JPEG_QUALITIES = intArrayOf(85, 75, 65)
private val PNG_MAGIC = intArrayOf(0x89, 0x50, 0x4E, 0x47)
private val JPEG_MAGIC = intArrayOf(0xFF, 0xD8, 0xFF)
private val RIFF_MAGIC = intArrayOf(0x52, 0x49, 0x46, 0x46)
private val WEBP_MAGIC = intArrayOf(0x57, 0x45, 0x42, 0x50)

/**
 * Chat pictures are loaded only from our own storage: a picture from any other
 * host would tell that host the reader's address.
 */
private val OWN_MEDIA_HOSTS = setOf("cdn.millida.trade", "cdn.millida.net")

internal fun isOwnMediaUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    val host = parsed.host?.lowercase() ?: return false
    return parsed.scheme.equals("https", ignoreCase = true) && host in OWN_MEDIA_HOSTS
}

/**
 * The server takes only PNG, JPEG and WebP: anything else (GIF, HEIC, BMP) or
 * anything too big is re-encoded to JPEG, turned the way the camera held it.
 * A GIF keeps only its first frame.
 */
internal suspend fun readChatImage(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val declared = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
    if (declared > MAX_SOURCE_BYTES) throw IOException("Картинка больше 40 МБ — выбери поменьше")
    val raw = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IOException("Картинка не открылась — выбери другую")
    if (raw.isEmpty()) throw IOException("Файл пустой — выбери другую картинку")
    if (raw.size <= MAX_UPLOAD_BYTES && serverTakesAsIs(raw)) return@withContext raw
    shrink(raw) ?: throw IOException("Эту картинку не получилось сжать — выбери другую")
}

private fun serverTakesAsIs(raw: ByteArray): Boolean = chatPictureType(raw) != null

internal enum class ChatPictureType(val mime: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp"),
}

/** The three formats a chat picture can be in, read from the bytes and never from a name or a header. */
internal fun chatPictureType(raw: ByteArray): ChatPictureType? = when {
    raw.hasBytes(0, PNG_MAGIC) -> ChatPictureType.PNG
    raw.hasBytes(0, JPEG_MAGIC) -> ChatPictureType.JPEG
    raw.hasBytes(0, RIFF_MAGIC) && raw.hasBytes(8, WEBP_MAGIC) -> ChatPictureType.WEBP
    else -> null
}

private fun ByteArray.hasBytes(offset: Int, expected: IntArray): Boolean =
    size >= offset + expected.size && expected.indices.all { this[offset + it] == expected[it].toByte() }

private fun shrink(raw: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val longest = max(bounds.outWidth, bounds.outHeight)
    val degrees = orientationOf(raw)
    for (side in SIDES) {
        val flat = prepared(raw, longest, side, degrees) ?: return null
        try {
            for (quality in JPEG_QUALITIES) {
                val out = ByteArrayOutputStream()
                if (!flat.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                if (out.size() <= MAX_UPLOAD_BYTES) return out.toByteArray()
            }
        } finally {
            flat.recycle()
        }
    }
    return null
}

private fun prepared(raw: ByteArray, longest: Int, side: Int, degrees: Int): Bitmap? {
    var sample = 1
    while (longest / (sample * 2) >= side) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: return null
    val turned = rotate(decoded, degrees)
    val fitted = fit(turned, side)
    val flat = opaque(fitted)
    listOf(decoded, turned, fitted).distinct().filter { it !== flat }.forEach { it.recycle() }
    return flat
}

private fun orientationOf(raw: ByteArray): Int = try {
    val exif = ExifInterface(ByteArrayInputStream(raw))
    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
} catch (unreadable: Exception) {
    0
}

private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun fit(bitmap: Bitmap, limit: Int): Bitmap {
    val side = max(bitmap.width, bitmap.height)
    if (side <= limit) return bitmap
    val scale = limit.toFloat() / side
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

/** JPEG has no transparency: a see-through picture is laid on white instead of turning black. */
private fun opaque(bitmap: Bitmap): Bitmap {
    if (!bitmap.hasAlpha()) return bitmap
    val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    Canvas(flat).apply {
        drawColor(android.graphics.Color.WHITE)
        drawBitmap(bitmap, 0f, 0f, null)
    }
    return flat
}
