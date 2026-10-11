package net.millida.launcher.ui

import android.graphics.Point
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.media.UiSoundEvent
import kotlin.math.round

internal val screenPadding = 16.dp

private const val KEPT_OPEN_CHECK_MS = 120L

private val WIDE_SHEET_MAX_WIDTH = 560.dp

@Composable
internal fun ScreenTitle(title: String, subtitle: String? = null) {
    Column {
        Text(
            title,
            style = MillidaType.screenTitle,
            color = millida.fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MillidaType.small,
                color = millida.fgFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/** The platform dim would stack on top of the kit's own solid scrim, so the window drops it. */
@Composable
internal fun DropWindowDim() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
    }
}

/**
 * Some firmware lays a full-height dialog window out below the status bar, so
 * its bottom sits past the screen edge and the last button of a sheet is cut.
 * The window cannot be trusted to fit, so the part that hangs off the display
 * is measured and given back as padding.
 */
@Composable
internal fun rememberWindowOverflow(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    var overflow by remember { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val measure = ViewTreeObserver.OnGlobalLayoutListener {
            val next = offscreenBottom(view)
            if (next != overflow) overflow = next
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(measure)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(measure) }
    }
    return with(density) { overflow.toDp() }
}

private fun offscreenBottom(view: View): Int {
    val root = view.rootView
    if (root.height == 0) return 0
    val at = IntArray(2)
    root.getLocationOnScreen(at)
    val displayBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        view.context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds?.bottom ?: return 0
    } else {
        val size = Point()
        @Suppress("DEPRECATION")
        view.display?.getRealSize(size) ?: return 0
        size.y
    }
    return (at[1] + root.height - displayBottom).coerceAtLeast(0)
}

/**
 * The activity goes under the camera cutout (enableEdgeToEdge), a dialog window
 * does not by default: the system then moves the full-height window down by the
 * status bar and the bottom of every sheet ends up below the screen edge.
 */
@Composable
internal fun ExtendWindowUnderCutout() {
    val view = LocalView.current
    SideEffect {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@SideEffect
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val attributes = window.attributes
        if (attributes.layoutInDisplayCutoutMode != mode) {
            attributes.layoutInDisplayCutoutMode = mode
            window.attributes = attributes
        }
    }
}

@Stable
internal class ModalStack {
    val layers = mutableStateListOf<Any>()
}

internal val LocalModalStack = staticCompositionLocalOf { ModalStack() }

internal val LocalToastOverlay = staticCompositionLocalOf<@Composable (atTop: Boolean) -> Unit> { {} }

// Every modal is its own platform window above the activity, so toasts must be drawn
// inside the topmost one; a list, not a counter, because a lower sheet may close first.
@Composable
internal fun rememberModalLayer(): Any {
    val stack = LocalModalStack.current
    val layer = remember { Any() }
    DisposableEffect(stack, layer) {
        stack.layers.add(layer)
        onDispose { stack.layers.remove(layer) }
    }
    return layer
}

@Composable
internal fun ModalToasts(layer: Any) {
    val stack = LocalModalStack.current
    val overlay = LocalToastOverlay.current
    if (stack.layers.lastOrNull() === layer) overlay(true)
}

@Composable
private fun ModalWindow(
    onDismiss: () -> Unit,
    scrim: Color,
    enterMs: Int,
    exitMs: Int,
    steps: Int,
    content: @Composable BoxScope.(appear: State<Float>, close: () -> Unit) -> Unit,
) {
    val feedback = LocalUiFeedback.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val appear = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    val layer = rememberModalLayer()
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            feedback.play(UiSoundEvent.Close)
            scope.launch {
                if (reduced) appear.snapTo(0f) else appear.animateTo(0f, tween(exitMs, easing = MillidaMotion.steps(steps)))
                dismiss()
                delay(KEPT_OPEN_CHECK_MS)
                closing = false
                if (reduced) appear.snapTo(1f) else appear.animateTo(1f, tween(enterMs, easing = MillidaMotion.steps(steps)))
            }
        }
    }
    LaunchedEffect(Unit) {
        feedback.play(UiSoundEvent.Open)
        if (reduced) appear.snapTo(1f) else appear.animateTo(1f, tween(enterMs, easing = MillidaMotion.steps(steps)))
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DropWindowDim()
        ExtendWindowUnderCutout()
        val overflow = rememberWindowOverflow()
        val shown = appear.asState()
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = overflow)
                .drawBehind { drawRect(scrim.copy(alpha = scrim.alpha * shown.value)) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = close,
                ),
        ) {
            content(shown, close)
            ModalToasts(layer)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MDialog(
    title: String,
    onDismiss: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    ModalWindow(
        onDismiss = onDismiss,
        scrim = MillidaPixel.modalScrim,
        enterMs = 210,
        exitMs = 150,
        steps = 3,
    ) { appear, _ ->
        val colors = millida
        Column(
            Modifier
                .align(Alignment.Center)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val shown = appear.value
                    val scale = 0.96f + 0.04f * shown
                    scaleX = scale
                    scaleY = scale
                    translationY = round((1f - shown) * 10.dp.toPx())
                    alpha = shown
                }
                .swallowTaps()
                .pixelSurface(colors.raised, MillidaCut.plate, colors.plateFrame())
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp),
        ) {
            Text(title, style = MillidaType.dialogTitle, color = colors.fg)
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                content = body,
            )
            Spacer(Modifier.height(18.dp))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
    }
}

/**
 * "Are you sure" in the kit's words: a title, one line, cancel and the action.
 * [onConfirm] only runs the action; the caller hides the dialog, as with [MDialog].
 */
@Composable
fun MConfirmDialog(
    title: String,
    text: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
    cancel: String = "Отмена",
) {
    MDialog(
        title = title,
        onDismiss = onDismiss,
        body = {
            if (text != null) {
                Text(text, style = MillidaType.body, color = millida.fgMuted)
            }
        },
        actions = {
            MTextButton(cancel, onClick = onDismiss)
            if (danger) {
                MDangerButton(confirm, onClick = onConfirm, size = MButtonSize.Medium)
            } else {
                MPrimaryButton(confirm, onClick = onConfirm)
            }
        },
    )
}

@Composable
fun MBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val wide = LocalWideLayout.current
    ModalWindow(
        onDismiss = onDismiss,
        scrim = if (wide) MillidaPixel.modalScrim else MillidaPixel.sheetScrim,
        enterMs = 240,
        exitMs = 180,
        steps = 4,
    ) { appear, close ->
        if (wide) {
            CenteredSheet(modifier, title, appear, content)
        } else {
            DockedSheet(modifier, title, appear, close, content)
        }
    }
}

/**
 * A wide window shows a sheet the way the desktop launcher shows its modals:
 * a plate in the middle, without the grab handle a thumb would need.
 */
@Composable
private fun BoxScope.CenteredSheet(
    modifier: Modifier,
    title: String?,
    appear: State<Float>,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = millida
    Column(
        modifier
            .align(Alignment.Center)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .widthIn(max = WIDE_SHEET_MAX_WIDTH)
            .fillMaxWidth()
            .graphicsLayer {
                val shown = appear.value
                val scale = 0.96f + 0.04f * shown
                scaleX = scale
                scaleY = scale
                translationY = round((1f - shown) * 10.dp.toPx())
                alpha = shown
            }
            .swallowTaps()
            .pixelSurface(colors.raised, MillidaCut.plate, colors.plateFrame()),
    ) {
        if (title != null) {
            Text(
                title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 18.dp),
                style = MillidaType.dialogTitle,
                color = colors.fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = if (title != null) 12.dp else 20.dp, bottom = 20.dp),
            content = content,
        )
    }
}

@Composable
private fun BoxScope.DockedSheet(
    modifier: Modifier,
    title: String?,
    appear: State<Float>,
    close: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = millida
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var sheetHeight by remember { mutableIntStateOf(0) }
    var drag by remember { mutableFloatStateOf(0f) }
    val flick = with(density) { 400.dp.toPx() }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    val dragState = rememberDraggableState { delta ->
        val next = drag + delta
        drag = if (next >= 0f) next else next * 0.5f
    }

    fun settle(velocity: Float) {
        if (drag > sheetHeight * 0.4f || velocity > flick) {
            close()
        } else {
            scope.launch {
                animate(drag, 0f, animationSpec = tween(160, easing = MillidaMotion.steps(3))) { value, _ ->
                    drag = value
                }
            }
        }
    }

    Column(
        modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .onSizeChanged { sheetHeight = it.height }
            .graphicsLayer { translationY = round((1f - appear.value) * sheetHeight + drag) }
            .swallowTaps()
            .pixelSurface(
                fill = colors.raised,
                cut = MillidaCut.plate,
                frame = colors.plateFrame().copy(outline = colors.dockOutline),
                corners = PixelCorners.Top,
            )
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> settle(velocity) },
                )
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 5.dp)
                    .pixelSurface(colors.borderStrong, 1.dp),
            )
            if (title != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    title,
                    modifier = Modifier.fillMaxWidth(),
                    style = MillidaType.dialogTitle,
                    color = colors.fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
            content = content,
        )
    }
}

@Composable
fun LoginDialog(state: UiState, model: LauncherViewModel) {
    val prompt = state.loginPrompt ?: return
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val colors = millida

    // The code alone is useless without the page it is typed into, so the page
    // opens by itself and the code lands in the clipboard.
    LaunchedEffect(prompt.code) {
        clipboard.setText(AnnotatedString(prompt.code))
        model.openLinkOrNotice(context, prompt.url)
    }

    MDialog(
        title = if (prompt.kind == AccountKind.MILLIDA) "Подтверди вход" else "Код Microsoft",
        onDismiss = model::cancelLogin,
        body = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .pixelSurface(colors.inset, MillidaCut.control, colors.wellFrame())
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    prompt.code,
                    style = MillidaType.pixelLarge.copy(fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = 0.12.em),
                    color = colors.accentText,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Код скопирован. Введи его на открывшейся странице — окно закроется само.",
                style = MillidaType.body,
                color = colors.fgMuted,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                prompt.url,
                style = MillidaType.pixelSmall,
                color = colors.fgFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            MTextButton("Отменить", onClick = model::cancelLogin)
            MSecondaryButton(
                "Скопировать",
                onClick = { clipboard.setText(AnnotatedString(prompt.code)) },
                icon = MIcons.copy,
                size = MButtonSize.Small,
            )
            MPrimaryButton("Открыть страницу", onClick = { model.openLinkOrNotice(context, prompt.url) }, icon = MIcons.externalLink)
        },
    )
}
