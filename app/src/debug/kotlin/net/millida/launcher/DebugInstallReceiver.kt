package net.millida.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Debug only: the test phone installs a fresh build without anyone touching the screen.
 * `adb push app-debug.apk /sdcard/Android/data/<package>/files/next.apk` and
 * `am broadcast -a net.millida.launcher.DEBUG_INSTALL -p <package>`. Once the launcher has
 * installed itself it is the installer of record, and the next updates go through silently.
 */
class DebugInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val apk = File(context.getExternalFilesDir(null), intent.getStringExtra("file") ?: "next.apk")
        val pending = goAsync()
        Thread {
            val local = File(context.cacheDir, "debug-install.apk")
            val ok = runCatching { apk.copyTo(local, overwrite = true) }.isSuccess && SelfInstaller.install(context, local)
            Log.i("millida", "debug install ${apk.path}: ${if (ok) "committed" else "failed"}")
            pending.finish()
        }.start()
    }
}
