package net.millida.launcher.ui.home

import net.millida.launcher.ui.RoomyFrame

/** A box of the landscape lobby in dp, counted from the start edge and the top. */
internal data class GridBox(val start: Float, val top: Float, val end: Float, val bottom: Float) {
    val width: Float get() = end - start
    val height: Float get() = bottom - top

    fun overlaps(other: GridBox): Boolean =
        start < other.end && other.start < end && top < other.bottom && other.top < bottom
}

/** The window the lobby is laid out in, in dp: its size and the safe insets on each side. */
internal data class GridWindow(
    val width: Float,
    val height: Float,
    val startInset: Float = 0f,
    val endInset: Float = 0f,
    val topInset: Float = 0f,
    val bottomInset: Float = 0f,
    /** A phone on its side: a HUD around the figure, not a desktop's panels. */
    val compact: Boolean = false,
)

/**
 * What the system font makes of the lobby's text blocks, in dp: the dock's
 * row, the corner row, and the banner and «Попробуй сегодня» at the desktop
 * width they are drawn at.
 */
internal data class GridType(val dockRow: Float, val cornerRow: Float, val bannerHeight: Float, val recHeight: Float)

/**
 * Where the figure stands: its [frame] height, its middle, how far the
 * frame's bottom stands above the window's bottom ([feet]), the top of its
 * head, the room its swaying body takes and the room its bubble pops in.
 */
internal data class GridFigure(val frame: Float, val centre: Float, val feet: Float, val headTop: Float, val body: GridBox, val bubble: GridBox)

/**
 * The landscape lobby as one grid, so every piece takes its place from the
 * same lines and the phone shapes it must survive are pinned by tests.
 *
 * Three columns over a dock: the section tiles on the start rail, the figure
 * in the stage between the rails, the corner buttons and the banner on the
 * end rail. Shared lines: the top line carries the first tile and the corner
 * buttons; the bottom line carries the last tile and «Играть»; the dock's top
 * edge is the ground the figure stands on; the end rail is exactly «Играть»
 * wide plus the dock's padding, so the banner's edges continue the button's
 * start and the dock's end.
 */
internal class WideLobbyGrid private constructor(
    val tiles: GridBox,
    val tileWidth: Float,
    val tileHeight: Float,
    val tilesReachDock: Boolean,
    val dock: GridBox,
    val playWidth: Float,
    val play: GridBox,
    val plateWidth: Float,
    val rail: GridBox,
    val corner: GridBox,
    val sky: GridBox,
    val bannerScale: Float,
    val recScale: Float,
    val figure: GridFigure,
    val menuTop: Float,
) {
    val bannerFits: Boolean get() = bannerScale >= CARD_MIN_SCALE
    val recFits: Boolean get() = recScale >= CARD_MIN_SCALE

    /** The banner as it is drawn: shrunk as a whole, centred in the sky, flush with the dock's end. */
    fun banner(width: Float, height: Float, scale: Float): GridBox {
        val drawnHeight = height * scale
        val top = sky.top + (sky.height - drawnHeight) / 2f
        return GridBox(sky.end - width * scale, top, sky.end, top + drawnHeight)
    }

    companion object {
        const val GUTTER = 16f
        const val CUTOUT_GAP = 8f
        const val TOP_LINE = 10f
        const val GAP = 12f
        const val TILE_GAP = 8f
        const val TILE_OVERFLOW = 22f
        const val TILE_MIN = 58f
        const val TILE_MAX = 132f
        const val TILE_MIN_WIDTH = 148f
        const val TILE_MAX_WIDTH = 196f
        const val TILE_SHARE = 0.17f
        const val PLAY_MIN = 208f
        const val PLAY_MAX = 292f
        const val PLAY_SHARE = 0.28f
        const val DOCK_PAD_TOP = 6f
        const val DOCK_PAD_BOTTOM = 8f
        const val DOCK_SIDE_PAD = 8f
        const val DOCK_GAP = 8f
        const val FIGURE_MIN = 140f
        const val FIGURE_MAX = 560f
        const val FIGURE_HALF_WIDTH_SHARE = 0.3f
        const val HEAD_HALF_WIDTH_SHARE = 0.13f
        const val FIGURE_SIDE_GAP = 8f
        const val BANNER_WIDTH = 300f
        const val REC_WIDTH = 320f
        const val CARD_MIN_SCALE = 0.62f
        const val MENU_GAP = 8f
        const val DESIGN_WIDTH = 1024f
        const val DESIGN_HEIGHT = 472f
        const val UI_SCALE_MAX = 1.3f
        const val COMPACT_CARD_SCALE = 0.85f
        const val COMPACT_TILE_WIDTH = 140f
        const val COMPACT_TILE_HEIGHT = 78f
        const val COMPACT_TILE_OVERFLOW = 18f
        /** The desktop's lobby row: 812 of 1440 wide, centred, «Играть» 290 of it. */
        const val COMPACT_DOCK_SHARE = 0.56f
        const val COMPACT_PLAY_SHARE = 0.357f

        /**
         * How much larger than a phone the lobby is drawn on [width] x [height]:
         * a tablet gets the same composition bigger, as the desktop zooms its
         * panels on a large window, instead of small islands in the corners.
         * A phone smaller than the design size is never shrunk, so the touch
         * targets stay at their full size.
         */
        fun uiScale(width: Float, height: Float): Float =
            minOf(width / DESIGN_WIDTH, height / DESIGN_HEIGHT).coerceIn(1f, UI_SCALE_MAX)

        fun dockHeight(dockRow: Float, bottomInset: Float): Float = dockRow + DOCK_PAD_TOP + DOCK_PAD_BOTTOM + bottomInset

        /**
         * [bubbleReach] is how far right of the figure's middle its bubble
         * reaches for a frame that tall, [bubbleLift] how far above the frame
         * the bubble starts, and [bubbleSpan] its height with the tail.
         */
        fun of(
            window: GridWindow,
            type: GridType,
            bubbleReach: (Float) -> Float,
            bubbleLift: (Float) -> Float,
            bubbleSpan: Float,
        ): WideLobbyGrid {
            val width = window.width
            val height = window.height
            val startEdge = maxOf(window.startInset + CUTOUT_GAP, GUTTER)
            val endEdge = width - maxOf(window.endInset + CUTOUT_GAP, GUTTER)
            val topLine = maxOf(window.topInset, TOP_LINE)
            val bottomLine = height - window.bottomInset - DOCK_PAD_BOTTOM
            val dockTop = height - dockHeight(type.dockRow, window.bottomInset)

            val compact = window.compact
            val overflow = if (compact) COMPACT_TILE_OVERFLOW else TILE_OVERFLOW
            val tileWidth = if (compact) COMPACT_TILE_WIDTH else (width * TILE_SHARE).coerceIn(TILE_MIN_WIDTH, TILE_MAX_WIDTH)
            val tileHeight = ((bottomLine - topLine - TILE_GAP * 2f) / 3f - overflow)
                .coerceIn(if (compact) COMPACT_TILE_HEIGHT else TILE_MIN, if (compact) COMPACT_TILE_HEIGHT else TILE_MAX)
            val tilesBottom = topLine + (tileHeight + overflow) * 3f + TILE_GAP * 2f
            val tiles = GridBox(startEdge, topLine, startEdge + tileWidth, tilesBottom)
            // A tall window leaves the tiles above the dock: the dock then takes
            // the whole width instead of leaving an empty strip under them.
            val tilesReachDock = tilesBottom > dockTop - GAP

            val playWidth = if (compact) COMPACT_DOCK_SHARE * width * COMPACT_PLAY_SHARE else (width * PLAY_SHARE).coerceIn(PLAY_MIN, PLAY_MAX)
            val railWidth = playWidth + DOCK_SIDE_PAD
            val rail = GridBox(endEdge - railWidth, topLine, endEdge, dockTop - GAP)
            // On a phone the dock is a compact pair at the end edge, not a bar across the screen.
            val dockStart = when {
                compact -> width * (1f - COMPACT_DOCK_SHARE) / 2f
                tilesReachDock -> tiles.end + GAP
                else -> startEdge
            }
            val dockEnd = if (compact) width - dockStart else endEdge
            val dock = GridBox(dockStart, dockTop, dockEnd, height)
            val play = GridBox(dockEnd - DOCK_SIDE_PAD - playWidth, dockTop + DOCK_PAD_TOP, dockEnd - DOCK_SIDE_PAD, bottomLine)
            val plateWidth = play.start - DOCK_GAP - (dock.start + DOCK_SIDE_PAD)

            // The soles stand on the dock's top edge; the camera's margin under
            // them goes behind the dock, so the shadow lies on that edge.
            val centre = (tiles.end + rail.start) / 2f
            val sideRoom = (rail.start - tiles.end) / 2f - FIGURE_SIDE_GAP
            val frame = ((dockTop - topLine) / RoomyFrame.BODY_SHARE)
                .coerceIn(FIGURE_MIN, FIGURE_MAX)
                .coerceAtMost(sideRoom / FIGURE_HALF_WIDTH_SHARE)
                .coerceAtLeast(0f)
            val frameBottom = dockTop + frame * RoomyFrame.FEET_SHARE
            val feet = height - frameBottom
            val frameTop = frameBottom - frame
            val headTop = dockTop - frame * RoomyFrame.BODY_SHARE
            val half = frame * FIGURE_HALF_WIDTH_SHARE
            val body = GridBox(centre - half, headTop, centre + half, dockTop)
            val reach = bubbleReach(frame)
            val bubbleTop = maxOf(frameTop - bubbleLift(frame), topLine)
            val bubble = GridBox(centre + frame * HEAD_HALF_WIDTH_SHARE, bubbleTop, centre + reach, bubbleTop + bubbleSpan)
            val figure = GridFigure(frame, centre, feet, headTop, body, bubble)

            // The corner buttons may lean into the stage over the figure's
            // shoulder, but never under its bubble.
            val corner = GridBox(bubble.end + GAP, topLine, endEdge, topLine + type.cornerRow)
            val sky = GridBox(rail.start, corner.bottom + GAP, endEdge, dockTop - GAP)
            val bannerScale = cardScale(sky.width, sky.height, BANNER_WIDTH, type.bannerHeight).let { if (compact) minOf(it, COMPACT_CARD_SCALE) else it }
            val recScale = cardScale(sky.width, sky.height, REC_WIDTH, type.recHeight).let { if (compact) minOf(it, COMPACT_CARD_SCALE) else it }
            return WideLobbyGrid(
                tiles = tiles,
                tileWidth = tileWidth,
                tileHeight = tileHeight,
                tilesReachDock = tilesReachDock,
                dock = dock,
                playWidth = playWidth,
                play = play,
                plateWidth = plateWidth,
                rail = rail,
                corner = corner,
                sky = sky,
                bannerScale = bannerScale,
                recScale = recScale,
                figure = figure,
                menuTop = corner.bottom + MENU_GAP,
            )
        }

        /**
         * How far a desktop card [width] x [height] shrinks to fit [room] x
         * [sky]: a low window gets the same card smaller, never a cut one, and
         * it never grows past the desktop's size.
         */
        fun cardScale(room: Float, sky: Float, width: Float, height: Float): Float =
            minOf(1f, room / width, sky / height).coerceAtLeast(0f)
    }
}
