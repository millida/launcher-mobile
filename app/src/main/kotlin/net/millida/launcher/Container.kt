package net.millida.launcher

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.millida.launcher.core.DeviceMemory
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.account.AccountStore
import net.millida.launcher.core.account.MicrosoftAuth
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.SecretVault
import net.millida.launcher.core.catalog.Catalog
import net.millida.launcher.core.catalog.ModpackLoader
import net.millida.launcher.core.cabinet.CabinetAchievementsClient
import net.millida.launcher.core.cabinet.CabinetNewsClient
import net.millida.launcher.core.cabinet.CabinetWalletClient
import net.millida.launcher.core.content.CatalogLookup
import net.millida.launcher.core.content.ContentDependencies
import net.millida.launcher.core.content.ContentSafety
import net.millida.launcher.core.content.ContentUpdates
import net.millida.launcher.core.content.LocalContentImport
import net.millida.launcher.core.cosmetics.MillidaMod
import net.millida.launcher.core.friends.FriendsClient
import net.millida.launcher.core.hosting.HostingClient
import net.millida.launcher.core.maintenance.Dedupe
import net.millida.launcher.core.media.Screenshots
import net.millida.launcher.core.media.UiSounds
import net.millida.launcher.core.packs.MillidaPacks
import net.millida.launcher.core.packs.PackInstaller
import net.millida.launcher.core.packs.Packs
import net.millida.launcher.core.packs.PhoneFit
import net.millida.launcher.core.profile.BuildMetaStore
import net.millida.launcher.core.profile.BuildOps
import net.millida.launcher.core.profile.PlaytimeStore
import net.millida.launcher.core.servers.ServerList
import net.millida.launcher.core.servers.ServerStatusCache
import net.millida.launcher.core.skins.MinecraftServicesClient
import net.millida.launcher.core.skins.SkinImport
import net.millida.launcher.core.skins.TextureLibrary
import net.millida.launcher.core.sync.CloudSync
import net.millida.launcher.core.sync.LocalBuilds
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.packs.PackOriginStore
import net.millida.launcher.core.sync.PackFile
import net.millida.launcher.core.sync.PackManifest
import net.millida.launcher.core.sync.NoPrefs
import net.millida.launcher.core.update.UpdateClient
import net.millida.launcher.core.skins.WardrobeClient
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.install.GameInstaller
import net.millida.launcher.core.launch.LaunchSpecBuilder
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.loader.ForgeFamilyInstaller
import net.millida.launcher.core.loader.ForgeVersions
import net.millida.launcher.core.loader.LoaderInstaller
import net.millida.launcher.core.loader.LoaderMirror
import net.millida.launcher.core.loader.MetaLoaderInstaller
import net.millida.launcher.core.loader.NeoForgeVersions
import net.millida.launcher.core.mojang.VersionResolver
import net.millida.launcher.core.portal.PortalClient
import net.millida.launcher.core.game.Worlds
import net.millida.launcher.core.logs.GameLogs
import net.millida.launcher.core.profile.ProfileContent
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileStore
import net.millida.launcher.core.rating.RatingClient
import net.millida.launcher.core.runtime.HostAbi
import net.millida.launcher.core.runtime.RuntimeImport
import net.millida.launcher.core.runtime.RuntimeInstaller
import net.millida.launcher.runtime.GameRunner
import net.millida.launcher.ui.UiSoundPlayer
import java.io.File

class EncryptedVault(context: Context) : SecretVault {
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "millida-secrets",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

const val RUNTIME_ASSET = "millida-runtime.zip"

/** The install steps of every Forge since 1.13 run on Java 17 and newer. */
private const val PROCESSOR_JAVA = 17

class Container(private val context: Context) {
    val layout: GameLayout = GameLayout(File(context.filesDir, "game"))
    val downloader = Downloader()
    val resolver = VersionResolver(layout, downloader)
    val installer = GameInstaller(layout, downloader, resolver)
    val profiles = ProfileStore(layout) { error ->
        android.util.Log.w("millida", "копия списка сборок не записалась: ${error.message}")
    }
    val accounts = AccountStore(layout)
    val content = ProfileContent(layout)
    val worlds = Worlds(layout)
    val gameLogs = GameLogs(layout)
    val catalog = Catalog(downloader)
    val rating = RatingClient(downloader)
    val launchSpecBuilder = LaunchSpecBuilder(layout)
    val runner = GameRunner(context, layout)
    val vault: SecretVault = EncryptedVault(context)
    val millidaAuth = MillidaAuth(Downloader.shared(), vault)

    /**
     * The launcher process's telemetry. The game process builds its own with a
     * session it cannot renew, and never probes the GPU itself.
     */
    val telemetry: AppTelemetry by lazy { AppTelemetry(context, millidaAuth, profiles, probeGpu = true) }
    val portal = PortalClient(millidaAuth)
    val wardrobe = WardrobeClient(millidaAuth)
    val microsoftAuth = MicrosoftAuth(Downloader.shared())
    val millidaMod = MillidaMod(layout, profiles, downloader)
    val updates = UpdateClient(layout, downloader)
    /**
     * A build can carry the runtime pack in its own assets. Then there is one
     * file to hand a player instead of two, and the first launch unpacks it
     * itself.
     */
    val bundledRuntime: Boolean by lazy {
        runCatching { context.assets.list("")?.contains(RUNTIME_ASSET) == true }.getOrDefault(false)
    }

    fun openBundledRuntime(): java.io.InputStream? =
        runCatching { context.assets.open(RUNTIME_ASSET) }.getOrNull()

    val serverList = ServerList(layout)

    /** One cache for the whole launcher: a list of servers is not a socket storm. */
    val serverStatuses = ServerStatusCache()
    val screenshots = Screenshots(layout)
    val uiSounds = UiSounds(layout, downloader, resolver)

    /** Lazy: the game and translator processes build a container too and never play interface sounds. */
    val uiSoundPlayer: UiSoundPlayer by lazy { UiSoundPlayer(Prefs(context), uiSounds) }

    val buildMeta = BuildMetaStore(layout)
    val playtime = PlaytimeStore(layout)
    val buildOps = BuildOps(layout, profiles, catalog)
    val catalogLookup = CatalogLookup(downloader)
    val contentUpdates = ContentUpdates(layout, catalog, catalogLookup)
    val contentSafety = ContentSafety(layout)
    val contentDependencies = ContentDependencies(layout, catalog, catalogLookup)
    val localContent = LocalContentImport(layout, catalogLookup)
    val dedupe = Dedupe(listOf(layout.profiles))
    val packApi = MillidaPacks(downloader, millidaAuth)
    val packs = Packs(
        layout,
        profiles,
        downloader,
        packApi,
        PackInstaller(layout, profiles, downloader, ModpackLoader { kind -> loaderInstaller(kind) }),
    )
    val hosting = HostingClient(millidaAuth, vault)
    val friends = FriendsClient(millidaAuth)
    val wallet = CabinetWalletClient(millidaAuth)
    val news = CabinetNewsClient(millidaAuth)
    val achievements = CabinetAchievementsClient(millidaAuth)
    val textures = TextureLibrary(File(layout.root, "textures"))
    val skinImport = SkinImport()
    val minecraftServices = MinecraftServicesClient()

    /**
     * The cloud keeps the shape of a build, not its files: the phone and the
     * computer both rebuild the content from the catalogue.
     */
    val cloud = CloudSync(millidaAuth, ProfileBuilds(profiles, buildMeta), NoPrefs, deviceName = Build.MODEL ?: "Телефон")

    val fabric = MetaLoaderInstaller.fabric(layout, downloader)
    val quilt = MetaLoaderInstaller.quilt(layout, downloader)
    private val loaderMirror = LoaderMirror(downloader)
    private val processorRunner = AndroidProcessorRunner(
        context = context,
        layout = layout,
        runtime = {
            RuntimeImport.installed(layout, PROCESSOR_JAVA)
                ?: runtimeInstaller().install(PROCESSOR_JAVA, runner.abi, null)
        },
        heapMb = { ForgeProcessorService.heapFor(deviceMemory().availableMb) },
    )
    val forge = ForgeFamilyInstaller(
        layout, downloader, ForgeVersions(loaderMirror), loaderMirror, processorRunner, installer,
    )
    val neoforge = ForgeFamilyInstaller(
        layout, downloader, NeoForgeVersions(loaderMirror), loaderMirror, processorRunner, installer,
    )

    /** One place decides who installs a loader, so the launch cannot pick another. */
    fun loaderInstaller(kind: LoaderKind): LoaderInstaller? = when (kind) {
        LoaderKind.FABRIC -> fabric
        LoaderKind.QUILT -> quilt
        LoaderKind.FORGE -> forge
        LoaderKind.NEOFORGE -> neoforge
        LoaderKind.VANILLA -> null
    }

    @Volatile
    private var runtime: RuntimeInstaller? = null

    private val totalMemoryMb by lazy { deviceMemory().totalMb }

    init {
        layout.ensure()
        PhoneFit.wire(
            legacyRuntime = { true },
            deviceTotalMb = { totalMemoryMb },
            sdlRuntime = { runtimeInstaller().sdlAvailable() },
        )
    }

    fun runtimeInstaller(): RuntimeInstaller = runtime ?: synchronized(this) {
        runtime ?: RuntimeInstaller(layout, downloader).also { runtime = it }
    }

    fun deviceMemory(): DeviceMemory {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return DeviceMemory(
            totalMb = (info.totalMem / (1024 * 1024)).toInt(),
            availableMb = (info.availMem / (1024 * 1024)).toInt(),
        )
    }

    fun deviceReport(): String = buildString {
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" · Android ").append(Build.VERSION.RELEASE)
        append(" · ").append(HostAbi.describe(Build.SUPPORTED_ABIS.toList()))
        append(" · ").append(deviceMemory().totalMb).append(" МБ")
    }

    companion object {
        @Volatile
        private var instance: Container? = null

        fun get(context: Context): Container = instance ?: synchronized(this) {
            instance ?: Container(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * What the cloud is allowed to know about a build. The file list stays empty
 * until the launcher keeps a record of what it installed from the catalogue:
 * a snapshot that names files it cannot restore would be worse than an honest
 * short one.
 */
private class ProfileBuilds(
    private val profiles: ProfileStore,
    private val meta: BuildMetaStore,
) : LocalBuilds {

    override fun names(): List<String> = profiles.load().map { it.name }

    override fun describe(name: String): PackManifest? {
        val profile = profiles.load().firstOrNull { it.name == name } ?: return null
        val directory = profiles.directory(profile)
        // Only what the launcher can put back: a file without an address would
        // reach the other device as a name and nothing else.
        val files = PackOriginStore.files(directory)
            .filter { it.url.isNotEmpty() && SafePath.join(directory, it.path).isFile }
            .map { file ->
                PackFile(
                    path = file.path,
                    kind = file.path.substringBefore('/'),
                    projectId = file.projectId,
                    versionId = file.versionId,
                    sha1 = file.sha1,
                    size = file.size,
                    url = file.url,
                )
            }
        return PackManifest(
            name = profile.name,
            game = profile.gameVersion,
            loader = profile.loader.id,
            loaderVersion = profile.loaderVersion,
            files = files,
        )
    }

    override fun group(name: String): String {
        val profile = profiles.load().firstOrNull { it.name == name } ?: return ""
        return meta.of(profile.id).group
    }

    override suspend fun install(name: String, manifest: PackManifest, group: String) {
        val existing = profiles.load().firstOrNull { it.name == name }
        if (existing != null) return
        val created = Profile(
            id = profiles.newId(name),
            name = name,
            gameVersion = manifest.game,
            loader = loaderOf(manifest.loader),
            loaderVersion = manifest.loaderVersion,
        )
        profiles.upsert(created)
        if (group.isNotEmpty()) meta.setGroup(created.id, group)
    }

    private fun loaderOf(id: String): LoaderKind =
        LoaderKind.entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: LoaderKind.VANILLA
}
