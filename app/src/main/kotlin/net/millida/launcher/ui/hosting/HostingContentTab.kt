package net.millida.launcher.ui.hosting

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import net.millida.launcher.HOSTING_KIND_MODPACK
import net.millida.launcher.HostingCatalogState
import net.millida.launcher.HostingContentState
import net.millida.launcher.HostingCoresState
import net.millida.launcher.HostingToolsModel
import net.millida.launcher.core.hosting.HOSTING_SOURCE_CURSEFORGE
import net.millida.launcher.core.hosting.HOSTING_SOURCE_FTB
import net.millida.launcher.core.hosting.HOSTING_SOURCE_MODRINTH
import net.millida.launcher.core.hosting.HostingCatalogHit
import net.millida.launcher.core.hosting.HostingCore
import net.millida.launcher.core.hosting.HostingInstall
import net.millida.launcher.core.hosting.HostingInstallUpdate
import net.millida.launcher.forServer
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPixelLoader
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSegmented
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.adaptiveColumns
import net.millida.launcher.ui.adaptiveItems
import net.millida.launcher.ui.catalog.compactCount
import net.millida.launcher.ui.millida

private const val CATALOG_MAX_COLUMNS = 3

/** The desktop catalog grid's `minmax(300px, 1fr)` cell. */
private val CATALOG_CELL = 300.dp

private enum class ContentView(val label: String) {
    Installed("На сервере"),
    Catalog("Каталог"),
    Core("Ядро"),
}

private val CONTENT_KINDS: List<Pair<String, String>> = listOf(
    HOSTING_KIND_MODPACK to "Сборки",
    "plugin" to "Плагины",
    "mod" to "Моды",
    "datapack" to "Дата-паки",
)

private val INSTALL_KIND: Map<String, String> = mapOf(
    "MOD" to "Мод",
    "PLUGIN" to "Плагин",
    "MODPACK" to "Сборка",
    "DATAPACK" to "Дата-пак",
    "MAP" to "Карта",
)

private val INSTALL_SOURCE: Map<String, String> = mapOf(
    "modrinth" to "Modrinth",
    "curseforge" to "CurseForge",
    "ftb" to "FTB",
    "market" to "Маркет",
    "upload" to "Свой файл",
    "partner" to "Сборка партнёра",
    "catalog" to "Каталог Millida",
)

private val CORE_FAMILY: Map<String, String> = mapOf(
    "vanilla" to "Без модов и плагинов",
    "plugins" to "Держит плагины",
    "mods" to "Держит моды",
    "proxy" to "Связывает серверы",
)

@Composable
fun HostingContentTab(
    serverId: String,
    tools: HostingToolsModel,
    core: String,
    version: String,
    running: Boolean,
    onChanged: () -> Unit,
) {
    LaunchedEffect(serverId) { tools.openContent(serverId) }
    val allContent by tools.content.collectAsState()
    val allCatalog by tools.catalog.collectAsState()
    val cores by tools.cores.collectAsState()
    val busy by tools.busy.collectAsState()
    val rights by tools.rights.collectAsState()
    val access = rights.forServer(serverId)
    val content = allContent.takeIf { it.serverId == serverId }
    val catalog = allCatalog.takeIf { it.serverId == serverId }

    var viewName by rememberSaveable(serverId) { mutableStateOf(ContentView.Installed.name) }
    val view = ContentView.entries.firstOrNull { it.name == viewName } ?: ContentView.Installed
    var query by rememberSaveable(serverId) { mutableStateOf("") }
    var pickVersion by rememberSaveable(serverId, version) { mutableStateOf(version) }
    var removing by remember { mutableStateOf<HostingInstall?>(null) }
    var askCore by remember { mutableStateOf<HostingCore?>(null) }
    var versionSheet by remember { mutableStateOf(false) }

    LaunchedEffect(serverId, view, version) {
        when (view) {
            ContentView.Catalog -> tools.openCatalog(serverId, version)
            ContentView.Core -> tools.loadCores()
            ContentView.Installed -> Unit
        }
    }

    val search: () -> Unit = {
        tools.setCatalogQuery(serverId, query)
        tools.searchCatalog(serverId, version)
    }
    val coreBusy = tools.key(serverId, "content:core") in busy
    val catalogColumns = adaptiveColumns(cell = CATALOG_CELL, max = CATALOG_MAX_COLUMNS)

    if (access != null && !access.can("content")) {
        ToolsList { item(key = "no-rights") { ToolsNoRights() } }
    } else {
        ToolsList {
            item(key = "view") {
                MSegmented(
                    options = ContentView.entries,
                    selected = view,
                    onSelect = { viewName = it.name },
                    label = { it.label },
                    badge = { if (it == ContentView.Installed) content?.updates?.size ?: 0 else 0 },
                )
            }
            when (view) {
                ContentView.Installed -> installedItems(
                    serverId = serverId,
                    tools = tools,
                    state = content,
                    busy = busy,
                    headline = serverLine(core, version),
                    onRemove = { removing = it },
                    onCatalog = { viewName = ContentView.Catalog.name },
                )

                ContentView.Catalog -> {
                    item(key = "search") {
                        CatalogFilters(
                            state = catalog,
                            query = query,
                            onQuery = { query = it },
                            onSearch = search,
                            onSource = { source ->
                                tools.setCatalogQuery(serverId, query)
                                tools.setCatalogFilter(serverId, version, source = source)
                            },
                            onKind = { kind ->
                                tools.setCatalogQuery(serverId, query)
                                tools.setCatalogFilter(serverId, version, kind = kind)
                            },
                        )
                    }
                    catalogItems(
                        serverId = serverId,
                        tools = tools,
                        state = catalog,
                        busy = busy,
                        hasQuery = query.isNotBlank(),
                        onRetry = search,
                        onReset = {
                            query = ""
                            tools.setCatalogQuery(serverId, "")
                            tools.searchCatalog(serverId, version)
                        },
                        onMore = { tools.searchCatalog(serverId, version, more = true) },
                        onInstalled = onChanged,
                        columns = catalogColumns,
                    )
                }

                ContentView.Core -> {
                    item(key = "core-head") {
                        MCard {
                            ToolsHead(title = "Ядро и версия", subtitle = "Сейчас: ${serverLine(core, version)}")
                            Spacer(Modifier.height(8.dp))
                            MListRow(
                                title = "Версия игры",
                                subtitle = pickVersion.ifBlank { "не выбрана" },
                                icon = MIcons.box,
                                plate = false,
                                onClick = { versionSheet = true },
                            )
                        }
                    }
                    coreItems(
                        state = cores,
                        current = core,
                        currentVersion = version,
                        pickVersion = pickVersion,
                        saving = coreBusy,
                        onRetry = { tools.loadCores(force = true) },
                        onPick = { askCore = it },
                    )
                }
            }
        }
    }

    removing?.let { install ->
        MConfirmDialog(
            title = "Убрать «${install.name}»?",
            text = "Пропадёт с сервера после перезапуска",
            confirm = "Убрать",
            onConfirm = {
                removing = null
                tools.removeInstall(serverId, install)
            },
            onDismiss = { removing = null },
        )
    }

    askCore?.let { next ->
        val target = if (pickVersion in next.versions) pickVersion else next.latest.ifBlank { next.versions.firstOrNull() ?: pickVersion }
        MConfirmDialog(
            title = "Поставить ${next.name} $target?",
            text = "Установленная сборка сбросится, мир останется" + if (running) ". Сервер перезапустится" else "",
            confirm = "Поставить",
            onConfirm = {
                askCore = null
                tools.changeCore(serverId, next, target, running, onDone = onChanged)
            },
            onDismiss = { askCore = null },
        )
    }

    if (versionSheet) {
        val versions = (cores.cores.orEmpty().flatMap { it.versions } + version)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(VERSION_ORDER)
        MBottomSheet(onDismiss = { versionSheet = false }, title = "Версия игры") {
            if (versions.isEmpty()) {
                MEmptyState(icon = MIcons.box, title = "Версии ещё не загрузились")
            } else {
                MButtonRow {
                    versions.forEach { item ->
                        MChip(
                            text = item,
                            selected = item == pickVersion,
                            onClick = {
                                pickVersion = item
                                versionSheet = false
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.installedItems(
    serverId: String,
    tools: HostingToolsModel,
    state: HostingContentState?,
    busy: Set<String>,
    headline: String,
    onRemove: (HostingInstall) -> Unit,
    onCatalog: () -> Unit,
) {
    val updates = state?.updates.orEmpty()
    if (updates.isNotEmpty()) {
        item(key = "updates") {
            MCard(Modifier.animateItem()) {
                ToolsHead(title = "Есть обновления", subtitle = "Всего: ${updates.size}")
                updates.forEachIndexed { index, update ->
                    if (index > 0) MDivider()
                    val working = tools.key(serverId, "content:update:${update.installId}") in busy
                    MListRow(
                        title = update.name,
                        subtitle = updateLine(update),
                        icon = MIcons.arrowUp,
                        plate = false,
                        trailing = {
                            MSecondaryButton(
                                "Обновить",
                                onClick = { tools.applyUpdate(serverId, update) },
                                enabled = update.compatible && !working,
                                size = MButtonSize.Small,
                                loading = working,
                            )
                        },
                    )
                }
            }
        }
    }
    item(key = "installed-head") {
        ToolsHead(title = "Сейчас на сервере", subtitle = headline)
    }
    val list = state?.installs
    val error = state?.error
    when {
        error != null && list == null -> item(key = "installed-error") {
            MErrorCard(error, onRetry = { tools.loadContent(serverId) })
        }
        list == null -> items(3, key = { "installed-skeleton-$it" }) { MSkeletonRow() }
        list.isEmpty() -> item(key = "installed-empty") {
            MEmptyState(
                icon = MIcons.box,
                title = "Пока пусто",
                actionText = "Открыть каталог",
                onAction = onCatalog,
                actionIcon = MIcons.search,
            )
        }
        else -> {
            if (error != null) {
                item(key = "installed-error") { MErrorCard(error, onRetry = { tools.loadContent(serverId) }) }
            }
            items(list, key = { "install:" + it.id }) { install ->
                val removingNow = tools.key(serverId, "content:remove:${install.id}") in busy
                MListRow(
                    title = install.name.ifBlank { install.projectId },
                    modifier = Modifier.animateItem(),
                    subtitle = installLine(install),
                    leading = { MRemoteImage(url = install.iconUrl.ifBlank { null }, icon = MIcons.box, size = 40.dp) },
                    trailing = {
                        InstallBadge(install.status)
                        if (removingNow) {
                            MPixelLoader()
                        } else {
                            MIconButton(MIcons.trash, onClick = { onRemove(install) }, label = "Убрать")
                        }
                    },
                )
            }
        }
    }
}

private fun LazyListScope.catalogItems(
    serverId: String,
    tools: HostingToolsModel,
    state: HostingCatalogState?,
    busy: Set<String>,
    hasQuery: Boolean,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    onMore: () -> Unit,
    onInstalled: () -> Unit,
    columns: Int,
) {
    val hits = state?.hits
    val error = state?.error
    val source = state?.source ?: HOSTING_SOURCE_MODRINTH
    when {
        error != null && hits == null -> item(key = "catalog-error") { MErrorCard(error, onRetry = onRetry) }
        hits == null -> items(4, key = { "catalog-skeleton-$it" }) { MSkeletonRow() }
        hits.isEmpty() -> item(key = "catalog-empty") {
            MEmptyState(
                icon = MIcons.search,
                title = "Ничего не нашли",
                actionText = if (hasQuery) "Сбросить" else null,
                onAction = if (hasQuery) onReset else null,
                primary = false,
            )
        }
        else -> {
            adaptiveItems(hits, columns, key = { "hit:" + it.id }) { hit ->
                val installing = tools.key(serverId, "content:install:$source:${hit.id}") in busy
                MListRow(
                    title = hit.name,
                    modifier = if (columns > 1) Modifier else Modifier.animateItem(),
                    subtitle = hitLine(hit),
                    leading = { MRemoteImage(url = hit.iconUrl.ifBlank { null }, icon = MIcons.box, size = 40.dp) },
                    trailing = {
                        if (installing) {
                            MPixelLoader()
                        } else {
                            MIconButton(
                                MIcons.download,
                                onClick = { tools.installHit(serverId, source, hit, onDone = onInstalled) },
                                tint = millida.accentText,
                                label = "Поставить",
                            )
                        }
                    },
                )
            }
            val searching = state?.searching == true
            if (state?.nextOffset != null) {
                item(key = "catalog-more") {
                    MSecondaryButton(
                        "Показать ещё",
                        onClick = onMore,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !searching,
                        loading = searching,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.coreItems(
    state: HostingCoresState,
    current: String,
    currentVersion: String,
    pickVersion: String,
    saving: Boolean,
    onRetry: () -> Unit,
    onPick: (HostingCore) -> Unit,
) {
    val list = state.cores
    val error = state.error
    when {
        error != null && list == null -> item(key = "cores-error") { MErrorCard(error, onRetry = onRetry) }
        list == null -> items(4, key = { "cores-skeleton-$it" }) { MSkeletonRow() }
        else -> {
            val fitting = list
                .filter { pickVersion in it.versions }
                .sortedWith(compareByDescending<HostingCore> { it.recommended }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            if (fitting.isEmpty()) {
                item(key = "cores-empty") {
                    MEmptyState(icon = MIcons.server, title = "Под эту версию ядер нет")
                }
            } else {
                items(fitting, key = { "core:" + it.id }) { item ->
                    val now = item.id.equals(current, ignoreCase = true) && pickVersion == currentVersion
                    MListRow(
                        title = item.name,
                        modifier = Modifier.animateItem(),
                        subtitle = listOfNotNull(
                            if (item.recommended && !now) "Советуем" else null,
                            CORE_FAMILY[item.family],
                        ).joinToString(" · ").ifEmpty { null },
                        icon = MIcons.server,
                        trailing = {
                            if (now) {
                                MBadge("стоит сейчас", millida.accentText, millida.accentSoft)
                            } else {
                                MSecondaryButton(
                                    "Поставить",
                                    onClick = { onPick(item) },
                                    enabled = !saving,
                                    size = MButtonSize.Small,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogFilters(
    state: HostingCatalogState?,
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onSource: (String) -> Unit,
    onKind: (String) -> Unit,
) {
    val kind = state?.kind ?: HOSTING_KIND_MODPACK
    val source = state?.source ?: HOSTING_SOURCE_MODRINTH
    val sources = if (kind == HOSTING_KIND_MODPACK) {
        listOf(HOSTING_SOURCE_MODRINTH, HOSTING_SOURCE_CURSEFORGE, HOSTING_SOURCE_FTB)
    } else {
        listOf(HOSTING_SOURCE_MODRINTH, HOSTING_SOURCE_CURSEFORGE)
    }
    MCard {
        MField(
            value = query,
            onValueChange = onQuery,
            placeholder = "Найти",
            leadingIcon = MIcons.search,
            imeAction = ImeAction.Search,
            onImeAction = onSearch,
            clearable = true,
        )
        Spacer(Modifier.height(10.dp))
        MSegmented(
            options = sources,
            selected = source,
            onSelect = onSource,
            label = { INSTALL_SOURCE[it] ?: it },
        )
        Spacer(Modifier.height(10.dp))
        MButtonRow {
            CONTENT_KINDS.forEach { (id, label) ->
                MChip(text = label, selected = kind == id, onClick = { onKind(id) })
            }
        }
    }
}

@Composable
private fun InstallBadge(status: String) {
    val colors = millida
    when (status.uppercase()) {
        "PENDING" -> MBadge("при запуске", colors.warning, colors.warningSoft)
        "FAILED" -> MBadge("ошибка", colors.danger, colors.dangerSoft)
        else -> Unit
    }
}

private fun serverLine(core: String, version: String): String =
    listOf(core, version).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { "ядро не выбрано" }

private fun installLine(install: HostingInstall): String = listOf(
    INSTALL_KIND[install.kind.uppercase()] ?: install.kind,
    INSTALL_SOURCE[install.source.lowercase()].orEmpty(),
    install.versionName,
    install.error,
).filter { it.isNotBlank() }.joinToString(" · ")

private fun updateLine(update: HostingInstallUpdate): String {
    val line = "${update.current.ifBlank { "—" }} → ${update.latest.ifBlank { "—" }}"
    return if (update.compatible) line else "$line · под другую версию"
}

private fun hitLine(hit: HostingCatalogHit): String {
    if (hit.summary.isNotBlank()) return hit.summary
    return listOfNotNull(
        hit.author.ifBlank { null },
        if (hit.downloads > 0) compactCount(hit.downloads) else null,
        if (hit.minRamMb > 0) "от ${(hit.minRamMb + 1023) / 1024} ГБ" else null,
    ).joinToString(" · ")
}

private fun versionKey(value: String): List<Int> = value.split('.', '-', '+').map { it.toIntOrNull() ?: -1 }

private val VERSION_ORDER: Comparator<String> = Comparator { a, b ->
    val x = versionKey(a)
    val y = versionKey(b)
    for (index in 0 until maxOf(x.size, y.size)) {
        val diff = (y.getOrNull(index) ?: -1) - (x.getOrNull(index) ?: -1)
        if (diff != 0) return@Comparator diff
    }
    0
}
