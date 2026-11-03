package net.millida.launcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import net.millida.launcher.LauncherViewModel

import net.millida.launcher.core.i18n.tr
private val LINK_FAILED_NOTICE: String get() = tr("Ссылка не открылась — поставь или включи браузер")
private val LINK_BROKEN_NOTICE: String get() = tr("Ссылка битая — сообщи в поддержку")

private val WEB_SCHEMES = setOf("http", "https")

internal fun headAvatarUrl(nickname: String): String =
    "https://api.millida.net/v2/heads/avatar/" + Uri.encode(nickname.trim()) + "?size=128"

// Links come from partners and the network: only web pages may leave the launcher,
// never a scheme that starts another app.
internal fun webUri(raw: String?): Uri? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
    return uri.takeIf { it.scheme?.lowercase() in WEB_SCHEMES && !it.host.isNullOrBlank() }
}

internal fun openLink(context: Context, url: String): Boolean {
    val uri = webUri(url) ?: return false
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}

internal fun LauncherViewModel.openLinkOrNotice(context: Context, url: String) {
    if (webUri(url) == null) {
        notice(LINK_BROKEN_NOTICE, isError = true)
        return
    }
    if (!openLink(context, url)) notice(LINK_FAILED_NOTICE, isError = true)
}

fun humanSize(bytes: Long): String = when {
    bytes <= 0 -> tr("пусто")
    bytes < 1024 -> tr("{bytes} Б", bytes)
    bytes < 1024L * 1024 -> tr("{0} КБ", (bytes / 1024.0).toInt())
    bytes < 1024L * 1024 * 1024 -> tr("{0} МБ", String.format(java.util.Locale.ROOT, "%.1f", bytes / 1024.0 / 1024))
    else -> tr("{0} ГБ", String.format(java.util.Locale.ROOT, "%.1f", bytes / 1024.0 / 1024 / 1024))
}

internal fun shotMoment(millis: Long): String {
    if (millis <= 0) return tr("без даты")
    val format = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
    return format.format(java.util.Date(millis))
}
