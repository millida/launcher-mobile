package net.millida.launcher.core.hud

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
enum class HudKind {
    STICK,
    KEY,
    MOUSE,
    ACTION,
    SCROLL,
    FLOAT_STICK,
    JUMP,
    HOTBAR_MORE,
}

@Serializable
enum class HudAction {
    POINTER_MODE,
    KEYBOARD,
    MENU,
    NONE,
    CHAT,
}

@Serializable
enum class HudVisibility {
    LOOK,
    CURSOR,
    ALWAYS,
    FLYING,
    WALKING,
    ;

    fun shown(lookMode: Boolean, flying: Boolean): Boolean = when (this) {
        ALWAYS -> true
        LOOK -> lookMode
        CURSOR -> !lookMode
        FLYING -> lookMode && flying
        WALKING -> lookMode && !flying
    }
}

@Serializable
data class HudControl(
    val id: String,
    val label: String,
    val kind: HudKind,
    val code: Int = 0,
    val action: HudAction = HudAction.NONE,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val width: Float = 64f,
    val height: Float = 46f,
    val toggle: Boolean = false,
    val visibility: HudVisibility = HudVisibility.LOOK,
    val opacity: Float? = null,
    val hidden: Boolean = false,
    val codes: List<Int> = emptyList(),
    val icon: String? = null,
    val latch: Boolean = false,
) {
    val keys: List<Int>
        get() = codes.ifEmpty { listOf(code) }

    val rebindable: Boolean
        get() = kind == HudKind.KEY || kind == HudKind.JUMP || kind == HudKind.HOTBAR_MORE

    val anchored: Boolean
        get() = kind == HudKind.HOTBAR_MORE

    fun withPosition(nextX: Float, nextY: Float): HudControl =
        copy(x = nextX.coerceIn(0.02f, 0.98f), y = nextY.coerceIn(0.02f, 0.98f))

    /** Centre in dp on a screen of the given dp size, put on the editor grid. */
    fun snappedTo(centreX: Float, centreY: Float, screenWidth: Float, screenHeight: Float, grid: Float = SNAP_GRID): HudControl {
        if (screenWidth <= 0f || screenHeight <= 0f) return this
        val snappedX = (centreX / grid).roundToInt() * grid
        val snappedY = (centreY / grid).roundToInt() * grid
        return withPosition(snappedX / screenWidth, snappedY / screenHeight)
    }

    fun withSize(factor: Float): HudControl {
        val nextWidth = base.width * factor.coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = if (width > 0f) height / width else base.height / base.width
        return copy(width = nextWidth, height = nextWidth * ratio)
    }

    fun withOpacity(value: Float?): HudControl = copy(opacity = value?.coerceIn(HudLayout.MIN_OPACITY, 1f))

    fun shownOpacity(global: Float): Float = (opacity ?: global).coerceIn(HudLayout.MIN_OPACITY, 1f)

    fun withKeys(next: List<Int>): HudControl {
        val clean = next.filter { HudKeys.isBindable(it) }.distinct().take(MAX_KEYS)
        if (clean.isEmpty()) return this
        return copy(code = clean.first(), codes = if (clean.size == 1) emptyList() else clean)
    }

    private val base: HudControl
        get() = HudPresets.base(kind)

    val scale: Float
        get() = width / base.width

    companion object {
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 2.2f
        const val MAX_KEYS = 3
        const val SNAP_GRID = 8f
    }
}

@Serializable
data class HudLayout(
    val name: String = "Своя раскладка",
    val controls: List<HudControl> = emptyList(),
    val opacity: Float = DEFAULT_OPACITY,
    val lookTap: Int = HudKeys.MOUSE_LEFT,
    val holdDelay: Int = DEFAULT_HOLD_MS,
) {
    fun replace(control: HudControl): HudLayout =
        copy(controls = controls.map { if (it.id == control.id) control else it })

    fun remove(id: String): HudLayout = copy(controls = controls.filterNot { it.id == id })

    fun add(control: HudControl): HudLayout {
        var id = control.id
        var suffix = 2
        while (controls.any { it.id == id }) id = "${control.id}-${suffix++}"
        return copy(controls = controls + control.copy(id = id))
    }

    fun withOpacity(value: Float): HudLayout = copy(opacity = value.coerceIn(MIN_OPACITY, 1f))

    fun withLookTap(button: Int): HudLayout =
        copy(lookTap = if (button == HudKeys.MOUSE_RIGHT) HudKeys.MOUSE_RIGHT else HudKeys.MOUSE_LEFT)

    fun withHoldDelay(ms: Int): HudLayout = copy(holdDelay = ms.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS))

    fun switchTo(preset: HudLayout): HudLayout = preset.copy(opacity = opacity, lookTap = lookTap, holdDelay = holdDelay)

    /** Puts a control back the way the layout's template has it; a control the template lacks stays. */
    fun reset(id: String): HudLayout {
        val original = (HudPresets.byName(name) ?: HudPresets.bedrock()).controls.firstOrNull { it.id == id }
            ?: return this
        return replace(original)
    }

    val shownOpacity: Float
        get() = opacity.coerceIn(MIN_OPACITY, 1f)

    val shownHoldDelay: Int
        get() = holdDelay.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)

    val tapHits: Boolean
        get() = lookTap == HudKeys.MOUSE_LEFT

    companion object {
        const val DEFAULT_OPACITY = 0.85f
        const val MIN_OPACITY = 0.3f
        const val DEFAULT_HOLD_MS = 250
        const val MIN_HOLD_MS = 150
        const val MAX_HOLD_MS = 500
    }
}

object HudKeys {
    const val SPACE = 32
    const val ZERO = 48
    const val ONE = 49
    const val TWO = 50
    const val THREE = 51
    const val FOUR = 52
    const val FIVE = 53
    const val SIX = 54
    const val SEVEN = 55
    const val EIGHT = 56
    const val NINE = 57
    const val A = 65
    const val D = 68
    const val E = 69
    const val F = 70
    const val Q = 81
    const val S = 83
    const val T = 84
    const val W = 87
    const val Z = 90
    const val ESCAPE = 256
    const val ENTER = 257
    const val TAB = 258
    const val BACKSPACE = 259
    const val RIGHT = 262
    const val LEFT = 263
    const val DOWN = 264
    const val UP = 265
    const val F1 = 290
    const val F2 = 291
    const val F3 = 292
    const val F5 = 294
    const val F12 = 301
    const val LEFT_SHIFT = 340
    const val LEFT_CONTROL = 341
    const val LEFT_ALT = 342

    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1
    const val MOUSE_MIDDLE = 2

    const val SCROLL_UP = 1
    const val SCROLL_DOWN = -1

    fun slot(index: Int): Int = ONE + index.coerceIn(0, 8)

    // The game acts on every repeat, so keys that open or switch something must not repeat.
    fun repeats(code: Int): Boolean = code !in NO_REPEAT

    private val NO_REPEAT = setOf(
        E, T, TAB, ESCAPE, ENTER, F1, F2, F3, F5,
        LEFT_SHIFT, LEFT_CONTROL, ONE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE,
    )

    private val special: List<Pair<Int, String>> = listOf(
        SPACE to "Пробел",
        LEFT_SHIFT to "Shift",
        LEFT_CONTROL to "Ctrl",
        LEFT_ALT to "Alt",
        TAB to "Tab",
        ESCAPE to "Esc",
        ENTER to "Enter",
        BACKSPACE to "Backspace",
        UP to "Вверх",
        DOWN to "Вниз",
        LEFT to "Влево",
        RIGHT to "Вправо",
    )

    /** Keys a player can put on a button: what a Minecraft keybinding usually lands on. */
    val bindable: List<Pair<Int, String>> =
        special +
            (A..Z).map { it to it.toChar().toString() } +
            (ZERO..NINE).map { it to it.toChar().toString() } +
            (F1..F12).map { it to "F${it - F1 + 1}" }

    private val names: Map<Int, String> = bindable.toMap()

    fun isBindable(code: Int): Boolean = code in names

    fun name(code: Int): String = names[code] ?: "#$code"
}

object Hotbar {
    const val WIDTH = 182
    const val HEIGHT = 22
    const val SLOT = 20
    const val SLOTS = 9

    private const val REACH = 6

    fun scale(width: Int, height: Int, wanted: Int, forceUnicode: Boolean = false): Int {
        if (width <= 0 || height <= 0) return 1
        var scale = 1
        while (scale != wanted && scale < width && scale < height &&
            width / (scale + 1) >= 320 && height / (scale + 1) >= 240
        ) {
            scale++
        }
        if (forceUnicode && scale % 2 != 0) scale++
        return scale
    }

    private fun guiSize(pixels: Int, scale: Int): Int = (pixels + scale - 1) / scale

    fun left(width: Int, scale: Int): Float = ((guiSize(width, scale) / 2 - WIDTH / 2) * scale).toFloat()

    fun top(height: Int, scale: Int): Float = ((guiSize(height, scale) - HEIGHT) * scale).toFloat()

    fun right(width: Int, scale: Int): Float = left(width, scale) + WIDTH * scale

    /** The topmost row a finger still counts as the hotbar. */
    fun reachTop(height: Int, scale: Int): Float = top(height, scale) - REACH * scale

    fun contains(x: Float, y: Float, width: Int, height: Int, scale: Int): Boolean {
        val left = left(width, scale)
        return x >= left && x < left + WIDTH * scale && y >= reachTop(height, scale) && y <= height
    }

    fun slotAt(x: Float, y: Float, width: Int, height: Int, scale: Int): Int? {
        if (!contains(x, y, width, height, scale)) return null
        val inside = (x - left(width, scale)) / scale - 1f
        return (inside / SLOT).toInt().coerceIn(0, SLOTS - 1)
    }

    fun slotPixels(scale: Int): Float = (SLOT * scale).toFloat()

    fun readOptions(text: String): Pair<Int, Boolean> {
        var scale = 0
        var unicode = false
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("guiScale:") -> scale = line.substringAfter(':').trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                line.startsWith("forceUnicodeFont:") -> unicode = line.substringAfter(':').trim() == "true"
            }
        }
        return scale to unicode
    }
}

object HudIcons {
    const val PAUSE = "pause"
    const val CHAT = "chat"
    const val CAMERA = "camera"
    const val PLAYERS = "players"
    const val MORE = "more"
    const val JUMP = "jump"
    const val SNEAK = "sneak"
    const val UP = "up"
    const val DOWN = "down"
    const val CLOSE = "close"

    val all: Set<String> = setOf(PAUSE, CHAT, CAMERA, PLAYERS, MORE, JUMP, SNEAK, UP, DOWN, CLOSE)
}

object HudPresets {

    // Places are shares of the smallest target screen: on a bigger one the gaps only grow.
    const val NOMINAL_WIDTH = 640f
    const val NOMINAL_HEIGHT = 360f

    const val BEDROCK = "Как в Bedrock"

    fun base(kind: HudKind): HudControl = when (kind) {
        HudKind.STICK -> HudControl("stick", "Ходьба", HudKind.STICK, width = 176f, height = 176f)
        HudKind.FLOAT_STICK -> HudControl("stick", "Ходьба", HudKind.FLOAT_STICK, width = 120f, height = 120f)
        HudKind.KEY -> HudControl("key", "Клавиша", HudKind.KEY, width = 64f, height = 46f)
        HudKind.MOUSE -> HudControl("mouse", "Кнопка мыши", HudKind.MOUSE, width = 104f, height = 46f)
        HudKind.ACTION -> HudControl("action", "Действие", HudKind.ACTION, width = 104f, height = 44f)
        HudKind.SCROLL -> HudControl("scroll", "Колесо", HudKind.SCROLL, width = 64f, height = 46f)
        HudKind.JUMP -> HudControl("jump", "Прыжок", HudKind.JUMP, code = HudKeys.SPACE, width = 64f, height = 64f)
        HudKind.HOTBAR_MORE -> HudControl("more", "Инвентарь", HudKind.HOTBAR_MORE, code = HudKeys.E, width = 32f, height = 32f)
    }

    private fun at(control: HudControl, centreX: Float, centreY: Float): HudControl =
        control.copy(x = centreX / NOMINAL_WIDTH, y = centreY / NOMINAL_HEIGHT)

    private fun key(
        id: String,
        label: String,
        code: Int,
        width: Float = 64f,
        toggle: Boolean = false,
        visibility: HudVisibility = HudVisibility.LOOK,
    ) = HudControl(
        id,
        label,
        HudKind.KEY,
        code = code,
        width = width,
        height = 46f,
        toggle = toggle,
        visibility = visibility,
    )

    private fun square(
        id: String,
        label: String,
        code: Int,
        side: Float,
        icon: String,
        visibility: HudVisibility = HudVisibility.LOOK,
        latch: Boolean = false,
        hidden: Boolean = false,
    ) = HudControl(
        id,
        label,
        HudKind.KEY,
        code = code,
        width = side,
        height = side,
        visibility = visibility,
        icon = icon,
        latch = latch,
        hidden = hidden,
    )

    private fun gameKeys(camera: Boolean): List<HudControl> = listOfNotNull(
        at(key("escape", "Esc", HudKeys.ESCAPE, visibility = HudVisibility.ALWAYS), 40f, 28f),
        if (camera) at(key("camera", "F5", HudKeys.F5), 112f, 28f) else null,
    )

    private fun mouse(id: String, label: String, code: Int) =
        HudControl(id, label, HudKind.MOUSE, code = code, width = 104f, height = 46f)

    private fun action(id: String, label: String, what: HudAction, width: Float) =
        HudControl(
            id,
            label,
            HudKind.ACTION,
            action = what,
            width = width,
            height = 44f,
            visibility = HudVisibility.ALWAYS,
        )

    private fun actions(): List<HudControl> = listOf(
        at(action("mode", "Курсор", HudAction.POINTER_MODE, 104f), 308f, 28f),
        at(action("keyboard", "Клавиатура", HudAction.KEYBOARD, 140f), 446f, 28f),
        at(action("menu", "Меню", HudAction.MENU, 96f), 580f, 28f),
    )

    private fun rightHand(): List<HudControl> = listOf(
        at(key("sneak", "Присесть", HudKeys.LEFT_SHIFT, width = 104f, toggle = true), 452f, 190f),
        at(key("jump", "Прыжок", HudKeys.SPACE, width = 104f), 572f, 190f),
        at(mouse("hit", "Удар", HudKeys.MOUSE_LEFT), 452f, 250f),
        at(mouse("use", "Действие", HudKeys.MOUSE_RIGHT), 572f, 250f),
        at(key("inventory", "Инвентарь", HudKeys.E, width = 104f), 452f, 310f),
        at(key("chat", "Чат", HudKeys.T, width = 104f), 572f, 310f),
    )

    private fun iconAction(id: String, label: String, what: HudAction, icon: String, visibility: HudVisibility) =
        HudControl(
            id,
            label,
            HudKind.ACTION,
            action = what,
            width = 36f,
            height = 36f,
            visibility = visibility,
            icon = icon,
        )

    /**
     * Tap places, holding breaks, the left thumb walks wherever it lands. Esc for
     * closing a game screen takes the chat's place while a screen is open: the
     * two are never needed at once.
     */
    fun bedrock(): HudLayout = HudLayout(
        name = BEDROCK,
        lookTap = HudKeys.MOUSE_RIGHT,
        controls = listOf(
            at(base(HudKind.FLOAT_STICK), 88f, 270f),
            at(base(HudKind.JUMP).copy(icon = HudIcons.JUMP), 584f, 280f),
            at(square("sneak", "Присесть", HudKeys.LEFT_SHIFT, 52f, HudIcons.SNEAK, HudVisibility.WALKING, latch = true), 520f, 312f),
            at(square("fly-down", "Вниз", HudKeys.LEFT_SHIFT, 52f, HudIcons.DOWN, HudVisibility.FLYING), 520f, 312f),
            at(iconAction("menu", "Меню", HudAction.MENU, HudIcons.PAUSE, HudVisibility.ALWAYS), 616f, 22f),
            at(iconAction("chat", "Чат", HudAction.CHAT, HudIcons.CHAT, HudVisibility.LOOK), 576f, 22f),
            at(square("escape", "Esc", HudKeys.ESCAPE, 36f, HudIcons.CLOSE, HudVisibility.CURSOR), 576f, 22f),
            at(square("camera", "Вид", HudKeys.F5, 36f, HudIcons.CAMERA), 536f, 22f),
            at(square("players", "Игроки", HudKeys.TAB, 36f, HudIcons.PLAYERS, hidden = true), 496f, 22f),
            base(HudKind.HOTBAR_MORE).copy(icon = HudIcons.MORE, x = 0.5f, y = 0.95f),
        ),
    )

    fun stick(): HudLayout = HudLayout(
        name = "Джойстик",
        controls = listOf(
            at(HudControl("stick", "Ходьба", HudKind.STICK, width = 176f, height = 176f), 104f, 252f),
        ) + rightHand() + gameKeys(camera = true) + actions(),
    )

    fun buttons(): HudLayout = HudLayout(
        name = "Кнопки",
        controls = listOf(
            at(key("forward", "W", HudKeys.W, width = 56f), 116f, 168f),
            at(key("left", "A", HudKeys.A, width = 56f), 56f, 222f),
            at(key("back", "S", HudKeys.S, width = 56f), 116f, 222f),
            at(key("right", "D", HudKeys.D, width = 56f), 176f, 222f),
            at(key("sprint", "Бег", HudKeys.LEFT_CONTROL, width = 104f, toggle = true), 68f, 282f),
        ) + rightHand() + gameKeys(camera = true) + actions(),
    )

    fun minimal(): HudLayout = HudLayout(
        name = "Только обзор",
        controls = listOf(
            at(HudControl("stick", "Ходьба", HudKind.STICK, width = 150f, height = 150f), 92f, 262f),
            at(key("jump", "Прыжок", HudKeys.SPACE, width = 104f), 572f, 250f),
        ) + gameKeys(camera = false) + actions(),
    )

    fun all(): List<HudLayout> = listOf(bedrock(), stick(), buttons(), minimal())

    fun byName(name: String): HudLayout? = all().firstOrNull { it.name == name }
}
