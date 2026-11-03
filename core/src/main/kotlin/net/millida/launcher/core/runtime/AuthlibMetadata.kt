package net.millida.launcher.core.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.http.Downloader
import java.io.File
import java.util.Base64

/**
 * authlib-injector asks the Millida API for its description on every start, inside the game, with its
 * own timeout. On a bad connection that call failed and the game started with a Millida token that
 * Mojang rejects: 200+ aborts of 130 installs and 64 exits without a first frame in the week of
 * 19-26.10.2026. The launcher asks first, keeps the answer, and hands it to the agent, which accepts
 * it as `-Dauthlibinjector.yggdrasil.prefetched=<base64 of the response>` (AuthlibInjector.java,
 * getPrefetchedResponse): then the agent makes no request of its own. The last good answer is kept,
 * so a start without a connection still has one.
 */
class AuthlibMetadata(
    layout: GameLayout,
    private val downloader: Downloader,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private val cache = File(layout.runtimes, CACHE_NAME)

    /** The `-D` argument for [yggdrasilUrl], or null when there is neither an answer nor a kept one. */
    suspend fun jvmArgument(yggdrasilUrl: String): String? {
        val body = fresh(yggdrasilUrl) ?: kept(yggdrasilUrl) ?: return null
        return PROPERTY + "=" + Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
    }

    private suspend fun fresh(url: String): String? = try {
        val body = withTimeout(timeoutMs) { downloader.text(url) }
        if (looksLikeMetadata(body)) {
            runCatching { cache.apply { parentFile?.mkdirs() }.writeText(url + "\n" + body) }
            body
        } else {
            null
        }
    } catch (cancelled: TimeoutCancellationException) {
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }

    private fun kept(url: String): String? = runCatching {
        val text = cache.readText()
        val at = text.indexOf('\n')
        text.substring(at + 1).takeIf { at > 0 && text.substring(0, at) == url && looksLikeMetadata(it) }
    }.getOrNull()

    companion object {
        const val PROPERTY = "-Dauthlibinjector.yggdrasil.prefetched"
        const val CACHE_NAME = "authlib-metadata.txt"
        const val TIMEOUT_MS = 6_000L

        /** The agent reads `meta` and the skin domains from it; anything else is an error page. */
        fun looksLikeMetadata(body: String): Boolean {
            val text = body.trim()
            return text.startsWith("{") && text.endsWith("}") && "\"meta\"" in text
        }
    }
}
