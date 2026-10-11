package net.millida.launcher

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

class HostingTransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        prepareChannel(this)
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "Передача файлов" }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, title, 0L, 0L), type)
        } catch (denied: Exception) {
            Log.w(TAG, "служба передачи не подтверждена: ${denied.message}")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "millida-transfer"
        private const val CHANNEL_ID = "millida-transfer"
        private const val NOTIFICATION_ID = 4503
        private const val EXTRA_TITLE = "title"
        private const val ACTION_STOP = "net.millida.launcher.STOP_HOSTING_TRANSFER"

        fun start(context: Context, title: String) {
            prepareChannel(context)
            val intent = Intent(context, HostingTransferService::class.java).putExtra(EXTRA_TITLE, title)
            try {
                context.startForegroundService(intent)
            } catch (refused: Exception) {
                Log.w(TAG, "служба передачи не запущена: ${refused.message}")
            }
        }

        fun progress(context: Context, title: String, done: Long, total: Long) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (!manager.areNotificationsEnabled()) return
            try {
                manager.notify(NOTIFICATION_ID, notification(context, title, done, total))
            } catch (refused: SecurityException) {
                Log.w(TAG, "прогресс передачи не показан: ${refused.message}")
            }
        }

        // Stopping goes through the service's own queue, after its start: stopping it before
        // startForeground ran makes the system kill the app for a broken foreground promise.
        fun stop(context: Context) {
            val intent = Intent(context, HostingTransferService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (refused: Exception) {
                try {
                    context.stopService(Intent(context, HostingTransferService::class.java))
                } catch (stuck: Exception) {
                    Log.w(TAG, "служба передачи не остановилась: ${stuck.message}")
                }
            }
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }

        private fun prepareChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Передача файлов", NotificationManager.IMPORTANCE_LOW)
                    .also { it.setShowBadge(false) },
            )
        }

        private fun notification(context: Context, title: String, done: Long, total: Long): Notification {
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val percent = if (total > 0L) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(if (total > 0L) "$percent%" else "Не закрывай приложение до конца передачи")
                .setSmallIcon(net.millida.launcher.runtime.R.drawable.ic_stat_millida)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, percent, total <= 0L)
                .setContentIntent(open)
                .build()
        }
    }
}
