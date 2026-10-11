package net.millida.launcher.ui.catalog

import android.util.Log
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
import net.millida.launcher.Container
import net.millida.launcher.core.catalog.CatalogItem
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.MillidaCard
import net.millida.launcher.core.catalog.MillidaCatalog
import net.millida.launcher.core.catalog.MillidaFacets
import net.millida.launcher.core.catalog.MillidaFilters
import net.millida.launcher.core.catalog.MillidaFilePick
import net.millida.launcher.core.catalog.MillidaFiles
import net.millida.launcher.core.catalog.MillidaItem
import net.millida.launcher.core.catalog.MillidaKind
import net.millida.launcher.core.catalog.MillidaPaid
import net.millida.launcher.core.catalog.PaidVerdict
import net.millida.launcher.core.catalog.Pricing
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.packs.PackOriginStore
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.telemetry.EventData
import net.millida.launcher.core.telemetry.TelemetryType
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * One question before a file of the catalogue goes into a build: a link from
 * the site asks first, and a file made for another version asks again.
 */
data class ContentAsk(
    val slug: String,
    val title: String,
    val kind: MillidaKind,
    val targetId: String,
    val targetName: String,
    /** Null for a paid item whose files the server shows only after the purchase. */
    val pick: MillidaFilePick?,
    val pricing: Pricing = Pricing.FREE,
    val priceKopecks: Long? = null,
) {
    val paid: Boolean get() = MillidaPaid.isPaid(pricing, priceKopecks)

    val mismatch: Boolean get() = pick?.fits == false
}

data class ContentShelfState(
    val kind: MillidaKind? = null,
    val items: List<MillidaCard> = emptyList(),
    val page: Int = 0,
    val pages: Int = 0,
    val query: String = "",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val jobs: Map<String, Float?> = emptyMap(),
    val installed: Set<String> = emptySet(),
    val ask: ContentAsk? = null,
    val filters: MillidaFilters = MillidaFilters(),
    val facets: MillidaFacets? = null,
    val total: Int = 0,
) {
    val more: Boolean get() = page in 1 until pages
}

interface MillidaContentHost : MillidaShelfHost {
    val profiles: List<Profile>
    val activeProfileId: String?
}

/**
 * The Millida half of the content tabs: mods, resource packs, shaders, data
 * packs and maps the site lists. A card with a Modrinth source is the same
 * project the Modrinth shelf shows; one an author uploaded to us goes in as
 * our own file, after the purchase when it is paid.
 */
class MillidaContentModel(
    private val container: Container,
    private val scope: CoroutineScope,
    private val catalog: CatalogModel,
    private val shelf: MillidaShelfModel,
    private val host: MillidaContentHost,
) {
    private val mutable = MutableStateFlow(ContentShelfState())
    val state: StateFlow<ContentShelfState> = mutable.asStateFlow()

    private val api = MillidaCatalog(container.millidaAuth)
    private val files = MillidaFiles(container.millidaAuth, container.downloader, container.worlds)
    private val listSeq = AtomicLong()
    private val installedSeq = AtomicLong()
    private var listJob: Job? = null
    private var facetsFor: String? = null

    fun ensureLoaded(kind: MillidaKind, query: String) {
        val current = mutable.value
        val wanted = query.trim()
        if (current.kind == kind && current.page > 0 && current.query == wanted && current.error == null) return
        if (current.kind == kind && current.loading && current.query == wanted) return
        load(kind, wanted)
    }

    fun reload() {
        val kind = mutable.value.kind ?: return
        load(kind, mutable.value.query)
    }

    /** A changed filter reloads the first page; a changed version also changes which categories the section has. */
    fun setFilters(filters: MillidaFilters) {
        val current = mutable.value
        val kind = current.kind ?: return
        if (current.filters == filters) return
        mutable.update { it.copy(filters = filters) }
        load(kind, current.query)
    }

    private fun load(kind: MillidaKind, query: String) {
        listJob?.cancel()
        val seq = listSeq.incrementAndGet()
        val switched = mutable.value.kind != kind
        mutable.update {
            it.copy(
                kind = kind,
                query = query,
                items = if (switched) emptyList() else it.items,
                page = if (switched) 0 else it.page,
                filters = if (switched) MillidaFilters() else it.filters,
                facets = if (switched) null else it.facets,
                loading = true,
                loadingMore = false,
                error = null,
            )
        }
        val filters = mutable.value.filters
        val facetsKey = kind.section + "|" + filters.version.orEmpty()
        if (facetsKey != facetsFor) {
            facetsFor = facetsKey
            loadFacets(kind, filters.version, facetsKey)
        }
        listJob = scope.launch {
            try {
                val listing = withContext(Dispatchers.IO) { api.listing(kind.section, query, 1, filters) }
                if (seq != listSeq.get()) return@launch
                mutable.update { it.copy(items = listing.items, page = listing.page, pages = listing.pages, total = listing.total, loading = false) }
                shelf.checkAccess(listing.items)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (seq != listSeq.get()) return@launch
                mutable.update { it.copy(loading = false, error = host.errorText(error)) }
            }
        }
        refreshInstalled()
    }

    fun loadMore() {
        val current = mutable.value
        val kind = current.kind ?: return
        if (!current.more || current.loading || current.loadingMore) return
        val seq = listSeq.get()
        mutable.update { it.copy(loadingMore = true) }
        scope.launch {
            try {
                val next = withContext(Dispatchers.IO) { api.listing(kind.section, current.query, current.page + 1, current.filters) }
                if (seq != listSeq.get()) return@launch
                mutable.update { state ->
                    val shown = state.items.mapTo(HashSet()) { it.slug }
                    state.copy(
                        items = state.items + next.items.filterNot { it.slug in shown },
                        page = next.page,
                        pages = next.pages,
                        loadingMore = false,
                    )
                }
                shelf.checkAccess(next.items)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (seq != listSeq.get()) return@launch
                mutable.update { it.copy(loadingMore = false) }
                host.notice("Каталог Millida не догрузился: ${host.errorText(error).lowerFirst()}", true)
            }
        }
    }

    /** The filter sheets are a convenience: without facets they are hidden, the list itself still loads. */
    private fun loadFacets(kind: MillidaKind, version: String?, key: String) {
        scope.launch {
            val facets = try {
                withContext(Dispatchers.IO) { api.facets(kind.section, version) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(LOG_TAG, "фильтры раздела ${kind.section} не загрузились: ${error.message}")
                if (facetsFor == key) facetsFor = null
                return@launch
            }
            if (facetsFor == key) mutable.update { it.copy(facets = facets) }
        }
    }

    /** Which of our files the chosen build already has; a map is a new world each time and is not tracked. */
    fun refreshInstalled() {
        val target = target() ?: run {
            mutable.update { it.copy(installed = emptySet()) }
            return
        }
        val seq = installedSeq.incrementAndGet()
        scope.launch {
            val found = try {
                withContext(Dispatchers.IO) {
                    val dir = container.profiles.directory(target)
                    PackOriginStore.files(dir)
                        .filter { it.projectId.startsWith(MILLIDA_PREFIX) && File(dir, it.path).isFile }
                        .map { it.projectId.removePrefix(MILLIDA_PREFIX) }
                        .toSet()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(LOG_TAG, "не прочитали файлы каталога в сборке ${target.id}: ${error.message}")
                emptySet()
            }
            if (seq == installedSeq.get()) mutable.update { it.copy(installed = found) }
        }
    }

    /** The row's button: sign-in and purchase come first, the file is chosen for the build next. */
    fun install(card: MillidaCard) {
        val kind = mutable.value.kind ?: return
        val target = readyTarget(kind) ?: return
        if (card.slug in mutable.value.jobs) return
        if (card.paid && shelf.state.value.access[card.slug] != true) {
            shelf.buyThen(card.slug, card.title, card.pricing, card.priceKopecks ?: 0) { prepare(card.slug, card.title, kind, target.id, null) }
            return
        }
        prepare(card.slug, card.title, kind, target.id, null)
    }

    /** A link from the site: the item is already read, the player confirms the build it goes into. */
    fun askFromLink(item: MillidaItem, kind: MillidaKind) {
        val target = readyTarget(kind) ?: return
        val title = MillidaCatalog.displayName(item.title)
        val pick = MillidaFiles.pickFile(item.files, target.gameVersion, target.loader, kind)
        if (pick == null && !MillidaPaid.isPaid(item.pricing, item.priceKopecks)) {
            host.notice("У «$title» пока нет файла — открой его на сайте", true)
            return
        }
        mutable.update {
            it.copy(
                ask = ContentAsk(item.slug, title, kind, target.id, target.name, pick, item.pricing, item.priceKopecks),
            )
        }
    }

    fun dismissAsk() = mutable.update { it.copy(ask = null) }

    fun confirmAsk() {
        val ask = mutable.value.ask ?: return
        mutable.update { it.copy(ask = null) }
        if (ask.paid && shelf.state.value.access[ask.slug] != true) {
            val price = ask.priceKopecks ?: 0
            shelf.buyThen(ask.slug, ask.title, ask.pricing, price) { prepare(ask.slug, ask.title, ask.kind, ask.targetId, null) }
            return
        }
        prepare(ask.slug, ask.title, ask.kind, ask.targetId, ask.pick)
    }

    /**
     * The card is read again after a purchase: before it the server may hide
     * the files. A file made for another version asks once more.
     */
    private fun prepare(slug: String, title: String, kind: MillidaKind, targetId: String, confirmed: MillidaFilePick?) {
        val target = host.profiles.firstOrNull { it.id == targetId }
        if (target == null) {
            host.notice("Сборки больше нет — выбери другую и поставь заново", true)
            return
        }
        if (confirmed != null) {
            run(slug, title, kind, target, confirmed)
            return
        }
        scope.launch {
            val item = try {
                withContext(Dispatchers.IO) { api.item(slug) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                host.notice("Каталог не ответил — повтори: ${host.errorText(error).lowerFirst()}", true)
                return@launch
            }
            val modrinth = MillidaCatalog.modrinthSlugOf(item.sourceUrl)
            if (modrinth != null && kind.content != null) {
                catalog.openDetail(
                    CatalogItem(
                        projectId = modrinth,
                        slug = modrinth,
                        title = title,
                        description = item.summary,
                        iconUrl = item.icon,
                        source = CatalogSource.MODRINTH,
                    ),
                )
                return@launch
            }
            val pick = MillidaFiles.pickFile(item.files, target.gameVersion, target.loader, kind)
            if (pick == null) {
                host.notice("У «$title» пока нет файла", true)
                return@launch
            }
            if (!pick.fits) {
                mutable.update {
                    it.copy(ask = ContentAsk(slug, title, kind, target.id, target.name, pick, item.pricing, item.priceKopecks))
                }
                return@launch
            }
            run(slug, title, kind, target, pick)
        }
    }

    private fun run(slug: String, title: String, kind: MillidaKind, target: Profile, pick: MillidaFilePick) {
        if (slug in mutable.value.jobs) return
        mutable.update { it.copy(jobs = it.jobs + (slug to null)) }
        val began = System.currentTimeMillis()
        scope.launch {
            try {
                var shown = -1
                val placed = withContext(Dispatchers.IO) {
                    files.install(
                        profileDir = container.profiles.directory(target),
                        kind = kind,
                        slug = slug,
                        file = pick.file,
                        title = title,
                        scratch = File(container.layout.caches, SCRATCH),
                    ) { share ->
                        val percent = (share * 100).toInt()
                        if (percent != shown) {
                            shown = percent
                            mutable.update { it.copy(jobs = it.jobs + (slug to share)) }
                        }
                    }
                }
                container.uiSoundPlayer.play(UiSoundEvent.Install)
                container.telemetry.track(
                    TelemetryType.CONTENT_INSTALL,
                    EventData()
                        .put("name", title)
                        .put("kind", kind.id)
                        .put("mc", target.gameVersion)
                        .put("loader", target.loader.id)
                        .put("source", "millida")
                        .build(),
                    durationMs = System.currentTimeMillis() - began,
                )
                host.notice(
                    if (kind == MillidaKind.WORLD) {
                        "Карта «$placed» → «${target.name}»: заходи в одиночную игру"
                    } else {
                        "${kind.noun} → «${target.name}»: $placed"
                    },
                    false,
                )
                refreshInstalled()
                catalog.refreshInstalled()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                container.telemetry.failure(
                    TelemetryType.CONTENT_INSTALL,
                    error,
                    EventData().put("contentKind", kind.id).put("source", "millida").build(),
                )
                failed(error, slug, title)
            } finally {
                mutable.update { it.copy(jobs = it.jobs - slug) }
            }
        }
    }

    private fun failed(error: Throwable, slug: String, title: String) {
        when (val verdict = MillidaPaid.verdict(error.message)) {
            PaidVerdict.LOGIN -> {
                host.notice("Войди в аккаунт Millida — покупки хранятся в нём", true)
                host.signIn()
            }
            PaidVerdict.NO_ACCESS -> {
                shelf.markLocked(slug)
                host.notice(MillidaPaid.verdictText(verdict).orEmpty(), true)
            }
            else -> host.notice(
                MillidaPaid.verdictText(verdict) ?: "«$title» не поставился: ${host.errorText(error).lowerFirst()}",
                true,
            )
        }
    }

    private fun target(): Profile? = catalog.state.value.target(host.profiles, host.activeProfileId)

    private fun readyTarget(kind: MillidaKind): Profile? {
        val target = target()
        if (target == null) {
            host.notice("Сначала создай сборку — ${kind.noun.lowercase()} ставится в неё", true)
            return null
        }
        if (kind.loaderBound && target.loader == LoaderKind.VANILLA) {
            host.notice("В ванильной сборке моды не работают — выбери сборку с загрузчиком", true)
            return null
        }
        return target
    }

    private companion object {
        const val LOG_TAG = "millida"
        const val SCRATCH = "millida-files"
        const val MILLIDA_PREFIX = "millida:"
    }
}

private fun String.lowerFirst(): String = replaceFirstChar { it.lowercase() }
