package net.millida.launcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.millida.launcher.core.http.CellularDownload
import net.millida.launcher.core.i18n.tr
import kotlin.math.roundToInt

/**
 * A long task keeps going while the player is in another app, and the shade is
 * the only place left to watch it. The speed is read from the bytes this app
 * received, so every download counts, whichever part of the launcher started it.
 */
class DownloadNotice(private val context: Context) {
    private var lastBytes = -1L
    private var lastAt = 0L
    private var shown = false

    /** areNotificationsEnabled reflects the notification permission on Android 13 and later. */
    @SuppressLint("MissingPermission")
    fun show(title: String, note: String, progress: Float) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        prepareChannel()
        val percent = (progress.coerceIn(0f, 1f) * 100).roundToInt()
        val determinate = progress > 0f
        val parts = listOfNotNull(
            if (determinate) "$percent%" else null,
            speed(),
            note.takeIf { it.isNotBlank() },
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(net.millida.launcher.runtime.R.drawable.ic_stat_millida)
            .setContentTitle(title)
            .setContentText(parts.joinToString(" · "))
            .setProgress(PROGRESS_MAX, percent, !determinate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .build()
        // Permission can be taken away between the check and the post; a lost progress line is not worth a crash.
        shown = runCatching { manager.notify(NOTIFICATION_ID, notification) }.isSuccess || shown
    }

    fun clear() {
        lastBytes = -1L
        if (!shown) return
        shown = false
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun speed(): String? {
        val bytes = TrafficStats.getUidRxBytes(Process.myUid())
        val now = SystemClock.elapsedRealtime()
        if (bytes == TrafficStats.UNSUPPORTED.toLong()) return null
        val previous = lastBytes
        val previousAt = lastAt
        lastBytes = bytes
        lastAt = now
        if (previous < 0 || now <= previousAt || bytes < previous) return null
        val perSecond = (bytes - previous) * 1000.0 / (now - previousAt)
        if (perSecond < MIN_SHOWN_BYTES_PER_SECOND) return null
        val (value, megabytes) = CellularDownload.speed(perSecond)
        return if (megabytes) tr("{speed} МБ/с", value) else tr("{speed} КБ/с", value)
    }

    private fun prepareChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tr("Загрузки"), NotificationManager.IMPORTANCE_LOW).also { it.setShowBadge(false) },
        )
    }

    companion object {
        private const val CHANNEL_ID = "millida-downloads"
        private const val NOTIFICATION_ID = 4511
        private const val PROGRESS_MAX = 100
        private const val MIN_SHOWN_BYTES_PER_SECOND = 1024.0
    }
}
