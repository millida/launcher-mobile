package net.millida.launcher.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.millida.launcher.GameLaunchModel
import net.millida.launcher.applyGameCutout
import net.millida.launcher.core.game.GameResolution
import net.millida.launcher.BlackOffer
import net.millida.launcher.GameUiState
import net.millida.launcher.LAUNCH_STAGES
import net.millida.launcher.Prefs
import net.millida.launcher.SdlHost
import net.millida.launcher.core.hud.HudAction
import net.millida.launcher.core.hud.HudControl
import net.millida.launcher.core.hud.HudHistory
import net.millida.launcher.core.hud.HudLayout
import net.millida.launcher.core.hud.HudProfiles
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.runtime.BootWatch
import net.millida.launcher.runtime.DesktopInput
import net.millida.launcher.runtime.GameSurfaceView
import net.millida.launcher.runtime.GlfwKeys
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.random.Random

private const val TIP_MS = 5_000L

private val TIPS get() = listOf(
    tr("Держи палец на месте — так копают блоки"),
    tr("Коснись хотбара внизу, чтобы взять слот"),
    tr("Проведи по хотбару — слоты листаются"),
    tr("Кнопки двигаются: Меню → Настроить управление"),
    tr("Клавиатура, мышь и геймпад тоже работают"),
    tr("Esc ставит игру на паузу и закрывает чат"),
)

private class SurfaceRef {
    var view: GameSurfaceView? = null
}

@Composable
fun GameHostScreen(
    model: GameLaunchModel,
    onLeave: () -> Unit,
    onRelaunch: () -> Unit,
    onSwitchVersion: (String) -> Unit = {},
) {
    val state by model.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val sink = model.inputSink
    val grabbed by model.grabbed.collectAsStateWithLifecycle()
    val hudProfiles by model.hudProfiles.collectAsStateWithLifecycle()
    val hud = hudProfiles.layout
    val surface = remember { SurfaceRef() }
    var editing by remember { mutableStateOf(false) }
    var selectedControl by remember { mutableStateOf<String?>(null) }
    var hudHistory by remember { mutableStateOf(HudHistory()) }
    var snapToGrid by remember { mutableStateOf(true) }
    var resolutionPercent by remember { mutableIntStateOf(model.resolutionPercent) }
    var resolution by remember { mutableFloatStateOf(model.resolution) }
    var fpsShown by remember { mutableStateOf(model.fpsMeter) }
    var underCutout by remember { mutableStateOf(model.underCutout) }
    val readFps = remember(model) { model::currentFps }
    val gamePrefs = remember { Prefs(context) }
    val gyroAvailable = remember { hasGyroscope(context) }
    var gyroOn by remember { mutableStateOf(gamePrefs.gyroLook) }
    var accelerationOn by remember { mutableStateOf(gamePrefs.touchAcceleration) }
    val directKeyboard = remember { gamePrefs.directKeyboard }
    var lookMode by remember { mutableStateOf(false) }
    var flying by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf(Offset.Unspecified) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var logOverlay by remember { mutableStateOf(false) }
    // The first touch is the last moment when the game can be told the window
    // size without the player noticing: the touch sets the pointer right after.
    var sized by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val typingFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val playing = state.running && !state.booting
    val friendsEnabled = remember { Prefs(context).friendsOverlay }
    val friends = rememberFriendsBridge(friendsEnabled)
    var friendsOpen by remember { mutableStateOf(false) }
    val keepTouchControls = remember { Prefs(context).touchControlsWithKeyboard }
    // A keyboard or mouse in use hides the on-screen controls; a finger on the screen brings them back.
    var desktopInput by remember { mutableStateOf(DesktopInput.connected(context)) }
    var mousePointer by remember { mutableStateOf(false) }
    val touchControls = keepTouchControls || !desktopInput

    DisposableEffect(context) {
        val stop = DesktopInput.listen(context) { desktopInput = DesktopInput.connected(context) }
        onDispose { stop() }
    }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.let { SdlHost.attachActivity(it) }
        // Not the sensor one: with auto-rotate switched off a part of the
        // firmwares never turns the screen, and the game is left in portrait.
        // The user one is always landscape and flips sides only when the
        // player allows rotation.
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = activity?.window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.let { SdlHost.detachActivity(it) }
        }
    }

    LaunchedEffect(state.closed) {
        if (state.closed) onLeave()
    }

    LaunchedEffect(typing) {
        if (typing) {
            delay(60)
            runCatching { typingFocus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    // The game taking the cursor hands a connected mouse straight to the camera,
    // as on a desktop; without a mouse the touch camera takes over instead.
    LaunchedEffect(grabbed) {
        lookMode = grabbed
        model.grabChanged(grabbed)
        if (!grabbed) surface.view?.releaseCapture()
        val launcherLayerOpen = menuOpen || editing || friendsOpen || typing || logOpen
        if (grabbed && desktopInput && !launcherLayerOpen && DesktopInput.mouseConnected(context)) {
            surface.view?.capturePointer()
        }
    }

    // A keyboard, a mouse and a gamepad talk to the focused view only, so the
    // game view takes the focus back whenever the launcher's own layer is done.
    LaunchedEffect(typing, menuOpen, editing, friendsOpen, logOpen) {
        if (menuOpen || editing || friendsOpen || logOpen) surface.view?.releaseCapture()
        if (!typing && !menuOpen && !editing && !friendsOpen && !logOpen) surface.view?.takeFocus()
    }

    fun tapKey(code: Int) {
        sink.key(code, code, true)
        sink.key(code, code, false)
    }

    fun openKeyboard() {
        if (directKeyboard) {
            surface.view?.showDirectKeyboard()
        } else {
            typing = true
        }
    }

    fun toggleKeyboard() {
        if (directKeyboard) {
            surface.view?.toggleDirectKeyboard()
        } else {
            typing = !typing
        }
    }

    fun editHud(next: HudProfiles, step: String? = null) {
        val before = model.hudProfiles.value
        if (next == before) return
        hudHistory = hudHistory.record(before, step)
        model.updateHudProfiles(next)
    }

    fun editLayout(next: HudLayout, step: String? = null) = editHud(model.hudProfiles.value.withLayout(next), step)

    fun editControl(control: HudControl, step: String?) = editLayout(model.hudProfiles.value.layout.replace(control), step)

    fun undoHud() {
        val (previous, rest) = hudHistory.undo() ?: return
        hudHistory = rest
        if (selectedControl != null && previous.layout.controls.none { it.id == selectedControl }) selectedControl = null
        model.updateHudProfiles(previous)
    }

    fun submitTyping() {
        tapKey(GlfwKeys.ENTER)
        draft = ""
        typing = false
    }

    BackHandler {
        when {
            state.finished -> onLeave()
            confirmExit -> confirmExit = false
            logOpen -> logOpen = false
            friendsOpen -> friendsOpen = false
            typing -> typing = false
            editing -> {
                editing = false
                selectedControl = null
            }
            playing -> menuOpen = !menuOpen
            else -> Unit
        }
    }

    // No background of our own: the game draws into its own layer underneath the
    // window, and anything opaque painted here hides the picture completely.
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GameSurfaceView(ctx).also {
                    it.sink = sink
                    it.resolutionPercent = resolutionPercent
                    it.gamepad = gamePrefs.gamepadMap
                    it.onSurfaceReady = { ready, width, height -> model.surfaceReady(ready, width, height) }
                    it.onSurfaceGone = { model.surfaceGone() }
                    it.onSurfaceFailure = { error -> model.surfaceFailed(error) }
                    it.onDesktopInput = { desktopInput = true }
                    surface.view = it
                    it.post { it.takeFocus() }
                }
            },
            update = { it.resolutionPercent = resolutionPercent },
        )

        if (!editing) {
            GameTouchArea(
                sink = sink,
                lookMode = lookMode,
                sensitivity = state.lookSensitivity,
                lookTap = hud.lookTap,
                holdDelay = hud.shownHoldDelay,
                guiScale = state.guiScale,
                forceUnicode = state.forceUnicode,
                haptics = state.haptics,
                resolution = resolution,
                acceleration = accelerationOn,
                invertY = hud.invertY,
                onCursor = { cursor = it },
                onFirstTouch = {
                    if (!sized) {
                        sized = true
                        model.focusGame()
                    }
                },
                onMouseLook = { surface.view?.capturePointer() },
                onMouse = {
                    desktopInput = true
                    mousePointer = true
                },
                onFinger = {
                    desktopInput = false
                    mousePointer = false
                },
            )
        }

        // A real mouse already shows the system pointer; a second arrow would trail behind it.
        if (!lookMode && !mousePointer && cursor != Offset.Unspecified) {
            CursorMark(cursor)
        }

        if (editing && playing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { selectedControl = null } },
            )
        }

        if (playing && (touchControls || editing)) {
            GameHud(
                layout = hud,
                lookMode = lookMode,
                flying = flying,
                editing = editing,
                selectedId = selectedControl,
                sink = sink,
                guiScale = state.guiScale,
                forceUnicode = state.forceUnicode,
                haptics = state.haptics,
                onAction = { action ->
                    when (action) {
                        HudAction.POINTER_MODE -> lookMode = !lookMode
                        HudAction.KEYBOARD -> toggleKeyboard()
                        HudAction.MENU -> menuOpen = true
                        HudAction.CHAT -> {
                            tapKey(GlfwKeys.T)
                            openKeyboard()
                        }
                        HudAction.NONE -> Unit
                    }
                },
                onFly = { flying = !flying },
                onSelect = { selectedControl = it },
                onChange = { control -> editControl(control, "move:${control.id}") },
                resolution = resolution,
                snap = snapToGrid,
                onResize = { control -> editControl(control, "size:${control.id}") },
                onGestureEnd = { hudHistory = hudHistory.seal() },
            )
        }

        if (playing && !editing && hudProfiles.legacyNote) {
            LegacyHudNote(
                onRestore = { model.updateHudProfiles(hudProfiles.restoreLegacy()) },
                onDismiss = { model.updateHudProfiles(hudProfiles.dismissNote()) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 56.dp, start = 16.dp, end = 16.dp),
            )
        }

        if (editing && playing) {
            HudEditorPanel(
                profiles = hudProfiles,
                selected = hud.controls.firstOrNull { it.id == selectedControl },
                sensitivity = state.lookSensitivity,
                onProfiles = { next ->
                    selectedControl = null
                    editHud(next)
                },
                onPreset = { preset ->
                    selectedControl = null
                    editLayout(hud.switchTo(preset))
                },
                onChange = { control -> editControl(control, null) },
                onSlide = { control -> editControl(control, "slide:${control.id}") },
                onLayout = { next -> editLayout(next, "layout") },
                onRemove = { id ->
                    selectedControl = null
                    editLayout(hud.remove(id))
                },
                onAdd = { control -> editLayout(hud.add(control)) },
                canUndo = hudHistory.canUndo,
                onUndo = ::undoHud,
                snap = snapToGrid,
                onSnap = { snapToGrid = it },
                gyro = if (gyroAvailable) gyroOn else null,
                onGyro = { on ->
                    gyroOn = on
                    gamePrefs.gyroLook = on
                },
                acceleration = accelerationOn,
                onAcceleration = { on ->
                    accelerationOn = on
                    gamePrefs.touchAcceleration = on
                },
                onSensitivity = model::setLookSensitivity,
                onNotice = model::showNotice,
                onDone = {
                    editing = false
                    selectedControl = null
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .widthIn(max = 640.dp),
            )
        }

        if (!state.finished && (!state.running || state.booting)) {
            LoadingScreen(state = state, onExit = model::stop, onLog = { logOpen = true })
        }

        if (typing && playing) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(12.dp)
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .pixelSurface(millida.raised, MillidaCut.plate, millida.plateFrame())
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MField(
                    value = draft,
                    onValueChange = { next ->
                        val common = draft.commonPrefixWith(next).length
                        repeat(draft.codePointCount(common, draft.length)) { tapKey(GlfwKeys.BACKSPACE) }
                        val added = next.substring(common)
                        var index = 0
                        while (index < added.length) {
                            val codePoint = Character.codePointAt(added, index)
                            sink.char(codePoint)
                            index += Character.charCount(codePoint)
                        }
                        draft = next
                    },
                    placeholder = tr("Текст для игры"),
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Send,
                    onImeAction = ::submitTyping,
                    focusRequester = typingFocus,
                    autoCorrectEnabled = false,
                )
                Spacer(Modifier.width(8.dp))
                MButton(
                    text = tr("Ввод"),
                    onClick = ::submitTyping,
                    variant = MButtonVariant.Primary,
                    size = MButtonSize.Small,
                    icon = MIcons.send,
                )
                Spacer(Modifier.width(6.dp))
                MIconButton(
                    MIcons.close,
                    onClick = {
                        draft = ""
                        typing = false
                    },
                    label = tr("Закрыть"),
                )
            }
        }

        if (playing && friends != null) {
            GameFriendsLayer(
                client = friends,
                open = friendsOpen,
                onOpen = { friendsOpen = true },
                onClose = { friendsOpen = false },
            )
        }

        if (menuOpen && playing) {
            PauseMenu(
                buildName = state.buildName,
                packLayer = state.packLayer && !state.sdlWindow,
                confirmExit = confirmExit,
                onResume = { menuOpen = false },
                onGamePause = {
                    menuOpen = false
                    tapKey(GlfwKeys.ESCAPE)
                },
                onChat = {
                    menuOpen = false
                    tapKey(GlfwKeys.T)
                    typing = true
                },
                onFriends = if (friends != null) {
                    {
                        menuOpen = false
                        friendsOpen = true
                    }
                } else {
                    null
                },
                onControls = {
                    menuOpen = false
                    editing = true
                },
                onLog = {
                    menuOpen = false
                    logOpen = true
                },
                lookMode = lookMode,
                onToggleMode = {
                    menuOpen = false
                    lookMode = !lookMode
                },
                screen = PauseScreenOptions(
                    resolutionPercent = resolutionPercent,
                    onResolution = { percent ->
                        resolution = model.changeResolution(percent)
                        resolutionPercent = model.resolutionPercent
                    },
                    fpsShown = fpsShown,
                    fpsAvailable = state.packLayer && !state.sdlWindow,
                    onFps = { shown ->
                        fpsShown = shown
                        model.fpsMeter = shown
                    },
                    underCutout = underCutout,
                    onCutout = { under ->
                        underCutout = under
                        model.underCutout = under
                        (context as? Activity)?.window?.let { applyGameCutout(it, under) }
                    },
                    onKeyboard = {
                        menuOpen = false
                        openKeyboard()
                    },
                ),
                onExit = { confirmExit = true },
                onConfirmExit = {
                    confirmExit = false
                    menuOpen = false
                    model.stop()
                },
                onCancelExit = { confirmExit = false },
            )
        }

        if (logOverlay && playing && !logOpen && !editing && !menuOpen) {
            GameLogOverlay(
                model = model,
                onClose = { logOverlay = false },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 12.dp, bottom = 64.dp, end = 12.dp),
            )
        }

        if (logOpen && !state.finished) {
            GameLogPanel(
                model = model,
                onClose = { logOpen = false },
                onOverlay = {
                    logOpen = false
                    logOverlay = true
                },
            )
        }

        GyroLookEffect(
            active = gyroOn && playing && lookMode && !editing && !menuOpen && !typing && !friendsOpen && !logOpen,
            sink = sink,
            sensitivity = state.lookSensitivity,
        )

        if (fpsShown && playing && !editing) {
            FpsMeter(
                read = readFps,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 12.dp, top = 8.dp),
            )
        }

        if (state.finished) {
            FinishedPanel(state = state, model = model, onLeave = onLeave, onRelaunch = onRelaunch)
        }

        state.blackOffer?.takeIf { !state.finished && !state.closing }?.let { offer ->
            BlackOfferPlate(
                offer = offer,
                onSwitch = onSwitchVersion,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
            )
        }

        state.notice?.let { text ->
            NoticePlate(
                text = text,
                onDismiss = model::dismissNotice,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 12.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun CursorMark(position: Offset) {
    Canvas(Modifier.fillMaxSize()) {
        val arrow = Path().apply {
            moveTo(position.x, position.y)
            lineTo(position.x, position.y + 34f)
            lineTo(position.x + 9f, position.y + 26f)
            lineTo(position.x + 15f, position.y + 39f)
            lineTo(position.x + 22f, position.y + 35f)
            lineTo(position.x + 16f, position.y + 23f)
            lineTo(position.x + 26f, position.y + 22f)
            close()
        }
        drawPath(arrow, MillidaPixel.divider, style = Stroke(width = 5f))
        drawPath(arrow, MillidaPixel.white)
    }
}

@Composable
private fun LoadingScreen(state: GameUiState, onExit: () -> Unit, onLog: () -> Unit) {
    val colors = millida
    val note = when {
        !state.booting -> state.note
        state.bootSeconds > 0 -> tr("Игра загружается · {0}", BootWatch.clock(state.bootSeconds * 1000L))
        else -> tr("Игра загружается")
    }
    val progress = when {
        state.closing || state.booting || state.stage <= 0 -> null
        else -> state.stage / LAUNCH_STAGES.toFloat()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 440.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.buildName.isNotEmpty()) {
                Text(
                    state.buildName,
                    style = MillidaType.barTitle,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MPixelLoader()
                Spacer(Modifier.width(10.dp))
                Text(
                    note,
                    style = MillidaType.body,
                    color = colors.fgMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))
            MProgressBar(progress)
            if (!state.closing) {
                Spacer(Modifier.height(20.dp))
                TipLine()
            }
        }
        if (!state.closing) {
            MTextButton(
                tr("Выйти"),
                onClick = onExit,
                modifier = Modifier.align(Alignment.TopEnd),
                icon = MIcons.close,
            )
            MTextButton(
                tr("Лог"),
                onClick = onLog,
                modifier = Modifier.align(Alignment.TopStart),
                icon = MIcons.list,
            )
        }
    }
}

@Composable
private fun TipLine() {
    val colors = millida
    var index by remember { mutableIntStateOf(Random.nextInt(TIPS.size)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TIP_MS)
            index = (index + 1) % TIPS.size
        }
    }
    AnimatedContent(
        targetState = index,
        transitionSpec = {
            fadeIn(tween(200, easing = MillidaMotion.steps(3))) togetherWith
                fadeOut(tween(150, easing = MillidaMotion.steps(2)))
        },
        label = "tip",
    ) { shown ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.info, size = 14.dp, color = colors.fgFaint)
            Spacer(Modifier.width(8.dp))
            Text(
                TIPS[shown],
                style = MillidaType.small,
                color = colors.fgSubtle,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun PauseMenu(
    buildName: String,
    packLayer: Boolean,
    confirmExit: Boolean,
    onResume: () -> Unit,
    onGamePause: () -> Unit,
    onChat: () -> Unit,
    onControls: () -> Unit,
    onLog: () -> Unit,
    onExit: () -> Unit,
    onConfirmExit: () -> Unit,
    onCancelExit: () -> Unit,
    lookMode: Boolean,
    onToggleMode: () -> Unit,
    onFriends: (() -> Unit)? = null,
    screen: PauseScreenOptions? = null,
) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) appear.animateTo(1f, tween(210, easing = MillidaMotion.steps(3)))
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(MillidaPixel.modalScrim.copy(alpha = MillidaPixel.modalScrim.alpha * appear.value)) }
            .pointerInput(confirmExit) {
                detectTapGestures { if (confirmExit) onCancelExit() else onResume() }
            }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val shown = appear.value
                    val scale = 0.96f + 0.04f * shown
                    scaleX = scale
                    scaleY = scale
                    translationY = round((1f - shown) * 10.dp.toPx())
                    alpha = shown
                }
                .pointerInput(Unit) { detectTapGestures { } }
                .pixelSurface(colors.raised, MillidaCut.plate, colors.plateFrame())
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (confirmExit) {
                Text(tr("Выйти из игры?"), style = MillidaType.dialogTitle, color = colors.fg)
                Text(
                    if (packLayer) {
                        tr("Игра закроется сразу — сначала выйди в главное меню игры, чтобы мир сохранился")
                    } else {
                        tr("Игра сохранит мир и закроется")
                    },
                    style = MillidaType.body,
                    color = colors.fgMuted,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MTextButton(tr("Отмена"), onClick = onCancelExit)
                    MDangerButton(tr("Выйти"), onClick = onConfirmExit, icon = MIcons.logout, size = MButtonSize.Medium)
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(tr("Пауза"), style = MillidaType.dialogTitle, color = colors.fg)
                    if (buildName.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            buildName,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MillidaType.small,
                            color = colors.fgFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                MPrimaryButton(
                    tr("Продолжить"),
                    onClick = onResume,
                    modifier = Modifier.fillMaxWidth(),
                    icon = MIcons.play,
                    size = MButtonSize.Large,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MSecondaryButton(tr("Пауза игры"), onClick = onGamePause, modifier = Modifier.weight(1f), icon = MIcons.pause)
                    MSecondaryButton(tr("Чат"), onClick = onChat, modifier = Modifier.weight(1f), icon = MIcons.chat)
                }
                if (onFriends != null) {
                    MSecondaryButton(tr("Чат друзей"), onClick = onFriends, modifier = Modifier.fillMaxWidth(), icon = MIcons.friends)
                }
                MSecondaryButton(
                    tr("Настроить управление"),
                    onClick = onControls,
                    modifier = Modifier.fillMaxWidth(),
                    icon = MIcons.settings,
                )
                MSecondaryButton(tr("Лог игры"), onClick = onLog, modifier = Modifier.fillMaxWidth(), icon = MIcons.list)
                screen?.let { ScreenOptions(it) }
                // Some packs never hand the cursor back or never take it: the mode can be forced by hand.
                MTextButton(
                    if (lookMode) tr("Режим курсора") else tr("Режим обзора"),
                    onClick = onToggleMode,
                    modifier = Modifier.fillMaxWidth(),
                )
                MDangerButton(
                    tr("Выйти"),
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth(),
                    icon = MIcons.logout,
                    size = MButtonSize.Medium,
                )
            }
        }
    }
}

/** What the pause menu can change about the picture without leaving the game. */
private class PauseScreenOptions(
    val resolutionPercent: Int,
    val onResolution: (Int) -> Unit,
    val fpsShown: Boolean,
    val fpsAvailable: Boolean,
    val onFps: (Boolean) -> Unit,
    val underCutout: Boolean,
    val onCutout: (Boolean) -> Unit,
    val onKeyboard: () -> Unit,
)

@Composable
private fun ScreenOptions(options: PauseScreenOptions) {
    var percent by remember(options.resolutionPercent) { mutableIntStateOf(options.resolutionPercent) }
    MSectionTitle(tr("ЭКРАН"))
    MSecondaryButton(tr("Клавиатура"), onClick = options.onKeyboard, modifier = Modifier.fillMaxWidth(), icon = MIcons.menuKey)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Разрешение игры"), modifier = Modifier.weight(1f), style = MillidaType.small, color = millida.fgSubtle, maxLines = 1)
        Text("$percent%", style = MillidaType.small, color = millida.fg, maxLines = 1)
    }
    MSlider(
        value = percent.toFloat(),
        onValueChange = { percent = GameResolution.clamp(it.roundToInt()) },
        valueRange = GameResolution.MIN.toFloat()..GameResolution.FULL.toFloat(),
        steps = (GameResolution.FULL - GameResolution.MIN) / GameResolution.STEP - 1,
        onValueChangeFinished = { options.onResolution(percent) },
    )
    if (options.fpsAvailable) {
        MSwitchRow(tr("Счётчик кадров"), tr("Число кадров и график в углу экрана"), options.fpsShown, options.onFps)
    }
    MSwitchRow(tr("Во весь экран"), tr("Игра рисует и под вырезом камеры"), options.underCutout, options.onCutout)
}

@Composable
private fun LegacyHudNote(onRestore: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    Row(
        modifier
            .widthIn(max = 520.dp)
            .pixelSurface(
                fill = colors.raised,
                cut = MillidaCut.plate,
                frame = colors.plateFrame().copy(left = colors.accent, leftWidth = 4.dp),
            )
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(MIcons.info, size = 16.dp, color = colors.accentText)
        Spacer(Modifier.width(10.dp))
        Text(tr("Новое управление"), style = MillidaType.body, color = colors.fg, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        MTextButton(tr("Вернуть старое"), onClick = onRestore, icon = MIcons.refresh)
        MIconButton(MIcons.close, onClick = onDismiss, label = tr("Закрыть"))
    }
}

@Composable
private fun BlackOfferPlate(offer: BlackOffer, onSwitch: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    Column(
        modifier
            .widthIn(max = 420.dp)
            .pixelSurface(
                fill = colors.raised,
                cut = MillidaCut.plate,
                frame = colors.plateFrame().copy(left = colors.accent, leftWidth = 4.dp),
            )
            .padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (offer.version != null) tr("На {0} экран чёрный", offer.from) else tr("На {0} экран чёрный, а сборка с модами", offer.from),
            style = MillidaType.body,
            color = colors.fg,
        )
        if (offer.version != null) {
            MPrimaryButton(tr("Играть на {0}", offer.version), onClick = { onSwitch(offer.version) }, icon = MIcons.play)
        } else {
            Text(tr("Выбери версию ниже 26.3 — моды пока не переключаем"), style = MillidaType.small, color = colors.fgMuted)
        }
    }
}

@Composable
private fun NoticePlate(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    Row(
        modifier
            .widthIn(max = 520.dp)
            .pixelSurface(
                fill = colors.raised,
                cut = MillidaCut.plate,
                frame = colors.plateFrame().copy(left = colors.accent, leftWidth = 4.dp),
            )
            .pointerInput(text) { detectTapGestures { onDismiss() } }
            .padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(MIcons.info, size = 16.dp, color = colors.accentText)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MillidaType.body, color = colors.fg)
    }
}

@Composable
private fun FinishedPanel(
    state: GameUiState,
    model: GameLaunchModel,
    onLeave: () -> Unit,
    onRelaunch: () -> Unit,
) {
    val colors = millida
    val clipboard = LocalClipboardManager.current
    val log by produceState(initialValue = "", model) {
        value = withContext(Dispatchers.IO) { model.logTail() }
    }
    var showLog by remember { mutableStateOf(false) }
    val failed = state.error != null
    val title = when {
        !failed -> tr("Игра закрылась")
        state.started -> tr("Игра вылетела")
        else -> tr("Игра не запустилась")
    }
    val reason = state.cause ?: state.error
    val logHeight = (effectiveScreenHeightDp() * 0.4f).dp

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .pixelSurface(
                            fill = if (failed) colors.dangerSoft else colors.accentSoft,
                            cut = MillidaCut.control,
                            frame = colors.plateFrame(),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    MIcon(
                        if (failed) MIcons.alert else MIcons.check,
                        size = 24.dp,
                        color = if (failed) colors.danger else colors.accentText,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MillidaType.screenTitle, color = colors.fg, maxLines = 1)
                    if (state.buildName.isNotEmpty()) {
                        Text(
                            state.buildName,
                            style = MillidaType.small,
                            color = colors.fgFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (reason != null) {
                Text(reason, style = MillidaType.body, color = colors.fgMuted)
            }

            MButtonRow {
                if (failed && state.retryHelps) {
                    MPrimaryButton(tr("Ещё раз"), onClick = onRelaunch, icon = MIcons.refresh)
                    MSecondaryButton(tr("Отправить отчёт"), onClick = { model.shareReport() }, icon = MIcons.share)
                } else if (failed) {
                    MPrimaryButton(tr("Отправить отчёт"), onClick = { model.shareReport() }, icon = MIcons.share)
                } else {
                    MPrimaryButton(tr("В лаунчер"), onClick = onLeave, icon = MIcons.back)
                    MSecondaryButton(tr("Ещё раз"), onClick = onRelaunch, icon = MIcons.refresh)
                }
                if (log.isNotBlank()) {
                    MTextButton(
                        if (showLog) tr("Скрыть лог") else tr("Лог"),
                        onClick = { showLog = !showLog },
                        icon = if (showLog) MIcons.chevronUp else MIcons.list,
                    )
                }
                if (failed) {
                    MTextButton(tr("В лаунчер"), onClick = onLeave, icon = MIcons.back)
                }
            }

            if (showLog && log.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = logHeight)
                            .pixelSurface(colors.inset, MillidaCut.control, colors.wellFrame())
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(
                            log,
                            style = MillidaType.small.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                            color = colors.fgSubtle,
                        )
                    }
                    MTextButton(
                        tr("Скопировать лог"),
                        onClick = {
                            clipboard.setText(AnnotatedString(log))
                            model.showNotice(tr("Лог скопирован"))
                        },
                        icon = MIcons.copy,
                    )
                }
            }
        }
    }
}

/** The settings row for [Prefs.touchControlsWithKeyboard], next to the other in-game switches. */
@Composable
internal fun DirectKeyboardSwitch() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var on by remember { mutableStateOf(prefs.directKeyboard) }
    MSwitchRow(
        tr("Клавиатура сразу в игру"),
        tr("Каждая клавиша уходит в игру при нажатии — для модов и горячих клавиш. Выключено: текст сначала набирается в поле"),
        on,
    ) { next ->
        prefs.directKeyboard = next
        on = next
    }
}

@Composable
internal fun TouchControlsSwitch() {

    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var on by remember { mutableStateOf(prefs.touchControlsWithKeyboard) }
    MSwitchRow(
        tr("Кнопки на экране с клавиатурой и мышью"),
        tr("Не прятать управление, когда подключены клавиатура или мышь"),
        on,
    ) { next ->
        prefs.touchControlsWithKeyboard = next
        on = next
    }
}
