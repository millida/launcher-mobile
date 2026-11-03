package net.millida.launcher.ui.settings

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import net.millida.launcher.core.looks.AppIcon

/**
 * The home screen icon is one of the manifest's activity aliases; exactly one
 * of them is enabled. The switch waits until the launcher leaves the screen:
 * MIUI, One UI and others close an app whose launcher entry changes, and the
 * player must not lose what was on screen, nor a game, an install or a call
 * running in the background.
 */
internal object AppIconSwitch {
    private const val ALIAS_PREFIX = "net.millida.launcher.Icon"

    private fun component(context: Context, icon: AppIcon): ComponentName =
        ComponentName(context, ALIAS_PREFIX + icon.name.lowercase().replaceFirstChar { it.uppercase() })

    private fun enabled(manager: PackageManager, context: Context, icon: AppIcon): Boolean =
        when (manager.getComponentEnabledSetting(component(context, icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.DEFAULT
            else -> false
        }

    fun current(context: Context): AppIcon? = try {
        val manager = context.packageManager
        AppIcon.entries.filter { enabled(manager, context, it) }.singleOrNull()
    } catch (error: Exception) {
        Log.w("millida", "иконка приложения не прочиталась: ${error.message}")
        null
    }

    /** Nothing else of ours is alive that a closing app would take with it. */
    @Suppress("DEPRECATION")
    fun idle(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val own = Process.myPid()
        val others = manager.runningAppProcesses.orEmpty().any { it.uid == Process.myUid() && it.pid != own }
        val working = manager.getRunningServices(Int.MAX_VALUE).orEmpty().any { it.uid == Process.myUid() && it.foreground }
        return !others && !working
    }

    /**
     * Turns the target on before the others go off, so the home screen never
     * has the launcher missing. Returns whether the icon is now [target].
     */
    fun apply(context: Context, target: AppIcon): Boolean {
        val manager = context.packageManager
        return try {
            if (current(context) == target) return true
            manager.setComponentEnabledSetting(
                component(context, target),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            AppIcon.entries.filter { it != target }.forEach { other ->
                manager.setComponentEnabledSetting(
                    component(context, other),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            true
        } catch (error: Exception) {
            Log.w("millida", "иконка приложения не сменилась: ${error.message}")
            false
        }
    }
}
