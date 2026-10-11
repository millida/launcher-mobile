package net.millida.launcher

import android.Manifest
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.AccountScreen
import net.millida.launcher.ui.BedrockScreen
import net.millida.launcher.ui.BuildScreen
import net.millida.launcher.ui.CabinetScreen
import net.millida.launcher.ui.CatalogScreen
import net.millida.launcher.ui.ChatScreen
import net.millida.launcher.ui.FriendsScreen
import net.millida.launcher.ui.HomeScreen
import net.millida.launcher.ui.HostingManageScreen
import net.millida.launcher.ui.HostingScreen
import net.millida.launcher.ui.LocalModalStack
import net.millida.launcher.ui.LocalWideBack
import net.millida.launcher.ui.LocalWideHub
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.WideBack
import net.millida.launcher.ui.WideHub
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.LocalToastOverlay
import net.millida.launcher.ui.LocalTour
import net.millida.launcher.ui.LocalUiFeedback
import net.millida.launcher.ui.LoginDialog
import net.millida.launcher.ui.catalog.MillidaDialogsHost
import net.millida.launcher.ui.catalog.InstallProgressHost
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MAlertBadge
import net.millida.launcher.ui.MCounterBadge
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaTheme
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.ModalStack
import net.millida.launcher.ui.MoreScreen
import net.millida.launcher.ui.Onboarding
import net.millida.launcher.ui.PacksScreen
import net.millida.launcher.ui.PixelCorners
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.ProvideUiFeedback
import net.millida.launcher.ui.ProvideWideLayout
import net.millida.launcher.ui.ScreenWave
import net.millida.launcher.ui.ScreenshotsScreen
import net.millida.launcher.ui.ServersScreen
import net.millida.launcher.ui.SettingsScreen
import net.millida.launcher.ui.SkinsScreen
import net.millida.launcher.core.update.UpdateVerdict
import net.millida.launcher.ui.UpdateOfferSheet
import net.millida.launcher.ui.LowMemoryNoticeDialog
import net.millida.launcher.ui.LowMemoryPlayDialog
import net.millida.launcher.ui.MillidaModOfferDialog
import net.millida.launcher.ui.TourRegistry
import net.millida.launcher.ui.WhatsNewDialog
import net.millida.launcher.ui.social.CallLayer
import net.millida.launcher.ui.premium.PremiumScreen
import net.millida.launcher.ui.home.AmbientLayer
import net.millida.launcher.ui.home.WIDE_LOBBY_DOCK_HEIGHT
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.millidaTokens
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed
import net.millida.launcher.ui.shop.ShopScreen
import net.millida.launcher.ui.shop.ShopWelcome
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

private val BAR_HEIGHT = 64.dp
private val BAR_LIFT = 8.dp
private val TAB_HEIGHT = 54.dp
private val TAB_FOOT = 5.dp
private const val TOAST_LIMIT = 3
private const val TOAST_INFO_MS = 3_500L
private const val TOAST_ERROR_MS = 6_000L

class MainActivity : ComponentActivity() {
    private val model: LauncherViewModel by viewModels()

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        paintWindow(startsDark(prefs), prefs.accentId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val player = Container.get(this).uiSoundPlayer
        setContent {
            val state by model.ui.collectAsStateWithLifecycle()
            val dark = when (state.themeMode) {
                Prefs.THEME_DARK -> true
                Prefs.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark, state.accentId) { paintWindow(dark, state.accentId) }
            MillidaTheme(dark = dark, accentId = state.accentId) {
                ProvideWideLayout {
                    ProvideUiFeedback(player) {
                        val modals = remember { ModalStack() }
                        val overlay = remember(model) { toastOverlay(model) }
                        val tour = remember { TourRegistry() }
                        CompositionLocalProvider(
                            LocalModalStack provides modals,
                            LocalToastOverlay provides overlay,
                            LocalTour provides tour,
                        ) {
                            ToastSounds(state.messages)
                            Root(model, onLeave = { moveTaskToBack(true) })
                        ScreenWave(state.screen, state.accentId)
                            if (!state.onboarding) CallLayer(state, model)
                            AmbientLayer(state, model)
                            if (state.onboarding) {
                                Onboarding(
                                    state.bundledRuntime,
                                    onDone = model::finishOnboarding,
                                    onTour = {
                                        model.finishOnboarding()
                                        model.ambient.startTour()
                                    },
                                )
                                if (modals.layers.isEmpty()) ToastHost(state, model, barShown = false, atTop = false)
                            }
                            state.releaseNotes?.let { notes ->
                                WhatsNewDialog(state.appVersion, notes, onClose = model::closeWhatsNew)
                            }
                            val lowMemory = state.lowMemoryNotice
                            if (lowMemory != null && !state.onboarding && state.releaseNotes == null) {
                                LowMemoryNoticeDialog(lowMemory, onClose = model::closeLowMemoryNotice)
                            }
                            if (!state.onboarding && state.releaseNotes == null) ShopWelcome(model)
                            state.lowMemoryPlay?.let { pending ->
                                LowMemoryPlayDialog(
                                    pending.notice,
                                    onPlay = model::confirmLowMemoryPlay,
                                    onCancel = model::cancelLowMemoryPlay,
                                )
                            }
                            if (state.millidaModOffer != null) {
                                MillidaModOfferDialog(
                                    onInstall = model::acceptMillidaModOffer,
                                    onDecline = model::declineMillidaModOffer,
                                    onCancel = model::cancelMillidaModOffer,
                                )
                            }
                            val offered = (state.updateVerdict as? UpdateVerdict.Available)?.release
                            if (state.updateOffer && offered != null && state.releaseNotes == null && !state.onboarding) {
                                UpdateOfferSheet(
                                    offered,
                                    state.updateProgress,
                                    onUpdate = model::downloadUpdate,
                                    onLater = model::postponeUpdate,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) deliverLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliverLink(intent)
    }

    private fun deliverLink(intent: Intent?) {
        FriendAlerts.openingOf(intent)?.let { opening ->
            model.openFromAlert(opening)
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        val scheme = data.scheme.orEmpty()
        if (!"millida".equals(scheme, ignoreCase = true) && !"https".equals(scheme, ignoreCase = true)) return
        model.handleLink(data.toString())
    }

    override fun onResume() {
        super.onResume()
        model.setResumed(true)
    }

    override fun onPause() {
        model.setResumed(false)
        super.onPause()
    }

    private fun startsDark(prefs: Prefs): Boolean = when (prefs.themeMode) {
        Prefs.THEME_DARK -> true
        Prefs.THEME_LIGHT -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun paintWindow(dark: Boolean, accentId: String) {
        val clear = android.graphics.Color.TRANSPARENT
        val bars = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        window.setBackgroundDrawable(ColorDrawable(millidaTokens(dark, accentId).bg.toArgb()))
    }
}

private data class Tab(val screen: Screen, val label: String, val icon: MillidaIcon)

private val tabs = listOf(
    Tab(Screen.HOME, "Играть", MIcons.home),
    Tab(Screen.CATALOG, "Каталог", MIcons.catalog),
    Tab(Screen.SKINS, "Персонаж", MIcons.character),
    Tab(Screen.SHOP, "Магазин", MIcons.shop),
    Tab(Screen.MORE, "Ещё", MIcons.more),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Root(model: LauncherViewModel, onLeave: () -> Unit) {
    val state by model.ui.collectAsStateWithLifecycle()
    val shop by model.shop.state.collectAsStateWithLifecycle()
    val modals = LocalModalStack.current
    val colors = millida
    val showBar = state.screen.isRoot
    val wide = LocalWideLayout.current
    val hub = remember { WideHub(mutableStateOf(false)) }

    // Before Android 12 Back on the root finishes the activity and cancels the installs
    // running in the view model scope; from 12 on the system moves the task back itself.
    BackHandler(enabled = state.screen != Screen.HOME || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        if (!model.back()) onLeave()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        if (wide) {
            WideShell(state, model, hub)
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .consumeWindowInsets(WindowInsets.navigationBars)
                        .consumeWindowInsets(PaddingValues(bottom = if (showBar) BAR_LIFT + BAR_HEIGHT else 0.dp))
                        .windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    ScreenHost(state, model)
                }
                BottomChrome(
                    visible = showBar,
                    current = rootTabOf(state.screen),
                    moreBadge = state.friendsBadge,
                    shopAlert = shop.badge > 0,
                    onPick = { model.open(it) },
                )
            }
        }
        LoginDialog(state, model)
        InstallProgressHost(state.screen, model)
        MillidaDialogsHost(model)
        state.linkJoin?.let { ask ->
            MConfirmDialog(
                title = "Зайти на сервер?",
                text = if (ask.name == ask.address) "«${ask.address}»" else "«${ask.name}» (${ask.address})",
                confirm = "Запустить",
                onConfirm = model::confirmLinkJoin,
                onDismiss = model::dismissLinkJoin,
                danger = false,
            )
        }
        if (modals.layers.isEmpty() && !state.onboarding) {
            ToastHost(
                state,
                model,
                barShown = showBar && !wide,
                atTop = false,
                overDock = wide && state.screen == Screen.HOME && !hub.library,
            )
        }
    }
}

/**
 * Landscape follows the desktop shell, which has no sections bar at all: the
 * lobby is a full-window scene whose tiles open the sections, and every other
 * screen carries the strip with «← Лобби». The lobby lays itself out under the
 * cutout; other screens are kept inside the safe area.
 */
@Composable
private fun WideShell(state: UiState, model: LauncherViewModel, hub: WideHub) {
    // With the library open the lobby screen shows the library, so a step back to it is
    // «Назад»; the library itself and its resources tab lead out to the lobby proper.
    val hubScreen = state.screen == Screen.HOME || state.screen == Screen.CATALOG
    val toLobby = model.backLeadsHome() && (!hub.library || hubScreen)
    val back = WideBack(
        label = if (toLobby) "Лобби" else "Назад",
        badge = state.friendsBadge,
        onBack = { model.back() },
    )
    CompositionLocalProvider(LocalWideHub provides hub, LocalWideBack provides back) {
        ScreenHost(state, model)
    }
}

/**
 * How deep a screen sits, for the slide direction. In landscape the sections
 * the lobby opens are a step below it, as the desktop's are; on the phone they
 * are tabs beside it.
 */
private fun Screen.depth(wide: Boolean): Int = when (this) {
    Screen.HOME -> 0
    Screen.CATALOG, Screen.SKINS, Screen.SHOP, Screen.MORE -> if (wide) 1 else 0
    Screen.CHAT, Screen.HOSTING_MANAGE -> 2
    else -> 1
}

private fun <T> stepSpec(reduced: Boolean, durationMs: Int, steps: Int): FiniteAnimationSpec<T> =
    if (reduced) snap() else tween(durationMs, easing = MillidaMotion.steps(steps))

private fun AnimatedContentTransitionScope<Screen>.screenTransition(reduced: Boolean, wide: Boolean): ContentTransform {
    val from = initialState
    val to = targetState
    if (reduced) {
        return fadeIn(tween(150)) togetherWith fadeOut(tween(150))
    }
    val duration = MillidaMotion.screen + 20
    return when {
        to.depth(wide) > from.depth(wide) -> ContentTransform(
            targetContentEnter = slideInHorizontally(stepSpec(false, duration, 5)) { it / 6 } +
                fadeIn(stepSpec(false, duration, 5)),
            initialContentExit = slideOutHorizontally(stepSpec(false, duration, 5)) { -it / 12 } +
                fadeOut(stepSpec(false, duration, 5)),
            targetContentZIndex = 1f,
            sizeTransform = null,
        )
        to.depth(wide) < from.depth(wide) -> ContentTransform(
            targetContentEnter = slideInHorizontally(stepSpec(false, duration, 5)) { -it / 12 } +
                fadeIn(stepSpec(false, duration, 5)),
            initialContentExit = slideOutHorizontally(stepSpec(false, duration, 5)) { it / 6 } +
                fadeOut(stepSpec(false, duration, 5)),
            targetContentZIndex = -1f,
            sizeTransform = null,
        )
        from.isRoot && to.isRoot -> {
            val direction = if (rootTabs.indexOf(to) >= rootTabs.indexOf(from)) 1 else -1
            ContentTransform(
                targetContentEnter = slideInHorizontally(stepSpec(false, 150, 3)) { direction * it / 14 } +
                    fadeIn(stepSpec(false, 150, 3)),
                initialContentExit = fadeOut(stepSpec(false, 110, 2)),
                sizeTransform = null,
            )
        }
        else -> ContentTransform(
            targetContentEnter = fadeIn(stepSpec(false, 150, 3)),
            initialContentExit = fadeOut(stepSpec(false, 110, 2)),
            sizeTransform = null,
        )
    }
}

@Composable
private fun ScreenHost(state: UiState, model: LauncherViewModel) {
    val reduced = LocalReducedMotion.current
    val wide = LocalWideLayout.current
    val saved = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = state.screen,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { screenTransition(reduced, wide) },
        label = "screen",
    ) { screen ->
        if (wide && screen != Screen.HOME) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            ) {
                KeptScreen(screen, state, model, saved)
            }
        } else {
            KeptScreen(screen, state, model, saved)
        }
    }
}

@Composable
private fun KeptScreen(screen: Screen, state: UiState, model: LauncherViewModel, saved: SaveableStateHolder) {
    if (screen.keepsState) {
        saved.SaveableStateProvider(screen.name) { ScreenFor(screen, state, model) }
    } else {
        ScreenFor(screen, state, model)
    }
}

// These screens show one build, chat or server at a time, and a saved tab or scroll
// of one would land on the next one opened.
private val Screen.keepsState: Boolean
    get() = this != Screen.BUILD && this != Screen.CHAT && this != Screen.HOSTING_MANAGE

@Composable
private fun ScreenFor(screen: Screen, state: UiState, model: LauncherViewModel) {
    when (screen) {
        Screen.HOME -> HomeScreen(state, model)
        Screen.BUILD -> BuildScreen(state, model)
        Screen.SERVERS -> ServersScreen(state, model)
        Screen.CATALOG -> CatalogScreen(state, model)
        Screen.ACCOUNT -> AccountScreen(state, model)
        Screen.MORE -> MoreScreen(state, model)
        Screen.SETTINGS -> SettingsScreen(state, model)
        Screen.SKINS -> SkinsScreen(state, model)
        Screen.HOSTING -> HostingScreen(state, model)
        Screen.BEDROCK -> BedrockScreen(state, model)
        Screen.HOSTING_MANAGE -> HostingManageScreen(state, model)
        Screen.PACKS -> PacksScreen(state, model)
        Screen.FRIENDS -> FriendsScreen(state, model)
        Screen.CHAT -> ChatScreen(state, model)
        Screen.CABINET -> CabinetScreen(state, model)
        Screen.SCREENSHOTS -> ScreenshotsScreen(state, model)
        Screen.SHOP -> ShopScreen(state, model)
        Screen.PREMIUM -> PremiumScreen(state, model)
    }
}

@Composable
private fun BottomChrome(
    visible: Boolean,
    current: Screen,
    moreBadge: Int,
    shopAlert: Boolean,
    onPick: (Screen) -> Unit,
) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = visible,
            enter = expandVertically(stepSpec(reduced, 200, 4), expandFrom = Alignment.Top) +
                slideInVertically(stepSpec(reduced, 200, 4)) { it / 2 },
            exit = shrinkVertically(stepSpec(reduced, 160, 4), shrinkTowards = Alignment.Top) +
                slideOutVertically(stepSpec(reduced, 160, 4)) { it / 2 },
            label = "dock",
        ) {
            BottomBar(current, moreBadge, shopAlert, onPick)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(if (visible) colors.surface else colors.bg)
                .windowInsetsBottomHeight(WindowInsets.navigationBars),
        )
    }
}

@Composable
private fun BottomBar(current: Screen, moreBadge: Int, shopAlert: Boolean, onPick: (Screen) -> Unit) {
    val colors = millida
    Box(
        Modifier
            .fillMaxWidth()
            .height(BAR_LIFT + BAR_HEIGHT),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .pixelSurface(
                    fill = colors.surface,
                    cut = MillidaCut.plate,
                    frame = PixelFrame(top = colors.dockOutline, topWidth = 2.dp, bottomWidth = 0.dp),
                    corners = PixelCorners.Top,
                ),
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            tabs.forEach { tab ->
                TabSlot(
                    tab = tab,
                    active = tab.screen == current,
                    badge = if (tab.screen == Screen.MORE) moreBadge else 0,
                    alert = tab.screen == Screen.SHOP && shopAlert,
                    modifier = Modifier.weight(1f),
                    onClick = { if (tab.screen != current) onPick(tab.screen) },
                )
            }
        }
    }
}

@Composable
private fun TabSlot(
    tab: Tab,
    active: Boolean,
    badge: Int,
    alert: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (active) null else UiSoundEvent.Nav)
    val press = rememberPressProgress(pressed && !active)
    val lift by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = stepSpec(reduced, 160, 4),
        label = "tab-lift",
    )
    val ink = if (active) colors.onAccent else colors.fgMuted
    Box(
        modifier
            .fillMaxHeight()
            .selectable(active, interaction, null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .padding(start = 3.dp, end = 3.dp, bottom = TAB_FOOT)
                .fillMaxWidth()
                .height(TAB_HEIGHT)
                .graphicsLayer { translationY = round(-lift * (BAR_LIFT + 2.dp).toPx()) }
                .pressShift(press),
        ) {
            if (active) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pixelSurface(colors.accent, MillidaCut.control, PixelFrame.Button, dots = true),
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 6.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MIcon(tab.icon, size = 24.dp, color = ink)
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label,
                    style = MillidaType.tabLabel.copy(fontWeight = if (active) FontWeight.Black else FontWeight.ExtraBold),
                    color = if (active) colors.onAccent else colors.fgSubtle,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val badgeSpot = Modifier.align(Alignment.TopCenter).offset(x = 16.dp, y = (-4).dp)
            if (badge > 0) MCounterBadge(badge, badgeSpot) else if (alert) MAlertBadge(badgeSpot)
        }
    }
}

private fun toastOverlay(model: LauncherViewModel): @Composable (Boolean) -> Unit = { atTop ->
    val state by model.ui.collectAsStateWithLifecycle()
    ToastHost(state, model, barShown = false, atTop = atTop)
}

// A toast plate is rebuilt when it moves into or out of a modal window, so the sound
// is played here, once per message, and not by the plate.
@Composable
private fun ToastSounds(messages: List<Message>) {
    val feedback = LocalUiFeedback.current
    var announced by remember { mutableLongStateOf(0L) }
    val newest = messages.take(TOAST_LIMIT).lastOrNull()
    LaunchedEffect(newest?.id) {
        if (newest == null || newest.id <= announced) return@LaunchedEffect
        announced = newest.id
        when (newest.tone) {
            ToastTone.Success -> feedback.play(UiSoundEvent.Success)
            ToastTone.Error -> feedback.play(UiSoundEvent.Error)
            ToastTone.Info -> Unit
        }
    }
}

@Composable
private fun ToastHost(
    state: UiState,
    model: LauncherViewModel,
    barShown: Boolean,
    atTop: Boolean,
    overDock: Boolean = false,
) {
    val messages = state.messages.take(TOAST_LIMIT)
    val edge = if (atTop) WindowInsets.statusBars else WindowInsets.navigationBars.union(WindowInsets.ime)
    val bottom = when {
        atTop -> 0.dp
        overDock -> WIDE_LOBBY_DOCK_HEIGHT + 10.dp
        barShown -> BAR_HEIGHT + BAR_LIFT + 10.dp
        else -> 16.dp
    }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(edge)
            .padding(start = 16.dp, end = 16.dp, top = if (atTop) 12.dp else 0.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(8.dp, if (atTop) Alignment.Top else Alignment.Bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        messages.forEach { message ->
            key(message.id) {
                ToastPlate(message, fromTop = atTop, onGone = { model.consume(message.id) })
            }
        }
    }
}

@Composable
private fun ToastPlate(message: Message, fromTop: Boolean, onGone: () -> Unit) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val appear = remember { Animatable(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    var leaving by remember { mutableStateOf(false) }
    val gone by rememberUpdatedState(onGone)
    val flick = with(density) { 600.dp.toPx() }

    fun leave(direction: Int) {
        if (leaving) return
        leaving = true
        scope.launch {
            if (!reduced) {
                if (direction != 0) {
                    animate(dragX, direction * width.toFloat(), animationSpec = tween(120, easing = MillidaMotion.steps(3))) { value, _ ->
                        dragX = value
                    }
                } else {
                    appear.animateTo(0f, tween(150, easing = MillidaMotion.steps(3)))
                }
            }
            gone()
        }
    }

    fun settle(velocity: Float) {
        if (abs(dragX) > width * 0.35f || abs(velocity) > flick) {
            leave(if (dragX + velocity * 0.05f >= 0f) 1 else -1)
        } else {
            scope.launch {
                animate(dragX, 0f, animationSpec = tween(140, easing = MillidaMotion.steps(3))) { value, _ ->
                    dragX = value
                }
            }
        }
    }

    LaunchedEffect(message.id) {
        if (reduced) appear.snapTo(1f) else appear.animateTo(1f, tween(200, easing = MillidaMotion.steps(4)))
        delay(if (message.tone == ToastTone.Error) TOAST_ERROR_MS else TOAST_INFO_MS)
        leave(0)
    }

    val dragState = rememberDraggableState { delta -> if (!leaving) dragX += delta }
    val tone = when (message.tone) {
        ToastTone.Success -> colors.accent
        ToastTone.Info -> colors.info
        ToastTone.Error -> colors.danger
    }
    val icon = when (message.tone) {
        ToastTone.Success -> MIcons.check
        ToastTone.Info -> MIcons.info
        ToastTone.Error -> MIcons.alert
    }
    val rise = if (fromTop) (-12).dp else 12.dp
    Row(
        Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .onSizeChanged { width = max(1, it.width) }
            .graphicsLayer {
                val shown = appear.value
                translationY = round((1f - shown) * rise.toPx())
                translationX = dragX
                alpha = shown * (1f - (abs(dragX) / width).coerceIn(0f, 1f) * 0.6f)
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStopped = { velocity -> settle(velocity) },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { leave(0) },
            )
            .pixelSurface(
                fill = colors.raised,
                cut = MillidaCut.plate,
                frame = colors.plateFrame().copy(left = tone, leftWidth = 4.dp),
            )
            .padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(icon, size = 18.dp, color = tone)
        Spacer(Modifier.width(11.dp))
        Text(
            message.text,
            style = MillidaType.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
            color = colors.fg,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
