package net.millida.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.Area
import net.millida.launcher.BuildTab
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.UiState
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.ui.build.AddServerSheet
import net.millida.launcher.ui.build.BuildHeader
import net.millida.launcher.ui.build.BuildHero
import net.millida.launcher.ui.build.PackUpdateCard
import net.millida.launcher.ui.build.PackUpdateConfirm
import net.millida.launcher.ui.build.PackUpdateModel
import net.millida.launcher.ui.build.contentTab
import net.millida.launcher.ui.build.logsTab
import net.millida.launcher.ui.build.optionsTab
import net.millida.launcher.ui.build.serversTab
import net.millida.launcher.ui.build.worldsTab
import net.millida.launcher.ui.home.LocalImportPrompt

/** The desktop's build tabs (InstancePage.tsx): worlds and servers share one tab with a filter. */
private val SHOWN_TABS = BuildTab.entries - BuildTab.SERVERS

private enum class WorldFilter(val label: String) {
    ALL("Все"),
    SINGLE("Одиночные"),
    SERVER("Серверы"),
}

private fun tabLabel(tab: BuildTab): String = when (tab) {
    BuildTab.CONTENT -> "Контент"
    BuildTab.WORLDS -> "Миры и серверы"
    BuildTab.SERVERS -> "Серверы"
    BuildTab.SHOTS -> "Скриншоты"
    BuildTab.LOGS -> "Логи"
    BuildTab.OPTIONS -> "Параметры"
}

private fun tabIcon(tab: BuildTab): MillidaIcon = when (tab) {
    BuildTab.CONTENT -> MIcons.box
    BuildTab.WORLDS -> MIcons.server
    BuildTab.SERVERS -> MIcons.server
    BuildTab.SHOTS -> MIcons.image
    BuildTab.LOGS -> MIcons.list
    BuildTab.OPTIONS -> MIcons.settings
}

/**
 * One build: its face with the launch button, then the tabs that stay pinned
 * while the list scrolls. File pickers live here and not inside the list, so a
 * result that arrives after a row scrolled away still finds its receiver.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BuildScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val profile = state.openedProfile
    if (profile == null) {
        LaunchedEffect(Unit) { model.open(Screen.HOME) }
        return
    }
    val lobby by model.lobby.state.collectAsStateWithLifecycle()
    val packUpdate by model.packUpdates.state.collectAsStateWithLifecycle()
    val catalogJob = model.catalogModel.state.collectAsStateWithLifecycle().value.modpack
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scrolled by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    var addingServer by rememberSaveable(profile.id) { mutableStateOf(false) }
    var worldFilter by rememberSaveable(profile.id) { mutableStateOf(WorldFilter.ALL) }
    val logLines = remember(state.buildLog) { state.buildLog?.lines().orEmpty() }

    LaunchedEffect(profile.id, state.buildTab, state.buildKind) { model.refreshBuild() }
    LaunchedEffect(profile.id) {
        model.lobby.refresh(state.profiles)
        model.loadBuildMeta(profile)
        model.packUpdates.refresh(profile)
    }

    val onAdded: (ContentKind?) -> Unit = { kind ->
        if (kind != null && kind != model.ui.value.buildKind) model.setBuildKind(kind) else model.refreshBuild()
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.lobby.importLocalFile(context.contentResolver, uri, profile, onAdded)
    }
    val worldPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) model.importWorld(profile, context.contentResolver, uri)
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) model.lobby.setCover(profile, context.contentResolver, uri, state.profiles)
    }

    val header: @Composable () -> Unit = {
        BuildHeader(
            profile = profile,
            state = state,
            lobby = lobby,
            onPlay = { model.packUpdates.playWithUpdate(profile) { built -> model.play(built) } },
            onCancel = model::cancelPlay,
        )
    }
    val hero: @Composable () -> Unit = {
        BuildHero(
            profile = profile,
            state = state,
            lobby = lobby,
            onPlay = { model.packUpdates.playWithUpdate(profile) { built -> model.play(built) } },
            onCancel = model::cancelPlay,
        )
    }
    val updateOffer: LazyListScope.() -> Unit = {
        val offer = packUpdate.offer?.takeIf { packUpdate.profileId == profile.id }
        if (offer != null) {
            item(key = "build:update", contentType = "card") {
                PackUpdateCard(
                    offer = offer,
                    job = catalogJob?.takeIf { it.key == PackUpdateModel.key(profile.id) },
                    busy = catalogJob != null,
                    onUpdate = model.packUpdates::ask,
                )
            }
        }
    }
    val tabContent: LazyListScope.() -> Unit = {
        when (state.buildTab) {
            BuildTab.CONTENT -> contentTab(profile, state, lobby, model, onAddFile = { filePicker.launch(arrayOf("*/*")) })
            BuildTab.WORLDS -> {
                item(key = "worlds:filter", contentType = "filter") {
                    MSegmented(
                        options = WorldFilter.entries,
                        selected = worldFilter,
                        onSelect = { worldFilter = it },
                        label = { it.label },
                    )
                }
                if (worldFilter != WorldFilter.SERVER) worldsTab(profile, state, model, onImport = { worldPicker.launch("*/*") })
                if (worldFilter != WorldFilter.SINGLE) serversTab(profile, state, model, onAdd = { addingServer = true })
            }
            BuildTab.SERVERS -> serversTab(profile, state, model, onAdd = { addingServer = true })
            BuildTab.SHOTS -> {
                val shots = state.shots.filter { it.profileId == profile.id }
                val error = state.errorOf(Area.BUILD)
                when {
                    shots.isEmpty() && state.isLoading(Area.BUILD) ->
                        items(2, key = { "shots:skeleton:$it" }, contentType = { "skeleton" }) { MSkeletonRow() }

                    shots.isEmpty() && error != null -> item(key = "shots:error", contentType = "error") {
                        MErrorCard(error, onRetry = { model.retry(Area.BUILD) })
                    }

                    else -> item(key = "build:shots", contentType = "shots") { ShotsGallery(shots, model) }
                }
            }
            BuildTab.LOGS -> logsTab(state, model, logLines)
            BuildTab.OPTIONS -> optionsTab(profile, state, lobby, model, onPickCover = { coverPicker.launch("image/*") })
        }
    }
    val spacing = if (state.buildTab == BuildTab.LOGS) 0.dp else 10.dp

    if (LocalWideLayout.current) {
        WideBuild(
            title = profile.name,
            selected = state.buildTab,
            onSelect = model::setBuildTab,
            onBack = { model.back() },
            listState = listState,
            spacing = spacing,
            header = header,
            hero = hero,
            shotCount = state.shots.count { it.profileId == profile.id },
            content = {
                updateOffer()
                tabContent()
            },
            modifier = modifier,
        )
    } else {
        MScreen(
            modifier = modifier.imePadding(),
            title = if (scrolled) profile.name else "Сборка",
            onBack = { model.back() },
            listState = listState,
            spacing = spacing,
        ) {
            item(key = "build:header", contentType = "header") { header() }
            updateOffer()
            stickyHeader(key = "build:tabs", contentType = "tabs") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(millida.bg)
                        .padding(vertical = 6.dp),
                ) {
                    MTabs(
                        tabs = SHOWN_TABS,
                        selected = state.buildTab,
                        onSelect = model::setBuildTab,
                        label = ::tabLabel,
                        icon = ::tabIcon,
                        scrollable = true,
                    )
                }
            }
            tabContent()
        }
    }

    val asked = packUpdate.offer
    if (packUpdate.asking && asked != null && packUpdate.profileId == profile.id) {
        PackUpdateConfirm(
            name = profile.name,
            offer = asked,
            edited = packUpdate.edited,
            onConfirm = { model.packUpdates.confirm(profile) },
            onDismiss = model.packUpdates::dismiss,
        )
    }

    if (addingServer) {
        AddServerSheet(
            onDismiss = { addingServer = false },
            onAdd = { name, address ->
                addingServer = false
                model.addBuildServer(profile, name, address)
            },
        )
    }

    val prompt = lobby.importPrompt
    if (prompt != null && prompt.profileId == profile.id) {
        LocalImportSheet(
            prompt = prompt,
            onConfirm = { model.lobby.confirmImport(profile, onAdded) },
            onCancel = model.lobby::cancelImport,
        )
    }
}

private val BUILD_RAIL_WIDTH = 280.dp
private val BUILD_TABS_WIDTH = 220.dp

/**
 * The build page as the desktop's InstancePage: the strip with the build's
 * name, then a left rail with the build's face and its tabs down the side,
 * and the picked tab's content filling the rest.
 */
@Composable
private fun WideBuild(
    title: String,
    selected: BuildTab,
    onSelect: (BuildTab) -> Unit,
    onBack: () -> Unit,
    listState: LazyListState,
    spacing: Dp,
    header: @Composable () -> Unit,
    hero: @Composable () -> Unit,
    shotCount: Int,
    content: LazyListScope.() -> Unit,
    modifier: Modifier,
) {
    val colors = millida
    val lifted by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    // The desktop page's head runs across the whole window above the tabs; a
    // phone on its side keeps it in the rail so the tab's content keeps its height.
    val headAcross = LocalConfiguration.current.screenHeightDp >= WIDE_ROOMY_HEIGHT_DP
    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding(),
    ) {
        WideTopBar(title = title, onBack = onBack, divider = lifted)
        if (headAcross) {
            Box(Modifier.padding(start = screenPadding, end = screenPadding, top = 16.dp, bottom = 4.dp)) { hero() }
        }
        Row(Modifier.weight(1f)) {
            Column(
                Modifier
                    .width(if (headAcross) BUILD_TABS_WIDTH else BUILD_RAIL_WIDTH)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = screenPadding, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!headAcross) header()
                WideRailTabs(
                    tabs = SHOWN_TABS,
                    selected = selected,
                    onSelect = onSelect,
                    label = ::tabLabel,
                    icon = ::tabIcon,
                    badge = { tab -> if (tab == BuildTab.SHOTS) shotCount else 0 },
                )
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                state = listState,
                contentPadding = PaddingValues(start = 14.dp, end = screenPadding, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
        }
    }
}

@Composable
private fun LocalImportSheet(prompt: LocalImportPrompt, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = millida
    MBottomSheet(onDismiss = onCancel, title = "Добавить файл?") {
        Text(
            prompt.fileName,
            style = MillidaType.rowTitle,
            color = colors.fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        prompt.warnings.forEach { warning ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                MIcon(MIcons.alert, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Text(warning, modifier = Modifier.weight(1f), style = MillidaType.body, color = colors.fgMuted)
            }
        }
        Spacer(Modifier.height(20.dp))
        Column {
            MDangerButton(
                "Всё равно добавить",
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                icon = MIcons.upload,
                size = MButtonSize.Medium,
            )
            Spacer(Modifier.height(8.dp))
            MSecondaryButton("Отмена", onClick = onCancel, modifier = Modifier.fillMaxWidth())
        }
    }
}
