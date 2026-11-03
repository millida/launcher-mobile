package net.millida.launcher.ui.settings

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.millida.launcher.Container
import net.millida.launcher.FriendsTab
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Prefs
import net.millida.launcher.Screen
import net.millida.launcher.ShopState
import net.millida.launcher.UiState
import net.millida.launcher.core.friends.ReferralsClient
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.looks.AppIcon
import net.millida.launcher.core.looks.IconRule
import net.millida.launcher.core.looks.LookAccess
import net.millida.launcher.core.looks.PremiumLook
import net.millida.launcher.ui.PlusTier
import net.millida.launcher.ui.isCustomAccent
import net.millida.launcher.ui.isKnownAccent

data class LookState(
    val theme: String = PremiumLook.OFF,
    val access: LookAccess = LookAccess(),
    val gallery: List<String> = emptyList(),
    val randomWallpaper: Boolean = false,
    val characterDance: Boolean = true,
    val icon: AppIcon = AppIcon.DEFAULT,
) {
    /** What the home screen should show: the choice, or the default while the choice is locked. */
    val shownIcon: AppIcon get() = AppIcon.effective(icon, access)
}

/**
 * The launcher's look beyond colour and light: the PLUS and Diamond theme and
 * what the account opens for it, kept for the whole process so the window,
 * the settings and the lobby read one answer (the desktop's premiumTheme.ts
 * and usePlus).
 */
class LookModel private constructor(private val context: Context) {
    private val prefs = Prefs(context)
    private val container = Container.get(context)
    private val referrals = ReferralsClient(container.millidaAuth)
    private val refreshing = Mutex()
    private var refreshedAt = 0L
    private val mutable = MutableStateFlow(
        LookState(
            theme = prefs.premiumTheme,
            access = prefs.lookAccess,
            gallery = prefs.wallpaperGallery,
            randomWallpaper = prefs.wallpaperRandom,
            characterDance = prefs.characterDance,
            icon = AppIcon.of(prefs.appIcon),
        ),
    )
    val state: StateFlow<LookState> = mutable.asStateFlow()

    /** Reads the ladder rewards; a failed request keeps the perks the account had. */
    suspend fun refresh(signedIn: Boolean, force: Boolean = false) {
        if (!signedIn) return
        refreshing.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - refreshedAt < REFRESH_GAP_MS) return
            refreshedAt = now
            val perks = attempt(tr("награды приглашений")) { referrals.overview().perks } ?: return
            mutable.update { it.copy(access = it.access.copy(perks = perks.value)) }
            prefs.lookAccess = mutable.value.access
        }
    }

    /**
     * Follows the subscription the shop read, the one tier the whole app shows.
     * Only a real answer may come here: a failed request says nothing about the
     * subscription, and treating it as «ended» repainted a subscriber's launcher
     * on a bad network.
     */
    fun followTier(tier: PlusTier?, model: LauncherViewModel) {
        val value = when (tier) {
            PlusTier.PLUS -> PremiumLook.TIER_PLUS
            PlusTier.DIAMOND -> PremiumLook.TIER_DIAMOND
            null -> null
        }
        mutable.update { it.copy(access = it.access.copy(tier = value)) }
        prefs.lookAccess = mutable.value.access
        onTier(value, model)
    }

    fun chooseTheme(theme: String, model: LauncherViewModel) {
        if (!PremiumLook.allowed(theme, mutable.value.access.tier)) {
            model.open(Screen.PLUS)
            return
        }
        setTheme(theme, model)
    }

    /** An ordinary colour means the ordinary launcher: the theme goes off and the colour stays. */
    fun pickAccent(id: String, model: LauncherViewModel) {
        leaveTheme()
        if (isCustomAccent(id)) model.setCustomAccent(id) else model.setAccentId(id)
    }

    fun pickPremiumAccent(id: String, model: LauncherViewModel) {
        if (!PremiumLook.accentAllowed(id, mutable.value.access.tier)) {
            model.open(Screen.PLUS)
            return
        }
        rememberAccent(model)
        model.setAccentId(id)
    }

    /** The choice is kept at once; the home screen follows when the launcher leaves the screen. */
    fun pickIcon(icon: AppIcon, model: LauncherViewModel) {
        if (!AppIcon.unlocked(icon, mutable.value.access)) {
            if (icon.rule == IconRule.FRIENDS) {
                model.social.setTab(FriendsTab.INVITE)
                model.open(Screen.FRIENDS)
            } else {
                model.open(Screen.PLUS)
            }
            return
        }
        prefs.appIcon = icon.id
        mutable.update { it.copy(icon = icon) }
        if (AppIconSwitch.current(context) != icon) model.notice(tr("Иконка сменится, когда свернёшь лаунчер"))
    }

    /** Called when the launcher leaves the screen: only then may the icon change, see [AppIconSwitch]. */
    fun applyIcon() {
        val target = mutable.value.shownIcon
        if (AppIconSwitch.current(context) == target || !AppIconSwitch.idle(context)) return
        AppIconSwitch.apply(context, target)
    }

    fun setCharacterDance(on: Boolean) {
        prefs.characterDance = on
        mutable.update { it.copy(characterDance = on) }
    }

    /** The random mode draws a clip at once, as the desktop does when it is switched on. */
    fun setRandomWallpaper(on: Boolean, model: LauncherViewModel) {
        prefs.wallpaperRandom = on
        mutable.update { it.copy(randomWallpaper = on) }
        if (on) model.setLiveWallpaper(Prefs.randomClip)
    }

    /** Any background picked by hand ends the random mode, as `pick` does on the desktop. */
    fun leaveRandom() {
        if (!mutable.value.randomWallpaper) return
        prefs.wallpaperRandom = false
        mutable.update { it.copy(randomWallpaper = false) }
    }

    /** The newest picture goes first; what falls past the limit is returned for deletion. */
    fun addToGallery(path: String): List<String> {
        val all = listOf(path) + mutable.value.gallery.filter { it != path }
        val kept = all.take(Prefs.WALLPAPER_GALLERY_LIMIT)
        prefs.wallpaperGallery = kept
        mutable.update { it.copy(gallery = kept) }
        return all.drop(Prefs.WALLPAPER_GALLERY_LIMIT)
    }

    fun removeFromGallery(path: String): List<String> {
        val kept = mutable.value.gallery.filter { it != path }
        prefs.wallpaperGallery = kept
        mutable.update { it.copy(gallery = kept) }
        return kept
    }

    private fun onTier(tier: String?, model: LauncherViewModel) {
        val before = prefs.lookTierSeen
        prefs.lookTierSeen = tier ?: TIER_NONE
        val theme = mutable.value.theme
        if (tier == null) {
            if (theme != PremiumLook.OFF) setTheme(PremiumLook.OFF, model)
            else if (PremiumLook.isPremiumAccent(model.ui.value.accentId)) restoreAccent(model)
            return
        }
        if (!PremiumLook.allowed(theme, tier)) setTheme(PremiumLook.themeFor(tier), model)
        if (!PremiumLook.accentAllowed(model.ui.value.accentId, tier)) restoreAccent(model)
        val known = before != TIER_UNKNOWN
        if (known && PremiumLook.paid(before.takeIf { it != TIER_NONE }, tier)) {
            setTheme(PremiumLook.themeFor(tier), model)
        }
    }

    private fun setTheme(theme: String, model: LauncherViewModel) {
        val current = mutable.value
        if (theme == current.theme || !PremiumLook.allowed(theme, current.access.tier)) return
        val accent = PremiumLook.accentOf(theme)
        if (accent == null) {
            restoreAccent(model)
        } else {
            rememberAccent(model)
            model.setAccentId(accent)
        }
        prefs.premiumTheme = theme
        mutable.update { it.copy(theme = theme) }
    }

    private fun leaveTheme() {
        if (mutable.value.theme == PremiumLook.OFF) return
        prefs.premiumTheme = PremiumLook.OFF
        mutable.update { it.copy(theme = PremiumLook.OFF) }
    }

    private fun rememberAccent(model: LauncherViewModel) {
        val current = model.ui.value.accentId
        if (!PremiumLook.isPremiumAccent(current)) prefs.premiumPrevAccent = current
    }

    private fun restoreAccent(model: LauncherViewModel) {
        val back = prefs.premiumPrevAccent.takeIf { isKnownAccent(it) && !PremiumLook.isPremiumAccent(it) } ?: DEFAULT_ACCENT
        model.setAccentId(back)
        prefs.premiumPrevAccent = ""
    }

    private class Answer<T>(val value: T)

    private suspend fun <T> attempt(what: String, block: suspend () -> T): Answer<T>? = try {
        Answer(withContext(Dispatchers.IO) { block() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.i("millida", "вид лаунчера: $what не прочиталась: ${error.message}")
        null
    }

    companion object {
        const val TIER_UNKNOWN = ""
        const val TIER_NONE = "none"
        private const val DEFAULT_ACCENT = "green"
        private const val REFRESH_GAP_MS = 30_000L

        @Volatile
        private var instance: LookModel? = null

        fun get(context: Context): LookModel = instance ?: synchronized(this) {
            instance ?: LookModel(context.applicationContext).also { instance = it }
        }
    }
}

@Composable
fun rememberLookModel(): LookModel {
    val context = LocalContext.current
    return remember { LookModel.get(context) }
}

/** Whether the window wears the PLUS or Diamond theme's lines. */
@Composable
fun premiumThemeOn(): Boolean {
    val look by rememberLookModel().state.collectAsStateWithLifecycle()
    return look.theme != PremiumLook.OFF
}

private data class TierAnswer(val tier: PlusTier?)

/** The shop's tier once it has heard from the server, null while it has nothing to tell. */
private fun answeredTier(shop: ShopState): TierAnswer? =
    if (shop.signedIn && (shop.plus.data != null || shop.store.data != null)) TierAnswer(shop.tier) else null

/**
 * Keeps the look in step with the account for the whole app: the tier follows
 * the shop's answer, which the shop rereads on sign-in and whenever the
 * launcher comes back to the front (a payment made in the browser); the ladder
 * rewards are read here on the same moments.
 */
@Composable
fun LookEffects(state: UiState, model: LauncherViewModel) {
    val look = rememberLookModel()
    val scope = rememberCoroutineScope()
    val signedIn = state.millidaSignedIn
    LaunchedEffect(look, model) {
        model.shop.state
            .map(::answeredTier)
            .distinctUntilChanged()
            .filterNotNull()
            .collect { answer -> look.followTier(answer.tier, model) }
    }
    LaunchedEffect(signedIn) { look.refresh(signedIn, force = true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, signedIn) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> scope.launch { look.refresh(signedIn) }
                Lifecycle.Event.ON_STOP -> look.applyIcon()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
