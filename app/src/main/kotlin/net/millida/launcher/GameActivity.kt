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
import androidx.core.view.WindowCompat
import net.millida.launcher.core.account.GameSession
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

        GameKeepAliveService.start(this, "Игра запущена")

        val prefs = Prefs(this)
        val themeMode = prefs.themeMode
        val accentId = prefs.accentId
        // Keyboard insets reach Compose only when the window does not fit system windows itself.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val dark = when (themeMode) {
                Prefs.THEME_DARK -> true
                Prefs.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            MillidaTheme(dark = dark, accentId = accentId) {
                GameHostScreen(
                    model = model,
                    onLeave = { finish() },
                    onRelaunch = ::relaunch,
                )
            }
        }

        model.start(request)
    }

    /**
     * A second game cannot start in this process, so the relaunch goes through
     * a screen in the launcher process that waits for this one to end.
     */
    private fun relaunch() {
        val extras = intent?.extras
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
     * The desktop virtual machine cannot be unloaded, so the process that ran it
     * has to end together with the screen: otherwise the next launch lands in a
     * process where a machine already exists, and the game never starts. The
     * services that only served this game go first, or the system would keep
     * them running for a game that is gone.
     */
    override fun onDestroy() {
        val leaving = isFinishing
        if (leaving) {
            GameKeepAliveService.stop(this)
            if (!relaunching) BedrockProxyService.stop(this)
        }
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

    companion object {
        private const val TAG = "millida-game"
        private const val EXTRA_PROFILE = "profile"
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
