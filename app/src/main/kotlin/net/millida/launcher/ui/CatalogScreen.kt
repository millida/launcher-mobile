package net.millida.launcher.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.millida.launcher.Area
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.UiState
import net.millida.launcher.core.catalog.CatalogFilter
import net.millida.launcher.core.catalog.CatalogItem
import net.millida.launcher.core.catalog.MillidaFilters
import net.millida.launcher.core.catalog.MillidaKind
import net.millida.launcher.core.catalog.ModpackLoader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.catalog.NewBuild
import net.millida.launcher.ui.catalog.CatalogDetailSheet
import net.millida.launcher.ui.catalog.CatalogItemPage
import net.millida.launcher.ui.catalog.CatalogModel
import net.millida.launcher.ui.catalog.CatalogTab
import net.millida.launcher.ui.catalog.DependencySheet
import net.millida.launcher.ui.catalog.DownloadsFact
import net.millida.launcher.ui.catalog.ItemAction
import net.millida.launcher.ui.catalog.ItemJob
import net.millida.launcher.ui.catalog.ModpackJob
import net.millida.launcher.ui.catalog.TargetPickerSheet
import net.millida.launcher.ui.catalog.AiBuilderModel
import net.millida.launcher.ui.catalog.AiBuilderSheet
import net.millida.launcher.ui.catalog.MillidaDetailSheet
import net.millida.launcher.ui.catalog.MillidaItemPage
import net.millida.launcher.ui.catalog.MillidaShelfModel
import net.millida.launcher.ui.catalog.PurchasesSheet
import net.millida.launcher.ui.catalog.millidaContentShelf
import net.millida.launcher.ui.catalog.millidaShelf

private const val CATALOG_LOAD_AHEAD = 5
private const val MILLIDA_SEARCH_DEBOUNCE_MS = 350L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CatalogScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val catalog = model.catalogModel
    val cs = catalog.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    var picking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CatalogItem?>(null) }

    val target = cs.target(state.profiles, state.activeProfileId)
    val kind = cs.tab.kind
    val loading = state.isLoading(Area.CATALOG)
    val error = state.errorOf(Area.CATALOG)
    val noMods = CatalogModel.takesNoMods(kind, target)
    val needsBuild = (cs.tab.intoBuild || !cs.tab.foreign) && (target == null || noMods)
    val moreError = cs.moreError

    LaunchedEffect(target?.id, target?.gameVersion, target?.loader) { catalog.ensureLoaded() }

    val shelf = model.millidaShelf
    val ms = shelf.state.collectAsStateWithLifecycle().value
    val showsMillida = cs.tab == CatalogTab.MODPACKS && cs.filter == CatalogFilter.ALL
    LaunchedEffect(showsMillida, cs.query) {
        if (!showsMillida) return@LaunchedEffect
        delay(MILLIDA_SEARCH_DEBOUNCE_MS)
        shelf.ensureLoaded(cs.query)
    }

    val content = model.millidaContent
    val mc = content.state.collectAsStateWithLifecycle().value
    val contentKind = cs.tab.millida
    val showsContent = contentKind != null && !needsBuild && (cs.filter == CatalogFilter.ALL || !cs.tab.foreign)
    LaunchedEffect(showsContent, contentKind, cs.query) {
        if (!showsContent || contentKind == null) return@LaunchedEffect
        delay(MILLIDA_SEARCH_DEBOUNCE_MS)
        content.ensureLoaded(contentKind, cs.query)
    }
    LaunchedEffect(showsContent, target?.id) { if (showsContent) content.refreshInstalled() }

    val aiBuilder = model.aiBuilder
    val scope = rememberCoroutineScope()

    fun startNewBuild(kindId: String, title: String, versions: List<String>, loaders: List<String>, then: () -> Unit) {
        val plan = NewBuild.plan(kindId, versions, loaders) ?: return
        scope.launch {
            val before = model.ui.value.profiles.mapTo(HashSet()) { it.id }
            model.createProfile(NewBuild.name(title), plan.version, plan.loader).join()
            val made = model.ui.value.profiles.firstOrNull { it.id !in before } ?: return@launch
            catalog.setTarget(made)
            then()
        }
    }
    val ai = aiBuilder.state.collectAsStateWithLifecycle().value

    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - CATALOG_LOAD_AHEAD
        }
    }
    LaunchedEffect(nearEnd, cs.items.size, cs.more, cs.loadingMore, moreError) {
        if (nearEnd) catalog.loadMore()
    }

    val wide = LocalWideLayout.current
    val wideHub = LocalWideHub.current
    val columns = if (wide && !cs.tab.showsCover) 1 else adaptiveColumns()

    val searchField: @Composable () -> Unit = {
        MField(
            value = cs.query,
            onValueChange = catalog::setQuery,
            placeholder = "Поиск по разделу «${cs.tab.label}»",
            leadingIcon = MIcons.search,
            clearable = true,
            imeAction = ImeAction.Search,
            onImeAction = {
                keyboard?.hide()
                catalog.reload()
            },
        )
    }
    val sectionTabs: @Composable () -> Unit = {
        MTabs(
            tabs = CatalogTab.entries,
            selected = cs.tab,
            onSelect = catalog::setTab,
            label = { it.label },
            scrollable = true,
        )
    }
    val scopeLine: @Composable () -> Unit = {
        if (kind != null && target != null) {
            MChip(
                text = "Для «${target.name}» · ${CatalogModel.buildLabel(kind, target)}",
                selected = true,
                onClick = { picking = true },
                icon = MIcons.box,
            )
        } else if (cs.tab == CatalogTab.MAPS && target != null) {
            MChip(
                text = "Для «${target.name}» · ${target.gameVersion}",
                selected = true,
                onClick = { picking = true },
                icon = MIcons.box,
            )
        } else if (kind == null && cs.tab.foreign) {
            Text(
                "Fabric, Quilt, Forge и NeoForge · каждая встаёт отдельной сборкой",
                style = MillidaType.small,
                color = millida.fgFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val packFilters: @Composable () -> Unit = {
        if (cs.tab == CatalogTab.MODPACKS) {
            PackFilters(
                version = cs.packVersion,
                loader = cs.packLoader,
                onVersion = catalog::togglePackVersion,
                onLoader = catalog::togglePackLoader,
            )
        }
    }
    val sources: @Composable () -> Unit = {
        MSegmented(
            options = CatalogFilter.entries,
            selected = cs.filter,
            onSelect = catalog::setFilter,
            label = { it.label },
        )
    }
    val aiRow: @Composable () -> Unit = {
        MListRow(title = "Собрать с ИИ", icon = MIcons.sparkle, onClick = aiBuilder::open)
    }
    val results: LazyListScope.() -> Unit = {
        cs.modpack?.let { job ->
            item(key = "modpack-job") {
                MBusyCard(label = "Ставим «${job.title}»", progress = job.progress, note = job.stage)
            }
        }
        if (showsMillida) {
            millidaShelf(
                shelf = ms,
                modpack = cs.modpack,
                onOpen = shelf::open,
                onPrimary = shelf::primary,
                onMore = shelf::loadMore,
                onRetry = shelf::reload,
                onPurchases = shelf::openPurchases,
                onFilters = shelf::setFilters,
                onShowAll = {
                    catalog.setQuery("")
                    shelf.setFilters(MillidaFilters())
                },
            )
        }
        if (showsContent) {
            millidaContentShelf(
                content = mc,
                shelf = ms,
                icon = cs.tab.icon,
                foreignBelow = cs.tab.foreign,
                onInstall = content::install,
                onMore = content::loadMore,
                onRetry = content::reload,
                onPurchases = shelf::openPurchases,
                onFilters = content::setFilters,
                onShowAll = {
                    catalog.setQuery("")
                    content.setFilters(MillidaFilters())
                },
                onNewBuild = { card ->
                    startNewBuild(contentKind?.id.orEmpty(), card.title, card.versions, card.loaders) { content.install(card) }
                },
            )
        }
        if (error != null && !loading && cs.items.isNotEmpty() && !needsBuild) {
            item(key = "reload-error") {
                MErrorCard(message = error, onRetry = { model.retry(Area.CATALOG) })
            }
        }
        if (loading && cs.items.isNotEmpty() && !needsBuild) {
            item(key = "reloading") { MProgressBar(progress = null) }
        }
        when {
            noMods -> item(key = "no-mods") {
                MEmptyState(
                    icon = MIcons.box,
                    title = "Сборка без модов",
                    actionText = "Выбрать сборку",
                    onAction = { picking = true },
                )
            }
            needsBuild -> item(key = "no-build") {
                MEmptyState(
                    icon = MIcons.chest,
                    title = "Сначала нужна сборка",
                    actionText = "Готовые сборки",
                    onAction = { catalog.setTab(CatalogTab.MODPACKS) },
                    actionIcon = MIcons.chest,
                )
            }
            !cs.tab.foreign -> Unit
            cs.items.isEmpty() && loading -> items(6, key = { "skeleton-$it" }) { MSkeletonRow() }
            cs.items.isEmpty() && error != null -> item(key = "error") {
                MErrorCard(message = error, onRetry = { model.retry(Area.CATALOG) })
            }
            cs.items.isEmpty() -> item(key = "empty") {
                val searching = cs.query.isNotBlank()
                MEmptyState(
                    icon = if (searching) MIcons.search else cs.tab.icon,
                    title = if (searching) "Ничего не нашлось" else "Здесь пока пусто",
                    actionText = if (searching) "Показать весь раздел" else null,
                    onAction = if (searching) ({ catalog.setQuery("") }) else null,
                    primary = false,
                )
            }
        }
        if (!needsBuild) {
            adaptiveItems(cs.items, columns, key = { it.key }, contentType = "catalog-item") { item ->
                val intoBuild = cs.tab.intoBuild
                CatalogCard(
                    item = item,
                    busy = !intoBuild && cs.modpack != null,
                    onInstall = {
                        if (intoBuild) catalog.install(item) else catalog.installModpack(item)
                    },
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(durationMillis = 180, easing = MillidaMotion.steps(3)),
                        placementSpec = null,
                        fadeOutSpec = null,
                    ),
                    installed = intoBuild && cs.installed.find(item) != null,
                    job = cs.jobs[item.key],
                    modpackJob = cs.modpack?.takeIf { it.key == item.key },
                    installLabel = if (intoBuild) "В сборку" else "Установить",
                    installIcon = if (intoBuild) MIcons.plus else MIcons.download,
                    onNewBuild = cs.tab.kind
                        ?.let { contentKind -> MillidaKind.entries.firstOrNull { it.content == contentKind }?.id }
                        ?.takeIf { intoBuild && NewBuild.plan(it, item.gameVersions, item.loaderIds) != null }
                        ?.let { kindId -> { startNewBuild(kindId, item.title, item.gameVersions, item.loaderIds) { catalog.install(item) } } },
                    icon = cs.tab.icon,
                    cover = if (cs.tab.showsCover) item.cover else null,
                    onRemove = { removing = item },
                    onOpen = { catalog.openDetail(item) },
                )
            }
            if (cs.items.isNotEmpty()) {
                when {
                    cs.loadingMore -> item(key = "more-loading") { MSkeletonRow() }
                    moreError != null -> item(key = "more-error") {
                        MErrorCard(message = moreError, onRetry = catalog::retryMore)
                    }
                }
            }
        }
    }

    val millidaDetail = ms.detail
    val itemDetail = cs.detail
    val itemPage: (@Composable () -> Unit)? = when {
        !wide -> null
        millidaDetail != null -> {
            {
                MillidaItemPage(
                    detail = millidaDetail,
                    bought = ms.access[millidaDetail.slug] == true,
                    waiting = millidaDetail.slug in ms.waiting,
                    signedIn = state.millidaSignedIn,
                    job = cs.modpack?.takeIf { it.key == MillidaShelfModel.key(millidaDetail.slug) },
                    busy = cs.modpack != null,
                    onBack = shelf::closeDetail,
                    onPrimary = shelf::primary,
                    onKey = { shelf.openKey(millidaDetail.slug, millidaDetail.title, "") },
                    onRetry = shelf::retryDetail,
                    onSite = { shelf.openOnSite(MillidaShelfModel.SECTION, millidaDetail.slug) },
                )
            }
        }
        itemDetail != null -> {
            {
                CatalogItemPage(
                    detail = itemDetail,
                    target = state.profiles.firstOrNull { it.id == itemDetail.targetId } ?: target,
                    installed = if (itemDetail.tab.intoBuild) cs.installed.find(itemDetail.item) else null,
                    job = cs.jobs[itemDetail.item.key],
                    modpack = cs.modpack,
                    onBack = catalog::closeDetail,
                    onInstall = { version ->
                        if (itemDetail.tab.intoBuild) {
                            catalog.install(itemDetail.item, version)
                        } else {
                            catalog.installModpack(itemDetail.item, version)
                        }
                    },
                    onRemove = { removing = itemDetail.item },
                    onRetry = catalog::retryDetail,
                    onOpenPage = { url -> model.openLinkOrNotice(context, url) },
                )
            }
        }
        else -> null
    }

    if (wide) {
        WideCatalog(
            modifier = modifier,
            listState = listState,
            sectionTitle = cs.tab.label,
            foreign = cs.tab.foreign,
            modpacks = cs.tab == CatalogTab.MODPACKS,
            searchField = searchField,
            sectionTabs = sectionTabs,
            scopeLine = scopeLine,
            sources = sources,
            aiRow = aiRow,
            results = results,
            onLobby = {
                wideHub.library = false
                model.back()
            },
            onLibrary = {
                wideHub.library = true
                model.open(Screen.HOME)
            },
            filters = packFilters,
            itemPage = itemPage,
        )
    } else {
        MScreen(
            modifier = modifier.imePadding(),
            title = "Каталог",
            listState = listState,
        ) {
            item(key = "search") { searchField() }
            stickyHeader(key = "tabs") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(millida.bg)
                        .padding(vertical = 4.dp),
                ) {
                    sectionTabs()
                }
            }
            item(key = "scope") { scopeLine() }
            if (cs.tab.foreign) {
                item(key = "sources") { sources() }
            }
            if (cs.tab == CatalogTab.MODPACKS) {
                item(key = "ai-builder") { aiRow() }
            }
            results()
        }
    }

    if (picking) {
        TargetPickerSheet(
            profiles = state.profiles,
            selectedId = target?.id,
            onPick = { profile ->
                picking = false
                catalog.setTarget(profile)
            },
            onDismiss = { picking = false },
        )
    }

    if (!wide) {
        cs.detail?.let { detail ->
            CatalogDetailSheet(
                detail = detail,
                target = state.profiles.firstOrNull { it.id == detail.targetId } ?: target,
                installed = if (detail.tab.intoBuild) cs.installed.find(detail.item) else null,
                job = cs.jobs[detail.item.key],
                modpack = cs.modpack,
                onDismiss = catalog::closeDetail,
                onInstall = { version ->
                    if (detail.tab.intoBuild) catalog.install(detail.item, version) else catalog.installModpack(detail.item, version)
                },
                onRemove = { removing = detail.item },
                onRetry = catalog::retryDetail,
                onOpenPage = { url -> model.openLinkOrNotice(context, url) },
            )
        }
    }

    millidaDetail?.takeIf { !wide }?.let { detail ->
        MillidaDetailSheet(
            detail = detail,
            bought = ms.access[detail.slug] == true,
            waiting = detail.slug in ms.waiting,
            signedIn = state.millidaSignedIn,
            job = cs.modpack?.takeIf { it.key == MillidaShelfModel.key(detail.slug) },
            busy = cs.modpack != null,
            onDismiss = shelf::closeDetail,
            onPrimary = shelf::primary,
            onKey = { shelf.openKey(detail.slug, detail.title, "") },
            onRetry = shelf::retryDetail,
            onSite = { shelf.openOnSite(MillidaShelfModel.SECTION, detail.slug) },
        )
    }

    if (ai.open) {
        AiBuilderSheet(
            ai = ai,
            signedIn = state.millidaSignedIn,
            job = cs.modpack?.takeIf { it.key.startsWith(AiBuilderModel.JOB_PREFIX) },
            busy = cs.modpack != null,
            onPrompt = aiBuilder::setPrompt,
            onBuild = aiBuilder::build,
            onTitle = aiBuilder::setTitle,
            onToggle = aiBuilder::toggle,
            onCreate = aiBuilder::create,
            onReset = aiBuilder::reset,
            onSignIn = model::loginMillida,
            onDismiss = aiBuilder::close,
        )
    }

    if (ms.purchasesOpen) {
        PurchasesSheet(
            shelf = ms,
            signedIn = state.millidaSignedIn,
            onSignIn = model::loginMillida,
            onRetry = shelf::loadPurchases,
            onOpen = shelf::openPurchase,
            onSite = { purchase -> purchase.section?.let { shelf.openOnSite(it, purchase.slug) } },
            onDismiss = shelf::closePurchases,
        )
    }

    cs.dependencies?.let { ask ->
        DependencySheet(
            ask = ask,
            installing = cs.installingDependencies,
            onInstall = catalog::installDependencies,
            onDismiss = catalog::dismissDependencies,
        )
    }

    removing?.let { item ->
        MConfirmDialog(
            title = "Удалить «${item.title}»?",
            text = target?.let { "Файл уйдёт из сборки «${it.name}»" },
            confirm = "Удалить",
            onConfirm = {
                removing = null
                catalog.remove(item)
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
internal fun CatalogCard(
    item: CatalogItem,
    busy: Boolean,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
    installed: Boolean = false,
    job: ItemJob? = null,
    modpackJob: ModpackJob? = null,
    installLabel: String = "Поставить",
    icon: MillidaIcon = MIcons.box,
    cover: String? = null,
    onRemove: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    installIcon: MillidaIcon = MIcons.download,
    onNewBuild: (() -> Unit)? = null,
) {
    val colors = millida
    MCard(modifier = modifier, onClick = onOpen, contentPadding = 14.dp) {
        if (!cover.isNullOrBlank()) {
            MRemoteImage(
                url = cover,
                icon = MIcons.image,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(128.dp),
                shape = MillidaShape.control,
            )
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.Top) {
            MRemoteImage(url = item.iconUrl, icon = icon, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MillidaType.rowTitle,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.author.isNotBlank()) {
                    Text(
                        "от ${item.author}",
                        style = MillidaType.small,
                        color = colors.fgFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.description,
                        style = MillidaType.small,
                        color = colors.fgSubtle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DownloadsFact(item.downloads)
                if (installed) {
                    MBadge("Установлено", colors.accentText, colors.accentSoft, icon = MIcons.check)
                } else {
                    MBadge(item.source.label, colors.info, colors.infoSoft)
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                modpackJob != null -> MButton(
                    text = percentOf(modpackJob.progress) ?: "Ставим",
                    onClick = {},
                    size = MButtonSize.Small,
                    loading = true,
                )
                job != null -> MButton(
                    text = if (job.action == ItemAction.REMOVE) "Удаляем" else percentOf(job.progress) ?: "Ставим",
                    onClick = {},
                    size = MButtonSize.Small,
                    loading = true,
                )
                installed && onRemove != null -> MDangerButton(text = "Удалить", onClick = onRemove, icon = MIcons.trash)
                installed -> Unit
                else -> MPrimaryButton(
                    text = installLabel,
                    onClick = onInstall,
                    enabled = !busy,
                    icon = installIcon,
                    size = MButtonSize.Small,
                )
            }
        }
        if (onNewBuild != null && job == null && modpackJob == null && !installed) {
            Spacer(Modifier.height(8.dp))
            MSecondaryButton(text = "Новая сборка", onClick = onNewBuild, modifier = Modifier.fillMaxWidth(), size = MButtonSize.Small)
        }
        if (modpackJob != null || job?.action == ItemAction.INSTALL) {
            Spacer(Modifier.height(10.dp))
            MProgressBar(progress = modpackJob?.progress ?: job?.progress)
        }
    }
}

private fun percentOf(share: Float?): String? = share?.let { "${(it * 100).toInt()}%" }

private val CATALOG_ASIDE_WIDTH = 260.dp

/**
 * «Версия» and «Загрузчик» of the desktop catalogue's filter column for the
 * modpacks of Modrinth and CurseForge, one pick per group. The other
 * sections take both from the build they install into.
 */
@Composable
private fun PackFilters(
    version: String?,
    loader: LoaderKind?,
    onVersion: (String) -> Unit,
    onLoader: (LoaderKind) -> Unit,
) {
    MCard(contentPadding = 12.dp) {
        MSectionTitle("ВЕРСИЯ")
        Spacer(Modifier.height(8.dp))
        MButtonRow {
            CatalogModel.PACK_VERSIONS.forEach { option ->
                MChip(text = option, selected = option == version, onClick = { onVersion(option) })
            }
        }
        Spacer(Modifier.height(14.dp))
        MSectionTitle("ЗАГРУЗЧИК")
        Spacer(Modifier.height(8.dp))
        MButtonRow {
            ModpackLoader.PHONE_LOADERS.forEach { option ->
                MChip(text = option.label, selected = option == loader, onClick = { onLoader(option) })
            }
        }
    }
}

/**
 * The desktop's «Ресурсы» tab of «Во что играем»: the hub's tabs in the
 * strip, the section tabs across the top, the narrowing controls in a
 * sidebar on the left (for which build, where from, the AI builder) and the
 * section's name, search and results in the main column.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WideCatalog(
    modifier: Modifier,
    listState: LazyListState,
    sectionTitle: String,
    foreign: Boolean,
    modpacks: Boolean,
    searchField: @Composable () -> Unit,
    sectionTabs: @Composable () -> Unit,
    scopeLine: @Composable () -> Unit,
    sources: @Composable () -> Unit,
    aiRow: @Composable () -> Unit,
    results: LazyListScope.() -> Unit,
    onLobby: () -> Unit,
    onLibrary: () -> Unit,
    filters: @Composable () -> Unit,
    itemPage: (@Composable () -> Unit)?,
) {
    val colors = millida
    val lifted by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    val roomy = LocalConfiguration.current.screenHeightDp >= WIDE_ROOMY_HEIGHT_DP
    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding(),
    ) {
        WideTopBar(
            title = null,
            onBack = onLobby,
            divider = lifted || itemPage != null,
            center = {
                PlayHubTabs(resources = true, onLibrary = onLibrary, onResources = {})
            },
        )
        if (itemPage != null) {
            Box(Modifier.weight(1f)) { itemPage() }
            return@Column
        }
        Box(Modifier.padding(start = screenPadding, end = screenPadding, top = 10.dp, bottom = 4.dp)) {
            sectionTabs()
        }
        Row(Modifier.weight(1f)) {
            Column(
                Modifier
                    .width(CATALOG_ASIDE_WIDTH)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = screenPadding, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                scopeLine()
                if (foreign) sources()
                filters()
                if (modpacks) aiRow()
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                state = listState,
                contentPadding = PaddingValues(start = 14.dp, end = screenPadding, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "head") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (roomy) {
                            Text(
                                sectionTitle,
                                style = MillidaType.screenTitle,
                                color = colors.fg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        searchField()
                    }
                }
                results()
            }
        }
    }
}
