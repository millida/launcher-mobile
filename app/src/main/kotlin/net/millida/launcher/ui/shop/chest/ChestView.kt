package net.millida.launcher.ui.shop.chest

import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.millida.launcher.ui.LocalReducedMotion
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val SURFACE_IDS = AtomicLong()
private val SHOTS = ConcurrentHashMap<String, ImageBitmap>()

private fun nowMs(): Double = System.nanoTime() / 1_000_000.0

/** 0xRRGGBB of a service colour ("#ff6a1a", "FF6A1A"), or null. */
internal fun parseTint(value: String?): Int? {
    val hex = value?.trim()?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
    return hex.toIntOrNull(16)
}

/** The desktop's snapshot of the same 3D chest (public/chests, scripts/chest-shots.mjs), per tier and case tint. */
internal fun chestShotName(tier: String, tint: Int?): String {
    val t = when (tier.uppercase()) {
        "RARE" -> "rare"
        "EPIC" -> "epic"
        "LEGEND", "LEGENDARY" -> "legend"
        else -> "common"
    }
    return if (tint == null) t else t + "-" + String.format("%06x", tint)
}

@Composable
internal fun ChestShot(tier: String, tint: Int?, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val context = LocalContext.current
    val name = chestShotName(tier, tint)
    val image = remember(name) {
        SHOTS[name] ?: listOf(name, chestShotName(tier, null)).firstNotNullOfOrNull { file ->
            runCatching { context.assets.open("chests/$file.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull()?.asImageBitmap()
        }?.also { SHOTS[name] = it }
    }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
            alpha = alpha,
            filterQuality = FilterQuality.None,
        )
    } else {
        Box(modifier)
    }
}

/**
 * The 3D chest: the Blockbench model of its tier drawn by the shared GL host.
 * Without GL, or once its context is lost, the snapshot of the same chest
 * stands instead and an opening does not wait for the lid.
 */
@Composable
internal fun Chest3D(
    tier: String,
    mode: ChestMode,
    modifier: Modifier = Modifier,
    tint: Int? = null,
    onOpened: (() -> Unit)? = null,
    flat: @Composable () -> Unit = { ChestShot(tier, tint, Modifier.fillMaxSize()) },
) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val latestOpened by rememberUpdatedState(onOpened)
    var failed by remember { mutableStateOf(!ChestHost.available) }
    val id = remember { SURFACE_IDS.incrementAndGet() }
    val scene = remember {
        ChestGlScene(tier, tint, reduced) { android.os.Handler(android.os.Looper.getMainLooper()).post { latestOpened?.invoke() } }
            .also { it.setMode(mode, nowMs()) }
    }

    LaunchedEffect(mode) { ChestHost.update(context, id) { it.setMode(mode, nowMs()) } }
    LaunchedEffect(tier, tint) { ChestHost.update(context, id) { it.setLook(tier, tint) } }
    LaunchedEffect(failed, mode) { if (failed && mode == ChestMode.Open) latestOpened?.invoke() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, id) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> ChestHost.pause(context, id, true)
                Lifecycle.Event.ON_START -> ChestHost.pause(context, id, false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        if (failed) {
            flat()
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).apply {
                        isOpaque = false
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                ChestHost.attach(ctx, id, surface, width, height, scene) { failed = true }
                            }

                            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                                ChestHost.resize(ctx, id, width, height)
                            }

                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                ChestHost.detach(ctx, id, surface)
                                return false
                            }

                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                        }
                    }
                },
            )
        }
    }
}

private const val MAX_LIVE = 9
private val liveSlots = mutableIntStateOf(0)

/**
 * A chest for the shelves, the desktop's ChestLive: the live 3D model while
 * fewer than nine are on screen, the snapshot of the same chest otherwise.
 * The model is drawn half again as large as [side], so its glow is not cut.
 */
@Composable
internal fun ChestLive(tier: String, side: Dp, modifier: Modifier = Modifier, tint: Int? = null, ready: Boolean = true, alpha: Float = 1f) {
    var slot by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        if (ChestHost.available && liveSlots.intValue < MAX_LIVE) {
            liveSlots.intValue += 1
            slot = true
        }
        onDispose { if (slot) liveSlots.intValue -= 1 }
    }
    Box(modifier.size(side), contentAlignment = Alignment.Center) {
        if (slot && alpha >= 1f) {
            Chest3D(tier, if (ready) ChestMode.Ready else ChestMode.Closed, Modifier.requiredSize(side * 1.5f), tint)
        } else {
            ChestShot(tier, tint, Modifier.size(side), alpha)
        }
    }
}
