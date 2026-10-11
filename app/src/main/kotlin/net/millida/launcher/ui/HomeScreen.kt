package net.millida.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.Area
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.UiState
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.VersionManifest
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.ui.character.characterLookOf
import net.millida.launcher.ui.home.BuildHero
import net.millida.launcher.ui.home.BuildMenuSheet
import net.millida.launcher.ui.home.FirstBuildHero
import net.millida.launcher.ui.home.HubTiles
import net.millida.launcher.ui.home.JoinServerSheet
import net.millida.launcher.ui.home.LobbyStage
import net.millida.launcher.ui.home.LobbyState
import net.millida.launcher.ui.home.LobbyTopRow
import net.millida.launcher.ui.home.WideLobby
import net.millida.launcher.ui.home.WideLobbyActions
import net.millida.launcher.ui.home.LobbyAccountMenu
import net.millida.launcher.ui.home.ONEBLOCK_PACK
import net.millida.launcher.ui.home.recommendedPack
import net.millida.launcher.ui.home.LoaderChip
import net.millida.launcher.ui.home.LoaderGate
import net.millida.launcher.ui.home.buildShelfCount
import net.millida.launcher.ui.home.buildShelves
import net.millida.launcher.ui.home.filterVersions
import net.millida.launcher.ui.home.isLegacyVersion
import net.millida.launcher.ui.home.isNewRenderer
import net.millida.launcher.ui.home.loaderGate
import net.millida.launcher.ui.home.rememberLobbyTourScroll
import net.millida.launcher.ui.home.serverShelf
import net.millida.launcher.ui.servers.ServerDetailSheet
import net.millida.launcher.ui.catalog.CatalogTab
import net.millida.launcher.core.premium.Premium
import net.millida.launcher.core.servers.OwnServers
import net.millida.launcher.ui.home.HubActions
import net.millida.launcher.ui.home.HubFeed
import net.millida.launcher.ui.home.ModeServersSheet
import net.millida.launcher.ui.home.hubSections
import net.millida.launcher.ui.premium.MillidaShelfSheets
import net.millida.launcher.ui.premium.PackPageSheet
import net.millida.launcher.ui.social.LobbyParty

private const val VERSION_PAGE = 24

@Composable
fun HomeScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val lobby by model.lobby.state.collectAsStateWithLifecycle()
    val licence by model.licence.state.collectAsStateWithLifecycle()
    val shop by model.shop.state.collectAsStateWithLifecycle()
    val hub by model.hub.state.collectAsStateWithLifecycle()
    val premium by model.premium.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var openedServer by remember { mutableStateOf<RatedServer?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var createPreset by rememberSaveable { mutableStateOf<String?>(null) }
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.profiles) { model.lobby.refresh(state.profiles) }
    LaunchedEffect(state.millidaSignedIn) {
        if (state.millidaSignedIn && state.gameProfile == null && !state.isLoading(Area.SKINS)) model.loadSkins()
    }
    LaunchedEffect(Unit) { model.lobby.loadTopServers() }
    LaunchedEffect(Unit) {
        model.hub.ensureLoaded()
        model.premium.ensureShowcase()
    }
    val licensedId = state.account?.takeIf { it.kind == AccountKind.MICROSOFT }?.id
    LaunchedEffect(licensedId) {
        val current = model.licence.state.value
        val known = current.accountId == licensedId && (current.profile != null || current.loading)
        if (licensedId != null && !known) model.licence.load(licensedId)
    }

    val openCreate: (String?) -> Unit = { preset ->
        createPreset = preset
        creating = true
        model.lobby.loadVersions()
    }
    LaunchedEffect(lobby.createAsk) {
        val ask = model.lobby.consumeCreate() ?: return@LaunchedEffect
        openCreate(ask.version)
    }
    val active = activeBuild(state, lobby)
    val playOwn: () -> Unit = {
        val own = OwnServers.ONEBLOCK
        model.playServer(own.address, own.title, emptyList(), own.slug)
    }

    val topRow: @Composable () -> Unit = {
        LobbyTopRow(
            state = state,
            onAccount = { model.open(Screen.ACCOUNT) },
            onFriends = { model.open(Screen.FRIENDS) },
            onSettings = { model.open(Screen.SETTINGS) },
        )
    }
    val stage: @Composable () -> Unit = {
        LobbyStage(
            state = state,
            look = characterLookOf(state, licence),
            totalSeconds = lobby.totalSeconds,
            onOpen = { model.open(Screen.SKINS) },
        )
    }
    val hero: @Composable () -> Unit = {
        if (active == null && !state.profilesLoaded) {
            MSkeleton(height = 196.dp)
        } else if (active == null) {
            FirstBuildHero(state = state, onCreate = { openCreate(null) })
        } else {
            BuildHero(
                profile = active,
                state = state,
                lobby = lobby,
                onPlay = {
                    model.selectBuild(active)
                    model.play(active)
                },
                onOpen = { model.openBuild(active) },
                onCancel = model::cancelPlay,
            )
        }
    }
    val tiles: @Composable () -> Unit = {
        HubTiles(
            hostedCount = state.hosted.size,
            shopAlert = shop.badge > 0,
            bonusReady = shop.dailyReady > 0,
            onShop = { model.open(Screen.SHOP) },
            onCharacter = { model.open(Screen.SKINS) },
            onHosting = { model.open(Screen.HOSTING) },
        )
    }
    val shelves: LazyListScope.() -> Unit = {
        buildShelves(
            profiles = state.profiles,
            lobby = lobby,
            activeId = active?.id,
            onSelect = { model.selectBuild(it) },
            onMenu = { menuFor = it.id },
            onCreate = { openCreate(null) },
        )
    }
    val servers: LazyListScope.() -> Unit = {
        serverShelf(
            lobby = lobby,
            onPlay = { server -> model.playServer(server.joinAddress, server.name, server.versionMajors, server.slug) },
            onOpen = { server -> openedServer = server },
            onAll = { model.open(Screen.SERVERS) },
            onRetry = { model.lobby.loadTopServers(force = true) },
        )
    }
    val sections: LazyListScope.() -> Unit = {
        hubSections(
            feed = HubFeed(
                hub = hub,
                exclusives = Premium.exclusives(premium.showcase?.packs.orEmpty(), hub.catalog.orEmpty()),
                free = Premium.freePacks(hub.catalog.orEmpty()),
                plan = premium.plan,
                premiumWait = premium.showcase == null && premium.error == null,
                ownPlayable = OwnServers.playable(OwnServers.ONEBLOCK),
            ),
            actions = HubActions(
                playOwn = playOwn,
                openPack = model.premium::open,
                openCatalog = {
                    model.open(Screen.CATALOG)
                    model.catalogModel.setTab(CatalogTab.MODPACKS)
                },
                openMode = model.hub::openMode,
                createVersion = { version -> openCreate(version) },
                retry = model.hub::retry,
            ),
            hasBuilds = state.profiles.isNotEmpty(),
            party = { LobbyParty(state, model, modeTitle = active?.name) },
        )
    }

    val wide = LocalWideLayout.current
    val tourScroll = rememberLobbyTourScroll(wide, buildShelfCount(state.profiles, lobby))
    val wideHub = LocalWideHub.current
    val ambient by model.ambient.state.collectAsStateWithLifecycle()
    LaunchedEffect(wide, ambient.tourStep != null) {
        if (wide && ambient.tourStep != null) wideHub.library = false
    }
    if (wide && wideHub.library) {
        BackHandler { wideHub.library = false }
        MScreen(
            modifier = modifier.then(tourScroll.listView.modifier),
            onBack = { wideHub.library = false },
            listState = tourScroll.listState,
            spacing = 14.dp,
            stripCenter = {
                PlayHubTabs(
                    resources = false,
                    onLibrary = {},
                    onResources = { model.open(Screen.CATALOG) },
                )
            },
            actions = {
                MPrimaryButton("Новая сборка", onClick = { openCreate(null) }, icon = MIcons.plus, size = MButtonSize.Small)
            },
        ) {
            shelves()
            servers()
            sections()
        }
    } else if (wide) {
        WideLobby(
            state = state,
            lobby = lobby,
            look = characterLookOf(state, licence),
            active = active,
            hostedCount = state.hosted.size,
            shopAlert = shop.badge > 0,
            bonusReady = shop.dailyReady > 0,
            recommend = recommendedPack(hub),
            accountMenu = { maxHeight, close, placement ->
                LobbyAccountMenu(state, model, maxHeight = maxHeight, onClose = close, modifier = placement)
            },
            actions = WideLobbyActions(
                onPick = { wideHub.library = true },
                onPlay = {
                    if (active == null) {
                        openCreate(null)
                    } else {
                        model.selectBuild(active)
                        model.play(active)
                    }
                },
                onCancel = model::cancelPlay,
                onCharacter = { model.open(Screen.SKINS) },
                onShop = { model.open(Screen.SHOP) },
                onHosting = { model.open(Screen.HOSTING) },
                onFriends = { model.open(Screen.FRIENDS) },
                onMessages = { model.open(Screen.CHAT) },
                onMore = { model.open(Screen.MORE) },
                onSettings = { model.open(Screen.SETTINGS) },
                onRecommend = { pack -> if (pack.slug == ONEBLOCK_PACK) playOwn() else model.premium.open(pack) },
            ),
            modifier = modifier,
        )
    } else {
        MScreen(
            modifier = modifier.then(tourScroll.listView.modifier),
            listState = tourScroll.listState,
            spacing = 14.dp,
        ) {
            item(key = "top", contentType = "top") { topRow() }
            item(key = "stage", contentType = "stage") { stage() }
            item(key = "hero", contentType = "hero") { hero() }
            shelves()
            item(key = "tiles", contentType = "tiles") { tiles() }
            servers()
            sections()
        }
    }

    hub.mode?.let { sheet ->
        ModeServersSheet(
            sheet = sheet,
            joining = lobby.joining,
            onPlay = { server ->
                model.hub.closeMode()
                model.playServer(server.joinAddress, server.name, server.versionMajors, server.slug)
            },
            onOpen = { server ->
                model.hub.closeMode()
                openedServer = server
            },
            onRetry = model.hub::retryMode,
            onDismiss = model.hub::closeMode,
        )
    }
    PackPageSheet(model)
    MillidaShelfSheets(state, model)

    openedServer?.let { server ->
        ServerDetailSheet(
            server = server,
            onDismiss = { openedServer = null },
            onPlay = {
                openedServer = null
                model.playServer(server.joinAddress, server.name, server.versionMajors, server.slug)
            },
            onCopy = { model.copyHostAddress(clipboard, server.joinAddress) },
            onOpenLink = { url -> model.openLinkOrNotice(context, url) },
            joining = lobby.joining.isNotEmpty() && lobby.joining == server.joinAddress,
        )
    }

    JoinServerSheet(state = state, model = model, onCreateBuild = openCreate)

    val menuProfile = state.profiles.firstOrNull { it.id == menuFor }
    if (menuProfile != null) {
        BuildMenuSheet(
            profile = menuProfile,
            onPlay = {
                menuFor = null
                model.selectBuild(menuProfile)
                model.play(menuProfile)
            },
            onOpen = {
                menuFor = null
                model.openBuild(menuProfile)
            },
            onDelete = {
                menuFor = null
                deleting = menuProfile.id
            },
            onDismiss = { menuFor = null },
        )
    }

    val deletingProfile = state.profiles.firstOrNull { it.id == deleting }
    if (deletingProfile != null) {
        MConfirmDialog(
            title = "Удалить сборку?",
            text = "«${deletingProfile.name}» удалится с модами и мирами. Вернуть нельзя.",
            confirm = "Удалить",
            onConfirm = {
                deleting = null
                model.deleteProfile(deletingProfile.id)
            },
            onDismiss = { deleting = null },
        )
    }

    if (creating) {
        CreateProfileSheet(
            state = state,
            onDismiss = { creating = false },
            onCreate = { name, version, loader ->
                creating = false
                model.createProfile(name, version, loader)
            },
            versions = lobby.versions,
            versionsLoading = lobby.versionsLoading,
            versionsError = lobby.versionsError,
            onRetryVersions = model.lobby::retryVersions,
            preset = createPreset,
        )
    }
}

private fun activeBuild(state: UiState, lobby: LobbyState): Profile? =
    state.profiles.firstOrNull { it.id == state.activeProfileId }
        ?: state.profiles.firstOrNull { it.id == lobby.lastPlayedId }
        ?: state.profiles.firstOrNull()

@Composable
internal fun CreateProfileSheet(
    state: UiState,
    onDismiss: () -> Unit,
    onCreate: (String, String, LoaderKind) -> Unit,
    versions: List<VersionManifest.ManifestEntry> = state.versions,
    versionsLoading: Boolean = false,
    versionsError: String? = null,
    onRetryVersions: (() -> Unit)? = null,
    preset: String? = null,
) {
    val colors = millida
    val source = versions.ifEmpty { state.versions }
    var name by rememberSaveable { mutableStateOf("") }
    var version by rememberSaveable { mutableStateOf(preset ?: state.versions.firstOrNull()?.id.orEmpty()) }
    var loader by rememberSaveable { mutableStateOf(LoaderKind.FABRIC) }
    var query by rememberSaveable { mutableStateOf("") }
    var snapshots by rememberSaveable { mutableStateOf(false) }
    var shown by rememberSaveable { mutableIntStateOf(VERSION_PAGE) }
    val filtered = remember(source, query, snapshots) { filterVersions(source, query, snapshots) }
    val entry = source.firstOrNull { it.id == version }
    val gate = loaderGate(loader, entry)

    MBottomSheet(onDismiss = onDismiss, title = "Новая сборка") {
        MSectionTitle("НАЗВАНИЕ")
        Spacer(Modifier.height(8.dp))
        MField(name, { name = it }, placeholder = "Например, Выживание с друзьями")

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MSectionTitle("ВЕРСИЯ ИГРЫ", Modifier.weight(1f))
            if (version.isNotEmpty()) {
                Text(version, style = MillidaType.pixel, color = colors.accentText, maxLines = 1)
            }
        }
        Spacer(Modifier.height(8.dp))
        MField(
            value = query,
            onValueChange = {
                query = it
                shown = VERSION_PAGE
            },
            placeholder = "Найти версию",
            leadingIcon = MIcons.search,
            clearable = true,
        )
        MSwitchRow("Снапшоты и старые", null, snapshots) {
            snapshots = it
            shown = VERSION_PAGE
        }
        Spacer(Modifier.height(4.dp))
        when {
            versionsError != null && (source.isEmpty() || snapshots) -> MErrorCard(versionsError, onRetry = onRetryVersions)
            source.isEmpty() || (versionsLoading && filtered.isEmpty()) -> MSkeleton(height = 96.dp)
            filtered.isEmpty() -> Text("Такой версии нет", style = MillidaType.body, color = colors.fgSubtle)
            else -> {
                MButtonRow {
                    filtered.take(shown).forEach { option ->
                        MChip(
                            text = option.id,
                            selected = option.id == version,
                            onClick = {
                                version = option.id
                                if (loaderGate(loader, option) == LoaderGate.Unsupported) loader = LoaderKind.VANILLA
                            },
                        )
                    }
                }
                if (filtered.size > shown) {
                    Spacer(Modifier.height(4.dp))
                    MTextButton(
                        text = "Ещё ${minOf(VERSION_PAGE, filtered.size - shown)}",
                        onClick = { shown += VERSION_PAGE },
                        icon = MIcons.chevronDown,
                    )
                }
            }
        }
        if (isLegacyVersion(entry) || isNewRenderer(entry)) {
            Spacer(Modifier.height(10.dp))
            MButtonRow {
                if (isLegacyVersion(entry)) {
                    MBadge("Старая версия: может не запуститься", colors.warning, colors.warningSoft, icon = MIcons.alert)
                    MBadge("Forge до 1.13 не поддерживается", colors.warning, colors.warningSoft, icon = MIcons.alert)
                }
                if (isNewRenderer(entry)) {
                    MBadge("Картинка может рваться", colors.warning, colors.warningSoft, icon = MIcons.alert)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        MSectionTitle("ЗАГРУЗЧИК")
        Spacer(Modifier.height(8.dp))
        MButtonRow {
            LoaderKind.entries.forEach { kind ->
                LoaderChip(
                    kind = kind,
                    selected = kind == loader,
                    unsupported = loaderGate(kind, entry) == LoaderGate.Unsupported,
                    onClick = { loader = kind },
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        MPrimaryButton(
            text = "Создать",
            onClick = { onCreate(name.trim().ifEmpty { "Сборка $version" }, version, loader) },
            modifier = Modifier.fillMaxWidth(),
            enabled = version.isNotEmpty() && gate == LoaderGate.Ready,
            icon = MIcons.plus,
            size = MButtonSize.Large,
        )
    }
}
