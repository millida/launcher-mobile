package net.millida.launcher.ui.catalog

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.Container
import net.millida.launcher.core.catalog.CatalogItem
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.MillidaCard
import net.millida.launcher.core.catalog.MillidaCatalog
import net.millida.launcher.core.catalog.MillidaFacets
import net.millida.launcher.core.catalog.MillidaFilters
import net.millida.launcher.core.catalog.MillidaFile
import net.millida.launcher.core.catalog.MillidaItem
import net.millida.launcher.core.catalog.MillidaPaid
import net.millida.launcher.core.catalog.PaidVerdict
import net.millida.launcher.core.catalog.PremiumOffer
import net.millida.launcher.core.catalog.PremiumPlan
import net.millida.launcher.core.catalog.Pricing
import net.millida.launcher.core.catalog.Purchase
import net.millida.launcher.core.http.HttpError
import net.millida.launcher.core.links.MillidaLink
import net.millida.launcher.core.packs.CatalogPackCard
import net.millida.launcher.core.packs.PackAccessDenied
import net.millida.launcher.core.packs.PackUpdates
import net.millida.launcher.core.packs.PhoneCard
import net.millida.launcher.core.packs.PhoneFit
import net.millida.launcher.core.packs.PhoneUnsupported
import net.millida.launcher.core.profile.Profile
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** A pack opened from the list or from a site link, before anything is installed. */
data class MillidaDetail(
    val slug: String,
    val title: String,
    val card: MillidaCard? = null,
    val item: MillidaItem? = null,
    val pack: CatalogPackCard? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val versions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val packOnly: Boolean = false,
) {
    val cover: String? get() = pack?.cover?.takeIf { it.isNotBlank() } ?: item?.cover ?: card?.cover
    val icon: String? get() = item?.icon ?: card?.icon
    val summary: String get() = pack?.summary?.takeIf { it.isNotBlank() } ?: item?.summary?.takeIf { it.isNotBlank() } ?: card?.summary.orEmpty()
    val author: String get() = item?.author?.takeIf { it.isNotBlank() } ?: card?.author.orEmpty()
    val pricing: Pricing get() = item?.pricing?.takeIf { it != Pricing.FREE } ?: card?.pricing ?: Pricing.FREE
    val priceKopecks: Long? get() = item?.priceKopecks ?: card?.priceKopecks
    val paid: Boolean get() = MillidaPaid.isPaid(pricing, priceKopecks)
    val ownPack: Boolean get() = packOnly || pack != null || item?.launcherOnly == true || card?.launcherOnly == true
    val premium: Boolean get() = pack?.accessRequired == true || card?.premium == true
    val file: MillidaFile? get() = item?.let { MillidaCatalog.modpackFile(it.files, versions, loaders) }
    val game: String get() = pack?.gameVersion?.takeIf { it.isNotBlank() } ?: file?.primaryGameVersion?.takeIf { it.isNotBlank() }
        ?: file?.gameVersions?.firstOrNull() ?: card?.versions?.firstOrNull().orEmpty()
    val loader: String get() = pack?.loader?.takeIf { it.isNotBlank() } ?: file?.loaders?.firstOrNull() ?: card?.loaders?.firstOrNull().orEmpty()
    val sizeBytes: Long get() = pack?.sizeBytes?.takeIf { it > 0 } ?: file?.size ?: 0

    /**
     * Known before the download from the card alone; the archive can still
     * refuse later. A pack that brings its own game is judged by that, not by
     * the loader its card names.
     */
    val phoneRefusal: String?
        get() = PhoneFit.cardVerdict(
            PhoneCard(
                loader = loader,
                gameVersion = game,
                ownLaunch = pack?.ownLaunch == true || card?.ownLaunch == true,
                accessRequired = premium,
                sizeBytes = sizeBytes,
            ),
        )?.let { PhoneFit.message(it, title, game) }
}

enum class BuyStep { CONFIRM, FUNDS }

data class BuyAsk(
    val slug: String,
    val title: String,
    val pricing: Pricing,
    val priceKopecks: Long,
    val step: BuyStep = BuyStep.CONFIRM,
    val repriced: Boolean = false,
    val balance: Long? = null,
    val busy: Boolean = false,
)

data class KeyAsk(
    val slug: String,
    val title: String,
    val reason: String,
    val buyUrl: String = "",
    val offer: PremiumOffer? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

data class PhoneRefusal(val title: String, val text: String)

data class ShelfState(
    val items: List<MillidaCard> = emptyList(),
    val page: Int = 0,
    val pages: Int = 0,
    val query: String = "",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val access: Map<String, Boolean> = emptyMap(),
    val waiting: Set<String> = emptySet(),
    val purchasesOpen: Boolean = false,
    val purchases: List<Purchase>? = null,
    val purchasesError: String? = null,
    val detail: MillidaDetail? = null,
    val buy: BuyAsk? = null,
    val key: KeyAsk? = null,
    val refusal: PhoneRefusal? = null,
    val filters: MillidaFilters = MillidaFilters(),
    val facets: MillidaFacets? = null,
    val total: Int = 0,
) {
    val more: Boolean get() = page in 1 until pages

    fun open(card: MillidaCard): Boolean = access[card.slug] == true

    fun locked(card: MillidaCard): Boolean = card.paid && access[card.slug] != true
}

interface MillidaShelfHost {
    val signedIn: Boolean

    fun notice(text: String, isError: Boolean)

    fun errorText(error: Throwable): String

    fun signIn()

    /** Opens an address in the browser; false when nothing could open it. */
    fun openExternal(url: String): Boolean
}

/**
 * The Millida half of the modpack shelf: the site's catalogue, its paid packs
 * and the purchase steps, in the order the desktop launcher and the site use
 * them - access, confirmation with the live price, the wallet or the gateway,
 * and only then the install.
 */
class MillidaShelfModel(
    private val container: Container,
    private val scope: CoroutineScope,
    private val catalog: CatalogModel,
    private val host: MillidaShelfHost,
) {
    private val mutable = MutableStateFlow(ShelfState())
    val state: StateFlow<ShelfState> = mutable.asStateFlow()

    private val api = MillidaCatalog(container.millidaAuth)
    private val listSeq = AtomicLong()
    private val detailSeq = AtomicLong()
    private var listJob: Job? = null
    private var facetsFor: String? = null
    private var watchJob: Job? = null
    private var buyKey: String? = null
    private val afterOwned = HashMap<String, () -> Unit>()

    fun ensureLoaded(query: String) {
        val current = mutable.value
        val wanted = query.trim()
        if (current.page > 0 && current.query == wanted && current.error == null) return
        if (current.loading && current.query == wanted) return
        load(wanted)
    }

    fun reload() = load(mutable.value.query)

    fun setFilters(filters: MillidaFilters) {
        if (mutable.value.filters == filters) return
        mutable.update { it.copy(filters = filters) }
        load(mutable.value.query)
    }

    private fun load(query: String) {
        listJob?.cancel()
        val seq = listSeq.incrementAndGet()
        mutable.update { it.copy(query = query, loading = true, loadingMore = false, error = null) }
        val filters = mutable.value.filters
        val facetsKey = filters.version.orEmpty()
        if (facetsKey != facetsFor) {
            facetsFor = facetsKey
            loadFacets(filters.version, facetsKey)
        }
        listJob = scope.launch {
            try {
                val (listing, premium) = withContext(Dispatchers.IO) {
                    val page = api.listing(SECTION, query, 1, filters)
                    val paidPacks = try {
                        api.premiumPacks()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(LOG_TAG, "платные сборки не загрузились: ${error.message}")
                        emptyList()
                    }
                    page to paidPacks
                }
                if (seq != listSeq.get()) return@launch
                val pinnable = if (filters.active) premium.filter { pack -> listing.items.any { it.slug == pack.slug } } else premium
                val items = MillidaCatalog.forPhone(MillidaCatalog.pinShowcase(listing.items, pinnable, query))
                mutable.update { it.copy(items = items, page = listing.page, pages = listing.pages, total = listing.total, loading = false) }
                loadAccess(items)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (seq != listSeq.get()) return@launch
                mutable.update { it.copy(loading = false, error = host.errorText(error)) }
            }
        }
    }

    fun loadMore() {
        val current = mutable.value
        if (!current.more || current.loading || current.loadingMore) return
        val seq = listSeq.get()
        mutable.update { it.copy(loadingMore = true) }
        scope.launch {
            try {
                val next = withContext(Dispatchers.IO) { api.listing(SECTION, current.query, current.page + 1, current.filters) }
                if (seq != listSeq.get()) return@launch
                val fitting = MillidaCatalog.forPhone(next.items)
                mutable.update { state ->
                    val shown = state.items.mapTo(HashSet()) { it.slug }
                    state.copy(
                        items = state.items + fitting.filterNot { it.slug in shown },
                        page = next.page,
                        pages = next.pages,
                        loadingMore = false,
                    )
                }
                loadAccess(fitting)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (seq != listSeq.get()) return@launch
                mutable.update { it.copy(loadingMore = false) }
                host.notice("Сборки Millida не догрузились: ${host.errorText(error).lowerFirst()}", true)
            }
        }
    }

    /** The filter sheets are a convenience: without facets they are hidden, the list itself still loads. */
    private fun loadFacets(version: String?, key: String) {
        scope.launch {
            val facets = try {
                withContext(Dispatchers.IO) { api.facets(SECTION, version) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(LOG_TAG, "фильтры сборок не загрузились: ${error.message}")
                if (facetsFor == key) facetsFor = null
                return@launch
            }
            if (facetsFor == key) mutable.update { it.copy(facets = facets) }
        }
    }

    /** Access is asked only for paid rows and only with a sign-in; a guest sees them locked. */
    private fun loadAccess(cards: List<MillidaCard>) {
        if (!host.signedIn) return
        cards.filter { it.paid && it.slug !in mutable.value.access }.forEach { card ->
            scope.launch { refreshAccess(card.slug) }
        }
    }

    private suspend fun refreshAccess(slug: String): Boolean {
        val open = try {
            val answer = withContext(Dispatchers.IO) { api.access(slug) }
            answer.livePriceKopecks?.let { live ->
                mutable.update { state ->
                    val ask = state.buy
                    if (ask != null && ask.slug == slug && ask.priceKopecks != live) {
                        state.copy(buy = ask.copy(priceKopecks = live, repriced = true))
                    } else {
                        state
                    }
                }
            }
            answer.open
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "доступ к $slug не проверен: ${error.message}")
            false
        }
        mutable.update { it.copy(access = it.access + (slug to open)) }
        return open
    }

    /** Signing in or out changes every paid row at once. */
    fun signInChanged() {
        mutable.update { it.copy(access = emptyMap(), purchases = null, purchasesError = null) }
        if (host.signedIn) loadAccess(mutable.value.items)
    }

    // --- Detail ---

    fun open(card: MillidaCard) {
        mutable.update { it.copy(detail = MillidaDetail(card.slug, card.title, card = card)) }
        loadDetail()
    }

    fun openFromLink(link: MillidaLink.Install) {
        mutable.update {
            it.copy(
                detail = MillidaDetail(
                    slug = link.slug,
                    title = link.name ?: link.slug,
                    versions = link.versions,
                    loaders = link.loaders,
                    packOnly = link.packOnly,
                ),
            )
        }
        loadDetail()
    }

    /**
     * A mod, resource pack, shader, data pack or map from the site. A card with
     * a Modrinth source goes by the Modrinth road, which also brings the
     * dependencies; one the author uploaded to us goes in as our own file
     * through [native], and without it plays on the computer only.
     */
    fun openContentLink(
        link: MillidaLink.Install,
        native: ((MillidaItem) -> Unit)? = null,
        show: (CatalogItem) -> Unit,
    ) {
        scope.launch {
            val item = try {
                withContext(Dispatchers.IO) { api.item(link.slug) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                host.notice("«${link.slug}» не открылся: ${host.errorText(error).lowerFirst()}", true)
                return@launch
            }
            val title = MillidaCatalog.displayName(item.title)
            val modrinth = MillidaCatalog.modrinthSlugOf(item.sourceUrl)
            val paid = MillidaPaid.isPaid(item.pricing, item.priceKopecks)
            if (modrinth == null && native != null && (item.files.isNotEmpty() || paid)) {
                native(item)
                return@launch
            }
            if (modrinth == null) {
                host.notice("«$title» на телефоне не ставится — поставь через Millida Launcher на компьютере", true)
                return@launch
            }
            show(
                CatalogItem(
                    projectId = modrinth,
                    slug = modrinth,
                    title = title,
                    description = item.summary,
                    iconUrl = item.icon,
                    source = CatalogSource.MODRINTH,
                ),
            )
        }
    }

    fun openPurchase(purchase: Purchase) {
        mutable.update {
            it.copy(
                purchasesOpen = false,
                detail = MillidaDetail(purchase.slug, purchase.title, packOnly = purchase.type == "MODPACK"),
            )
        }
        loadDetail()
    }

    fun retryDetail() = loadDetail()

    fun closeDetail() = mutable.update { it.copy(detail = null) }

    private fun loadDetail() {
        val detail = mutable.value.detail ?: return
        val seq = detailSeq.incrementAndGet()
        mutable.update { it.copy(detail = detail.copy(loading = true, error = null)) }
        scope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) { fetchDetail(detail) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                detail.copy(loading = false, error = host.errorText(error))
            }
            if (seq != detailSeq.get()) return@launch
            mutable.update { state -> if (state.detail?.slug == detail.slug) state.copy(detail = loaded) else state }
            val shown = loaded.card ?: MillidaCard(loaded.slug, loaded.title, pricing = loaded.pricing, priceKopecks = loaded.priceKopecks)
            if (loaded.paid) loadAccess(listOf(shown))
        }
    }

    /**
     * A ready launcher pack has no public file, only the signed link, so its
     * pack card is the one to read; a mirrored modpack is read from its item.
     */
    private suspend fun fetchDetail(detail: MillidaDetail): MillidaDetail {
        val item = if (detail.packOnly) {
            null
        } else {
            try {
                api.item(detail.slug)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (missing: Exception) {
                null
            }
        }
        val pack = if (item == null || item.launcherOnly) {
            try {
                container.packApi.catalogPack(detail.slug)
            } catch (missing: HttpError) {
                if (missing.code != 404) throw missing
                throw IOException("В каталоге Millida нет «${detail.slug}» — проверь ссылку на сайте", missing)
            }
        } else {
            null
        }
        val title = pack?.title?.takeIf { it.isNotBlank() }
            ?: item?.title?.let { MillidaCatalog.displayName(it) }
            ?: detail.title
        return detail.copy(item = item, pack = pack, title = title, loading = false, error = null)
    }

    /** The one main button of a pack: sign in, buy, or install, whichever comes first. */
    fun primary() {
        val detail = mutable.value.detail ?: return
        if (detail.loading) return
        detail.phoneRefusal?.let { text ->
            mutable.update { it.copy(refusal = PhoneRefusal(detail.title, text)) }
            return
        }
        if ((detail.paid || detail.ownPack) && !host.signedIn) {
            host.notice("Войди в аккаунт Millida — сборки каталога ставятся на аккаунт", true)
            host.signIn()
            return
        }
        if (detail.paid && mutable.value.access[detail.slug] != true) {
            startBuy(detail.slug, detail.title, detail.pricing, detail.priceKopecks ?: 0) { install(detail.slug) }
            return
        }
        install(detail.slug)
    }

    /**
     * The row's button: sign-in and purchase start right there, an install
     * opens the sheet first, because a pack is gigabytes and deserves one look.
     */
    fun primary(card: MillidaCard) {
        if ((card.paid || card.launcherOnly || card.premium) && !host.signedIn) {
            host.notice("Войди в аккаунт Millida — сборки каталога ставятся на аккаунт", true)
            host.signIn()
            return
        }
        if (mutable.value.locked(card)) {
            startBuy(card.slug, card.title, card.pricing, card.priceKopecks ?: 0) { install(card.slug) }
            return
        }
        open(card)
    }

    /** Installs from what the sheet already read; a pack not read yet is read first. */
    fun install(slug: String) {
        val detail = mutable.value.detail?.takeIf { it.slug == slug && !it.loading && it.error == null }
        if (detail == null) {
            val card = mutable.value.items.firstOrNull { it.slug == slug }
            if (card != null) open(card) else mutable.update { it.copy(detail = MillidaDetail(slug, slug)) }
            if (card == null) loadDetail()
            return
        }
        detail.phoneRefusal?.let { text ->
            mutable.update { it.copy(refusal = PhoneRefusal(detail.title, text)) }
            return
        }
        if (detail.ownPack || detail.item == null) {
            installOwnPack(detail.slug, detail.title)
            return
        }
        val item = detail.item
        val mirrored = PackUpdates.mirroredFile(item, detail.file, detail.title)
        if (mirrored != null) {
            val started = catalog.runModpack(
                key(slug),
                detail.title,
                { error -> explained(error, slug, detail.title) },
                source = SOURCE,
            ) { onStep ->
                container.packs.installMirroredPack(mirrored, onProgress = onStep)
            }
            if (started) closeAfterStart(slug)
            return
        }
        val modrinth = MillidaCatalog.modrinthSlugOf(item.sourceUrl)
        if (modrinth != null) {
            val idle = catalog.state.value.modpack == null
            catalog.installModpack(
                CatalogItem(projectId = modrinth, slug = modrinth, title = detail.title, iconUrl = item.icon, source = CatalogSource.MODRINTH),
            )
            if (idle) closeAfterStart(slug)
            return
        }
        host.notice("Файла сборки «${detail.title}» нет на зеркале Millida — открой её на сайте", true)
        host.openExternal(sitePage(slug))
    }

    /**
     * A ready launcher pack, from the list, the sheet or a site link. A paid one
     * without access opens the key window, and the install goes on by itself
     * once the key is accepted.
     */
    private fun installOwnPack(slug: String, title: String) {
        val started = catalog.runModpack(key(slug), title, { error -> explained(error, slug, title) }, source = SOURCE) { onStep ->
            container.packs.installCatalogPack(slug, null, onStep)
        }
        if (started) closeAfterStart(slug)
    }

    /**
     * A launcher-only server's client pack, installed and started in one
     * press. When a window has to answer first — the key of a paid pack or a
     * phone refusal — [reveal] brings the catalogue forward so it is seen.
     */
    fun installAndPlay(slug: String, title: String, reveal: () -> Unit, play: (Profile) -> Unit) {
        if (!host.signedIn) {
            host.notice("Войди в аккаунт Millida — сборки каталога ставятся на аккаунт", true)
            host.signIn()
            return
        }
        catalog.runModpack(
            key(slug),
            title,
            onFailure = { error ->
                val shown = explained(error, slug, title)
                if (shown) reveal()
                shown
            },
            onInstalled = play,
            source = SOURCE,
        ) { onStep -> container.packs.installCatalogPack(slug, null, onStep) }
    }

    private fun closeAfterStart(slug: String) {
        mutable.update { state -> if (state.detail?.slug == slug) state.copy(detail = null) else state }
    }

    private fun explained(error: Throwable, slug: String, title: String): Boolean = when (error) {
        is PhoneUnsupported -> {
            mutable.update { it.copy(refusal = PhoneRefusal(error.title, error.message.orEmpty())) }
            true
        }
        is PackAccessDenied -> {
            if (slug.isNotEmpty()) openKey(slug, title, error.message.orEmpty())
            slug.isNotEmpty()
        }
        else -> false
    }

    fun dismissRefusal() = mutable.update { it.copy(refusal = null) }

    /** The key window or the phone refusal for a failure anywhere in the launcher; false when neither fits. */
    fun explain(error: Throwable, slug: String, title: String): Boolean = explained(error, slug, title)

    /** Access is asked for rows another shelf shows; the answers land in the same map. */
    fun checkAccess(cards: List<MillidaCard>) = loadAccess(cards)

    /** The server refused a file: the row goes back to «Купить». */
    fun markLocked(slug: String) = mutable.update { it.copy(access = it.access + (slug to false)) }

    /**
     * The purchase steps for any paid item of the catalogue, not only packs:
     * sign-in first, then the confirmation with the live price, then [onOwned].
     */
    fun buyThen(slug: String, title: String, pricing: Pricing, priceKopecks: Long, onOwned: () -> Unit) {
        if (!host.signedIn) {
            host.notice("Войди в аккаунт Millida — покупки хранятся в нём", true)
            host.signIn()
            return
        }
        startBuy(slug, title, pricing, priceKopecks, onOwned)
    }

    // --- Purchase ---

    private fun startBuy(slug: String, title: String, pricing: Pricing, price: Long, onOwned: () -> Unit) {
        if (mutable.value.buy != null) return
        scope.launch {
            if (refreshAccess(slug)) {
                onOwned()
                return@launch
            }
            afterOwned[slug] = onOwned
            buyKey = null
            mutable.update { it.copy(buy = BuyAsk(slug, title, pricing, price)) }
            val balance = freshBalance()
            mutable.update { state ->
                val ask = state.buy
                if (ask == null || ask.slug != slug) {
                    state
                } else {
                    val short = if (ask.pricing == Pricing.ONE_TIME) MillidaPaid.shortfallKopecks(ask.priceKopecks, balance) else null
                    state.copy(buy = ask.copy(balance = balance, step = if (short != null && short > 0) BuyStep.FUNDS else ask.step))
                }
            }
        }
    }

    private suspend fun freshBalance(): Long? = try {
        withContext(Dispatchers.IO) { container.wallet.balance().availableKopecks }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Log.w(LOG_TAG, "баланс не прочитан: ${error.message}")
        null
    }

    fun closeBuy() {
        val ask = mutable.value.buy ?: return
        if (ask.busy) return
        afterOwned.remove(ask.slug)
        buyKey = null
        mutable.update { it.copy(buy = null) }
    }

    fun confirmBuy() {
        val ask = mutable.value.buy ?: return
        if (ask.busy) return
        if (ask.step == BuyStep.FUNDS) {
            if (!host.openExternal(MillidaPaid.WALLET_URL)) host.notice("Не открылся кошелёк — пополни его на millida.net", true)
            return
        }
        mutable.update { it.copy(buy = ask.copy(busy = true)) }
        scope.launch {
            try {
                if (ask.pricing == Pricing.SUBSCRIPTION) {
                    val paymentUrl = withContext(Dispatchers.IO) { api.subscribe(ask.slug) }
                    mutable.update { it.copy(buy = it.buy?.copy(busy = false)) }
                    if (!openPayment(paymentUrl)) return@launch
                    mutable.update { it.copy(buy = null, waiting = it.waiting + ask.slug) }
                    return@launch
                }
                val attempt = buyKey ?: MillidaCatalog.newIdempotencyKey().also { buyKey = it }
                withContext(Dispatchers.IO) { api.buy(ask.slug, attempt, ask.priceKopecks) }
                owned(ask.slug, "Куплено")
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(buy = it.buy?.copy(busy = false)) }
                throw cancelled
            } catch (error: Throwable) {
                mutable.update { it.copy(buy = it.buy?.copy(busy = false)) }
                if (MillidaPaid.transient(error)) {
                    host.notice("Millida не ответила — повтори, второй раз не спишем", true)
                    return@launch
                }
                buyKey = null
                failBuy(error, ask)
            }
        }
    }

    private suspend fun failBuy(error: Throwable, ask: BuyAsk) {
        when (val verdict = MillidaPaid.verdict(error.message)) {
            PaidVerdict.LOGIN -> {
                afterOwned.remove(ask.slug)
                mutable.update { it.copy(buy = null) }
                host.notice("Войди в аккаунт Millida — покупки хранятся в нём", true)
                host.signIn()
            }
            PaidVerdict.FUNDS -> {
                val balance = freshBalance()
                mutable.update { it.copy(buy = it.buy?.copy(step = BuyStep.FUNDS, balance = balance)) }
            }
            PaidVerdict.PRICE_CHANGED -> {
                refreshAccess(ask.slug)
                mutable.update { it.copy(buy = it.buy?.copy(repriced = true)) }
            }
            PaidVerdict.OWNED -> owned(ask.slug, "Уже куплено")
            else -> {
                afterOwned.remove(ask.slug)
                mutable.update { it.copy(buy = null) }
                val fallback = if (ask.pricing == Pricing.SUBSCRIPTION) "Не удалось оформить подписку" else "Не удалось купить"
                host.notice(MillidaPaid.verdictText(verdict) ?: "$fallback: ${host.errorText(error).lowerFirst()}", true)
            }
        }
    }

    private fun owned(slug: String, text: String) {
        buyKey = null
        mutable.update { it.copy(buy = null, access = it.access + (slug to true), waiting = it.waiting - slug) }
        host.notice(text, false)
        if (mutable.value.purchases != null) loadPurchases()
        afterOwned.remove(slug)?.invoke()
    }

    private fun openPayment(url: String): Boolean {
        if (!MillidaPaid.isPaymentUrl(url)) {
            host.notice("Ссылка на оплату не прошла проверку — оплати на millida.net", true)
            return false
        }
        if (!host.openExternal(url)) {
            host.notice("Не открылась страница оплаты — оплати на millida.net", true)
            return false
        }
        return true
    }

    /**
     * The player pays in the browser and comes back: access is asked again a
     * few times, the way the desktop launcher does on focus, until it opens.
     */
    fun onResumed() {
        if (mutable.value.waiting.isEmpty()) {
            val ask = mutable.value.buy
            if (ask != null && ask.step == BuyStep.FUNDS) {
                scope.launch {
                    val balance = freshBalance()
                    mutable.update { state ->
                        val current = state.buy
                        if (current == null || current.slug != ask.slug) state
                        else if (MillidaPaid.shortfallKopecks(current.priceKopecks, balance) == 0L) {
                            state.copy(buy = current.copy(step = BuyStep.CONFIRM, balance = balance))
                        } else {
                            state.copy(buy = current.copy(balance = balance))
                        }
                    }
                }
            }
            return
        }
        watchJob?.cancel()
        watchJob = scope.launch {
            repeat(PAYMENT_TRIES) {
                val pending = mutable.value.waiting
                if (pending.isEmpty()) return@launch
                pending.forEach { slug ->
                    val open = if (slug.startsWith(PREMIUM_MARK)) premiumOwned(slug.removePrefix(PREMIUM_MARK)) else refreshAccess(slug)
                    if (open) {
                        val plain = slug.removePrefix(PREMIUM_MARK)
                        mutable.update { it.copy(waiting = it.waiting - slug, access = it.access + (plain to true)) }
                        host.notice("Подписка оформлена", false)
                        if (mutable.value.purchases != null) loadPurchases()
                        afterOwned.remove(slug)?.invoke()
                    }
                }
                delay(PAYMENT_EVERY_MS)
            }
        }
    }

    private suspend fun premiumOwned(slug: String): Boolean =
        withContext(Dispatchers.IO) { api.premiumOffer(slug) }?.owned == true

    // --- Access key ---

    fun openKey(slug: String, title: String, reason: String) {
        mutable.update { it.copy(key = KeyAsk(slug, title, reason)) }
        scope.launch {
            val buyUrl = try {
                withContext(Dispatchers.IO) { container.packApi.catalogPack(slug) }.accessBuyUrl
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ""
            }
            val offer = withContext(Dispatchers.IO) { api.premiumOffer(slug) }
            mutable.update { state ->
                val ask = state.key
                if (ask == null || ask.slug != slug) state else state.copy(key = ask.copy(buyUrl = buyUrl, offer = offer))
            }
        }
    }

    fun closeKey() {
        if (mutable.value.key?.busy == true) return
        mutable.update { it.copy(key = null) }
    }

    fun redeem(code: String) {
        val ask = mutable.value.key ?: return
        if (ask.busy || code.trim().length < KEY_MIN) return
        mutable.update { it.copy(key = ask.copy(busy = true, error = null)) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.redeem(ask.slug, code) }
                mutable.update { it.copy(key = null, access = it.access + (ask.slug to true)) }
                host.notice("Ключ принят — ставим сборку", false)
                installOwnPack(ask.slug, ask.title)
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(key = it.key?.copy(busy = false)) }
                throw cancelled
            } catch (error: Throwable) {
                mutable.update { it.copy(key = it.key?.copy(busy = false, error = host.errorText(error))) }
            }
        }
    }

    /** The seller's page opens in the browser, marked as coming from the launcher. */
    fun buyOnSellerPage() {
        val ask = mutable.value.key ?: return
        val link = MillidaPaid.packBuyLink(ask.buyUrl, ask.slug) ?: return
        if (!host.openExternal(link)) host.notice("Не открылась страница продавца — купи сборку на millida.net", true)
    }

    fun subscribePlan(plan: PremiumPlan) {
        val ask = mutable.value.key ?: return
        if (ask.busy) return
        mutable.update { it.copy(key = ask.copy(busy = true, error = null)) }
        scope.launch {
            try {
                val url = withContext(Dispatchers.IO) { api.subscribePremium(plan.id, ask.slug) }
                mutable.update { it.copy(key = it.key?.copy(busy = false)) }
                if (!openPayment(url)) return@launch
                val mark = PREMIUM_MARK + ask.slug
                afterOwned[mark] = { installOwnPack(ask.slug, ask.title) }
                mutable.update { it.copy(key = null, waiting = it.waiting + mark) }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(key = it.key?.copy(busy = false)) }
                throw cancelled
            } catch (error: Throwable) {
                mutable.update { it.copy(key = it.key?.copy(busy = false)) }
                host.notice("Оплата не открылась: ${host.errorText(error).lowerFirst()}", true)
            }
        }
    }

    // --- Purchases ---

    fun openPurchases() {
        mutable.update { it.copy(purchasesOpen = true) }
        if (host.signedIn) loadPurchases()
    }

    fun closePurchases() = mutable.update { it.copy(purchasesOpen = false) }

    fun loadPurchases() {
        if (!host.signedIn) {
            mutable.update { it.copy(purchases = emptyList(), purchasesError = null) }
            return
        }
        mutable.update { it.copy(purchasesError = null) }
        scope.launch {
            try {
                val rows = withContext(Dispatchers.IO) { api.purchases() }
                mutable.update { state ->
                    state.copy(purchases = rows, purchasesError = null, access = state.access + rows.associate { it.slug to true })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutable.update { it.copy(purchasesError = host.errorText(error), purchases = it.purchases ?: emptyList()) }
            }
        }
    }

    fun openOnSite(section: String, slug: String) {
        if (!host.openExternal("https://millida.net/$section/$slug")) host.notice("Не открылся сайт — зайди на millida.net", true)
    }

    fun waiting(slug: String): Boolean = slug in mutable.value.waiting

    companion object {
        const val SECTION = "modpacks"

        /** The desktop's `source` for a pack from the Millida catalogue. */
        private const val SOURCE = "millida"
        private const val LOG_TAG = "millida"
        private const val KEY_MIN = 8
        private const val PAYMENT_TRIES = 20
        private const val PAYMENT_EVERY_MS = 3_000L
        private const val PREMIUM_MARK = "premium:"

        fun key(slug: String): String = "millida:$slug"

        fun sitePage(slug: String): String = "https://millida.net/$SECTION/$slug"
    }
}

private fun String.lowerFirst(): String = replaceFirstChar { it.lowercase() }
