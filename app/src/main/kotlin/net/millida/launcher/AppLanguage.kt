package net.millida.launcher

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import net.millida.launcher.core.i18n.I18n
import net.millida.launcher.core.i18n.Locales

object AppLanguage {
    private const val TABLES = "i18n"

    fun install(context: Context) {
        val app = context.applicationContext
        I18n.install(
            read = { code -> runCatching { app.assets.open("$TABLES/$code.json").bufferedReader().use { it.readText() } }.getOrNull() },
            initial = resolve(app),
        )
    }

    /**
     * Android 13+ keeps a per-app language in the system settings: a pick made there wins, then
     * the launcher's own pick, then the phone's languages in their order, as the desktop does.
     */
    fun resolve(context: Context): String {
        val prefs = Prefs(context)
        appLocales(context)?.let { tag ->
            val code = Locales.available(Locales.fromSystem(listOf(tag)))
            if (prefs.language != code) prefs.language = code
            return code
        }
        val stored = prefs.language
        // A pick in the launcher also lands in the system list, so on Android 13+ an empty list
        // there means the player reset it to the phone language in the system settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && stored.isNotEmpty()) prefs.language = ""
        else if (Locales.isAvailable(stored)) return stored
        return Locales.available(Locales.fromSystem(systemTags()))
    }

    fun sync(context: Context) {
        val code = resolve(context)
        if (code != I18n.code) I18n.setLocale(code)
    }

    fun choose(activity: Activity, code: String) {
        if (!Locales.isAvailable(code) || code == I18n.code) return
        Prefs(activity).language = code
        I18n.setLocale(code)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales = LocaleList.forLanguageTags(Locales.info(code).system)
        }
    }

    private fun appLocales(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val list = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
        return if (list.isEmpty) null else list[0].toLanguageTag()
    }

    /** The phone's own list, not the app's: once a language is applied to the app, its configuration reports that one. */
    private fun systemTags(): List<String> {
        val list = Resources.getSystem().configuration.locales
        return (0 until list.size()).map { list[it].toLanguageTag() }
    }
}
