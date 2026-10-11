package net.millida.launcher.ui.character

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.millida.launcher.core.skins.CapeOffer
import net.millida.launcher.core.skins.SkinCatalog
import net.millida.launcher.core.skins.WardrobeClient

private const val LOG_TAG = "millida"

/**
 * The server keeps the list of Mojang capes anyone may wear and refreshes it on
 * its own; the bundled copy stands in until that answer arrives or when the
 * network is down.
 */
class MojangCapes(
    private val wardrobe: WardrobeClient,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(SkinCatalog.OFFICIAL_CAPES.map { CapeOffer(it.id, it.name, it.url, unlocked = true) })
    val state: StateFlow<List<CapeOffer>> = mutable.asStateFlow()
    private var fetched = false
    private var running: Job? = null

    fun load() {
        if (fetched || running?.isActive == true) return
        running = scope.launch {
            try {
                val list = wardrobe.mojangCapes()
                if (list.isNotEmpty()) {
                    mutable.value = list
                    fetched = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(LOG_TAG, "Mojang cape list did not load, the bundled one stays: ${failure.message}")
            }
        }
    }
}
