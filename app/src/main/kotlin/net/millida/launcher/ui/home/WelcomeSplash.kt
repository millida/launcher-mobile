package net.millida.launcher.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida
import kotlin.math.ceil
import kotlin.math.hypot

private const val HOLD_MS = 1_700L
private const val DISSOLVE_MS = 560
private const val FLASH_SHARE = 0.12f
private const val SCATTER = 3f
private val CELL = 36.dp

/**
 * «С возвращением, <ник>» once per launch, about two seconds, a tap skips it.
 * It leaves by a pixel dissolve: cells go out in a wave from the centre and
 * flash in the accent on the front, the way the desktop splash does.
 */
@Composable
fun WelcomeSplash(ask: WelcomeAsk, onDone: () -> Unit) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    var leaving by remember { mutableStateOf(false) }
    val dissolve = remember { Animatable(0f) }

    BackHandler { leaving = true }
    LaunchedEffect(Unit) {
        delay(HOLD_MS)
        leaving = true
    }
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        if (!reduced) dissolve.animateTo(1f + FLASH_SHARE, tween(DISSOLVE_MS, easing = LinearEasing))
        onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { leaving = true } },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = CELL.toPx()
            val columns = ceil(size.width / cell).toInt()
            val rows = ceil(size.height / cell).toInt()
            val center = Offset(size.width / 2, size.height / 2)
            val reach = hypot(center.x, center.y) * 0.72f + cell * SCATTER
            val now = dissolve.value
            for (y in 0 until rows) {
                for (x in 0 until columns) {
                    val distance = hypot((x + 0.5f) * cell - center.x, (y + 0.5f) * cell - center.y) * 0.72f +
                        scatter(x, y) * cell * SCATTER
                    val at = distance / reach
                    if (now >= at + FLASH_SHARE) continue
                    drawRect(
                        color = if (now >= at && now > 0f) colors.accent else colors.bg,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell + 1, cell + 1),
                    )
                }
            }
        }
        if (!leaving) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Millida", style = MillidaType.brand, color = colors.accentText)
                Spacer(Modifier.height(18.dp))
                Text(
                    if (ask.first) "Добро пожаловать" else "С возвращением,",
                    style = MillidaType.body,
                    color = colors.fgMuted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (ask.first) "в Millida" else ask.nick.ifBlank { "игрок" },
                    style = MillidaType.screenTitle,
                    color = colors.fg,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A fixed per-cell jitter in 0..1, so the wave's edge is ragged but the same on every frame. */
private fun scatter(x: Int, y: Int): Float {
    var hash = x * 73_856_093 xor y * 19_349_663
    hash = hash xor (hash ushr 13)
    hash *= 1_274_126_177
    return ((hash ushr 8) and 0xFFFF) / 65_535f
}
