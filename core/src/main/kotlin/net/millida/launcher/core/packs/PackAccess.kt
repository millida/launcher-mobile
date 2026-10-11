package net.millida.launcher.core.packs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import net.millida.launcher.core.account.Unauthorized
import java.io.File
import java.io.IOException

/** What our API says about a paid pack that is about to run or is running. */
sealed interface PackAccess {
    data class Allowed(val recheckInSec: Long) : PackAccess

    data class Lost(val reason: String) : PackAccess

    companion object {
        const val RECHECK_DEFAULT_SEC = 15 * 60L
        const val RECHECK_MIN_SEC = 5 * 60L
        const val RECHECK_MAX_SEC = 60 * 60L
        const val RECHECK_RETRY_SEC = 5 * 60L
        private const val LOST_DEFAULT = "У этого аккаунта больше нет доступа к сборке"

        /**
         * Only a verdict spelled out by our API takes the pack away. An unknown
         * word, a missing field or `need-login` means we do not know, and not
         * knowing must never cost a paying player their pack.
         */
        fun from(response: JsonObject?): PackAccess? {
            val access = (response?.get("access") as? JsonPrimitive)?.contentOrNull ?: return null
            return when (access) {
                "allow" -> Allowed(
                    ((response["recheckInSec"] as? JsonPrimitive)?.longOrNull ?: RECHECK_DEFAULT_SEC)
                        .coerceIn(RECHECK_MIN_SEC, RECHECK_MAX_SEC),
                )
                "revoked", "expired", "no-access", "not-paid" -> Lost(
                    (response["message"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } ?: LOST_DEFAULT,
                )
                else -> null
            }
        }

        /** Seconds until the next check, or null when the pack is gone. */
        fun nextCheckSec(verdict: PackAccess?): Long? = when (verdict) {
            is Allowed -> verdict.recheckInSec
            is Lost -> null
            null -> RECHECK_RETRY_SEC
        }
    }
}

/**
 * The launch signature our API issues to the signed-in account, as JVM flags.
 * It is never stored: a value on disk travels with a copied folder, and asking
 * for it on every start makes a revoked key take effect at the next start.
 */
object PackLaunchToken {
    @Throws(IOException::class)
    fun args(response: JsonObject): List<String> {
        val kept = (response["jvmArgs"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { PackJvmArgs.allowed(it) }
            .orEmpty()
        if (kept.isEmpty()) throw IOException("сервер не выдал ключ запуска сборки — нажми «Играть» ещё раз через минуту")
        return kept
    }
}

/** Access to a paid pack is gone for good: the build leaves the phone, the player's files are kept. */
class PackAccessLost(val slug: String, reason: String) : IOException(reason)

/** What one start of a build needs from our API: the slug to watch and the signature flags. */
data class PackAdmission(
    val slug: String = "",
    val tokenArgs: List<String> = emptyList(),
    val watch: Boolean = false,
)

/** The API half the gate needs; [MillidaPacks] is the real one. */
interface PackAccessApi {
    fun signedIn(): Boolean

    suspend fun accessRequired(slug: String): Boolean

    suspend fun access(slug: String): PackAccess?

    suspend fun launchToken(slug: String): List<String>
}

/**
 * Asked before every start of a build installed from our catalogue, the same
 * order the desktop launcher keeps:
 * - a pack with its own launch that needs a signature gets one per start; when
 *   that fails and our API says access is gone, the pack is forfeited;
 * - any other catalogue build is closed only by an explicit refusal; a lost
 *   connection lets it start, so a paying player is never locked out offline.
 */
class PackLaunchGate(
    private val api: PackAccessApi,
    private val gateWaitMs: Long = GATE_WAIT_MS,
) {

    @Throws(IOException::class)
    suspend fun admit(profileDir: File): PackAdmission {
        val spec = PackLaunches.trusted(profileDir)
        if (spec != null && spec.needsToken) return signed(spec.slug)
        val origin = PackOriginStore.read(profileDir) ?: return PackAdmission()
        if (origin.source != PackSource.MILLIDA_CATALOG.id || !catalogSlugValid(origin.id)) return PackAdmission()
        gate(origin.id)
        return PackAdmission(slug = origin.id)
    }

    @Throws(IOException::class)
    private suspend fun signed(slug: String): PackAdmission {
        val token = try {
            api.launchToken(slug)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            val verdict = try {
                api.access(slug)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unknown: IOException) {
                null
            }
            if (verdict is PackAccess.Lost) throw PackAccessLost(slug, verdict.reason)
            if (failure is Unauthorized) throw Unauthorized("войди в аккаунт Millida — эта сборка запускается по доступу")
            throw failure
        }
        return PackAdmission(slug = slug, tokenArgs = token, watch = true)
    }

    @Throws(IOException::class)
    private suspend fun gate(slug: String) {
        if (!api.signedIn()) {
            val required = withTimeoutOrNull(gateWaitMs) { quietly { api.accessRequired(slug) } } == true
            if (required) throw PackAccessDenied("Войди в аккаунт Millida — эта сборка открывается по подписке")
            return
        }
        val verdict = withTimeoutOrNull(gateWaitMs) { quietly { api.access(slug) } }
        if (verdict is PackAccess.Lost) throw PackAccessDenied(verdict.reason)
    }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unknown: IOException) {
        null
    }

    companion object {
        const val GATE_WAIT_MS = 6_000L
    }
}

/**
 * Rechecks access while a paid pack runs: a refund or a revoked key takes
 * effect within one interval instead of at a next start that may never come.
 * Returns the reason when access is gone, null once the game has ended.
 */
object PackAccessWatch {
    suspend fun watch(
        slug: String,
        running: () -> Boolean,
        check: suspend (String) -> PackAccess?,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): String? {
        var waitSec = PackAccess.RECHECK_DEFAULT_SEC
        while (true) {
            sleep(waitSec * 1000)
            if (!running()) return null
            val verdict = try {
                check(slug)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unknown: IOException) {
                null
            }
            waitSec = PackAccess.nextCheckSec(verdict) ?: return (verdict as PackAccess.Lost).reason
        }
    }
}
