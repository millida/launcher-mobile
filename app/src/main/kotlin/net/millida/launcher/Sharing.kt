package net.millida.launcher

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Anything the player sends out of the launcher - a log for support, a
 * screenshot, an exported world - leaves as a grant on our own file, never as a
 * raw path: another application cannot read our storage directly.
 */
fun shareFile(context: Context, file: File, mime: String, title: String): Boolean {
    if (!file.isFile) return false
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }.getOrNull() ?: return false
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching {
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/**
 * The system installer reads the package itself, so it needs the same grant.
 * Without the flag the install screen opens on an empty file and blames us.
 */
fun installApk(context: Context, apk: File): Boolean {
    if (!apk.isFile) return false
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
    }.getOrNull() ?: return false
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}
