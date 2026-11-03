package net.millida.launcher

import android.app.Application
import android.os.Build
import android.os.Process
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.telemetry.DeviceFacts
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.millida.launcher.core.MemoryTuning
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.SecretVault
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.game.FpsStats
import net.millida.launcher.core.game.GameResolution
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
import net.millida.launcher.core.logs.CrashHints
import net.millida.launcher.core.packs.PackLaunchBuild
import net.millida.launcher.core.packs.PackLaunches
import net.millida.launcher.core.packs.PhoneFit
import net.millida.launcher.core.runtime.AuthlibAgent
import net.millida.launcher.core.runtime.AuthlibMetadata
import net.millida.launcher.core.runtime.BootPicture
import net.millida.launcher.core.runtime.BootStep
import net.millida.launcher.core.runtime.BootWatch
import net.millida.launcher.core.runtime.GameEnd
import net.millida.launcher.core.runtime.HostAbi
import net.millida.launcher.core.runtime.InstalledRuntime
import net.millida.launcher.core.runtime.InstalledSdl
import net.millida.launcher.core.runtime.JavaRequirement
import net.millida.launcher.core.runtime.ModJava
import net.millida.launcher.core.runtime.LibraryCompatibility
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.runtime.PhoneJvmArgs
import net.millida.launcher.core.runtime.PhoneVersions
import net.millida.launcher.core.runtime.Picture
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
import net.millida.launcher.core.runtime.SdlLaunch
import net.millida.launcher.core.runtime.SodiumFamily
import net.millida.launcher.core.runtime.withLayer
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.support.ReportRedaction
import net.millida.launcher.core.packs.PackOriginStore
import net.millida.launcher.core.telemetry.AnrTrace
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

const val LAUNCH_STAGES = 5

/**
 * A game on a RenderPearl version drew a black screen. [version] is the one a phone plays well
 * and a vanilla build can be switched to at once; null for a build with mods, which only gets
 * the explanation.
 */
data class BlackOffer(val from: String, val version: String?)

data class GameUiState(
    val note: String = tr("Готовим игру"),
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
    /** Seconds the game has been loading, set once its first minute is over: the loading screen shows them. */
    val bootSeconds: Int = 0,
    val started: Boolean = false,
    val blackOffer: BlackOffer? = null,
    val cause: String? = null,
    /** False when a repeated start would run the very same renderer into the very same wall. */
    val retryHelps: Boolean = true,
    val notice: String? = null,
    val guiScale: Int = 0,
    val forceUnicode: Boolean = false,
    val haptics: Boolean = true,
)

class GameLaunchModel(application: Application) : AndroidViewModel(application) {
    private val container = Container.get(application)
    private val prefs = Prefs(application)

    /** The view, the touches and the window read the same pair, so they change together. */
    @Volatile
    var resolutionPercent: Int = prefs.resolutionPercent
        private set

    /** Game pixels per screen pixel, the same rounding the view applies to its buffer. */
    @Volatile
    var resolution: Float = factorFor(resolutionPercent)
        private set

    private fun factorFor(percent: Int): Float = getApplication<Application>().resources.displayMetrics.let { metrics ->
        val width = maxOf(metrics.widthPixels, metrics.heightPixels)
        val height = minOf(metrics.widthPixels, metrics.heightPixels)
        if (width <= 0) 1f else GameResolution.window(width, height, percent).first.toFloat() / width
    }

    /** Changed from the pause menu: kept for the next start too. Returns the new factor. */
    fun changeResolution(percent: Int): Float {
        val clamped = GameResolution.clamp(percent)
        resolutionPercent = clamped
        resolution = factorFor(clamped)
        prefs.resolutionPercent = clamped
        return resolution
    }

    /** Frames of the last second as the window layer counts them; null where the layer does not count. */
    fun currentFps(): Int? {
        val current = state.value
        if (!current.packLayer || current.sdlWindow || !current.running) return null
        return runCatching { CallbackBridge.getCurrentFps() }.getOrNull()
    }

    var fpsMeter: Boolean
        get() = prefs.fpsMeter
        set(value) {
            prefs.fpsMeter = value
        }

    var underCutout: Boolean
        get() = prefs.gameUnderCutout
        set(value) {
            prefs.gameUnderCutout = value
        }

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
                    showNotice(tr("Раскладка не сохранилась — освободи место на телефоне и поправь её ещё раз"))
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
        override fun cursorPosition(x: Float, y: Float) =
            target().cursorPosition(GameResolution.toGame(x, resolution), GameResolution.toGame(y, resolution))
        override fun cursorDelta(x: Float, y: Float) = target().cursorDelta(x, y)
        override fun scroll(horizontal: Double, vertical: Double) = target().scroll(horizontal, vertical)
        override fun screenSize(width: Int, height: Int) = target().screenSize(width, height)
        override fun focus(focused: Boolean) = target().focus(focused)
    }

    @Volatile
    private var sessionLog: File = GameSession.sessionLog(container.layout.runtimes)

    @Volatile
    private var openedAt = System.currentTimeMillis()
    private val surfaces = MutableStateFlow<SurfaceInfo?>(null)
    private val surfaceLock = Any()
    private var surfaceBound = false
    private val exited = CompletableDeferred<Unit>()
    private val ended = AtomicBoolean(false)

    /** The window library of a game shown by [showResult]: its report names it, and no input goes to it. */
    @Volatile
    private var shownSdl = false
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

    /**
     * «Millida не отвечает»: the system keeps no thread dump of this process, so the stack of
     * the screen thread is read here, into the log the player sends and into statistics.
     */
    private val screenWatch = MainThreadWatch { stuckMs, trace ->
        val stack = AnrTrace.ofThread(trace).orEmpty()
        GameSession.note(sessionLog, "экран не отвечает ${stuckMs / 1000} с: $stack")
        val phase = when {
            state.value.closing -> "close"
            state.value.booting -> "boot"
            state.value.running -> "play"
            else -> "prepare"
        }
        telemetry.mainThreadStuck(facts, phase, stuckMs, trace)
    }

    private var launchBegan = 0L

    @Volatile
    private var launchStage = "prepare"

    @Volatile
    private var facts: LaunchFacts? = null
    private var launchedBuild: Profile? = null

    @Volatile
    private var rendererInUse: RendererInUse? = null

    /** Renderers besides the one in use that a repeated start could take; zero means «Ещё раз» changes nothing. */
    @Volatile private var otherRenderers = 1

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
        secrets = secretsOf(request)
        ExitTrap.watch(::trapped)

        launchBegan = SystemClock.elapsedRealtime()
        screenWatch.start()
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

    private fun secretsOf(request: LaunchIntent): List<Pair<String?, String>> = listOf(
        request.session.accessToken to "<token>",
        request.session.uuid to "<uuid>",
        request.session.xuid to "<xuid>",
    )

    /**
     * A game that crashed in the process before this one: nothing is started, its end
     * is shown from the log it left.
     */
    fun showResult(request: LaunchIntent, result: GameResult) {
        if (started) return
        started = true
        nick = request.session.nick
        secrets = secretsOf(request)
        // The name comes through an intent: only a file of the logs folder is ever read.
        sessionLog = File(GameSession.logsDir(container.layout.runtimes), File(result.logName).name)
        openedAt = result.openedAt
        shownSdl = result.sdl
        state.update {
            it.copy(
                buildName = result.build,
                started = true,
                finished = true,
                note = tr("Игра закрылась с ошибкой"),
                error = result.error,
                cause = result.cause,
                retryHelps = result.retryHelps,
            )
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
        showNotice(tr("Экран игры не подключился — сверни игру и вернись в неё"))
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
        // The screen of a game that already ended has nothing to focus, and a refusal would land in its log.
        if (state.value.finished) return
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
        // Off the screen thread: the frame check is a call into the window layer's native library.
        sizeSync = viewModelScope.launch(Dispatchers.Default) {
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

    /**
     * Waits for the game's first frame and decides what a start without one is, see [BootWatch].
     * Runs off the screen thread: it reads the log's age from disk and asks the window layer's
     * native library for frames four times a second.
     */
    /** Test builds only: milestones of the game's own log go to logcat, so a script can time a start. */
    private fun echoGameLog() {
        if (!BuildConfig.TEST_HOOKS) return
        val marks = Regex("Sound engine started|Created: \\d+x\\d+x\\d+ .*atlas|Loading \\d+ mods|Reloading ResourceManager|Stopping!|Preparing spawn area|joined the game")
        viewModelScope.launch(Dispatchers.IO) {
            var offset = 0L
            while (state.value.running) {
                runCatching {
                    val file = sessionLog
                    if (file.length() > offset) {
                        java.io.RandomAccessFile(file, "r").use { raf ->
                            raf.seek(offset)
                            val bytes = ByteArray((file.length() - offset).toInt().coerceAtMost(1 shl 20))
                            raf.readFully(bytes)
                            offset += bytes.size
                            String(bytes).lineSequence().filter { marks.containsMatchIn(it) }
                                .forEach { android.util.Log.i("millida-test", "GAMELOG ${it.take(160)}") }
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    private fun watchFirstFrame() {
        echoGameLog()
        if (!state.value.packLayer) {
            state.update { it.copy(booting = false) }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val entry = rendererInUse?.entry
            val began = SystemClock.elapsedRealtime()
            var verdict = BootWatch.Verdict.WAITING
            var drawn = false
            while (state.value.running) {
                if (frameDrawn()) {
                    drawn = true
                    break
                }
                val elapsed = SystemClock.elapsedRealtime() - began
                verdict = BootWatch.verdict(elapsed, logQuietMs())
                if (verdict == BootWatch.Verdict.SILENT || verdict == BootWatch.Verdict.OVERDUE) break
                if (verdict == BootWatch.Verdict.LOADING) {
                    val seconds = (elapsed / 1000).toInt()
                    if (seconds != state.value.bootSeconds) state.update { it.copy(bootSeconds = seconds) }
                }
                delay(FIRST_FRAME_POLL_MS)
            }
            if (!drawn && state.value.running) {
                val waited = SystemClock.elapsedRealtime() - began
                val quiet = logQuietMs()
                // The renderer is remembered as failed for this build, so the next start takes
                // another one; a frame that comes later clears it.
                if (entry != null) prefs.rendererNoFrame = prefs.rendererNoFrame + entry
                GameSession.note(
                    sessionLog,
                    "кадра нет ${BootWatch.clock(waited)}, лог молчит ${quiet / 1000} с — экран загрузки убран" +
                        if (entry != null) ", рендерер помечен: в следующий раз автовыбор возьмёт другой" else "",
                )
                // Чёрный экран — не вылет, и без этого события о нём не узнать: игрок просто закрывает игру.
                val log = runCatching { logTail() }.getOrNull()
                facts?.let { telemetry.gameBlackScreen(it, log, waited, quiet) }
                offerRecommended()
                state.update { it.copy(booting = false, bootSeconds = 0) }
                if (verdict == BootWatch.Verdict.SILENT) {
                    withContext(Dispatchers.Main) { showNotice(tr("Игра молчит уже минуту. Если экран чёрный — выйди и зайди снова")) }
                }
                drawn = withTimeoutOrNull(LATE_FRAME_WAIT_MS) {
                    while (state.value.running && !frameDrawn()) delay(LATE_FRAME_POLL_MS)
                    state.value.running
                } == true
            }
            if (drawn && entry != null && entry in prefs.rendererNoFrame) {
                prefs.rendererNoFrame = prefs.rendererNoFrame - entry
            }
            if (drawn) {
                facts = facts?.copy(frame = true, frameMs = SystemClock.elapsedRealtime() - launchBegan, resolutionPercent = resolutionPercent)
                facts?.let(telemetry::gameFrameReached)
                sampleFps()
                val guards = GameGuards(getApplication())
                guards.confirmExitHook()
                if (state.value.booting) awaitPicture(guards, began)
            }
            state.update { it.copy(booting = false, bootSeconds = 0) }
        }
    }

    private val fpsStats = FpsStats()

    /**
     * Counts the game's frames once a second from its first frame, shown meter or not: the numbers go
     * to the pack's card (how fast it runs on weak phones). The first seconds are the world loading
     * and are left out. The open-game record gets the totals every half minute and at the end.
     */
    private fun sampleFps() {
        if (state.value.sdlWindow) return
        viewModelScope.launch(Dispatchers.Default) {
            delay(FPS_WARMUP_MS)
            var tick = 0
            while (state.value.running) {
                runCatching { CallbackBridge.getCurrentFps() }.getOrNull()?.let(fpsStats::add)
                if (++tick % FPS_PUSH_EVERY == 0) pushFps()
                delay(FPS_SAMPLE_MS)
            }
        }
    }

    private fun pushFps() {
        val p50 = fpsStats.percentile(50) ?: return
        facts = facts?.copy(fpsP50 = p50, fpsP10 = fpsStats.percentile(10), fpsN = fpsStats.n)
        facts?.let(telemetry::gameFrameReached)
    }

    /**
     * Frames are coming, and the first of them are black, see [BootPicture]: the loading
     * screen stays until the picture is there, for as long as the game's log says it is
     * loading. Whatever goes wrong with reading the picture ends in what was before — the
     * game shown as soon as it hands frames over.
     */
    private suspend fun awaitPicture(guards: GameGuards, began: Long) {
        val version = BuildConfig.VERSION_NAME
        if (!BootPicture.enabled(pack?.rendererEnvironment.orEmpty()) || guards.pictureProbeOff(version)) return
        val probe = PictureProbe()
        var log = BootWatch.Verdict.WAITING
        var step = BootStep.WAIT
        try {
            while (state.value.running && step == BootStep.WAIT) {
                val picture = probe.look(surfaces.value?.surface)
                val elapsed = SystemClock.elapsedRealtime() - began
                log = BootWatch.verdict(elapsed, logQuietMs())
                step = BootPicture.step(picture, log)
                if (step != BootStep.WAIT) break
                if (log == BootWatch.Verdict.LOADING) {
                    val seconds = (elapsed / 1000).toInt()
                    if (seconds != state.value.bootSeconds) state.update { it.copy(bootSeconds = seconds) }
                }
                delay(PICTURE_POLL_MS)
            }
        } finally {
            probe.close()
        }
        if (step != BootStep.BLACK || !state.value.running) return
        val waited = SystemClock.elapsedRealtime() - began
        val quiet = logQuietMs()
        GameSession.note(
            sessionLog,
            "игра отдаёт кадры, а картинка чёрная ${BootWatch.clock(waited)}, лог молчит ${quiet / 1000} с — экран загрузки убран",
        )
        // A black screen is not a crash, and without this event nobody learns of it: the player just closes the game.
        val tail = runCatching { logTail() }.getOrNull()
        facts?.let { telemetry.gameBlackScreen(it, tail, waited, quiet, BLACK_PICTURE) }
        offerRecommended()
        state.update { it.copy(booting = false, bootSeconds = 0) }
        if (log == BootWatch.Verdict.SILENT) {
            withContext(Dispatchers.Main) { showNotice(tr("Игра молчит уже минуту. Если экран чёрный — выйди и зайди снова")) }
        }
        // A real black screen is closed within minutes; a game played on was never black.
        delay(BootPicture.DISTRUST_AFTER_MS)
        if (!state.value.running) return
        val kept = guards.distrustPictureProbe(version)
        GameSession.note(
            sessionLog,
            if (kept) "игра идёт дальше после «чёрной картинки» — на этом телефоне экран загрузки снова снимается по первому кадру"
            else "недоверие к чтению картинки не записалось — в следующий раз проверка повторится",
        )
    }

    /**
     * The picture is black on a version the phone is known not to draw (RenderPearl, 26.3+). A vanilla
     * build is offered the version a phone plays well; a build with mods only gets the reason.
     */
    private suspend fun offerRecommended() {
        val build = launchedBuild ?: return
        val phone = PhoneVersions(runCatching { container.runtimeInstaller().cachedManifest()?.phoneVersions }.getOrNull())
        if (!phone.isRisky(build.gameVersion)) return
        val modded = build.loader != LoaderKind.VANILLA ||
            runCatching { PackOriginStore.read(container.profiles.directory(build)) }.getOrNull() != null
        val target = if (modded) null else {
            runCatching { phone.recommended(container.resolver.manifest().releases())?.id }.getOrNull()
                ?.takeIf { it != build.gameVersion }
        }
        GameSession.note(sessionLog, "чёрный экран на ${build.gameVersion}: " + (target?.let { "предложено $it" } ?: "переключать нельзя"))
        state.update { it.copy(blackOffer = BlackOffer(build.gameVersion, target)) }
    }

    /** How long ago the game last wrote its log; a log that cannot be read counts as just written. */
    private fun logQuietMs(): Long {
        val written = runCatching { sessionLog.lastModified() }.getOrDefault(0L)
        if (written <= 0L) return 0L
        return (System.currentTimeMillis() - written).coerceAtLeast(0L)
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
        state.update { it.copy(running = false, booting = false, closing = true, note = tr("Закрываем игру")) }
        viewModelScope.launch {
            val asked = running && askToClose()
            val left = asked && withTimeoutOrNull(CLOSE_GRACE_MS) { exited.await() } != null
            if (!left) {
                forceStop(
                    when {
                        asked -> tr("Игра не ответила на просьбу закрыться — завершаем её процесс")
                        running -> tr("Закрываем игру. Выйти по просьбе она не умеет, так что сохраняйся в игре перед выходом")
                        else -> tr("Отменяем запуск")
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
        withContext(Dispatchers.IO) {
            // A game that hung on a renderer failure is closed by the player, and that is still the renderer's failure.
            rememberInitFailure()
            prefs.rendererPendingStart = ""
            reportEnd(ok = true, exitCode = "closed")
        }
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

    /** The session log so far, cleaned of secrets, handed to another app while the game keeps running. */
    fun shareLog(): Job = viewModelScope.launch {
        val app = getApplication<Application>()
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val text = ReportRedaction.clean(GameSession.tail(sessionLog, SHARED_LOG_LINES), secrets)
                File(app.cacheDir, "millida-game-log.txt").also { it.writeText(text) }
            }.getOrNull()
        }
        if (file == null || !shareFile(app, file, "text/plain", tr("Лог игры"))) {
            showNotice(tr("Не получилось поделиться логом — скопируй его кнопкой «Скопировать»"))
        }
    }

    fun shareReport(): Job = viewModelScope.launch {

        val report = withContext(Dispatchers.IO) {
            runCatching { writeReport() }
                .onFailure { GameSession.note(sessionLog, "отчёт не собран: ${it.message}") }
                .getOrNull()
        }
        val shared = report != null && shareFile(getApplication<Application>(), report, "text/plain", tr("Отчёт Millida"))
        if (!shared) showNotice(tr("Отчёт не отправился — открой «Лог», скопируй его и пришли в поддержку"))
    }

    private fun writeReport(): File {
        val folder = File(getApplication<Application>().cacheDir, "reports")
        val target = File(folder, "millida-game-report.txt")
        val snapshot = state.value
        val text = buildString {
            append("Millida ").append(BuildConfig.VERSION_NAME).append('\n')
            append(container.deviceReport()).append('\n')
            append(tr("Сборка: {0}", snapshot.buildName)).append('\n')
            val layer = when {
                snapshot.sdlWindow || shownSdl -> "SDL3"
                snapshot.packLayer -> tr("готовый")
                else -> tr("свой")
            }
            append(tr("Оконный слой: {0}", layer)).append('\n')
            snapshot.error?.let { append(tr("Причина: {0}", it)).append('\n') }
            snapshot.cause?.let { append(tr("Похоже на: {0}", it)).append('\n') }
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
            ?: throw IllegalStateException(tr("сборка не найдена"))

        launchedBuild = profile
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
            // Without these a crash with no cause cannot be told apart by GPU (29.10: 399 phones).
            gpu = withContext(Dispatchers.Default) { GraphicsProbe.read(getApplication<Application>()) }.glRenderer,
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().take(60),
        )
        stage(1, tr("Читаем описание версии"))
        val version = container.resolver.resolve(profile.versionId)
        val packLaunch = PackLaunches.trusted(container.profiles.directory(profile))
        if (packLaunch == null && PackLaunches.unusable(container.profiles.directory(profile))) {
            throw IllegalStateException(
                tr("у «{name}» свой запуск, но лаунчер не смог его прочитать — переустанови сборку из каталога, а если не поможет, напиши в поддержку", profile.name),
            )
        }
        val javaMajor = packLaunch?.javaMajor ?: version.javaMajor
        val packNeedsSdl = packLaunch != null && PackLaunches.needsSdl(container.profiles.directory(profile), packLaunch)
        val needsSdl = packNeedsSdl || version.sdlWindow
        val window = WindowChoices.choose(needsSdl, PhoneFit.legacyRuntime(), container.runtimeInstaller().sdlAvailable())
        if (window is WindowChoice.Refused) {
            throw IllegalStateException(PhoneFit.message(window.block, profile.name, profile.gameVersion))
        }
        if (window == WindowChoice.Sdl) {
            SdlLaunch.androidProblem(Build.VERSION.SDK_INT, javaMajor, profile.gameVersion)?.let { throw IllegalStateException(it) }
        }

        stage(2, tr("Готовим рантайм"))
        HostAbi.launchProblem(Build.SUPPORTED_ABIS.toList())?.let { throw IllegalStateException(it) }
        withContext(Dispatchers.IO) { settlePendingStart() }
        val requestedRenderer = profile.rendererId.ifEmpty { prefs.rendererId }
        val baseJava = JavaRequirement.of(javaMajor, packLaunch?.mainClass ?: version.mainClass)
        val modsJava = withContext(Dispatchers.IO) { ModJava.required(File(container.profiles.directory(profile), "mods")) }
        val javaNeeded = baseJava.withMods(modsJava)
        if (javaNeeded != baseJava) GameSession.note(sessionLog, "моды собраны под Java $modsJava, версия игры просит ${baseJava.minimum}: берём Java ${javaNeeded.minimum} или новее")
        val runtime = withSuitableRenderer(
            runtimeFor(javaNeeded, requestedRenderer),
            profile.gameVersion,
            version.releaseTime,
            requestedRenderer,
            RendererFallback.failedAtInit(prefs.rendererInitFailures + prefs.rendererNoFrame, profile.id, profile.versionId),
            sodiumFamily(container.profiles.directory(profile)),
            sdl = window == WindowChoice.Sdl,
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
        if (!plan.canLaunch) throw IllegalStateException(plan.warning ?: tr("не хватает памяти"))
        val wanted = when {
            request.heapMb > 0 -> request.heapMb
            profile.memoryMb > 0 -> profile.memoryMb
            else -> plan.heapMb
        }
        // A heap of a one-off request is the player's own word; a build's or a saved one is held to the phone's share.
        val asked = if (request.heapMb > 0) wanted else MemoryTuning.fitSmallPhone(wanted, container.deviceMemory().totalMb)
        if (asked < wanted) GameSession.note(sessionLog, "телефон с малой памятью: куча $asked МБ вместо $wanted МБ, чтобы систему не пришлось закрывать игру")
        val heap = MemoryTuning.fitAddressSpace(asked, narrow = !Process.is64Bit())
        if (heap < asked) GameSession.note(sessionLog, "система 32-битная: куча $heap МБ вместо $asked МБ, больше не поместится в адреса процесса")

        stage(3, tr("Проверяем вход"))
        val agent = if (request.session.yggdrasil.isNotEmpty()) authlibAgent() else null
        val prefetched = if (agent != null) {
            AuthlibMetadata(container.layout, Downloader(attempts = 1)).jvmArgument(request.session.yggdrasil)
        } else {
            null
        }
        if (agent != null && prefetched == null) GameSession.note(sessionLog, "вход Millida: описание сервера входа не получено, агент спросит сам")
        val modToken = container.millidaAuth.modSessionToken()
        if (modToken == null && container.millidaAuth.signedIn()) {
            GameSession.note(sessionLog, "токен мода Millida не получен: гардероб в игре останется без входа")
        }

        // SDL3 starts on the machinery of the ready-made layer, whichever layer the settings name.
        if (sdl != null) state.update { it.copy(packLayer = true, sdlWindow = true) }
        val usePack = state.value.packLayer
        val layerName = when {
            sdl != null -> "SDL3"
            usePack -> tr("готовый")
            else -> tr("свой")
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
                tr("Эта версия игры идёт на Java {javaMajor} только с готовым оконным слоем — переключи «Оконный слой» в настройках и запусти снова", runtime.javaMajor),
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
                authlibPrefetched = prefetched,
                quickPlayServer = request.serverAddress.takeIf { it.isNotEmpty() },
                millidaToken = modToken,
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
        withContext(Dispatchers.IO) {
            rendererInUse?.let { prefs.rendererPendingStart = RendererFallback.pendingStart(it.entry, it.kind, sessionLog.name) }
        }
        if (usePack) {
            startWithPack(runtime, spec, heap, profile.id, presence, sdl)
        } else {
            startWithOwn(runtime, spec, heap, profile.id, presence)
        }
    }

    /**
     * The pack on the phone gives the Java it has; a Java it lacks comes from the
     * network alone, instead of a second full runtime. The window layer and the
     * renderers come from the manifest: the pack inside the application is a
     * snapshot of its build day and never receives the fixes published later.
     * It stays the fallback for a phone that cannot reach the manifest.
     */
    private suspend fun runtimeFor(requirement: JavaRequirement, rendererId: String): InstalledRuntime {
        val installer = container.runtimeInstaller()
        val fitting = RuntimeImport.installed(container.layout, requirement.minimum, rendererId, requirement.maximum)
        val pack = fitting ?: RuntimeImport.installed(container.layout, 0, rendererId)
        if (pack == null) {
            return installer.install(
                javaMajor = requirement.minimum,
                abi = container.runner.abi,
                rendererId = rendererId.ifEmpty { null },
                javaMaximum = requirement.maximum,
            ) { transfer -> runtimeProgress(transfer) }
        }
        val withJava = fitting ?: withFetchedJava(pack, requirement, rendererId)
        if (RuntimeImport.pinned(container.layout)) return withJava
        // What is already unpacked is used without a word to the network: a start of a game
        // that is on the phone must not wait for a server, least of all without a connection.
        installer.installedLayer(rendererId.ifEmpty { null })?.let { return withJava.withLayer(it) }
        if (!optionalFetchDue()) return withJava
        return try {
            withJava.withLayer(installer.installLayer(rendererId.ifEmpty { null }) { transfer -> runtimeProgress(transfer) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            postponeOptionalFetch()
            GameSession.note(sessionLog, "оконный слой и рендереры из сети не получены (${failure.message}) — запускаем на тех, что в пакете рантайма")
            withJava
        }
    }

    /**
     * The pack lacks the Java this version asks for. A ceiling that is not strict is a
     * preference: when that Java cannot be fetched now, the game starts on the pack's own
     * Java as it always did, instead of not starting.
     */
    private suspend fun withFetchedJava(pack: InstalledRuntime, requirement: JavaRequirement, rendererId: String): InstalledRuntime {
        val installer = container.runtimeInstaller()
        installer.installedJava(requirement.minimum, container.runner.abi, requirement.maximum)
            ?.let { return pack.copy(javaHome = it.home, javaMajor = it.major) }
        val relaxed = if (requirement.strict) null else RuntimeImport.installed(container.layout, requirement.minimum, rendererId)
        if (relaxed != null && !optionalFetchDue()) return relaxed
        GameSession.note(sessionLog, "в пакете рантайма нет нужной Java (${requirement.describe()}), берём её из сети")
        return try {
            val java = installer.installJava(
                javaMajor = requirement.minimum,
                abi = container.runner.abi,
                javaMaximum = requirement.maximum,
            ) { transfer -> runtimeProgress(transfer) }
            pack.copy(javaHome = java.home, javaMajor = java.major)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            if (relaxed == null) throw failure
            postponeOptionalFetch()
            GameSession.note(sessionLog, "${requirement.describe()} из сети не получена (${failure.message}) — запускаем на Java ${relaxed.javaMajor} из пакета рантайма")
            relaxed
        }
    }

    private fun optionalFetchDue(): Boolean = System.currentTimeMillis() >= prefs.runtimeFetchRetryAt

    private fun postponeOptionalFetch() {
        prefs.runtimeFetchRetryAt = System.currentTimeMillis() + OPTIONAL_FETCH_RETRY_MS
    }

    private suspend fun withSuitableRenderer(
        runtime: InstalledRuntime,
        gameVersion: String,
        releaseTime: String,
        requested: String,
        failedAtInit: Set<String>,
        sodium: Boolean,
        sdl: Boolean,
    ): InstalledRuntime {
        val declared = if (RuntimeImport.owns(container.layout, runtime.rendererDir)) {
            RuntimeImport.renderers(container.layout).map { RendererCatalog.asEntry(it) }
        } else {
            runCatching { container.runtimeInstaller().manifestForLaunch().renderers }
                .getOrDefault(emptyList())
                .filter { it.folder == runtime.rendererDir.name }
        }
        val offered = RendererCatalog.offered(declared.ifEmpty { listOf(runtime.renderer) }, runtime.rendererDir, debugTurnipDirs(getApplication()))
        val graphics = withContext(Dispatchers.Default) { GraphicsProbe.read(getApplication<Application>()) }
        return when (val verdict = RendererChoice.choose(gameVersion, releaseTime, graphics, offered, requested, failedAtInit, sodium, sdl)) {
            is RendererVerdict.Refused -> throw IllegalStateException(verdict.message)
            is RendererVerdict.Use -> {
                GameSession.note(
                    sessionLog,
                    "рендерер ${verdict.renderer.id} (${verdict.reason}), GPU ${graphics.gpu}, Vulkan 1.1: ${graphics.vulkan11}",
                )
                if (verdict.note.isNotEmpty()) showNotice(verdict.note)
                otherRenderers = RendererChoice.alternatives(
                    gameVersion, releaseTime, graphics, offered, verdict.renderer.id, failedAtInit + verdict.renderer.id, sodium, sdl,
                )
                runtime.copy(renderer = verdict.renderer)
            }
        }
    }

    private fun sodiumFamily(directory: File): Boolean =
        runCatching { SodiumFamily.present(container.content.list(directory, ContentKind.MOD)) }
            .onFailure { GameSession.note(sessionLog, "моды сборки не прочитались, Sodium не ищем: ${it.message}") }
            .getOrDefault(false)

    private suspend fun sdlComponent(): InstalledSdl {
        stage(2, tr("Готовим SDL3"))
        val sdl = container.runtimeInstaller().installSdl { transfer -> runtimeProgress(transfer) }
        GameSession.note(sessionLog, "SDL3 ${sdl.sdlVersion}, LWJGL ${sdl.lwjglVersion}: ${sdl.dir.absolutePath}")
        return sdl
    }

    private fun runtimeProgress(transfer: TransferProgress) {
        val percent = if (transfer.totalBytes > 0) (transfer.doneBytes * 100 / transfer.totalBytes).toInt() else 0
        stage(2, tr("Скачиваем рантайм: {percent}%", percent))
    }

    // Without the agent a Millida session silently plays offline: no multiplayer and no skin.
    private suspend fun authlibAgent(): File = try {
        AuthlibAgent(container.layout, Downloader(attempts = AGENT_ATTEMPTS)).ensure()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        GameSession.note(sessionLog, "вход Millida: ${error.message}")
        throw IllegalStateException(
            tr("Не скачался файл входа Millida — без него не будет скина и игры по сети. Проверь интернет и нажми «Ещё раз»"),
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

        stage(4, tr("Готовим оконный слой"))
        val surface = awaitSurface()
        layer.prepare(spec.gameDir, surface.width, surface.height)
        bindSurface()

        gameStarted(profileId, presence)
        syncWindowSize()
        val code = onDedicatedThread {
            layer.start(spec, heap, surface.width, surface.height, bridgeJars)
        }
        finish(code == 0, tr("Код выхода {code}", code), "exit_$code")
    }

    private suspend fun startWithOwn(
        runtime: InstalledRuntime,
        spec: LaunchSpec,
        heap: Int,
        profileId: String,
        presence: Presence,
    ) {
        val prepared = withAppBridge(runtime)
        stage(4, tr("Ждём поверхность отрисовки"))
        awaitSurface()
        runCatching { bindSurface() }
            .onFailure { GameSession.note(sessionLog, "поверхность не отдана: ${it.message}") }
        inputSink.focus(true)

        gameStarted(profileId, presence)
        syncWindowSize()
        val ok = container.runner.start(spec, prepared, heap, sessionLog)
        finish(ok, tr("Игра закрылась с ошибкой"), "exit_error")
    }

    private fun gameStarted(profileId: String, presence: Presence) {
        state.update {
            it.copy(stage = LAUNCH_STAGES, note = tr("Запускаем игру"), running = true, started = true, booting = true)
        }
        beginSession(profileId, presence)
        facts?.let { telemetry.gameLaunched(it, SystemClock.elapsedRealtime() - launchBegan, System.currentTimeMillis()) }
        watchFirstFrame()
    }

    private suspend fun finish(ok: Boolean, reason: String, exitCode: String) {
        if (!ended.compareAndSet(false, true)) return
        val leaving = !forced && state.value.closing && ok
        val cause = settle(ok, exitCode, awaitBeat = leaving)
        if (forced) return
        state.update {
            it.copy(
                running = false,
                booting = false,
                finished = true,
                note = if (ok) tr("Игра закрылась") else tr("Игра закрылась с ошибкой"),
                error = if (ok) null else reason,
                cause = cause,
                retryHelps = retryHelps,
                // Asked to close and it did: the screen leaves instead of showing a panel.
                closed = it.closing && ok,
            )
        }
    }

    /** The end of the game written down: playtime, presence, statistics, the renderer's record. Answers with what the crash looks like. */
    private suspend fun settle(ok: Boolean, exitCode: String, awaitBeat: Boolean): String? {
        sizeSync?.cancel()
        val hadSession = endSession()
        exited.complete(Unit)
        if (!forced) reportEnd(ok, exitCode)
        if (hadSession) {
            if (awaitBeat) lastBeat() else viewModelScope.launch(Dispatchers.IO) { lastBeat() }
        }
        if (forced) return null
        val cause = if (ok) null else crashCause()
        retryHelps = ok || !(cause != null && RendererFallback.MISSING_GL_FUNCTION in logTailSafe() && otherRenderers == 0)
        withContext(Dispatchers.IO) {
            if (!ok) rememberInitFailure()
            prefs.rendererPendingStart = ""
        }
        return cause
    }

    /**
     * The Java machine exited or aborted (see [ExitTrap]). Runs on the thread that ended
     * the game and holds it until the end is written down: the process is ended right
     * after, so nothing here may be left to a coroutine that outlives the call.
     */
    private fun trapped(code: Int, signal: Boolean): GameResult? {
        if (!ended.compareAndSet(false, true)) return null
        val asked = state.value.closing
        val end = GameEnd.of(code, signal, crashReported = crashReportHead().isNotEmpty())
        GameSession.note(sessionLog, "игра завершилась сама: ${end.code}")
        // An abort proves nothing about the interceptor: it would end a start it broke the same way.
        if (!signal) GameGuards(getApplication()).confirmExitHook()
        state.update { it.copy(running = false, booting = false, closing = true, note = tr("Закрываем игру")) }
        val settled = runBlocking {
            withTimeoutOrNull(EXIT_SETTLE_MS) { Settled(settle(end.ok, end.code, awaitBeat = true), retryHelps) }
        }
        if (settled == null) {
            GameSession.note(sessionLog, "конец игры не успел записаться за ${EXIT_SETTLE_MS / 1000} с")
            return null
        }
        // The process is ended on purpose from here on: without the mark that reads as a kill.
        GameExitMark.set(getApplication())
        // The player closing the game gets no crash panel, whatever code the game left with.
        if (end.ok || asked || forced) return null
        return GameResult(
            error = if (signal) tr("Игра закрылась с ошибкой") else tr("Код выхода {code}", code),
            cause = settled.cause,
            retryHelps = settled.retryHelps,
            logName = sessionLog.name,
            build = state.value.buildName,
            openedAt = openedAt,
            sdl = state.value.sdlWindow,
        )
    }

    private class Settled(val cause: String?, val retryHelps: Boolean)

    /** The player closing the game is an exit, not a crash, even when its process has to be ended. */
    private suspend fun reportEnd(ok: Boolean, exitCode: String) {
        val masks = listOf(nick to "<nick>", state.value.buildName to "<build>")
        val log = if (ok) null else runCatching { ReportRedaction.mask(logTail(), masks) }.getOrNull()
        val p50 = fpsStats.percentile(50)
        if (p50 != null) {
            facts = facts?.copy(fpsP50 = p50, fpsP10 = fpsStats.percentile(10), fpsN = fpsStats.n)
            facts?.let { telemetry.gameFpsSeen(it) }
        }
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

    /**
     * The previous start left its record behind only if its process died before it could
     * finish: an abort in the renderer is read from that start's own log.
     */
    private fun settlePendingStart() {
        val pending = prefs.rendererPendingStart
        if (pending.isEmpty()) return
        prefs.rendererPendingStart = ""
        val logs = GameSession.logsDir(container.layout.runtimes)
        val failed = RendererFallback.diedAtInit(pending) { name ->
            runCatching { GameSession.tail(File(logs, File(name).name), PENDING_LOG_LINES) }.getOrDefault("")
        } ?: return
        prefs.rendererInitFailures = prefs.rendererInitFailures + failed
        GameSession.note(sessionLog, "прошлый запуск оборвался в рендерере — автовыбор возьмёт другой")
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

    @Volatile private var retryHelps = true

    private fun logTailSafe(): String = runCatching { logTail() }.getOrDefault("")

    private fun crashCause(): String? {
        val log = runCatching { logTail() }.getOrDefault("")
        if (RendererFallback.MISSING_GL_FUNCTION in log && otherRenderers == 0) {
            return tr("Сборке не хватило функции графики на этом телефоне. Повтор не поможет — отправь отчёт, мы доработаем")
        }
        return CrashHints.missingMods(log) ?: CRASH_CAUSES.firstOrNull { (marker, _) -> log.contains(marker) }?.second
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
            ?: throw IllegalStateException(tr("экран игры не открылся — вернись в игру и нажми «Ещё раз»"))

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
                    throw IOException(tr("оконный мост не обновился"))
                }
            }
            target
        }.getOrNull()
        if (copied == null || !target.isFile) return runtime

        val rest = runtime.bridgeClasspath.filterNot { it.name.startsWith("millida-glfw") }
        return runtime.copy(bridgeClasspath = listOf(target) + rest)
    }

    override fun onCleared() {
        screenWatch.stop()
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
        const val SHARED_LOG_LINES = 5_000
        const val SIZE_SYNC_CONFIRM_MS = 700L
        const val FIRST_FRAME_POLL_MS = 250L
        const val FPS_WARMUP_MS = 15_000L
        const val FPS_SAMPLE_MS = 1_000L
        const val FPS_PUSH_EVERY = 30
        const val LATE_FRAME_WAIT_MS = 10 * 60_000L
        const val LATE_FRAME_POLL_MS = 1_000L
        const val PICTURE_POLL_MS = 500L
        const val EXIT_SETTLE_MS = 6_000L
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

        const val PENDING_LOG_LINES = 400
        const val OPTIONAL_FETCH_RETRY_MS = 6L * 60 * 60 * 1000
        val CRASH_CAUSES get() = listOf(
            RendererFallback.MISSING_GL_FUNCTION to tr("Рендерер не дал игре нужную функцию графики — запусти ещё раз, лаунчер возьмёт другой рендерер. Если рендерер выбран в настройках вручную, смени его там"),
            "java.lang.OutOfMemoryError" to tr("Игре не хватило памяти — выключи часть модов или дай сборке больше памяти"),
            "Could not reserve enough space" to tr("Java не получила память — поставь сборке меньше памяти"),
            "UnsatisfiedLinkError" to tr("Не загрузилась часть рантайма — переустанови его в настройках"),
            "Failed to fetch metadata" to tr("Игра не достучалась до входа Millida — проверь интернет"),
        )
    }
}
