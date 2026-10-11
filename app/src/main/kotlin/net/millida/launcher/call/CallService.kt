package net.millida.launcher.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps a call alive while the launcher is behind the game or in the
 * background. Its notification is also the way to hang up from anywhere.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_HANG_UP -> {
                CallCenter.get(this).leaveEverything()
                return START_NOT_STICKY
            }
        }
        prepareChannel(this)
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "Звонок" }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, title), type)
        } catch (denied: Exception) {
            Log.w(TAG, "служба звонка не подтверждена: ${denied.message}")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        CallCenter.get(this).leaveEverything()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val TAG = "millida-call"
        private const val CHANNEL_ID = "millida-call"
        private const val NOTIFICATION_ID = 4611
        private const val EXTRA_TITLE = "title"
        private const val ACTION_STOP = "net.millida.launcher.STOP_CALL_SERVICE"
        private const val ACTION_HANG_UP = "net.millida.launcher.HANG_UP"

        fun start(context: Context, title: String) {
            prepareChannel(context)
            val intent = Intent(context, CallService::class.java).putExtra(EXTRA_TITLE, title)
            try {
                context.startForegroundService(intent)
            } catch (refused: Exception) {
                Log.w(TAG, "служба звонка не запущена — звонок может прерваться в фоне: ${refused.message}")
            }
        }

        /** Through the service's own queue: stopping before startForeground ran breaks the foreground promise. */
        fun stop(context: Context) {
            try {
                context.startService(Intent(context, CallService::class.java).setAction(ACTION_STOP))
            } catch (refused: Exception) {
                try {
                    context.stopService(Intent(context, CallService::class.java))
                } catch (stuck: Exception) {
                    Log.w(TAG, "служба звонка не остановилась: ${stuck.message}")
                }
            }
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }

        private fun prepareChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Звонки", NotificationManager.IMPORTANCE_LOW).also { it.setShowBadge(false) },
            )
        }

        private fun notification(context: Context, title: String): Notification {
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val hangUp = PendingIntent.getService(
                context,
                1,
                Intent(context, CallService::class.java).setAction(ACTION_HANG_UP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText("Идёт разговор")
                .setSmallIcon(net.millida.launcher.runtime.R.drawable.ic_stat_millida)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
                .addAction(0, "Завершить", hangUp)
                .build()
        }
    }
}
