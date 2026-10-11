package net.millida.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.millida.launcher.core.update.UpdatePolicy
import java.io.File

/** Messages from the install session result, picked up by the view model as notices. */
object SelfInstallEvents {
    data class Event(val text: String, val isError: Boolean)

    private val bus = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = bus.asSharedFlow()

    fun post(text: String, isError: Boolean) {
        bus.tryEmit(Event(text, isError))
    }
}

/**
 * Installs our own update through a PackageInstaller session. From API 31 the
 * session asks for no user action; the system honours that only for the
 * installer of record (this app, after the first update installed this way),
 * on an update of an already installed package, with targetSdk >= 31 (we
 * target 35). Android 12-14 may still show the dialog once (first update, or
 * the system's own conditions), which arrives as STATUS_PENDING_USER_ACTION.
 */
object SelfInstaller {
    private const val TAG = "millida"
    const val ACTION_RESULT = "net.millida.launcher.SELF_UPDATE_RESULT"

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Sends the player to the "install unknown apps" switch of this app. */
    fun openInstallSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** True when the session was committed; the result comes later to [UpdateInstallReceiver]. */
    fun install(context: Context, apk: File): Boolean {
        if (!apk.isFile) return false
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())
        if (UpdatePolicy.canSkipUserAction(Build.VERSION.SDK_INT)) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        return try {
            // stale sessions of ours would hold the previous file
            installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("millida.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(ACTION_RESULT).setPackage(context.packageName)
                // mutable: the system fills in status extras
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, id, intent, flags)
                session.commit(pending.intentSender)
            }
            true
        } catch (error: Exception) {
            Log.w(TAG, "сессия установки не открылась: ${error.message}")
            false
        }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    SelfInstallEvents.post("Система не показала подтверждение установки", true)
                    return
                }
                val started = runCatching {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (!started) SelfInstallEvents.post("Не удалось открыть подтверждение — открой лаунчер и повтори", true)
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("millida", "обновление установлено")
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                SelfInstallEvents.post("Установка отменена", false)
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Log.w("millida", "установка обновления: статус $status, $why")
                SelfInstallEvents.post(
                    when (status) {
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватило места для обновления"
                        PackageInstaller.STATUS_FAILURE_CONFLICT,
                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                            "Обновление не подошло к установленной версии — скачай её с сайта"
                        else -> "Не получилось установить обновление — повтори позже"
                    },
                    true,
                )
            }
        }
    }
}
