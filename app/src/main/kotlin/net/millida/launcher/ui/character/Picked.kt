package net.millida.launcher.ui.character

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import net.millida.launcher.core.skins.TexturePng
import java.io.ByteArrayOutputStream
import java.io.File

private const val FITTING_DIR = "fitting"
private const val FITTING_KEEP = 4
private const val READ_CHUNK = 16 * 1024

/** Stops one byte past [limit], so an oversized pick fails the size check instead of filling memory. */
internal fun readPicked(context: Context, uri: Uri, limit: Int = TexturePng.MAX_BYTES): ByteArray? = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        while (out.size() <= limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }?.takeIf { it.isNotEmpty() }
} catch (unreadable: Exception) {
    null
}

internal fun pickedName(context: Context, uri: Uri, fallback: String): String {
    val shown = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (unreadable: Exception) {
        null
    }
    val base = shown ?: uri.lastPathSegment?.substringAfterLast('/')
    return base.orEmpty().substringBeforeLast('.').trim().take(60).ifEmpty { fallback }
}

/**
 * The figure loads textures by address, so a picked picture is written to the
 * cache under a fresh name first; an old name would hand back the cached image
 * of the previous pick.
 */
internal fun stageFile(context: Context, bytes: ByteArray): File? = try {
    val dir = File(context.cacheDir, FITTING_DIR)
    if (!dir.isDirectory) dir.mkdirs()
    dir.listFiles().orEmpty()
        .sortedByDescending { it.lastModified() }
        .drop(FITTING_KEEP)
        .forEach { it.delete() }
    File(dir, "skin-${System.currentTimeMillis()}.png").also { it.writeBytes(bytes) }
} catch (unwritable: Exception) {
    null
}
