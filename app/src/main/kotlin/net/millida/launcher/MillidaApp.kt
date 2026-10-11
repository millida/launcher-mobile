package net.millida.launcher

import android.app.Application

class MillidaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }
}
