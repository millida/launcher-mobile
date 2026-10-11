package net.millida.launcher.core.logs

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.profile.profileFolder
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

private const val LATEST = "latest.log"
private const val MAX_TAIL_BYTES = 2L * 1024 * 1024
private const val MAX_HEAD_BYTES = 512 * 1024
private const val LAUNCHER_MARK = "[millida]"
private const val HIDDEN = "<hidden>"

// Both launch paths write the JVM options into the session log, one as
// "[millida] jvm option ..." and the other as bare lines, so every -D value
// and agent argument is hidden wherever it stands in a line.
private val OPTION_VALUE = Regex("""(^|\s)(-D[^\s=]+=|-javaagent:[^\s=]+=)\S*""")
private val LOG_EXTENSIONS = setOf("log", "txt", "gz")

class GameLogs(private val layout: GameLayout) {

    @Throws(IOException::class)
    fun files(profileDir: File): List<File> {
        val folder = profileFolder(layout, profileDir, "logs")
        val entries = folder.listFiles() ?: return emptyList()
        val logs = entries
            .filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() in LOG_EXTENSIONS }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
        val latest = logs.firstOrNull { it.name.equals(LATEST, ignoreCase = true) }
            ?: return logs
        return listOf(latest) + logs.filter { it != latest }
    }

    @Throws(IOException::class)
    fun crashReports(profileDir: File): List<File> {
        val folder = profileFolder(layout, profileDir, "crash-reports")
        val entries = folder.listFiles() ?: return emptyList()
        return entries
            .filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
    }

    @Throws(IOException::class)
    fun tail(file: File, maxLines: Int = 400): String {
        if (!file.isFile || maxLines <= 0) return ""
        return FileInputStream(file).use { raw ->
            var partialFirstLine = false
            val stream: InputStream = if (file.name.endsWith(".gz", ignoreCase = true)) {
                GZIPInputStream(raw)
            } else {
                // Only the tail is read: a session log grows to hundreds of megabytes
                // and the phone cannot hold it in memory.
                val extra = file.length() - MAX_TAIL_BYTES
                if (extra > 0) {
                    raw.channel.position(extra)
                    partialFirstLine = true
                }
                raw
            }
            val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
            if (partialFirstLine) reader.readLine()
            collectTail(reader, maxLines)
        }
    }

    /**
     * The launcher's own lines (JVM options, signal repairs) are written as the
     * session starts and scroll out of the tail long before the game dies; they
     * are picked from the head so a shared log still says how the game was run.
     */
    @Throws(IOException::class)
    fun launcherLines(file: File, maxLines: Int = 120): String {
        if (!file.isFile || maxLines <= 0 || file.name.endsWith(".gz", ignoreCase = true)) return ""
        val head = ByteArray(minOf(file.length(), MAX_HEAD_BYTES.toLong()).toInt())
        var filled = 0
        FileInputStream(file).use { raw ->
            while (filled < head.size) {
                val read = raw.read(head, filled, head.size - filled)
                if (read < 0) break
                filled += read
            }
        }
        return String(head, 0, filled, StandardCharsets.UTF_8)
            .lineSequence()
            .filter { it.startsWith(LAUNCHER_MARK) }
            .take(maxLines)
            .map(::withoutSecrets)
            .joinToString("\n")
    }

    /**
     * The journal is shared into messengers, and the options the launcher logs
     * carry the pack's launch signature from the server: property values and
     * agent arguments stay home, only their names travel.
     */
    private fun withoutSecrets(line: String): String = OPTION_VALUE.replace(line) { match ->
        match.groupValues[1] + match.groupValues[2] + HIDDEN
    }

    private fun collectTail(reader: BufferedReader, maxLines: Int): String {
        val lines = ArrayDeque<String>()
        while (true) {
            val line = reader.readLine() ?: break
            lines.addLast(withoutSecrets(line))
            if (lines.size > maxLines) lines.removeFirst()
        }
        return lines.joinToString("\n")
    }
}
