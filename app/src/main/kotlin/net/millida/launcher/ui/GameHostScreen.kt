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
import net.millida.launcher.GameUiState
import net.millida.launcher.LAUNCH_STAGES
import net.millida.launcher.Prefs
import net.millida.launcher.SdlHost
import net.millida.launcher.core.hud.HudAction
import net.millida.launcher.runtime.DesktopInput
import net.millida.launcher.runtime.GameSurfaceView
import net.millida.launcher.runtime.GlfwKeys
import kotlin.math.round
import kotlin.random.Random

private const val TIP_MS = 5_000L

private val TIPS = listOf(
    "Держи палец на месте — так копают блоки",
    "Коснись хотбара внизу, чтобы взять слот",
    "Проведи по хотбару — слоты листаются",
    "Кнопки двигаются: Меню → Настроить управление",
    "Клавиатура, мышь и геймпад тоже работают",
    "Esc ставит игру на паузу и закрывает чат",
)

private class SurfaceRef {
    var view: GameSurfaceView? = null
}

@Composable
fun GameHostScreen(model: GameLaunchModel, onLeave: () -> Unit, onRelaunch: () -> Unit) {
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
    var lookMode by remember { mutableStateOf(false) }
    var flying by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf(Offset.Unspecified) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
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
        val launcherLayerOpen = menuOpen || editing || friendsOpen || typing
        if (grabbed && desktopInput && !launcherLayerOpen && DesktopInput.mouseConnected(context)) {
            surface.view?.capturePointer()
        }
    }

    // A keyboard, a mouse and a gamepad talk to the focused view only, so the
    // game view takes the focus back whenever the launcher's own layer is done.
    LaunchedEffect(typing, menuOpen, editing, friendsOpen) {
        if (menuOpen || editing || friendsOpen) surface.view?.releaseCapture()
        if (!typing && !menuOpen && !editing && !friendsOpen) surface.view?.takeFocus()
    }

    fun tapKey(code: Int) {
        sink.key(code, code, true)
        sink.key(code, code, false)
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
                    it.onSurfaceReady = { ready, width, height -> model.surfaceReady(ready, width, height) }
                    it.onSurfaceGone = { model.surfaceGone() }
                    it.onSurfaceFailure = { error -> model.surfaceFailed(error) }
                    it.onDesktopInput = { desktopInput = true }
                    surface.view = it
                    it.post { it.takeFocus() }
                }
            },
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
                        HudAction.KEYBOARD -> typing = !typing
                        HudAction.MENU -> menuOpen = true
                        HudAction.CHAT -> {
                            tapKey(GlfwKeys.T)
                            typing = true
                        }
                        HudAction.NONE -> Unit
                    }
                },
                onFly = { flying = !flying },
                onSelect = { selectedControl = it },
                onChange = { control -> model.updateHud(hud.replace(control)) },
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
                    model.updateHudProfiles(next)
                },
                onPreset = { preset ->
                    selectedControl = null
                    model.updateHud(hud.switchTo(preset))
                },
                onChange = { control -> model.updateHud(hud.replace(control)) },
                onLayout = { next -> model.updateHud(next) },
                onRemove = { id ->
                    selectedControl = null
                    model.updateHud(hud.remove(id))
                },
                onAdd = { control -> model.updateHud(hud.add(control)) },
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
            LoadingScreen(state = state, onExit = model::stop)
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
                    placeholder = "Текст для игры",
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Send,
                    onImeAction = ::submitTyping,
                    focusRequester = typingFocus,
                    autoCorrectEnabled = false,
                )
                Spacer(Modifier.width(8.dp))
                MButton(
                    text = "Ввод",
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
                    label = "Закрыть",
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
                lookMode = lookMode,
                onToggleMode = {
                    menuOpen = false
                    lookMode = !lookMode
                },
                onExit = { confirmExit = true },
                onConfirmExit = {
                    confirmExit = false
                    menuOpen = false
                    model.stop()
                },
                onCancelExit = { confirmExit = false },
            )
        }

        if (state.finished) {
            FinishedPanel(state = state, model = model, onLeave = onLeave, onRelaunch = onRelaunch)
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
private fun LoadingScreen(state: GameUiState, onExit: () -> Unit) {
    val colors = millida
    val note = if (state.booting) "Игра загружается" else state.note
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
                "Выйти",
                onClick = onExit,
                modifier = Modifier.align(Alignment.TopEnd),
                icon = MIcons.close,
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
    onExit: () -> Unit,
    onConfirmExit: () -> Unit,
    onCancelExit: () -> Unit,
    lookMode: Boolean,
    onToggleMode: () -> Unit,
    onFriends: (() -> Unit)? = null,
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
                Text("Выйти из игры?", style = MillidaType.dialogTitle, color = colors.fg)
                Text(
                    if (packLayer) {
                        "Игра закроется сразу — сначала выйди в главное меню игры, чтобы мир сохранился"
                    } else {
                        "Игра сохранит мир и закроется"
                    },
                    style = MillidaType.body,
                    color = colors.fgMuted,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MTextButton("Отмена", onClick = onCancelExit)
                    MDangerButton("Выйти", onClick = onConfirmExit, icon = MIcons.logout, size = MButtonSize.Medium)
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Пауза", style = MillidaType.dialogTitle, color = colors.fg)
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
                    "Продолжить",
                    onClick = onResume,
                    modifier = Modifier.fillMaxWidth(),
                    icon = MIcons.play,
                    size = MButtonSize.Large,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MSecondaryButton("Пауза игры", onClick = onGamePause, modifier = Modifier.weight(1f), icon = MIcons.pause)
                    MSecondaryButton("Чат", onClick = onChat, modifier = Modifier.weight(1f), icon = MIcons.chat)
                }
                if (onFriends != null) {
                    MSecondaryButton("Чат друзей", onClick = onFriends, modifier = Modifier.fillMaxWidth(), icon = MIcons.friends)
                }
                MSecondaryButton(
                    "Настроить управление",
                    onClick = onControls,
                    modifier = Modifier.fillMaxWidth(),
                    icon = MIcons.settings,
                )
                // Some packs never hand the cursor back or never take it: the mode can be forced by hand.
                MTextButton(
                    if (lookMode) "Режим курсора" else "Режим обзора",
                    onClick = onToggleMode,
                    modifier = Modifier.fillMaxWidth(),
                )
                MDangerButton(
                    "Выйти",
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth(),
                    icon = MIcons.logout,
                    size = MButtonSize.Medium,
                )
            }
        }
    }
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
        Text("Новое управление", style = MillidaType.body, color = colors.fg, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        MTextButton("Вернуть старое", onClick = onRestore, icon = MIcons.refresh)
        MIconButton(MIcons.close, onClick = onDismiss, label = "Закрыть")
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
        !failed -> "Игра закрылась"
        state.started -> "Игра вылетела"
        else -> "Игра не запустилась"
    }
    val reason = state.cause ?: state.error
    val logHeight = (LocalConfiguration.current.screenHeightDp * 0.4f).dp

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
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
                if (failed) {
                    MPrimaryButton("Ещё раз", onClick = onRelaunch, icon = MIcons.refresh)
                    MSecondaryButton("Отправить отчёт", onClick = { model.shareReport() }, icon = MIcons.share)
                } else {
                    MPrimaryButton("В лаунчер", onClick = onLeave, icon = MIcons.back)
                    MSecondaryButton("Ещё раз", onClick = onRelaunch, icon = MIcons.refresh)
                }
                if (log.isNotBlank()) {
                    MTextButton(
                        if (showLog) "Скрыть лог" else "Лог",
                        onClick = { showLog = !showLog },
                        icon = if (showLog) MIcons.chevronUp else MIcons.list,
                    )
                }
                if (failed) {
                    MTextButton("В лаунчер", onClick = onLeave, icon = MIcons.back)
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
                        "Скопировать лог",
                        onClick = {
                            clipboard.setText(AnnotatedString(log))
                            model.showNotice("Лог скопирован")
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
internal fun TouchControlsSwitch() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var on by remember { mutableStateOf(prefs.touchControlsWithKeyboard) }
    MSwitchRow(
        "Кнопки на экране с клавиатурой и мышью",
        "Не прятать управление, когда подключены клавиатура или мышь",
        on,
    ) { next ->
        prefs.touchControlsWithKeyboard = next
        on = next
    }
}
