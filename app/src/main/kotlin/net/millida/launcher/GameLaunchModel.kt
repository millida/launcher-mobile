package net.millida.launcher

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.millida.launcher.core.MemoryTuning
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.SecretVault
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.friends.BeatStatus
import net.millida.launcher.core.friends.FriendsClient
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.TransferProgress
import net.millida.launcher.core.loader.ForgeLaunch
import net.millida.launcher.core.loader.ForgeSplash
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.loader.forgeGameUnsupported
import net.millida.launcher.core.launch.LaunchRequest
import net.millida.launcher.core.launch.LaunchSpec
import net.millida.launcher.core.packs.PackLaunchBuild
import net.millida.launcher.core.packs.PackLaunches
import net.millida.launcher.core.packs.PhoneFit
import net.millida.launcher.core.runtime.AuthlibAgent
import net.millida.launcher.core.runtime.HostAbi
import net.millida.launcher.core.runtime.InstalledRuntime
import net.millida.launcher.core.runtime.InstalledSdl
import net.millida.launcher.core.runtime.JavaRequirement
import net.millida.launcher.core.runtime.LibraryCompatibility
import net.millida.launcher.core.runtime.PhoneJvmArgs
import net.millida.launcher.core.runtime.WindowChoice
import net.millida.launcher.core.runtime.WindowChoices
import net.millida.launcher.core.hud.Hotbar
import net.millida.launcher.core.hud.HudLayout
import net.millida.launcher.core.hud.HudProfiles
import net.millida.launcher.core.hud.HudStore
import net.millida.launcher.core.runtime.RendererCatalog
import net.millida.launcher.core.runtime.RendererChoice
import net.millida.launcher.core.runtime.RendererFallback
import net.millida.launcher.core.runtime.RendererKind
import net.millida.launcher.core.runtime.RendererVerdict
import net.millida.launcher.core.runtime.RuntimeImport
import net.millida.launcher.core.runtime.SodiumFamily
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.support.ReportRedaction
import net.millida.launcher.core.packs.PackOriginStore
import net.millida.launcher.core.telemetry.BeatState
import net.millida.launcher.core.telemetry.LaunchFacts
import net.millida.launcher.core.telemetry.TelemetryPrivacy
import net.millida.launcher.runtime.GameInputSink
import net.millida.launcher.runtime.GameSession
import net.millida.launcher.runtime.OwnInputSink
import net.millida.launcher.runtime.RuntimeBridge
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

const val LAUNCH_STAGES = 5

data class GameUiState(
    val note: String = "Готовим игру",
    val running: Boolean = false,
    val finished: Boolean = false,
    val closing: Boolean = false,
    val closed: Boolean = false,
    val error: String? = null,
    val lookSensitivity: Float = 1.0f,
    val packLayer: Boolean = true,
    /** The pack's window goes through SDL3 (beta); set once the start has chosen it. */
    val sdlWindow: Boolean = false,
    val buildName: String = "",
    val stage: Int = 0,
    val booting: Boolean = false,
    val started: Boolean = false,
    val cause: String? = null,
    val notice: String? = null,
    val guiScale: Int = 0,
    val forceUnicode: Boolean = false,
    val haptics: Boolean = true,
)

class GameLaunchModel(application: Application) : AndroidViewModel(application) {
    private val container = Container.get(application)
    private val prefs = Prefs(application)
    private val state = MutableStateFlow(
        GameUiState(
            lookSensitivity = prefs.lookSensitivity,
            packLayer = prefs.windowLayer == Prefs.LAYER_PACK,
            haptics = prefs.haptics,
        )
    )
    val ui: StateFlow<GameUiState> = state.asStateFlow()

    private val grabState = MutableStateFlow(false)
    val grabbed: StateFlow<Boolean> = grabState.asStateFlow()

    private val hudStore = HudStore(container.layout)
    private val hudState = MutableStateFlow(hudStore.load())
    val hudProfiles: StateFlow<HudProfiles> = hudState.asStateFlow()
    private var hudSave: Job? = null

    @Volatile
    private var pendingHud: HudProfiles? = null

    fun updateHud(layout: HudLayout) = updateHudProfiles(hudState.value.withLayout(layout))

    fun updateHudProfiles(profiles: HudProfiles) {
        hudState.value = profiles
        pendingHud = profiles
        hudSave?.cancel()
        hudSave = viewModelScope.launch {
            delay(HUD_SAVE_DELAY_MS)
            val saved = withContext(Dispatchers.IO) { runCatching { hudStore.save(profiles) } }
            saved
                .onSuccess { if (pendingHud === profiles) pendingHud = null }
                .onFailure { error ->
                    GameSession.note(sessionLog, "раскладка не сохранилась: ${error.message}")
                    showNotice("Раскладка не сохранилась — освободи место на телефоне и поправь её ещё раз")
                }
        }
    }

    /**
     * One object for the whole screen: the view and the touch layer keep it
     * from their first frame, while the window library is chosen only after
     * the start has read the pack.
     */
    val inputSink: GameInputSink = object : GameInputSink {
        private fun target(): GameInputSink = when {
            state.value.sdlWindow -> SdlInputSink
            state.value.packLayer -> PackInputSink
            else -> OwnInputSink
        }

        override fun key(code: Int, scancode: Int, pressed: Boolean) = target().key(code, scancode, pressed)
        override fun repeat(code: Int, scancode: Int) = target().repeat(code, scancode)
        override fun char(codePoint: Int) = target().char(codePoint)
        override fun mouseButton(button: Int, pressed: Boolean) = target().mouseButton(button, pressed)
        override fun cursorPosition(x: Float, y: Float) = target().cursorPosition(x, y)
        override fun cursorDelta(x: Float, y: Float) = target().cursorDelta(x, y)
        override fun scroll(horizontal: Double, vertical: Double) = target().scroll(horizontal, vertical)
        override fun screenSize(width: Int, height: Int) = target().screenSize(width, height)
        override fun focus(focused: Boolean) = target().focus(focused)
    }

    private val sessionLog: File = GameSession.sessionLog(container.layout.runtimes)
    private val openedAt = System.currentTimeMillis()
    private val surfaces = MutableStateFlow<SurfaceInfo?>(null)
    private val surfaceLock = Any()
    private var surfaceBound = false
    private val exited = CompletableDeferred<Unit>()
    private var pack: PackRuntime? = null
    private var started = false
    private var forced = false
    private var sizeSync: Job? = null
    private var noticeJob: Job? = null

    @Volatile
    private var secrets: List<Pair<String?, String>> = emptyList()

    @Volatile
    private var gameDir: File? = null

    // The game process must not renew the Millida session: the launcher keeps its own copy of
    // the secrets and would overwrite the renewal, leaving itself with a spent refresh token.
    private val gameAuth = MillidaAuth(Downloader.shared(), ReadOnlySession(container.vault))
    private val presence = FriendsClient(gameAuth)
    private val telemetry = AppTelemetry(application, gameAuth, container.profiles, probeGpu = false)

    private var launchBegan = 0L

    @Volatile
    private var launchStage = "prepare"

    @Volatile
    private var facts: LaunchFacts? = null

    @Volatile
    private var rendererInUse: RendererInUse? = null

    @Volatile
    private var nick = ""

    @Volatile
    private var sessionLoop: Job? = null
    private val playLock = Any()
    private var playProfileId = ""
    private var playFrom = 0L
    private var playCounted = false

    @Volatile
    private var presenceFailing = false

    private data class SurfaceInfo(val surface: Surface, val width: Int, val height: Int)

    private data class Presence(val build: String, val server: String, val nick: String)

    fun start(request: LaunchIntent) {
        if (started) return
        started = true
        nick = request.session.nick
        secrets = listOf(
            request.session.accessToken to "<token>",
            request.session.uuid to "<uuid>",
            request.session.xuid to "<xuid>",
        )

        launchBegan = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { launch(request) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                telemetry.gameLaunchFailed(facts, launchStage, error, SystemClock.elapsedRealtime() - launchBegan)
                GameSession.note(sessionLog, "отказ: ${error.message ?: error.toString()}")
                state.update {
                    it.copy(
                        error = error.message ?: error.toString(),
                        finished = true,
                        running = false,
                        booting = false,
                    )
                }
            }
        }
    }

    fun surfaceReady(surface: Surface, width: Int, height: Int) {
        val info = SurfaceInfo(surface, width, height)
        synchronized(surfaceLock) {
            surfaces.value = info
            // A rotation or a return from the background replaces the window.
            if (surfaceBound) {
                runCatching { attach(info) }
                    .onFailure { GameSession.note(sessionLog, "поверхность не отдана: ${it.message}") }
            }
        }
    }

    fun surfaceGone() {
        synchronized(surfaceLock) {
            surfaces.value = null
            if (!surfaceBound) return
            val current = pack
            if (current != null) {
                current.detachSurface()
            } else {
                runCatching { RuntimeBridge.detachSurface() }
            }
        }
    }

    fun surfaceFailed(error: Throwable) {
        GameSession.note(sessionLog, "поверхность не принята: ${error.message}")
        showNotice("Экран игры не подключился — сверни игру и вернись в неё")
    }

    private fun attach(info: SurfaceInfo) {
        val current = pack
        if (current != null) {
            current.attachSurface(info.surface, info.width, info.height)
        } else {
            RuntimeBridge.attachSurface(info.surface, info.width, info.height)
        }
        inputSink.screenSize(info.width, info.height)
    }

    private fun bindSurface(): SurfaceInfo? = synchronized(surfaceLock) {
        surfaceBound = true
        surfaces.value?.also { attach(it) }
    }

    fun focusGame() {
        if (state.value.sdlWindow) {
            inputSink.focus(true)
            return
        }
        if (state.value.packLayer) PackInputSink.arm()
        val size = surfaces.value
        if (size != null) {
            if (state.value.packLayer) {
                PackInputSink.windowSize(size.width, size.height) {
                    GameSession.note(sessionLog, "размер окна не ушёл: ${it.message}")
                }
            } else {
                inputSink.screenSize(size.width, size.height)
            }
        }
        inputSink.focus(true)
    }

    // The game takes the real window size only from a size change sent after its own window
    // exists, and that happens at an unknown point of its start-up.
    private fun syncWindowSize() {
        sizeSync?.cancel()
        sizeSync = viewModelScope.launch {
            SIZE_SYNC_STEPS.forEach { step ->
                delay(step)
                if (!state.value.running) return@launch
                focusGame()
                if (frameDrawn()) {
                    delay(SIZE_SYNC_CONFIRM_MS)
                    if (!state.value.running) return@launch
                    focusGame()
                    GameSession.note(sessionLog, "размер окна подтверждён после первого кадра игры")
                    return@launch
                }
            }
            GameSession.note(sessionLog, "размер окна: первый кадр не дождались, повторы закончены")
        }
    }

    private fun frameDrawn(): Boolean = when {
        state.value.sdlWindow -> SdlHost.windowShown(SDL_FIRST_FRAME_MS)
        else -> state.value.packLayer && runCatching { CallbackBridge.getCurrentFps() }.getOrDefault(0) > 0
    }

    private fun watchFirstFrame() {
        if (!state.value.packLayer) {
            state.update { it.copy(booting = false) }
            return
        }
        viewModelScope.launch {
            val entry = rendererInUse?.entry
            val quick = withTimeoutOrNull(RendererFallback.NO_FRAME_GRACE_MS) {
                while (state.value.running && !frameDrawn()) delay(FIRST_FRAME_POLL_MS)
                true
            }
            // Black screen: no frame for a minute. The renderer is remembered as failed for this
            // build, so the next start takes another one; a frame that comes later clears it.
            if (quick == null && entry != null && state.value.running) {
                prefs.rendererNoFrame = prefs.rendererNoFrame + entry
                GameSession.note(sessionLog, "кадра нет больше минуты — рендерер помечен, в следующий раз автовыбор возьмёт другой")
            }
            val drawn = quick ?: withTimeoutOrNull(FIRST_FRAME_WAIT_MS - RendererFallback.NO_FRAME_GRACE_MS) {
                while (state.value.running && !frameDrawn()) delay(FIRST_FRAME_POLL_MS)
                true
            }
            if (drawn != null && entry != null && entry in prefs.rendererNoFrame) {
                prefs.rendererNoFrame = prefs.rendererNoFrame - entry
            }
            if (drawn == null) GameSession.note(sessionLog, "первый кадр не замечен — экран загрузки убран по времени")
            state.update { it.copy(booting = false) }
        }
    }

    fun setLookSensitivity(value: Float) {
        val next = value.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        prefs.lookSensitivity = next
        state.update { it.copy(lookSensitivity = next) }
    }

    fun grabChanged(grabbing: Boolean) {
        if (grabbing) refreshGuiScale()
    }

    // The desktop virtual machine cannot be unloaded, so closing always ends the game process.
    // The ready-made layer has no close channel, so there the process is ended straight away.
    fun stop() {
        if (state.value.closing || forced) return
        val running = state.value.running
        state.update { it.copy(running = false, booting = false, closing = true, note = "Закрываем игру") }
        viewModelScope.launch {
            val asked = running && askToClose()
            val left = asked && withTimeoutOrNull(CLOSE_GRACE_MS) { exited.await() } != null
            if (!left) {
                forceStop(
                    when {
                        asked -> "Игра не ответила на просьбу закрыться — завершаем её процесс"
                        running -> "Закрываем игру. Выйти по просьбе она не умеет, так что сохраняйся в игре перед выходом"
                        else -> "Отменяем запуск"
                    }
                )
            }
        }
    }

    private fun askToClose(): Boolean {
        if (state.value.sdlWindow) {
            val asked = runCatching { SdlHost.requestQuit() }.getOrDefault(false)
            GameSession.note(sessionLog, if (asked) "закрытие: SDL3 передал игре просьбу закрыть окно" else "закрытие: SDL3 ещё не поднят")
            return asked
        }
        if (state.value.packLayer) {
            GameSession.note(sessionLog, "закрытие: у готового оконного слоя нет канала закрытия")
            return false
        }
        RuntimeBridge.requestClose()
        GameSession.note(sessionLog, "закрытие: игре отправлена просьба закрыть окно")
        return true
    }

    private suspend fun forceStop(message: String) {
        forced = true
        GameSession.note(sessionLog, "принудительное завершение: $message")
        withContext(Dispatchers.IO) { reportEnd(ok = true, exitCode = "closed") }
        state.update { it.copy(running = false, booting = false, note = message) }
        val farewell = viewModelScope.launch(Dispatchers.IO) { if (endSession()) lastBeat() }
        delay(FORCED_NOTICE_MS)
        farewell.join()
        state.update { it.copy(closed = true) }
    }

    fun logTail(): String {
        val crash = GameSession.nativeCrash(container.layout.runtimes)?.takeIf { it.lastModified() >= openedAt }
        val joined = listOfNotNull(
            crash?.let { GameSession.tail(it, 120) },
            GameSession.tail(sessionLog, 220).takeIf { it.isNotBlank() },
        ).joinToString(System.lineSeparator() + System.lineSeparator())
        return ReportRedaction.clean(joined, secrets)
    }

    fun shareReport(): Job = viewModelScope.launch {
        val report = withContext(Dispatchers.IO) {
            runCatching { writeReport() }
                .onFailure { GameSession.note(sessionLog, "отчёт не собран: ${it.message}") }
                .getOrNull()
        }
        val shared = report != null && shareFile(getApplication<Application>(), report, "text/plain", "Отчёт Millida")
        if (!shared) showNotice("Отчёт не отправился — открой «Лог», скопируй его и пришли в поддержку")
    }

    private fun writeReport(): File {
        val folder = File(getApplication<Application>().cacheDir, "reports")
        val target = File(folder, "millida-game-report.txt")
        val snapshot = state.value
        val text = buildString {
            append("Millida ").append(BuildConfig.VERSION_NAME).append('\n')
            append(container.deviceReport()).append('\n')
            append("Сборка: ").append(snapshot.buildName).append('\n')
            append("Оконный слой: ").append(
                when {
                    snapshot.sdlWindow -> "SDL3"
                    snapshot.packLayer -> "готовый"
                    else -> "свой"
                },
            ).append('\n')
            snapshot.error?.let { append("Причина: ").append(it).append('\n') }
            snapshot.cause?.let { append("Похоже на: ").append(it).append('\n') }
            append('\n').append(logTail())
        }
        target.atomicWriteText(text)
        return target
    }

    fun showNotice(text: String) {
        noticeJob?.cancel()
        state.update { it.copy(notice = text) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            state.update { if (it.notice == text) it.copy(notice = null) else it }
        }
    }

    fun dismissNotice() {
        noticeJob?.cancel()
        state.update { it.copy(notice = null) }
    }

    private fun stage(step: Int, note: String) {
        launchStage = STAGE_NAMES.getOrElse(step) { launchStage }
        state.update { it.copy(stage = step, note = note) }
    }

    private suspend fun launch(request: LaunchIntent) {
        val profile = container.profiles.load().firstOrNull { it.id == request.profileId }
            ?: throw IllegalStateException("сборка не найдена")

        GameSession.note(sessionLog, "сборка ${profile.name}, версия ${profile.versionId}")
        state.update { it.copy(buildName = profile.name) }
        val origin = runCatching { PackOriginStore.read(container.profiles.directory(profile)) }.getOrNull()
        val (catalogPack, modpack) = LaunchFacts.origin(origin?.source, origin?.id)
        facts = LaunchFacts(
            build = TelemetryPrivacy.buildTag(profile.name, catalogPack) ?: "default",
            mc = profile.gameVersion,
            loader = profile.loader.id,
            pack = catalogPack,
            modpack = modpack,
            renderer = profile.rendererId.ifEmpty { prefs.rendererId }.ifEmpty { null },
        )
        stage(1, "Читаем описание версии")
        val version = container.resolver.resolve(profile.versionId)
        val packLaunch = PackLaunches.trusted(container.profiles.directory(profile))
        if (packLaunch == null && PackLaunches.unusable(container.profiles.directory(profile))) {
            throw IllegalStateException(
                "у «${profile.name}» свой запуск, но лаунчер не смог его прочитать — переустанови сборку из каталога, а если не поможет, напиши в поддержку",
            )
        }
        val javaMajor = packLaunch?.javaMajor ?: version.javaMajor
        val packNeedsSdl = packLaunch != null && PackLaunches.needsSdl(container.profiles.directory(profile), packLaunch)
        val needsSdl = packNeedsSdl || version.sdlWindow
        val window = WindowChoices.choose(needsSdl, PhoneFit.legacyRuntime(), container.runtimeInstaller().sdlAvailable())
        if (window is WindowChoice.Refused) {
            throw IllegalStateException(PhoneFit.message(window.block, profile.name, profile.gameVersion))
        }

        stage(2, "Готовим рантайм")
        HostAbi.launchProblem(Build.SUPPORTED_ABIS.toList())?.let { throw IllegalStateException(it) }
        val requestedRenderer = profile.rendererId.ifEmpty { prefs.rendererId }
        val runtime = withSuitableRenderer(
            runtimeFor(JavaRequirement.of(javaMajor, packLaunch?.mainClass ?: version.mainClass), requestedRenderer),
            profile.gameVersion,
            version.releaseTime,
            requestedRenderer,
            RendererFallback.failedAtInit(prefs.rendererInitFailures + prefs.rendererNoFrame, profile.id, profile.versionId),
            sodiumFamily(container.profiles.directory(profile)),
        )
        rendererInUse = RendererInUse(RendererKind.of(runtime.renderer), RendererFallback.entry(runtime.renderer.id, profile.id, profile.versionId))

        val sdl = if (window == WindowChoice.Sdl) sdlComponent() else null

        // A newer graphics library in the version description is a warning, not a refusal.
        LibraryCompatibility.problem(version.lwjglVersion, sdl?.lwjglVersion ?: runtime.lwjglVersion)?.let { problem ->
            GameSession.note(sessionLog, "предупреждение: $problem")
        }

        val directory = container.profiles.directory(profile)
        gameDir = directory
        readGuiScale(directory)
        val modCount = File(directory, "mods").listFiles { file -> file.extension == "jar" }?.size ?: 0
        val plan = MemoryTuning.plan(container.deviceMemory(), profile.loader, modCount, packLaunch?.minRamMb ?: 0)
        if (!plan.canLaunch) throw IllegalStateException(plan.warning ?: "не хватает памяти")
        val heap = when {
            request.heapMb > 0 -> request.heapMb
            profile.memoryMb > 0 -> profile.memoryMb
            else -> plan.heapMb
        }

        stage(3, "Проверяем вход")
        val agent = if (request.session.yggdrasil.isNotEmpty()) authlibAgent() else null

        // SDL3 starts on the machinery of the ready-made layer, whichever layer the settings name.
        if (sdl != null) state.update { it.copy(packLayer = true, sdlWindow = true) }
        val usePack = state.value.packLayer
        val layerName = when {
            sdl != null -> "SDL3"
            usePack -> "готовый"
            else -> "свой"
        }
        GameSession.note(sessionLog, "оконный слой: $layerName")
        facts = facts?.copy(
            renderer = runtime.renderer.id.ifEmpty { null } ?: facts?.renderer,
            layer = when {
                sdl != null -> LaunchFacts.LAYER_SDL
                usePack -> LaunchFacts.LAYER_PACK
                else -> LaunchFacts.LAYER_OWN
            },
            java = runtime.javaMajor,
            heapMb = heap,
            mods = modCount,
        )
        // Our own window module is built for Java 17; Java 8 cannot even load its classes.
        if (!usePack && runtime.javaMajor in 1..LEGACY_JAVA) {
            throw IllegalStateException(
                "Эта версия игры идёт на Java ${runtime.javaMajor} только с готовым оконным слоем — " +
                    "переключи «Оконный слой» в настройках и запусти снова",
            )
        }
        if (profile.loader == LoaderKind.FORGE && forgeGameUnsupported(profile.gameVersion)) {
            ForgeSplash.disable(directory)
        }

        val launchRequest = LaunchRequest(
                version = version,
                session = request.session,
                gameDir = directory,
                memoryMb = heap,
                windowWidth = request.windowWidth,
                windowHeight = request.windowHeight,
                extraJvmArgs = profile.extraJvmArgs,
                authlibInjector = agent,
                quickPlayServer = request.serverAddress.takeIf { it.isNotEmpty() },
        )

        // Forge and NeoForge describe their arguments their own way, and the plain builder
        // hands the game a command line it refuses to start with.
        // A catalogue pack with its own launch brings its own game and loader, so its description wins.
        val forgeFamily = profile.loader == LoaderKind.FORGE || profile.loader == LoaderKind.NEOFORGE
        val built = when {
            packLaunch != null -> PackLaunchBuild.build(container.layout, launchRequest, packLaunch, request.packJvmArgs)
            forgeFamily -> ForgeLaunch.build(container.layout, launchRequest)
            else -> container.launchSpecBuilder.build(launchRequest)
        }
        val spec = built.copy(jvmArgs = PhoneJvmArgs.filter(built.jvmArgs, runtime.javaMajor))

        val presence = Presence(
            build = profile.name,
            server = request.serverAddress.takeUnless { isLoopback(it) }.orEmpty(),
            nick = request.session.nick,
        )
        if (usePack) {
            startWithPack(runtime, spec, heap, profile.id, presence, sdl)
        } else {
            startWithOwn(runtime, spec, heap, profile.id, presence)
        }
    }

    /**
     * The pack on the phone is asked first. When it lacks the Java this version
     * needs, only that Java comes from the network and runs under the window
     * layer and renderers of the pack, instead of a second full runtime.
     */
    private suspend fun runtimeFor(requirement: JavaRequirement, rendererId: String): InstalledRuntime {
        RuntimeImport.installed(container.layout, requirement.minimum, rendererId, requirement.maximum)
            ?.let { return it }
        val pack = RuntimeImport.installed(container.layout, 0, rendererId)
        if (pack == null) {
            return container.runtimeInstaller().install(
                javaMajor = requirement.minimum,
                abi = container.runner.abi,
                rendererId = rendererId.ifEmpty { null },
                javaMaximum = requirement.maximum,
            ) { transfer -> runtimeProgress(transfer) }
        }
        GameSession.note(sessionLog, "в пакете рантайма нет нужной Java (${requirement.describe()}), берём её из сети")
        val java = container.runtimeInstaller().installJava(
            javaMajor = requirement.minimum,
            abi = container.runner.abi,
            javaMaximum = requirement.maximum,
        ) { transfer -> runtimeProgress(transfer) }
        return pack.copy(javaHome = java.home, javaMajor = java.major)
    }

    private suspend fun withSuitableRenderer(
        runtime: InstalledRuntime,
        gameVersion: String,
        releaseTime: String,
        requested: String,
        failedAtInit: Set<String>,
        sodium: Boolean,
    ): InstalledRuntime {
        val declared = if (RuntimeImport.owns(container.layout, runtime.rendererDir)) {
            RuntimeImport.renderers(container.layout).map { RendererCatalog.asEntry(it) }
        } else {
            runCatching { container.runtimeInstaller().manifest().renderers }
                .getOrDefault(emptyList())
                .filter { it.folder == runtime.rendererDir.name }
        }
        val offered = RendererCatalog.offered(declared.ifEmpty { listOf(runtime.renderer) }, runtime.rendererDir)
        val graphics = withContext(Dispatchers.Default) { GraphicsProbe.read(getApplication<Application>()) }
        return when (val verdict = RendererChoice.choose(gameVersion, releaseTime, graphics, offered, requested, failedAtInit, sodium)) {
            is RendererVerdict.Refused -> throw IllegalStateException(verdict.message)
            is RendererVerdict.Use -> {
                GameSession.note(
                    sessionLog,
                    "рендерер ${verdict.renderer.id} (${verdict.reason}), GPU ${graphics.gpu}, Vulkan 1.1: ${graphics.vulkan11}",
                )
                if (verdict.note.isNotEmpty()) showNotice(verdict.note)
                runtime.copy(renderer = verdict.renderer)
            }
        }
    }

    private fun sodiumFamily(directory: File): Boolean =
        runCatching { SodiumFamily.present(container.content.list(directory, ContentKind.MOD)) }
            .onFailure { GameSession.note(sessionLog, "моды сборки не прочитались, Sodium не ищем: ${it.message}") }
            .getOrDefault(false)

    private suspend fun sdlComponent(): InstalledSdl {
        stage(2, "Готовим SDL3")
        val sdl = container.runtimeInstaller().installSdl { transfer -> runtimeProgress(transfer) }
        GameSession.note(sessionLog, "SDL3 ${sdl.sdlVersion}, LWJGL ${sdl.lwjglVersion}: ${sdl.dir.absolutePath}")
        return sdl
    }

    private fun runtimeProgress(transfer: TransferProgress) {
        val percent = if (transfer.totalBytes > 0) (transfer.doneBytes * 100 / transfer.totalBytes).toInt() else 0
        stage(2, "Скачиваем рантайм: $percent%")
    }

    // Without the agent a Millida session silently plays offline: no multiplayer and no skin.
    private suspend fun authlibAgent(): File = try {
        AuthlibAgent(container.layout, Downloader(attempts = AGENT_ATTEMPTS)).ensure()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        GameSession.note(sessionLog, "вход Millida: ${error.message}")
        throw IllegalStateException(
            "Не скачался файл входа Millida — без него не будет скина и игры по сети. Проверь интернет и нажми «Ещё раз»",
            error,
        )
    }

    private suspend fun startWithPack(
        runtime: InstalledRuntime,
        spec: LaunchSpec,
        heap: Int,
        profileId: String,
        presence: Presence,
        sdl: InstalledSdl?,
    ) {
        val bridgeJars = if (sdl != null) emptyList() else runtime.bridgeClasspath.filterNot { it.name.startsWith("millida-glfw") }
        val layer = PackRuntime(getApplication(), runtime, sessionLog, sdl)
        pack = layer

        if (sdl != null) {
            SdlHost.onRelativeMouse { grabbing -> grabState.value = grabbing }
        } else {
            CallbackBridge.setGrabListener { grabbing -> grabState.value = grabbing }
        }

        stage(4, "Готовим оконный слой")
        val surface = awaitSurface()
        layer.prepare(spec.gameDir, surface.width, surface.height)
        bindSurface()

        gameStarted(profileId, presence)
        syncWindowSize()
        val code = onDedicatedThread {
            layer.start(spec, heap, surface.width, surface.height, bridgeJars)
        }
        finish(code == 0, "Код выхода $code", "exit_$code")
    }

    private suspend fun startWithOwn(
        runtime: InstalledRuntime,
        spec: LaunchSpec,
        heap: Int,
        profileId: String,
        presence: Presence,
    ) {
        val prepared = withAppBridge(runtime)
        stage(4, "Ждём поверхность отрисовки")
        awaitSurface()
        runCatching { bindSurface() }
            .onFailure { GameSession.note(sessionLog, "поверхность не отдана: ${it.message}") }
        inputSink.focus(true)

        gameStarted(profileId, presence)
        syncWindowSize()
        val ok = container.runner.start(spec, prepared, heap, sessionLog)
        finish(ok, "Игра закрылась с ошибкой", "exit_error")
    }

    private fun gameStarted(profileId: String, presence: Presence) {
        state.update {
            it.copy(stage = LAUNCH_STAGES, note = "Запускаем игру", running = true, started = true, booting = true)
        }
        beginSession(profileId, presence)
        facts?.let { telemetry.gameLaunched(it, SystemClock.elapsedRealtime() - launchBegan, System.currentTimeMillis()) }
        watchFirstFrame()
    }

    private suspend fun finish(ok: Boolean, reason: String, exitCode: String) {
        sizeSync?.cancel()
        val hadSession = endSession()
        exited.complete(Unit)
        if (!forced) reportEnd(ok, exitCode)
        val leaving = !forced && state.value.closing && ok
        if (hadSession) {
            if (leaving) lastBeat() else viewModelScope.launch(Dispatchers.IO) { lastBeat() }
        }
        if (forced) return
        val cause = if (ok) null else crashCause()
        if (!ok) withContext(Dispatchers.IO) { rememberInitFailure() }
        state.update {
            it.copy(
                running = false,
                booting = false,
                finished = true,
                note = if (ok) "Игра закрылась" else "Игра закрылась с ошибкой",
                error = if (ok) null else reason,
                cause = cause,
                // Asked to close and it did: the screen leaves instead of showing a panel.
                closed = it.closing && ok,
            )
        }
    }

    /** The player closing the game is an exit, not a crash, even when its process has to be ended. */
    private suspend fun reportEnd(ok: Boolean, exitCode: String) {
        val masks = listOf(nick to "<nick>", state.value.buildName to "<build>")
        val log = if (ok) null else runCatching { ReportRedaction.mask(logTail(), masks) }.getOrNull()
        telemetry.gameEnded(ok, exitCode, log)
        telemetry.flushSoon()
    }

    private fun rememberInitFailure() {
        val used = rendererInUse ?: return
        val crashText = listOf(crashReportHead(), runCatching { logTail() }.getOrDefault("")).joinToString(System.lineSeparator())
        if (!RendererFallback.crashedAtInit(used.kind, crashText)) return
        prefs.rendererInitFailures = prefs.rendererInitFailures + used.entry
        GameSession.note(sessionLog, "рендерер упал при запуске игры, в следующий раз автовыбор возьмёт другой")
    }

    /** The tail of the session log can lose the report's description under a long mod list. */
    private fun crashReportHead(): String {
        val dir = gameDir ?: return ""
        return runCatching {
            container.gameLogs.crashReports(dir).firstOrNull()
                ?.takeIf { it.lastModified() >= openedAt }
                ?.bufferedReader()
                ?.useLines { lines -> lines.take(CRASH_REPORT_HEAD_LINES).joinToString(System.lineSeparator()) }
        }.getOrNull().orEmpty()
    }

    private fun crashCause(): String? {
        val log = runCatching { logTail() }.getOrDefault("")
        return CRASH_CAUSES.firstOrNull { (marker, _) -> log.contains(marker) }?.second
    }

    private fun beginSession(profileId: String, presence: Presence) {
        synchronized(playLock) {
            playProfileId = profileId
            playFrom = SystemClock.elapsedRealtime()
            playCounted = false
        }
        sessionLoop?.cancel()
        sessionLoop = viewModelScope.launch(Dispatchers.IO) {
            flushPlaytime()
            while (isActive) {
                beat(BeatStatus.PLAYING, presence)
                telemetry.gameAlive()
                delay(SESSION_TICK_MS)
                flushPlaytime()
            }
        }
    }

    private fun endSession(): Boolean {
        val loop = sessionLoop
        sessionLoop = null
        loop?.cancel()
        flushPlaytime()
        synchronized(playLock) { playProfileId = "" }
        return loop != null
    }

    private suspend fun lastBeat() {
        withTimeoutOrNull(LAST_BEAT_MS) { beat(BeatStatus.LOBBY, null) }
    }

    private fun flushPlaytime() {
        synchronized(playLock) {
            if (playProfileId.isEmpty()) return
            val now = SystemClock.elapsedRealtime()
            val seconds = (now - playFrom) / 1000
            val first = !playCounted
            if (seconds <= 0 && !first) return
            try {
                container.playtime.record(playProfileId, seconds, newSession = first)
                playFrom += seconds * 1000
                playCounted = true
            } catch (error: Exception) {
                GameSession.note(sessionLog, "время игры не записано: ${error.message}")
            }
        }
    }

    private suspend fun beat(status: BeatStatus, presence: Presence?) {
        val live = if (status == BeatStatus.PLAYING) BeatState.PLAYING else BeatState.IDLE
        val tag = facts
        if (!container.millidaAuth.signedIn()) {
            telemetry.beatAlone(live, tag?.build, tag?.mc, presence?.server)
            return
        }
        try {
            this.presence.heartbeat(
                status = status,
                serverName = presence?.server,
                serverIp = presence?.server,
                build = presence?.build,
                gameNick = presence?.nick,
                telemetry = telemetry.beatPayload(live, tag?.build, tag?.mc, presence?.server),
            )
            if (presenceFailing) {
                presenceFailing = false
                GameSession.note(sessionLog, "присутствие снова отправляется")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "присутствие не отправлено: ${error.message}")
            if (!presenceFailing) {
                presenceFailing = true
                GameSession.note(sessionLog, "присутствие не отправлено: ${error.message}")
            }
        }
    }

    fun refreshGuiScale() {
        val directory = gameDir ?: return
        viewModelScope.launch(Dispatchers.IO) { readGuiScale(directory) }
    }

    private fun readGuiScale(directory: File) {
        val options = File(directory, "options.txt")
        if (!options.isFile) return
        val (scale, unicode) = runCatching { Hotbar.readOptions(options.readText()) }.getOrNull() ?: return
        state.update { it.copy(guiScale = scale, forceUnicode = unicode) }
    }

    private fun isLoopback(address: String): Boolean {
        val host = address.substringBeforeLast(':').removePrefix("[").removeSuffix("]").lowercase()
        return host == "localhost" || host.startsWith("127.") || host == "::1"
    }

    private suspend fun awaitSurface(): SurfaceInfo =
        withTimeoutOrNull(SURFACE_WAIT_MS) { surfaces.filterNotNull().first() }
            ?: throw IllegalStateException("экран игры не открылся — вернись в игру и нажми «Ещё раз»")

    private suspend fun onDedicatedThread(block: () -> Int): Int =
        suspendCancellableCoroutine { continuation ->
            val thread = Thread({
                val code = runCatching(block).getOrElse { error ->
                    GameSession.note(sessionLog, "падение при запуске: ${error.message}")
                    -1
                }
                if (continuation.isActive) continuation.resume(code)
            }, "millida-game")
            thread.isDaemon = false
            thread.start()
        }

    private fun withAppBridge(runtime: InstalledRuntime): InstalledRuntime {
        val target = File(File(container.layout.runtimes, "bridge-app"), BRIDGE_ASSET)
        val application = getApplication<Application>()
        val copied = runCatching {
            application.assets.open(BRIDGE_ASSET).use { source ->
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "$BRIDGE_ASSET.new")
                temporary.outputStream().use { out -> source.copyTo(out) }
                // A rename replaces the old jar in one step, so a failure keeps the previous copy whole.
                if (!temporary.renameTo(target)) {
                    temporary.delete()
                    throw IOException("оконный мост не обновился")
                }
            }
            target
        }.getOrNull()
        if (copied == null || !target.isFile) return runtime

        val rest = runtime.bridgeClasspath.filterNot { it.name.startsWith("millida-glfw") }
        return runtime.copy(bridgeClasspath = listOf(target) + rest)
    }

    override fun onCleared() {
        endSession()
        pendingHud?.let { layout -> runCatching { hudStore.save(layout) } }
        super.onCleared()
    }

    private class RendererInUse(val kind: RendererKind, val entry: String)

    private class ReadOnlySession(private val source: SecretVault) : SecretVault {
        override fun read(key: String): String? =
            if (key == MillidaAuth.KEY_REFRESH) null else source.read(key)

        override fun write(key: String, value: String) = Unit

        override fun remove(key: String) = Unit
    }

    private companion object {
        const val TAG = "millida-game"
        const val BRIDGE_ASSET = "millida-glfw.jar"
        const val CLOSE_GRACE_MS = 12_000L
        const val FORCED_NOTICE_MS = 1_800L
        const val SIZE_SYNC_CONFIRM_MS = 700L
        const val FIRST_FRAME_POLL_MS = 250L
        const val FIRST_FRAME_WAIT_MS = 120_000L
        const val SURFACE_WAIT_MS = 120_000L
        const val HUD_SAVE_DELAY_MS = 400L
        const val MIN_SENSITIVITY = 0.3f
        const val MAX_SENSITIVITY = 3f
        const val NOTICE_MS = 4_000L
        const val SESSION_TICK_MS = 60_000L
        const val CRASH_REPORT_HEAD_LINES = 40

        /** The launch steps by the names the dashboard groups failed starts under. */
        val STAGE_NAMES = mapOf(1 to "version", 2 to "runtime", 3 to "auth", 4 to "window", LAUNCH_STAGES to "start")
        const val LAST_BEAT_MS = 3_000L
        const val AGENT_ATTEMPTS = 2
        const val LEGACY_JAVA = 8
        const val SDL_FIRST_FRAME_MS = 1_500L
        val SIZE_SYNC_STEPS = listOf(
            1_200L, 1_800L, 3_000L, 4_000L, 6_000L,
            8_000L, 12_000L, 15_000L, 20_000L, 30_000L, 40_000L,
        )

        val CRASH_CAUSES = listOf(
            "java.lang.OutOfMemoryError" to "Игре не хватило памяти — выключи часть модов или дай сборке больше памяти",
            "Could not reserve enough space" to "Java не получила память — поставь сборке меньше памяти",
            "UnsatisfiedLinkError" to "Не загрузилась часть рантайма — переустанови его в настройках",
            "Failed to fetch metadata" to "Игра не достучалась до входа Millida — проверь интернет",
        )
    }
}
