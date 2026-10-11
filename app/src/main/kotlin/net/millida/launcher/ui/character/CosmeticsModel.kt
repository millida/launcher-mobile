package net.millida.launcher.ui.character

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.core.cosmetics.CosmeticCatalog
import net.millida.launcher.core.cosmetics.CosmeticItem
import net.millida.launcher.core.cosmetics.CosmeticModelFile
import net.millida.launcher.core.cosmetics.CosmeticsClient
import net.millida.launcher.core.cosmetics.Look
import net.millida.launcher.core.cosmetics.Outfit
import net.millida.launcher.core.cosmetics.OutfitStore
import net.millida.launcher.core.cosmetics.Outfits
import net.millida.launcher.core.cosmetics.VARIANT_SEP
import net.millida.launcher.core.cosmetics.WornCosmetic
import net.millida.launcher.core.cosmetics.defaultVariant
import net.millida.launcher.core.rubies.WardrobeBill
import net.millida.launcher.core.skins.ShowcaseCard
import net.millida.launcher.core.skins.ShowcaseKind
import net.millida.launcher.core.skins.SkinShowcase
import net.millida.launcher.loadErrorText
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val LOG_TAG = "millida"

/** An item by its code and colourway, as a chest or the shop hands it out. */
data class WearRef(val code: String, val variant: String = "")

data class CosmeticsState(
    val accountId: String = "",
    val signedIn: Boolean = false,
    val catalog: CosmeticCatalog = CosmeticCatalog.EMPTY,
    val catalogReady: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val owned: Set<String> = emptySet(),
    val worn: List<WornCosmetic> = emptyList(),
    val wornReady: Boolean = false,
    /** Items on the figure only: locked ones the player looks at before buying. */
    val fitting: List<CosmeticItem> = emptyList(),
    /** What is being sent right now: an item id, [BUSY_OFF] or empty. */
    val busy: String = "",
    val outfits: List<Outfit> = emptyList(),
    val outfitBusy: String = "",
    val showcase: Map<ShowcaseKind, List<ShowcaseCard>> = emptyMap(),
    val showcaseLoading: Set<ShowcaseKind> = emptySet(),
    val showcaseFailed: Map<ShowcaseKind, String> = emptyMap(),
    /** Items another screen asked to put on (a chest, the shop), waiting for the catalogue. */
    val wearIntent: List<WearRef>? = null,
) {
    /** Paid and not the player's yet. */
    fun locked(item: CosmeticItem): Boolean = !item.free && item.id !in owned

    fun isWorn(item: CosmeticItem): Boolean = worn.any { it.id == item.id }

    fun isFitting(item: CosmeticItem): Boolean = fitting.any { it.id == item.id }

    /** A try-on pushes the worn item off the same slot: two hats at once would lie about the look. */
    val dressed: List<CosmeticItem>
        get() {
            val busySlots = fitting.map { it.slot }.toSet()
            return worn.filter { it.slot !in busySlots }.mapNotNull { catalog.byId(it.id) } + fitting
        }

    companion object {
        const val BUSY_OFF = "off"
    }
}

/**
 * The mod's wardrobe on the phone: the catalogue, the player's rights, what is
 * worn now and what is only tried on. The rules are the desktop's
 * (src/screens/Skins.tsx): an own item goes on with one tap and is saved on the
 * account; a locked one goes on the figure only; the server always gets the
 * whole set, never a difference.
 */
class CosmeticsModel(
    private val client: CosmeticsClient,
    private val showcaseClient: SkinShowcase,
    private val outfitStore: OutfitStore,
    private val scope: CoroutineScope,
    private val notice: (String, Boolean) -> Unit,
) {
    private val mutable = MutableStateFlow(CosmeticsState())
    val state: StateFlow<CosmeticsState> = mutable.asStateFlow()
    private val loads = AtomicLong()
    private val models = ConcurrentHashMap<String, CompletableDeferred<CosmeticModelFile?>>()
    private var running: Job? = null

    /**
     * The catalogue is open to everyone and rights belong to the signed-in only,
     * so a refused rights call must not hide the catalogue itself.
     */
    fun load(accountId: String, signedIn: Boolean, uuid: String?): Job {
        val ticket = loads.incrementAndGet()
        mutable.update { current ->
            val fresh = if (current.accountId == accountId) current else CosmeticsState(accountId = accountId)
            fresh.copy(loading = true, error = null, signedIn = signedIn)
        }
        running?.cancel()
        return scope.launch {
            val catalog = try {
                withContext(Dispatchers.IO) { client.catalog() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (loads.get() == ticket) mutable.update { it.copy(loading = false, error = loadErrorText(failure)) }
                return@launch
            }
            if (loads.get() != ticket) return@launch
            mutable.update { it.copy(catalog = catalog, catalogReady = true) }
            if (!signedIn) {
                mutable.update { it.copy(loading = false, owned = emptySet(), worn = emptyList(), wornReady = true) }
                return@launch
            }
            val owned = attempt { withContext(Dispatchers.IO) { client.owned() } }
            val worn = if (uuid.isNullOrBlank()) null else attempt { withContext(Dispatchers.IO) { client.equipped(uuid) } }
            if (loads.get() != ticket) return@launch
            val ownedCodes = owned?.let { catalog.ownedCodes(it.codes, it.variants).toSet() }
            val wornCards = worn?.map(catalog::fromServer)
            val failure = when {
                owned == null -> "Права на вещи не загрузились — нажми «Обновить»"
                uuid.isNullOrBlank() -> null
                worn == null -> "Надетое не загрузилось — нажми «Обновить»"
                else -> null
            }
            mutable.update { current ->
                current.copy(
                    loading = false,
                    owned = ownedCodes ?: current.owned,
                    worn = wornCards ?: current.worn,
                    wornReady = wornCards != null || current.wornReady,
                    fitting = current.fitting.filter { item -> ownedCodes == null || (!item.free && item.id !in ownedCodes) },
                    error = failure,
                )
            }
        }.also { running = it }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w(LOG_TAG, "cosmetics: ${failure.message}")
        null
    }

    /** Puts a piece on the figure or takes it off there; buying has nothing to do with it. */
    fun tryOn(item: CosmeticItem) {
        mutable.update { current ->
            val next = if (current.isFitting(item)) {
                current.fitting.filterNot { it.id == item.id }
            } else {
                current.fitting.filterNot { it.slot == item.slot } + item
            }
            current.copy(fitting = next)
        }
    }

    /**
     * "Put on" from a chest or the shop: own items go on and are saved, others go on
     * the figure as a try-on. The wardrobe opens on the first item's section.
     */
    fun wearIntent(refs: List<WearRef>) {
        if (refs.isNotEmpty()) mutable.update { it.copy(wearIntent = refs) }
    }

    /** Resolves a waiting intent once the catalogue is in; returns the cards it found, first one first. */
    fun takeWearIntent(): List<CosmeticItem> {
        val current = mutable.value
        val refs = current.wearIntent ?: return emptyList()
        if (!current.catalogReady) return emptyList()
        mutable.update { it.copy(wearIntent = null) }
        val items = refs.mapNotNull { ref ->
            val same = current.catalog.items.filter { it.id == ref.code || it.baseId == ref.code }
            val byVariant = if (ref.variant.isNotEmpty()) same.firstOrNull { it.id.endsWith("$VARIANT_SEP${ref.variant}") } else null
            byVariant ?: same.firstOrNull { it.id == ref.code } ?: same.firstOrNull()
        }
        if (items.isEmpty()) return items
        val others = items.filter { !current.signedIn || current.locked(it) }
        if (others.isNotEmpty()) {
            mutable.update { state ->
                state.copy(fitting = state.fitting.filterNot { item -> others.any { it.slot == item.slot } } + others)
            }
        }
        val mine = items - others.toSet()
        if (mine.isNotEmpty() && current.busy.isEmpty()) {
            val next = current.worn.filterNot { worn -> mine.any { it.slot == worn.slot } } +
                mine.map { WornCosmetic(it.id, it.slot, defaultVariant(it.variants)?.name.orEmpty()) }
            send(next, mine.first().id, "Надето — видно в игре") { state ->
                state.copy(fitting = state.fitting.filterNot { item -> mine.any { it.slot == item.slot } })
            }
        }
        return items
    }

    /**
     * Pieces the shop confirmed as bought: they are the player's at once, without
     * waiting for the rights list, and go on in the slots they were tried on in.
     * A wear that fails leaves them owned and off the try-on, so a tap on the
     * card puts them on.
     */
    fun wearBought(ids: List<String>) {
        val current = mutable.value
        val bought = current.fitting.filter { it.id in ids }
        mutable.update { it.copy(owned = it.owned + ids) }
        if (bought.isEmpty()) return
        val boughtSlots = bought.map { it.slot }.toSet()
        val offTryOn: (CosmeticsState) -> CosmeticsState = { state -> state.copy(fitting = state.fitting.filterNot { it.slot in boughtSlots }) }
        if (current.busy.isNotEmpty()) {
            mutable.update(offTryOn)
            notice("Куплено — нажми на вещь, чтобы надеть", false)
            return
        }
        send(
            next = WardrobeBill.dressAfter(current.worn, bought),
            busy = bought.first().id,
            done = "Куплено и надето — видно в игре",
            failed = "Куплено, но не надето — нажми на вещь",
            afterFailed = offTryOn,
            after = offTryOn,
        )
    }

    fun clearFitting() {
        mutable.update { it.copy(fitting = emptyList()) }
    }

    /** A tap on a card: worn comes off, own goes on and is saved, locked or signed-out is only tried on. */
    fun tap(item: CosmeticItem, variant: String = "") {
        val current = mutable.value
        when {
            !current.signedIn -> tryOn(item)
            current.isWorn(item) -> toggle(item, variant)
            current.isFitting(item) || current.locked(item) -> tryOn(item)
            else -> toggle(item, variant)
        }
    }

    private fun toggle(item: CosmeticItem, variant: String) {
        val current = mutable.value
        if (current.busy.isNotEmpty()) return
        if (current.locked(item)) {
            notice("Эта вещь ещё не открыта — её можно только примерить", false)
            return
        }
        val already = current.isWorn(item)
        val next = current.worn.filter { it.slot != item.slot && it.id != item.id }.toMutableList()
        if (!already) next.add(WornCosmetic(item.id, item.slot, variant.ifEmpty { defaultVariant(item.variants)?.name.orEmpty() }))
        send(next, item.id, if (already) "Снято" else "Надето — видно в игре") { state ->
            if (already) state else state.copy(fitting = state.fitting.filterNot { it.slot == item.slot })
        }
    }

    /** Empties the slots on the account with the same call that dresses it; a try-on there goes too. */
    fun takeOff(slots: Collection<String>) {
        val current = mutable.value
        if (current.busy.isNotEmpty()) return
        mutable.update { it.copy(fitting = it.fitting.filterNot { item -> item.slot in slots }) }
        if (current.worn.none { it.slot in slots }) return
        send(current.worn.filterNot { it.slot in slots }, CosmeticsState.BUSY_OFF, "Снято") { it }
    }

    private fun send(
        next: List<WornCosmetic>,
        busy: String,
        done: String,
        failed: String = "Не удалось изменить косметику",
        afterFailed: (CosmeticsState) -> CosmeticsState = { it },
        after: (CosmeticsState) -> CosmeticsState,
    ) {
        mutable.update { it.copy(busy = busy) }
        scope.launch {
            val catalog = mutable.value.catalog
            val sent = try {
                withContext(Dispatchers.IO) { client.wear(next.map(catalog::toServer)) }
                true
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(busy = "") }
                throw cancelled
            } catch (failure: Exception) {
                notice("$failed: " + loadErrorText(failure).replaceFirstChar { it.lowercase() }, true)
                false
            }
            mutable.update { current -> if (sent) after(current.copy(worn = next, busy = "")) else afterFailed(current.copy(busy = "")) }
            if (sent) notice(done, false)
        }
    }

    /**
     * Models stay in memory for the session: the server caps them per day and a
     * piece goes on and off several times. A failure is not kept, or one broken
     * request would leave the item blank until a restart.
     */
    suspend fun model(modelId: String): CosmeticModelFile? {
        val existing = models[modelId]
        if (existing != null) return existing.await()
        val asked = CompletableDeferred<CosmeticModelFile?>()
        val raced = models.putIfAbsent(modelId, asked)
        if (raced != null) return raced.await()
        val file = try {
            withContext(Dispatchers.IO) { client.model(modelId) }
        } catch (cancelled: CancellationException) {
            models.remove(modelId, asked)
            asked.complete(null)
            throw cancelled
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "cosmetic model $modelId: ${failure.message}")
            null
        }
        if (file == null) models.remove(modelId, asked)
        asked.complete(file)
        return file
    }

    fun loadShowcase(kind: ShowcaseKind, force: Boolean = false) {
        val current = mutable.value
        if (kind in current.showcaseLoading) return
        if (!force && current.showcase.containsKey(kind)) return
        mutable.update { it.copy(showcaseLoading = it.showcaseLoading + kind, showcaseFailed = it.showcaseFailed - kind) }
        scope.launch {
            val cards = try {
                withContext(Dispatchers.IO) { showcaseClient.list(kind) }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(showcaseLoading = it.showcaseLoading - kind) }
                throw cancelled
            } catch (failure: Exception) {
                val text = loadErrorText(failure)
                mutable.update { it.copy(showcaseLoading = it.showcaseLoading - kind, showcaseFailed = it.showcaseFailed + (kind to text)) }
                return@launch
            }
            mutable.update { it.copy(showcaseLoading = it.showcaseLoading - kind, showcase = it.showcase + (kind to cards)) }
        }
    }

    fun loadOutfits(accountId: String) {
        scope.launch {
            val list = try {
                withContext(Dispatchers.IO) { outfitStore.load(accountId) }
            } catch (failure: Exception) {
                notice(failure.message ?: "Сохранённые образы не читаются", true)
                emptyList()
            }
            mutable.update { if (it.accountId == accountId) it.copy(outfits = list) else it }
        }
    }

    private fun keepOutfits(accountId: String, next: List<Outfit>, done: String?) {
        val before = mutable.value.outfits
        mutable.update { it.copy(outfits = next) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { outfitStore.save(accountId, next) }
                if (done != null) notice(done, false)
            } catch (failure: Exception) {
                mutable.update { if (it.outfits == next) it.copy(outfits = before) else it }
                notice(failure.message ?: "Образ не сохранился — повтори", true)
            }
        }
    }

    fun saveOutfit(look: Look) {
        val current = mutable.value
        val same = current.outfits.firstOrNull { Outfits.same(it, look) }
        if (same != null) {
            notice("Это «${same.name}». Надень другое — и сохрани", false)
            return
        }
        if (current.outfits.size >= Outfits.LIMIT) {
            notice("Больше ${Outfits.LIMIT} образов не сохранить — удали ненужный", true)
            return
        }
        val next = Outfits.add(current.outfits, look, System.currentTimeMillis().toString(36))
        keepOutfits(current.accountId, next, "Образ сохранён: «${next.last().name}»")
    }

    fun renameOutfit(id: String, name: String) {
        val current = mutable.value
        val next = Outfits.rename(current.outfits, id, name)
        if (next != current.outfits) keepOutfits(current.accountId, next, null)
    }

    fun removeOutfit(id: String) {
        val current = mutable.value
        keepOutfits(current.accountId, Outfits.remove(current.outfits, id), "Образ удалён")
    }

    /**
     * Puts a look on: the skin first, then the cape, then the items. What is
     * already on is not sent again. Locked items are skipped, or the server
     * would refuse the whole set over one of them.
     */
    fun wearOutfit(outfit: Outfit, wearSkin: suspend () -> Boolean, wearCape: suspend () -> Boolean) {
        val current = mutable.value
        if (current.outfitBusy.isNotEmpty() || current.busy.isNotEmpty()) return
        mutable.update { it.copy(outfitBusy = outfit.id) }
        scope.launch {
            try {
                if (!wearSkin()) return@launch
                if (!wearCape()) return@launch
                val state = mutable.value
                val (wear, skipped) = Outfits.splitWearable(outfit.cosmetics) { id ->
                    val item = state.catalog.byId(id)
                    item != null && !state.locked(item)
                }
                val same = wear.size == state.worn.size && wear.all { w -> state.worn.any { it.id == w.id && it.variant == w.variant } }
                if (!same) {
                    withContext(Dispatchers.IO) { client.wear(wear.map(state.catalog::toServer)) }
                    mutable.update { it.copy(worn = wear, fitting = emptyList()) }
                }
                if (skipped.isNotEmpty()) {
                    notice("«${outfit.name}» надет, закрытых вещей пропущено: ${skipped.size}", false)
                } else {
                    notice("«${outfit.name}» надет", false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                notice("Образ надет не целиком: " + loadErrorText(failure).replaceFirstChar { it.lowercase() }, true)
            } finally {
                mutable.update { it.copy(outfitBusy = "") }
            }
        }
    }
}
