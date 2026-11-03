package net.millida.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import net.millida.launcher.core.game.DisplayModeInfo
import net.millida.launcher.core.game.DisplayModes

import net.millida.launcher.core.account.GameSession
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.runtime.GameKeepAliveService
import net.millida.launcher.ui.GameHostScreen
import net.millida.launcher.ui.MillidaTheme

/**
 * The game lives in its own process (":game" in the manifest). The desktop
 * virtual machine installs its own signal handlers over the ones the Android
 * runtime relies on, and inside the launcher process that took down the system
 * drawing threads. A separate process also means the virtual machine can be
 * created again after the player leaves the game, without restarting the app.
 */
class GameActivity : ComponentActivity() {

    private val model: GameLaunchModel by viewModels()

    /** "Play again" keeps the Bedrock translator: the next game connects through it too. */
    private var relaunching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)

        val request = readRequest(intent)
        if (request == null) {
            finish()
            return
        }

        ExitTrap.attach(this)
        val result = readResult(intent)
        if (result == null) GameKeepAliveService.start(this, tr("Игра запущена"))

        val prefs = Prefs(this)
        val themeMode = prefs.themeMode
        val accentId = prefs.accentId
        if (prefs.highRefresh) askFastestDisplayMode()
        applyGameCutout(window, prefs.gameUnderCutout)
        // Where the firmware supports it, the clocks stay at a level the phone can hold for an hour
        // instead of boosting and then throttling hard: steadier frames once the phone is warm.
        val power = getSystemService(android.os.PowerManager::class.java)
        if (power?.isSustainedPerformanceModeSupported == true) window.setSustainedPerformanceMode(true)
        // Keyboard insets reach Compose only when the window does not fit system windows itself.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val dark = when (themeMode) {
                Prefs.THEME_DARK -> true
                Prefs.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            MillidaTheme(dark = dark, accentId = accentId) {
                // Controls sit where the player placed them and the game draws its own screen: neither mirrors for Arabic or Urdu.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    GameHostScreen(
                        model = model,
                        onLeave = { finish() },
                        onRelaunch = ::relaunch,
                        onSwitchVersion = ::switchVersion,
                    )
                }
            }
        }

        if (result == null) model.start(request) else model.showResult(request, result)
    }

    @Suppress("DEPRECATION")
    private fun askFastestDisplayMode() {
        val display = runCatching { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) display else windowManager.defaultDisplay }
            .getOrNull() ?: return
        val modes = display.supportedModes.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val current = display.mode.let { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val wanted = DisplayModes.fastest(modes, current) ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = wanted }
    }

    /**
     * A second game cannot start in this process, so the relaunch goes through
     * a screen in the launcher process that waits for this one to end.
     */
    private fun relaunch() {
        // "Play again" from the screen of a crashed game starts the game, not that screen once more.
        val extras = intent?.extras?.let { Bundle(it) }?.apply { RESULT_EXTRAS.forEach { key -> remove(key) } }
        if (extras == null) {
            finish()
            return
        }
        relaunching = true
        runCatching { startActivity(Intent(this, GameRelaunchActivity::class.java).putExtras(extras)) }
            .onFailure { error ->
                relaunching = false
                Log.w(TAG, "повторный запуск не открылся: ${error.message}")
            }
        finish()
    }

    /**
     * The launcher process switches the build to [version], installs it and starts the game again;
     * this process is closed meanwhile. The launcher waits for it to end before it starts the next one.
     */
    private fun switchVersion(version: String) {
        val profile = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        if (profile.isEmpty()) return
        relaunching = true
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_SWITCH_PROFILE, profile)
                    .putExtra(EXTRA_SWITCH_VERSION, version),
            )
        }.onFailure { error ->
            relaunching = false
            Log.w(TAG, "лаунчер не открылся для смены версии: ${error.message}")
        }
        if (relaunching) model.stop()
    }

    /**
     * The game ended by itself and its process is about to be ended with it. After a
     * crash the launcher process is asked to open this screen anew in a fresh game
     * process, which shows what happened: this one cannot stay to do it.
     */
    fun end(result: GameResult?) {
        val extras = intent?.extras
        if (result != null && extras != null) {
            relaunching = true
            runCatching {
                startActivity(Intent(this, GameRelaunchActivity::class.java).putExtras(extras).putExtras(result.extras()))
            }.onFailure { error ->
                relaunching = false
                Log.w(TAG, "итог игры не открылся: ${error.message}")
            }
        }
        releaseServices()
        finish()
    }

    private fun releaseServices() {
        GameKeepAliveService.stop(this)
        if (!relaunching) BedrockProxyService.stop(this)
    }

    /**
     * The desktop virtual machine cannot be unloaded, so the process that ran it
     * has to end together with the screen: otherwise the next launch lands in a
     * process where a machine already exists, and the game never starts. The
     * services that only served this game go first, or the system would keep
     * them running for a game that is gone.
     */
    override fun onDestroy() {
        val leaving = isFinishing
        ExitTrap.detach(this)
        if (leaving) releaseServices()
        super.onDestroy()
        if (leaving) {
            GameExitMark.set(this)
            Process.killProcess(Process.myPid())
        }
    }

    private fun readRequest(intent: Intent?): LaunchIntent? {
        val profileId = intent?.getStringExtra(EXTRA_PROFILE) ?: return null
        return LaunchIntent(
            profileId = profileId,
            heapMb = intent.getIntExtra(EXTRA_HEAP, 0),
            windowWidth = intent.getIntExtra(EXTRA_WIDTH, 1280),
            windowHeight = intent.getIntExtra(EXTRA_HEIGHT, 720),
            serverAddress = intent.getStringExtra(EXTRA_SERVER).orEmpty(),
            packJvmArgs = intent.getStringArrayListExtra(EXTRA_PACK_JVM).orEmpty(),
            session = GameSession(
                nick = intent.getStringExtra(EXTRA_NICK).orEmpty(),
                uuid = intent.getStringExtra(EXTRA_UUID).orEmpty(),
                accessToken = intent.getStringExtra(EXTRA_TOKEN).orEmpty(),
                xuid = intent.getStringExtra(EXTRA_XUID).orEmpty(),
                yggdrasil = intent.getStringExtra(EXTRA_YGGDRASIL).orEmpty(),
            ),
        )
    }

    private fun readResult(intent: Intent?): GameResult? {
        val error = intent?.getStringExtra(EXTRA_RESULT_ERROR) ?: return null
        return GameResult(
            error = error,
            cause = intent.getStringExtra(EXTRA_RESULT_CAUSE),
            logName = intent.getStringExtra(EXTRA_RESULT_LOG).orEmpty(),
            build = intent.getStringExtra(EXTRA_RESULT_BUILD).orEmpty(),
            openedAt = intent.getLongExtra(EXTRA_RESULT_OPENED, 0L),
            sdl = intent.getBooleanExtra(EXTRA_RESULT_SDL, false),
            retryHelps = intent.getBooleanExtra(EXTRA_RESULT_RETRY, true),
        )
    }

    companion object {
        private const val TAG = "millida-game"
        internal const val EXTRA_RESULT_ERROR = "result-error"
        internal const val EXTRA_RESULT_CAUSE = "result-cause"
        internal const val EXTRA_RESULT_LOG = "result-log"
        internal const val EXTRA_RESULT_BUILD = "result-build"
        internal const val EXTRA_RESULT_OPENED = "result-opened"
        internal const val EXTRA_RESULT_SDL = "result-sdl"
        internal const val EXTRA_RESULT_RETRY = "result-retry"
        private val RESULT_EXTRAS = listOf(
            EXTRA_RESULT_ERROR, EXTRA_RESULT_CAUSE, EXTRA_RESULT_LOG, EXTRA_RESULT_BUILD, EXTRA_RESULT_OPENED, EXTRA_RESULT_SDL, EXTRA_RESULT_RETRY,
        )
        private const val EXTRA_PROFILE = "profile"
        const val EXTRA_SWITCH_PROFILE = "switch-profile"
        const val EXTRA_SWITCH_VERSION = "switch-version"
        private const val EXTRA_HEAP = "heap"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_NICK = "nick"
        private const val EXTRA_UUID = "uuid"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_XUID = "xuid"
        private const val EXTRA_YGGDRASIL = "yggdrasil"
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_PACK_JVM = "pack-jvm"

        fun intent(
            context: Context,
            profileId: String,
            session: GameSession,
            heapMb: Int,
            windowWidth: Int,
            windowHeight: Int,
            serverAddress: String = "",
            packJvmArgs: List<String> = emptyList(),
        ): Intent = Intent(context, GameActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_PROFILE, profileId)
            .putExtra(EXTRA_HEAP, heapMb)
            .putExtra(EXTRA_WIDTH, windowWidth)
            .putExtra(EXTRA_HEIGHT, windowHeight)
            .putExtra(EXTRA_NICK, session.nick)
            .putExtra(EXTRA_UUID, session.uuid)
            .putExtra(EXTRA_TOKEN, session.accessToken)
            .putExtra(EXTRA_XUID, session.xuid)
            .putExtra(EXTRA_YGGDRASIL, session.yggdrasil)
            .putExtra(EXTRA_SERVER, serverAddress)
            .putStringArrayListExtra(EXTRA_PACK_JVM, ArrayList(packJvmArgs))

        /**
         * The same game again, from the extras the previous start carried. A
         * pack's launch signature is issued for one start and does not travel.
         */
        fun again(context: Context, extras: Bundle): Intent? {
            if (extras.getString(EXTRA_PROFILE).isNullOrEmpty()) return null
            return Intent(context, GameActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtras(extras)
                .apply { removeExtra(EXTRA_PACK_JVM) }
        }
    }
}

/**
 * Lives in the launcher process, which outlasts the game one. It holds the
 * screen while the finished game process goes away and then starts the game
 * anew; starting it earlier would put the new game into the dying process.
 */
class GameRelaunchActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var waitedMs = 0L
    private var next: Intent? = null

    private val waitStep = object : Runnable {
        override fun run() {
            if (isFinishing) return
            if (GameProcess.alive(this@GameRelaunchActivity) && waitedMs < WAIT_MS) {
                waitedMs += POLL_MS
                handler.postDelayed(this, POLL_MS)
                return
            }
            next?.let { target ->
                runCatching { startActivity(target) }
                    .onFailure { Log.w(TAG, "игра не открылась повторно: ${it.message}") }
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent?.extras
        next = extras?.let { GameActivity.again(this, it) }
        if (next == null) {
            finish()
            return
        }
        handler.post(waitStep)
    }

    override fun onDestroy() {
        handler.removeCallbacks(waitStep)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "millida-game"
        const val POLL_MS = 100L
        const val WAIT_MS = 5_000L
    }
}

/** The separate process the game runs in, seen from the launcher process. */
object GameProcess {
    private const val SUFFIX = ":game"

    private fun pid(context: Context): Int? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val name = context.packageName + SUFFIX
        return manager.runningAppProcesses?.firstOrNull { it.processName == name }?.pid
    }

    fun alive(context: Context): Boolean = pid(context) != null

    /** Ends the game at once; its own screen cannot be asked to close from another process. */
    fun kill(context: Context): Boolean {
        val running = pid(context) ?: return false
        Process.killProcess(running)
        return true
    }
}

/**
 * A game that crashed, as the next process shows it: the dying one cannot hold the
 * screen, so its verdict travels through the launcher process with the start's extras.
 */
data class GameResult(
    val error: String,
    val cause: String?,
    /** The file name only: it is looked up in the logs folder, never opened as a path. */
    val logName: String,
    val build: String,
    val openedAt: Long,
    val sdl: Boolean,
    val retryHelps: Boolean = true,
) {
    fun extras(): Bundle = Bundle().apply {
        putString(GameActivity.EXTRA_RESULT_ERROR, error)
        putString(GameActivity.EXTRA_RESULT_CAUSE, cause)
        putString(GameActivity.EXTRA_RESULT_LOG, logName)
        putString(GameActivity.EXTRA_RESULT_BUILD, build)
        putLong(GameActivity.EXTRA_RESULT_OPENED, openedAt)
        putBoolean(GameActivity.EXTRA_RESULT_SDL, sdl)
        putBoolean(GameActivity.EXTRA_RESULT_RETRY, retryHelps)
    }
}

data class LaunchIntent(
    val profileId: String,
    val heapMb: Int,
    val windowWidth: Int,
    val windowHeight: Int,
    val session: GameSession,
    /** Filled when the player pressed "play" on a server: the game joins it at once. */
    val serverAddress: String = "",
    /** The launch signature of a protected pack, issued by our API for this one start. */
    val packJvmArgs: List<String> = emptyList(),
)

/**
 * Landscape windows stop at the camera cutout by default, which leaves a black
 * strip along one side; the player may give that strip to the game.
 */
fun applyGameCutout(window: android.view.Window, under: Boolean) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
    val mode = if (under) {
        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    } else {
        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
    }
    if (window.attributes.layoutInDisplayCutoutMode == mode) return
    window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = mode }
}
