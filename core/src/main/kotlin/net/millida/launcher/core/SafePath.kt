package net.millida.launcher.core

import java.io.File
import java.io.IOException

object SafePath {
    private val ILLEGAL_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    fun fileName(raw: String): String {
        val trimmed = raw.trim().trimEnd('.', ' ')
        val cleaned = buildString(trimmed.length) {
            for (ch in trimmed) {
                append(if (ch in ILLEGAL_CHARS || ch.isWhitespace() || ch.code < 0x20) '_' else ch)
            }
        }
        val name = if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "file" else cleaned
        return name.take(180)
    }

    @Throws(IOException::class)
    fun join(base: File, relative: String): File {
        if (relative.startsWith("/") || relative.startsWith("\\") || relative.contains(":")) {
            throw IOException("путь отклонён как абсолютный: $relative")
        }
        val target = File(base, relative)
        val baseReal = base.canonicalPath + File.separator
        val targetReal = target.canonicalPath
        if (targetReal != base.canonicalPath && !targetReal.startsWith(baseReal)) {
            throw IOException("путь выходит за пределы папки: $relative")
        }
        return target
    }

    fun isInside(base: File, candidate: File): Boolean {
        val baseReal = base.canonicalPath + File.separator
        val target = candidate.canonicalPath
        return target == base.canonicalPath || target.startsWith(baseReal)
    }
}
