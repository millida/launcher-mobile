package net.millida.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val HOLE_PAD = 8.dp
private val HOLE_RING = 2.dp
private val CARD_GAP = 16.dp
private val CARD_WIDTH = 320.dp
private const val TRAVEL_MS = 220
private const val TRAVEL_STEPS = 4
private const val SCROLL_MS = MillidaMotion.slow
private const val REVEAL_FRAMES = 30

/** How far from the edges of its scroll area a highlighted element is kept: the hole's pad plus air. */
val TourScrollMargin: Dp = HOLE_PAD + 8.dp

enum class TourTarget { PLAY, BUILDS, CHARACTER, WARDROBE, SHOP, SERVER, FRIENDS, ACCOUNT, SETTINGS }

@Immutable
class TourStep(val target: TourTarget?, val title: String, val text: String)

/**
 * Scrolls the screen so [TourTarget] is on screen; `room` tells how much free
 * height the card needs next to the given window bounds of the target.
 */
typealias TourRevealer = suspend (target: TourTarget, room: (Rect) -> Float) -> Unit

/** Where each tagged element is right now, and who can scroll it into view. */
@Stable
class TourRegistry {
    private val nodes = HashMap<TourTarget, LayoutCoordinates>()

    var revealer: TourRevealer? = null

    internal fun place(target: TourTarget, coordinates: LayoutCoordinates) {
        nodes[target] = coordinates
    }

    internal fun drop(target: TourTarget, coordinates: LayoutCoordinates) {
        if (nodes[target] === coordinates) nodes.remove(target)
    }

    /** The part of the target the user sees: clipped by its scroll area. */
    fun visible(target: TourTarget): Rect? = nodes[target]
        ?.takeIf { it.isAttached }
        ?.boundsInWindow()
        ?.takeIf { it.width > 0f && it.height > 0f }

    /** The whole target, including what is scrolled out of sight. */
    fun whole(target: TourTarget): Rect? = nodes[target]
        ?.takeIf { it.isAttached }
        ?.let { Rect(it.positionInWindow(), it.size.toSize()) }

    /** A lazy row is composed a frame after it is scrolled to; waits for it, but not forever. */
    suspend fun awaitWhole(target: TourTarget): Rect? {
        repeat(REVEAL_FRAMES) {
            withFrameNanos { }
            whole(target)?.let { return it }
        }
        return null
    }
}

val LocalTour = staticCompositionLocalOf { TourRegistry() }

/** Marks a real element of a screen so the guide can cut its hole around it. */
fun Modifier.tourTarget(target: TourTarget): Modifier = this then TourTargetElement(target)

private data class TourTargetElement(val target: TourTarget) : ModifierNodeElement<TourTargetNode>() {
    override fun create(): TourTargetNode = TourTargetNode(target)

    override fun update(node: TourTargetNode) = node.retarget(target)
}

private class TourTargetNode(private var target: TourTarget) :
    Modifier.Node(),
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode {
    private var registry: TourRegistry? = null
    private var placed: LayoutCoordinates? = null

    override fun onAttach() {
        registry = currentValueOf(LocalTour)
    }

    override fun onDetach() {
        placed?.let { registry?.drop(target, it) }
        placed = null
        registry = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        placed = coordinates
        registry?.place(target, coordinates)
    }

    fun retarget(next: TourTarget) {
        if (next == target) return
        val coordinates = placed
        if (coordinates != null) {
            registry?.drop(target, coordinates)
            registry?.place(next, coordinates)
        }
        target = next
    }
}

/** The window bounds of one scrolling container, for [bringIntoTour]. */
@Stable
class TourViewport {
    internal var bounds: Rect? = null

    val modifier: Modifier = Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }
}

/**
 * Scrolls so the target and the card's [room] both fit inside [view]; leaves
 * the list alone when they already do.
 */
suspend fun ScrollableState.bringIntoTour(view: TourViewport, target: Rect, room: Float, margin: Float, animate: Boolean) {
    val bounds = view.bounds ?: return
    val top = bounds.top + margin
    val bottom = bounds.bottom - margin
    val span = bottom - top
    val inside = target.top >= top && target.bottom <= bottom
    val spare = max(target.top - top, bottom - target.bottom)
    if (inside && spare >= room) return
    val goal = if (target.height + room <= span) top + (span - target.height - room) / 2f else top
    val delta = target.top - goal
    if (abs(delta) < 1f) return
    if (animate) animateScrollBy(delta, tween(SCROLL_MS)) else scrollBy(delta)
}

private class HoleMemory {
    var last: Rect? = null
}

/**
 * The guide over a live screen, as on the desktop: the screen dims, a stepped
 * hole opens around the real element, and the card sits beside it.
 */
@Composable
fun TourOverlay(steps: List<TourStep>, index: Int, onStep: (Int) -> Unit, onClose: () -> Unit) {
    if (steps.isEmpty()) return
    val colors = millida
    val registry = LocalTour.current
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val at = index.coerceIn(0, steps.lastIndex)
    val step = steps[at]
    val last = at == steps.lastIndex
    val pad = with(density) { HOLE_PAD.toPx() }
    val gap = with(density) { CARD_GAP.toPx() }

    var layer by remember { mutableStateOf<Rect?>(null) }
    var card by remember { mutableStateOf(IntSize.Zero) }
    var target by remember { mutableStateOf<Rect?>(null) }

    BackHandler { if (at > 0) onStep(at - 1) else onClose() }

    val area = layer?.let { bounds ->
        Rect(
            left = insets.getLeft(density, direction) + gap,
            top = insets.getTop(density) + gap,
            right = bounds.width - insets.getRight(density, direction) - gap,
            bottom = bounds.height - insets.getBottom(density) - gap,
        )
    }
    val cardWidth = min(with(density) { CARD_WIDTH.toPx() }, area?.width ?: 0f).coerceAtLeast(0f)

    val goal by rememberUpdatedState(step.target)
    LaunchedEffect(registry) {
        while (true) {
            withFrameNanos { }
            val origin = layer?.topLeft ?: continue
            val next = goal?.let(registry::visible)?.translate(-origin)?.inflate(pad)?.toWholePixels()
            if (next != target) target = next
        }
    }

    val currentArea by rememberUpdatedState(area)
    val currentLayer by rememberUpdatedState(layer)
    val currentCard by rememberUpdatedState(card)
    val currentWidth by rememberUpdatedState(cardWidth)
    LaunchedEffect(at, step.target, registry) {
        val wanted = step.target ?: return@LaunchedEffect
        withFrameNanos { }
        registry.revealer?.invoke(wanted) { bounds ->
            val space = currentArea
            val origin = currentLayer?.left ?: 0f
            val besideFits = space != null && bounds.right - origin + pad + gap + currentWidth <= space.right
            if (besideFits) 0f else currentCard.height + gap + pad
        }
    }

    val memory = remember { HoleMemory() }
    val from = remember(at) { memory.last }
    val travel = remember(at) { Animatable(if (reduced || from == null) 1f else 0f) }
    LaunchedEffect(at) {
        if (travel.value < 1f) travel.animateTo(1f, tween(TRAVEL_MS, easing = MillidaMotion.steps(TRAVEL_STEPS)))
    }
    val hole = target?.let { live -> if (from == null) live else lerp(from, live, travel.value).toWholePixels() }
    SideEffect { if (hole != null) memory.last = hole }

    val measured = card.height > 0 && area != null
    val spot = area?.let { tourCardSpot(hole, Size(cardWidth, card.height.toFloat()), it, gap) } ?: Offset.Zero
    var settled by remember { mutableStateOf(false) }
    val placed by animateIntOffsetAsState(
        targetValue = IntOffset(spot.x.roundToInt(), spot.y.roundToInt()),
        animationSpec = if (reduced || !settled) snap<IntOffset>() else tween<IntOffset>(TRAVEL_MS, easing = MillidaMotion.steps(TRAVEL_STEPS)),
        label = "tour-card",
    )
    SideEffect { if (measured) settled = true }

    val scrim = MillidaPixel.sheetScrim
    val ring = colors.accent
    val sole = remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { layer = it.boundsInWindow() }
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawTourScrim(hole, scrim, ring, MillidaCut.plate.toPx(), HOLE_RING.toPx())
        }
        Column(
            Modifier
                .offset { placed }
                .width(with(density) { cardWidth.toDp() })
                .onSizeChanged { card = it }
                .graphicsLayer { alpha = if (measured) 1f else 0f }
                .pixelSole(sole)
                .pixelSurface(colors.raised, MillidaCut.plate, colors.plateFrame())
                .padding(18.dp),
        ) {
            TourBar(count = steps.size, at = at)
            Spacer(Modifier.height(12.dp))
            Text(step.title, style = MillidaType.dialogTitle, color = colors.fg)
            Spacer(Modifier.height(6.dp))
            Text(step.text, style = MillidaType.body, color = colors.fgMuted)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MTextButton("Пропустить", onClick = onClose)
                Spacer(Modifier.weight(1f))
                if (at > 0) {
                    MSecondaryButton("Назад", onClick = { onStep(at - 1) }, size = MButtonSize.Small)
                    Spacer(Modifier.size(8.dp))
                }
                MPrimaryButton(
                    text = if (last) "Готово" else "Далее",
                    onClick = { if (last) onClose() else onStep(at + 1) },
                    size = MButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun TourBar(count: Int, at: Int) {
    val colors = millida
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Шаг ${at + 1} из $count" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(count) { segment ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(if (segment <= at) colors.accent else colors.inset),
            )
        }
    }
}

private fun DrawScope.drawTourScrim(hole: Rect?, scrim: Color, ring: Color, cut: Float, ringWidth: Float) {
    val dim = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        if (hole != null) addPath(holePath(hole, cut))
    }
    drawPath(dim, scrim)
    if (hole == null || hole.width <= ringWidth * 2f || hole.height <= ringWidth * 2f) return
    val band = Path().apply {
        fillType = PathFillType.EvenOdd
        addPath(holePath(hole, cut))
        addPath(holePath(hole.deflate(ringWidth), cut))
    }
    drawPath(band, ring)
}

private fun holePath(rect: Rect, cut: Float): Path {
    val step = pixelStep(floor(cut), rect.width, rect.height)
    return steppedPath(rect.width, rect.height, step, step).apply { translate(rect.topLeft) }
}

private fun Rect.toWholePixels(): Rect = Rect(floor(left), floor(top), ceil(right), ceil(bottom))

private fun clampSpan(value: Float, low: Float, high: Float): Float = min(max(low, value), max(low, high))

/**
 * The desktop's card placement: right of the hole, else below, above, left,
 * and as a last resort below but pushed back inside [area].
 */
internal fun tourCardSpot(hole: Rect?, card: Size, area: Rect, gap: Float): Offset {
    if (hole == null) {
        return Offset(
            max(area.left, area.left + (area.width - card.width) / 2f),
            max(area.top, area.top + (area.height - card.height) / 2f),
        )
    }
    val maxTop = area.bottom - card.height
    val right = hole.right + gap
    if (right + card.width <= area.right) return Offset(right, clampSpan(hole.top, area.top, maxTop))
    val left = clampSpan(hole.left, area.left, area.right - card.width)
    val below = hole.bottom + gap
    if (below <= maxTop) return Offset(left, below)
    val above = hole.top - gap - card.height
    if (above >= area.top) return Offset(left, above)
    val beside = hole.left - gap - card.width
    if (beside >= area.left) return Offset(beside, clampSpan(hole.top, area.top, maxTop))
    return Offset(left, clampSpan(below, area.top, maxTop))
}
