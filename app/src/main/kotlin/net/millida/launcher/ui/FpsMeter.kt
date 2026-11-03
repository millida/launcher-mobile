package net.millida.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.millida.launcher.core.game.FpsHistory
import net.millida.launcher.core.i18n.tr

private const val SAMPLE_MS = 500L
private const val GRAPH_WIDTH_DP = 96
private const val GRAPH_HEIGHT_DP = 28

/**
 * Frames per second and the last half minute of them as columns, in the corner
 * of the game. The count comes from the window layer off the screen thread:
 * it is a call into a native library.
 */
@Composable
fun FpsMeter(read: () -> Int?, modifier: Modifier = Modifier) {
    val colors = millida
    var history by remember { mutableStateOf(FpsHistory()) }
    var available by remember { mutableStateOf(true) }
    LaunchedEffect(read) {
        while (true) {
            val fps = withContext(Dispatchers.Default) { read() }
            available = fps != null
            if (fps != null) history = history.add(fps)
            delay(SAMPLE_MS)
        }
    }
    Column(
        modifier
            .pixelSurface(colors.raised.copy(alpha = 0.8f), MillidaCut.control)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            if (available) tr("{fps} FPS", history.last) else tr("FPS недоступен"),
            style = MillidaType.small.copy(fontSize = 12.sp),
            color = colors.fg,
            maxLines = 1,
        )
        if (available && history.samples.isNotEmpty()) {
            val bar = colors.accent
            val low = colors.warning
            Canvas(Modifier.size(GRAPH_WIDTH_DP.dp, GRAPH_HEIGHT_DP.dp)) {
                val top = history.ceiling.toFloat()
                val step = size.width / FpsHistory.CAPACITY
                history.samples.forEachIndexed { index, fps ->
                    val height = (fps / top).coerceIn(0f, 1f) * size.height
                    drawRect(
                        if (fps < FpsHistory.LOW) low else bar,
                        topLeft = Offset(index * step, size.height - height),
                        size = Size((step - 1f).coerceAtLeast(1f), height),
                    )
                }
            }
        }
    }
}
