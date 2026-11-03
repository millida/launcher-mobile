package net.millida.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.core.hud.Hotbar
import net.millida.launcher.core.hud.HudAction
import net.millida.launcher.core.hud.HudControl
import net.millida.launcher.core.hud.HudKind
import net.millida.launcher.core.hud.HudLayout
import net.millida.launcher.runtime.GameInputSink

/** The strip along the top edge belongs to the icon buttons, so the walking zone starts below it. */
private const val TOP_STRIP_DP = 56f
private const val FLOAT_ZONE_SHARE = 0.4f
private const val HOTBAR_GAP_DP = 4f
private const val HIDDEN_ALPHA = 0.45f
private const val GRID_LINE_DP = 32f
private const val GRID_ALPHA = 0.35f
private const val HANDLE_DP = 18f
private const val HANDLE_INSET_DP = 2f

private class HudGeometry(val width: Float, val height: Float, val hotbar: Rect, val density: Density) {

    fun box(control: HudControl): Rect {
        val sizeX = with(density) { control.width.dp.toPx() }
        val sizeY = with(density) { control.height.dp.toPx() }
        if (control.kind == HudKind.HOTBAR_MORE) {
            val gap = with(density) { HOTBAR_GAP_DP.dp.toPx() }
            val left = (hotbar.right + gap).coerceIn(0f, (width - sizeX).coerceAtLeast(0f))
            val top = (hotbar.center.y - sizeY / 2f).coerceIn(0f, (height - sizeY).coerceAtLeast(0f))
            return Rect(left, top, left + sizeX, top + sizeY)
        }
        val left = (control.x * width - sizeX / 2f).coerceIn(0f, (width - sizeX).coerceAtLeast(0f))
        val top = (control.y * height - sizeY / 2f).coerceIn(0f, (height - sizeY).coerceAtLeast(0f))
        return Rect(left, top, left + sizeX, top + sizeY)
    }

    /** Where a thumb brings up the floating stick: the left part of the screen, above the hotbar. */
    fun floatZone(control: HudControl): Rect {
        val rest = box(control)
        val top = with(density) { TOP_STRIP_DP.dp.toPx() }.coerceAtMost(rest.top)
        val right = maxOf(width * FLOAT_ZONE_SHARE, rest.right).coerceAtMost(width)
        val bottom = if (hotbar.left < right) hotbar.top else height
        return Rect(0f, top, right, maxOf(bottom, top + rest.height))
    }
}

@Composable
fun GameHud(
    layout: HudLayout,
    lookMode: Boolean,
    flying: Boolean,
    editing: Boolean,
    selectedId: String?,
    sink: GameInputSink,
    guiScale: Int,
    forceUnicode: Boolean,
    haptics: Boolean,
    onAction: (HudAction) -> Unit,
    onFly: () -> Unit,
    onSelect: (String?) -> Unit,
    onChange: (HudControl) -> Unit,
    resolution: Float = 1f,
    snap: Boolean = true,
    onResize: (HudControl) -> Unit = onChange,
    onGestureEnd: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val density = LocalDensity.current
        // The game lays its hotbar out in its own pixels; a reduced resolution
        // makes each of them bigger on the glass by the same factor.
        val gameWidth = (width * resolution).toInt().coerceAtLeast(1)
        val gameHeight = (height * resolution).toInt().coerceAtLeast(1)
        val scale = Hotbar.scale(gameWidth, gameHeight, guiScale, forceUnicode)
        val geometry = HudGeometry(
            width = width.toFloat(),
            height = height.toFloat(),
            hotbar = Rect(
                Hotbar.left(gameWidth, scale) / resolution,
                Hotbar.reachTop(gameHeight, scale) / resolution,
                Hotbar.right(gameWidth, scale) / resolution,
                height.toFloat(),
            ),
            density = density,
        )

        if (editing && snap) EditorGrid()

        val sizeScale = if (editing) 1f else layout.shownSizeScale
        val shown = layout.controls.filter { control ->
            editing || (!control.hidden && control.visibility.shown(lookMode, flying))
        }.map { control ->
            if (sizeScale == 1f) control else control.copy(width = control.width * sizeScale, height = control.height * sizeScale)
        }
        shown.forEach { control ->
            key(control.id) {
                val opacity = control.shownOpacity(layout.shownOpacity)
                if (!editing && control.kind == HudKind.FLOAT_STICK) {
                    val zone = geometry.floatZone(control)
                    Placed(zone, density) {
                        HudFloatStick(
                            control = control,
                            opacity = opacity,
                            sink = sink,
                            zoneOrigin = zone.topLeft,
                            rest = geometry.box(control).center,
                            haptics = haptics,
                        )
                    }
                } else {
                    Placed(geometry.box(control), density) {
                        if (editing) {
                            EditableControl(
                                control = control,
                                selected = control.id == selectedId,
                                screen = Offset(geometry.width, geometry.height),
                                snap = snap,
                                onSelect = { onSelect(control.id) },
                                onMove = onChange,
                                onResize = onResize,
                                onGestureEnd = onGestureEnd,
                            )
                        } else {
                            LiveControl(control, lookMode, opacity, sink, onAction, onFly)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Placed(box: Rect, density: Density, content: @Composable () -> Unit) {
    Box(
        Modifier
            .offset(
                x = with(density) { box.left.toDp() },
                y = with(density) { box.top.toDp() },
            )
            .size(
                width = with(density) { box.width.toDp() },
                height = with(density) { box.height.toDp() },
            ),
    ) {
        content()
    }
}

@Composable
private fun LiveControl(
    control: HudControl,
    lookMode: Boolean,
    opacity: Float,
    sink: GameInputSink,
    onAction: (HudAction) -> Unit,
    onFly: () -> Unit,
) {
    when (control.kind) {
        HudKind.STICK -> HudStick(control, opacity, sink)
        HudKind.FLOAT_STICK -> HudStick(control, opacity, sink)
        HudKind.ACTION -> HudActionButton(control, lookMode, opacity, onAction)
        HudKind.SCROLL -> HudWheel(control, opacity, sink)
        HudKind.JUMP -> HudJump(control, opacity, sink, onFly)
        HudKind.KEY, HudKind.MOUSE, HudKind.HOTBAR_MORE -> HudPressable(control, opacity, sink)
    }
}

@Composable
private fun EditorGrid() {
    val line = millida.border.copy(alpha = GRID_ALPHA)
    Canvas(Modifier.fillMaxSize()) {
        val step = GRID_LINE_DP.dp.toPx()
        val stroke = 1.dp.toPx()
        var x = step
        while (x < size.width) {
            drawRect(line, topLeft = Offset(x, 0f), size = Size(stroke, size.height))
            x += step
        }
        var y = step
        while (y < size.height) {
            drawRect(line, topLeft = Offset(0f, y), size = Size(size.width, stroke))
            y += step
        }
    }
}

@Composable
private fun EditableControl(
    control: HudControl,
    selected: Boolean,
    screen: Offset,
    snap: Boolean,
    onSelect: () -> Unit,
    onMove: (HudControl) -> Unit,
    onResize: (HudControl) -> Unit,
    onGestureEnd: () -> Unit,
) {
    val colors = millida
    val latest by rememberUpdatedState(control)
    val move by rememberUpdatedState(onMove)
    val resize by rememberUpdatedState(onResize)
    val ended by rememberUpdatedState(onGestureEnd)
    val snapping by rememberUpdatedState(snap)
    val density = LocalDensity.current.density
    val icon = hudIcon(control.icon)
    val ink = if (selected) colors.onAccent else colors.fg
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (control.hidden) HIDDEN_ALPHA else 1f }
            .pixelSurface(
                fill = if (selected) colors.accent.copy(alpha = 0.9f) else colors.raised.copy(alpha = 0.9f),
                cut = hudCut(control.kind),
                frame = if (selected) PixelFrame.Button else colors.plateFrame().copy(outline = colors.borderStrong),
                dots = selected,
            )
            .pointerInput(control.id) {
                detectTapGestures(onTap = { onSelect() })
            }
            .pointerInput(control.id, control.anchored) {
                if (control.anchored) return@pointerInput
                // The place is counted from where the finger started: the position
                // read between drag events has not caught up with the finger yet.
                var startX = latest.x
                var startY = latest.y
                var travelled = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        startX = latest.x
                        startY = latest.y
                        travelled = Offset.Zero
                        onSelect()
                    },
                    onDrag = { _, delta ->
                        travelled += delta
                        move(
                            latest.snappedTo(
                                (startX * screen.x + travelled.x) / density,
                                (startY * screen.y + travelled.y) / density,
                                screen.x / density,
                                screen.y / density,
                                grid = if (snapping) HudControl.SNAP_GRID else 1f,
                            ),
                        )
                    },
                    onDragEnd = { ended() },
                    onDragCancel = { ended() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            MIcon(icon, size = (minOf(control.width, control.height) * 0.5f).dp, color = ink)
        } else {
            Text(
                control.label,
                color = ink,
                style = MillidaType.button.copy(fontSize = 12.sp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
        if (selected) {
            ResizeHandle(
                Modifier.align(Alignment.BottomEnd),
                onStart = { latest.width },
                onResize = { startWidth, travelled ->
                    val wanted = startWidth + maxOf(travelled.x, travelled.y) / density
                    val unit = if (latest.scale > 0f) latest.width / latest.scale else latest.width
                    if (unit > 0f) resize(latest.withSize(wanted / unit))
                },
                onEnd = { ended() },
            )
        }
    }
}

/** The corner a finger drags to change the size, as in the editors of other launchers. */
@Composable
private fun ResizeHandle(
    modifier: Modifier,
    onStart: () -> Float,
    onResize: (startWidth: Float, travelled: Offset) -> Unit,
    onEnd: () -> Unit,
) {
    val colors = millida
    val start by rememberUpdatedState(onStart)
    val change by rememberUpdatedState(onResize)
    val end by rememberUpdatedState(onEnd)
    Box(
        modifier
            .padding(HANDLE_INSET_DP.dp)
            .size(HANDLE_DP.dp)
            .pixelSurface(fill = colors.onAccent, cut = MillidaCut.control, frame = PixelFrame.Button)
            .pointerInput(Unit) {
                var startWidth = 0f
                var travelled = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        startWidth = start()
                        travelled = Offset.Zero
                    },
                    onDrag = { pointer, delta ->
                        pointer.consume()
                        travelled += delta
                        change(startWidth, travelled)
                    },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                )
            },
    )
}
