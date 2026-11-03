package net.millida.launcher

import android.app.Application
import android.os.Build
import java.io.File

class MillidaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        if (isLauncherProcess()) CrashGuard.sendEarly(this)
        AppLanguage.install(this)
    }

    private fun isLauncherProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrDefault(packageName)
        }
        return name == packageName
    }
}
