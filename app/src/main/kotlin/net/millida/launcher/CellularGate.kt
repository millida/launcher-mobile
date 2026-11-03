package net.millida.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import net.millida.launcher.core.http.CellularDownload
import net.millida.launcher.core.packs.PackDownloadGate
import kotlin.coroutines.resume

/**
 * The phone's answer to a pack that wants to download a lot over a metered
 * network: the install waits on a question the main screen shows. «Wait for
 * Wi-Fi» keeps it waiting until an unmetered network is up. One yes covers the
 * rest of the same install, which asks once for the archive and once for its
 * files.
 */
class CellularGate(private val context: Context) : PackDownloadGate {

    enum class Choice { DOWNLOAD, WAIT, CANCEL }

    class Ask(val title: String, val bytes: Long, internal val answer: CompletableDeferred<Choice>)

    private val asking = MutableStateFlow<Ask?>(null)
    val pending: StateFlow<Ask?> = asking.asStateFlow()

    @Volatile
    private var allowedUntil = 0L

    override suspend fun allow(bytes: Long, title: String): Boolean {
        if (!CellularDownload.needsConsent(bytes) || !metered()) return true
        if (SystemClock.elapsedRealtime() < allowedUntil) return true
        val ask = Ask(title, bytes, CompletableDeferred())
        asking.value = ask
        val choice = try {
            ask.answer.await()
        } finally {
            asking.compareAndSet(ask, null)
        }
        return when (choice) {
            Choice.DOWNLOAD -> {
                allowedUntil = SystemClock.elapsedRealtime() + CONSENT_MS
                true
            }
            Choice.WAIT -> awaitUnmetered()
            Choice.CANCEL -> false
        }
    }

    fun answer(choice: Choice) {
        asking.value?.answer?.complete(choice)
    }

    private fun manager(): ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    private fun metered(): Boolean = manager()?.isActiveNetworkMetered ?: false

    /** False when the network cannot be watched: the install stops rather than spend mobile data. */
    private suspend fun awaitUnmetered(): Boolean {
        val manager = manager() ?: return false
        if (!manager.isActiveNetworkMetered) return true
        return suspendCancellableCoroutine { continuation ->
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runCatching { manager.unregisterNetworkCallback(this) }
                    if (continuation.isActive) continuation.resume(true)
                }
            }
            val registered = runCatching { manager.registerNetworkCallback(request, callback) }.isSuccess
            if (!registered) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { runCatching { manager.unregisterNetworkCallback(callback) } }
        }
    }

    private companion object {
        const val CONSENT_MS = 10 * 60 * 1000L
    }
}
