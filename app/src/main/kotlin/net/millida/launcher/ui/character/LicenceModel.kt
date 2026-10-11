package net.millida.launcher.ui.character

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.core.skins.MinecraftServicesClient
import net.millida.launcher.core.skins.MsProfile
import net.millida.launcher.core.skins.SkinImport
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private const val FALLBACK_FAILURE = "Mojang не ответил — проверь сеть и повтори"

data class LicenceState(
    val accountId: String = "",
    val profile: MsProfile? = null,
    val loading: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
)

/**
 * The skin and capes of a licensed Microsoft account live at Mojang, not in the
 * Millida wardrobe, so they are read and changed here with the account's own
 * game token. The token comes from the caller, which renews it the same way it
 * does before a launch.
 */
class LicenceModel(
    private val services: MinecraftServicesClient,
    private val skinImport: SkinImport,
    private val scope: CoroutineScope,
    private val token: suspend () -> String,
    private val notice: (String, Boolean) -> Unit,
) {
    private val mutable = MutableStateFlow(LicenceState())
    val state: StateFlow<LicenceState> = mutable.asStateFlow()
    private val loads = AtomicLong()

    fun load(accountId: String): Job = scope.launch {
        val ticket = loads.incrementAndGet()
        mutable.update {
            if (it.accountId == accountId) it.copy(loading = true, error = null) else LicenceState(accountId, loading = true)
        }
        val result = attempt { withContext(Dispatchers.IO) { services.profile(token()) } }
        if (loads.get() != ticket) return@launch
        mutable.update { current ->
            result.fold(
                onSuccess = { current.copy(profile = it, loading = false, error = null) },
                onFailure = { current.copy(loading = false, error = failureText(it)) },
            )
        }
    }

    fun wearBytes(png: ByteArray, slim: Boolean, onDone: () -> Unit = {}): Job =
        change("Скин на лицензии сменился", onDone) { key -> services.uploadSkin(key, png, slim) }

    fun wearFile(file: File, slim: Boolean, onDone: () -> Unit = {}): Job =
        change("Скин на лицензии сменился", onDone) { key -> services.uploadSkin(key, file.readBytes(), slim) }

    fun wearUrl(url: String, slim: Boolean?, onDone: () -> Unit = {}): Job =
        change("Скин на лицензии сменился", onDone) { key ->
            val imported = skinImport.byUrl(url)
            services.uploadSkin(key, imported.bytes, slim ?: slimArms(imported.bytes) ?: false)
        }

    /** Mojang keeps the arm model with the texture, so the same skin goes up again with the other model. */
    fun setModel(slim: Boolean): Job {
        val current = mutable.value.profile
        if (current == null || current.skinUrl.isEmpty()) {
            return scope.launch { notice("Сначала поставь скин на лицензию", true) }
        }
        if (current.slim == slim) return scope.launch { }
        return wearUrl(current.skinUrl, slim)
    }

    /** An empty id takes the cape off. */
    fun setCape(capeId: String, onDone: () -> Unit = {}): Job =
        change(if (capeId.isEmpty()) "Плащ лицензии снят" else "Плащ на лицензии сменился", onDone) { key ->
            services.setCape(key, capeId)
            services.profile(key)
        }

    fun resetSkin(onDone: () -> Unit = {}): Job =
        change("Скин лицензии сброшен", onDone) { key ->
            services.resetSkin(key)
            services.profile(key)
        }

    private fun change(success: String, onDone: () -> Unit, block: suspend (String) -> MsProfile): Job =
        scope.launch {
            if (mutable.value.working) {
                notice("Подожди, прошлое изменение ещё идёт", true)
                return@launch
            }
            val owner = mutable.value.accountId
            mutable.update { it.copy(working = true) }
            val result = attempt { withContext(Dispatchers.IO) { block(token()) } }
            if (mutable.value.accountId != owner) return@launch
            val fresh = result.getOrNull()
            // A load that started before this change would bring back the old profile.
            if (fresh != null) loads.incrementAndGet()
            mutable.update { current ->
                when {
                    current.accountId != owner -> current
                    fresh != null -> current.copy(working = false, loading = false, error = null, profile = fresh)
                    else -> current.copy(working = false)
                }
            }
            result.fold(
                onSuccess = {
                    notice(success, false)
                    onDone()
                },
                onFailure = { notice(failureText(it), true) },
            )
        }

    private fun failureText(error: Throwable): String =
        error.message?.trim()?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() } ?: FALLBACK_FAILURE
}

private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
