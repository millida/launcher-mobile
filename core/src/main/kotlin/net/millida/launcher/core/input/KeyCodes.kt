package net.millida.launcher.core.input

/**
 * Key codes of Android's KeyEvent, written out as numbers so the mapping lives
 * in the plain JVM module and its tests run without a phone. The values are
 * public Android API and never change between releases.
 */
object AndroidKeys {
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DIGIT_0 = 7
    const val A = 29
    const val COMMA = 55
    const val PERIOD = 56
    const val ALT_LEFT = 57
    const val ALT_RIGHT = 58
    const val SHIFT_LEFT = 59
    const val SHIFT_RIGHT = 60
    const val TAB = 61
    const val SPACE = 62
    const val ENTER = 66
    const val DEL = 67
    const val GRAVE = 68
    const val MINUS = 69
    const val EQUALS = 70
    const val LEFT_BRACKET = 71
    const val RIGHT_BRACKET = 72
    const val BACKSLASH = 73
    const val SEMICOLON = 74
    const val APOSTROPHE = 75
    const val SLASH = 76
    const val MENU = 82
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val ESCAPE = 111
    const val FORWARD_DEL = 112
    const val CTRL_LEFT = 113
    const val CTRL_RIGHT = 114
    const val CAPS_LOCK = 115
    const val SCROLL_LOCK = 116
    const val META_LEFT = 117
    const val META_RIGHT = 118
    const val SYSRQ = 120
    const val BREAK = 121
    const val MOVE_HOME = 122
    const val MOVE_END = 123
    const val INSERT = 124
    const val F1 = 131
    const val NUM_LOCK = 143
    const val NUMPAD_0 = 144
    const val NUMPAD_DIVIDE = 154
    const val NUMPAD_MULTIPLY = 155
    const val NUMPAD_SUBTRACT = 156
    const val NUMPAD_ADD = 157
    const val NUMPAD_DOT = 158
    const val NUMPAD_COMMA = 159
    const val NUMPAD_ENTER = 160
    const val NUMPAD_EQUALS = 161

    const val BUTTON_PRIMARY = 1
    const val BUTTON_SECONDARY = 2
    const val BUTTON_TERTIARY = 4
    const val BUTTON_BACK = 8
    const val BUTTON_FORWARD = 16
}

/** GLFW key and button numbers: what the game reads on the GLFW path, and what the HUD speaks on both paths. */
object GlfwCodes {
    const val UNKNOWN = -1
    const val SPACE = 32
    const val APOSTROPHE = 39
    const val COMMA = 44
    const val MINUS = 45
    const val PERIOD = 46
    const val SLASH = 47
    const val DIGIT_0 = 48
    const val SEMICOLON = 59
    const val EQUALS = 61
    const val A = 65
    const val LEFT_BRACKET = 91
    const val BACKSLASH = 92
    const val RIGHT_BRACKET = 93
    const val GRAVE = 96
    const val ESCAPE = 256
    const val ENTER = 257
    const val TAB = 258
    const val BACKSPACE = 259
    const val INSERT = 260
    const val DELETE = 261
    const val RIGHT = 262
    const val LEFT = 263
    const val DOWN = 264
    const val UP = 265
    const val PAGE_UP = 266
    const val PAGE_DOWN = 267
    const val HOME = 268
    const val END = 269
    const val CAPS_LOCK = 280
    const val SCROLL_LOCK = 281
    const val NUM_LOCK = 282
    const val PRINT_SCREEN = 283
    const val PAUSE = 284
    const val F1 = 290
    const val KP_0 = 320
    const val KP_DECIMAL = 330
    const val KP_DIVIDE = 331
    const val KP_MULTIPLY = 332
    const val KP_SUBTRACT = 333
    const val KP_ADD = 334
    const val KP_ENTER = 335
    const val KP_EQUAL = 336
    const val LEFT_SHIFT = 340
    const val LEFT_CONTROL = 341
    const val LEFT_ALT = 342
    const val LEFT_SUPER = 343
    const val RIGHT_SHIFT = 344
    const val RIGHT_CONTROL = 345
    const val RIGHT_ALT = 346
    const val RIGHT_SUPER = 347
    const val MENU = 348

    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1
    const val MOUSE_MIDDLE = 2
    const val MOUSE_BACK = 3
    const val MOUSE_FORWARD = 4
}

/**
 * One table between Android keys and GLFW keys. The GLFW path reads it one
 * way, the SDL3 path the other way: the HUD and the input layer speak GLFW,
 * and SDL3 on Android takes Android key codes, so both paths share every key.
 */
object KeyCodes {

    private val table: Map<Int, Int> = buildMap {
        for (index in 0..25) put(AndroidKeys.A + index, GlfwCodes.A + index)
        for (index in 0..9) put(AndroidKeys.DIGIT_0 + index, GlfwCodes.DIGIT_0 + index)
        for (index in 0..11) put(AndroidKeys.F1 + index, GlfwCodes.F1 + index)
        for (index in 0..9) put(AndroidKeys.NUMPAD_0 + index, GlfwCodes.KP_0 + index)
        put(AndroidKeys.SPACE, GlfwCodes.SPACE)
        put(AndroidKeys.APOSTROPHE, GlfwCodes.APOSTROPHE)
        put(AndroidKeys.COMMA, GlfwCodes.COMMA)
        put(AndroidKeys.MINUS, GlfwCodes.MINUS)
        put(AndroidKeys.PERIOD, GlfwCodes.PERIOD)
        put(AndroidKeys.SLASH, GlfwCodes.SLASH)
        put(AndroidKeys.SEMICOLON, GlfwCodes.SEMICOLON)
        put(AndroidKeys.EQUALS, GlfwCodes.EQUALS)
        put(AndroidKeys.LEFT_BRACKET, GlfwCodes.LEFT_BRACKET)
        put(AndroidKeys.BACKSLASH, GlfwCodes.BACKSLASH)
        put(AndroidKeys.RIGHT_BRACKET, GlfwCodes.RIGHT_BRACKET)
        put(AndroidKeys.GRAVE, GlfwCodes.GRAVE)
        put(AndroidKeys.ESCAPE, GlfwCodes.ESCAPE)
        put(AndroidKeys.ENTER, GlfwCodes.ENTER)
        put(AndroidKeys.TAB, GlfwCodes.TAB)
        put(AndroidKeys.DEL, GlfwCodes.BACKSPACE)
        put(AndroidKeys.INSERT, GlfwCodes.INSERT)
        put(AndroidKeys.FORWARD_DEL, GlfwCodes.DELETE)
        put(AndroidKeys.DPAD_RIGHT, GlfwCodes.RIGHT)
        put(AndroidKeys.DPAD_LEFT, GlfwCodes.LEFT)
        put(AndroidKeys.DPAD_DOWN, GlfwCodes.DOWN)
        put(AndroidKeys.DPAD_UP, GlfwCodes.UP)
        put(AndroidKeys.PAGE_UP, GlfwCodes.PAGE_UP)
        put(AndroidKeys.PAGE_DOWN, GlfwCodes.PAGE_DOWN)
        put(AndroidKeys.MOVE_HOME, GlfwCodes.HOME)
        put(AndroidKeys.MOVE_END, GlfwCodes.END)
        put(AndroidKeys.CAPS_LOCK, GlfwCodes.CAPS_LOCK)
        put(AndroidKeys.SCROLL_LOCK, GlfwCodes.SCROLL_LOCK)
        put(AndroidKeys.NUM_LOCK, GlfwCodes.NUM_LOCK)
        put(AndroidKeys.SYSRQ, GlfwCodes.PRINT_SCREEN)
        put(AndroidKeys.BREAK, GlfwCodes.PAUSE)
        put(AndroidKeys.NUMPAD_DOT, GlfwCodes.KP_DECIMAL)
        put(AndroidKeys.NUMPAD_DIVIDE, GlfwCodes.KP_DIVIDE)
        put(AndroidKeys.NUMPAD_MULTIPLY, GlfwCodes.KP_MULTIPLY)
        put(AndroidKeys.NUMPAD_SUBTRACT, GlfwCodes.KP_SUBTRACT)
        put(AndroidKeys.NUMPAD_ADD, GlfwCodes.KP_ADD)
        put(AndroidKeys.NUMPAD_ENTER, GlfwCodes.KP_ENTER)
        put(AndroidKeys.NUMPAD_EQUALS, GlfwCodes.KP_EQUAL)
        put(AndroidKeys.SHIFT_LEFT, GlfwCodes.LEFT_SHIFT)
        put(AndroidKeys.CTRL_LEFT, GlfwCodes.LEFT_CONTROL)
        put(AndroidKeys.ALT_LEFT, GlfwCodes.LEFT_ALT)
        put(AndroidKeys.META_LEFT, GlfwCodes.LEFT_SUPER)
        put(AndroidKeys.SHIFT_RIGHT, GlfwCodes.RIGHT_SHIFT)
        put(AndroidKeys.CTRL_RIGHT, GlfwCodes.RIGHT_CONTROL)
        put(AndroidKeys.ALT_RIGHT, GlfwCodes.RIGHT_ALT)
        put(AndroidKeys.META_RIGHT, GlfwCodes.RIGHT_SUPER)
        put(AndroidKeys.MENU, GlfwCodes.MENU)
    }

    /** A European keyboard's numpad comma types the decimal key, as on a desktop. */
    private val aliases: Map<Int, Int> = mapOf(AndroidKeys.NUMPAD_COMMA to GlfwCodes.KP_DECIMAL)

    private val reverse: Map<Int, Int> = table.entries.associate { (android, glfw) -> glfw to android }

    fun glfw(androidKey: Int): Int = table[androidKey] ?: aliases[androidKey] ?: GlfwCodes.UNKNOWN

    fun android(glfwKey: Int): Int? = reverse[glfwKey]

    fun mouseButton(androidButton: Int): Int? = when (androidButton) {
        AndroidKeys.BUTTON_PRIMARY -> GlfwCodes.MOUSE_LEFT
        AndroidKeys.BUTTON_SECONDARY -> GlfwCodes.MOUSE_RIGHT
        AndroidKeys.BUTTON_TERTIARY -> GlfwCodes.MOUSE_MIDDLE
        AndroidKeys.BUTTON_BACK -> GlfwCodes.MOUSE_BACK
        AndroidKeys.BUTTON_FORWARD -> GlfwCodes.MOUSE_FORWARD
        else -> null
    }

    /** The Android button bit of a GLFW button: SDL3 on Android takes the whole button state as Android bits. */
    fun androidButtonBit(glfwButton: Int): Int? = when (glfwButton) {
        GlfwCodes.MOUSE_LEFT -> AndroidKeys.BUTTON_PRIMARY
        GlfwCodes.MOUSE_RIGHT -> AndroidKeys.BUTTON_SECONDARY
        GlfwCodes.MOUSE_MIDDLE -> AndroidKeys.BUTTON_TERTIARY
        GlfwCodes.MOUSE_BACK -> AndroidKeys.BUTTON_BACK
        GlfwCodes.MOUSE_FORWARD -> AndroidKeys.BUTTON_FORWARD
        else -> null
    }
}

/**
 * A pointer position on the game view, turned into the game window's pixels.
 * The surface can be smaller than the view when the picture is drawn at a
 * lower resolution; a position outside the view sticks to its edge, because a
 * desktop cursor never leaves the window either.
 */
object CursorMapping {
    fun toWindow(x: Float, y: Float, viewWidth: Int, viewHeight: Int, windowWidth: Int, windowHeight: Int): Pair<Float, Float> {
        if (viewWidth <= 0 || viewHeight <= 0 || windowWidth <= 0 || windowHeight <= 0) return 0f to 0f
        val scaledX = x * windowWidth / viewWidth
        val scaledY = y * windowHeight / viewHeight
        return scaledX.coerceIn(0f, (windowWidth - 1).toFloat()) to scaledY.coerceIn(0f, (windowHeight - 1).toFloat())
    }
}
