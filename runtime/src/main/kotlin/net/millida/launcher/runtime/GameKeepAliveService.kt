package net.millida.launcher.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

// The system kills the process when a service started with startForegroundService does not
// confirm itself within seconds, so confirming is the very first thing this service does.
class GameKeepAliveService : Service() {

    private var title = "Игра запущена"

    override fun onCreate() {
        super.onCreate()
        prepareChannel()
        confirm()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Not sticky: a restart after the process died would show a notification with no game behind it.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_TITLE)?.let { title = it }
        confirm()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        Log.w(TAG, "система ограничила время службы — снимаем передний план")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun confirm(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
            true
        } catch (denied: Throwable) {
            // Refused starts (app already in the background, missing permission)
            // must not leave the service unconfirmed: it is stopped right away.
            Log.w(TAG, "служба переднего плана не подтверждена: ${denied.message}")
            stopSelf()
            false
        }
    }

    private fun prepareChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Игра", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(title)
        .setContentText("Пока идёт игра, система не выгрузит её из памяти")
        .setSmallIcon(R.drawable.ic_stat_millida)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    companion object {
        private const val TAG = "millida-runtime"
        private const val CHANNEL_ID = "millida-game"
        private const val NOTIFICATION_ID = 4501
        const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String) {
            val intent = Intent(context, GameKeepAliveService::class.java).putExtra(EXTRA_TITLE, title)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (denied: Throwable) {
                Log.w(TAG, "не удалось запустить службу игры: ${denied.message}")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GameKeepAliveService::class.java)) }
                .onFailure { Log.w(TAG, "служба игры не остановлена: ${it.message}") }
        }
    }
}
