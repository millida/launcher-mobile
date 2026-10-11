package net.millida.launcher.ui.catalog

import java.util.Locale

private val HTML_BREAK = Regex("(?i)<\\s*(br|/p|/div|/li|/h[1-6])\\s*/?>")
private val HTML_TAG = Regex("<[^>]+>")
private val MD_IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
private val MD_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
private val MD_HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
private val MD_QUOTE = Regex("(?m)^\\s{0,3}>\\s?")
private val MD_RULE = Regex("(?m)^\\s*([-*_]\\s*){3,}$")
private val MD_MARKS = Regex("(\\*\\*|__|~~|`{1,3})")
private val BLANK_LINES = Regex("\\n{3,}")
private val SPACES = Regex("[ \\t\\u00A0]{2,}")

private const val PLAIN_LIMIT = 4000

/**
 * Project pages arrive as Markdown (Modrinth) or HTML (CurseForge). The sheet
 * shows words, not markup: pictures and link targets go, their text stays.
 */
internal fun plainText(raw: String): String {
    if (raw.isBlank()) return ""
    val text = raw
        .replace("\r\n", "\n")
        .replace(HTML_BREAK, "\n")
        .replace(HTML_TAG, "")
        .replace(MD_IMAGE, "")
        .replace(MD_LINK, "$1")
        .replace(MD_HEADING, "")
        .replace(MD_QUOTE, "")
        .replace(MD_RULE, "")
        .replace(MD_MARKS, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(SPACES, " ")
        .replace(BLANK_LINES, "\n\n")
        .trim()
    return if (text.length > PLAIN_LIMIT) text.take(PLAIN_LIMIT).trimEnd() + "…" else text
}

internal fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.ROOT, "%.1f", value / 1_000_000.0).replace('.', ',').removeSuffix(",0") + " млн"
    value >= 1_000 -> "${value / 1_000} тыс."
    else -> value.coerceAtLeast(0).toString()
}
