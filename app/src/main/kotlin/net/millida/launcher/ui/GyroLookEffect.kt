package net.millida.launcher.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import net.millida.launcher.core.input.GyroLook
import net.millida.launcher.runtime.GameInputSink

/** True when the phone has a gyroscope at all; the setting is not offered otherwise. */
fun hasGyroscope(context: Context): Boolean =
    context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

/**
 * While [active], turning the phone turns the camera. The listener lives only
 * while the camera is the game's: in menus the sensor is released.
 */
@Composable
fun GyroLookEffect(active: Boolean, sink: GameInputSink, sensitivity: Float) {
    val context = LocalContext.current
    val gain by rememberUpdatedState(sensitivity)
    DisposableEffect(active, sink) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (!active || manager == null || sensor == null) return@DisposableEffect onDispose { }
        var lastNanos = 0L
        var restX = 0f
        var restY = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val previous = lastNanos
                lastNanos = event.timestamp
                if (previous == 0L) return
                val seconds = ((event.timestamp - previous) / 1_000_000_000f).coerceIn(0f, MAX_STEP_SECONDS)
                val (dx, dy) = GyroLook.delta(rotation(context), event.values[0], event.values[1], seconds, gain)
                restX += dx
                restY += dy
                val moveX = restX.toInt()
                val moveY = restY.toInt()
                restX -= moveX
                restY -= moveY
                if (moveX != 0 || moveY != 0) sink.cursorDelta(moveX.toFloat(), moveY.toFloat())
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager.unregisterListener(listener) }
    }
}

private const val MAX_STEP_SECONDS = 0.1f

@Suppress("DEPRECATION")
private fun rotation(context: Context): Int {
    val display = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            context.getSystemService(WindowManager::class.java)?.defaultDisplay
        }
    }.getOrNull()
    return display?.rotation ?: 1
}
