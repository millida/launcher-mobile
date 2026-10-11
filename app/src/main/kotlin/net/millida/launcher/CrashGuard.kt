package net.millida.launcher

import android.content.Context
import android.os.Build
import net.millida.launcher.core.support.CrashFile
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A crash that only reaches logcat is a crash nobody can report. The stack is
 * written to a file first; the next start sends it and removes the file only
 * once the server has taken it.
 */
object CrashGuard {

    private const val FOLDER = "crash"
    private const val FILE = "last.txt"

    fun install(context: Context) {
        val target = file(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(target, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun report(context: Context): String? {
        val file = file(context)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    /**
     * The game process writes into the same file, so a crash that landed while
     * the previous one was on its way is not the one to delete.
     */
    fun clearIfSame(context: Context, sent: String): Boolean {
        val current = report(context) ?: return true
        return current == sent && file(context).delete()
    }

    private fun file(context: Context): File = File(File(context.filesDir, FOLDER), FILE)

    private fun write(target: File, threadName: String, error: Throwable) {
        target.parentFile?.mkdirs()
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val stack = StringWriter().also { writer -> error.printStackTrace(PrintWriter(writer)) }.toString()
        val device = buildString {
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" · Android ").append(Build.VERSION.RELEASE)
            append(" · ").append(Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        }
        target.writeText(CrashFile.format(BuildConfig.VERSION_NAME, device, stamp, threadName, stack))
    }
}
