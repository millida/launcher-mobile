package net.millida.launcher.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.shareFile
import net.millida.launcher.ui.media.GalleryError
import net.millida.launcher.ui.media.saveToGallery
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

internal const val SAVED_PICTURE_NOTICE = "Сохранено: Pictures/Millida"

private const val MAX_PICTURE_BYTES = 10 * 1024 * 1024
private const val PICTURE_DIR = "chat-pictures"

private class PictureError(message: String) : IOException(message)

/**
 * A redirect is never followed: the address was checked against our own
 * storage, and a hop elsewhere would hand the reader's address to that host.
 */
private val pictureClient: OkHttpClient by lazy {
    Downloader.shared().newBuilder().followRedirects(false).followSslRedirects(false).build()
}

/** The desktop "Копировать картинку": the picture itself goes to the clipboard, as a grant on our own file. */
internal suspend fun copyChatPicture(context: Context, url: String) {
    val (file) = fetchChatPicture(context, url)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val clipboard = context.getSystemService(ClipboardManager::class.java)
        ?: throw PictureError("Буфер обмена недоступен — сохрани картинку вместо копирования")
    clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Картинка", uri))
}

/**
 * Android 10 and newer take the picture into the gallery without any
 * permission; older ones get the system share sheet, where the player picks
 * the gallery or a folder, as the screenshot viewer does.
 */
internal suspend fun saveChatPicture(context: Context, url: String): Boolean {
    val (file, type) = fetchChatPicture(context, url)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveToGallery(context, file, file.name, type.mime)
        return true
    }
    if (!shareFile(context, file, type.mime, "Сохранить картинку")) {
        throw PictureError("Не нашлось приложения, куда сохранить картинку — поставь галерею или файловый менеджер")
    }
    return false
}

private data class FetchedPicture(val file: File, val type: ChatPictureType)

internal suspend fun pictureFailure(block: suspend () -> Unit): String? = try {
    block()
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (known: PictureError) {
    known.message
} catch (gallery: GalleryError) {
    gallery.message
} catch (network: IOException) {
    "Картинка не скачалась — проверь интернет и повтори"
} catch (other: Exception) {
    "Картинка не сохранилась — повтори ещё раз"
}

private suspend fun fetchChatPicture(context: Context, url: String): FetchedPicture = withContext(Dispatchers.IO) {
    if (!isOwnMediaUrl(url)) throw PictureError("Эта картинка не из чата Millida — сохранить её нельзя")
    val bytes = pictureClient.newCall(Request.Builder().url(url.trim()).build()).execute().use { response ->
        if (!response.isSuccessful) throw PictureError("Картинка не скачалась: сервер ответил ${response.code} — повтори позже")
        val body = response.body ?: throw PictureError("Картинка пришла пустой — повтори")
        if (body.contentLength() > MAX_PICTURE_BYTES) throw PictureError("Картинка больше 10 МБ — сохранить её нельзя")
        body.byteStream().use { it.readLimited() }
    }
    val type = chatPictureType(bytes) ?: throw PictureError("Это не картинка — сохранить нельзя")
    val dir = File(context.cacheDir, PICTURE_DIR).apply { mkdirs() }
    val target = File(dir, "millida-" + Integer.toHexString(url.trim().hashCode()) + "." + type.extension)
    val temp = File(dir, target.name + ".part")
    try {
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw PictureError("Не хватило места, чтобы подготовить картинку — освободи память")
        }
    } finally {
        temp.delete()
    }
    FetchedPicture(target, type)
}

private fun InputStream.readLimited(): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        if (out.size() > MAX_PICTURE_BYTES) throw PictureError("Картинка больше 10 МБ — сохранить её нельзя")
    }
    return out.toByteArray()
}
