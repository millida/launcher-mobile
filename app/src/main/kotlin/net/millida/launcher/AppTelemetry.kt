package net.millida.launcher

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.profile.ProfileStore
import net.millida.launcher.core.telemetry.AnrTrace
import net.millida.launcher.core.telemetry.BeatState
import net.millida.launcher.core.telemetry.BlackScreenClue
import net.millida.launcher.core.telemetry.DeviceFacts
import net.millida.launcher.core.telemetry.EventData
import net.millida.launcher.core.telemetry.AbortClue
import net.millida.launcher.core.telemetry.FailureKind
import net.millida.launcher.core.telemetry.LaunchFacts
import net.millida.launcher.core.telemetry.OpenGame
import net.millida.launcher.core.telemetry.TelemetryClient
import net.millida.launcher.core.telemetry.TelemetryCore
import net.millida.launcher.core.telemetry.TelemetryDevice
import net.millida.launcher.core.telemetry.TelemetryPrivacy
import net.millida.launcher.core.telemetry.TelemetryStore
import net.millida.launcher.core.telemetry.TelemetryType
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's side of the desktop telemetry: what the phone is, and the
 * moments the launcher and the game report. The launcher process and the game
 * process each hold one; they share the queue and the install id on disk.
 */
class AppTelemetry(
    private val context: Context,
    auth: MillidaAuth,
    private val profiles: ProfileStore,
    private val probeGpu: Boolean,
) {
    val store = TelemetryStore(File(context.filesDir, FOLDER))
    private val sessionId = UUID.randomUUID().toString()
    // One at a time and in order: a game's end must not overtake the record of its start.
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private val started = AtomicBoolean(false)
    private val exited = AtomicBoolean(false)

    @Volatile
    private var base: TelemetryDevice? = null

    @Volatile
    private var startedAt = 0L

    val core = TelemetryCore(store, TelemetryClient(auth), ::device, BuildConfig.VERSION_NAME)

    val enabled: Boolean get() = store.enabled

    fun setEnabled(on: Boolean) {
        store.setEnabled(on)
        if (on) scope.launch { core.flush() }
    }

    fun track(type: String, data: Map<String, JsonPrimitive> = emptyMap(), durationMs: Long? = null, ok: Boolean? = null) {
        scope.launch { runCatching { core.track(type, data, durationMs, ok) }.onFailure(::logFailure) }
    }

    fun failure(where: String, error: Throwable, extra: Map<String, JsonPrimitive> = emptyMap()) {
        scope.launch { runCatching { core.trackFailure(where, error, extra) }.onFailure(::logFailure) }
    }

    fun flushSoon() {
        scope.launch { runCatching { core.flush() }.onFailure(::logFailure) }
    }

    suspend fun flush() {
        runCatching { core.flush() }.onFailure(::logFailure)
    }

    /** One `app_start` per launcher process, its duration the time from the process start to a ready screen. */
    fun appStarted() {
        if (!started.compareAndSet(false, true)) return
        startedAt = System.currentTimeMillis()
        val bootMs = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()).coerceAtLeast(0)
        track(TelemetryType.APP_START, durationMs = bootMs)
        flushSoon()
    }

    fun gameLaunched(facts: LaunchFacts, tookMs: Long, startedAt: Long) {
        scope.launch {
            runCatching {
                core.track(TelemetryType.GAME_LAUNCH, facts.data(), tookMs, ok = true)
                store.openGame(OpenGame(startedAt, startedAt, facts))
                core.flush()
            }.onFailure(::logFailure)
        }
    }

    /** The game drew its first frame: the open game keeps it, so a later crash says whether it was a black screen. */
    fun gameFrameReached(facts: LaunchFacts) {
        scope.launch { runCatching { store.updateGameFacts(facts) }.onFailure(::logFailure) }
    }

    /** The frame statistics at the game's end, written before the exit is reported (in order, unlike [gameFrameReached]). */
    suspend fun gameFpsSeen(facts: LaunchFacts) {
        withContext(serial) { runCatching { store.updateGameFacts(facts) }.onFailure(::logFailure) }
    }

    /**
     * Black screen: the game runs, its first minute passed, it drew nothing and its log went quiet
     * (or the far limit came). No crash follows, so this event is the only trace: the renderer, how
     * long the start waited and how long the log has been silent, and the lines of the game log that
     * name the cause. The server keeps 300 characters only under `detail`, hence the key.
     */
    fun gameBlackScreen(facts: LaunchFacts, log: String?, waitedMs: Long, quietMs: Long, code: String = BLACK_NO_FRAME) {
        val data = EventData()
            .put("where", "black_screen")
            .put("kind", "graphics")
            .put("code", code)
            .put("detail", BlackScreenClue.of(log))
            .put("waitS", waitedMs / 1000)
            .put("quietS", quietMs / 1000)
            .putAll(facts.data())
            .build()
        track(TelemetryType.ERROR, data, ok = false)
        flushSoon()
    }

    /**
     * The screen thread of the game process did not answer for [stuckMs]: the call it stood in and
     * the stack under it. Sent at once — the system's «не отвечает» dialog and a kill may follow.
     */
    fun mainThreadStuck(facts: LaunchFacts?, phase: String, stuckMs: Long, trace: Array<StackTraceElement>) {
        val data = EventData()
            .put("where", "main_stuck")
            .put("kind", "anr")
            .put("code", AnrTrace.culprit(trace))
            .put("detail", AnrTrace.ofThread(trace))
            .put("phase", phase)
            .put("ms", stuckMs)
            .putAll(facts?.data().orEmpty())
            .build()
        track(TelemetryType.ERROR, data, ok = false)
        flushSoon()
    }

    /** As the desktop: a failed `game_launch` with the stage it stopped at, and the failure itself as `error`. */
    fun gameLaunchFailed(facts: LaunchFacts?, stage: String, error: Throwable, tookMs: Long?) {
        if (error is CancellationException) return
        val text = error.message ?: error.javaClass.simpleName
        val data = EventData()
            .put("code", TelemetryPrivacy.errorCode(text))
            .put("kind", FailureKind.of(error))
            .put("stage", stage)
            .putAll(facts?.data().orEmpty())
            .build()
        scope.launch {
            runCatching {
                core.track(TelemetryType.GAME_LAUNCH, data, tookMs, ok = false)
                core.trackFailure("launch", error, EventData().put("stage", stage).build())
                core.flush()
            }.onFailure(::logFailure)
        }
    }

    /** The launcher screen is gone for good; the next start of the process is a new session. */
    fun appExited() {
        if (startedAt == 0L || !exited.compareAndSet(false, true)) return
        track(TelemetryType.APP_EXIT, durationMs = System.currentTimeMillis() - startedAt)
        flushSoon()
    }

    /** A guest's heartbeat goes on its own; a signed-in launcher's rides inside the presence beat. */
    suspend fun beatAlone(state: BeatState, build: String? = null, mc: String? = null, server: String? = null) {
        val payload = runCatching { core.beat(state, build, mc, server) }.getOrNull() ?: return
        runCatching { core.sendBeat(payload) }.onFailure { Log.w(TAG, "удар лаунчера не ушёл: ${it.message}") }
    }

    fun beatPayload(state: BeatState, build: String? = null, mc: String? = null, server: String? = null): JsonObject? =
        runCatching { core.beat(state, build, mc, server, force = true) }.getOrNull()

    fun gameAlive() {
        runCatching { store.touchGame(System.currentTimeMillis()) }.onFailure(::logFailure)
    }

    /**
     * The game's own end: `game_exit` with the time played, and `game_crash`
     * when it ended with an error, as the desktop reports a process exit.
     */
    suspend fun gameEnded(ok: Boolean, reason: String, log: String?) {
        withContext(serial) {
            runCatching {
                val game = store.takeGame() ?: return@runCatching
                recordEnd(game, System.currentTimeMillis(), ok, reason, log)
            }.onFailure(::logFailure)
        }
    }

    /**
     * A game process the system killed or a native crash took down never
     * reports its end. The launcher finds its record still open with no game
     * process alive and reports it as a crash, timed to its last sign of life.
     */
    fun settleLostGame(
        gameAlive: Boolean,
        nativeCrashAt: Long?,
        onCleaned: () -> Unit = {},
        sessionLog: (since: Long) -> String?,
    ) {
        scope.launch {
            runCatching {
                if (gameAlive || store.peekGame() == null) return@runCatching
                val game = store.takeGame() ?: return@runCatching
                val quit = GameExitMark.all(context).filter { it >= game.startedAt }.maxOrNull()
                if (quit != null) {
                    recordEnd(game, quit, ok = true, reason = "quit", log = null)
                    core.flush()
                    return@runCatching
                }
                val native = nativeCrashAt != null && nativeCrashAt >= game.startedAt
                // The game's own System.exit after a crash report ends the process before it can
                // report; Android's exit record and the session log tell that apart from a kill.
                val exit = lastGameExit(game.startedAt)
                val log = runCatching { sessionLog(game.startedAt) }.getOrNull()
                if (!native && quitFromGame(exit?.reason, exit?.status, log)) {
                    recordEnd(game, game.aliveAt, ok = true, reason = "quit", log = null)
                    core.flush()
                    return@runCatching
                }
                if (!native && closedByPlayer(exit?.reason, exit?.description)) {
                    recordEnd(game, game.aliveAt, ok = true, reason = "swiped", log = null)
                    core.flush()
                    return@runCatching
                }
                if (!native && killedByCleaner(exit?.reason, exit?.description)) {
                    recordEnd(game, game.aliveAt, ok = true, reason = "cleaned", log = null)
                    core.flush()
                    onCleaned()
                    return@runCatching
                }
                val (reason, kind) = lostGameVerdict(native, exit?.reason, exit?.status, FailureKind.cause(log) != null)
                val clue = EventData().put("abort", exit?.abort).put("bg", exit?.background).build()
                recordEnd(game, game.aliveAt, ok = false, reason = reason, log = log, kind = kind, extra = clue)
                core.flush()
            }.onFailure(::logFailure)
        }
    }

    /**
     * Android's own record of why the game process ended: low memory, a
     * signal, a native crash. It outlives the process, so an exit the launcher
     * missed is still reported, once, the next time the launcher opens.
     */
    fun reportGameExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        scope.launch {
            runCatching {
                val seen = File(context.filesDir, "$FOLDER/$EXITS_SEEN")
                val since = runCatching { seen.readText().trim().toLong() }.getOrDefault(0L)
                val manager = context.getSystemService(ActivityManager::class.java)
                val leaving = GameExitMark.all(context)
                val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
                    .filter { it.processName.endsWith(GAME_PROCESS) && it.timestamp > since && !it.isCleanExit(leaving) }
                if (exits.isEmpty()) return@runCatching
                exits.forEach { exit -> core.track(TelemetryType.ERROR, exitData(exit), ok = false) }
                seen.writeText(exits.maxOf { it.timestamp }.toString())
                core.flush()
            }.onFailure(::logFailure)
        }
    }

    private class GameExit(val reason: Int, val status: Int, val description: String?, val abort: String?, val background: Boolean)

    /** The newest exit of the game process since [since], as Android's reason, status and wording. */
    private fun lastGameExit(since: Long): GameExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java)
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.processName.endsWith(GAME_PROCESS) && it.timestamp >= since }
            .maxByOrNull { it.timestamp }
            ?.let { GameExit(it.reason, it.status, it.description, abortClue(it), inBackground(it.importance)) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ApplicationExitInfo.isCleanExit(leaving: List<Long>): Boolean = when {
        reason == ApplicationExitInfo.REASON_EXIT_SELF && status == 0 -> true
        reason == ApplicationExitInfo.REASON_SIGNALED && status == SIGKILL ->
            leaving.any { mark -> timestamp in mark..mark + GameExitMark.WINDOW_MS }
        else -> false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitData(exit: ApplicationExitInfo): Map<String, JsonPrimitive> = EventData()
        .put("where", "game_process_exit")
        .put("kind", exitReason(exit.reason))
        .put("status", exit.status)
        .put("description", exit.description)
        .put("importance", exit.importance)
        .put("pssMb", exit.pss / KB_PER_MB)
        .put("rssMb", exit.rss / KB_PER_MB)
        .put("exitAt", exit.timestamp)
        .put("stack", anrStack(exit))
        .put("abort", abortClue(exit))
        .put("bg", inBackground(exit.importance))
        .build()

    /**
     * The game process was out of the player's sight when it ended (a cached or service process;
     * 100 is the foreground, 200 visible). Aborts on a lost window surface happen right after
     * the launcher goes to the background, and the log of the game never says so.
     */
    private fun inBackground(importance: Int): Boolean = importance >= IMPORTANCE_BACKGROUND

    /** The abort message and top frames of a native crash, from the tombstone Android keeps, see [AbortClue]. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun abortClue(exit: ApplicationExitInfo): String? {
        val native = exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            (exit.reason == ApplicationExitInfo.REASON_SIGNALED && exit.status == SIGABRT)
        if (!native) return null
        return runCatching {
            exit.traceInputStream?.use { stream -> AbortClue.summary(readHead(stream, ANR_DUMP_HEAD)) }
        }.getOrNull()
    }

    private fun readHead(stream: java.io.InputStream, limit: Int): ByteArray {
        val head = ByteArray(limit)
        var read = 0
        while (read < head.size) {
            val n = stream.read(head, read, head.size - read)
            if (n <= 0) break
            read += n
        }
        return head.copyOf(read)
    }

    /** Where the main thread stood in an ANR: the system keeps the thread dump, see [AnrTrace]. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun anrStack(exit: ApplicationExitInfo): String? {
        if (exit.reason != ApplicationExitInfo.REASON_ANR) return null
        return runCatching {
            exit.traceInputStream?.use { stream -> AnrTrace.mainStack(String(readHead(stream, ANR_DUMP_HEAD), Charsets.UTF_8)) }
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "reason_$reason"
    }

    private fun recordEnd(game: OpenGame, endedAt: Long, ok: Boolean, reason: String, log: String?, kind: String? = null, extra: Map<String, JsonPrimitive> = emptyMap()) {
        val played = (endedAt - game.startedAt).coerceAtLeast(0)
        core.track(TelemetryType.GAME_EXIT, game.facts.exitData(), played)
        if (ok) return
        val cause = FailureKind.cause(log)
        val data = EventData()
            .put("code", reason)
            .put("kind", kind ?: FailureKind.of(FailureKind.kindSource(log, cause)))
            .put("cause", cause)
            .putAll(game.facts.data())
            .putAll(extra)
            .build()
        core.track(TelemetryType.GAME_CRASH, data, ok = false)
    }

    /** Blocking: reads files and may probe the GPU once, so only from a background thread. */
    fun device(): TelemetryDevice {
        val known = base ?: facts().also { base = it }
        val builds = runCatching { profiles.load() }.getOrDefault(emptyList())
        val mods = builds.sumOf { profile ->
            File(profiles.directory(profile), "mods").listFiles { file -> file.extension == "jar" }?.size ?: 0
        }
        return known.copy(
            buildsCount = builds.size,
            modsCount = mods,
            themeMode = DeviceFacts.themeMode(Prefs(context).themeMode),
        )
    }

    private fun facts(): TelemetryDevice {
        val cores = DeviceFacts.cpuCount(runCatching { File(CPU_POSSIBLE).readText() }.getOrNull())
            ?: Runtime.getRuntime().availableProcessors()
        val chip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DeviceFacts.chip(Build.SOC_MANUFACTURER, Build.SOC_MODEL, Build.HARDWARE, Build.BOARD)
        } else {
            DeviceFacts.chip(null, null, Build.HARDWARE, Build.BOARD)
        }
        return TelemetryDevice(
            installId = store.installId(),
            sessionId = sessionId,
            appVersion = BuildConfig.VERSION_NAME,
            osVersion = DeviceFacts.osVersion(Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
            arch = DeviceFacts.arch(Build.SUPPORTED_ABIS.toList()),
            locale = DeviceFacts.locale(Locale.getDefault().toLanguageTag()),
            timezone = TimeZone.getDefault().id?.take(TIMEZONE_MAX),
            cpu = chip,
            cpuCores = cores,
            cpuThreads = cores,
            ramMb = totalMemoryMb(),
            gpu = gpu(),
            screen = screen(),
            deviceVendor = DeviceFacts.vendor(Build.MANUFACTURER),
            deviceModel = DeviceFacts.model(Build.MODEL),
            soc = DeviceFacts.platform(Build.HARDWARE, Build.BOARD),
        )
    }

    private fun totalMemoryMb(): Int? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return (info.totalMem / (1024 * 1024)).toInt().takeIf { it > 0 }
    }

    private fun screen(): String? {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return DeviceFacts.screen(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    /**
     * Measured once per system build. The attempt is written down before the
     * probe: a driver that kills the process on it is not probed again on every start.
     */
    private fun gpu(): String? {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val cached = store.cached(GPU_KEY, fingerprint)
        if (cached != null || !probeGpu) return cached?.ifEmpty { null }
        store.cache(GPU_KEY, fingerprint, "")
        val label = GlProbe.read()?.let { (vendor, renderer) -> DeviceFacts.gpu(vendor, renderer) }.orEmpty()
        store.cache(GPU_KEY, fingerprint, label)
        return label.ifEmpty { null }
    }

    private fun logFailure(error: Throwable) {
        Log.w(TAG, "телеметрия: ${error.message}")
    }

    private companion object {
        /** The "main" thread stands near the top of an ANR dump: its first part is enough. */
        const val ANR_DUMP_HEAD = 256 * 1024
        const val TAG = "millida-telemetry"
        const val FOLDER = "telemetry"
        const val GPU_KEY = "gpu"
        const val EXITS_SEEN = "game-exits-seen"
        const val GAME_PROCESS = ":game"
        const val KB_PER_MB = 1024
        const val SIGKILL = 9
        const val SIGABRT = 6
        const val IMPORTANCE_BACKGROUND = 230
        const val CPU_POSSIBLE = "/sys/devices/system/cpu/possible"
        const val TIMEZONE_MAX = 64
    }
}

/** The GPU's own name, read from an off-screen GLES context that lives for one call. */
private object GlProbe {
    fun read(): Pair<String?, String?>? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val wanted = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
        val config = configs[0] ?: return null
        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) return null
        val surface = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        // The default display stays initialised: the system's own drawing shares it in this process.
        return try {
            if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                null
            } else {
                GLES20.glGetString(GLES20.GL_VENDOR) to GLES20.glGetString(GLES20.GL_RENDERER)
            }
        } catch (unavailable: RuntimeException) {
            null
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }
}

/**
 * The game process ends itself with a kill signal when its screen closes (a
 * desktop virtual machine cannot be unloaded), and Android records that the
 * same way as a kill by the system. The mark written just before tells the two
 * apart, so a player quitting the game is not counted as a crash.
 */
/** `code` of a black screen event: the game handed over no frame at all. */
internal const val BLACK_NO_FRAME = "no_frame"

/** `code` of a black screen event: frames keep coming and every one of them is black. */
internal const val BLACK_PICTURE = "black_picture"

object GameExitMark {
    const val WINDOW_MS = 5_000L
    private const val FILE = "telemetry/game-leaving"
    private const val KEPT = 20

    fun set(context: Context) {
        runCatching {
            val file = File(context.filesDir, FILE)
            file.parentFile?.mkdirs()
            val marks = (all(context) + System.currentTimeMillis()).takeLast(KEPT)
            file.writeText(marks.joinToString("\n"))
        }
    }

    fun all(context: Context): List<Long> = runCatching {
        File(context.filesDir, FILE).readLines().mapNotNull { it.trim().toLongOrNull() }
    }.getOrDefault(emptyList())
}

/**
 * «Quit Game» in the game's own menu ends the process with code 0 before the launcher
 * hears of it. That is the player leaving, not a crash, unless the game wrote a crash
 * report first: some versions exit with 0 after one.
 */
internal fun quitFromGame(reason: Int?, status: Int?, log: String?): Boolean =
    reason == EXIT_REASON_SELF && status == 0 && CRASH_REPORT_MARK !in log.orEmpty()

private const val CRASH_REPORT_MARK = "---- Minecraft Crash Report ----"

/**
 * The player closing the game through the system instead of its menu: a swipe out of the
 * recent apps, a phone's «Очистить всё», a force stop in settings. On 13.10.2026 these were
 * two thirds of the starts counted as «killed» (22 of 29 installs on 1.9.22–1.9.24 in six
 * hours: SwipeUpClean, OneKeyClean, [REMOVE TASK], [STOP APP]) and put some nine points on
 * the crash share. A kill the player chose in the «не отвечает» dialog has the ANR reason
 * and stays a crash.
 */
internal fun closedByPlayer(reason: Int?, description: String?): Boolean = when (reason) {
    EXIT_REASON_USER_REQUESTED, EXIT_REASON_USER_STOPPED, EXIT_REASON_PACKAGE_UPDATED -> true
    EXIT_REASON_OTHER -> PLAYER_CLEANERS.any { description.orEmpty().contains(it, ignoreCase = true) }
    else -> false
}

/** How the phone makers' task cleaners sign the kill of a swiped-away app. */
private val PLAYER_CLEANERS = listOf("SwipeUpClean", "Swipe-up clean", "swipUpClean", "OneKeyClean", "remove task")

/**
 * The phone makers' memory cleaners ending the game in the background. 2 373 «process_died» of the week
 * 19-26.10.2026 were mostly these, on OnePlus 16%, Lenovo 17%, OPPO 13% of starts (others 2-4%): the
 * system took the game away from a player who had looked at another app, which is not a crash of the
 * game. The player is told how to stop it, see [onCleaned].
 */
internal fun killedByCleaner(reason: Int?, description: String?): Boolean =
    reason == EXIT_REASON_OTHER && OEM_CLEANERS.any { description.orEmpty().contains(it, ignoreCase = true) }

private val OEM_CLEANERS = listOf(
    "o-kill", "o-stop", // OPPO, realme, OnePlus (ColorOS/OxygenOS)
    "ZuiMemoryCleaner", "single-cleaner", // Lenovo ZUI
    "GarbageClean", "OptimizationClean", // Xiaomi
    "background restricted", "too many empty", // the system's own limit on cached processes
)

/**
 * What a game that ended without reporting is counted as. Android's reasons
 * are passed as numbers (ApplicationExitInfo.REASON_*), so the rule stays a
 * plain function. A null kind lets the session log decide.
 */
internal fun lostGameVerdict(native: Boolean, reason: Int?, status: Int?, logHasCause: Boolean): Pair<String, String?> = when {
    native || reason == EXIT_REASON_CRASH_NATIVE -> "native_crash" to "native_crash"
    reason == EXIT_REASON_SELF -> "exit_$status" to if (logHasCause) null else "game_exit"
    reason == EXIT_REASON_LOW_MEMORY -> "low_memory" to "out_of_memory"
    reason == EXIT_REASON_SIGNALED -> "signal_$status" to "killed"
    reason == EXIT_REASON_ANR -> "anr" to "anr"
    reason == EXIT_REASON_CRASH -> "crash" to if (logHasCause) null else "crash"
    else -> "process_died" to "killed"
}

private const val EXIT_REASON_SELF = 1
private const val EXIT_REASON_SIGNALED = 2
private const val EXIT_REASON_LOW_MEMORY = 3
private const val EXIT_REASON_CRASH = 4
private const val EXIT_REASON_CRASH_NATIVE = 5
private const val EXIT_REASON_ANR = 6
private const val EXIT_REASON_USER_REQUESTED = 10
private const val EXIT_REASON_USER_STOPPED = 11
private const val EXIT_REASON_OTHER = 13
private const val EXIT_REASON_PACKAGE_UPDATED = 16

