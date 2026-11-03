package net.millida.launcher

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.millida.launcher.core.account.AccountStore
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.support.CrashFile
import net.millida.launcher.core.support.ErrorReport
import net.millida.launcher.core.support.SupportClient
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
    private const val TAG = "millida"
    private const val RELEASE_PREFIX = "launcher-mobile@"
    private const val NICK_MASK = "<nick>"
    private const val EARLY_WAIT_MS = 3_000L

    private val sending = Mutex()

    @Volatile
    private var early: Thread? = null

    fun install(context: Context) {
        val target = file(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(target, thread.name, error) }
            // A crash right at start would otherwise kill the report of the previous one mid-flight.
            early?.takeIf { it !== thread && it.isAlive }?.let { sender -> runCatching { sender.join(EARLY_WAIT_MS) } }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * A crash on start kills the process before any screen is up, so the report it
     * left is sent here, off the main thread and without the container or the
     * translations, which may be the very thing that crashed. A failure leaves the
     * file in place for the support screen to send later.
     */
    fun sendEarly(context: Context) {
        val app = context.applicationContext
        val sender = Thread({
            try {
                if (!Prefs(app).crashReports) return@Thread
                val text = report(app) ?: return@Thread
                val masks = nickMasks(AccountStore(gameLayout(app)))
                val client = SupportClient(MillidaAuth(Downloader.shared(), MemoryVault()))
                runBlocking { send(app, client, masks, text) }
            } catch (failure: Throwable) {
                Log.w(TAG, "отчёт о вылете при старте не ушёл: $failure")
            }
        }, "crash-report")
        sender.isDaemon = true
        early = sender
        sender.start()
    }

    /** One sender at a time, so the early send and the support screen never post the same crash twice. */
    suspend fun send(context: Context, client: SupportClient, masks: List<Pair<String?, String>>, text: String) {
        sending.withLock {
            if (report(context) != text) return
            val record = CrashFile.parse(text)
            val release = RELEASE_PREFIX + record.version.ifEmpty { BuildConfig.VERSION_NAME }
            client.error(ErrorReport.fromCrash(record, release, masks))
            clearIfSame(context, text)
        }
    }

    /** A report names a fault, not a player. */
    fun nickMasks(accounts: AccountStore): List<Pair<String?, String>> = try {
        accounts.load().map { it.nick to NICK_MASK }
    } catch (unreadable: Exception) {
        Log.w(TAG, "аккаунты не прочитались для маски ников: ${unreadable.message}")
        emptyList()
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
