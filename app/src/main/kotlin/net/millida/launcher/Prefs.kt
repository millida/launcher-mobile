package net.millida.launcher

import android.content.Context
import net.millida.launcher.core.friends.Eltaller
import net.millida.launcher.core.media.LiveWallpaper
import net.millida.launcher.ui.isCustomAccent
import net.millida.launcher.ui.isKnownAccent

class Prefs(context: Context) {
    private val store = context.getSharedPreferences("millida-settings", Context.MODE_PRIVATE)

    var rendererId: String
        get() = store.getString(KEY_RENDERER, "").orEmpty()
        set(value) = store.edit().putString(KEY_RENDERER, value).apply()

    var lookSensitivity: Float
        get() = store.getFloat(KEY_SENSITIVITY, 1.0f)
        set(value) = store.edit().putFloat(KEY_SENSITIVITY, value).apply()

    var memoryOverrideMb: Int
        get() = store.getInt(KEY_MEMORY, 0)
        set(value) = store.edit().putInt(KEY_MEMORY, value).apply()

    /**
     * Which window layer the game gets: the one that comes with the runtime pack
     * (years of fixes for real devices) or ours. Kept switchable so a failure on
     * one path is one toggle away from the other.
     */
    var windowLayer: String
        get() = store.getString(KEY_WINDOW_LAYER, LAYER_PACK) ?: LAYER_PACK
        set(value) = store.edit().putString(KEY_WINDOW_LAYER, value).apply()

    var safeGraphics: Boolean
        get() = store.getBoolean(KEY_SAFE_GRAPHICS, true)
        set(value) = store.edit().putBoolean(KEY_SAFE_GRAPHICS, value).apply()

    var activeAccountId: String
        get() = store.getString(KEY_ACTIVE_ACCOUNT, "").orEmpty()
        set(value) = store.edit().putString(KEY_ACTIVE_ACCOUNT, value).apply()

    var activeProfileId: String
        get() = store.getString(KEY_ACTIVE_PROFILE, "").orEmpty()
        set(value) = store.edit().putString(KEY_ACTIVE_PROFILE, value).apply()

    /**
     * Which way a Bedrock server is opened: the official client, or our own
     * Java client with the protocol translator. Unset means the launcher picks
     * whatever is ready.
     */
    var bedrockMode: String
        get() = store.getString(KEY_BEDROCK_MODE, BEDROCK_ASK) ?: BEDROCK_ASK
        set(value) = store.edit().putString(KEY_BEDROCK_MODE, value).apply()

    /** Light, dark, or whatever the phone itself is set to. */
    var themeMode: String
        get() = store.getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
        set(value) = store.edit().putString(KEY_THEME, value).apply()

    var accentId: String
        get() = store.getString(KEY_ACCENT, null)?.takeIf(::isKnownAccent) ?: ACCENT_DEFAULT
        set(value) = store.edit().putString(KEY_ACCENT, value.takeIf(::isKnownAccent) ?: ACCENT_DEFAULT).apply()

    var accentCustom: String
        get() = store.getString(KEY_ACCENT_CUSTOM, null)?.takeIf(::isCustomAccent).orEmpty()
        set(value) = store.edit().putString(KEY_ACCENT_CUSTOM, value.takeIf(::isCustomAccent).orEmpty()).apply()

    var onboardingDone: Boolean
        get() = store.getBoolean(KEY_ONBOARDING, false)
        set(value) = store.edit().putBoolean(KEY_ONBOARDING, value).apply()

    var lowMemoryNoticeHidden: Boolean
        get() = store.getBoolean(KEY_LOW_MEMORY_HIDDEN, false)
        set(value) = store.edit().putBoolean(KEY_LOW_MEMORY_HIDDEN, value).apply()

    /** Builds whose owner answered «Без мода»: the start does not ask them again. */
    var millidaModDeclined: Set<String>
        get() = store.getStringSet(KEY_MILLIDA_MOD_DECLINED, null).orEmpty().toSet()
        set(value) = store.edit().putStringSet(KEY_MILLIDA_MOD_DECLINED, value.toSet()).apply()

    /** Renderers that crashed a build at init, as written by RendererFallback.entry. */
    var rendererInitFailures: Set<String>
        get() = store.getStringSet(KEY_RENDERER_INIT_FAILURES, null).orEmpty().toSet()
        set(value) = store.edit().putStringSet(KEY_RENDERER_INIT_FAILURES, value.toSet()).apply()

    /** Renderers whose start drew no frame in time (black screen), same entry format. */
    var rendererNoFrame: Set<String>
        get() = store.getStringSet(KEY_RENDERER_NO_FRAME, null).orEmpty().toSet()
        set(value) = store.edit().putStringSet(KEY_RENDERER_NO_FRAME, value.toSet()).apply()

    /** The lobby skin tile was opened once: its «!» and «Загрузи свой» go out, as on the desktop. */
    var lobbySkinSeen: Boolean
        get() = store.getBoolean(KEY_LOBBY_SKIN_SEEN, false)
        set(value) = store.edit().putBoolean(KEY_LOBBY_SKIN_SEEN, value).apply()

    /** Own picture behind the build list, the way the desktop launcher allows. */
    var wallpaperPath: String
        get() = store.getString(KEY_WALLPAPER, "").orEmpty()
        set(value) = store.edit().putString(KEY_WALLPAPER, value).apply()

    /** A live lobby background from the desktop's set; empty keeps the static one. */
    var liveWallpaper: String
        get() = LiveWallpaper.known(store.getString(KEY_LIVE_WALLPAPER, null))
        set(value) = store.edit().putString(KEY_LIVE_WALLPAPER, LiveWallpaper.known(value)).apply()

    /** The desktop's «Анимация» switch: off leaves the live background as a still frame. */
    var wallpaperMotion: Boolean
        get() = store.getBoolean(KEY_WALLPAPER_MOTION, true)
        set(value) = store.edit().putBoolean(KEY_WALLPAPER_MOTION, value).apply()

    /** The release whose notes were already shown, so the window appears once. */
    var whatsNewShownFor: String
        get() = store.getString(KEY_WHATS_NEW, "").orEmpty()
        set(value) = store.edit().putString(KEY_WHATS_NEW, value).apply()

    var updateOfferSkipped: Long
        get() = store.getLong(KEY_UPDATE_OFFER_SKIPPED, 0L)
        set(value) = store.edit().putLong(KEY_UPDATE_OFFER_SKIPPED, value).apply()

    /** Same default as the desktop launcher: notifications sound, presses stay quiet until asked for. */
    var soundMode: String
        get() = store.getString(KEY_SOUND_MODE, null)?.takeIf { it in SOUND_MODES } ?: SOUND_NOTIFY
        set(value) = store.edit().putString(KEY_SOUND_MODE, value.takeIf { it in SOUND_MODES } ?: SOUND_NOTIFY).apply()

    var soundVolume: Int
        get() = store.getInt(KEY_SOUND_VOLUME, SOUND_VOLUME_DEFAULT).coerceIn(0, 100)
        set(value) = store.edit().putInt(KEY_SOUND_VOLUME, value.coerceIn(0, 100)).apply()

    var haptics: Boolean
        get() = store.getBoolean(KEY_HAPTICS, true)
        set(value) = store.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** On by default, as the desktop telemetry switch: a crash nobody sends is a crash nobody fixes. */
    var crashReports: Boolean
        get() = store.getBoolean(KEY_CRASH_REPORTS, true)
        set(value) = store.edit().putBoolean(KEY_CRASH_REPORTS, value).apply()

    /** Off by default, as on the desktop: the chat over the game is something a player turns on. */
    var friendsOverlay: Boolean
        get() = store.getBoolean(KEY_FRIENDS_OVERLAY, false)
        set(value) = store.edit().putBoolean(KEY_FRIENDS_OVERLAY, value).apply()

    /**
     * Off by default: with a keyboard or a mouse in use the on-screen controls
     * hide, as a desktop player expects; a touch brings them back. On keeps
     * them on screen for those who play with both.
     */
    var touchControlsWithKeyboard: Boolean
        get() = store.getBoolean(KEY_TOUCH_WITH_KEYBOARD, false)
        set(value) = store.edit().putBoolean(KEY_TOUCH_WITH_KEYBOARD, value).apply()

    /** Recently picked chat emoji, newest first, as the desktop keeps them in `m-chat-elt`. */
    var chatEmojiRecent: List<String>
        get() = Eltaller.cleanRecent(store.getString(KEY_CHAT_EMOJI_RECENT, "").orEmpty().split(',').filter { it.isNotEmpty() })
        set(value) = store.edit().putString(KEY_CHAT_EMOJI_RECENT, Eltaller.cleanRecent(value).joinToString(",")).apply()

    companion object {
        private const val KEY_CHAT_EMOJI_RECENT = "chat-emoji-recent"
        private const val KEY_TOUCH_WITH_KEYBOARD = "touch-controls-with-keyboard"
        private const val KEY_RENDERER = "renderer"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_MEMORY = "memory-mb"
        private const val KEY_ACTIVE_ACCOUNT = "active-account"
        private const val KEY_ACTIVE_PROFILE = "active-profile"
        private const val KEY_WINDOW_LAYER = "window-layer"
        const val KEY_SAFE_GRAPHICS = "safe_graphics"
        const val LAYER_PACK = "pack"
        const val LAYER_OWN = "own"
        private const val KEY_ONBOARDING = "onboarding-done"
        private const val KEY_LOW_MEMORY_HIDDEN = "low-memory-notice-hidden"
        private const val KEY_MILLIDA_MOD_DECLINED = "millida-mod-declined"
        private const val KEY_RENDERER_INIT_FAILURES = "renderer-init-failures"
        private const val KEY_RENDERER_NO_FRAME = "renderer-no-frame"
        private const val KEY_LOBBY_SKIN_SEEN = "lobby-skin-seen"
        private const val KEY_WALLPAPER = "wallpaper"
        private const val KEY_LIVE_WALLPAPER = "live-wallpaper"
        private const val KEY_WALLPAPER_MOTION = "wallpaper-motion"
        private const val KEY_WHATS_NEW = "whats-new-shown"
        private const val KEY_UPDATE_OFFER_SKIPPED = "update-offer-skipped"
        private const val KEY_THEME = "theme-mode"
        private const val KEY_ACCENT = "accent"
        private const val KEY_ACCENT_CUSTOM = "accent-custom"
        private const val ACCENT_DEFAULT = "green"
        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        private const val KEY_BEDROCK_MODE = "bedrock-mode"
        const val BEDROCK_ASK = "ask"
        const val BEDROCK_OFFICIAL = "official"
        const val BEDROCK_OWN = "own"
        private const val KEY_SOUND_MODE = "sound-mode"
        private const val KEY_SOUND_VOLUME = "sound-volume"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_CRASH_REPORTS = "crash-reports"
        private const val KEY_FRIENDS_OVERLAY = "friends-overlay"
        const val SOUND_OFF = "off"
        const val SOUND_NOTIFY = "notify"
        const val SOUND_ALL = "all"
        const val SOUND_VOLUME_DEFAULT = 60
        val SOUND_MODES = setOf(SOUND_OFF, SOUND_NOTIFY, SOUND_ALL)
    }
}
