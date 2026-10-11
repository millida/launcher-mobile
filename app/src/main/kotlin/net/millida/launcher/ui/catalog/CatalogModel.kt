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
import net.millida.launcher.core.catalog.CatalogFilter
import net.millida.launcher.core.catalog.CatalogItem
import net.millida.launcher.core.catalog.CatalogProject
import net.millida.launcher.core.catalog.CatalogSection
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.catalog.MillidaKind
import net.millida.launcher.core.catalog.ModpackLoader
import net.millida.launcher.core.catalog.mergeKey
import net.millida.launcher.core.content.DependencyPlan
import net.millida.launcher.core.content.InstalledIndex
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.packs.PackOriginStore
import net.millida.launcher.core.packs.PackProgress
import net.millida.launcher.core.packs.Packs
import net.millida.launcher.core.profile.ContentFile
import net.millida.launcher.core.profile.DISABLED_SUFFIX
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.telemetry.EventData
import net.millida.launcher.core.telemetry.TelemetryType
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MillidaIcon
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

enum class CatalogTab(val label: String, val section: CatalogSection) {
    MODS("Моды", CatalogSection.MOD),
    MODPACKS("Сборки", CatalogSection.MODPACK),
    RESOURCE_PACKS("Ресурс-паки", CatalogSection.RESOURCE_PACK),
    SHADERS("Шейдеры", CatalogSection.SHADER),
    DATA_PACKS("Дата-паки", CatalogSection.DATA_PACK),
    MAPS("Карты", CatalogSection.WORLD);

    val kind: ContentKind? get() = section.kind

    /** Maps come from the Millida catalogue alone; the other tabs also search Modrinth and CurseForge. */
    val foreign: Boolean get() = section.foreign

    /** The site section of the Millida shelf a content tab shows above the foreign list. */
    val millida: MillidaKind?
        get() = when (this) {
            MODS -> MillidaKind.MOD
            MODPACKS -> null
            RESOURCE_PACKS -> MillidaKind.RESOURCE_PACK
            SHADERS -> MillidaKind.SHADER
            DATA_PACKS -> MillidaKind.DATA_PACK
            MAPS -> MillidaKind.WORLD
        }

    val intoBuild: Boolean get() = kind != null

    /** Resource packs and shaders are chosen by how they look. */
    val showsCover: Boolean get() = this == RESOURCE_PACKS || this == SHADERS

    val icon: MillidaIcon
        get() = when (this) {
            MODS -> MIcons.box
            MODPACKS -> MIcons.chest
            RESOURCE_PACKS -> MIcons.image
            SHADERS -> MIcons.sun
            DATA_PACKS -> MIcons.book
            MAPS -> MIcons.map
        }
}

enum class ItemAction { INSTALL, REMOVE }

data class ItemJob(val action: ItemAction, val progress: Float? = null)

data class ModpackJob(
    val key: String,
    val title: String,
    val stage: String,
    val progress: Float? = null,
)

data class InstalledFile(val file: ContentFile, val versionId: String)

class InstalledLookup internal constructor(
    private val byProject: Map<String, InstalledFile>,
    private val byName: Map<String, InstalledFile>,
) {
    fun find(item: CatalogItem): InstalledFile? = byProject[item.projectId]
        ?: sequenceOf(item.slug, item.title)
            .map { InstalledIndex.normalize(it) }
            .filter { it.isNotEmpty() }
            .firstNotNullOfOrNull { byName[it] }

    companion object {
        val EMPTY = InstalledLookup(emptyMap(), emptyMap())
    }
}

data class CatalogDetail(
    val item: CatalogItem,
    val tab: CatalogTab,
    val targetId: String?,
    val project: CatalogProject? = null,
    val versions: List<CatalogVersion> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

data class DependencyAsk(
    val plan: DependencyPlan,
    val profileId: String,
    val kind: ContentKind,
)

data class CatalogState(
    val query: String = "",
    val tab: CatalogTab = CatalogTab.MODS,
    val filter: CatalogFilter = CatalogFilter.ALL,
    val targetId: String? = null,
    val items: List<CatalogItem> = emptyList(),
    val more: Boolean = false,
    val loadingMore: Boolean = false,
    val moreError: String? = null,
    val installed: InstalledLookup = InstalledLookup.EMPTY,
    val jobs: Map<String, ItemJob> = emptyMap(),
    val detail: CatalogDetail? = null,
    val dependencies: DependencyAsk? = null,
    val installingDependencies: Boolean = false,
    val modpack: ModpackJob? = null,
    val packVersion: String? = null,
    val packLoader: LoaderKind? = null,
) {
    fun target(profiles: List<Profile>, activeId: String?): Profile? {
        val wanted = targetId ?: activeId
        return profiles.firstOrNull { it.id == wanted } ?: profiles.firstOrNull()
    }
}

interface CatalogHost {
    val profiles: List<Profile>
    val activeProfileId: String?

    fun notice(text: String, isError: Boolean)

    fun errorText(error: Throwable): String

    fun buildInstalled(profile: Profile)

    fun markPending()

    suspend fun loadArea(block: suspend (current: () -> Boolean) -> Unit)
}

class CatalogModel(
    private val container: Container,
    private val scope: CoroutineScope,
    private val host: CatalogHost,
) {
    private val mutable = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = mutable.asStateFlow()

    private val modpacks: Packs get() = container.packs

    private var searchJob: Job? = null
    private var moreJob: Job? = null
    private var detailJob: Job? = null
    private var installedJob: Job? = null
    private val searchSeq = AtomicLong()
    private val installedSeq = AtomicLong()

    @Volatile
    private var requested: Request? = null

    @Volatile
    private var lastRequest: Request? = null

    @Volatile
    private var pagesLoaded = 0

    private data class Request(
        val query: String,
        val section: CatalogSection,
        val gameVersion: String?,
        val loaders: List<LoaderKind>,
        val filter: CatalogFilter,
    )

    /** Loads the shelf when what it would show has changed: another build, a migrated version, a first visit. */
    fun ensureLoaded() {
        val request = requestOf(mutable.value)
        val idle = searchJob?.isActive != true
        if (request != requested || (idle && mutable.value.items.isEmpty())) {
            search(debounce = false)
        }
        refreshInstalled()
    }

    fun setQuery(query: String) {
        if (query == mutable.value.query) return
        mutable.update { it.copy(query = query) }
        search(debounce = true)
    }

    fun setTab(tab: CatalogTab) {
        if (tab == mutable.value.tab) return
        mutable.update { it.copy(tab = tab, items = emptyList(), more = false, moreError = null) }
        search(debounce = false)
        refreshInstalled()
    }

    fun setFilter(filter: CatalogFilter) {
        if (filter == mutable.value.filter) return
        mutable.update { it.copy(filter = filter, items = emptyList(), more = false, moreError = null) }
        search(debounce = false)
    }

    /** The modpacks' «Версия» and «Загрузчик» of the desktop filters: a second tap on the picked one clears it. */
    fun togglePackVersion(version: String) {
        val next = version.takeIf { it != mutable.value.packVersion }
        mutable.update { it.copy(packVersion = next, items = emptyList(), more = false, moreError = null) }
        if (mutable.value.tab.kind == null) search(debounce = false)
    }

    fun togglePackLoader(loader: LoaderKind) {
        val next = loader.takeIf { it != mutable.value.packLoader }
        mutable.update { it.copy(packLoader = next, items = emptyList(), more = false, moreError = null) }
        if (mutable.value.tab.kind == null) search(debounce = false)
    }

    fun setTarget(profile: Profile) {
        mutable.update { it.copy(targetId = profile.id) }
        if (mutable.value.tab.intoBuild) search(debounce = false)
        refreshInstalled()
    }

    fun reload() = search(debounce = false)

    private fun search(debounce: Boolean) {
        searchJob?.cancel()
        moreJob?.cancel()
        val seq = searchSeq.incrementAndGet()
        val snapshot = mutable.value
        requested = requestOf(snapshot)
        mutable.update { it.copy(loadingMore = false, moreError = null) }
        val target = currentTarget()
        if (!snapshot.tab.foreign || (snapshot.tab.intoBuild && (target == null || takesNoMods(snapshot.tab.kind, target)))) {
            lastRequest = requested
            mutable.update { it.copy(items = emptyList(), more = false) }
            return
        }
        host.markPending()
        searchJob = scope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val request = requestOf(mutable.value)
            requested = request
            host.loadArea { current ->
                val page = container.catalog.searchPage(
                    query = request.query,
                    section = request.section,
                    gameVersion = request.gameVersion,
                    loaders = request.loaders,
                    filter = request.filter,
                    offset = 0,
                    limit = PAGE_SIZE,
                )
                if (current() && seq == searchSeq.get()) {
                    lastRequest = request
                    pagesLoaded = 1
                    val items = phoneItems(request, page.items)
                    mutable.update { it.copy(items = items.distinctBy { item -> item.key }, more = page.more) }
                }
            }
        }
    }

    fun loadMore() {
        val current = mutable.value
        if (!current.more || current.loadingMore || current.moreError != null) return
        if (searchJob?.isActive == true) return
        val request = lastRequest ?: return
        if (request != requested) return
        val seq = searchSeq.get()
        val offset = pagesLoaded * PAGE_SIZE
        mutable.update { it.copy(loadingMore = true) }
        moreJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    container.catalog.searchPage(
                        query = request.query,
                        section = request.section,
                        gameVersion = request.gameVersion,
                        loaders = request.loaders,
                        filter = request.filter,
                        offset = offset,
                        limit = PAGE_SIZE,
                    )
                }
                if (seq != searchSeq.get()) return@launch
                pagesLoaded += 1
                mutable.update { state ->
                    val shown = state.items.mapTo(HashSet()) { it.mergeKey }
                    state.copy(
                        items = (state.items + phoneItems(request, page.items).filterNot { it.mergeKey in shown }).distinctBy { it.key },
                        more = page.more,
                        loadingMore = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (seq != searchSeq.get()) return@launch
                mutable.update { it.copy(loadingMore = false, moreError = host.errorText(error)) }
            }
        }
    }

    fun retryMore() {
        mutable.update { it.copy(moreError = null) }
        loadMore()
    }

    /** Re-reads the build folder, so a mod deleted elsewhere stops showing as installed. */
    fun refreshInstalled() {
        installedJob?.cancel()
        installedJob = scope.launch { rescanInstalled() }
    }

    /** Awaited after an install or removal, so the button never flips back before the folder is re-read. */
    private suspend fun rescanInstalled() {
        val kind = mutable.value.tab.kind
        val target = currentTarget()
        val seq = installedSeq.incrementAndGet()
        if (kind == null || target == null) {
            mutable.update { it.copy(installed = InstalledLookup.EMPTY) }
            return
        }
        val found = try {
            withContext(Dispatchers.IO) { scanInstalled(target, kind) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "не прочитали содержимое сборки ${target.id}: ${error.message}")
            InstalledLookup.EMPTY
        }
        if (seq == installedSeq.get()) mutable.update { it.copy(installed = found) }
    }

    private fun scanInstalled(profile: Profile, kind: ContentKind): InstalledLookup {
        val dir = container.profiles.directory(profile)
        val files = container.content.list(dir, kind)
        val byPath = files.associateBy { "${kind.folder}/${it.name.removeSuffix(DISABLED_SUFFIX)}".lowercase() }
        val byProject = HashMap<String, InstalledFile>()
        PackOriginStore.files(dir).forEach { origin ->
            if (origin.projectId.isEmpty()) return@forEach
            val file = byPath[origin.path.lowercase()] ?: return@forEach
            byProject[origin.projectId] = InstalledFile(file, origin.versionId)
        }
        val byName = HashMap<String, InstalledFile>()
        files.forEach { file ->
            val entry = byProject.values.firstOrNull { it.file.name == file.name } ?: InstalledFile(file, "")
            sequenceOf(file.modId, file.title, InstalledIndex.baseName(file.name))
                .map { InstalledIndex.normalize(it) }
                .filter { it.isNotEmpty() }
                .forEach { key -> byName.putIfAbsent(key, entry) }
        }
        return InstalledLookup(byProject, byName)
    }

    fun install(item: CatalogItem, version: CatalogVersion? = null) {
        val kind = mutable.value.tab.kind ?: return
        val target = currentTarget()
        if (target == null) {
            host.notice("Сначала создай сборку — ${kind.noun} ставятся в неё", true)
            return
        }
        if (takesNoMods(kind, target)) {
            host.notice("В ванильной сборке моды не работают — выбери сборку на Fabric или Quilt", true)
            return
        }
        val key = item.key
        if (mutable.value.jobs.containsKey(key)) return
        val previous = mutable.value.installed.find(item)
        setJob(key, ItemJob(ItemAction.INSTALL))
        val began = System.currentTimeMillis()
        val source = item.source.name.lowercase()
        scope.launch {
            try {
                val dir = withContext(Dispatchers.IO) { container.profiles.directory(target) }
                val chosen = version ?: withContext(Dispatchers.IO) { pickVersion(item, target, kind) }
                var shown = -1
                val saved = withContext(Dispatchers.IO) {
                    container.catalog.install(chosen, kind, dir) { share ->
                        val percent = (share * 100).roundToInt()
                        if (percent != shown) {
                            shown = percent
                            setJob(key, ItemJob(ItemAction.INSTALL, share))
                        }
                    }
                }
                previous?.takeIf { it.file.name != saved.name }?.let { replaced ->
                    dropReplaced(item, replaced, saved, kind, dir)
                }
                container.uiSoundPlayer.play(UiSoundEvent.Install)
                container.telemetry.track(
                    TelemetryType.CONTENT_INSTALL,
                    EventData()
                        .put("name", item.title)
                        .put("kind", kind.slug)
                        .put("mc", target.gameVersion)
                        .put("loader", target.loader.id)
                        .put("source", source)
                        .build(),
                    durationMs = System.currentTimeMillis() - began,
                )
                rescanInstalled()
                if (kind == ContentKind.MOD) askDependencies(target, chosen, kind)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                container.telemetry.failure(
                    TelemetryType.CONTENT_INSTALL,
                    error,
                    EventData().put("contentKind", kind.slug).put("source", source).build(),
                )
                host.notice("«${item.title}» не поставился: ${host.errorText(error).lowerFirst()}", true)
            } finally {
                clearJob(key)
            }
        }
    }

    // A match by name alone may be a different mod, so only a file the build
    // remembers as this project is replaced; the other one is left to the player.
    private suspend fun dropReplaced(
        item: CatalogItem,
        replaced: InstalledFile,
        saved: File,
        kind: ContentKind,
        dir: File,
    ) {
        val name = replaced.file.name
        if (replaced.versionId.isEmpty()) {
            host.notice("Старый файл $name остался в сборке — удали его, если это тот же «${item.title}»", true)
            return
        }
        val removed = try {
            withContext(Dispatchers.IO) {
                val deleted = container.content.delete(dir, replaced.file)
                val path = name.removeSuffix(DISABLED_SUFFIX)
                if (deleted && path != saved.name) {
                    runCatching { PackOriginStore.forget(dir, "${kind.folder}/$path") }
                        .onFailure { Log.w(LOG_TAG, "запись о файле $path осталась: ${it.message}") }
                }
                deleted
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "старая версия $name не удалилась: ${error.message}")
            false
        }
        if (!removed) host.notice("Старая версия $name осталась в сборке — удали её вручную", true)
    }

    private suspend fun pickVersion(item: CatalogItem, target: Profile, kind: ContentKind): CatalogVersion {
        val versions = container.catalog.versionsFor(item, target.gameVersion, loaderFor(kind, target))
            .filter { it.primaryFile() != null }
        return versions.firstOrNull { it.versionType == "release" } ?: versions.firstOrNull()
            ?: throw IOException("нет версии под ${buildLabel(kind, target)} — выбери другую сборку")
    }

    private suspend fun askDependencies(target: Profile, chosen: CatalogVersion, kind: ContentKind) {
        val plan = try {
            withContext(Dispatchers.IO) {
                container.contentDependencies.plan(target, container.profiles.directory(target), chosen, kind)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "зависимости не проверены: ${error.message}")
            null
        }
        if (plan != null && !plan.empty) {
            mutable.update { it.copy(dependencies = DependencyAsk(plan, target.id, kind)) }
        }
    }

    fun installDependencies() {
        val ask = mutable.value.dependencies ?: return
        if (mutable.value.installingDependencies) return
        val profile = host.profiles.firstOrNull { it.id == ask.profileId }
        if (profile == null) {
            mutable.update { it.copy(dependencies = null) }
            host.notice("Сборки больше нет — выбери другую и поставь мод заново", true)
            return
        }
        mutable.update { it.copy(installingDependencies = true) }
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    container.contentDependencies.install(
                        container.profiles.directory(profile),
                        ask.plan.missing,
                        ask.kind,
                    )
                }
                rescanInstalled()
                if (report.failed.isEmpty()) {
                    mutable.update { it.copy(dependencies = null) }
                    container.uiSoundPlayer.play(UiSoundEvent.Install)
                } else {
                    host.notice(
                        "Не поставилось ${report.failed.size} из ${ask.plan.missing.size}: " +
                            "${report.failed.keys.joinToString(", ")} — повтори установку",
                        true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                host.notice("Зависимости не поставились: ${host.errorText(error).lowerFirst()}", true)
            } finally {
                mutable.update { it.copy(installingDependencies = false) }
            }
        }
    }

    fun dismissDependencies() = mutable.update { it.copy(dependencies = null) }

    fun remove(item: CatalogItem) {
        val kind = mutable.value.tab.kind ?: return
        val target = currentTarget() ?: return
        val installed = mutable.value.installed.find(item) ?: return
        val key = item.key
        if (mutable.value.jobs.containsKey(key)) return
        setJob(key, ItemJob(ItemAction.REMOVE))
        scope.launch {
            try {
                val removed = withContext(Dispatchers.IO) {
                    val dir = container.profiles.directory(target)
                    val deleted = container.content.delete(dir, installed.file)
                    if (deleted) {
                        val path = "${kind.folder}/${installed.file.name.removeSuffix(DISABLED_SUFFIX)}"
                        runCatching { PackOriginStore.forget(dir, path) }
                            .onFailure { Log.w(LOG_TAG, "запись о файле $path осталась: ${it.message}") }
                    }
                    deleted
                }
                if (removed) {
                    container.uiSoundPlayer.play(UiSoundEvent.Delete)
                } else {
                    host.notice("Файл ${installed.file.name} не удалился — закрой игру и повтори", true)
                }
                rescanInstalled()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                host.notice("«${item.title}» не удалился: ${host.errorText(error).lowerFirst()}", true)
            } finally {
                clearJob(key)
            }
        }
    }

    fun openDetail(item: CatalogItem) {
        val snapshot = mutable.value
        mutable.update { it.copy(detail = CatalogDetail(item, snapshot.tab, currentTarget()?.id)) }
        loadDetail()
    }

    fun retryDetail() = loadDetail()

    fun closeDetail() {
        detailJob?.cancel()
        mutable.update { it.copy(detail = null) }
    }

    private fun loadDetail() {
        val detail = mutable.value.detail ?: return
        detailJob?.cancel()
        mutable.update { it.copy(detail = detail.copy(loading = true, error = null)) }
        val target = host.profiles.firstOrNull { it.id == detail.targetId }
        detailJob = scope.launch {
            val project = if (detail.project != null) {
                detail.project
            } else {
                try {
                    withContext(Dispatchers.IO) { container.catalog.project(detail.item) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(LOG_TAG, "страница проекта не загрузилась: ${error.message}")
                    null
                }
            }
            val versions: Result<List<CatalogVersion>> = try {
                Result.success(withContext(Dispatchers.IO) { detailVersions(detail, target) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            mutable.update { state ->
                val open = state.detail
                if (open == null || open.item.key != detail.item.key) {
                    state
                } else {
                    val loaded = versions.getOrDefault(emptyList())
                    state.copy(
                        detail = open.copy(
                            item = canonicalItem(open.item, loaded),
                            project = project,
                            versions = loaded,
                            loading = false,
                            error = versions.exceptionOrNull()?.let { host.errorText(it) },
                        ),
                    )
                }
            }
        }
    }

    private suspend fun detailVersions(detail: CatalogDetail, target: Profile?): List<CatalogVersion> {
        val kind = detail.tab.kind ?: return phoneModpackVersions(detail.item)
        return container.catalog.versionsFor(detail.item, target?.gameVersion, target?.let { loaderFor(kind, it) })
            .filter { it.primaryFile() != null }
    }

    private suspend fun phoneModpackVersions(item: CatalogItem): List<CatalogVersion> {
        return container.catalog.versionsFor(item, null, null)
            .filter { version -> ModpackLoader.phoneFits(version.loaders, version.gameVersions) }
    }

    fun installModpack(item: CatalogItem, version: CatalogVersion? = null) {
        runModpack(item.key, item.title, source = item.source.name.lowercase()) { onStep ->
            val chosen = version ?: modpackPick(phoneModpackVersions(item))
                ?: throw IOException("нет версии для телефона — выбери другую сборку")
            when (item.source) {
                CatalogSource.MODRINTH -> modpacks.installModrinthModpack(item.projectId, chosen.id, null, onStep)
                CatalogSource.CURSEFORGE -> modpacks.installCurseForgeModpack(item.projectId, chosen.id, null, onStep)
            }
        }
    }

    /**
     * One modpack at a time, whichever catalogue it comes from: two archives
     * unpacking at once on a phone is how both of them fail. [onFailure]
     * answers true when it has told the player itself, as the key window does.
     * A [source] makes it a `modpack_install` in the telemetry, as on the desktop;
     * updates and generated builds pass none.
     */
    fun runModpack(
        key: String,
        title: String,
        onFailure: (Throwable) -> Boolean = { false },
        onInstalled: (Profile) -> Unit = {},
        source: String? = null,
        work: suspend (onStep: (PackProgress) -> Unit) -> Profile,
    ): Boolean {
        mutable.value.modpack?.let { running ->
            host.notice("Дождись, пока поставится «${running.title}»", true)
            return false
        }
        mutable.update { it.copy(modpack = ModpackJob(key, title, "Читаем сборку")) }
        val began = System.currentTimeMillis()
        scope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { work { step -> packStep(key, step) } }
                container.uiSoundPlayer.play(UiSoundEvent.Install)
                source?.let {
                    container.telemetry.track(
                        TelemetryType.MODPACK_INSTALL,
                        EventData()
                            .put("name", title)
                            .put("kind", "modpack")
                            .put("mc", profile.gameVersion)
                            .put("loader", profile.loader.id)
                            .put("source", it)
                            .build(),
                        durationMs = System.currentTimeMillis() - began,
                    )
                }
                mutable.update { state ->
                    state.copy(
                        modpack = null,
                        detail = state.detail?.takeUnless { it.item.key == key },
                    )
                }
                host.buildInstalled(profile)
                onInstalled(profile)
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(modpack = null) }
                throw cancelled
            } catch (error: Throwable) {
                mutable.update { it.copy(modpack = null) }
                source?.let { container.telemetry.failure(TelemetryType.MODPACK_INSTALL, error, EventData().put("source", it).build()) }
                if (!onFailure(error)) {
                    host.notice("Сборка «$title» не поставилась: ${host.errorText(error).lowerFirst()}", true)
                }
            }
        }
        return true
    }

    private fun packStep(key: String, step: PackProgress) {
        val share = if (step.total > 0) (step.done.toFloat() / step.total).coerceIn(0f, 1f) else null
        mutable.update { state ->
            val running = state.modpack
            if (running == null || running.key != key) {
                state
            } else if (running.stage == step.stage && running.progress.percent() == share.percent()) {
                state
            } else {
                state.copy(modpack = running.copy(stage = step.stage, progress = share))
            }
        }
    }

    private fun Float?.percent(): Int = if (this == null) -1 else (this * 100).roundToInt()

    private fun setJob(key: String, job: ItemJob) = mutable.update { it.copy(jobs = it.jobs + (key to job)) }

    private fun clearJob(key: String) = mutable.update { it.copy(jobs = it.jobs - key) }

    private fun currentTarget(): Profile? = mutable.value.target(host.profiles, host.activeProfileId)

    /** Modpacks only: a pack whose every version is Forge before 1.13 never starts on a phone. */
    private fun phoneItems(request: Request, items: List<CatalogItem>): List<CatalogItem> =
        if (request.section != CatalogSection.MODPACK) {
            items
        } else {
            items.filter { ModpackLoader.phoneFits(it.loaderIds, it.gameVersions) }
        }

    private fun requestOf(state: CatalogState): Request {
        val target = state.target(host.profiles, host.activeProfileId)
        val kind = state.tab.kind
        return if (kind == null) {
            Request(
                query = state.query.trim(),
                section = state.tab.section,
                gameVersion = state.packVersion,
                loaders = state.packLoader?.let { listOf(it) } ?: ModpackLoader.PHONE_LOADERS,
                filter = state.filter,
            )
        } else {
            Request(
                query = state.query.trim(),
                section = state.tab.section,
                gameVersion = target?.gameVersion,
                loaders = target?.let { loaderFor(kind, it) }?.let { listOf(it) }.orEmpty(),
                filter = state.filter,
            )
        }
    }

    companion object {
        const val PAGE_SIZE = 20

        /** The desktop's version shelf without 1.12.2, whose Forge packs never start on a phone. */
        val PACK_VERSIONS = listOf("26.3", "26.2", "26.1.2", "1.21.11", "1.21.4", "1.21.1", "1.20.1", "1.19.2", "1.16.5")
        private const val SEARCH_DEBOUNCE_MS = 350L
        private const val LOG_TAG = "millida"

        /** Only mods are bound to a loader; a resource pack or a shader fits any build of its version. */
        /**
         * The Millida catalogue and site links open a Modrinth project by its slug,
         * while an install records the project id the versions carry; left as the
         * slug, the item never matches its own file and the sheet keeps offering it.
         */
        fun canonicalItem(item: CatalogItem, versions: List<CatalogVersion>): CatalogItem {
            if (item.source != CatalogSource.MODRINTH) return item
            val id = versions.firstOrNull { it.projectId.isNotEmpty() }?.projectId ?: return item
            return if (id == item.projectId) item else item.copy(projectId = id)
        }

        fun loaderFor(kind: ContentKind, target: Profile): LoaderKind? =
            if (kind == ContentKind.MOD && target.loader != LoaderKind.VANILLA) target.loader else null

        fun buildLabel(kind: ContentKind, target: Profile): String =
            if (kind == ContentKind.MOD) target.label else target.gameVersion

        fun takesNoMods(kind: ContentKind?, target: Profile?): Boolean =
            kind == ContentKind.MOD && target?.loader == LoaderKind.VANILLA

        fun modpackPick(versions: List<CatalogVersion>): CatalogVersion? {
            val installable = versions.filter { version ->
                version.primaryFile()?.filename?.contains("server", ignoreCase = true) != true &&
                    (version.source != CatalogSource.MODRINTH || version.files.any { it.filename.endsWith(".mrpack") })
            }
            return installable.firstOrNull { it.versionType == "release" } ?: installable.firstOrNull()
        }
    }
}

internal val ContentKind.noun: String
    get() = when (this) {
        ContentKind.MOD -> "моды"
        ContentKind.RESOURCE_PACK -> "ресурс-паки"
        ContentKind.SHADER -> "шейдеры"
        ContentKind.DATA_PACK -> "дата-паки"
    }

private fun String.lowerFirst(): String = replaceFirstChar { it.lowercase() }
