package net.millida.launcher

import android.app.Application
import android.util.Log
import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.millida.launcher.core.LowMemoryNotice
import net.millida.launcher.core.MemoryTuning
import net.millida.launcher.core.account.Account
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.account.DevicePoll
import net.millida.launcher.core.account.GameSession
import net.millida.launcher.core.account.MicrosoftSignInRequired
import net.millida.launcher.core.account.OfflineAuth
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.catalog.CatalogItem
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.catalog.MillidaItem
import net.millida.launcher.core.links.MillidaLink
import net.millida.launcher.core.links.MillidaLinks
import net.millida.launcher.core.game.WorldCard
import net.millida.launcher.core.profile.ContentFile
import net.millida.launcher.core.install.InstallStage
import net.millida.launcher.core.loader.ForgeFamilyInstaller
import net.millida.launcher.core.loader.ForgeInstallStep
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.VersionManifest
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.core.cabinet.AchievementsPage
import net.millida.launcher.core.cabinet.NewsItem
import net.millida.launcher.core.cabinet.WalletBalance
import net.millida.launcher.core.cabinet.WalletOperation
import net.millida.launcher.core.content.PhoneBlockedMods
import net.millida.launcher.core.content.SafetyReport
import net.millida.launcher.core.content.UpdatePlan
import net.millida.launcher.core.cosmetics.CosmeticsModState
import net.millida.launcher.core.cosmetics.LaunchModAction
import net.millida.launcher.core.cosmetics.MillidaModOffer
import net.millida.launcher.core.cosmetics.SkinDiagnosis
import net.millida.launcher.core.friends.ChatTarget
import net.millida.launcher.core.friends.Dialog
import net.millida.launcher.core.friends.Friend
import net.millida.launcher.core.friends.FriendRequests
import net.millida.launcher.core.game.WorldBackup
import net.millida.launcher.core.hosting.HostingDetails
import net.millida.launcher.core.hosting.HostingEvent
import net.millida.launcher.core.hosting.HostingStats
import net.millida.launcher.core.media.Screenshot
import net.millida.launcher.core.packs.MyPack
import net.millida.launcher.core.packs.PackAccessDenied
import net.millida.launcher.core.packs.PackAccessLost
import net.millida.launcher.core.packs.PackAccessWatch
import net.millida.launcher.core.packs.PackAdmission
import net.millida.launcher.core.packs.PackLaunchGate
import net.millida.launcher.core.packs.PackLaunches
import net.millida.launcher.core.packs.PackNames
import net.millida.launcher.core.packs.PackOriginStore
import net.millida.launcher.core.packs.PackProgress
import net.millida.launcher.core.packs.SharedPackView
import net.millida.launcher.core.profile.BuildMeta
import net.millida.launcher.core.profile.MigratePlan
import net.millida.launcher.core.profile.PlayStats
import net.millida.launcher.core.servers.OwnServer
import net.millida.launcher.core.servers.OwnServers
import net.millida.launcher.core.servers.ServerEntry
import net.millida.launcher.core.servers.ServerStatus
import net.millida.launcher.core.skins.TextureEntry
import net.millida.launcher.core.skins.TextureKind
import net.millida.launcher.core.sync.CloudStatus
import net.millida.launcher.core.sync.PullReport
import net.millida.launcher.core.update.ReleaseNotes
import net.millida.launcher.core.update.ReleaseNotesParser
import net.millida.launcher.core.update.WhatsNewGate
import net.millida.launcher.core.update.InstallMoment
import net.millida.launcher.core.update.UpdateFaults
import net.millida.launcher.core.update.UpdatePolicy
import net.millida.launcher.core.update.UpdateRelease
import net.millida.launcher.core.update.UpdateVerdict
import net.millida.launcher.core.bedrock.BEDROCK_PROXY_HOST
import net.millida.launcher.core.bedrock.BEDROCK_PROXY_PORT
import net.millida.launcher.core.bedrock.BedrockBridge
import net.millida.launcher.core.bedrock.VIAPROXY_SIZE
import net.millida.launcher.core.bedrock.isBedrockAddress
import net.millida.launcher.core.bedrock.parseBedrockAddress
import net.millida.launcher.core.portal.AccountDetails
import net.millida.launcher.core.portal.AccountSession
import net.millida.launcher.core.portal.PortalProfile
import net.millida.launcher.core.portal.Privacy
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.rating.LicenseFilter
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.core.rating.RatingSort
import net.millida.launcher.core.rating.ServerFilters
import net.millida.launcher.core.rating.VersionFacet
import net.millida.launcher.core.rating.newestFirst
import net.millida.launcher.core.telemetry.BeatState
import net.millida.launcher.core.telemetry.EventData
import net.millida.launcher.core.telemetry.LaunchFacts
import net.millida.launcher.core.telemetry.TelemetryCore
import net.millida.launcher.core.telemetry.TelemetryPrivacy
import net.millida.launcher.core.telemetry.TelemetryType
import net.millida.launcher.runtime.GameSession as RuntimeSession
import net.millida.launcher.ui.catalog.CatalogHost
import net.millida.launcher.ui.catalog.CatalogModel
import net.millida.launcher.ui.catalog.CatalogTab
import net.millida.launcher.ui.catalog.MillidaShelfHost
import net.millida.launcher.ui.catalog.MillidaShelfModel
import net.millida.launcher.ui.catalog.AiBuilderModel
import net.millida.launcher.ui.catalog.MillidaContentHost
import net.millida.launcher.ui.catalog.MillidaContentModel
import net.millida.launcher.ui.build.PackUpdateModel
import net.millida.launcher.core.catalog.MillidaCatalog
import net.millida.launcher.core.hub.HubClient
import net.millida.launcher.core.media.LobbyMusic
import net.millida.launcher.core.media.LiveWallpaper
import net.millida.launcher.core.premium.PremiumClient
import net.millida.launcher.ui.character.art
import net.millida.launcher.ui.home.AmbientModel
import net.millida.launcher.ui.home.HubModel
import net.millida.launcher.ui.premium.PremiumModel
import net.millida.launcher.core.skins.CapeOffer
import net.millida.launcher.core.skins.GameProfile
import net.millida.launcher.core.skins.ImportedTexture
import net.millida.launcher.ui.character.LicenceModel
import net.millida.launcher.ui.character.MojangCapes
import net.millida.launcher.ui.character.slimArms
import net.millida.launcher.core.skins.Reward
import net.millida.launcher.core.skins.WardrobeItem
import net.millida.launcher.core.game.VideoSettings
import net.millida.launcher.core.runtime.RuntimeImport
import net.millida.launcher.core.runtime.RuntimeManifest
import net.millida.launcher.core.support.ReportRedaction
import net.millida.launcher.ui.home.JoinPrompt
import net.millida.launcher.ui.home.LobbyModel
import net.millida.launcher.ui.isCustomAccent
import net.millida.launcher.ui.openLink
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Five places live in the bottom bar; everything else hangs off MORE. A phone
 * bar with more than five entries turns into a row of unreadable icons, so the
 * rare screens are reached from a list instead.
 */
enum class Screen {
    HOME, BUILD, SERVERS, CATALOG, ACCOUNT, MORE, SETTINGS, SKINS, HOSTING, HOSTING_MANAGE,
    BEDROCK, PACKS, FRIENDS, CHAT, CABINET, SCREENSHOTS, SHOP,
    PREMIUM,
}

val rootTabs: List<Screen> = listOf(Screen.HOME, Screen.CATALOG, Screen.SKINS, Screen.SHOP, Screen.MORE)

val Screen.isRoot: Boolean get() = this in rootTabs

fun rootTabOf(screen: Screen): Screen = when (screen) {
    Screen.HOME, Screen.BUILD -> Screen.HOME
    Screen.CATALOG -> Screen.CATALOG
    Screen.SKINS -> Screen.SKINS
    Screen.SHOP -> Screen.SHOP
    Screen.MORE, Screen.ACCOUNT, Screen.SERVERS, Screen.FRIENDS, Screen.CHAT, Screen.HOSTING,
    Screen.HOSTING_MANAGE, Screen.CABINET, Screen.PACKS, Screen.SCREENSHOTS, Screen.BEDROCK,
    Screen.SETTINGS -> Screen.MORE
    Screen.PREMIUM -> Screen.MORE
}

/** A part of the app that loads on its own, so its wait and its failure stay inside it. */
enum class Area { SERVERS, CATALOG, FRIENDS, CABINET, PACKS, HOSTING, HOSTED_SERVER, SKINS, BUILD, SHOP, ACCOUNT, SHOTS }

enum class BuildTab(val label: String) {
    CONTENT("Содержимое"),
    WORLDS("Миры"),
    SERVERS("Серверы"),
    SHOTS("Снимки"),
    LOGS("Журнал"),
    OPTIONS("Параметры"),
}

data class LoginPrompt(
    val code: String,
    val url: String,
    val kind: AccountKind,
)

enum class ToastTone { Success, Info, Error }

data class Message(
    val id: Long,
    val text: String,
    val isError: Boolean,
    val tone: ToastTone = if (isError) ToastTone.Error else ToastTone.Success,
)

/** A start held back until the player agrees to play on a phone with little memory. */
data class PendingPlay(val profile: Profile, val serverAddress: String, val notice: LowMemoryNotice)

data class PendingModOffer(val profile: Profile, val serverAddress: String)

data class UiState(
    val screen: Screen = Screen.HOME,
    val profiles: List<Profile> = emptyList(),
    val versions: List<VersionManifest.ManifestEntry> = emptyList(),
    val renderers: List<RuntimeManifest.RendererEntry> = emptyList(),
    val safeGraphics: Boolean = true,
    val memoryCeilingMb: Int = 4096,
    val accounts: List<Account> = emptyList(),
    val account: Account? = null,
    val portal: PortalProfile? = null,
    val privacy: Privacy? = null,
    val hosted: List<HostingServerState> = emptyList(),
    val offlineNick: String = "",
    val tasks: List<String> = emptyList(),
    val progress: Float = 0f,
    val progressNote: String = "",
    val messages: List<Message> = emptyList(),
    val loginPrompt: LoginPrompt? = null,
    val details: AccountDetails? = null,
    val sessions: List<AccountSession> = emptyList(),
    val nicknameDraft: String = "",
    val linkUrl: String = "",
    val gameProfile: GameProfile? = null,
    val wardrobe: List<WardrobeItem> = emptyList(),
    val capes: List<CapeOffer> = emptyList(),
    val rewards: List<Reward> = emptyList(),
    val servers: List<RatedServer> = emptyList(),
    val serversTotal: Int = 0,
    val serverQuery: String = "",
    val serverSort: RatingSort = RatingSort.RATING,
    val serverLicense: LicenseFilter = LicenseFilter.ANY,
    val serverVersion: String = "",
    val serverCategory: String = "",
    val serverOnline: String = "",
    val serverVersions: List<VersionFacet> = emptyList(),
    val serversFor: String = "",
    val activeProfileId: String? = null,
    val openedProfileId: String? = null,
    val buildTab: BuildTab = BuildTab.CONTENT,
    val buildKind: ContentKind = ContentKind.MOD,
    val buildContent: List<ContentFile> = emptyList(),
    val buildWorlds: List<WorldCard> = emptyList(),
    val buildLog: String? = null,
    val deviceReport: String = "",
    val rendererId: String = "",
    val lookSensitivity: Float = 1.0f,
    val memoryOverrideMb: Int = 0,
    val windowLayer: String = Prefs.LAYER_PACK,
    val importedRuntime: String? = null,
    val cosmetics: CosmeticsModState? = null,
    val skinDiagnosis: SkinDiagnosis? = null,
    val buildServers: List<ServerEntry> = emptyList(),
    val serverStatuses: Map<String, ServerStatus> = emptyMap(),
    val silentServers: Set<String> = emptySet(),
    val worldBackups: List<WorldBackup> = emptyList(),
    val shots: List<Screenshot> = emptyList(),
    val shotsScope: String? = null,
    val shotsTotal: Int = 0,
    val shotsAll: Int? = null,
    val buildMeta: BuildMeta? = null,
    val playStats: PlayStats? = null,
    val migratePlan: MigratePlan? = null,
    val contentUpdatePlan: UpdatePlan? = null,
    val safetyReport: SafetyReport? = null,
    val updateVerdict: UpdateVerdict? = null,
    val updateProgress: Float = 0f,
    val updateOffer: Boolean = false,
    val releaseNotes: ReleaseNotes? = null,
    val cloudStatus: CloudStatus? = null,
    val cloudReport: PullReport? = null,
    val myPacks: List<MyPack> = emptyList(),
    val packPreview: SharedPackView? = null,
    val hostingServerId: String = "",
    val hostingDetails: HostingDetails? = null,
    val hostingStats: HostingStats? = null,
    val hostingEvents: List<HostingEvent> = emptyList(),
    val friends: List<Friend> = emptyList(),
    val friendRequests: FriendRequests? = null,
    val dialogs: List<Dialog> = emptyList(),
    val chatTarget: ChatTarget? = null,
    val wallet: WalletBalance? = null,
    val walletHistory: List<WalletOperation> = emptyList(),
    val achievements: AchievementsPage? = null,
    val newsFeed: List<NewsItem> = emptyList(),
    val textureLibrary: List<TextureEntry> = emptyList(),
    val bundledRuntime: Boolean = false,
    val onboarding: Boolean = false,
    val wallpaperPath: String = "",
    val liveWallpaper: String = "",
    val wallpaperMotion: Boolean = true,
    val cacheBytes: Long = 0,
    val duplicateBytes: Long = 0,
    val duplicateGroups: Int = 0,
    val themeMode: String = Prefs.THEME_DARK,
    val accentId: String = "green",
    val accentCustom: String = "",
    val preparingGame: Boolean = false,
    val bedrockMode: String = Prefs.BEDROCK_ASK,
    val bedrockOfficialReady: Boolean = false,
    val bedrockBridgeReady: Boolean = false,
    val bedrockBridgeProgress: Float = 0f,
    val bedrockTargetVersion: String = "",
    val appVersion: String = BuildConfig.VERSION_NAME,
    val loading: Set<Area> = emptySet(),
    val errors: Map<Area, String> = emptyMap(),
    val loadedAreas: Set<Area> = emptySet(),
    val millidaSignedIn: Boolean = false,
    val sessionExpired: AccountKind? = null,
    val unreadMessages: Int = 0,
    val soundMode: String = Prefs.SOUND_NOTIFY,
    val soundVolume: Int = Prefs.SOUND_VOLUME_DEFAULT,
    val haptics: Boolean = true,
    val profilesLoaded: Boolean = false,
    val lowMemoryNotice: LowMemoryNotice? = null,
    val lowMemoryPlay: PendingPlay? = null,
    val millidaModOffer: PendingModOffer? = null,
    val linkJoin: MillidaLink.Join? = null,
) {
    val busy: String? get() = tasks.lastOrNull()

    val openedProfile: Profile? get() = profiles.firstOrNull { it.id == openedProfileId }

    val incomingRequests: Int get() = friendRequests?.incoming?.size ?: 0

    val friendsBadge: Int get() = unreadMessages + incomingRequests

    val serverFilterKey: String
        get() = listOf(serverQuery.trim(), serverSort.id, serverLicense.id.orEmpty(), serverVersion, serverCategory, serverOnline).joinToString("|")

    fun isLoading(area: Area): Boolean = area in loading

    fun errorOf(area: Area): String? = errors[area]
}

/** A server that did not answer drops its old status and is marked silent, so its row stops waiting. */
private fun UiState.withPings(asked: Collection<String>, answers: Map<String, ServerStatus>): UiState {
    val silent = asked.filterNot { it in answers }.toSet()
    return copy(
        serverStatuses = serverStatuses - silent + answers,
        silentServers = silentServers - answers.keys + silent,
    )
}

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val container = Container.get(application)
    private val prefs = Prefs(application)
    private val state = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = state.asStateFlow()

    val lobby = LobbyModel(container, viewModelScope) { text, isError -> notice(text, isError) }

    val support = SupportModel(application, container, prefs, viewModelScope) { text, isError -> notice(text, isError) }

    val shop = ShopModel(
        container = container,
        scope = viewModelScope,
        notice = { text, isError -> notice(text, isError) },
        onSessionLost = { viewModelScope.launch { millidaReady() } },
        onInventoryChanged = { if (state.value.gameProfile != null) loadSkins() },
    )

    private var loginJob: Job? = null
    private var playJob: Job? = null
    private var packWatch: Job? = null
    private val packGate by lazy { PackLaunchGate(container.packApi) }
    private val memorySaveJobs = HashMap<String, Job>()
    private var messageSeq = 0L
    private val profileWrites = Mutex()
    private val microsoftRefreshLock = Mutex()
    private val areaTickets = ConcurrentHashMap<Area, Long>()
    private val ticketSeq = AtomicLong()
    private val friendsSeq = AtomicLong()
    private val bedrockSeq = AtomicLong()
    private val appResumed = MutableStateFlow(false)

    val catalogModel = CatalogModel(
        container,
        viewModelScope,
        object : CatalogHost {
            override val profiles: List<Profile> get() = state.value.profiles
            override val activeProfileId: String? get() = state.value.activeProfileId

            override fun notice(text: String, isError: Boolean) = this@LauncherViewModel.notice(text, isError)

            override fun errorText(error: Throwable): String = loadErrorText(error)

            override fun buildInstalled(profile: Profile) {
                reloadProfiles()
                if (state.value.screen == Screen.CATALOG) {
                    openBuild(profile)
                } else {
                    this@LauncherViewModel.notice("Сборка «${profile.name}» готова")
                }
            }

            override fun markPending() = this@LauncherViewModel.markPending(Area.CATALOG)

            override suspend fun loadArea(block: suspend (current: () -> Boolean) -> Unit) =
                this@LauncherViewModel.loadArea(Area.CATALOG) { ticket -> block { ticket.current } }
        },
    )

    val millidaShelf = MillidaShelfModel(
        container,
        viewModelScope,
        catalogModel,
        object : MillidaShelfHost {
            override val signedIn: Boolean get() = state.value.millidaSignedIn

            override fun notice(text: String, isError: Boolean) = this@LauncherViewModel.notice(text, isError)

            override fun errorText(error: Throwable): String = loadErrorText(error)

            override fun signIn() = loginMillida()

            override fun openExternal(url: String): Boolean = openLink(getApplication<Application>(), url)
        },
    )

    private val millidaHost = object : MillidaContentHost {
        override val signedIn: Boolean get() = state.value.millidaSignedIn
        override val profiles: List<Profile> get() = state.value.profiles
        override val activeProfileId: String? get() = state.value.activeProfileId

        override fun notice(text: String, isError: Boolean) = this@LauncherViewModel.notice(text, isError)

        override fun errorText(error: Throwable): String = loadErrorText(error)

        override fun signIn() = loginMillida()

        override fun openExternal(url: String): Boolean = openLink(getApplication<Application>(), url)
    }

    val millidaContent = MillidaContentModel(container, viewModelScope, catalogModel, millidaShelf, millidaHost)

    val aiBuilder = AiBuilderModel(container, viewModelScope, catalogModel, millidaHost)

    val packUpdates = PackUpdateModel(container, viewModelScope, catalogModel, millidaShelf, millidaHost)

    val hub = HubModel(application, HubClient(container.millidaAuth), container.rating, viewModelScope, ::loadErrorText)

    val premium = PremiumModel(
        PremiumClient(container.millidaAuth, MillidaCatalog(container.millidaAuth)),
        HubClient(container.millidaAuth),
        viewModelScope,
        object : MillidaShelfHost {
            override val signedIn: Boolean get() = state.value.millidaSignedIn

            override fun notice(text: String, isError: Boolean) = this@LauncherViewModel.notice(text, isError)

            override fun errorText(error: Throwable): String = loadErrorText(error)

            override fun signIn() = loginMillida()

            override fun openExternal(url: String): Boolean = openLink(getApplication<Application>(), url)
        },
        installPack = { slug, title -> millidaShelf.openFromLink(MillidaLink.Install(MillidaShelfModel.SECTION, slug, name = title)) },
    )

    val ambient = AmbientModel(application, LobbyMusic(container.layout, container.downloader, container.resolver), viewModelScope)

    val social = SocialModel(
        container,
        viewModelScope,
        object : SocialHost {
            override val ui: StateFlow<UiState> get() = state
            override val resumed: StateFlow<Boolean> get() = appResumed

            override fun updateUi(change: (UiState) -> UiState) = state.update(change)

            override fun notice(text: String, isError: Boolean) = this@LauncherViewModel.notice(text, isError)

            override fun reloadFriends() {
                loadFriends()
            }

            override fun recheckSignIn() {
                viewModelScope.launch { millidaReady() }
            }
        },
        FriendAlerts(application),
    )

    init {
        state.update {
            it.copy(
                rendererId = prefs.rendererId,
                lookSensitivity = prefs.lookSensitivity,
                memoryOverrideMb = prefs.memoryOverrideMb,
                windowLayer = prefs.windowLayer,
                themeMode = prefs.themeMode,
                onboarding = !prefs.onboardingDone,
                lowMemoryNotice = if (prefs.lowMemoryNoticeHidden) null else MemoryTuning.lowMemory(container.deviceMemory()),
                bundledRuntime = container.bundledRuntime,
                wallpaperPath = prefs.wallpaperPath,
                liveWallpaper = prefs.liveWallpaper,
                wallpaperMotion = prefs.wallpaperMotion,
                accentId = prefs.accentId,
                accentCustom = prefs.accentCustom,
                bedrockMode = prefs.bedrockMode,
            safeGraphics = prefs.safeGraphics,
                memoryCeilingMb = MemoryTuning.settingCeilingMb(container.deviceMemory().totalMb),
                soundMode = prefs.soundMode,
                soundVolume = prefs.soundVolume,
                haptics = prefs.haptics,
            )
        }
        if (prefs.soundMode != Prefs.SOUND_OFF) viewModelScope.launch { container.uiSoundPlayer.prepare() }
        // Both are quiet on purpose: a launcher that cannot reach the channel
        // still has to start.
        viewModelScope.launch { SelfInstallEvents.events.collect { notice(it.text, it.isError) } }
        checkUpdate()
        openWhatsNewIfDue()
        installBundledRuntime()
        viewModelScope.launch {
            val signedIn = millidaReady()
            runTask("Читаем данные лаунчера", silent = true) {
                val profiles = profileWrites.withLock {
                    try {
                        PackNames.migrate(container.profiles)
                    } catch (error: IOException) {
                        Log.w("millida", "названия сборок без версии сборки не записались: ${error.message}")
                        container.profiles.load()
                    }
                }
                val accounts = container.accounts.load()
                val active = accounts.firstOrNull { it.id == prefs.activeAccountId }
                    ?: accounts.firstOrNull()
                val report = container.deviceReport()
                val remembered = prefs.activeProfileId.takeIf { id -> profiles.any { it.id == id } }
                state.update {
                    it.copy(
                        profiles = profiles,
                        accounts = accounts,
                        account = active,
                        activeProfileId = it.activeProfileId ?: remembered,
                        deviceReport = report,
                        importedRuntime = importedLabel(),
                    )
                }
            }
            state.update { it.copy(profilesLoaded = true) }
            container.telemetry.appStarted()
            refreshVersions()
            refreshRenderers()
            refreshRuntimeManifest()
            if (signedIn) refreshPortal()
        }
        viewModelScope.launch {
            combine(appResumed, state.map { it.millidaSignedIn }) { resumed, signedIn -> resumed && signedIn }
                .distinctUntilChanged()
                .collectLatest { active ->
                    while (active) {
                        if (!refreshCounters()) break
                        delay(COUNTERS_PERIOD_MS)
                    }
                }
        }
        viewModelScope.launch {
            appResumed.collectLatest { resumed ->
                val telemetry = container.telemetry
                if (!resumed) {
                    telemetry.flushSoon()
                    return@collectLatest
                }
                withContext(Dispatchers.IO) {
                    val crash = RuntimeSession.nativeCrash(container.layout.runtimes)?.lastModified()?.takeIf { it > 0 }
                    val runtimes = container.layout.runtimes
                    telemetry.settleLostGame(GameProcess.alive(getApplication()), crash) { since ->
                        // A game that called System.exit after a crash leaves its own report, which names the
                        // failure better than the session tail, so it goes first.
                        val report = runCatching { lostGameCrashReport(since) }.getOrNull()
                        val session = RuntimeSession.lastSessionLog(runtimes)
                            ?.takeIf { it.lastModified() >= since }
                            ?.let { log -> container.gameLogs.tail(log, LOST_GAME_LOG_LINES) }
                        listOfNotNull(report, session)
                            .filter { it.isNotBlank() }
                            .takeIf { it.isNotEmpty() }
                            ?.let { parts ->
                                // The same masks as a reported end: the player's nicks and build names stay home.
                                val masks = state.value.accounts.map { it.nick to "<nick>" } +
                                    state.value.profiles.map { it.name to "<build>" }
                                ReportRedaction.clean(parts.joinToString(System.lineSeparator() + System.lineSeparator()), masks)
                            }
                    }
                    telemetry.reportGameExits()
                }
                while (true) {
                    withContext(Dispatchers.IO) { telemetry.flush() }
                    delay(TelemetryCore.FLUSH_MS)
                }
            }
        }
        // A signed-in launcher's heartbeat rides inside the presence beat (SocialModel); a guest's goes alone.
        viewModelScope.launch {
            combine(appResumed, state.map { it.millidaSignedIn }) { resumed, signedIn -> resumed && !signedIn }
                .distinctUntilChanged()
                .collectLatest { guest ->
                    while (guest) {
                        withContext(Dispatchers.IO) { container.telemetry.beatAlone(BeatState.IDLE) }
                        delay(GUEST_BEAT_MS)
                    }
                }
        }
        viewModelScope.launch {
            combine(appResumed, state.map { it.millidaSignedIn }) { resumed, signedIn -> resumed to signedIn }
                .distinctUntilChanged()
                .collectLatest { (resumed, signedIn) ->
                    if (!signedIn) shop.prime(false) else if (resumed) shop.prime(true)
                }
        }
        viewModelScope.launch {
            state.map { it.millidaSignedIn }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    millidaShelf.signInChanged()
                    premium.signInChanged()
                    aiBuilder.loadQuota()
                }
        }
        viewModelScope.launch {
            state.map { it.profilesLoaded to it.activeProfileId }
                .distinctUntilChanged()
                .collectLatest { (loaded, id) -> if (loaded) prefs.activeProfileId = id.orEmpty() }
        }
    }

    private val history = ArrayDeque<Screen>()
    private var catalogReturn: List<Screen>? = null

    /** Called by the activity: the badge counters are refreshed only while the launcher is on screen. */
    fun setResumed(resumed: Boolean) {
        appResumed.value = resumed
        if (resumed) millidaShelf.onResumed()
        if (resumed) premium.onResumed()
        if (resumed) installReadyUpdate(manual = false)
        ambient.onResumed(resumed)
    }

    /**
     * A `millida://` address from the site. Nothing starts by itself: an install
     * opens the pack's sheet, a code opens its preview, a server asks first -
     * any page can fire such a link.
     */
    fun handleLink(raw: String?) {
        val link = MillidaLinks.parse(raw)
        if (link == null) {
            notice("Ссылка не распознана — открой её на сайте ещё раз", isError = true)
            return
        }
        when (link) {
            is MillidaLink.Join -> state.update { it.copy(linkJoin = link) }
            is MillidaLink.Pack -> {
                open(Screen.PACKS)
                previewPack(link.code)
            }
            is MillidaLink.Project -> openCatalogItem(
                if (link.modpack) CatalogTab.MODPACKS else CatalogTab.MODS,
                CatalogItem(projectId = link.slug, slug = link.slug, title = link.slug, source = CatalogSource.MODRINTH),
            )
            is MillidaLink.Install -> openInstallLink(link)
            is MillidaLink.Invite -> {
                open(Screen.FRIENDS)
                social.offerInviteCode(link.code)
            }
        }
    }

    fun confirmLinkJoin() {
        val ask = state.value.linkJoin ?: return
        state.update { it.copy(linkJoin = null) }
        playServer(ask.address, ask.name, ask.versions)
    }

    fun dismissLinkJoin() = state.update { it.copy(linkJoin = null) }

    private fun openInstallLink(link: MillidaLink.Install) {
        val tab = LINK_TABS[link.section]
        when {
            link.section == MillidaShelfModel.SECTION -> {
                open(Screen.CATALOG)
                catalogModel.setTab(CatalogTab.MODPACKS)
                millidaShelf.openFromLink(link)
            }
            tab != null -> millidaShelf.openContentLink(link, native = { item -> openNativeItem(tab, item) }) { item ->
                openCatalogItem(tab, item)
            }
            link.section == "skins" || link.section == "capes" -> {
                open(Screen.SKINS)
                notice("На телефоне это по ссылке пока не надевается — выбери во вкладке «Персонаж»", tone = ToastTone.Info)
            }
            else -> notice(
                "«${link.slug}» на телефоне не ставится — поставь через Millida Launcher на компьютере",
                isError = true,
            )
        }
    }

    /** A file an author uploaded to us: the build it goes into is confirmed first, nothing starts by itself. */
    private fun openNativeItem(tab: CatalogTab, item: MillidaItem) {
        val kind = tab.millida ?: return
        open(Screen.CATALOG)
        catalogModel.setTab(tab)
        millidaContent.askFromLink(item, kind)
    }

    private fun openCatalogItem(tab: CatalogTab, item: CatalogItem) {
        open(Screen.CATALOG)
        catalogModel.setTab(tab)
        catalogModel.openDetail(item)
    }

    /**
     * A sub-screen returns to the screen that opened it, a tab returns to HOME,
     * and HOME answers false so the activity finishes.
     */
    fun back(): Boolean {
        val current = state.value.screen
        val trail = catalogReturn
        catalogReturn = null
        if (current == Screen.CATALOG && trail != null && state.value.openedProfile != null) {
            history.clear()
            history.addAll(trail)
            state.update { it.copy(screen = Screen.BUILD) }
            return true
        }
        if (current == Screen.HOME) {
            history.clear()
            return false
        }
        val target = if (current.isRoot) {
            history.clear()
            Screen.HOME
        } else {
            history.removeLastOrNull() ?: rootTabOf(current)
        }
        state.update { it.copy(screen = target) }
        return true
    }

    /** Whether [back] from the screen on show lands in the lobby, without going there. */
    fun backLeadsHome(): Boolean {
        val current = state.value.screen
        if (current == Screen.CATALOG && catalogReturn != null && state.value.openedProfile != null) return false
        if (current == Screen.HOME || current.isRoot) return true
        return (history.lastOrNull() ?: rootTabOf(current)) == Screen.HOME
    }

    fun openCatalogFromBuild() {
        val trail = history.toList()
        val fromBuild = state.value.screen == Screen.BUILD && state.value.openedProfile != null
        open(Screen.CATALOG)
        if (fromBuild) catalogReturn = trail
    }

    fun open(screen: Screen) {
        catalogReturn = null
        val current = state.value.screen
        when {
            screen.isRoot -> history.clear()
            current != screen -> history.addLast(current)
        }
        while (history.size > HISTORY_LIMIT) history.removeFirst()
        state.update { it.copy(screen = screen) }
        if (current != screen) {
            container.telemetry.track(
                TelemetryType.SCREEN_VIEW,
                EventData().put("screen", screen.name.lowercase()).put("from", current.name.lowercase()).build(),
            )
        }
        if (screen == Screen.SERVERS && state.value.servers.isEmpty()) loadServers(reset = true)
        if (screen == Screen.SERVERS && state.value.serverVersions.isEmpty()) loadServerVersions()
        if (screen == Screen.SKINS && state.value.gameProfile == null) loadSkins()
        if (screen == Screen.BEDROCK || screen == Screen.MORE) refreshBedrock()
        if (screen == Screen.PACKS && state.value.myPacks.isEmpty()) loadMyPacks()
        if (screen == Screen.SKINS && state.value.textureLibrary.isEmpty()) loadTextures()
        if (screen == Screen.HOSTING && state.value.hosted.isEmpty()) refreshHosted()
        if (screen == Screen.SHOP) shop.open(state.value.millidaSignedIn)
    }

    /**
     * Another build's lists and plans are dropped on the way in: an update plan
     * left over from the previous build would be applied to this one's folder.
     */
    fun openBuild(profile: Profile) {
        state.update { current ->
            val same = current.openedProfileId == profile.id
            current.copy(
                openedProfileId = profile.id,
                activeProfileId = profile.id,
                buildContent = if (same) current.buildContent else emptyList(),
                buildWorlds = if (same) current.buildWorlds else emptyList(),
                buildLog = if (same) current.buildLog else null,
                buildServers = if (same) current.buildServers else emptyList(),
                worldBackups = if (same) current.worldBackups else emptyList(),
                buildMeta = if (same) current.buildMeta else null,
                migratePlan = if (same) current.migratePlan else null,
                contentUpdatePlan = if (same) current.contentUpdatePlan else null,
                safetyReport = if (same) current.safetyReport else null,
            )
        }
        open(Screen.BUILD)
    }

    fun selectBuild(profile: Profile) {
        state.update { it.copy(activeProfileId = profile.id) }
    }

    fun retry(area: Area) {
        when (area) {
            Area.SERVERS -> loadServers(reset = true)
            Area.CATALOG -> catalogModel.reload()
            Area.FRIENDS -> loadFriends()
            Area.CABINET -> loadCabinet()
            Area.PACKS -> loadMyPacks()
            Area.HOSTING -> refreshHosted()
            Area.HOSTED_SERVER -> loadHostingDetails()
            Area.SKINS -> loadSkins()
            Area.BUILD -> refreshBuild()
            Area.SHOP -> shop.refresh()
            Area.ACCOUNT -> loadAccountDetails()
            Area.SHOTS -> loadShots()
        }
    }

    fun setBuildTab(tab: BuildTab) {
        state.update { it.copy(buildTab = tab) }
    }

    fun setBuildKind(kind: ContentKind) {
        state.update { it.copy(buildKind = kind) }
    }

    fun profileDirectory(profile: Profile): String = File(container.layout.profiles, profile.id).absolutePath

    fun refreshBuild() = viewModelScope.launch {
        val profile = state.value.openedProfile ?: return@launch
        val tab = state.value.buildTab
        val kind = state.value.buildKind
        if (tab == BuildTab.OPTIONS) {
            issueTicket(Area.BUILD)
            state.update { it.copy(loading = it.loading - Area.BUILD, errors = it.errors - Area.BUILD) }
        }
        when (tab) {
            BuildTab.OPTIONS -> Unit
            BuildTab.CONTENT, BuildTab.WORLDS, BuildTab.LOGS, BuildTab.SERVERS, BuildTab.SHOTS -> loadArea(Area.BUILD) { ticket ->
                val dir = container.profiles.directory(profile)
                when (tab) {
                    BuildTab.CONTENT -> {
                        val files = attempt { container.content.list(dir, kind) }
                        ticket.commit { it.copy(buildContent = files.getOrDefault(emptyList())) }
                        files.exceptionOrNull()?.let { throw it }
                    }

                    BuildTab.WORLDS -> {
                        val worlds = attempt { container.worlds.list(dir) }
                        val servers = attempt { container.serverList.list(dir) }
                        ticket.commit {
                            it.copy(buildWorlds = worlds.getOrDefault(emptyList()), buildServers = servers.getOrDefault(emptyList()))
                        }
                        servers.getOrNull()?.let { list -> if (ticket.current) pingServers(list.map { entry -> entry.address }) }
                        (worlds.exceptionOrNull() ?: servers.exceptionOrNull())?.let { throw it }
                    }

                    BuildTab.LOGS -> {
                        // A native crash never reaches the Kotlin handler, so its report is
                        // shown first: it is the only trace of that kind of failure.
                        val ownSession = state.value.profiles.maxByOrNull { it.lastPlayedAt }
                            ?.let { it.id == profile.id && it.lastPlayedAt > 0 } == true
                        val crash = if (ownSession) {
                            net.millida.launcher.runtime.GameSession.nativeCrash(container.layout.runtimes)
                        } else {
                            null
                        }
                        val session = if (ownSession) {
                            net.millida.launcher.runtime.GameSession.lastSessionLog(container.layout.runtimes)
                        } else {
                            null
                        }
                        val newest = container.gameLogs.files(dir).firstOrNull()
                        val parts = ArrayList<String>()
                        crash?.let { parts += container.gameLogs.tail(it, 120) }
                        session?.let { container.gameLogs.launcherLines(it) }?.takeIf { it.isNotBlank() }?.let { parts += it }
                        when {
                            session != null -> parts += container.gameLogs.tail(session)
                            newest != null -> parts += container.gameLogs.tail(newest)
                        }
                        val log = parts.joinToString(System.lineSeparator() + System.lineSeparator())
                            .takeIf { text -> text.isNotBlank() }
                        ticket.commit { it.copy(buildLog = log) }
                    }

                    BuildTab.SERVERS -> {
                        val list = container.serverList.list(dir)
                        ticket.commit { it.copy(buildServers = list) }
                        if (ticket.current) pingServers(list.map { entry -> entry.address })
                    }

                    BuildTab.SHOTS -> {
                        val shots = container.screenshots.list(profile.id)
                        ticket.commit { it.copy(shots = shots, shotsScope = profile.id) }
                    }

                    BuildTab.OPTIONS -> Unit
                }
            }
        }
    }

    fun toggleContent(file: ContentFile) = viewModelScope.launch(Dispatchers.IO) {
        guarded {
            val profile = state.value.openedProfile ?: return@guarded
            val dir = container.profiles.directory(profile)
            container.content.setEnabled(dir, file, !file.enabled)
        }
        refreshBuild()
    }

    fun deleteContent(file: ContentFile) = viewModelScope.launch(Dispatchers.IO) {
        guarded {
            val profile = state.value.openedProfile ?: return@guarded
            val dir = container.profiles.directory(profile)
            if (container.content.delete(dir, file)) {
                runCatching { PackOriginStore.forget(dir, "${file.kind.folder}/${file.name}") }
                notice("Удалено: ${file.name}")
            }
        }
        refreshBuild()
    }

    fun deleteWorld(world: WorldCard) = viewModelScope.launch(Dispatchers.IO) {
        guarded {
            val profile = state.value.openedProfile ?: return@guarded
            val dir = container.profiles.directory(profile)
            if (container.worlds.delete(dir, world.folder)) notice("Мир удалён")
        }
        refreshBuild()
    }

    fun renameProfile(profile: Profile, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            notice("Название не может быть пустым", isError = true)
            return
        }
        saveProfile(profile.copy(name = trimmed))
    }

    /**
     * A slider reports every frame of the drag. The value is shown at once and
     * written once the finger rests: parallel writes of the same file could
     * leave it broken, and a broken file costs every build.
     */
    fun setProfileMemory(profile: Profile, memoryMb: Int): Job {
        val base = state.value.profiles.firstOrNull { it.id == profile.id } ?: profile
        val updated = base.copy(memoryMb = memoryMb)
        state.update { current ->
            current.copy(profiles = current.profiles.map { if (it.id == updated.id) updated else it })
        }
        memorySaveJobs[updated.id]?.cancel()
        val job = viewModelScope.launch {
            delay(MEMORY_SAVE_DEBOUNCE_MS)
            patchProfile(updated.id) { it.copy(memoryMb = memoryMb) }
        }
        memorySaveJobs[updated.id] = job
        return job
    }

    fun setProfileRenderer(profile: Profile, rendererId: String) =
        saveProfile(profile.copy(rendererId = rendererId))

    private fun saveProfile(profile: Profile) = viewModelScope.launch { persistProfile(profile) }

    /** Re-read under the lock, so the stamp never writes back a stale copy over a newer save. */
    private suspend fun stampPlayed(profileId: String) {
        try {
            withContext(Dispatchers.IO) {
                profileWrites.withLock {
                    val stored = container.profiles.load().firstOrNull { it.id == profileId } ?: return@withLock
                    val profiles = container.profiles.upsert(stored.copy(lastPlayedAt = System.currentTimeMillis()))
                    state.update { it.copy(profiles = profiles) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("millida", "время запуска сборки не записалось: ${error.message}")
        }
    }

    private suspend fun persistProfile(profile: Profile) {
        try {
            withContext(Dispatchers.IO) {
                profileWrites.withLock {
                    val profiles = container.profiles.upsert(profile)
                    state.update { it.copy(profiles = profiles) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w("millida", "сборка не сохранилась: ${error.message}")
            notice("Настройки сборки не сохранились — проверь свободное место и повтори", isError = true)
        }
    }

    private suspend fun patchProfile(id: String, change: (Profile) -> Profile) {
        try {
            withContext(Dispatchers.IO) {
                profileWrites.withLock {
                    val stored = container.profiles.load().firstOrNull { it.id == id } ?: return@withLock
                    val profiles = container.profiles.upsert(change(stored))
                    state.update { it.copy(profiles = profiles) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w("millida", "сборка не сохранилась: ${error.message}")
            notice("Настройки сборки не сохранились — проверь свободное место и повтори", isError = true)
        }
    }

    private fun lostGameCrashReport(since: Long): String? {
        val played = container.profiles.load().maxByOrNull { it.lastPlayedAt }?.takeIf { it.lastPlayedAt > 0 } ?: return null
        val report = container.gameLogs.crashReports(container.profiles.directory(played)).firstOrNull()
            ?.takeIf { it.lastModified() >= since }
            ?: return null
        return report.bufferedReader().useLines { lines -> lines.take(LOST_GAME_REPORT_LINES).joinToString(System.lineSeparator()) }
    }

    fun consume(id: Long) {
        state.update { current -> current.copy(messages = current.messages.filterNot { it.id == id }) }
    }

    fun setOfflineNick(nick: String) = state.update { it.copy(offlineNick = nick) }

    fun setRenderer(id: String) {
        prefs.rendererId = id
        state.update { it.copy(rendererId = id) }
    }

    fun setSensitivity(value: Float) {
        prefs.lookSensitivity = value
        state.update { it.copy(lookSensitivity = value) }
    }

    fun setMemoryOverride(mb: Int) {
        prefs.memoryOverrideMb = mb
        state.update { it.copy(memoryOverrideMb = mb) }
    }

    fun setSafeGraphics(enabled: Boolean) {
        prefs.safeGraphics = enabled
        state.update { it.copy(safeGraphics = enabled) }
    }

    fun setWindowLayer(layer: String) {
        prefs.windowLayer = layer
        state.update { it.copy(windowLayer = layer) }
    }

    fun setServerQuery(query: String) = state.update { it.copy(serverQuery = query) }

    fun setServerVersion(version: String) {
        if (state.value.serverVersion == version) return
        state.update { it.copy(serverVersion = version) }
        loadServers(reset = true)
    }

    /** One reload for the whole reset: four setters in a row would fire four pages. */
    fun resetServerFilters() {
        state.update {
            it.copy(
                serverQuery = "",
                serverSort = RatingSort.RATING,
                serverLicense = LicenseFilter.ANY,
                serverVersion = "",
                serverCategory = "",
                serverOnline = "",
            )
        }
        loadServers(reset = true)
    }

    fun setServerCategory(category: String) {
        if (state.value.serverCategory == category) return
        state.update { it.copy(serverCategory = category) }
        loadServers(reset = true)
    }

    fun setServerOnline(online: String) {
        if (state.value.serverOnline == online) return
        state.update { it.copy(serverOnline = online) }
        loadServers(reset = true)
    }

    /** The version filter lists only branches that have servers; without the list the filter is hidden, not broken. */
    fun loadServerVersions() = viewModelScope.launch {
        runTask("Читаем версии серверов", silent = true) {
            val versions = container.rating.versions().newestFirst()
            state.update { it.copy(serverVersions = versions) }
        }
    }

    fun setServerSort(sort: RatingSort) {
        state.update { it.copy(serverSort = sort) }
        loadServers(reset = true)
    }

    fun setServerLicense(license: LicenseFilter) {
        state.update { it.copy(serverLicense = license) }
        loadServers(reset = true)
    }

    fun loadServers(reset: Boolean) = viewModelScope.launch {
        val snapshot = state.value
        val offset = if (reset) 0 else snapshot.servers.size
        loadArea(Area.SERVERS) { ticket ->
            val page = container.rating.servers(
                search = snapshot.serverQuery,
                sort = snapshot.serverSort,
                license = snapshot.serverLicense,
                version = snapshot.serverVersion.ifEmpty { null },
                category = snapshot.serverCategory.ifEmpty { null },
                minOnline = ServerFilters.minOnline(snapshot.serverOnline),
                offset = offset,
            )
            ticket.commit { current ->
                current.copy(
                    servers = if (reset) page.servers else current.servers + page.servers,
                    serversTotal = page.total,
                    serversFor = snapshot.serverFilterKey,
                )
            }
        }
    }

    private fun importedLabel(): String? = RuntimeImport.installed(container.layout)?.let { runtime ->
        "${runtime.renderer.label} · Java ${RuntimeImport.availableJava(container.layout).joinToString(", ")}"
    }

    /**
     * The pack that came inside the application. Unpacking is done once and
     * only when there is nothing installed yet: a player who already imported
     * their own pack must not have it replaced behind their back.
     */
    fun installBundledRuntime(force: Boolean = false) = viewModelScope.launch {
        if (!container.bundledRuntime) return@launch
        if (!force && RuntimeImport.installed(container.layout) != null) return@launch
        runTask("Распаковываем рантайм — это один раз") {
            withContext(Dispatchers.IO) {
                val source = container.openBundledRuntime()
                    ?: throw IllegalStateException("встроенный рантайм не открылся")
                source.use { stream ->
                    RuntimeImport.install(container.layout, stream) { name ->
                        state.update { it.copy(progressNote = name) }
                    }
                }
            }
            state.update { it.copy(importedRuntime = importedLabel()) }
            notice("Рантайм готов — можно создавать сборку")
        }
    }

    fun importRuntime(open: () -> java.io.InputStream?) = viewModelScope.launch {
        runTask("Ставим рантайм из файла") {
            withContext(Dispatchers.IO) {
                val stream = open() ?: throw IllegalStateException("файл не открылся")
                stream.use { source ->
                    RuntimeImport.install(container.layout, source) { name ->
                        state.update { it.copy(progressNote = name) }
                    }
                }
            }
            state.update { it.copy(importedRuntime = importedLabel()) }
            notice("Рантайм установлен — можно запускать сборку")
        }
    }

    fun dropRuntime() = viewModelScope.launch {
        runTask("Удаляем рантайм") {
            RuntimeImport.clear(container.layout)
            state.update { it.copy(importedRuntime = null) }
            notice("Рантайм из файла удалён")
        }
    }

    private suspend fun refreshVersions() {
        runTask("Обновляем список версий", silent = true) {
            val manifest = container.resolver.manifest()
            state.update { it.copy(versions = manifest.releases().take(120)) }
        }
    }

    /**
     * The catalogue decides offline whether an SDL3 pack can start, from the last
     * manifest this phone saw. A phone with a bundled runtime never asks for the
     * manifest otherwise, so it would not learn about components published later.
     * A failed refresh only keeps the previous copy, which is the same answer as before.
     */
    private fun refreshRuntimeManifest() = viewModelScope.launch {
        try {
            container.runtimeInstaller().manifest()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.i("millida", "манифест рантайма не обновился: ${failure.message}")
        }
    }

    private suspend fun refreshRenderers() {
        // The installed pack is the authority: it is what the game will actually
        // load, so its own list comes first and the manifest only fills the gap.
        val packed = RuntimeImport.renderers(container.layout)
        if (packed.isNotEmpty()) {
            val entries = packed.map { option ->
                RuntimeManifest.RendererEntry(
                    id = option.id,
                    label = option.label,
                    url = "",
                    sha256 = "",
                    library = option.library,
                    eglLibrary = option.eglLibrary,
                    nativeId = option.nativeId,
                    shaders = option.shaders,
                    environment = option.environment,
                )
            }
            state.update { current ->
                current.copy(
                    renderers = entries,
                    rendererId = current.rendererId.ifEmpty { entries.first().id },
                )
            }
            return
        }
        try {
            val manifest = container.runtimeInstaller().manifest()
            state.update { current ->
                current.copy(
                    renderers = manifest.renderers,
                    rendererId = current.rendererId.ifEmpty { manifest.renderers.firstOrNull()?.id.orEmpty() },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.update { it.copy(renderers = emptyList()) }
        }
    }

    private suspend fun refreshPortal() {
        runTask("Читаем профиль Millida", silent = true) {
            val profile = container.portal.profile()
            val privacy = runCatching { container.portal.privacy() }.getOrNull()
            val hosted = runCatching { container.hosting.servers() }.getOrDefault(emptyList())
            state.update { it.copy(portal = profile, privacy = privacy, hosted = hosted) }
        }
    }

    fun togglePrivacy(field: String, value: Boolean) = viewModelScope.launch {
        runTask("Сохраняем настройки приватности") {
            val updated = container.portal.updatePrivacy(field, value)
            state.update { it.copy(privacy = updated) }
        }
    }

    override fun onCleared() {
        ambient.close()
        container.telemetry.appExited()
        super.onCleared()
    }

    fun setDuoInvites(value: String) = viewModelScope.launch {
        runTask("Сохраняем, кто может звать в эмоции вдвоём") {
            val updated = container.portal.updateDuoInvites(value)
            state.update { it.copy(privacy = updated) }
        }
    }

    fun createProfile(name: String, gameVersion: String, loader: LoaderKind) = viewModelScope.launch {
        runTask("Создаём сборку") {
            try {
                createProfileNow(name, gameVersion, loader)
            } catch (error: Exception) {
                container.telemetry.failure(
                    TelemetryType.BUILD_CREATE,
                    error,
                    EventData().put("step", "create").put("mc", gameVersion).put("loader", loader.id).build(),
                )
                throw error
            }
        }
    }

    private suspend fun createProfileNow(name: String, gameVersion: String, loader: LoaderKind) {
        val id = container.profiles.newId(name)
        var versionId = gameVersion
        var loaderVersion = ""
        if (loader != LoaderKind.VANILLA) {
            val installer = container.loaderInstaller(loader)
                ?: throw IllegalStateException("${loader.label} пока не ставится")
            val candidates = installer.versions(gameVersion)
            val chosen = candidates.firstOrNull { it.stable } ?: candidates.firstOrNull()
                ?: throw IllegalStateException("${loader.label} не выходил для $gameVersion — выбери другую версию игры или другой загрузчик")
            loaderVersion = chosen.version
            versionId = if (installer is ForgeFamilyInstaller) {
                installer.install(gameVersion, chosen.version) { step -> publishLoader(loader, step) }
            } else {
                installer.install(gameVersion, chosen.version)
            }
            state.update { it.copy(progressNote = "", progress = 0f) }
        }
        val profile = Profile(
            id = id,
            name = name.ifBlank { "Сборка $gameVersion" },
            gameVersion = gameVersion,
            loader = loader,
            loaderVersion = loaderVersion,
            versionId = versionId,
        )
        container.profiles.directory(profile)
        val profiles = container.profiles.upsert(profile)
        state.update { it.copy(profiles = profiles) }
        container.telemetry.track(
            TelemetryType.BUILD_CREATE,
            EventData().put("mc", gameVersion).put("loader", loader.id).put("loaderVersion", loaderVersion.ifEmpty { "auto" }).build(),
        )
        notice("Сборка «${profile.name}» создана")
    }

    fun deleteProfile(id: String) = viewModelScope.launch {
        runTask("Удаляем сборку") {
            val gone = state.value.profiles.firstOrNull { it.id == id }
            dropBuild(id)
            gone?.let {
                container.telemetry.track(
                    TelemetryType.BUILD_DELETE,
                    EventData().put("mc", it.gameVersion).put("loader", it.loader.id).build(),
                )
            }
            notice("Сборка удалена")
        }
    }

    private fun dropBuild(id: String) {
        val profile = state.value.profiles.firstOrNull { it.id == id }
        val profiles = container.profiles.remove(id)
        profile?.let { container.profiles.directory(it).deleteRecursively() }
        forgetBuildRecords(id)
        state.update {
            it.copy(
                profiles = profiles,
                openedProfileId = null,
                activeProfileId = it.activeProfileId.takeUnless { active -> active == id },
            )
        }
    }

    /**
     * Access to a protected pack is gone: the game closes, the player's worlds
     * and files are moved aside and the pack leaves the phone, so paid content
     * does not stay behind. A failed move keeps the build. Returns what the
     * player is told.
     */
    private suspend fun forfeitPack(profile: Profile, slug: String, reason: String): String {
        GameProcess.kill(getApplication<Application>())
        return try {
            withContext(Dispatchers.IO) {
                container.packs.stashPlayerFiles(profile, slug)
                dropBuild(profile.id)
            }
            "$reason. Сборка удалена с телефона, а миры и твои файлы сохранены — они вернутся, когда установишь её снова"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            "$reason. Сборку не удалось убрать: ${error.message}"
        }
    }

    /**
     * A launcher-only server is joined by its own client pack: one press uses
     * the installed one or installs it from our catalogue, then starts it. The
     * pack's own launch decides how it reaches the server, as on the desktop.
     */
    private fun playOwnServer(own: OwnServer) {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) {
                container.packs.installedCatalogPack(state.value.profiles, own.pack)
            }
            val start: (Profile) -> Unit = { build ->
                state.update { it.copy(activeProfileId = build.id) }
                play(build)
            }
            if (installed != null) {
                start(installed)
                return@launch
            }
            millidaShelf.installAndPlay(
                slug = own.pack,
                title = own.title,
                reveal = {},
                play = start,
            )
        }
    }

    /** The build is already gone, so a leftover record is only clutter: it is logged, not reported as a failed delete. */
    private fun forgetBuildRecords(profileId: String) {
        listOf(
            runCatching { container.buildMeta.forget(profileId) },
            runCatching { container.playtime.forget(profileId) },
        ).mapNotNull { it.exceptionOrNull() }.forEach { error ->
            Log.w("millida", "сведения удалённой сборки не стёрлись: ${error.message}")
        }
    }

    /**
     * Joining a server is the same launch with one extra argument, so the button
     * on a server card leads to the game itself instead of to a copied address.
     */
    fun playOnAddress(address: String, name: String = "Millida") {
        OwnServers.of(null, address)?.let { own ->
            playOwnServer(own)
            return
        }
        if (isBedrockAddress(address)) {
            openBedrockServer(address, name)
            return
        }
        val profiles = state.value.profiles
        val profile = profiles.firstOrNull { it.id == state.value.activeProfileId }
            ?: profiles.firstOrNull()
        if (profile == null) {
            open(Screen.HOME)
            notice("Сначала создай сборку — в неё и зайдёт игра", isError = true)
            return
        }
        play(profile, address)
    }

    fun playServer(address: String, name: String, versions: List<String>, slug: String = "") {
        OwnServers.of(slug, address)?.let { own ->
            playOwnServer(own)
            return
        }
        if (address.isBlank()) {
            notice("У сервера нет адреса — выбери другой", isError = true)
            return
        }
        if (isBedrockAddress(address)) {
            playOnAddress(address, name)
            return
        }
        if (!lobby.beginJoin(address)) return
        container.telemetry.track(
            TelemetryType.SERVER_JOIN,
            if (slug.isNotEmpty()) {
                EventData().put("addr", address.take(64)).put("name", name.take(64)).put("slug", slug.take(80)).build()
            } else {
                EventData().put("rated", false).build()
            },
        )
        viewModelScope.launch {
            try {
                val releases = state.value.versions.map { it.id }
                val wanted = lobby.wantedVersions(address, versions, releases)
                val snapshot = state.value
                when (val plan = joinPlan(joinableBuilds(snapshot.profiles), snapshot.activeProfileId, wanted)) {
                    is JoinPlan.Launch -> joinServer(plan.build, address, name)
                    is JoinPlan.Switch -> {
                        notice("Сервер на ${wanted.joinToString(", ")} — заходим сборкой «${plan.build.name}»")
                        joinServer(plan.build, address, name)
                    }
                    else -> lobby.askJoin(
                        JoinPrompt(
                            address = address,
                            serverName = name,
                            wanted = wanted,
                            plan = plan,
                            version = pickVersionForServer(snapshot.versions.map { it.id }, wanted),
                        ),
                    )
                }
            } finally {
                lobby.endJoin(address)
            }
        }
    }

    fun joinServer(profile: Profile, address: String, name: String) {
        lobby.dismissJoin()
        state.update { it.copy(activeProfileId = profile.id) }
        play(profile, address)
    }

    fun createBuildAndJoin(version: String, address: String, serverName: String) {
        lobby.dismissJoin()
        val before = state.value.profiles.map { it.id }.toSet()
        val buildName = serverName.trim().take(16).let { if (it.isEmpty()) "Сборка $version" else "$it $version" }
        viewModelScope.launch {
            createProfile(buildName, version, LoaderKind.VANILLA).join()
            val made = state.value.profiles.firstOrNull { it.id !in before && it.gameVersion == version }
                ?: return@launch
            joinServer(made, address, serverName)
        }
    }

    /**
     * Two ways lead into a Bedrock server, and the player owns the choice. With
     * no choice made the launcher takes the one that is ready, and sends the
     * player to the screen when neither is.
     */
    fun openBedrockServer(address: String, name: String = "Millida") {
        when (prefs.bedrockMode) {
            Prefs.BEDROCK_OFFICIAL -> playOnBedrockEdition(address, name)
            Prefs.BEDROCK_OWN -> playBedrockThroughBridge(address, name)
            else -> when {
                bedrockInstalled(getApplication()) -> playOnBedrockEdition(address, name)
                BedrockBridge(container.layout.root).installed() -> playBedrockThroughBridge(address, name)
                else -> {
                    refreshBedrock()
                    open(Screen.BEDROCK)
                    notice("Выбери, чем открывать Bedrock-серверы", tone = ToastTone.Info)
                }
            }
        }
    }

    /**
     * The Bedrock client is a separate application, so the launcher stops at
     * handing over the address - and says plainly when it cannot.
     */
    fun playOnBedrockEdition(address: String, name: String = "Millida") {
        val parsed = parseBedrockAddress(address)
        if (parsed == null) {
            notice("Не разобрал адрес Bedrock: $address", isError = true)
            return
        }
        when (playOnBedrock(getApplication(), name, parsed)) {
            BedrockHandoff.CONNECTED -> notice("Открываю Bedrock: $parsed")
            BedrockHandoff.ADDED -> notice("Сервер сохранён в Bedrock — открой в игре «Серверы»")
            BedrockHandoff.NOT_INSTALLED -> {
                refreshBedrock()
                open(Screen.BEDROCK)
                notice("Официальный клиент не установлен", isError = true)
            }
            BedrockHandoff.FAILED -> notice("Bedrock-версия не открылась", isError = true)
        }
    }

    fun installBedrockEdition() = openBedrockStore(getApplication())

    fun openBedrockEdition() {
        if (!openBedrockApp(getApplication())) notice("Bedrock-версия не открылась", isError = true)
    }

    fun bedrockReady(): Boolean = bedrockInstalled(getApplication())

    /** What is ready on this phone: the official client, our translator, or neither. */
    fun refreshBedrock() {
        val seq = bedrockSeq.incrementAndGet()
        viewModelScope.launch {
            val app = getApplication<Application>()
            val (official, installed, target) = withContext(Dispatchers.IO) {
                val bridge = BedrockBridge(container.layout.root)
                val ready = bridge.installed()
                Triple(bedrockInstalled(app), ready, if (ready) bridge.targetVersion() else "")
            }
            if (seq != bedrockSeq.get()) return@launch
            state.update {
                it.copy(
                    bedrockMode = prefs.bedrockMode,
                    bedrockOfficialReady = official,
                    bedrockBridgeReady = installed,
                    bedrockTargetVersion = target,
                )
            }
        }
    }

    fun closeLowMemoryNotice(hideForever: Boolean) {
        if (hideForever) prefs.lowMemoryNoticeHidden = true
        state.update { it.copy(lowMemoryNotice = null) }
    }

    fun confirmLowMemoryPlay() {
        val pending = state.value.lowMemoryPlay ?: return
        state.update { it.copy(lowMemoryPlay = null) }
        play(pending.profile, pending.serverAddress, memoryConfirmed = true)
    }

    fun cancelLowMemoryPlay() {
        state.update { it.copy(lowMemoryPlay = null) }
    }

    fun acceptMillidaModOffer() {
        val pending = state.value.millidaModOffer ?: return
        state.update { it.copy(millidaModOffer = null) }
        val installing = viewModelScope.launch(start = CoroutineStart.LAZY) {
            runTask("Ставим мод Millida") {
                try {
                    installMillidaMod(pending.profile)
                } catch (error: IOException) {
                    throw IOException("Мод Millida не поставился (${error.message}) — игра запустится без него", error)
                }
            }
            loadCosmetics(pending.profile)
            playJob = null
            play(pending.profile, pending.serverAddress, memoryConfirmed = true, modAnswered = true)
        }
        playJob = installing
        installing.start()
    }

    fun declineMillidaModOffer() {
        val pending = state.value.millidaModOffer ?: return
        state.update { it.copy(millidaModOffer = null) }
        prefs.millidaModDeclined = prefs.millidaModDeclined + pending.profile.id
        play(pending.profile, pending.serverAddress, memoryConfirmed = true, modAnswered = true)
    }

    fun cancelMillidaModOffer() {
        state.update { it.copy(millidaModOffer = null) }
    }

    fun finishOnboarding() {
        prefs.onboardingDone = true
        state.update { it.copy(onboarding = false) }
    }

    /** One background at a time, as on the desktop: an own picture replaces a live one. */
    fun setWallpaper(path: String) {
        prefs.wallpaperPath = path
        if (path.isNotEmpty()) prefs.liveWallpaper = LiveWallpaper.OFF
        state.update { it.copy(wallpaperPath = path, liveWallpaper = if (path.isNotEmpty()) LiveWallpaper.OFF else it.liveWallpaper) }
    }

    fun setLiveWallpaper(id: String) {
        prefs.liveWallpaper = id
        state.update { it.copy(liveWallpaper = prefs.liveWallpaper) }
    }

    fun setWallpaperMotion(on: Boolean) {
        prefs.wallpaperMotion = on
        state.update { it.copy(wallpaperMotion = on) }
    }

    /**
     * Caches are the launcher's own copies: downloads in flight, unpacked
     * temporaries, thumbnails. Games, builds and worlds are never touched here.
     */
    fun measureCache() = viewModelScope.launch {
        runTask("Считаем кэш", silent = true) {
            val size = directorySize(getApplication<Application>().cacheDir) +
                directorySize(container.layout.caches)
            state.update { it.copy(cacheBytes = size) }
        }
    }

    /**
     * The same mod in five builds is five copies of the same bytes. They are
     * collapsed into one file through hard links, so every build still has its
     * own file and none of them loses it.
     */
    fun scanDuplicates() = viewModelScope.launch {
        runTask("Ищем одинаковые файлы") {
            val report = container.dedupe.scan()
            state.update {
                it.copy(duplicateBytes = report.reclaimable, duplicateGroups = report.groups.size)
            }
            if (report.groups.isEmpty()) notice("Одинаковых файлов не нашлось", tone = ToastTone.Info)
        }
    }

    fun runDedupe() = viewModelScope.launch {
        runTask("Склеиваем одинаковые файлы") {
            container.dedupe.collectGarbage()
            val report = container.dedupe.scan()
            val result = container.dedupe.run(report)
            state.update { it.copy(duplicateBytes = 0, duplicateGroups = 0) }
            notice(
                if (result.failed > 0) {
                    "Освободили место, но ${result.failed} файлов остались как были"
                } else {
                    "Готово, склеено файлов: ${result.linked}"
                },
                isError = result.failed > 0,
            )
        }
    }

    fun clearCache() = viewModelScope.launch {
        runTask("Чистим кэш") {
            getApplication<Application>().cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            container.layout.caches.listFiles()?.forEach { it.deleteRecursively() }
            measureCache()
            notice("Кэш очищен")
        }
    }

    // --- Косметика Millida: без неё скин и плащ видны только в лаунчере ---

    fun loadCosmetics(profile: Profile) = viewModelScope.launch {
        runTask("Проверяем косметику", silent = true) {
            val state1 = container.millidaMod.state(profile)
            val diagnosis = container.millidaMod.diagnose(profile)
            state.update { it.copy(cosmetics = state1, skinDiagnosis = diagnosis) }
        }
    }

    fun installCosmetics(profile: Profile) = viewModelScope.launch {
        runTask("Ставим косметику Millida") {
            val result = installMillidaMod(profile)
            state.update { it.copy(cosmetics = result) }
            loadCosmetics(profile)
            notice("Косметика в сборке: скин и плащ будут видны в игре")
        }
    }

    /** Nothing to do when the catalogue cannot be read: the start then goes on as it is. */
    private suspend fun millidaModAction(profile: Profile, declined: Boolean): LaunchModAction = try {
        withContext(Dispatchers.IO) {
            val mod = container.millidaMod.state(profile)
            MillidaModOffer.onLaunch(
                mod,
                container.millidaMod.intact(profile, mod),
                declined,
                container.millidaMod.updateDue(profile, mod),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("millida", "мод Millida: состояние сборки не прочитано: ${error.message}")
        LaunchModAction.NONE
    }

    private fun updateMillidaModAndPlay(profile: Profile, serverAddress: String) {
        val updating = viewModelScope.launch(start = CoroutineStart.LAZY) {
            runTask("Обновляем мод Millida") {
                try {
                    withContext(Dispatchers.IO) { container.millidaMod.rememberUpdate(profile) }
                } catch (error: IOException) {
                    Log.w("millida", "мод Millida: попытка обновления не запомнилась: ${error.message}")
                }
                try {
                    installMillidaMod(profile)
                } catch (error: IOException) {
                    throw IOException("Мод Millida не обновился (${error.message}) — игра запустится с прежней версией", error)
                }
            }
            loadCosmetics(profile)
            playJob = null
            play(profile, serverAddress, memoryConfirmed = true, modAnswered = true)
        }
        playJob = updating
        updating.start()
    }

    @Throws(IOException::class)
    private suspend fun installMillidaMod(profile: Profile): CosmeticsModState {
        val dir = container.profiles.directory(profile)
        val needsApi = MillidaModOffer.needsFabricApi(profile.loader.id) &&
            !MillidaModOffer.hasFabricApi(container.content.list(dir, ContentKind.MOD))
        if (needsApi) {
            val item = CatalogItem(projectId = MillidaModOffer.FABRIC_API_PROJECT, source = CatalogSource.MODRINTH)
            val api = MillidaModOffer.pickFabricApi(container.catalog.versions(item, profile.gameVersion, profile.loader))
                ?: throw IOException("моду нужен Fabric API, а под ${profile.gameVersion} его в каталоге нет")
            container.catalog.install(api.copy(projectId = MillidaModOffer.FABRIC_API_PROJECT), ContentKind.MOD, dir)
        }
        return container.millidaMod.install(profile)
    }

    fun repairCosmetics(profile: Profile) = viewModelScope.launch {
        runTask("Чиним скин в игре") {
            val result = container.millidaMod.repair(profile)
            state.update { it.copy(cosmetics = result) }
            loadCosmetics(profile)
            notice("Готово, запусти сборку и проверь")
        }
    }

    fun removeCosmetics(profile: Profile) = viewModelScope.launch {
        runTask("Убираем косметику") {
            val result = container.millidaMod.remove(profile)
            state.update { it.copy(cosmetics = result) }
            loadCosmetics(profile)
        }
    }

    fun setCosmeticsEnabled(profile: Profile, enabled: Boolean) = viewModelScope.launch {
        runTask(if (enabled) "Включаем косметику" else "Выключаем косметику") {
            val result = container.millidaMod.setEnabled(profile, enabled)
            state.update { it.copy(cosmetics = result) }
            loadCosmetics(profile)
        }
    }

    // --- Свои серверы сборки ---

    fun addBuildServer(profile: Profile, name: String, address: String) = viewModelScope.launch {
        runTask("Добавляем сервер") {
            val list = container.serverList.add(container.profiles.directory(profile), name, address)
            state.update { it.copy(buildServers = list) }
        }
    }

    fun removeBuildServer(profile: Profile, address: String) = viewModelScope.launch {
        runTask("Убираем сервер") {
            val list = container.serverList.remove(container.profiles.directory(profile), address)
            state.update { it.copy(buildServers = list) }
        }
    }

    fun pinBuildServer(profile: Profile, address: String) = viewModelScope.launch {
        runTask("Закрепляем сервер") {
            val list = container.serverList.pin(container.profiles.directory(profile), address)
            state.update { it.copy(buildServers = list) }
        }
    }

    /**
     * The cache decides what actually goes out: a held answer, a request
     * already in flight for that address, or one more socket out of the few
     * allowed at a time.
     */
    fun pingServer(address: String, refresh: Boolean = false) = viewModelScope.launch {
        if (!refresh && state.value.serverStatuses.containsKey(address)) return@launch
        runTask("Опрашиваем $address", silent = true) {
            val status = container.serverStatuses.status(address, refresh)
            state.update { it.withPings(listOf(address), status?.let { answer -> mapOf(address to answer) }.orEmpty()) }
        }
    }

    /** The whole list in one go, so scrolling does not ask again for every row. */
    fun pingServers(addresses: List<String>, refresh: Boolean = false) = viewModelScope.launch {
        val wanted = addresses.filter { refresh || !state.value.serverStatuses.containsKey(it) }
        if (wanted.isEmpty()) return@launch
        runTask("Опрашиваем серверы", silent = true) {
            val answers = container.serverStatuses.statuses(wanted, refresh)
            state.update { it.withPings(wanted, answers) }
        }
    }

    // --- Миры ---

    fun loadWorldBackups(profile: Profile, folder: String) = viewModelScope.launch {
        runTask("Читаем копии мира", silent = true) {
            val list = container.worlds.backups(container.profiles.directory(profile), folder)
            state.update { current ->
                current.copy(worldBackups = current.worldBackups.filterNot { it.folder == folder } + list)
            }
        }
    }

    fun backupWorld(profile: Profile, folder: String) = viewModelScope.launch {
        runTask("Делаем копию мира") {
            container.worlds.backup(container.profiles.directory(profile), folder)
            loadWorldBackups(profile, folder)
            notice("Копия мира готова")
        }
    }

    fun restoreWorld(profile: Profile, backupFile: String) = viewModelScope.launch {
        runTask("Возвращаем мир из копии") {
            container.worlds.restoreBackup(container.profiles.directory(profile), backupFile)
            refreshBuild()
            notice("Мир восстановлен")
        }
    }

    fun deleteWorldBackup(profile: Profile, backupFile: String, folder: String) = viewModelScope.launch {
        runTask("Удаляем копию") {
            container.worlds.deleteBackup(container.profiles.directory(profile), backupFile)
            loadWorldBackups(profile, folder)
        }
    }

    fun duplicateWorld(profile: Profile, folder: String) = viewModelScope.launch {
        runTask("Копируем мир") {
            container.worlds.duplicate(container.profiles.directory(profile), folder)
            refreshBuild()
        }
    }

    fun renameWorld(profile: Profile, folder: String, name: String) = viewModelScope.launch {
        runTask("Переименовываем мир") {
            container.worlds.rename(container.profiles.directory(profile), folder, name)
            refreshBuild()
        }
    }

    /**
     * The archive is written beside the target and moved into place only when
     * it is whole, and the old file goes first: what reaches [onSuccess] is
     * always this export, never a leftover or a half-written zip.
     */
    fun exportWorldFile(profile: Profile, folder: String, target: File, onSuccess: (File) -> Unit): Job =
        viewModelScope.launch {
            val done = runTask("Сохраняем мир файлом") {
                val destination = target.absoluteFile
                val partial = File(destination.parentFile, destination.name + ".part")
                dropStale(destination)
                try {
                    partial.outputStream().use { stream ->
                        container.worlds.exportWorld(container.profiles.directory(profile), folder, stream)
                    }
                    if (!partial.renameTo(destination)) throw IOException("файл мира не сохранился — проверь свободное место")
                } catch (failure: Throwable) {
                    partial.delete()
                    throw failure
                }
                notice("Мир сохранён")
            }
            if (done) onSuccess(target.absoluteFile)
        }

    fun importWorld(
        profile: Profile,
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
    ) = viewModelScope.launch {
        runTask("Ставим мир из файла") {
            val name = lobby.displayName(resolver, uri)
                ?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() }
                ?: "Мир из файла"
            val source = resolver.openInputStream(uri) ?: throw IOException("файл не открылся — выбери zip с миром")
            source.use { container.worlds.importWorld(container.profiles.directory(profile), it, name) }
            refreshBuild()
            notice("Мир добавлен")
        }
    }

    // --- Снимки ---

    /** The list is capped for the gallery, so the honest total is counted separately. */
    fun loadShots(profileId: String = "") = viewModelScope.launch {
        loadArea(Area.SHOTS) { ticket ->
            val shots = container.screenshots.list(profileId)
            val total = container.screenshots.count(profileId)
            ticket.commit { it.copy(shots = shots, shotsScope = profileId, shotsTotal = total) }
            if (profileId.isBlank()) state.update { it.copy(shotsAll = total) }
        }
    }

    // The gallery list is re-scoped by the build screen, so the hub keeps its own all-builds total.
    fun loadShotsCount() = viewModelScope.launch {
        val total = try {
            withContext(Dispatchers.IO) { container.screenshots.count() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            return@launch
        }
        state.update { it.copy(shotsAll = total) }
    }

    /** Only the deleted frame leaves the list, so the all-builds gallery keeps the other builds' shots. */
    fun deleteShot(profileId: String, name: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        val done = runTask("Удаляем снимок") {
            container.screenshots.delete(profileId, name)
            state.update { current ->
                val left = current.shots.filterNot { it.profileId == profileId && it.name == name }
                val removed = current.shots.size - left.size
                current.copy(shots = left, shotsTotal = (current.shotsTotal - removed).coerceAtLeast(0))
            }
        }
        if (done) loadShotsCount()
        if (done) onDone()
    }

    fun shotFile(profileId: String, name: String): java.io.File = container.screenshots.file(profileId, name)

    // --- Сборка: перенос, копия, группа, наиграно ---

    fun loadBuildMeta(profile: Profile) = viewModelScope.launch {
        runTask("Читаем сведения о сборке", silent = true) {
            state.update {
                it.copy(
                    buildMeta = container.buildMeta.of(profile.id),
                    playStats = container.playtime.stats(),
                )
            }
        }
    }

    fun setBuildGroup(profile: Profile, group: String) = viewModelScope.launch {
        runTask("Меняем группу") {
            val meta = container.buildMeta.setGroup(profile.id, group)
            state.update { it.copy(buildMeta = meta) }
        }
    }

    fun duplicateBuild(profile: Profile, withContent: Boolean) = viewModelScope.launch {
        runTask("Копируем сборку") {
            container.buildOps.duplicate(profile, withContent)
            reloadProfiles()
            notice("Копия сборки готова")
        }
    }

    fun planMigration(profile: Profile, gameVersion: String, loader: LoaderKind) = viewModelScope.launch {
        state.update { it.copy(migratePlan = null) }
        runTask("Считаем перенос сборки") {
            val plan = container.buildOps.migratePlan(profile, gameVersion, loader)
            state.update { it.copy(migratePlan = plan) }
        }
    }

    fun migrateBuild(profile: Profile, gameVersion: String, loader: LoaderKind) = viewModelScope.launch {
        runTask("Переносим сборку") {
            container.buildOps.migrate(profile, gameVersion, loader)
            state.update { it.copy(migratePlan = null) }
            reloadProfiles()
            notice("Сборка перенесена в копию, исходная осталась на месте")
        }
    }

    fun repairBuild(profile: Profile) = viewModelScope.launch {
        runTask("Чиним сборку") {
            container.buildOps.repair(profile, container.installer) { stage -> publish(stage) }
            notice("Сборка проверена")
        }
    }

    // --- Контент: обновления, зависимости, безопасность ---

    fun planContentUpdates(profile: Profile) = viewModelScope.launch {
        runTask("Ищем обновления модов") {
            val plan = container.contentUpdates.plan(profile, container.profiles.directory(profile))
            state.update { it.copy(contentUpdatePlan = plan) }
        }
    }

    fun applyContentUpdates(profile: Profile) = viewModelScope.launch {
        val plan = state.value.contentUpdatePlan ?: return@launch
        runTask("Обновляем моды") {
            val outcomes = container.contentUpdates.applyAll(container.profiles.directory(profile), plan)
            val failed = outcomes.filterNot { it.ok }
            state.update {
                it.copy(contentUpdatePlan = if (failed.isEmpty()) null else plan.copy(candidates = failed.map { outcome -> outcome.candidate }))
            }
            refreshBuild()
            if (failed.isEmpty()) {
                notice("Моды обновлены")
            } else {
                notice(
                    "Обновилось ${outcomes.size - failed.size} из ${outcomes.size}. Не вышло: " +
                        "${failed.joinToString(", ") { it.candidate.title }} — нажми «Обновить всё» ещё раз",
                    isError = true,
                )
            }
        }
    }

    fun scanSafety(profile: Profile) = viewModelScope.launch {
        runTask("Проверяем моды") {
            val report = container.contentSafety.scan(profile, container.profiles.directory(profile))
            state.update { it.copy(safetyReport = report) }
        }
    }

    fun quarantineMods(profile: Profile) = viewModelScope.launch {
        val report = state.value.safetyReport ?: return@launch
        runTask("Отключаем подозрительное") {
            container.contentSafety.quarantine(container.profiles.directory(profile), report.toQuarantine())
            scanSafety(profile)
            refreshBuild()
        }
    }

    // --- Обновление лаунчера ---

    /**
     * The notes window belongs to the version that is already installed, so it
     * is answered from the package itself and not from the update channel.
     */
    fun openWhatsNewIfDue() = viewModelScope.launch {
        runTask("Читаем, что нового", silent = true) {
            val current = BuildConfig.VERSION_CODE.toLong()
            val seen = prefs.whatsNewShownFor.toLongOrNull() ?: 0
            val manifest = runCatching { container.updates.manifest() }.getOrNull() ?: return@runTask
            // The channel carries the notes of the version it publishes; they
            // are ours to show only once that version is the one installed.
            val published = manifest.buildFor()?.versionCode ?: 0
            val notes = if (published == current) ReleaseNotesParser.parse(manifest.notes) else ReleaseNotes()
            val decision = WhatsNewGate.evaluate(current, seen, notes)
            prefs.whatsNewShownFor = decision.seenToStore.toString()
            if (decision.show) state.update { it.copy(releaseNotes = notes) }
        }
    }

    fun closeWhatsNew() = state.update { it.copy(releaseNotes = null) }

    /** The start-up check stays quiet; a check the player asked for says when the channel did not answer. */
    fun checkUpdate(manual: Boolean = false) = viewModelScope.launch {
        try {
            val verdict = withContext(Dispatchers.IO) {
                container.updates.check(
                    currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    deviceSdk = android.os.Build.VERSION.SDK_INT,
                )
            }
            val offer = !manual &&
                verdict is UpdateVerdict.Available &&
                prefs.updateOfferSkipped != verdict.release.versionCode
            state.update { it.copy(updateVerdict = verdict, updateOffer = it.updateOffer || offer) }
            if (!manual && verdict is UpdateVerdict.Available) backgroundUpdate(verdict.release)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("millida", "проверка обновления не удалась: ${error.message}")
            if (manual) notice(UpdateFaults.describe(error), isError = true)
        }
    }

    /** Package that is downloaded and verified, waiting for a moment to install. */
    private var readyUpdate: UpdateRelease? = null
    private var backgroundJob: kotlinx.coroutines.Job? = null

    private fun unmeteredNetwork(): Boolean {
        val manager = getApplication<android.app.Application>()
            .getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return !manager.isActiveNetworkMetered
    }

    /** Quiet download right after the start-up check; no tap, no task banner. */
    private fun backgroundUpdate(release: UpdateRelease) {
        if (readyUpdate?.versionCode == release.versionCode || backgroundJob?.isActive == true) return
        val allowed = UpdatePolicy.shouldDownloadInBackground(
            sizeBytes = release.size,
            unmetered = unmeteredNetwork(),
            postponed = prefs.updateOfferSkipped == release.versionCode,
        )
        if (!allowed) return
        backgroundJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { container.updates.download(release) }
                readyUpdate = release
                installReadyUpdate(manual = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("millida", "фоновая загрузка обновления не удалась: ${error.message}")
            }
        }
    }

    /** Installs the downloaded package unless the game is running; asked again on return from the game. */
    private fun installReadyUpdate(manual: Boolean) {
        val release = readyUpdate ?: return
        val app = getApplication<android.app.Application>()
        val moment = UpdatePolicy.installMoment(
            packageReady = container.updates.verify(release),
            gameAlive = GameProcess.alive(app),
        )
        if (moment != InstallMoment.Install) return
        if (!SelfInstaller.canInstall(app)) {
            // never throw the player into settings uninvited
            if (manual) {
                SelfInstaller.openInstallSettings(app)
                notice("Разреши установку из лаунчера и нажми снова", isError = true)
            }
            return
        }
        readyUpdate = null
        if (SelfInstaller.install(app, container.updates.apkFile(release))) {
            state.update { it.copy(updateOffer = false) }
        } else {
            notice("Система не открыла установку — повтори позже", isError = true)
        }
    }

    fun downloadUpdate() = viewModelScope.launch {
        val verdict = state.value.updateVerdict
        val release = (verdict as? UpdateVerdict.Available)?.release ?: return@launch
        runTask("Качаем обновление") {
            backgroundJob?.join()
            if (readyUpdate?.versionCode != release.versionCode) {
                container.updates.download(release) { transfer ->
                    val total = transfer.totalBytes.takeIf { it > 0 } ?: release.size
                    val share = if (total > 0) transfer.doneBytes.toFloat() / total else 0f
                    state.update { it.copy(updateProgress = share.coerceIn(0f, 1f)) }
                }
                state.update { it.copy(updateProgress = 0f) }
                readyUpdate = release
            }
            if (GameProcess.alive(getApplication())) {
                notice("Обновление скачано — поставится, когда выйдешь из игры")
                state.update { it.copy(updateOffer = false) }
            } else {
                installReadyUpdate(manual = true)
            }
        }
    }

    fun postponeUpdate() {
        val release = (state.value.updateVerdict as? UpdateVerdict.Available)?.release
        if (release != null) prefs.updateOfferSkipped = release.versionCode
        state.update { it.copy(updateOffer = false) }
    }

    // --- Облако сборок ---

    fun cloudStatus() = viewModelScope.launch {
        runTask("Смотрим облако") {
            state.update { it.copy(cloudStatus = container.cloud.status()) }
        }
    }

    fun cloudPush() = viewModelScope.launch {
        runTask("Выгружаем сборки") {
            val status = container.cloud.push()
            state.update { it.copy(cloudStatus = status) }
            notice("Сборки выгружены в облако")
        }
    }

    fun cloudPull(only: List<String>? = null) = viewModelScope.launch {
        runTask("Забираем сборки") {
            val report = container.cloud.pull(only)
            state.update { it.copy(cloudReport = report) }
            reloadProfiles()
            when {
                report.failed.isNotEmpty() -> notice(
                    "Не забралось ${report.failed.size}: ${report.failed.first()} — повтори",
                    isError = true,
                )
                report.installed.isNotEmpty() || report.updated.isNotEmpty() ->
                    notice("Добавлено: ${report.installed.size}, обновлено: ${report.updated.size}")
                only != null -> notice("Этой сборки уже нет в облаке — нажми «Проверить»", isError = true)
                report.skipped.isNotEmpty() -> notice("Новых нет — ${report.skipped.size} уже на телефоне")
                else -> notice("В облаке пусто")
            }
        }
    }

    // --- Модпаки ---

    fun loadMyPacks() = viewModelScope.launch {
        if (!millidaReady()) return@launch
        loadArea(Area.PACKS) { ticket ->
            val packs = container.packs.myPacks()
            ticket.commit { it.copy(myPacks = packs) }
        }
    }

    fun previewPack(code: String) = viewModelScope.launch {
        runTask("Ищем пак по коду") {
            state.update { it.copy(packPreview = container.packs.packPreview(code)) }
        }
    }

    fun installPackByCode(code: String) = viewModelScope.launch {
        runTask("Ставим пак") {
            container.packs.installByCode(code) { step -> publishPack(step) }
            container.telemetry.track(TelemetryType.BUILD_IMPORT, EventData().put("source", "code").build())
            state.update { it.copy(packPreview = null) }
            reloadProfiles()
            notice("Пак установлен")
        }
    }

    fun installPackFile(source: java.io.File) = viewModelScope.launch {
        runTask("Ставим модпак из файла") {
            container.packs.installFile(source) { step -> publishPack(step) }
            container.telemetry.track(TelemetryType.BUILD_IMPORT, EventData().put("source", "file").build())
            reloadProfiles()
            notice("Модпак установлен")
        }
    }

    fun sharePack(profile: Profile, summary: String = "", onDone: ((String) -> Unit)? = null) = viewModelScope.launch {
        var code = ""
        val done = runTask("Готовим код сборки") {
            code = container.packs.share(profile, summary).code
            loadMyPacks()
        }
        if (!done) return@launch
        if (onDone != null) onDone(code) else notice("Код сборки: $code")
    }

    /** A code handed out by mistake is taken back: the build no longer installs by it. */
    fun unsharePack(code: String) = viewModelScope.launch {
        val done = runTask("Удаляем код сборки") { container.packs.unshare(code) }
        if (!done) return@launch
        state.update { current -> current.copy(myPacks = current.myPacks.filterNot { it.code == code }) }
        notice("Код $code удалён — по нему сборка больше не ставится")
    }

    /** The old file is removed first, so a failed export can never pass for a finished one. */
    fun exportPack(profile: Profile, target: java.io.File, onSuccess: (File) -> Unit = {}): Job =
        viewModelScope.launch {
            var exported: File? = null
            val done = runTask("Сохраняем сборку файлом") {
                dropStale(target)
                exported = container.packs.exportMrpack(profile, target)
                notice("Файл сборки готов: ${target.name}")
            }
            val file = exported
            if (done && file != null) onSuccess(file)
        }

    // --- Хостинг ---

    val hostingTools = HostingToolsModel(
        container.hosting,
        viewModelScope,
        getApplication<Application>(),
        { text, isError -> notice(text, isError) },
        { event -> container.uiSoundPlayer.play(event) },
    )

    val hosting: HostingModel = HostingModel(
        client = container.hosting,
        wallet = container.wallet,
        scope = viewModelScope,
        notice = { text, isError -> notice(text, isError) },
        onServerChanged = { id ->
            if (state.value.hostingServerId == id) loadHostingDetails()
            refreshHosted()
        },
        onListChanged = { refreshHosted() },
    )

    fun openHostedServer(serverId: String) {
        state.update {
            it.copy(
                hostingServerId = serverId,
                hostingDetails = null,
                hostingStats = null,
                hostingEvents = emptyList(),
            )
        }
        hosting.select(serverId)
        open(Screen.HOSTING_MANAGE)
        loadHostingDetails()
    }

    fun loadHostingDetails(full: Boolean = true) = viewModelScope.launch {
        val id = state.value.hostingServerId
        if (id.isEmpty()) return@launch
        if (!millidaReady()) return@launch
        loadArea(Area.HOSTED_SERVER) { ticket ->
            val details = container.hosting.details(id)
            val stats = if (full) attempt { container.hosting.stats(id) }.getOrNull() else null
            val events = if (full) attempt { container.hosting.events(id) }.getOrNull() else null
            ticket.commit {
                if (it.hostingServerId != id) {
                    it
                } else {
                    it.copy(
                        hostingDetails = details,
                        hostingStats = stats ?: it.hostingStats,
                        hostingEvents = events ?: it.hostingEvents,
                    )
                }
            }
        }
    }

    // --- Друзья и переписка ---

    fun loadFriends() = viewModelScope.launch {
        markPending(Area.FRIENDS)
        if (!millidaReady()) {
            state.update { it.copy(loading = it.loading - Area.FRIENDS) }
            return@launch
        }
        friendsSeq.incrementAndGet()
        loadArea(Area.FRIENDS) { ticket ->
            val list = attempt { container.friends.friends() }
            val requests = attempt { container.friends.requests() }
            val dialogs = list.getOrNull()?.let { people -> attempt { container.friends.dialogs(people) } }
                ?: attempt { container.friends.dialogs() }
            ticket.commit { current ->
                val threads = dialogs.getOrDefault(current.dialogs)
                current.copy(
                    friends = list.getOrDefault(current.friends),
                    friendRequests = requests.getOrDefault(current.friendRequests),
                    dialogs = threads,
                    unreadMessages = container.friends.unreadTotal(threads),
                )
            }
            listOf(list, requests, dialogs).firstFailure()?.let { throw it }
        }
    }

    /**
     * The badge numbers only. A result that started before a full friends load
     * is dropped: it is older than what that load already put on screen.
     * False when the sign-in was refused, so the timer stops asking until the
     * next return to the app.
     */
    private suspend fun refreshCounters(): Boolean {
        val started = friendsSeq.incrementAndGet()
        val (requests, dialogs) = withContext(Dispatchers.IO) {
            attempt { container.friends.requests() } to attempt { container.friends.dialogs() }
        }
        state.update { current ->
            if (friendsSeq.get() != started) {
                current
            } else {
                val threads = dialogs.getOrDefault(current.dialogs)
                current.copy(
                    friendRequests = requests.getOrDefault(current.friendRequests),
                    dialogs = threads,
                    unreadMessages = container.friends.unreadTotal(threads),
                )
            }
        }
        val failure = listOf(requests, dialogs).firstFailure() ?: return true
        Log.w("millida", "счётчики друзей не обновились: ${failure.message}")
        if (!failure.isUnauthorized()) return true
        millidaReady()
        return false
    }

    fun addFriend(nickname: String) = social.addFriend(nickname)

    fun acceptFriend(requestId: String) = social.accept(requestId)

    fun declineFriend(requestId: String) = social.decline(requestId)

    fun removeFriend(userId: String) = social.remove(userId)

    fun openChat(target: ChatTarget) {
        state.update { it.copy(chatTarget = target) }
        open(Screen.CHAT)
        social.openThread(target)
    }

    fun openFriendProfile(userId: String) {
        openChat(ChatTarget.Dm(userId))
        social.showProfile(userId)
    }

    // --- Кабинет: кошелёк, достижения, новости ---

    fun loadCabinet() = viewModelScope.launch {
        markPending(Area.CABINET)
        if (!millidaReady()) {
            state.update { it.copy(loading = it.loading - Area.CABINET) }
            return@launch
        }
        loadArea(Area.CABINET) { ticket ->
            val balance = attempt { container.wallet.balance() }
            val history = attempt { container.wallet.history().items }
            val achievements = attempt { container.achievements.mine() }
            val news = attempt { container.news.feed().items }
            ticket.commit {
                it.copy(
                    wallet = balance.getOrDefault(it.wallet),
                    walletHistory = history.getOrDefault(it.walletHistory),
                    achievements = achievements.getOrDefault(it.achievements),
                    newsFeed = news.getOrDefault(it.newsFeed),
                )
            }
            listOf(balance, history, achievements, news).firstFailure()?.let { throw it }
        }
    }

    fun topUpUrl(): String = container.wallet.topUpUrl()

    // --- Библиотека текстур ---

    fun loadTextures() = viewModelScope.launch {
        runTask("Читаем библиотеку скинов", silent = true) {
            state.update { it.copy(textureLibrary = container.textures.list(TextureKind.SKIN)) }
        }
    }

    fun saveTexture(name: String, png: ByteArray, slim: Boolean, onDone: () -> Unit = {}) = viewModelScope.launch {
        val done = runTask("Сохраняем скин") {
            container.textures.save(TextureKind.SKIN, name, png, slim)
            loadTextures()
            notice("Скин в библиотеке")
        }
        if (done) onDone()
    }

    fun deleteTexture(file: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        val done = runTask("Удаляем скин") {
            container.textures.delete(TextureKind.SKIN, file)
            loadTextures()
        }
        if (done) onDone()
    }

    /** The catalogue hands out an address, not a file: it is fetched and checked like any other. */
    fun importSkinByUrl(url: String, name: String = "", onDone: () -> Unit = {}) = viewModelScope.launch {
        val done = runTask("Забираем скин по ссылке") {
            val imported = container.skinImport.byUrl(url)
            saveImportedSkin(imported, name.ifEmpty { imported.name })
            notice("Скин в библиотеке")
        }
        if (done) onDone()
    }

    fun importSkinByNick(nick: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        val done = runTask("Ищем скин игрока") {
            val imported = container.skinImport.byNick(nick)
            saveImportedSkin(imported, imported.name)
            notice("Скин ${imported.name} в библиотеке")
        }
        if (done) onDone()
    }

    /** The arm model is read off the picture, so an imported slim skin is not worn with wide arms later. */
    private fun saveImportedSkin(imported: ImportedTexture, name: String) {
        container.textures.save(imported.kind, name, imported.bytes, slimArms(imported.bytes) ?: false)
        loadTextures()
    }

    private fun reloadProfiles() {
        state.update { it.copy(profiles = container.profiles.load()) }
    }

    fun setThemeMode(mode: String) {
        prefs.themeMode = mode
        state.update { it.copy(themeMode = mode) }
    }

    fun setAccentId(id: String) {
        prefs.accentId = id
        state.update { it.copy(accentId = id) }
    }

    fun setCustomAccent(hex: String) {
        if (!isCustomAccent(hex)) return
        val color = hex.lowercase()
        prefs.accentCustom = color
        prefs.accentId = color
        state.update { it.copy(accentId = color, accentCustom = color) }
    }

    fun setSoundMode(mode: String) {
        prefs.soundMode = mode
        val stored = prefs.soundMode
        state.update { it.copy(soundMode = stored) }
        if (stored != Prefs.SOUND_OFF) viewModelScope.launch { container.uiSoundPlayer.prepare() }
    }

    /** Writing on every slider pixel would hammer the settings file; [saveSoundVolume] writes once the finger lifts. */
    fun setSoundVolume(volume: Int) {
        state.update { it.copy(soundVolume = volume.coerceIn(0, 100)) }
    }

    fun saveSoundVolume() {
        prefs.soundVolume = state.value.soundVolume
    }

    fun setHaptics(enabled: Boolean) {
        prefs.haptics = enabled
        state.update { it.copy(haptics = enabled) }
    }

    fun setBedrockMode(mode: String) {
        prefs.bedrockMode = mode
        state.update { it.copy(bedrockMode = mode) }
    }

    fun installBedrockBridge() = viewModelScope.launch {
        val bridge = BedrockBridge(container.layout.root)
        if (bridge.installed()) {
            notice("Наш переводчик уже установлен")
            return@launch
        }
        runTask("Ставим переводчик Bedrock") {
            state.update { it.copy(bedrockBridgeProgress = 0.001f) }
            try {
                bridge.install(container.downloader) { done ->
                    state.update {
                        it.copy(bedrockBridgeProgress = (done.toFloat() / VIAPROXY_SIZE).coerceIn(0.001f, 1f))
                    }
                }
            } finally {
                state.update { it.copy(bedrockBridgeProgress = 0f) }
            }
            refreshBedrock()
            notice("Переводчик готов: Bedrock-серверы открываются нашим клиентом")
        }
    }

    fun removeBedrockBridge() = viewModelScope.launch {
        runTask("Убираем переводчик") {
            val bridge = BedrockBridge(container.layout.root)
            BedrockProxyService.stop(getApplication())
            bridge.jar.delete()
            refreshBedrock()
        }
    }

    /**
     * Our way in: the translator is started first and the game is pointed at
     * the phone itself. Waiting for the port is not politeness - a client that
     * knocks before the translator listens shows "connection refused", and the
     * player blames the launcher.
     */
    fun playBedrockThroughBridge(address: String, name: String = "Millida") {
        val parsed = parseBedrockAddress(address)
        if (parsed == null) {
            notice("Не разобрал адрес Bedrock: $address", isError = true)
            return
        }
        if (!BedrockBridge(container.layout.root).installed()) {
            open(Screen.BEDROCK)
            notice("Сначала скачай наш переводчик Bedrock", isError = true)
            return
        }
        val profile = state.value.profiles.firstOrNull { it.id == state.value.activeProfileId }
            ?: state.value.profiles.firstOrNull()
        if (profile == null) {
            open(Screen.HOME)
            notice("Сначала создай сборку: в неё и зайдёт игра", isError = true)
            return
        }
        viewModelScope.launch {
            var ready = false
            runTask("Поднимаем перевод протокола") {
                // A translator already running points at another server, and
                // nothing can re-aim a started virtual machine.
                BedrockProxyService.stop(getApplication())
                delay(600)
                BedrockProxyService.start(getApplication(), parsed.toString())
                ready = awaitProxy()
            }
            if (!ready) {
                BedrockProxyService.stop(getApplication())
                notice("Переводчик не поднялся, журнал лежит в папке bedrock", isError = true)
                return@launch
            }
            notice("$name через наш клиент: ${parsed.host}")
            play(profile, BedrockProxyService.localAddress())
        }
    }

    private suspend fun awaitProxy(): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + PROXY_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val open = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(BEDROCK_PROXY_HOST, BEDROCK_PROXY_PORT), 500)
                    true
                }
            }.getOrDefault(false)
            if (open) return@withContext true
            delay(400)
        }
        false
    }

    fun setNicknameDraft(value: String) = state.update { it.copy(nicknameDraft = value) }

    fun consumeLink() = state.update { it.copy(linkUrl = "") }

    /** What the site knows about the account: read once and shown as it is. */
    fun loadAccountDetails() = viewModelScope.launch {
        if (!millidaReady()) return@launch
        loadArea(Area.ACCOUNT) { ticket ->
            val details = container.portal.details()
            val sessions = attempt { container.portal.sessions() }
            val privacy = attempt { container.portal.privacy() }
            ticket.commit {
                it.copy(
                    details = details,
                    sessions = sessions.getOrDefault(it.sessions),
                    privacy = privacy.getOrDefault(it.privacy),
                    nicknameDraft = it.nicknameDraft.ifEmpty { details.nickname },
                )
            }
            listOf(sessions, privacy).firstFailure()?.let { throw it }
        }
    }

    fun changeNickname() = viewModelScope.launch {
        val value = state.value.nicknameDraft.trim()
        if (value.length < 3) {
            notice("Ник короче трёх знаков", isError = true)
            return@launch
        }
        if (value == state.value.details?.nickname) return@launch
        runTask("Меняем ник") {
            val free = runCatching { container.portal.nicknameFree(value) }.getOrDefault(true)
            if (!free) throw IllegalStateException("ник «$value» занят")
            val details = container.portal.changeNickname(value)
            state.update { it.copy(details = details, nicknameDraft = details.nickname) }
            notice("Ник теперь $value")
        }
    }

    /**
     * Binding an account elsewhere happens in a browser: the launcher asks the
     * site for the address and hands the player over.
     */
    fun linkDiscord() = viewModelScope.launch {
        runTask("Готовим привязку Discord") {
            val url = container.portal.discordLinkUrl()
            if (url.isEmpty()) throw IllegalStateException("сайт не выдал ссылку привязки")
            state.update { it.copy(linkUrl = url) }
        }
    }

    fun linkTelegram() = viewModelScope.launch {
        runTask("Готовим привязку Telegram") {
            val url = container.portal.telegramLinkUrl()
            if (url.isEmpty()) throw IllegalStateException("сайт не выдал ссылку привязки")
            state.update { it.copy(linkUrl = url) }
        }
    }

    fun unlinkDiscord() = viewModelScope.launch {
        runTask("Отвязываем Discord") {
            container.portal.unlinkDiscord()
            loadAccountDetails()
        }
    }

    fun unlinkTelegram() = viewModelScope.launch {
        runTask("Отвязываем Telegram") {
            container.portal.unlinkTelegram()
            loadAccountDetails()
        }
    }

    fun revokeOtherSessions() = viewModelScope.launch {
        runTask("Закрываем другие входы") {
            container.portal.revokeOtherSessions()
            loadAccountDetails()
            notice("Другие входы закрыты")
        }
    }

    /**
     * The skin, the wardrobe, the capes and the rewards come from four places at
     * once. A single one of them failing must not leave the screen empty, so each
     * is read on its own and what arrived is shown.
     */
    fun loadSkins() = viewModelScope.launch {
        if (!millidaReady()) return@launch
        loadArea(Area.SKINS) { ticket ->
            val profile = attempt { container.wardrobe.profile() }
            val items = attempt { container.wardrobe.wardrobe() }
            val capes = attempt { container.wardrobe.capes() }
            val rewards = attempt { container.wardrobe.rewards() }
            ticket.commit {
                it.copy(
                    gameProfile = profile.getOrDefault(it.gameProfile),
                    wardrobe = items.getOrDefault(it.wardrobe),
                    capes = capes.getOrDefault(it.capes),
                    rewards = rewards.getOrDefault(it.rewards),
                )
            }
            listOf(profile, items, capes, rewards).firstFailure()?.let { throw it }
        }
    }

    fun uploadSkin(bytes: ByteArray, slim: Boolean, name: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        val done = runTask("Ставим скин") { applyMillidaSkin(bytes, slim, name) }
        if (done) onDone()
    }

    /** A catalogue or link skin goes through the checked import first, then on like an uploaded file. */
    fun wearSkinUrl(url: String, name: String, slim: Boolean?, onDone: () -> Unit = {}) = viewModelScope.launch {
        val done = runTask("Ставим скин") {
            val imported = container.skinImport.byUrl(url)
            val arms = slim ?: slimArms(imported.bytes) ?: false
            applyMillidaSkin(imported.bytes, arms, name.ifEmpty { imported.name })
        }
        if (done) onDone()
    }

    private suspend fun applyMillidaSkin(bytes: ByteArray, slim: Boolean, name: String) {
        if (bytes.size > SKIN_LIMIT) {
            throw IllegalStateException("Файл больше 512 КБ — возьми обычный скин 64×64")
        }
        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        container.wardrobe.applyTexture("skin", encoded, slim, name)
        notice("Скин применён")
        loadSkins()
    }

    /** The licence's skin and capes: read and changed at Mojang with the token a launch would use. */
    val licence: LicenceModel by lazy {
        LicenceModel(
            services = container.minecraftServices,
            skinImport = container.skinImport,
            scope = viewModelScope,
            token = { licenceToken() },
            notice = { text, isError -> notice(text, isError) },
        )
    }

    val mojangCapes: MojangCapes by lazy { MojangCapes(container.wardrobe, viewModelScope) }

    /** The try-on bill: the shop takes the rubies, and only what it confirmed becomes owned and goes on. */
    fun buyTryOn(items: List<net.millida.launcher.core.cosmetics.CosmeticItem>) {
        shop.buyFromWardrobe(items.map { it.art() }) { codes -> cosmeticWardrobe.wearBought(codes) }
    }

    /** The mod's items, emotes, saved looks and the site's skin showcase, shared by the character screen and the lobby. */
    val cosmeticWardrobe: net.millida.launcher.ui.character.CosmeticsModel by lazy {
        net.millida.launcher.ui.character.CosmeticsModel(
            client = net.millida.launcher.core.cosmetics.CosmeticsClient(container.millidaAuth),
            showcaseClient = net.millida.launcher.core.skins.SkinShowcase(container.millidaAuth),
            outfitStore = net.millida.launcher.core.cosmetics.OutfitStore(java.io.File(getApplication<Application>().filesDir, "outfits")),
            scope = viewModelScope,
            notice = { text, isError -> notice(text, isError) },
        )
    }

    private suspend fun licenceToken(): String {
        val account = state.value.account
        if (account == null || account.kind != AccountKind.MICROSOFT) {
            throw IllegalStateException("Выбери аккаунт Microsoft, чтобы менять скин лицензии")
        }
        return microsoftSession(account, navigate = false).accessToken
    }

    /** The model is part of the texture for the server, so the same skin is sent again. */
    fun setSkinModel(slim: Boolean) = viewModelScope.launch {
        val current = state.value.gameProfile
        if (current == null || current.skinUrl.isEmpty()) {
            notice("Сначала поставь скин", isError = true)
            return@launch
        }
        if (current.slim == slim) return@launch
        runTask(if (slim) "Ставим тонкие руки" else "Ставим классическую модель") {
            val bytes = container.downloader.bytes(current.skinUrl)
            val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            container.wardrobe.applyTexture("skin", encoded, slim, "millida-mobile")
            loadSkins()
        }
    }

    fun applyWardrobeItem(id: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        val done = runTask("Надеваем") {
            container.wardrobe.applyWardrobe(id)
            notice("Надето")
            loadSkins()
        }
        if (done) onDone()
    }

    fun removeWardrobeItem(id: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        val done = runTask("Убираем из гардероба") {
            container.wardrobe.removeWardrobe(id)
            loadSkins()
        }
        if (done) onDone()
    }

    fun applyCape(id: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        val done = runTask("Надеваем плащ") {
            container.wardrobe.applyCape(id)
            notice("Плащ надет")
            loadSkins()
        }
        if (done) onDone()
    }

    /** An empty texture is the server's way to take the cape off the Millida profile. */
    fun takeOffCape(onDone: () -> Unit = {}) = viewModelScope.launch {
        val done = runTask("Снимаем плащ") {
            container.wardrobe.applyTexture("cape", "", false, "")
            notice("Плащ снят")
            loadSkins()
        }
        if (done) onDone()
    }

    fun claimReward(code: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        val done = runTask("Забираем награду") {
            val reward = container.wardrobe.claim(code)
            state.update { current ->
                current.copy(rewards = current.rewards.map { if (it.code == reward.code) reward else it })
            }
            notice("Плащ «${reward.title.ifEmpty { code }}» в гардеробе", tone = ToastTone.Info)
            loadSkins()
        }
        if (done) onDone()
    }

    fun refreshHosted() = viewModelScope.launch {
        loadArea(Area.HOSTING) { ticket ->
            if (!millidaReady()) return@loadArea
            val hosted = container.hosting.servers()
            ticket.commit { it.copy(hosted = hosted) }
        }
    }

    fun play(
        profile: Profile,
        serverAddress: String = "",
        memoryConfirmed: Boolean = false,
        modAnswered: Boolean = false,
    ) {
        if (playJob?.isActive == true) {
            notice("Игра уже готовится", isError = true)
            return
        }
        val session = state.value.account
        if (session == null) {
            open(Screen.ACCOUNT)
            notice("Сначала войди в аккаунт или укажи ник для оффлайн-входа", isError = true)
            return
        }
        if (!memoryConfirmed) {
            val low = MemoryTuning.lowMemory(container.deviceMemory())
            if (low != null) {
                state.update { it.copy(lowMemoryPlay = PendingPlay(profile, serverAddress, low)) }
                return
            }
        }
        if (!modAnswered) {
            val asking = viewModelScope.launch(start = CoroutineStart.LAZY) {
                val declined = profile.id in prefs.millidaModDeclined
                val action = withTimeoutOrNull(MOD_OFFER_CHECK_MS) { millidaModAction(profile, declined) }
                playJob = null
                when (action) {
                    LaunchModAction.OFFER ->
                        state.update { it.copy(millidaModOffer = PendingModOffer(profile, serverAddress)) }
                    LaunchModAction.UPDATE -> updateMillidaModAndPlay(profile, serverAddress)
                    LaunchModAction.NONE, null ->
                        play(profile, serverAddress, memoryConfirmed = true, modAnswered = true)
                }
            }
            playJob = asking
            asking.start()
            return
        }

        playJob = viewModelScope.launch {
            runTask("Готовим игру") {
                var launchStage = "prepare"
                try {
                    state.update { it.copy(preparingGame = true) }
                    launchStage = "session"
                    val resolvedSession = gameSession()
                        ?: throw IllegalStateException("сессия не получена — войди в аккаунт заново")

                    launchStage = "pack"
                    val admission = admitPack(profile) ?: return@runTask
                    val build = if (withContext(Dispatchers.IO) { container.packs.ownLaunchLost(profile) }) {
                        launchStage = "repair"
                        state.update { it.copy(progressNote = "Сборка распакована не полностью — ставим её заново", progress = 0f) }
                        withContext(Dispatchers.IO) {
                            container.packs.update(profile, onProgress = { step -> publishPack(step) })
                        }.also { reloadProfiles() }
                    } else {
                        profile
                    }
                    val packLaunch = PackLaunches.trusted(container.profiles.directory(build))
                    if (packLaunch == null && PackLaunches.unusable(container.profiles.directory(build))) {
                        throw IllegalStateException(
                            "у «${build.name}» свой запуск, но лаунчер не смог его прочитать — переустанови сборку из каталога, а если не поможет, напиши в поддержку",
                        )
                    }
                    launchStage = "install"
                    val version = container.installer.install(build.versionId) { stage -> publish(stage) }
                    val javaMajor = packLaunch?.javaMajor ?: version.javaMajor
                    VideoSettings.applySafeDefaults(
                        gameDir = container.profiles.directory(build),
                        force = prefs.safeGraphics,
                    )
                    val blocked = PhoneBlockedMods.disableIn(File(container.profiles.directory(build), "mods"))
                    if (blocked.isNotEmpty()) notice("Отключены моды, которые на телефоне не работают. " + blocked.joinToString("; "))

                    launchStage = "memory"
                    val memory = container.deviceMemory()
                    val modCount = File(container.profiles.directory(build), "mods")
                        .listFiles { file -> file.extension == "jar" }?.size ?: 0
                    val plan = MemoryTuning.plan(memory, build.loader, modCount, packLaunch?.minRamMb ?: 0)
                    plan.warning?.let { notice(it, isError = true) }
                    if (!plan.canLaunch) {
                        throw IllegalStateException(
                            plan.warning ?: "не хватает свободной памяти для запуска",
                        )
                    }

                    launchStage = "runtime"
                    state.update { it.copy(progressNote = "Ставим виртуальную машину Java и рендерер", progress = 0f) }
                    val runtime = RuntimeImport.installed(container.layout, javaMajor)
                        ?: container.runtimeInstaller().install(
                        javaMajor = javaMajor,
                        abi = container.runner.abi,
                        rendererId = build.rendererId.ifEmpty { state.value.rendererId }.ifEmpty { null },
                    ) { transfer ->
                        state.update {
                            it.copy(
                                progress = if (transfer.totalBytes > 0) {
                                    transfer.doneBytes.toFloat() / transfer.totalBytes
                                } else {
                                    0f
                                },
                                progressNote = "Рантайм: ${transfer.current}",
                            )
                        }
                    }

                    val latest = state.value.profiles.firstOrNull { it.id == build.id } ?: build
                    val heap = when {
                        latest.memoryMb > 0 -> latest.memoryMb.coerceAtLeast(PROFILE_HEAP_FLOOR_MB)
                        state.value.memoryOverrideMb > 0 -> state.value.memoryOverrideMb
                        else -> plan.heapMb
                    }

                    // The game runs in its own process: the desktop virtual machine
                    // replaces signal handlers the Android runtime depends on, and in
                    // the launcher process that killed the system drawing threads.
                    // Everything the game needs is prepared here; the arguments are
                    // assembled on the other side, in that process.
                    launchStage = "start"
                    val size = screenSize()
                    val intent = GameActivity.intent(
                        context = getApplication(),
                        profileId = build.id,
                        session = resolvedSession,
                        heapMb = heap,
                        windowWidth = size.first,
                        windowHeight = size.second,
                        serverAddress = serverAddress,
                        packJvmArgs = admission.tokenArgs,
                    )
                    stampPlayed(build.id)
                    getApplication<Application>().startActivity(intent)
                    state.update { it.copy(progressNote = "") }
                    if (admission.watch) watchPackAccess(build, admission.slug)
                } catch (error: Exception) {
                    container.telemetry.gameLaunchFailed(launchFacts(profile), launchStage, error, null)
                    throw error
                }
            }
        }
        val started = playJob
        started?.invokeOnCompletion {
            if (playJob === started) state.update { it.copy(preparingGame = false) }
        }
    }

    /**
     * Our API's word on a catalogue build before it starts. Null when the start
     * is called off and a window explains why: the key window for a closed
     * pack, or the forfeit notice for a pack whose access is gone.
     */
    /** What the launcher knows about a start before the game process takes over. */
    private fun launchFacts(profile: Profile): LaunchFacts {
        val origin = runCatching { PackOriginStore.read(container.profiles.directory(profile)) }.getOrNull()
        val (pack, modpack) = LaunchFacts.origin(origin?.source, origin?.id)
        return LaunchFacts(
            build = TelemetryPrivacy.buildTag(profile.name, pack) ?: "default",
            mc = profile.gameVersion,
            loader = profile.loader.id,
            pack = pack,
            modpack = modpack,
            renderer = profile.rendererId.ifEmpty { state.value.rendererId }.ifEmpty { null },
        )
    }

    private suspend fun admitPack(profile: Profile): PackAdmission? = try {
        packGate.admit(container.profiles.directory(profile))
    } catch (lost: PackAccessLost) {
        notice(forfeitPack(profile, lost.slug, lost.message.orEmpty()), isError = true)
        null
    } catch (denied: PackAccessDenied) {
        val slug = PackOriginStore.read(container.profiles.directory(profile))?.id.orEmpty()
        withContext(Dispatchers.Main) {
            millidaShelf.openKey(slug, profile.name, denied.message.orEmpty())
        }
        null
    }

    /** Runs beside the game for as long as its process lives; the launcher process outlasts it. */
    private fun watchPackAccess(profile: Profile, slug: String) {
        packWatch?.cancel()
        val app = getApplication<Application>()
        packWatch = viewModelScope.launch {
            val reason = PackAccessWatch.watch(
                slug = slug,
                running = { GameProcess.alive(app) },
                check = { withContext(Dispatchers.IO) { container.packApi.access(it) } },
            ) ?: return@launch
            notice(forfeitPack(profile, slug, reason), isError = true)
        }
    }

    fun cancelPlay() {
        val job = playJob?.takeIf { it.isActive } ?: return
        job.cancel()
        viewModelScope.launch {
            job.join()
            notice("Запуск отменён", tone = ToastTone.Info)
        }
    }

    fun loginMillida() {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            runTask("Вход через Millida") {
                val init = container.millidaAuth.startLogin()
                state.update {
                    it.copy(loginPrompt = LoginPrompt(init.userCode, init.verifyUrl, AccountKind.MILLIDA))
                }
                val deadline = System.currentTimeMillis() + init.expiresInSec * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(3000)
                    if (container.millidaAuth.pollLogin(init.deviceCode)) {
                        state.update { it.copy(millidaSignedIn = true) }
                        val session = container.millidaAuth.gameSession()
                        saveAccount(AccountKind.MILLIDA, session)
                        state.update {
                            it.copy(
                                loginPrompt = null,
                                sessionExpired = it.sessionExpired.takeUnless { kind -> kind == AccountKind.MILLIDA },
                            )
                        }
                        notice("Вошли как ${session.nick}")
                        refreshPortal()
                        return@runTask
                    }
                }
                state.update { it.copy(loginPrompt = null) }
                notice("Код входа истёк — попробуй снова", isError = true)
            }
        }
    }

    fun loginMicrosoft() {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            runTask("Вход через Microsoft") {
                val prompt = container.microsoftAuth.startDeviceFlow()
                state.update {
                    it.copy(loginPrompt = LoginPrompt(prompt.userCode, prompt.verificationUri, AccountKind.MICROSOFT))
                }
                val deadline = System.currentTimeMillis() + prompt.expiresInSec * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(prompt.intervalSec.coerceAtLeast(3) * 1000L)
                    when (val poll = container.microsoftAuth.poll(prompt.deviceCode)) {
                        DevicePoll.Pending -> Unit
                        is DevicePoll.Ready -> {
                            val account = saveAccount(AccountKind.MICROSOFT, poll.session)
                            storeMicrosoftTokens(account, poll)
                            state.update {
                                it.copy(
                                    loginPrompt = null,
                                    sessionExpired = it.sessionExpired.takeUnless { kind -> kind == AccountKind.MICROSOFT },
                                )
                            }
                            notice("Вошли как ${poll.session.nick}")
                            return@runTask
                        }
                    }
                }
                state.update { it.copy(loginPrompt = null) }
                notice("Код Microsoft истёк — попробуй снова", isError = true)
            }
        }
    }

    fun useOffline() = viewModelScope.launch {
        val nick = state.value.offlineNick.trim()
        if (!OfflineAuth.validNick(nick)) {
            notice("Ник — от 3 до 16 знаков: латиница, цифры и подчёркивание", isError = true)
            return@launch
        }
        runTask("Сохраняем аккаунт") {
            saveAccount(AccountKind.OFFLINE, OfflineAuth.session(nick))
            notice("Играем как $nick, только на серверах Millida")
        }
    }

    fun selectAccount(account: Account) {
        prefs.activeAccountId = account.id
        state.update { it.copy(account = account) }
        if (account.kind == AccountKind.MILLIDA) viewModelScope.launch { refreshPortal() }
    }

    fun removeAccount(account: Account) = viewModelScope.launch(Dispatchers.IO) {
        guarded {
        val remaining = container.accounts.remove(account.id)
        container.vault.remove(msRefreshKey(account))
        container.vault.remove(msTokenKey(account))
        container.vault.remove(msExpiresKey(account))
        val millida = account.kind == AccountKind.MILLIDA
        if (millida) container.millidaAuth.signOut()
        val active = remaining.firstOrNull()
        prefs.activeAccountId = active?.id.orEmpty()
        state.update {
            val kept = if (millida) clearMillidaData(it).copy(millidaSignedIn = false) else it
            kept.copy(accounts = remaining, account = active)
        }
        notice("Аккаунт удалён")
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        loginJob = null
        state.update { it.copy(loginPrompt = null) }
    }

    /** Throws with a message for the player when the account has to sign in again. */
    private suspend fun gameSession(): GameSession? {
        val account = state.value.account ?: return null
        return when (account.kind) {
            AccountKind.OFFLINE -> OfflineAuth.session(account.nick)
            AccountKind.MILLIDA -> try {
                container.millidaAuth.gameSession()
            } catch (unauthorized: Unauthorized) {
                throw signInAgain(AccountKind.MILLIDA)
            }

            AccountKind.MICROSOFT -> microsoftSession(account)
        }
    }

    /**
     * The stored game token lives about a day. It is used while its time is not
     * up and Minecraft still accepts it; otherwise it is renewed first, so a
     * licensed server never meets a dead token.
     */
    private suspend fun microsoftSession(account: Account, navigate: Boolean = true): GameSession {
        val stored = container.vault.read(msTokenKey(account))
        var rejected = false
        if (!stored.isNullOrEmpty() && !microsoftTokenDue(account)) {
            if (container.microsoftAuth.tokenAccepted(stored) != false) {
                return GameSession(account.nick, account.uuid, stored, account.xuid)
            }
            rejected = true
        }
        return refreshMicrosoft(account, spent = stored, rejected = rejected, navigate = navigate)
    }

    /**
     * Serialised and re-read under the lock: a refresh token spent twice is
     * treated by Microsoft as reuse, and the second launch would be refused.
     */
    private suspend fun refreshMicrosoft(
        account: Account,
        spent: String?,
        rejected: Boolean,
        navigate: Boolean = true,
    ): GameSession =
        microsoftRefreshLock.withLock {
            val current = container.vault.read(msTokenKey(account))
            if (!current.isNullOrEmpty() && current != spent && !microsoftTokenDue(account)) {
                return@withLock GameSession(account.nick, account.uuid, current, account.xuid)
            }
            val refreshToken = container.vault.read(msRefreshKey(account))
            if (refreshToken.isNullOrEmpty()) throw signInAgain(AccountKind.MICROSOFT, navigate)
            val ready = try {
                container.microsoftAuth.refresh(refreshToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: MicrosoftSignInRequired) {
                throw signInAgain(AccountKind.MICROSOFT, navigate)
            } catch (error: IOException) {
                if (error.isOffline()) {
                    if (current.isNullOrEmpty()) throw IllegalStateException(OFFLINE_TEXT)
                    // Without a network only a single-player world opens, and that
                    // needs no fresh token.
                    notice("Нет сети — вход Microsoft не обновился, по сети играть не выйдет", isError = true)
                    return@withLock GameSession(account.nick, account.uuid, current, account.xuid)
                }
                val alive = current?.takeIf { it.isNotEmpty() && !rejected && microsoftTokenAlive(account) }
                    ?: throw IllegalStateException(
                        error.message?.trim()?.takeIf { it.isNotEmpty() } ?: "Microsoft не ответил — повтори запуск через минуту",
                    )
                notice("Вход Microsoft не обновился — играем со старым, он ещё действует", tone = ToastTone.Info)
                return@withLock GameSession(account.nick, account.uuid, alive, account.xuid)
            }
            storeMicrosoftTokens(account, ready)
            ready.session
        }

    private fun storeMicrosoftTokens(account: Account, ready: DevicePoll.Ready) {
        container.vault.write(msTokenKey(account), ready.session.accessToken)
        container.vault.write(msRefreshKey(account), ready.refreshToken)
        container.vault.write(
            msExpiresKey(account),
            (System.currentTimeMillis() + ready.expiresInSec * 1000L).toString(),
        )
    }

    private fun microsoftTokenDue(account: Account): Boolean {
        val expiresAt = container.vault.read(msExpiresKey(account))?.toLongOrNull() ?: return false
        return System.currentTimeMillis() >= expiresAt - MS_REFRESH_MARGIN_MS
    }

    private fun microsoftTokenAlive(account: Account): Boolean {
        val expiresAt = container.vault.read(msExpiresKey(account))?.toLongOrNull() ?: return false
        return System.currentTimeMillis() < expiresAt
    }

    private suspend fun signInAgain(kind: AccountKind, navigate: Boolean = true): IllegalStateException {
        if (kind == AccountKind.MILLIDA) millidaReady()
        state.update { it.copy(sessionExpired = kind) }
        if (navigate) withContext(Dispatchers.Main) { open(Screen.ACCOUNT) }
        val service = if (kind == AccountKind.MICROSOFT) "Microsoft" else "Millida"
        return IllegalStateException("Вход $service истёк — войди заново")
    }

    private fun saveAccount(kind: AccountKind, session: GameSession): Account {
        val account = Account(
            id = "${kind.name.lowercase()}:${session.nick.lowercase()}",
            kind = kind,
            nick = session.nick,
            uuid = session.uuid,
            xuid = session.xuid,
            addedAt = System.currentTimeMillis(),
        )
        val accounts = container.accounts.upsert(account)
        prefs.activeAccountId = account.id
        state.update { it.copy(accounts = accounts, account = account) }
        return account
    }

    private fun msRefreshKey(account: Account) = "${account.id}:ms-refresh"

    private fun msTokenKey(account: Account) = "${account.id}:ms-token"

    private fun msExpiresKey(account: Account) = "${account.id}:ms-expires"

    private fun screenSize(): Pair<Int, Int> {
        val context = getApplication<Application>()
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        val width = maxOf(metrics.widthPixels, metrics.heightPixels)
        val height = minOf(metrics.widthPixels, metrics.heightPixels)
        return (if (width > 0) width else 1280) to (if (height > 0) height else 720)
    }

    /** A pack reports its own steps; they reach the player the same way. */
    private fun publishPack(step: PackProgress) {
        state.update {
            it.copy(
                progressNote = listOf(step.stage, step.file).filter { part -> part.isNotEmpty() }.joinToString(": "),
                progress = if (step.total > 0) step.done.toFloat() / step.total else 0f,
            )
        }
    }

    private fun publishLoader(loader: LoaderKind, step: ForgeInstallStep) {
        val (note, fraction) = when (step) {
            ForgeInstallStep.Installer -> "${loader.label}: скачиваем установщик" to 0f
            is ForgeInstallStep.Libraries ->
                "${loader.label}: библиотеки ${step.done} из ${step.total}" to fractionOf(step.done, step.total)
            ForgeInstallStep.Client -> "${loader.label}: клиент игры" to 0f
            is ForgeInstallStep.Processors ->
                "${loader.label}: собираем клиент, шаг ${minOf(step.done + 1, step.total)} из ${step.total}" to
                    fractionOf(step.done, step.total)
        }
        state.update { it.copy(progressNote = note, progress = fraction) }
    }

    private fun fractionOf(done: Int, total: Int): Float =
        if (total > 0) done.toFloat() / total else 0f

    private fun publish(stage: InstallStage) {
        when (stage) {
            is InstallStage.Meta -> state.update {
                it.copy(progressNote = "Читаем описание версии ${stage.versionId}", progress = 0f)
            }

            is InstallStage.Client -> state.update {
                it.copy(
                    progressNote = "Клиент игры",
                    progress = ratio(stage.progress.doneBytes, stage.progress.totalBytes),
                )
            }

            is InstallStage.Libraries -> state.update {
                it.copy(
                    progressNote = "Библиотеки: ${stage.progress.doneFiles} из ${stage.progress.totalFiles}",
                    progress = ratio(stage.progress.doneFiles.toLong(), stage.progress.totalFiles.toLong()),
                )
            }

            is InstallStage.Assets -> state.update {
                it.copy(
                    progressNote = "Ресурсы: ${stage.progress.doneFiles} из ${stage.progress.totalFiles}",
                    progress = ratio(stage.progress.doneFiles.toLong(), stage.progress.totalFiles.toLong()),
                )
            }

            InstallStage.Done -> state.update { it.copy(progressNote = "Файлы игры готовы", progress = 1f) }
        }
    }

    private fun ratio(done: Long, total: Long): Float =
        if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    /** Nothing on a background thread may throw into the void: an uncaught
     * failure in a coroutine kills the process. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            notice(error.message ?: "не получилось", isError = true)
        }
    }

    internal fun notice(
        text: String,
        isError: Boolean = false,
        tone: ToastTone = if (isError) ToastTone.Error else ToastTone.Success,
    ) {
        messageSeq += 1
        val message = Message(messageSeq, text, isError, tone)
        state.update { it.copy(messages = it.messages + message) }
    }

    /**
     * Catches Throwable, not Exception: UnsatisfiedLinkError and OutOfMemoryError
     * are exactly what the launch path throws, and an uncaught one takes the
     * whole process down instead of showing a message. The body runs off the main
     * thread, so file and network work in it can never freeze the interface.
     */
    private fun directorySize(dir: java.io.File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * A quiet task is background work the player did not ask for: checking the
     * update channel, reading the account, pinging servers. It must not raise
     * the busy flag - controls gated on it used to go grey on the first launch
     * and stay grey while the runtime unpacked, and the launcher looked broken.
     */
    private suspend fun runTask(label: String, silent: Boolean = false, block: suspend () -> Unit): Boolean {
        if (silent) {
            return try {
                withContext(Dispatchers.IO) { block() }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w("millida", "фоновая задача «$label» не удалась: ${error.message}")
                false
            }
        }
        state.update { it.copy(tasks = it.tasks + label) }
        return try {
            withContext(Dispatchers.IO) { block() }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            notice(error.message ?: "не получилось: $label", isError = true)
            false
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                state.update { current ->
                    val remaining = current.tasks.toMutableList()
                    remaining.remove(label)
                    current.copy(
                        tasks = remaining,
                        progress = if (remaining.isEmpty()) 0f else current.progress,
                        progressNote = if (remaining.isEmpty()) "" else current.progressNote,
                    )
                }
            }
        }
    }

    /**
     * A read the player did not start as a task: it never raises the global
     * busy flag, only its own area's, and its failure stays in that area as a
     * message with the next step. Only the newest load of an area may write.
     */
    private suspend fun loadArea(area: Area, block: suspend (Ticket) -> Unit) {
        val ticket = issueTicket(area)
        state.update { it.copy(loading = it.loading + area) }
        try {
            withContext(Dispatchers.IO) { block(ticket) }
            ticket.commit { it.copy(errors = it.errors - area, loadedAreas = it.loadedAreas + area) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w("millida", "загрузка ${area.name} не удалась: ${error.message}")
            if (error.isUnauthorized()) millidaReady()
            ticket.commit { it.copy(errors = it.errors + (area to loadErrorText(error)), loadedAreas = it.loadedAreas + area) }
        } finally {
            ticket.commit { it.copy(loading = it.loading - area) }
        }
    }

    private inner class Ticket(private val area: Area, private val id: Long) {
        val current: Boolean get() = areaTickets[area] == id

        fun commit(change: (UiState) -> UiState) = state.update { if (current) change(it) else it }
    }

    private fun issueTicket(area: Area): Ticket {
        val id = ticketSeq.incrementAndGet()
        areaTickets[area] = id
        return Ticket(area, id)
    }

    /** Retires the load in flight and shows the wait before the next one starts. */
    private fun markPending(area: Area) {
        issueTicket(area)
        state.update { it.copy(loading = it.loading + area) }
    }

    /** Reads the sign-in from the vault and keeps [UiState.millidaSignedIn] in step with it. */
    private suspend fun millidaReady(): Boolean {
        val signedIn = withContext(Dispatchers.IO) { container.millidaAuth.signedIn() }
        state.update {
            when {
                it.millidaSignedIn == signedIn -> it
                signedIn -> it.copy(millidaSignedIn = true)
                else -> clearMillidaData(it).copy(millidaSignedIn = false)
            }
        }
        return signedIn
    }

    /** Everything read under one Millida account: none of it may reach the next one. */
    private fun clearMillidaData(ui: UiState): UiState = ui.copy(
        friends = emptyList(),
        friendRequests = null,
        dialogs = emptyList(),
        unreadMessages = 0,
        details = null,
        sessions = emptyList(),
        privacy = null,
        portal = null,
        wallet = null,
        walletHistory = emptyList(),
        achievements = null,
        newsFeed = emptyList(),
        nicknameDraft = "",
        errors = ui.errors - MILLIDA_AREAS,
        loadedAreas = ui.loadedAreas - MILLIDA_AREAS,
    )

    private fun dropStale(target: File) {
        if (target.exists() && !target.delete()) {
            throw IOException("старый файл ${target.name} не удалился — освободи место и повтори")
        }
    }

    private companion object {
        /** What the server accepts as a texture: half a megabyte of PNG. */
        const val SKIN_LIMIT = 512 * 1024

        /** The translator loads every protocol it knows before it binds. */
        const val PROXY_WAIT_MS = 60_000L

        const val HISTORY_LIMIT = 8
        const val MEMORY_SAVE_DEBOUNCE_MS = 400L
        const val PROFILE_HEAP_FLOOR_MB = 512
        const val LOST_GAME_LOG_LINES = 200
        const val LOST_GAME_REPORT_LINES = 120
        /** A slow catalogue must not hold the start: past this the game goes on without the question. */
        const val MOD_OFFER_CHECK_MS = 3_000L
        const val COUNTERS_PERIOD_MS = 30_000L
        /** The desktop beats every 45 s while its window is on screen (src/App.tsx). */
        const val GUEST_BEAT_MS = 45_000L

        /** Renewed an hour early, so a token taken now outlives a long evening of play. */
        const val MS_REFRESH_MARGIN_MS = 60 * 60 * 1000L
    }
}

private const val OFFLINE_TEXT = "Нет сети — проверь подключение и нажми «Повторить»"

private val MILLIDA_AREAS = setOf(Area.FRIENDS, Area.CABINET, Area.ACCOUNT)

private inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

private fun List<Result<*>>.firstFailure(): Throwable? = firstNotNullOfOrNull { it.exceptionOrNull() }

private fun Throwable.causes(): Sequence<Throwable> =
    generateSequence(this) { current -> current.cause?.takeIf { it !== current } }.take(8)

private fun Throwable.isUnauthorized(): Boolean = causes().any { it is Unauthorized }

private fun Throwable.isOffline(): Boolean =
    causes().any { it is UnknownHostException || it is SocketException || it is SocketTimeoutException }

internal fun loadErrorText(error: Throwable): String = when {
    error.isUnauthorized() -> "Вход Millida истёк — войди заново"
    error.isOffline() -> OFFLINE_TEXT
    else -> error.message?.trim()?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
        ?: "Не загрузилось — нажми «Повторить»"
}

/** Site sections a phone installs into a build, and the catalogue tab each one opens. */
private val LINK_TABS = mapOf(
    "mods" to CatalogTab.MODS,
    "texture-packs" to CatalogTab.RESOURCE_PACKS,
    "shaders" to CatalogTab.SHADERS,
    "data-packs" to CatalogTab.DATA_PACKS,
    "maps" to CatalogTab.MAPS,
)
