package net.millida.launcher.ui.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.millida.launcher.core.media.Screenshot
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val GALLERY_FOLDER = "Millida"
private const val DAY_MS = 24L * 60 * 60 * 1000
private val RUSSIAN: Locale = Locale.forLanguageTag("ru")

internal val Screenshot.key: String get() = "$profileId/$name"

internal class GalleryError(message: String) : IOException(message)

/**
 * The public gallery on Android 10 and newer takes files through MediaStore
 * without any storage permission. The row stays pending until the copy is
 * whole, and a failed copy removes it, so the gallery never shows half a frame.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal suspend fun saveToGallery(
    context: Context,
    source: File,
    name: String,
    mime: String = "image/png",
) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + GALLERY_FOLDER)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val row = resolver.insert(collection, values)
        ?: throw GalleryError("Галерея не приняла снимок — проверь свободное место")
    try {
        val output = resolver.openOutputStream(row)
            ?: throw GalleryError("Галерея не открылась на запись — повтори через минуту")
        output.use { target -> source.inputStream().use { it.copyTo(target) } }
        val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        resolver.update(row, ready, null, null)
    } catch (error: Throwable) {
        resolver.delete(row, null, null)
        throw error
    }
}

/** Before Android 10 the gallery needs a storage permission, so the player picks the place in the system dialog. */
internal suspend fun copyToDocument(context: Context, source: File, target: Uri) = withContext(Dispatchers.IO) {
    val output = context.contentResolver.openOutputStream(target)
        ?: throw GalleryError("Файл не открылся на запись — выбери другую папку")
    output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
}

internal suspend fun failureOf(block: suspend () -> Unit): String? = try {
    block()
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: GalleryError) {
    error.message
} catch (error: Throwable) {
    "Снимок не сохранился — проверь свободное место и повтори"
}

internal fun startOfDay(millis: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = millis
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

internal fun dayLabel(day: Long, now: Long = System.currentTimeMillis()): String {
    if (day <= 0L) return "Без даты"
    val days = Math.round((startOfDay(now) - day).toDouble() / DAY_MS)
    if (days == 0L) return "Сегодня"
    if (days == 1L) return "Вчера"
    val sameYear = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR) ==
        Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.YEAR)
    val pattern = if (sameYear) "d MMMM" else "d MMMM yyyy"
    return SimpleDateFormat(pattern, RUSSIAN).format(Date(day))
}

internal fun shotsCount(count: Int): String {
    val tens = count % 100
    val ones = count % 10
    val word = when {
        tens in 11..14 -> "снимков"
        ones == 1 -> "снимок"
        ones in 2..4 -> "снимка"
        else -> "снимков"
    }
    return "$count $word"
}
