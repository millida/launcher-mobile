package net.millida.launcher.ui

import android.app.ActivityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.millida.launcher.Screen
import net.millida.launcher.isRoot
import kotlin.math.ceil
import kotlin.math.hypot

private const val WAVE_MS = 380
private const val FLASH_MS = 60
private const val TOTAL_MS = WAVE_MS + FLASH_MS
private const val WAVE_STEPS = 16
private const val SCATTER_CELLS = 3f
private const val DISTANCE_WEIGHT = 0.72f
private const val ACCENT_SETTLE_MS = 450L
private const val ACCENT_FILL_KEEP = 0.45f
private const val ACCENT_FLASH_KEEP = 0.9f
private val CELL = 36.dp

@Immutable
private data class WaveTone(val fill: Color, val flash: Color)

@Immutable
private data class Wave(val tone: WaveTone, val seed: Int)

private fun accentTone(accent: Color): WaveTone = WaveTone(
    fill = accent.mixSrgb(Color.Black, ACCENT_FILL_KEEP),
    flash = accent.mixSrgb(Color.Black, ACCENT_FLASH_KEEP),
)

private fun toneOf(screen: Screen, accent: Color): WaveTone = when (screen) {
    Screen.SHOP -> WaveTone(MillidaPixel.shopBand, MillidaPixel.shopPlate)
    Screen.PREMIUM -> WaveTone(MillidaPixel.dailyBand, MillidaPixel.dailyPlate)
    Screen.SKINS -> WaveTone(MillidaPixel.wardrobeBand, MillidaPixel.wardrobePlate)
    Screen.FRIENDS, Screen.CHAT -> WaveTone(MillidaPixel.socialWave, MillidaPixel.socialWaveFlash)
    Screen.HOSTING, Screen.HOSTING_MANAGE -> WaveTone(MillidaPixel.hostingWave, MillidaPixel.hostingWaveFlash)
    Screen.SETTINGS -> WaveTone(MillidaPixel.settingsWave, MillidaPixel.settingsWaveFlash)
    else -> accentTone(accent)
}

/**
 * Screen change as a pixel dissolve, the desktop launcher's transition: the new screen is
 * already underneath, a layer of cells in the destination's tone goes out in a wave from the
 * centre and each cell flashes lighter on the front. It never takes touches.
 *
 * Plays on the way into and out of pushed screens and when the accent changes; root tabs keep
 * their own slide, a full-window wave on every tab tap would hide the bar the finger is on.
 * Landscape has no bar: the lobby opens every section as the desktop does, so it waves too,
 * except between the hub's library and resources, which are two tabs of one screen there.
 */
@Composable
fun ScreenWave(screen: Screen, accentId: String) {
    val reduced = LocalReducedMotion.current
    val context = LocalContext.current
    val weak = remember(context) { context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true }
    if (reduced || weak) return

    val accent by rememberUpdatedState(millida.accent)
    val tabbed by rememberUpdatedState(!LocalWideLayout.current)
    val progress = remember { Animatable(1f) }
    var wave by remember { mutableStateOf<Wave?>(null) }
    var played by remember { mutableIntStateOf(0) }
    var shownScreen by remember { mutableStateOf(screen) }
    var shownAccent by remember { mutableStateOf(accentId) }

    val scope = rememberCoroutineScope()

    // Runs outside the effects that start it: a tab tap mid-wave restarts the screen effect,
    // and cancelling there would freeze the layer half-dissolved over the new screen.
    fun play(tone: WaveTone) {
        played += 1
        val next = Wave(tone, played)
        wave = next
        scope.launch {
            try {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(TOTAL_MS, easing = MillidaMotion.steps(WAVE_STEPS)))
            } finally {
                if (wave == next) wave = null
            }
        }
    }

    LaunchedEffect(screen) {
        val was = shownScreen
        shownScreen = screen
        if (was == screen || (tabbed && was.isRoot && screen.isRoot) || (!tabbed && hubTabs(was, screen))) return@LaunchedEffect
        play(toneOf(screen, accent))
    }

    LaunchedEffect(accentId) {
        if (accentId == shownAccent) return@LaunchedEffect
        // A typed custom hex arrives a character at a time; one wave once it stops changing.
        if (isCustomAccent(accentId)) delay(ACCENT_SETTLE_MS)
        shownAccent = accentId
        play(accentTone(accentById(accentId).color))
    }

    val current = wave ?: return
    Canvas(Modifier.fillMaxSize()) {
        drawDissolve(current, progress.value * TOTAL_MS)
    }
}

private fun hubTabs(was: Screen, screen: Screen): Boolean =
    setOf(was, screen) == setOf(Screen.HOME, Screen.CATALOG)

private fun DrawScope.drawDissolve(wave: Wave, elapsedMs: Float) {
    val cell = CELL.toPx()
    val columns = ceil(size.width / cell).toInt()
    val rows = ceil(size.height / cell).toInt()
    val center = Offset(size.width / 2, size.height / 2)
    val reach = hypot(center.x, center.y) * DISTANCE_WEIGHT + cell * SCATTER_CELLS
    val tile = Size(cell + 1, cell + 1)
    for (y in 0 until rows) {
        for (x in 0 until columns) {
            val distance = hypot((x + 0.5f) * cell - center.x, (y + 0.5f) * cell - center.y) * DISTANCE_WEIGHT +
                scatter(x, y, wave.seed) * cell * SCATTER_CELLS
            val at = distance / reach * WAVE_MS
            if (elapsedMs >= at + FLASH_MS) continue
            drawRect(
                color = if (elapsedMs >= at) wave.tone.flash else wave.tone.fill,
                topLeft = Offset(x * cell, y * cell),
                size = tile,
            )
        }
    }
}

/** A per-wave jitter in 0..1: the edge is ragged, differs between waves and holds still within one. */
private fun scatter(x: Int, y: Int, seed: Int): Float {
    var hash = x * 73_856_093 xor y * 19_349_663 xor seed * 83_492_791
    hash = hash xor (hash ushr 13)
    hash *= 1_274_126_177
    return ((hash ushr 8) and 0xFFFF) / 65_535f
}
