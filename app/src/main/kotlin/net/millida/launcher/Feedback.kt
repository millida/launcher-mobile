package net.millida.launcher

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.core.support.BugKind
import net.millida.launcher.core.support.BugReport
import net.millida.launcher.core.support.CrashFile
import net.millida.launcher.core.support.ErrorReport
import net.millida.launcher.core.support.ReportRedaction
import net.millida.launcher.core.support.SupportClient
import net.millida.launcher.runtime.GameSession
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class CrashStatus { Kept, Sending, Sent, Failed }

data class CrashNote(val text: String, val headline: String, val status: CrashStatus)

data class SupportState(
    val crash: CrashNote? = null,
    val crashReports: Boolean = true,
    val anonymousStats: Boolean = true,
    val sendingReview: Boolean = false,
    val sendingBug: Boolean = false,
)

/**
 * Reviews, bug reports and the crash left by the previous run. The crash file
 * stays on the phone until the server has taken it, so a report that did not
 * leave today leaves on the next start.
 */
class SupportModel(
    private val app: Application,
    private val container: Container,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val notice: (String, Boolean) -> Unit,
) {
    private val client = SupportClient(container.millidaAuth)
    private val mutable = MutableStateFlow(
        SupportState(crashReports = prefs.crashReports, anonymousStats = container.telemetry.enabled),
    )
    val state: StateFlow<SupportState> = mutable.asStateFlow()

    init {
        scope.launch { pickUpCrash() }
    }

    fun setCrashReports(enabled: Boolean) {
        prefs.crashReports = enabled
        mutable.update { it.copy(crashReports = enabled) }
    }

    /** The desktop's «Анонимная статистика»: off empties the queue, and nothing is sent until it is on again. */
    fun setAnonymousStats(enabled: Boolean) {
        mutable.update { it.copy(anonymousStats = enabled) }
        scope.launch(Dispatchers.IO) { container.telemetry.setEnabled(enabled) }
    }

    fun sendCrash() {
        val note = mutable.value.crash ?: return
        if (note.status == CrashStatus.Sending || note.status == CrashStatus.Sent) return
        scope.launch {
            if (upload(note.text)) {
                notice("Отчёт о вылете отправлен", false)
            } else {
                notice("Отчёт не ушёл — проверь сеть и нажми «Отправить» ещё раз", true)
            }
        }
    }

    fun dismissCrash() {
        mutable.update { it.copy(crash = null) }
        scope.launch(Dispatchers.IO) { CrashGuard.clear(app) }
    }

    fun sendReview(text: String, stars: Int, onSent: () -> Unit) {
        val body = text.trim()
        if (mutable.value.sendingReview || body.length < BugReport.TEXT_MIN) return
        mutable.update { it.copy(sendingReview = true) }
        scope.launch {
            try {
                val answer = client.feedback(body, stars)
                when {
                    answer == null -> {
                        val report = withContext(Dispatchers.IO) { BugReport.review(body, stars, facts()) }
                        client.bugReport(report)
                        notice("Отзыв принят — спасибо, мы читаем каждый", false)
                    }
                    answer.granted -> notice("Спасибо за отзыв! +${answer.shards} осколков", false)
                    else -> notice("Отзыв принят — награда за отзыв раз в неделю", false)
                }
                onSent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice(failureText(error), true)
            } finally {
                mutable.update { it.copy(sendingReview = false) }
            }
        }
    }

    fun sendBug(kind: BugKind, text: String, attachLog: Boolean, onSent: () -> Unit) {
        val body = text.trim()
        if (mutable.value.sendingBug || body.length < BugReport.TEXT_MIN) return
        mutable.update { it.copy(sendingBug = true) }
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val log = if (attachLog) ReportRedaction.clean(logTail(), nickMasks()) else ""
                    BugReport.bug(kind, body, facts(), log)
                }
                client.bugReport(report)
                notice("Баг отправлен — спасибо, разберёмся", false)
                onSent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice(failureText(error), true)
            } finally {
                mutable.update { it.copy(sendingBug = false) }
            }
        }
    }

    private suspend fun pickUpCrash() {
        val text = withContext(Dispatchers.IO) { CrashGuard.report(app) } ?: return
        val headline = CrashFile.parse(text).message
        mutable.update { it.copy(crash = CrashNote(text, headline, CrashStatus.Kept)) }
        if (prefs.crashReports) upload(text)
    }

    private suspend fun upload(text: String): Boolean {
        mutable.update { current -> current.copy(crash = current.crash?.copy(status = CrashStatus.Sending)) }
        val sent = try {
            withContext(Dispatchers.IO) {
                val record = CrashFile.parse(text)
                val release = RELEASE_PREFIX + record.version.ifEmpty { BuildConfig.VERSION_NAME }
                client.error(ErrorReport.fromCrash(record, release, nickMasks()))
                CrashGuard.clearIfSame(app, text)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "отчёт о вылете не ушёл: ${error.message}")
            false
        }
        val status = if (sent) CrashStatus.Sent else CrashStatus.Failed
        mutable.update { current -> current.copy(crash = current.crash?.copy(status = status)) }
        return sent
    }

    private fun facts(): String = "Millida Mobile ${BuildConfig.VERSION_NAME} · ${container.deviceReport()}"

    /** A report names a fault, not a player. */
    private fun nickMasks(): List<Pair<String?, String>> = try {
        container.accounts.load().map { it.nick to NICK_MASK }
    } catch (unreadable: Exception) {
        Log.w(TAG, "аккаунты не прочитались для маски ников: ${unreadable.message}")
        emptyList()
    }

    private fun logTail(): String {
        val runtimes = container.layout.runtimes
        val parts = ArrayList<String>()
        GameSession.nativeCrash(runtimes)?.let { parts += GameSession.tail(it, NATIVE_LINES) }
        GameSession.lastSessionLog(runtimes)?.let { parts += GameSession.tail(it, SESSION_LINES) }
        CrashGuard.report(app)?.let { parts += it.lineSequence().take(CRASH_LINES).joinToString("\n") }
        return parts.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun failureText(error: Throwable): String {
        val offline = generateSequence(error) { it.cause }.take(8).any {
            it is UnknownHostException || it is SocketException || it is SocketTimeoutException
        }
        if (offline) return "Нет сети — проверь подключение и отправь ещё раз"
        val reason = error.message?.trim().orEmpty()
        return if (reason.isEmpty()) "Не отправилось — попробуй ещё раз" else "Не отправилось: $reason"
    }

    private companion object {
        const val TAG = "millida"
        const val RELEASE_PREFIX = "launcher-mobile@"
        const val NICK_MASK = "<nick>"
        const val NATIVE_LINES = 40
        const val SESSION_LINES = 120
        const val CRASH_LINES = 40
    }
}
