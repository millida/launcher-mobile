package net.millida.launcher.core.hud

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import net.millida.launcher.core.mojang.MojangJson
import java.util.Locale

enum class HudSource { MILLIDA, POJAV, FCL, ZALITH }

data class HudImported(val layout: HudLayout, val source: HudSource, val skipped: Int)

/**
 * Layouts of the other Android launchers. Positions are placed on the nominal
 * screen of our own presets, keys go through the same bindable list as the
 * editor, and whatever has no counterpart here (macros, layer switches,
 * drawers) is counted as skipped instead of guessed.
 */
object HudForeign {

    private const val SCREEN_W = HudPresets.NOMINAL_WIDTH
    private const val SCREEN_H = HudPresets.NOMINAL_HEIGHT
    private const val POJAV_MARGIN = 2f
    private const val FCL_SCALE = 1000f
    private const val ZALITH_SCALE = 10000f
    private const val DEFAULT_SIDE = 50f

    fun read(text: String): HudImported? {
        if (text.isBlank() || text.length > MAX_TEXT) return null
        HudTransfer.decode(text)?.let { return HudImported(it, HudSource.MILLIDA, 0) }
        val tree = runCatching { MojangJson.parseToJsonElement(text.trim()) }.getOrNull() as? JsonObject ?: return null
        val draft = when {
            "mControlDataList" in tree || "mJoystickDataList" in tree -> pojav(tree)
            "viewGroups" in tree -> fcl(tree)
            "layers" in tree -> zalith(tree)
            else -> null
        } ?: return null
        val layout = HudTransfer.sanitize(draft.layout)
        if (layout.controls.isEmpty()) return null
        return HudImported(layout, draft.source, draft.skipped + (draft.layout.controls.size - layout.controls.size).coerceAtLeast(0))
    }

    const val MAX_TEXT = 4_000_000

    private class Draft(val layout: HudLayout, val source: HudSource, val skipped: Int)

    private sealed interface Binding {
        data class Keys(val codes: List<Int>) : Binding
        data class Mouse(val button: Int) : Binding
        data class Wheel(val notches: Int) : Binding
        data class Act(val action: HudAction) : Binding
    }

    private fun control(
        index: Int,
        label: String,
        binding: Binding,
        centreX: Float,
        centreY: Float,
        width: Float,
        height: Float,
        toggle: Boolean,
        visibility: HudVisibility,
        hidden: Boolean,
        opacity: Float? = null,
    ): HudControl {
        val id = "imp$index"
        val x = centreX / SCREEN_W
        val y = centreY / SCREEN_H
        val shown = label.trim()
        return when (binding) {
            is Binding.Keys -> HudControl(
                id = id,
                label = shown.ifEmpty { binding.codes.joinToString("+") { HudKeys.name(it) } },
                kind = HudKind.KEY,
                code = binding.codes.first(),
                codes = if (binding.codes.size > 1) binding.codes.take(HudControl.MAX_KEYS) else emptyList(),
                x = x, y = y, width = width, height = height, toggle = toggle, visibility = visibility, hidden = hidden, opacity = opacity,
            )
            is Binding.Mouse -> HudControl(
                id, shown.ifEmpty { "M${binding.button + 1}" }, HudKind.MOUSE, code = binding.button,
                x = x, y = y, width = width, height = height, toggle = toggle, visibility = visibility, hidden = hidden, opacity = opacity,
            )
            is Binding.Wheel -> HudControl(
                id, shown.ifEmpty { if (binding.notches > 0) "+" else "-" }, HudKind.SCROLL, code = binding.notches,
                x = x, y = y, width = width, height = height, visibility = visibility, hidden = hidden, opacity = opacity,
            )
            is Binding.Act -> HudControl(
                id, shown.ifEmpty { binding.action.name }, HudKind.ACTION, action = binding.action,
                x = x, y = y, width = width, height = height, visibility = visibility, hidden = hidden, opacity = opacity,
            )
        }
    }

    private fun stick(index: Int, centreX: Float, centreY: Float, side: Float, visibility: HudVisibility, hidden: Boolean): HudControl =
        HudControl(
            id = "imp$index",
            label = "",
            kind = HudKind.STICK,
            x = centreX / SCREEN_W,
            y = centreY / SCREEN_H,
            width = side,
            height = side,
            visibility = visibility,
            hidden = hidden,
        )

    private fun pojav(tree: JsonObject): Draft {
        val controls = mutableListOf<HudControl>()
        var skipped = 0
        tree.array("mControlDataList").forEach { element ->
            val item = element as? JsonObject
            if (item == null) {
                skipped++
                return@forEach
            }
            val width = item.float("width") ?: DEFAULT_SIDE
            val height = item.float("height") ?: DEFAULT_SIDE
            val left = PojavExpression.evaluate(item.string("dynamicX"), width, height) ?: item.float("x")
            val top = PojavExpression.evaluate(item.string("dynamicY"), width, height) ?: item.float("y")
            val binding = pojavBinding(item.array("keycodes").mapNotNull { (it as? JsonPrimitive)?.intOrNull })
            if (left == null || top == null || binding == null) {
                skipped++
                return@forEach
            }
            val inGame = item.bool("displayInGame") ?: true
            val inMenu = item.bool("displayInMenu") ?: false
            controls += control(
                index = controls.size,
                label = item.string("name"),
                binding = binding,
                centreX = left + width / 2f,
                centreY = top + height / 2f,
                width = width,
                height = height,
                toggle = item.bool("isToggle") ?: false,
                visibility = when {
                    inGame && inMenu -> HudVisibility.ALWAYS
                    inMenu -> HudVisibility.CURSOR
                    else -> HudVisibility.LOOK
                },
                hidden = !inGame && !inMenu,
                opacity = item.float("opacity"),
            )
        }
        tree.array("mJoystickDataList").forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val width = item.float("width") ?: DEFAULT_SIDE * 3
            val height = item.float("height") ?: width
            val left = PojavExpression.evaluate(item.string("dynamicX"), width, height)
            val top = PojavExpression.evaluate(item.string("dynamicY"), width, height)
            if (left == null || top == null) {
                skipped++
                return@forEach
            }
            controls += stick(controls.size, left + width / 2f, top + height / 2f, maxOf(width, height), HudVisibility.LOOK, hidden = false)
        }
        skipped += tree.array("mDrawerDataList").size
        return Draft(HudLayout(name = "Pojav", controls = controls), HudSource.POJAV, skipped)
    }

    private fun pojavBinding(codes: List<Int>): Binding? {
        val special = codes.firstOrNull { it < 0 }
        if (special != null) {
            return when (special) {
                -1 -> Binding.Act(HudAction.KEYBOARD)
                -3 -> Binding.Mouse(HudKeys.MOUSE_LEFT)
                -4 -> Binding.Mouse(HudKeys.MOUSE_RIGHT)
                -6 -> Binding.Mouse(HudKeys.MOUSE_MIDDLE)
                -5 -> Binding.Act(HudAction.POINTER_MODE)
                -7 -> Binding.Wheel(HudKeys.SCROLL_UP)
                -8 -> Binding.Wheel(HudKeys.SCROLL_DOWN)
                -9 -> Binding.Act(HudAction.MENU)
                else -> null
            }
        }
        val keys = codes.filter { HudKeys.isBindable(it) }.distinct()
        return if (keys.isEmpty()) null else Binding.Keys(keys)
    }

    private fun fcl(tree: JsonObject): Draft {
        val controls = mutableListOf<HudControl>()
        var skipped = 0
        tree.array("viewGroups").forEach { groupElement ->
            val group = groupElement as? JsonObject ?: return@forEach
            val hidden = group.string("visibility") == "INVISIBLE"
            val data = group.obj("viewData") ?: return@forEach
            data.array("buttonList").forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val base = item.obj("baseInfo") ?: JsonObject(emptyMap())
                val event = item.obj("event") ?: JsonObject(emptyMap())
                val binding = fclBinding(event.obj("pressEvent")) ?: fclBinding(event.obj("clickEvent"))
                if (binding == null) {
                    skipped++
                    return@forEach
                }
                val (left, top, width, height) = fclRect(base)
                val autoKeep = event.obj("pressEvent")?.bool("autoKeep") ?: false
                controls += control(
                    index = controls.size,
                    label = item.string("text"),
                    binding = binding,
                    centreX = left + width / 2f,
                    centreY = top + height / 2f,
                    width = width,
                    height = height,
                    toggle = autoKeep,
                    visibility = fclVisibility(base.string("visibilityType")),
                    hidden = hidden,
                )
            }
            data.array("directionList").forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val base = item.obj("baseInfo") ?: JsonObject(emptyMap())
                val (left, top, width, height) = fclRect(base)
                controls += stick(controls.size, left + width / 2f, top + height / 2f, maxOf(width, height), fclVisibility(base.string("visibilityType")), hidden)
            }
        }
        val name = tree.string("name").ifEmpty { "FCL" }
        return Draft(HudLayout(name = name, controls = controls), HudSource.FCL, skipped)
    }

    private data class Box(val left: Float, val top: Float, val width: Float, val height: Float)

    private fun fclRect(base: JsonObject): Box {
        val absolute = base.string("sizeType") == "ABSOLUTE"
        val width = if (absolute) base.float("absoluteWidth") ?: DEFAULT_SIDE else share(base.obj("percentageWidth"), FCL_SCALE)
        val height = if (absolute) base.float("absoluteHeight") ?: DEFAULT_SIDE else share(base.obj("percentageHeight"), FCL_SCALE)
        val left = (SCREEN_W - width) * ((base.float("xPosition") ?: 0f) / FCL_SCALE)
        val top = (SCREEN_H - height) * ((base.float("yPosition") ?: 0f) / FCL_SCALE)
        return Box(left, top, width, height)
    }

    private fun share(size: JsonObject?, scale: Float): Float {
        if (size == null) return DEFAULT_SIDE
        val reference = if (size.string("reference").equals("SCREEN_HEIGHT", ignoreCase = true)) SCREEN_H else SCREEN_W
        return reference * ((size.float("size") ?: DEFAULT_SIDE) / scale)
    }

    private fun fclVisibility(value: String): HudVisibility = when (value.uppercase()) {
        "IN_GAME" -> HudVisibility.LOOK
        "IN_MENU" -> HudVisibility.CURSOR
        else -> HudVisibility.ALWAYS
    }

    private fun fclBinding(event: JsonObject?): Binding? {
        if (event == null) return null
        if (event.bool("input") == true) return Binding.Act(HudAction.KEYBOARD)
        if (event.bool("openMenu") == true) return Binding.Act(HudAction.MENU)
        if (event.bool("switchTouchMode") == true || event.bool("switchMouseMode") == true) return Binding.Act(HudAction.POINTER_MODE)
        val codes = event.array("outputKeycodes").mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        codes.firstNotNullOfOrNull { FCL_MOUSE[it] }?.let { return it }
        val keys = codes.mapNotNull { FCL_KEYS[it] }.filter { HudKeys.isBindable(it) }.distinct()
        return if (keys.isEmpty()) null else Binding.Keys(keys)
    }

    private fun zalith(tree: JsonObject): Draft {
        val controls = mutableListOf<HudControl>()
        var skipped = 0
        tree.array("layers").forEach { layerElement ->
            val layer = layerElement as? JsonObject ?: return@forEach
            val hidden = layer.bool("hide") ?: false
            val layerVisibility = layer.string("visibilityType")
            layer.array("normalButtons").forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val binding = zalithBinding(item.array("clickEvents"))
                if (binding == null) {
                    skipped++
                    return@forEach
                }
                val size = item.obj("buttonSize") ?: JsonObject(emptyMap())
                val percentage = size.string("type") == "percentage"
                val width = if (percentage) zalithShare(size, "widthPercentage", "widthReference") else size.float("widthDp") ?: DEFAULT_SIDE
                val height = if (percentage) zalithShare(size, "heightPercentage", "heightReference") else size.float("heightDp") ?: DEFAULT_SIDE
                val position = item.obj("position") ?: JsonObject(emptyMap())
                val left = (SCREEN_W - width) * ((position.float("x") ?: 0f) / ZALITH_SCALE)
                val top = (SCREEN_H - height) * ((position.float("y") ?: 0f) / ZALITH_SCALE)
                val own = item.string("visibilityType")
                controls += control(
                    index = controls.size,
                    label = item.obj("text")?.string("default") ?: item.string("text"),
                    binding = binding,
                    centreX = left + width / 2f,
                    centreY = top + height / 2f,
                    width = width,
                    height = height,
                    toggle = item.bool("isToggleable") ?: false,
                    visibility = zalithVisibility(own.takeIf { it.isNotEmpty() && it != "always" } ?: layerVisibility),
                    hidden = hidden,
                )
            }
        }
        val name = (tree.obj("info")?.obj("name")?.string("default")).orEmpty().ifEmpty { "Zalith" }
        return Draft(HudLayout(name = name, controls = controls), HudSource.ZALITH, skipped)
    }

    private fun zalithShare(size: JsonObject, value: String, reference: String): Float {
        val base = if (size.string(reference) == "screen_height") SCREEN_H else SCREEN_W
        return base * ((size.float(value) ?: 1000f) / ZALITH_SCALE)
    }

    private fun zalithVisibility(value: String): HudVisibility = when (value) {
        "in_game" -> HudVisibility.LOOK
        "in_menu" -> HudVisibility.CURSOR
        else -> HudVisibility.ALWAYS
    }

    private fun zalithBinding(events: JsonArray): Binding? {
        val keys = mutableListOf<Int>()
        events.forEach { element ->
            val event = element as? JsonObject ?: return@forEach
            val key = event.string("key")
            when (event.string("type")) {
                "key" -> {
                    ZALITH_MOUSE[key]?.let { return Binding.Mouse(it) }
                    GLFW_NAMES[key]?.let { keys += it }
                }
                "launcher_event" -> when {
                    key == "launcher.event.switch_ime" -> return Binding.Act(HudAction.KEYBOARD)
                    key == "launcher.event.switch_menu" -> return Binding.Act(HudAction.MENU)
                    key.startsWith("launcher.event.scroll_up") -> return Binding.Wheel(HudKeys.SCROLL_UP)
                    key.startsWith("launcher.event.scroll_down") -> return Binding.Wheel(HudKeys.SCROLL_DOWN)
                }
            }
        }
        val bindable = keys.filter { HudKeys.isBindable(it) }.distinct()
        return if (bindable.isEmpty()) null else Binding.Keys(bindable)
    }

    private val GLFW_NAMES: Map<String, Int> = buildMap {
        ('A'..'Z').forEach { put("GLFW_KEY_$it", it.code) }
        ('0'..'9').forEach { put("GLFW_KEY_$it", it.code) }
        (1..12).forEach { put("GLFW_KEY_F$it", HudKeys.F1 + it - 1) }
        put("GLFW_KEY_SPACE", HudKeys.SPACE)
        put("GLFW_KEY_LEFT_SHIFT", HudKeys.LEFT_SHIFT)
        put("GLFW_KEY_LEFT_CONTROL", HudKeys.LEFT_CONTROL)
        put("GLFW_KEY_LEFT_ALT", HudKeys.LEFT_ALT)
        put("GLFW_KEY_TAB", HudKeys.TAB)
        put("GLFW_KEY_ESCAPE", HudKeys.ESCAPE)
        put("GLFW_KEY_ENTER", HudKeys.ENTER)
        put("GLFW_KEY_BACKSPACE", HudKeys.BACKSPACE)
        put("GLFW_KEY_UP", HudKeys.UP)
        put("GLFW_KEY_DOWN", HudKeys.DOWN)
        put("GLFW_KEY_LEFT", HudKeys.LEFT)
        put("GLFW_KEY_RIGHT", HudKeys.RIGHT)
    }

    private val ZALITH_MOUSE: Map<String, Int> = mapOf(
        "GLFW_MOUSE_BUTTON_LEFT" to HudKeys.MOUSE_LEFT,
        "GLFW_MOUSE_BUTTON_1" to HudKeys.MOUSE_LEFT,
        "GLFW_MOUSE_BUTTON_RIGHT" to HudKeys.MOUSE_RIGHT,
        "GLFW_MOUSE_BUTTON_2" to HudKeys.MOUSE_RIGHT,
        "GLFW_MOUSE_BUTTON_MIDDLE" to HudKeys.MOUSE_MIDDLE,
        "GLFW_MOUSE_BUTTON_3" to HudKeys.MOUSE_MIDDLE,
    )

    /** FCL numbers its keys after Linux input codes and puts the mouse above a thousand. */
    private val FCL_MOUSE: Map<Int, Binding> = mapOf(
        1000 to Binding.Mouse(HudKeys.MOUSE_LEFT),
        1001 to Binding.Mouse(HudKeys.MOUSE_MIDDLE),
        1002 to Binding.Mouse(HudKeys.MOUSE_RIGHT),
        1003 to Binding.Wheel(HudKeys.SCROLL_UP),
        1004 to Binding.Wheel(HudKeys.SCROLL_DOWN),
    )

    private val FCL_KEYS: Map<Int, Int> = buildMap {
        "QWERTYUIOP".forEachIndexed { i, c -> put(16 + i, c.code) }
        "ASDFGHJKL".forEachIndexed { i, c -> put(30 + i, c.code) }
        "ZXCVBNM".forEachIndexed { i, c -> put(44 + i, c.code) }
        "1234567890".forEachIndexed { i, c -> put(2 + i, c.code) }
        (0 until 10).forEach { put(59 + it, HudKeys.F1 + it) }
        put(87, HudKeys.F1 + 10)
        put(88, HudKeys.F12)
        put(1, HudKeys.ESCAPE)
        put(14, HudKeys.BACKSPACE)
        put(15, HudKeys.TAB)
        put(28, HudKeys.ENTER)
        put(29, HudKeys.LEFT_CONTROL)
        put(42, HudKeys.LEFT_SHIFT)
        put(56, HudKeys.LEFT_ALT)
        put(57, HudKeys.SPACE)
        put(103, HudKeys.UP)
        put(105, HudKeys.LEFT)
        put(106, HudKeys.RIGHT)
        put(108, HudKeys.DOWN)
    }

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun JsonObject.float(name: String): Float? = (this[name] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

    /**
     * Pojav keeps a place as arithmetic over named sizes. It is evaluated on our
     * nominal screen at density one, where a pixel and a dp are the same thing.
     */
    internal object PojavExpression {

        fun evaluate(source: String, width: Float, height: Float): Float? {
            if (source.isBlank() || source.length > MAX_EXPRESSION) return null
            val values = mapOf(
                "top" to 0f,
                "left" to 0f,
                "right" to SCREEN_W - width,
                "bottom" to SCREEN_H - height,
                "width" to width,
                "height" to height,
                "screen_width" to SCREEN_W,
                "screen_height" to SCREEN_H,
                "margin" to POJAV_MARGIN,
                "preferred_scale" to 100f,
            )
            var text = source
            values.forEach { (name, value) -> text = text.replace("\${$name}", String.format(Locale.ROOT, "%.4f", value)) }
            if ('$' in text) return null
            return runCatching { Parser(text).parse() }.getOrNull()?.takeIf { it.isFinite() }
        }

        private const val MAX_EXPRESSION = 512

        private class Parser(private val text: String) {
            private var at = 0

            fun parse(): Float {
                val value = sum()
                skip()
                require(at == text.length)
                return value
            }

            private fun skip() {
                while (at < text.length && text[at].isWhitespace()) at++
            }

            private fun sum(): Float {
                var value = product()
                while (true) {
                    skip()
                    value = when (text.getOrNull(at)) {
                        '+' -> { at++; value + product() }
                        '-' -> { at++; value - product() }
                        else -> return value
                    }
                }
            }

            private fun product(): Float {
                var value = unary()
                while (true) {
                    skip()
                    value = when (text.getOrNull(at)) {
                        '*' -> { at++; value * unary() }
                        '/' -> {
                            at++
                            val divisor = unary()
                            require(divisor != 0f)
                            value / divisor
                        }
                        else -> return value
                    }
                }
            }

            private fun unary(): Float {
                skip()
                return when (text.getOrNull(at)) {
                    '-' -> { at++; -unary() }
                    '+' -> { at++; unary() }
                    else -> atom()
                }
            }

            private fun atom(): Float {
                skip()
                val c = text.getOrNull(at) ?: error("end")
                if (c == '(') {
                    at++
                    val value = sum()
                    skip()
                    require(text.getOrNull(at) == ')')
                    at++
                    return value
                }
                if (c.isLetter()) {
                    val start = at
                    while (at < text.length && text[at].isLetter()) at++
                    val name = text.substring(start, at)
                    require(name == "dp" || name == "px")
                    skip()
                    require(text.getOrNull(at) == '(')
                    return atom()
                }
                val start = at
                while (at < text.length && (text[at].isDigit() || text[at] == '.')) at++
                return text.substring(start, at).toFloat()
            }
        }
    }
}
