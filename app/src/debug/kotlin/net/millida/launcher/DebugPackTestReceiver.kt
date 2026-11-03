package net.millida.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug only: installs a catalogue pack and starts it, no taps. The launcher should be on screen
 * (`am start -n <package>/net.millida.launcher.MainActivity`), then
 * `am broadcast -a net.millida.launcher.DEBUG_PACK_TEST -p <package> --es pack <slug|modrinth:slug|curseforge:id> [--es nick tester]`.
 * Progress is one line per step in logcat tag `millida-test`.
 */
class DebugPackTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pack = intent.getStringExtra("pack") ?: return
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("debugPack", pack)
                .putExtra("debugNick", intent.getStringExtra("nick").orEmpty()),
        )
    }
}
