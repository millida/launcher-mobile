package net.millida.launcher

import android.content.Context
import net.millida.launcher.core.friends.Eltaller
import net.millida.launcher.core.game.GameResolution
import net.millida.launcher.core.hub.PhoneBlocked
import net.millida.launcher.core.hub.PhoneBlockedCache
import net.millida.launcher.core.input.GamepadMap

import net.millida.launcher.core.looks.AppIcon
import net.millida.launcher.core.looks.LookAccess
import net.millida.launcher.core.looks.PremiumLook
import net.millida.launcher.core.media.LiveWallpaper
import net.millida.launcher.core.media.LiveWallpaperKind
import net.millida.launcher.ui.isCustomAccent
import net.millida.launcher.ui.isKnownAccent

class Prefs(private val context: Context) {
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
     * (years of fixes for real devices) or ours. Ours answers only part of what
     * the game asks of the window library, and four of five starts on it ended
     * in a crash, so outside debug builds the choice is neither offered nor kept.
     */
    var windowLayer: String
        get() = if (BuildConfig.TEST_HOOKS) store.getString(KEY_WINDOW_LAYER, LAYER_PACK) ?: LAYER_PACK else LAYER_PACK
        set(value) = store.edit().putString(KEY_WINDOW_LAYER, value).apply()

    var safeGraphics: Boolean
        get() = store.getBoolean(KEY_SAFE_GRAPHICS, true)
        set(value) = store.edit().putBoolean(KEY_SAFE_GRAPHICS, value).apply()

    /** «Экраны как на ПК»: Каталог и Хостинг идут с сайта, как в настольном лаунчере. */
    var remoteUi: Boolean
        get() = store.getBoolean(KEY_REMOTE_UI, true)
        set(value) = store.edit().putBoolean(KEY_REMOTE_UI, value).apply()

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

    /** The interface language the player picked; empty until a pick, so the system language is followed. */
    var language: String
        get() = store.getString(KEY_LANGUAGE, "").orEmpty()
        set(value) = store.edit().putString(KEY_LANGUAGE, value).apply()

    var accentId: String
        get() = store.getString(KEY_ACCENT, null)?.takeIf(::isKnownAccent) ?: ACCENT_DEFAULT
        set(value) = store.edit().putString(KEY_ACCENT, value.takeIf(::isKnownAccent) ?: ACCENT_DEFAULT).apply()

    var accentCustom: String
        get() = store.getString(KEY_ACCENT_CUSTOM, null)?.takeIf(::isCustomAccent).orEmpty()
        set(value) = store.edit().putString(KEY_ACCENT_CUSTOM, value.takeIf(::isCustomAccent).orEmpty()).apply()

    var onboardingDone: Boolean
        get() = store.getBoolean(KEY_ONBOARDING, false)
        set(value) = store.edit().putBoolean(KEY_ONBOARDING, value).apply()

    /** Set by the first Millida sign-in ever: the game-account step is for new players only. */
    var millidaEver: Boolean
        get() = store.getBoolean(KEY_MILLIDA_EVER, false)
        set(value) = store.edit().putBoolean(KEY_MILLIDA_EVER, value).apply()

    var firstRun: String
        get() = store.getString(KEY_FIRST_RUN, null).orEmpty()
        set(value) = store.edit().putString(KEY_FIRST_RUN, value).apply()

    var lowMemoryNoticeHidden: Boolean
        get() = store.getBoolean(KEY_LOW_MEMORY_HIDDEN, false)
        set(value) = store.edit().putBoolean(KEY_LOW_MEMORY_HIDDEN, value).apply()

    /** The «телефон закрыл игру в фоне» hint is shown once: it points at settings, not at a fault. */
    var cleanerHintShown: Boolean
        get() = store.getBoolean(KEY_CLEANER_HINT, false)
        set(value) = store.edit().putBoolean(KEY_CLEANER_HINT, value).apply()

    /** Builds whose owner answered «Без мода»: the start does not ask them again. */
    var millidaModDeclined: Set<String>
        get() = store.getStringSet(KEY_MILLIDA_MOD_DECLINED, null).orEmpty().toSet()
        set(value) = store.edit().putStringSet(KEY_MILLIDA_MOD_DECLINED, value.toSet()).apply()

    /** Mods-folder signature of the last pre-launch scan that found nothing, per build and check ("missing" / "problems"). */
    fun cleanScan(profileId: String, check: String): String? = store.getString("clean-scan-$check-$profileId", null)

    fun setCleanScan(profileId: String, check: String, signature: String?) {
        store.edit().apply { if (signature == null) remove("clean-scan-$check-$profileId") else putString("clean-scan-$check-$profileId", signature) }.apply()
    }

    /** Last answer of the Millida mod check, see PreLaunchCache. */
    fun modOffer(profileId: String): String? = store.getString("mod-offer-$profileId", null)

    fun setModOffer(profileId: String, raw: String) {
        store.edit().putString("mod-offer-$profileId", raw).apply()
    }

    /** How long the game process took from start to its first frame, per build: the loading percent runs against it. */
    fun bootMs(profileId: String): Long = store.getLong("boot-ms-$profileId", 0L)

    fun setBootMs(profileId: String, ms: Long) {
        store.edit().putLong("boot-ms-$profileId", ms).apply()
    }

    /** Renderers that crashed a build at init, as written by RendererFallback.entry. */
    var rendererInitFailures: Set<String>
        get() = store.getStringSet(KEY_RENDERER_INIT_FAILURES, null).orEmpty().toSet()
        set(value) = store.edit().putStringSet(KEY_RENDERER_INIT_FAILURES, value.toSet()).apply()

    /** Renderers whose start drew no frame in time (black screen), same entry format. */
    var rendererNoFrame: Set<String>
        get() = store.getStringSet(KEY_RENDERER_NO_FRAME, null).orEmpty().toSet()
        set(value) = store.edit().putStringSet(KEY_RENDERER_NO_FRAME, value.toSet()).apply()

    /**
     * The start in progress, as written by RendererFallback.pendingStart. Written to disk at
     * once: it has to outlive a game process that dies without running any code of its own.
     */
    var rendererPendingStart: String
        get() = store.getString(KEY_RENDERER_PENDING_START, "").orEmpty()
        set(value) {
            store.edit().putString(KEY_RENDERER_PENDING_START, value).commit()
        }

    /**
     * Not before this moment does a start try again to fetch a part of the runtime it can
     * do without: a phone that is offline, or cannot reach the storage, must not wait for
     * the same failure on every start.
     */
    var runtimeFetchRetryAt: Long
        get() = store.getLong(KEY_RUNTIME_FETCH_RETRY_AT, 0L)
        set(value) = store.edit().putLong(KEY_RUNTIME_FETCH_RETRY_AT, value).apply()

    /** Missing mods the player chose to start without, as «build id, new line, project id»: not asked again. */
    var missingModsDeclined: Set<String>
        get() = store.getStringSet(KEY_MISSING_MODS_DECLINED, null).orEmpty().toSet()
        set(value) = store.edit().putStringSet(KEY_MISSING_MODS_DECLINED, value.toSet()).apply()

    /** The lobby skin tile was opened once: its «!» and «Загрузи свой» go out, as on the desktop. */
    var lobbySkinSeen: Boolean
        get() = store.getBoolean(KEY_LOBBY_SKIN_SEEN, false)
        set(value) = store.edit().putBoolean(KEY_LOBBY_SKIN_SEEN, value).apply()

    /** Own picture behind the build list, the way the desktop launcher allows. */
    var wallpaperPath: String
        get() = store.getString(KEY_WALLPAPER, "").orEmpty()
        set(value) = store.edit().putString(KEY_WALLPAPER, value).apply()

    /**
     * A live lobby background from the desktop's set; empty keeps the static one.
     * In the random mode every start of the process draws one of the clips, as
     * the desktop's `m-wp-mode` «random» does on every launch.
     */
    var liveWallpaper: String
        get() = if (wallpaperRandom) randomClip else LiveWallpaper.known(store.getString(KEY_LIVE_WALLPAPER, null))
        set(value) = store.edit().putString(KEY_LIVE_WALLPAPER, LiveWallpaper.known(value)).apply()

    var wallpaperRandom: Boolean
        get() = store.getBoolean(KEY_WALLPAPER_RANDOM, false)
        set(value) = store.edit().putBoolean(KEY_WALLPAPER_RANDOM, value).apply()

    /**
     * Own pictures, newest first and at most [WALLPAPER_GALLERY_LIMIT], the
     * desktop's `m-wp-gallery`. A picture chosen before the gallery existed
     * becomes its first entry.
     */
    var wallpaperGallery: List<String>
        get() {
            val stored = store.getString(KEY_WALLPAPER_GALLERY, null)
            if (stored != null) return stored.split('\n').filter { it.isNotBlank() }.take(WALLPAPER_GALLERY_LIMIT)
            val single = wallpaperPath
            return if (single.isNotEmpty() && !single.contains(STILL_FOLDER_MARK)) listOf(single) else emptyList()
        }
        set(value) = store.edit()
            .putString(KEY_WALLPAPER_GALLERY, value.filter { it.isNotBlank() && '\n' !in it }.take(WALLPAPER_GALLERY_LIMIT).joinToString("\n"))
            .apply()

    /** The chosen app icon (`m-app-icon`); kept while the icon is locked, so it comes back with the subscription. */
    var appIcon: String
        get() = AppIcon.of(store.getString(KEY_APP_ICON, null)).id
        set(value) = store.edit().putString(KEY_APP_ICON, AppIcon.of(value).id).apply()

    /** The desktop's «Персонаж танцует» (`m-anim-char`): the lobby figure plays emotes between pauses. */
    var characterDance: Boolean
        get() = store.getBoolean(KEY_CHARACTER_DANCE, true)
        set(value) = store.edit().putBoolean(KEY_CHARACTER_DANCE, value).apply()

    /** The desktop's «Анимация» switch: off leaves the live background as a still frame. */
    var wallpaperMotion: Boolean
        get() = store.getBoolean(KEY_WALLPAPER_MOTION, LiveWallpaper.MOTION_DEFAULT)
        set(value) = store.edit().putBoolean(KEY_WALLPAPER_MOTION, value).apply()

    /** The launcher theme: off, plus or diamond (the desktop's `m-ptheme`). */
    var premiumTheme: String
        get() = PremiumLook.known(store.getString(KEY_PREMIUM_THEME, null))
        set(value) = store.edit().putString(KEY_PREMIUM_THEME, PremiumLook.known(value)).apply()

    /** The ordinary colour worn before the theme painted the buttons, to come back to (`m-ptheme-prev`). */
    var premiumPrevAccent: String
        get() = store.getString(KEY_PREMIUM_PREV_ACCENT, null)?.takeIf(::isKnownAccent).orEmpty()
        set(value) = store.edit().putString(KEY_PREMIUM_PREV_ACCENT, value.takeIf(::isKnownAccent).orEmpty()).apply()

    /**
     * The tier of the last real answer: «none», a tier, or empty before the
     * first one. A change from none to a tier is a payment, which switches
     * the theme on once.
     */
    var lookTierSeen: String
        get() = store.getString(KEY_LOOK_TIER_SEEN, null).orEmpty()
        set(value) = store.edit().putString(KEY_LOOK_TIER_SEEN, value).apply()

    /** What the account opened, kept so a start without network still shows the right icon and swatches. */
    var lookAccess: LookAccess
        get() = LookAccess(
            tier = store.getString(KEY_LOOK_TIER, null)?.takeIf { it == PremiumLook.TIER_PLUS || it == PremiumLook.TIER_DIAMOND },
            perks = store.getStringSet(KEY_LOOK_PERKS, null).orEmpty().toSet(),
        )
        set(value) = store.edit()
            .putString(KEY_LOOK_TIER, value.tier.orEmpty())
            .putStringSet(KEY_LOOK_PERKS, value.perks.toSet())
            .apply()

    /** The release whose notes were already shown, so the window appears once. */
    var whatsNewShownFor: String
        get() = store.getString(KEY_WHATS_NEW, "").orEmpty()
        set(value) = store.edit().putString(KEY_WHATS_NEW, value).apply()

    var updateOfferSkipped: Long
        get() = store.getLong(KEY_UPDATE_OFFER_SKIPPED, 0L)
        set(value) = store.edit().putLong(KEY_UPDATE_OFFER_SKIPPED, value).apply()

    /** Presses sound by default, like a game lobby; «Важные» and «Выкл» stay in settings. */
    var soundMode: String
        get() = store.getString(KEY_SOUND_MODE, null)?.takeIf { it in SOUND_MODES } ?: SOUND_ALL
        set(value) = store.edit().putString(KEY_SOUND_MODE, value.takeIf { it in SOUND_MODES } ?: SOUND_NOTIFY).apply()

    var soundVolume: Int
        get() = store.getInt(KEY_SOUND_VOLUME, SOUND_VOLUME_DEFAULT).coerceIn(0, 100)
        set(value) = store.edit().putInt(KEY_SOUND_VOLUME, value.coerceIn(0, 100)).apply()

    var haptics: Boolean
        get() = store.getBoolean(KEY_HAPTICS, true)
        set(value) = store.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** Climbing a block without a jump, as in Bedrock; written into the game's options.txt at start. */
    var autoJump: Boolean
        get() = store.getBoolean(KEY_AUTO_JUMP, true)
        set(value) = store.edit().putBoolean(KEY_AUTO_JUMP, value).apply()

    /** The server's list of packs that do not start on phones, kept between starts; see PhoneBlocked. */
    var phoneBlocked: PhoneBlockedCache?
        get() {
            val at = store.getLong(KEY_PHONE_BLOCKED_AT, 0L).takeIf { it > 0 } ?: return null
            return PhoneBlockedCache(PhoneBlocked.decode(store.getString(KEY_PHONE_BLOCKED, "")), at)
        }
        set(value) {
            if (value == null) return
            store.edit()
                .putString(KEY_PHONE_BLOCKED, PhoneBlocked.encode(value.ids))
                .putLong(KEY_PHONE_BLOCKED_AT, value.fetchedAtMs)
                .apply()
        }

    /** Start time and frame rate per pack, from the same answer as the list above. */
    var phonePackStats: Map<String, net.millida.launcher.core.hub.PackStat>
        get() = PhoneBlocked.decodeStats(store.getString(KEY_PHONE_STATS, ""))
        set(value) = store.edit().putString(KEY_PHONE_STATS, PhoneBlocked.encodeStats(value)).apply()

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
    /** The system keyboard types straight into the game instead of into the launcher's text field. */
    var directKeyboard: Boolean
        get() = store.getBoolean(KEY_DIRECT_KEYBOARD, false)
        set(value) = store.edit().putBoolean(KEY_DIRECT_KEYBOARD, value).apply()

    var touchControlsWithKeyboard: Boolean
        get() = store.getBoolean(KEY_TOUCH_WITH_KEYBOARD, false)
        set(value) = store.edit().putBoolean(KEY_TOUCH_WITH_KEYBOARD, value).apply()

    /** Share of the screen's pixels the game draws, in percent; the picture is stretched to the glass. */
    var resolutionPercent: Int
        get() = GameResolution.clamp(
            if (store.contains(KEY_RESOLUTION)) store.getInt(KEY_RESOLUTION, GameResolution.FULL)
            else GameResolution.defaultFor(GraphicsProbe.read(context.applicationContext).glRenderer),
        )
        set(value) = store.edit().putInt(KEY_RESOLUTION, GameResolution.clamp(value)).apply()

    /** The game window asks for the display's fastest mode instead of the 60 Hz most firmwares hand out. */
    var highRefresh: Boolean
        get() = store.getBoolean(KEY_HIGH_REFRESH, false)
        set(value) = store.edit().putBoolean(KEY_HIGH_REFRESH, value).apply()

    /** The player's gamepad buttons; empty keeps the defaults. */
    var gamepadMap: GamepadMap
        get() = GamepadMap.decode(store.getString(KEY_GAMEPAD, "").orEmpty())
        set(value) = store.edit().putString(KEY_GAMEPAD, value.encode()).apply()

    /** Turning the phone turns the camera while the game has it. */
    var gyroLook: Boolean
        get() = store.getBoolean(KEY_GYRO_LOOK, false)
        set(value) = store.edit().putBoolean(KEY_GYRO_LOOK, value).apply()

    /** A fast swipe turns the camera further than a slow one of the same length. */
    var touchAcceleration: Boolean
        get() = store.getBoolean(KEY_TOUCH_ACCELERATION, false)
        set(value) = store.edit().putBoolean(KEY_TOUCH_ACCELERATION, value).apply()

    /** The frame counter with its graph in the corner of the game. */
    var fpsMeter: Boolean
        get() = store.getBoolean(KEY_FPS_METER, false)
        set(value) = store.edit().putBoolean(KEY_FPS_METER, value).apply()

    /** The game also draws under the camera cutout instead of leaving a black strip there. */
    var gameUnderCutout: Boolean
        get() = store.getBoolean(KEY_UNDER_CUTOUT, false)
        set(value) = store.edit().putBoolean(KEY_UNDER_CUTOUT, value).apply()

    /** When the player agreed to the processing of personal data, in milliseconds; zero until then. */
    var personalDataConsentAt: Long
        get() = store.getLong(KEY_PD_CONSENT, 0L)
        set(value) = store.edit().putLong(KEY_PD_CONSENT, value).apply()

    /** Recently picked chat emoji, newest first, as the desktop keeps them in `m-chat-elt`. */
    var chatEmojiRecent: List<String>
        get() = Eltaller.cleanRecent(store.getString(KEY_CHAT_EMOJI_RECENT, "").orEmpty().split(',').filter { it.isNotEmpty() })
        set(value) = store.edit().putString(KEY_CHAT_EMOJI_RECENT, Eltaller.cleanRecent(value).joinToString(",")).apply()

    companion object {
        private const val KEY_RESOLUTION = "game-resolution"
        private const val KEY_HIGH_REFRESH = "game-high-refresh"
        private const val KEY_PD_CONSENT = "personal-data-consent-at"
        private const val KEY_FPS_METER = "game-fps-meter"
        private const val KEY_GYRO_LOOK = "game-gyro-look"
        private const val KEY_GAMEPAD = "game-gamepad-map"
        private const val KEY_DIRECT_KEYBOARD = "game-direct-keyboard"

        private const val KEY_TOUCH_ACCELERATION = "game-touch-acceleration"
        private const val KEY_UNDER_CUTOUT = "game-under-cutout"
        private const val KEY_CHAT_EMOJI_RECENT = "chat-emoji-recent"
        private const val KEY_TOUCH_WITH_KEYBOARD = "touch-controls-with-keyboard"
        private const val KEY_RENDERER = "renderer"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_MEMORY = "memory-mb"
        private const val KEY_ACTIVE_ACCOUNT = "active-account"
        private const val KEY_ACTIVE_PROFILE = "active-profile"
        private const val KEY_WINDOW_LAYER = "window-layer"
        const val KEY_SAFE_GRAPHICS = "safe_graphics"
        const val KEY_REMOTE_UI = "remote_ui"
        const val LAYER_PACK = "pack"
        const val LAYER_OWN = "own"
        private const val KEY_ONBOARDING = "onboarding-done"
        private const val KEY_MILLIDA_EVER = "millida-ever"
        private const val KEY_FIRST_RUN = "first-run"
        private const val KEY_LOW_MEMORY_HIDDEN = "low-memory-notice-hidden"
        private const val KEY_CLEANER_HINT = "cleaner-hint-shown"
        private const val KEY_MILLIDA_MOD_DECLINED = "millida-mod-declined"
        private const val KEY_RENDERER_INIT_FAILURES = "renderer-init-failures"
        private const val KEY_RENDERER_NO_FRAME = "renderer-no-frame"
        private const val KEY_RENDERER_PENDING_START = "renderer-pending-start"
        private const val KEY_RUNTIME_FETCH_RETRY_AT = "runtime-fetch-retry-at"
        private const val KEY_MISSING_MODS_DECLINED = "missing-mods-declined"
        private const val KEY_LOBBY_SKIN_SEEN = "lobby-skin-seen"
        private const val KEY_WALLPAPER = "wallpaper"
        private const val KEY_LIVE_WALLPAPER = "live-wallpaper"
        private const val KEY_WALLPAPER_MOTION = "wallpaper-motion"
        private const val KEY_WALLPAPER_RANDOM = "wallpaper-random"
        private const val KEY_WALLPAPER_GALLERY = "wallpaper-gallery"
        private const val KEY_CHARACTER_DANCE = "character-dance"
        private const val KEY_APP_ICON = "app-icon"
        const val WALLPAPER_GALLERY_LIMIT = 4
        const val STILL_FOLDER = "wallpaper-stills"
        private const val STILL_FOLDER_MARK = "/$STILL_FOLDER/"

        /** One clip per process, so the lobby and the settings agree on what this start drew. */
        val randomClip: String by lazy {
            LiveWallpaper.ALL.filter { it.kind == LiveWallpaperKind.VIDEO }.random().id
        }
        private const val KEY_WHATS_NEW = "whats-new-shown"
        private const val KEY_PREMIUM_THEME = "premium-theme"
        private const val KEY_PREMIUM_PREV_ACCENT = "premium-prev-accent"
        private const val KEY_LOOK_TIER_SEEN = "look-tier-seen"
        private const val KEY_LOOK_TIER = "look-tier"
        private const val KEY_LOOK_PERKS = "look-perks"
        private const val KEY_UPDATE_OFFER_SKIPPED = "update-offer-skipped"
        private const val KEY_THEME = "theme-mode"
        private const val KEY_LANGUAGE = "language"
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
        private const val KEY_AUTO_JUMP = "auto-jump"
        private const val KEY_PHONE_BLOCKED = "phone-blocked-packs"
        private const val KEY_PHONE_STATS = "phone-pack-stats"
        private const val KEY_PHONE_BLOCKED_AT = "phone-blocked-packs-at"
        private const val KEY_CRASH_REPORTS = "crash-reports"
        private const val KEY_FRIENDS_OVERLAY = "friends-overlay"
        const val SOUND_OFF = "off"
        const val SOUND_NOTIFY = "notify"
        const val SOUND_ALL = "all"
        const val SOUND_VOLUME_DEFAULT = 60
        val SOUND_MODES = setOf(SOUND_OFF, SOUND_NOTIFY, SOUND_ALL)
    }
}
