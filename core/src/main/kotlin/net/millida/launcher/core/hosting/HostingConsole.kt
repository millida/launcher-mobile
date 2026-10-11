package net.millida.launcher.core.hosting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.SecretVault
import net.millida.launcher.core.http.Downloader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * One console line. [replay] marks the tail of the log the node sends when the
 * stream opens, so the screen can tell history from what happens now.
 */
data class ConsoleLine(val text: String, val replay: Boolean)

/**
 * The console is a plain SSE stream - the same one the desktop launcher reads
 * from Rust, because there is no websocket for it. Sending a command is an
 * ordinary authorised POST, so a rejected command is reported like any other
 * failure instead of vanishing into the stream.
 */
class ServerConsole(
    private val auth: MillidaAuth,
    private val vault: SecretVault,
    // The shared client is the one that carries our timeouts and the name
    // resolver that survives a dead system DNS; a private client has neither.
    private val client: OkHttpClient = Downloader.shared(),
    private val apiBase: String = MILLIDA_API,
) {

    suspend fun send(serverId: String, command: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        val text = command.trim()
        if (text.isEmpty()) throw HostingError("Команда пустая — нечего отправлять")
        return guard("Команда не ушла") {
            auth.authorized(
                "/hosting/servers/${URLEncoder.encode(id, "UTF-8")}/console/command",
                "POST",
                buildJsonObject { put("command", text) },
            )
            HostingActionResult("Команда отправлена", text)
        }
    }

    /**
     * Cold stream of console lines. It ends when the node closes the connection;
     * reconnecting is the caller's decision, because a screen that is no longer
     * open must not keep a stream alive.
     */
    fun stream(serverId: String): Flow<ConsoleLine> {
        val id = HostingPaths.serverId(serverId)
        return flow {
            val token = vault.read(MillidaAuth.KEY_ACCESS)
            if (token.isNullOrEmpty()) throw HostingError("Консоль недоступна: ${statusText(401)}")
            val request = Request.Builder()
                .url("$apiBase/hosting/servers/${URLEncoder.encode(id, "UTF-8")}/console/stream")
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream")
                .build()
            val call = client.newCall(request)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw HostingError("Консоль недоступна: ${statusText(response.code)}")
                    }
                    val body = response.body ?: throw HostingError("Консоль недоступна: поток пустой")
                    val source = body.source()
                    var replay = true
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val raw = source.readUtf8Line() ?: break
                        val line = raw.trimEnd('\r')
                        if (line.trimStart(':').trim() == REPLAY_END) {
                            replay = false
                            continue
                        }
                        val data = when {
                            line.startsWith("data: ") -> line.substring(6)
                            line.startsWith("data:") -> line.substring(5)
                            else -> continue
                        }
                        if (data.isEmpty()) continue
                        emit(ConsoleLine(data, replay))
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                call.cancel()
                throw cancelled
            } catch (error: Throwable) {
                call.cancel()
                throw hostingFailure("Консоль оборвалась", error)
            }
        }.flowOn(Dispatchers.IO)
    }

    private companion object {
        const val REPLAY_END = "replay-end"
    }
}
