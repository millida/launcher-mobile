package net.millida.launcher.ui.premium

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
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.catalog.MillidaPaid
import net.millida.launcher.core.hub.HealthLine
import net.millida.launcher.core.hub.Hub
import net.millida.launcher.core.hub.HubClient
import net.millida.launcher.core.premium.PackView
import net.millida.launcher.core.premium.Premium
import net.millida.launcher.core.premium.PremiumAction
import net.millida.launcher.core.premium.PremiumClient
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.core.premium.PremiumPackDetail
import net.millida.launcher.core.premium.PremiumShowcase
import net.millida.launcher.core.premium.PremiumSubscription
import net.millida.launcher.core.premium.PremiumTier
import net.millida.launcher.ui.catalog.MillidaShelfHost
import java.util.concurrent.atomic.AtomicLong

/** One pack's page: the card it was opened from, then what the server adds to it. */
data class PackPageState(
    val pack: PremiumPack,
    val detail: PremiumPackDetail? = null,
    val view: PackView? = null,
    val health: HealthLine? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
) {
    val full: PremiumPack get() = Premium.withView(detail?.pack ?: pack, view)

    val shots: List<String> get() = detail?.screenshots?.ifEmpty { null } ?: view?.frames.orEmpty()

    val video: String? get() = detail?.videoUrl ?: view?.video

    val art: String? get() = full.coverUrl ?: view?.art
}

data class PremiumUiState(
    val showcase: PremiumShowcase? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val page: PackPageState? = null,
    val waiting: Set<String> = emptySet(),
    val asking: String? = null,
) {
    val plan: PremiumTier? get() = showcase?.plan
    val subscription: PremiumSubscription? get() = showcase?.subscription
}

/**
 * The paid-pack showcase and a pack's page. Money moves only through the
 * server: it hands out the gateway's address, the launcher opens it after the
 * host check, and access counts as bought only once the server says so.
 * Installing goes through the catalogue's own installer, key window included.
 */
class PremiumModel(
    private val premium: PremiumClient,
    private val hub: HubClient,
    private val scope: CoroutineScope,
    private val host: MillidaShelfHost,
    private val installPack: (slug: String, title: String) -> Unit,
) {
    private val mutable = MutableStateFlow(PremiumUiState())
    val state: StateFlow<PremiumUiState> = mutable.asStateFlow()

    private val showcaseSeq = AtomicLong()
    private val pageSeq = AtomicLong()
    private var watchJob: Job? = null
    private var pendingOpen: String? = null

    fun ensureShowcase() {
        val current = mutable.value
        if (current.loading || (current.showcase != null && current.error == null)) return
        reload()
    }

    fun reload() {
        val seq = showcaseSeq.incrementAndGet()
        mutable.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val shown = withContext(Dispatchers.IO) { premium.showcase() }
                if (seq != showcaseSeq.get()) return@launch
                mutable.update { it.copy(showcase = shown, loading = false) }
                pendingOpen?.let { id ->
                    shown.packs.firstOrNull { it.id == id || it.slug == id }?.let { pendingOpen = null; open(it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (seq != showcaseSeq.get()) return@launch
                mutable.update { it.copy(loading = false, error = host.errorText(error)) }
            }
        }
    }

    /** A pack named from the hub opens as soon as the showcase knows it. */
    fun openById(id: String) {
        val known = mutable.value.showcase?.packs?.firstOrNull { it.id == id || it.slug == id }
        if (known != null) {
            open(known)
            return
        }
        pendingOpen = id
        ensureShowcase()
    }

    fun open(pack: PremiumPack) {
        mutable.update { it.copy(page = PackPageState(pack)) }
        loadPage(pack)
    }

    fun retryPage() = mutable.value.page?.let { loadPage(it.pack) }

    fun closePage() {
        pageSeq.incrementAndGet()
        mutable.update { it.copy(page = null) }
    }

    private fun loadPage(pack: PremiumPack) {
        val seq = pageSeq.incrementAndGet()
        mutable.update { state -> state.copy(page = state.page?.copy(loading = true, failed = false)) }
        val slug = pack.slug?.takeIf { it.isNotBlank() }
        if (slug != null) {
            scope.launch {
                val health = try {
                    withContext(Dispatchers.IO) { hub.packHealth(slug) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(LOG_TAG, "здоровье сборки $slug не прочиталось: ${error.message}")
                    null
                }
                val line = Hub.healthLine(health) ?: return@launch
                mutable.update { state ->
                    if (seq != pageSeq.get()) state else state.copy(page = state.page?.copy(health = line))
                }
            }
        }
        scope.launch {
            try {
                // A catalogue pack has no premium page on the server: its art and frames come from the catalogue card.
                val (detail, view) = withContext(Dispatchers.IO) {
                    if (Premium.viaCatalog(pack)) null to premium.packView(slug.orEmpty()) else premium.pack(pack) to null
                }
                if (seq != pageSeq.get()) return@launch
                mutable.update { state ->
                    state.copy(page = state.page?.copy(detail = detail, view = view, loading = false, failed = false))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (seq != pageSeq.get()) return@launch
                Log.w(LOG_TAG, "страница сборки ${pack.id} не догрузилась: ${error.message}")
                mutable.update { state -> state.copy(page = state.page?.copy(loading = false, failed = true)) }
            }
        }
    }

    /** The plan a page speaks of: the pack's own levels first, otherwise the showcase's. */
    fun planOf(page: PackPageState): PremiumTier? =
        page.detail?.let { Premium.plans(it, mutable.value.plan).firstOrNull() } ?: mutable.value.plan

    fun subscriptionOf(page: PackPageState?): PremiumSubscription? =
        Premium.liveSubscription(page?.detail) ?: mutable.value.subscription

    /** The pack's one main button, the same order of checks as on the desktop. */
    fun primary(pack: PremiumPack, plan: PremiumTier?, sub: PremiumSubscription?) {
        when (val action = Premium.action(pack, plan, sub)) {
            is PremiumAction.Install -> {
                closePage()
                installPack(action.slug, pack.title)
            }
            is PremiumAction.Subscribe -> pay(pack) { premium.subscribe(action.plan.id, pack.id) }
            is PremiumAction.Buy -> pay(pack) { premium.buy(pack.id) }
            PremiumAction.Soon, PremiumAction.None, is PremiumAction.PcOnly -> Unit
        }
    }

    /** The cancel page is a payment page too: it opens only on an allowed host. */
    fun manage(sub: PremiumSubscription) {
        val url = sub.manageUrl ?: return
        if (!MillidaPaid.isPaymentUrl(url) || !host.openExternal(url)) {
            host.notice("Управление подпиской — на millida.net в кошельке", true)
        }
    }

    fun openVideo(url: String) {
        if (!PackView.isHttps(url) || !host.openExternal(url)) host.notice("Видео не открылось — посмотри его на сайте", true)
    }

    private fun pay(pack: PremiumPack, ask: suspend () -> String) {
        if (mutable.value.asking != null || pack.id in mutable.value.waiting) return
        if (!host.signedIn) {
            host.notice("Войди в аккаунт Millida — покупки хранятся в нём", true)
            host.signIn()
            return
        }
        mutable.update { it.copy(asking = pack.id) }
        scope.launch {
            try {
                val url = withContext(Dispatchers.IO) { ask() }
                mutable.update { it.copy(asking = null) }
                if (!MillidaPaid.isPaymentUrl(url)) {
                    host.notice("Ссылка на оплату не прошла проверку — оплати на millida.net", true)
                    return@launch
                }
                if (!host.openExternal(url)) {
                    host.notice("Не открылась страница оплаты — оплати на millida.net", true)
                    return@launch
                }
                mutable.update { it.copy(waiting = it.waiting + pack.id) }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(asking = null) }
                throw cancelled
            } catch (expired: Unauthorized) {
                mutable.update { it.copy(asking = null) }
                host.notice("Войди в аккаунт Millida — покупки хранятся в нём", true)
                host.signIn()
            } catch (error: Throwable) {
                mutable.update { it.copy(asking = null) }
                host.notice("Оплата не открылась: ${host.errorText(error).replaceFirstChar { it.lowercase() }}", true)
            }
        }
    }

    /**
     * The player pays in the browser and comes back: the pack is asked again a
     * few times until the server confirms the access, never assumed.
     */
    fun onResumed() {
        if (mutable.value.waiting.isEmpty()) return
        watchJob?.cancel()
        watchJob = scope.launch {
            repeat(PAYMENT_TRIES) {
                val pending = mutable.value.waiting
                if (pending.isEmpty()) return@launch
                pending.forEach { id -> confirm(id) }
                delay(PAYMENT_EVERY_MS)
            }
        }
    }

    private suspend fun confirm(id: String) {
        val base = mutable.value.showcase?.packs?.firstOrNull { it.id == id }
            ?: mutable.value.page?.pack?.takeIf { it.id == id }
            ?: return
        val detail = try {
            withContext(Dispatchers.IO) { premium.pack(base) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(LOG_TAG, "оплата $id не проверена: ${error.message}")
            return
        }
        val open = Premium.hasAccess(detail.pack, Premium.liveSubscription(detail))
        if (!open) return
        mutable.update { state ->
            state.copy(
                waiting = state.waiting - id,
                page = state.page?.takeIf { it.pack.id == id }?.copy(detail = detail) ?: state.page,
            )
        }
        host.notice(if (detail.pack.owned) "Куплено" else "Подписка оформлена", false)
        reload()
    }

    fun signInChanged() {
        mutable.update { it.copy(waiting = emptySet()) }
        if (mutable.value.showcase != null) reload()
    }

    private companion object {
        const val LOG_TAG = "millida"
        const val PAYMENT_TRIES = 20
        const val PAYMENT_EVERY_MS = 3_000L
    }
}
