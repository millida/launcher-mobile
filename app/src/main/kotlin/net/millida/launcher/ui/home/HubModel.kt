package net.millida.launcher.ui.home

import android.content.Context
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
import net.millida.launcher.core.hub.Hub
import net.millida.launcher.core.hub.HubClient
import net.millida.launcher.core.hub.HubMode
import net.millida.launcher.core.hub.ModeArt
import net.millida.launcher.core.hub.ModeArts
import net.millida.launcher.core.hub.PopularVersion
import net.millida.launcher.core.hub.Rotation
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.core.rating.RatingClient
import net.millida.launcher.core.rating.RatingSort
import net.millida.launcher.core.servers.OwnServers
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/** A mode opened from its tile: the busiest servers of that activity. */
data class ModeSheet(
    val mode: HubMode,
    val servers: List<RatedServer>? = null,
    val error: String? = null,
)

data class HubState(
    val modes: List<HubMode>? = null,
    val modesFailed: Boolean = false,
    val catalog: List<PremiumPack>? = null,
    val catalogFailed: Boolean = false,
    val ownOnline: Int? = null,
    val popular: List<PopularVersion> = emptyList(),
    val art: Map<String, ModeArt> = emptyMap(),
    val mode: ModeSheet? = null,
    val heroIndex: Int = 0,
    val recommendTurn: Int = 0,
    val day: Long = 0,
)

/**
 * The data of the «Во что играем» sections under the lobby: modes of the
 * rating, our catalogue's packs, the own event's online and the popular
 * versions. Each part loads and fails on its own, so one silent address
 * never blanks the rest of the lobby.
 */
class HubModel(
    context: Context,
    private val hub: HubClient,
    private val rating: RatingClient,
    private val scope: CoroutineScope,
    private val errorText: (Throwable) -> String,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = mutable.asStateFlow()

    private val modeSeq = AtomicLong()
    private var loaded = false
    private var turned = false

    fun ensureLoaded() {
        if (!turned) {
            turned = true
            turn()
        }
        if (loaded) return
        loaded = true
        load()
    }

    fun retry() = load()

    private fun load() {
        mutable.update { it.copy(modesFailed = false, catalogFailed = false) }
        if (mutable.value.art.isEmpty()) {
            scope.launch {
                val art = withContext(Dispatchers.IO) { readArt() }
                mutable.update { it.copy(art = art) }
            }
        }
        scope.launch {
            try {
                val modes = withContext(Dispatchers.IO) { hub.modes() }
                mutable.update { it.copy(modes = modes, modesFailed = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(LOG_TAG, "режимы не загрузились: ${error.message}")
                mutable.update { it.copy(modesFailed = true) }
            }
        }
        scope.launch {
            try {
                val packs = withContext(Dispatchers.IO) { hub.catalogPacks() }
                mutable.update { it.copy(catalog = packs, catalogFailed = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(LOG_TAG, "сборки каталога не загрузились: ${error.message}")
                mutable.update { it.copy(catalogFailed = true) }
            }
        }
        scope.launch {
            val online = try {
                withContext(Dispatchers.IO) { hub.serverOnline(OwnServers.ONEBLOCK.slug) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                null
            }
            mutable.update { it.copy(ownOnline = online) }
        }
        scope.launch {
            val popular = try {
                withContext(Dispatchers.IO) { hub.popularVersions() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Hub.POPULAR_FALLBACK
            }
            mutable.update { it.copy(popular = popular) }
        }
    }

    /** One hero a day and a new «попробуй» card on every launch, kept between launches. */
    private fun turn() {
        val today = LocalDate.now().toString()
        val hero = Rotation.heroPick(prefs.getString(KEY_HERO, null), today)
        val recommend = prefs.getInt(KEY_RECOMMEND, -1) + 1
        prefs.edit().putString(KEY_HERO, hero.stored).putInt(KEY_RECOMMEND, recommend).apply()
        mutable.update { it.copy(heroIndex = hero.index, recommendTurn = recommend, day = Rotation.dayNumber()) }
    }

    fun openMode(mode: HubMode) {
        val seq = modeSeq.incrementAndGet()
        mutable.update { it.copy(mode = ModeSheet(mode)) }
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    rating.servers(sort = RatingSort.ONLINE, category = mode.code, limit = MODE_LIMIT)
                }
                if (seq != modeSeq.get()) return@launch
                mutable.update { state -> state.copy(mode = state.mode?.copy(servers = busiest(OwnServers.forPhone(page.servers)), error = null)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (seq != modeSeq.get()) return@launch
                mutable.update { state -> state.copy(mode = state.mode?.copy(error = errorText(error))) }
            }
        }
    }

    fun retryMode() = mutable.value.mode?.mode?.let(::openMode)

    fun closeMode() {
        modeSeq.incrementAndGet()
        mutable.update { it.copy(mode = null) }
    }

    /**
     * The rating is assembled from several monitorings, so one project comes
     * once per address; the liveliest row stays.
     */
    private fun busiest(servers: List<RatedServer>): List<RatedServer> =
        servers.groupBy { it.name.trim().lowercase() }
            .map { (_, rows) -> rows.maxBy { it.online } }
            .sortedWith(compareByDescending<RatedServer> { it.up }.thenByDescending { it.online })

    private fun readArt(): Map<String, ModeArt> = try {
        ModeArts.parse(app.assets.open("${ModeArts.ROOT}/manifest.json").bufferedReader().use { it.readText() })
    } catch (missing: Exception) {
        Log.w(LOG_TAG, "картинки режимов не прочитались: ${missing.message}")
        emptyMap()
    }

    private companion object {
        const val PREFS = "millida-hub"
        const val KEY_HERO = "hero"
        const val KEY_RECOMMEND = "recommend"
        const val LOG_TAG = "millida"
        const val MODE_LIMIT = 30
    }
}
