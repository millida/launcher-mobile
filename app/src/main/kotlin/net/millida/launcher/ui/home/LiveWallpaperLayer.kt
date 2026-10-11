package net.millida.launcher.ui.home

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import net.millida.launcher.core.media.LiveWallpaper
import net.millida.launcher.core.media.LiveWallpaperKind
import net.millida.launcher.core.media.SceneSpark
import net.millida.launcher.core.media.SceneWave
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import java.io.IOException
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "millida-wallpaper"
private const val FRAME_MS = 33L
private const val TURN = 6.2831855f
private const val SPARKS = 14
private const val SPARK_DP = 5f
private const val SPARK_BIG_DP = 8f
private const val STAR_DP = 3f
private const val SPARK_SWAY_DP = 6f
private const val STAR_GAP_MIN_MS = 350L
private const val STAR_GAP_SPREAD_MS = 700L
private const val WAVE_FILL = 0.75f
private const val WAVE_SHINE = 0.25f
private const val WAVE_SHADE = 0.22f
private const val WAVE_SHINE_DP = 3f
private const val WAVE_SHADE_DP = 4f

/**
 * The lobby's live background. The scene moves over the stage the lobby already
 * draws; a clip plays muted over its own poster. Both stop to a still frame when
 * the player switched motion off, the phone asks for less motion, or the app is
 * not in front, so nothing runs where nobody looks.
 */
@Composable
internal fun LiveWallpaperLayer(chosen: LiveWallpaper, motionOn: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val live = LiveWallpaper.animates(chosen, motionOn, reduced, lifecycle.isAtLeast(Lifecycle.State.RESUMED))
    when (chosen.kind) {
        LiveWallpaperKind.SCENE -> if (live) PixelSceneMotion(modifier)
        LiveWallpaperKind.VIDEO -> Box(modifier) {
            AssetImage(chosen.poster, Modifier.fillMaxSize(), pixelated = false)
            if (live) WallpaperClip(chosen.clip, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MillidaPixel.textShadow),
            )
        }
    }
}

private class Wave(val x: Float, val y: Float, val born: Long)

private class Spark(
    val x: Float,
    val y: Float,
    val rise: Float,
    val sway: Float,
    val born: Long,
    val life: Long,
    val warm: Boolean,
    val side: Float,
    val star: Boolean,
)

/** Simulation in dp and frame milliseconds; drawing only reads it. */
private class PixelScene {
    var widthDp = 0f
    var heightDp = 0f
    var cols = 0
    var rows = 0
    var jitter = FloatArray(0)
    val waves = ArrayList<Wave>()
    val sparks = ArrayList<Spark>()
    var nextWave = -1L
    var nextStar = 0L

    fun resize(widthDp: Float, heightDp: Float) {
        if (widthDp == this.widthDp && heightDp == this.heightDp) return
        this.widthDp = widthDp
        this.heightDp = heightDp
        cols = (widthDp / SceneWave.CELL_DP).toInt() + 2
        rows = (heightDp / SceneWave.CELL_DP).toInt() + 2
        jitter = FloatArray(cols * rows) { Random.nextFloat() * SceneWave.JITTER_CELLS }
    }

    fun step(now: Long) {
        if (widthDp <= 0f || heightDp <= 0f) return
        if (nextWave < 0L) nextWave = now + SceneWave.FIRST_MS
        if (now >= nextWave) {
            waves.add(spawnWave(now))
            nextWave = now + SceneWave.GAP_MIN_MS + Random.nextLong(SceneWave.GAP_SPREAD_MS)
        }
        val life = SceneWave.lifeMs(widthDp, heightDp)
        waves.removeAll { now - it.born >= life }
        sparks.removeAll { now - it.born >= it.life }
        while (sparks.count { !it.star } < SPARKS) {
            val fresh = spawnSpark(now, star = false)
            sparks.add(fresh.copyBornEarlier(Random.nextFloat() * 0.9f))
        }
        if (now >= nextStar) {
            sparks.add(spawnSpark(now, star = true))
            nextStar = now + STAR_GAP_MIN_MS + Random.nextLong(STAR_GAP_SPREAD_MS)
        }
    }

    private fun spawnWave(now: Long): Wave {
        val along = Random.nextFloat()
        return when (Random.nextInt(4)) {
            0 -> Wave(0f, along * heightDp, now)
            1 -> Wave(widthDp, along * heightDp, now)
            2 -> Wave(along * widthDp, 0f, now)
            else -> Wave(along * widthDp, heightDp, now)
        }
    }

    private fun spawnSpark(now: Long, star: Boolean): Spark = if (star) {
        Spark(
            x = widthDp * (0.2f + Random.nextFloat() * 0.6f),
            y = heightDp * (0.12f + Random.nextFloat() * 0.5f),
            rise = 0f,
            sway = Random.nextFloat() * TURN,
            born = now,
            life = 700L + Random.nextLong(500L),
            warm = Random.nextFloat() < 0.4f,
            side = STAR_DP,
            star = true,
        )
    } else {
        Spark(
            x = widthDp * (0.32f + Random.nextFloat() * 0.36f),
            y = heightDp * (0.35f + Random.nextFloat() * 0.4f),
            rise = 10f + Random.nextFloat() * 22f,
            sway = Random.nextFloat() * TURN,
            born = now,
            life = 2_600L + Random.nextLong(2_600L),
            warm = Random.nextFloat() < 0.4f,
            side = if (Random.nextFloat() < 0.3f) SPARK_BIG_DP else SPARK_DP,
            star = false,
        )
    }

    private fun Spark.copyBornEarlier(share: Float): Spark =
        Spark(x, y, rise, sway, born - (life * share).toLong(), life, warm, side, star)
}

@Composable
private fun PixelSceneMotion(modifier: Modifier) {
    val colors = millida
    val density = LocalDensity.current
    val scene = remember { PixelScene() }
    var clock by remember { mutableLongStateOf(0L) }
    LaunchedEffect(scene) {
        while (true) {
            withFrameMillis { now ->
                scene.step(now)
                clock = now
            }
            delay(FRAME_MS)
        }
    }
    val flash = colors.accent.mixSrgb(MillidaPixel.serverDark, 0.9f)
    val light = colors.accent.mixSrgb(MillidaPixel.white, 0.38f)
    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { size -> with(density) { scene.resize(size.width.toDp().value, size.height.toDp().value) } },
    ) {
        val now = clock
        if (now == 0L) return@Canvas
        drawWaves(scene, now, flash)
        drawSparks(scene, now, light)
    }
}

private fun DrawScope.drawWaves(scene: PixelScene, now: Long, flash: Color) {
    if (scene.waves.isEmpty()) return
    val unit = density
    val cell = floor(SceneWave.CELL_DP * unit)
    val ox = floor(((size.width / 2f) % cell + cell) % cell - cell / 2f)
    val oy = floor(((size.height * 0.48f) % cell + cell) % cell - cell / 2f)
    val shine = max(1f, round(WAVE_SHINE_DP * unit))
    val shade = max(1f, round(WAVE_SHADE_DP * unit))
    for (row in 0 until scene.rows) {
        for (col in 0 until scene.cols) {
            val left = ox + (col - 1) * cell
            val top = oy + (row - 1) * cell
            val cx = (left + cell / 2f) / unit
            val cy = (top + cell / 2f) / unit
            val jitter = scene.jitter[(row * scene.cols + col) % scene.jitter.size]
            var level = 0f
            for (wave in scene.waves) {
                val since = SceneWave.sinceFront((now - wave.born).toFloat(), hypot(cx - wave.x, cy - wave.y), jitter)
                level = max(level, SceneWave.level(since))
            }
            if (level <= 0f) continue
            drawRect(flash.copy(alpha = WAVE_FILL * level), Offset(left, top), Size(cell, cell))
            drawRect(MillidaPixel.white.copy(alpha = WAVE_SHINE * level), Offset(left, top), Size(cell, shine))
            drawRect(MillidaPixel.sole.copy(alpha = WAVE_SHADE), Offset(left, top + cell - shade), Size(cell, shade))
        }
    }
}

private fun DrawScope.drawSparks(scene: PixelScene, now: Long, light: Color) {
    val unit = density
    for (spark in scene.sparks) {
        val age = (now - spark.born).toFloat()
        val alpha = SceneSpark.alpha(age / spark.life, sin(age / 140f + spark.sway))
        if (alpha <= 0f) continue
        val color = if (spark.warm) MillidaPixel.gold else light
        val x = round((spark.x + sin(age / 700f + spark.sway) * SPARK_SWAY_DP) * unit)
        val y = round((spark.y - spark.rise * age / 1_000f) * unit)
        val side = round(spark.side * unit)
        if (spark.star) {
            drawRect(color.copy(alpha = alpha), Offset(x - side, y), Size(side * 3f, side))
            drawRect(color.copy(alpha = alpha), Offset(x, y - side), Size(side, side * 3f))
            drawRect(MillidaPixel.white.copy(alpha = (alpha + 0.25f).coerceAtMost(1f)), Offset(x, y), Size(side, side))
        } else {
            drawRect(color.copy(alpha = alpha), Offset(x, y), Size(side, side))
        }
    }
}

/**
 * A bundled clip on a TextureView, so it is clipped and layered like any other
 * picture. Losing the clip is not fatal: the poster underneath stays.
 */
@Composable
private fun WallpaperClip(asset: String, modifier: Modifier) {
    var failed by remember(asset) { mutableStateOf(false) }
    if (failed) return
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                isOpaque = false
                surfaceTextureListener = ClipPlayer(context, this, asset) { failed = true }
            }
        },
        modifier = modifier,
        onRelease = { view -> (view.surfaceTextureListener as? ClipPlayer)?.release() },
    )
}

private class ClipPlayer(
    private val context: Context,
    private val view: TextureView,
    private val asset: String,
    private val onFailed: () -> Unit,
) : TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val created = MediaPlayer()
        val target = Surface(texture)
        player = created
        surface = target
        try {
            context.assets.openFd(asset).use { fd -> created.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            created.setVolume(0f, 0f)
            created.isLooping = true
            created.setSurface(target)
            created.setOnVideoSizeChangedListener { _, w, h ->
                videoWidth = w
                videoHeight = h
                cover()
            }
            created.setOnPreparedListener { it.start() }
            created.setOnErrorListener { _, what, extra ->
                fail("ролик $asset не играет: код $what/$extra")
                true
            }
            created.prepareAsync()
        } catch (error: IOException) {
            fail("ролик $asset не открылся: ${error.message}")
        } catch (error: IllegalStateException) {
            fail("ролик $asset не запустился: ${error.message}")
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = cover()

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun release() {
        player?.release()
        player = null
        surface?.release()
        surface = null
    }

    private fun fail(reason: String) {
        Log.w(TAG, reason)
        release()
        onFailed()
    }

    /** A TextureView stretches the frame to its box; this scales it to cover, cropping the overflow evenly. */
    private fun cover() {
        val boxWidth = view.width.toFloat()
        val boxHeight = view.height.toFloat()
        if (videoWidth <= 0 || videoHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) return
        val scale = max(boxWidth / videoWidth, boxHeight / videoHeight)
        val matrix = Matrix()
        matrix.setScale(videoWidth * scale / boxWidth, videoHeight * scale / boxHeight, boxWidth / 2f, boxHeight / 2f)
        view.setTransform(matrix)
    }
}
