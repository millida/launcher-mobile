package net.millida.launcher.runtime

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GameSession {
    private const val LOGS_DIR = "logs"
    private const val LOG_PREFIX = "game-"
    private const val LOG_SUFFIX = ".log"
    private const val NATIVE_CRASH = "native-crash.txt"

    fun logsDir(runtimesDir: File): File = File(runtimesDir, LOGS_DIR).also { it.mkdirs() }

    fun sessionLog(runtimesDir: File): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(logsDir(runtimesDir), "$LOG_PREFIX$stamp$LOG_SUFFIX")
    }

    fun lastSessionLog(runtimesDir: File): File? =
        File(runtimesDir, LOGS_DIR)
            .listFiles { file -> file.isFile && file.name.startsWith(LOG_PREFIX) && file.name.endsWith(LOG_SUFFIX) }
            ?.maxByOrNull { it.lastModified() }

    fun nativeCrash(runtimesDir: File): File? =
        File(File(runtimesDir, LOGS_DIR), NATIVE_CRASH).takeIf { it.isFile && it.length() > 0 }

    /**
     * The launcher writes its own steps into the same file the game writes to,
     * so the place a start stops at is visible in one copied log instead of
     * being guessed at.
     */
    fun note(target: File, text: String) {
        runCatching {
            target.parentFile?.mkdirs()
            target.appendText("[millida] $text" + System.lineSeparator())
        }
    }

    fun tail(file: File, maxLines: Int = 300): String {
        if (!file.isFile) return ""
        val limit = maxLines.coerceAtLeast(1)
        val kept = ArrayDeque<String>(limit)
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (kept.size == limit) kept.removeFirst()
                kept.addLast(line)
            }
        }
        return kept.joinToString("\n")
    }
}
