package net.millida.launcher.core.support

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.Unauthorized
import java.io.IOException

/**
 * Reviews, bug reports and crash reports. A bug report is open to a guest on
 * the server, because a player who cannot sign in has to be able to say so;
 * a signed-in player is sent with the session so the report is theirs.
 */
class SupportClient(private val auth: MillidaAuth) {
    @Volatile
    private var feedbackMissing = false

    /**
     * The reviewed path with a reward. Null means the server has no such
     * endpoint yet or there is no session, and the review goes as a bug report.
     */
    suspend fun feedback(text: String, stars: Int): FeedbackAnswer? {
        if (!auth.signedIn() || feedbackMissing) return null
        val payload = buildJsonObject {
            put("text", text)
            if (stars in 1..5) put("stars", stars)
        }
        return try {
            val answer = auth.authorized(FEEDBACK_PATH, "POST", payload)
            FeedbackAnswer(
                granted = (answer["granted"] as? JsonPrimitive)?.booleanOrNull ?: false,
                shards = (answer["shards"] as? JsonPrimitive)?.intOrNull ?: 0,
                nextAt = (answer["nextAt"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        } catch (expired: Unauthorized) {
            null
        } catch (error: IOException) {
            if (!error.isMissingEndpoint()) throw error
            feedbackMissing = true
            null
        }
    }

    suspend fun bugReport(report: BugReport) {
        val payload = buildJsonObject {
            put("service", SERVICE)
            put("category", report.category)
            put("severity", report.severity)
            put("title", report.title)
            put("description", report.description)
        }
        auth.publicCall(BUG_REPORTS_PATH, "POST", payload)
    }

    /** `POST /errors` takes no session on the server, so none is sent. */
    suspend fun error(report: ErrorReport) {
        val payload = buildJsonObject {
            put("source", SOURCE)
            put("level", report.level)
            put("name", report.name)
            put("message", report.message)
            if (report.stack.isNotBlank()) put("stack", report.stack)
            put("release", report.release)
            putJsonObject("context") {
                report.context.forEach { (key, value) -> put(key, value) }
            }
        }
        auth.publicCall(ERRORS_PATH, "POST", payload, withSession = false)
    }

    private fun IOException.isMissingEndpoint(): Boolean {
        val text = message.orEmpty().trim()
        return text.startsWith("Cannot POST", ignoreCase = true) ||
            text.startsWith("Cannot GET", ignoreCase = true) ||
            text.equals("Not Found", ignoreCase = true) ||
            text.endsWith(" 404")
    }

    companion object {
        const val FEEDBACK_PATH = "/launcher/feedback"
        const val BUG_REPORTS_PATH = "/bug-reports"
        const val ERRORS_PATH = "/errors"
        private const val SERVICE = "launcher"
        private const val SOURCE = "LAUNCHER"
    }
}
