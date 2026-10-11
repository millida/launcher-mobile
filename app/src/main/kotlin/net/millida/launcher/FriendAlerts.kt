package net.millida.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.millida.launcher.core.friends.ChatTarget
import net.millida.launcher.core.friends.NotifyKind
import net.millida.launcher.core.friends.NotifyLevel
import net.millida.launcher.core.friends.NotifyLevels
import net.millida.launcher.core.friends.levelOf
import java.util.concurrent.ConcurrentHashMap

sealed class AlertOpen {
    data class Chat(val target: ChatTarget) : AlertOpen()

    data class Friends(val tab: FriendsTab) : AlertOpen()
}

/** [key] groups the alerts of one thread or one event: a newer one replaces the older in the shade. */
data class FriendAlert(
    val kind: NotifyKind,
    val key: String,
    val title: String,
    val text: String,
    val open: AlertOpen,
)

/**
 * Friend events as system notifications, with the player's per-kind levels.
 * They are posted only while the launcher process lives: the backend has no
 * native push for phones (its push is browser Web Push), so a closed app hears
 * nothing until it is opened again.
 */
class FriendAlerts(context: Context) {
    private val app = context.applicationContext
    private val store = app.getSharedPreferences("millida-notify", Context.MODE_PRIVATE)
    private val mutableLevels = MutableStateFlow(readLevels())
    val levels: StateFlow<Map<NotifyKind, NotifyLevel>> = mutableLevels.asStateFlow()

    private val posted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var channelsReady = false

    fun setLevel(kind: NotifyKind, level: NotifyLevel) {
        store.edit().putString(kind.key, level.wire).apply()
        mutableLevels.update { it + (kind to level) }
    }

    fun systemAllowed(): Boolean {
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /** False when the player switched this kind off or the system forbids notifications. */
    @SuppressLint("MissingPermission")
    fun post(alert: FriendAlert): Boolean {
        val level = mutableLevels.value.levelOf(alert.kind)
        if (level == NotifyLevel.OFF || !systemAllowed()) return false
        prepareChannels()
        val notification = NotificationCompat.Builder(app, channelOf(alert.kind))
            .setSmallIcon(net.millida.launcher.runtime.R.drawable.ic_stat_millida)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setCategory(if (alert.kind == NotifyKind.MSG || alert.kind == NotifyKind.ROOM) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(if (alert.kind == NotifyKind.MSG || alert.kind == NotifyKind.ROOM) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(level == NotifyLevel.SILENT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(alert))
            .build()
        return try {
            NotificationManagerCompat.from(app).notify(TAG, alert.key.hashCode(), notification)
            posted.add(alert.key)
            true
        } catch (denied: SecurityException) {
            Log.w(TAG, "уведомление не показано: ${denied.message}")
            false
        }
    }

    fun dismiss(key: String) {
        if (!posted.remove(key)) return
        NotificationManagerCompat.from(app).cancel(TAG, key.hashCode())
    }

    /** Only what was posted here: the install and translator services keep their own notifications. */
    fun dismissAll() {
        val manager = NotificationManagerCompat.from(app)
        posted.toList().forEach { key ->
            manager.cancel(TAG, key.hashCode())
            posted.remove(key)
        }
    }

    private fun readLevels(): Map<NotifyKind, NotifyLevel> =
        NotifyKind.entries.associateWith { kind -> NotifyLevels.parse(kind, store.getString(kind.key, null)) }

    private fun channelOf(kind: NotifyKind): String = when (kind) {
        NotifyKind.MSG, NotifyKind.ROOM -> CHANNEL_MESSAGES
        NotifyKind.PLAY, NotifyKind.ONLINE -> CHANNEL_PRESENCE
        NotifyKind.REQUEST -> CHANNEL_REQUESTS
    }

    private fun prepareChannels() {
        if (channelsReady || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MESSAGES, "Сообщения друзей", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_PRESENCE, "Друзья в игре и в сети", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_REQUESTS, "Заявки в друзья", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
        channelsReady = true
    }

    private fun contentIntent(alert: FriendAlert): PendingIntent {
        val intent = Intent(app, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        when (val open = alert.open) {
            is AlertOpen.Chat -> when (val target = open.target) {
                is ChatTarget.Dm -> intent.putExtra(EXTRA_DM, target.userId)
                is ChatTarget.Room -> intent.putExtra(EXTRA_ROOM, target.roomId)
            }
            is AlertOpen.Friends -> intent.putExtra(EXTRA_TAB, open.tab.name)
        }
        return PendingIntent.getActivity(
            app,
            alert.key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "millida-friends"
        private const val ACTION_OPEN = "net.millida.launcher.OPEN_FRIENDS"
        private const val EXTRA_DM = "dm"
        private const val EXTRA_ROOM = "room"
        private const val EXTRA_TAB = "tab"
        private const val CHANNEL_MESSAGES = "millida-friends-messages"
        private const val CHANNEL_PRESENCE = "millida-friends-presence"
        private const val CHANNEL_REQUESTS = "millida-friends-requests"
        private const val ID_LIMIT = 128

        /**
         * The activity is exported, so these extras can come from any app: an
         * id is only ever used to open a conversation, never to act, and a
         * malformed one opens the friends list instead.
         */
        fun openingOf(intent: Intent?): AlertOpen? {
            if (intent?.action != ACTION_OPEN) return null
            safeId(intent.getStringExtra(EXTRA_DM))?.let { return AlertOpen.Chat(ChatTarget.Dm(it)) }
            safeId(intent.getStringExtra(EXTRA_ROOM))?.let { return AlertOpen.Chat(ChatTarget.Room(it)) }
            val tab = FriendsTab.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_TAB) } ?: FriendsTab.FRIENDS
            return AlertOpen.Friends(tab)
        }

        private fun safeId(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty() || value.length > ID_LIMIT) return null
            return value.takeIf { id -> id.all { it.isLetterOrDigit() || it == '-' || it == '_' } }
        }
    }
}

fun LauncherViewModel.openFromAlert(target: AlertOpen) {
    when (target) {
        is AlertOpen.Chat -> openChat(target.target)
        is AlertOpen.Friends -> {
            social.setTab(target.tab)
            open(Screen.FRIENDS)
        }
    }
}
