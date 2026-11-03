package net.millida.launcher.ui.home

import android.app.Application
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.captureRoboImage
import net.millida.launcher.UiState
import net.millida.launcher.core.account.Account
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.MillidaTheme
import net.millida.launcher.ui.character.CharacterLook
import net.millida.launcher.ui.millida
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the landscape lobby on the phones it has to survive, so a change to
 * it is judged by a picture instead of by a phone in the owner's hand. Runs
 * only when the build asks for shots (`-Pmillida.shots=<dir>`); the pictures
 * land in that folder and CI uploads them as `lobby-shots`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class LobbyShotsTest(private val device: ShotDevice, private val fontScale: Float, private val scene: ShotScene) {

    /** A phone or tablet in landscape with the system bars and the cutout it reserves, in dp. */
    class ShotDevice(val name: String, val qualifiers: String, val top: Int, val start: Int, val bottom: Int) {
        override fun toString() = name
    }

    enum class ShotScene { BANNERS, BANNER_OFF, DOWNLOAD }

    private val compose = createAndroidComposeRule<ComponentActivity>()

    // The phone shape must be set before the activity starts, so it wraps the
    // rule that starts it.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceRule(device.qualifiers, fontScale)).around(compose)

    @Test
    fun lobby() {
        val folder = System.getProperty(SHOTS_PROPERTY).orEmpty()
        assumeTrue("shots are rendered only with -Pmillida.shots", folder.isNotEmpty())
        val watchdog = stackWatchdog("$device font $fontScale $scene")
        val files = ShotFiles(File(folder, "input"))
        println("shot: start $device font $fontScale $scene")
        // The alert marker and the slide-in still tick on the clock; it is
        // stepped by hand so the test never waits for an idle that never comes.
        compose.mainClock.autoAdvance = false
        compose.setContent { ShotLobby(scene, files) }
        compose.runOnUiThread {
            val activity = compose.activity
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            val density = activity.resources.displayMetrics.density
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (device.top * density).roundToInt(), 0, 0))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of((device.start * density).roundToInt(), 0, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (device.bottom * density).roundToInt()))
                .build()
            // Straight to the composition's view: Robolectric's decor view does
            // not hand insets down the way a phone's window does.
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        // Pictures decode on Coil's own threads in real time; the clock moves a
        // frame at a time meanwhile so they land and the slide-in settles.
        repeat(SETTLE_STEPS) {
            Thread.sleep(SETTLE_SLEEP_MS)
            compose.mainClock.advanceTimeBy(FRAME_MS)
        }
        val name = "${device.name}-font${(fontScale * 10).roundToInt()}-${scene.name.lowercase()}.png"
        println("shot: settled, capturing $name")
        compose.onRoot().captureRoboImage(File(folder, name).absolutePath)
        println("shot: saved $name")
        watchdog.interrupt()
    }

    /** Sets the screen and the system font size before the activity is created. */
    private class DeviceRule(private val qualifiers: String, private val fontScale: Float) : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                RuntimeEnvironment.setQualifiers(qualifiers)
                RuntimeEnvironment.setFontScale(fontScale)
                base.evaluate()
            }
        }
    }

    /** A shot that hangs on CI otherwise leaves an empty log: after a minute every thread's stack is printed. */
    private fun stackWatchdog(label: String): Thread = Thread {
        try {
            Thread.sleep(WATCHDOG_MS)
            println("shot: $label still running after ${WATCHDOG_MS / 1000} s, threads:")
            Thread.getAllStackTraces().forEach { (thread, frames) ->
                println("--- ${thread.name} (${thread.state})")
                frames.take(WATCHDOG_FRAMES).forEach { println("    at $it") }
            }
        } catch (_: InterruptedException) {
            return@Thread
        }
    }.apply {
        isDaemon = true
        start()
    }

    private class ShotFiles(folder: File) {
        val skin: String = copy(folder, "skin.png")
        val oneblock: String = copy(folder, "oneblock-cover.png")
        val arcania: String = copy(folder, "arcania-cover.png")

        private fun copy(folder: File, name: String): String {
            folder.mkdirs()
            val target = File(folder, name)
            val source = requireNotNull(LobbyShotsTest::class.java.getResourceAsStream("/shots/$name")) { "test resource shots/$name is missing" }
            source.use { input -> target.outputStream().use { input.copyTo(it) } }
            return target.toURI().toString()
        }
    }

    @Composable
    private fun ShotLobby(scene: ShotScene, files: ShotFiles) {
        val oneblock = PremiumPack(
            id = ONEBLOCK_PACK,
            slug = ONEBLOCK_PACK,
            title = "OneBlock",
            coverUrl = files.oneblock,
            mcVersion = "1.7.10",
            loader = "Forge",
            downloads = 110_243,
        )
        val arcania = PremiumPack(
            id = "arcania",
            slug = "arcania",
            title = "Arcania 1.4.3",
            coverUrl = files.arcania,
            mcVersion = "1.20.1",
            loader = "Fabric",
            downloads = 2_526,
        )
        val banners = if (scene == ShotScene.BANNER_OFF) {
            emptyList()
        } else {
            lobbyBanners(promoOn = true, ownPlayable = true, ownOnline = 214, arcania = arcania, invitePlusDays = 2)
        }
        val signedIn = UiState(
            account = Account(id = "alex", kind = AccountKind.MILLIDA, nick = "Alex", uuid = ""),
            millidaSignedIn = true,
        )
        val state = if (scene == ShotScene.DOWNLOAD) {
            signedIn.copy(tasks = listOf("Готовим игру"), progress = 0.65f, progressNote = "Ресурсы: 31 из 3350", preparingGame = true)
        } else {
            signedIn
        }
        MillidaTheme(dark = true) {
            // A still frame: the scene, carousel and figure stop the way they do
            // for a phone that asks for less motion, so nothing keeps the
            // composition busy between the steps of the test clock.
            CompositionLocalProvider(LocalWideLayout provides true, LocalReducedMotion provides true) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(millida.bg),
                ) {
                    WideLobby(
                        state = state,
                        lobby = LobbyState(totalSeconds = PLAYED_SECONDS),
                        look = CharacterLook(nick = "Alex", skinUrl = files.skin, slim = false, capeUrl = null),
                        active = Profile(id = "oneblock", name = "OneBlock", gameVersion = "1.7.10", loader = LoaderKind.FORGE),
                        hostedCount = 0,
                        shopAlert = true,
                        bonusReady = true,
                        recommend = oneblock,
                        banners = banners,
                        bannerStart = 0,
                        actions = WideLobbyActions(
                            onPick = {},
                            onPlay = {},
                            onCancel = {},
                            onCharacter = {},
                            onShop = {},
                            onHosting = {},
                            onFriends = {},
                            onSettings = {},
                            onRecommend = {},
                            onBanner = {},
                            onChest = {},
                            onParty = {},
                            onInvite = {},
                            onHelp = {},
                            onFeedback = {},
                        ),
                        accountMenu = { _, _, _ -> },
                    )
                }
            }
        }
    }

    companion object {
        private const val SHOTS_PROPERTY = "millida.shots"
        private const val PLAYED_SECONDS = 9_360L
        private const val FRAME_MS = 16L
        private const val SETTLE_STEPS = 90
        private const val SETTLE_SLEEP_MS = 20L
        private const val WATCHDOG_MS = 60_000L
        private const val WATCHDOG_FRAMES = 40

        private val DEVICES = listOf(
            ShotDevice("phone-2560x1179-460dpi", "w890dp-h410dp-land-460dpi", top = 40, start = 36, bottom = 0),
            ShotDevice("phone-2400x1080-440dpi", "w873dp-h393dp-land-440dpi", top = 24, start = 0, bottom = 16),
            ShotDevice("tablet-1280x800dp", "w1280dp-h800dp-land-xhdpi", top = 24, start = 0, bottom = 24),
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} font {1} {2}")
        fun cases(): List<Array<Any>> = DEVICES.flatMap { device ->
            listOf(1f, 1.3f).flatMap { scale -> ShotScene.entries.map { scene -> arrayOf<Any>(device, scale, scene) } }
        }
    }
}
