package net.millida.launcher.core.hud

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.mojang.MojangJson
import java.io.File

/**
 * Every arrangement the player keeps, by name. [version] has no default on
 * purpose: defaults are not written, and a file without it reads as the
 * single layout of the older launcher.
 */
@Serializable
data class HudProfiles(
    val version: Int,
    val active: String = HudPresets.BEDROCK,
    val profiles: Map<String, HudLayout> = emptyMap(),
    val legacyNote: Boolean = false,
) {
    val activeName: String
        get() = if (active in profiles) active else profiles.keys.firstOrNull() ?: HudPresets.BEDROCK

    val layout: HudLayout
        get() = profiles[activeName] ?: HudPresets.bedrock()

    val names: List<String>
        get() = profiles.keys.toList()

    val hasLegacy: Boolean
        get() = LEGACY in profiles

    fun withLayout(next: HudLayout): HudProfiles = copy(active = activeName, profiles = profiles + (activeName to next))

    fun switchTo(name: String): HudProfiles = if (name in profiles) copy(active = name) else this

    fun create(name: String, layout: HudLayout = HudPresets.bedrock()): HudProfiles {
        if (profiles.size >= MAX_PROFILES) return this
        val unique = uniqueName(name)
        return copy(active = unique, profiles = profiles + (unique to layout))
    }

    fun duplicate(): HudProfiles = create("$activeName $COPY_SUFFIX", layout)

    fun rename(from: String, to: String): HudProfiles {
        val wanted = cleanName(to)
        if (from !in profiles || wanted.isEmpty() || wanted == from) return this
        val unique = uniqueName(wanted)
        val renamed = LinkedHashMap<String, HudLayout>()
        profiles.forEach { (name, layout) -> renamed[if (name == from) unique else name] = layout }
        return copy(active = if (activeName == from) unique else activeName, profiles = renamed)
    }

    fun delete(name: String): HudProfiles {
        if (name !in profiles || profiles.size <= 1) return this
        val rest = LinkedHashMap(profiles).apply { remove(name) }
        val nextActive = if (activeName == name) rest.keys.first() else activeName
        return copy(active = nextActive, profiles = rest)
    }

    fun dismissNote(): HudProfiles = copy(legacyNote = false)

    fun restoreLegacy(): HudProfiles = switchTo(LEGACY).dismissNote()

    fun uniqueName(base: String): String {
        val clean = cleanName(base).ifEmpty { NEW_NAME }
        if (clean !in profiles) return clean
        var suffix = 2
        while ("$clean $suffix" in profiles) suffix++
        return "$clean $suffix"
    }

    /** A file edited by hand or written by a newer build must still leave the player with controls. */
    fun normalized(): HudProfiles {
        val kept = LinkedHashMap<String, HudLayout>()
        profiles.forEach { (name, layout) ->
            val clean = cleanName(name)
            if (clean.isNotEmpty() && layout.controls.isNotEmpty() && clean !in kept && kept.size < MAX_PROFILES) {
                kept[clean] = layout
            }
        }
        if (kept.isEmpty()) return initial()
        val nextActive = if (active in kept) active else kept.keys.first()
        return copy(version = VERSION, active = nextActive, profiles = kept, legacyNote = legacyNote && LEGACY in kept)
    }

    companion object {
        const val VERSION = 2
        const val LEGACY = "Старая раскладка"
        const val NEW_NAME = "Моя раскладка"
        const val COPY_SUFFIX = "копия"
        const val MAX_PROFILES = 16
        const val MAX_NAME = 32

        fun initial(): HudProfiles = HudProfiles(
            version = VERSION,
            active = HudPresets.BEDROCK,
            profiles = linkedMapOf(HudPresets.BEDROCK to HudPresets.bedrock()),
        )

        /** The single layout of the older launcher is kept as a profile, and the new preset starts active. */
        fun migrate(old: HudLayout): HudProfiles {
            if (old.controls.isEmpty()) return initial()
            return HudProfiles(
                version = VERSION,
                active = HudPresets.BEDROCK,
                profiles = linkedMapOf(HudPresets.BEDROCK to HudPresets.bedrock(), LEGACY to old),
                legacyNote = true,
            )
        }

        fun parse(text: String): HudProfiles? {
            val tree = runCatching { MojangJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            return runCatching {
                if ("version" in tree) {
                    MojangJson.decodeFromJsonElement<HudProfiles>(tree).normalized()
                } else {
                    migrate(MojangJson.decodeFromJsonElement<HudLayout>(tree))
                }
            }.getOrNull()
        }

        private fun cleanName(name: String): String =
            name.filterNot { it.isISOControl() }.trim().take(MAX_NAME)
    }
}

/**
 * A layout passed between players through the clipboard. Whatever arrives
 * there is foreign text, so everything is clamped to what the editor itself
 * could have produced.
 */
object HudTransfer {
    const val MAX_TEXT = 64_000
    const val MAX_CONTROLS = 64
    const val MAX_LABEL = 24

    private const val MIN_SIDE = 16f
    private const val MAX_SIDE = 400f
    private const val MAX_MOUSE_BUTTON = 7

    fun encode(layout: HudLayout): String = MojangJson.encodeToString(layout)

    fun decode(text: String): HudLayout? {
        if (text.isBlank() || text.length > MAX_TEXT) return null
        val tree = runCatching { MojangJson.parseToJsonElement(text.trim()) }.getOrNull() as? JsonObject ?: return null
        val layout = runCatching { MojangJson.decodeFromJsonElement<HudLayout>(tree) }.getOrNull() ?: return null
        return sanitize(layout).takeIf { it.controls.isNotEmpty() }
    }

    fun sanitize(layout: HudLayout): HudLayout {
        var result = HudLayout(
            name = layout.name.filterNot { it.isISOControl() }.trim().take(HudProfiles.MAX_NAME).ifEmpty { HudProfiles.NEW_NAME },
            opacity = layout.opacity.takeIf { it.isFinite() }?.coerceIn(HudLayout.MIN_OPACITY, 1f) ?: HudLayout.DEFAULT_OPACITY,
            lookTap = if (layout.lookTap == HudKeys.MOUSE_RIGHT) HudKeys.MOUSE_RIGHT else HudKeys.MOUSE_LEFT,
            holdDelay = layout.holdDelay.coerceIn(HudLayout.MIN_HOLD_MS, HudLayout.MAX_HOLD_MS),
        )
        layout.controls.asSequence()
            .mapNotNull(::control)
            .take(MAX_CONTROLS)
            .forEach { result = result.add(it) }
        return result
    }

    private fun control(source: HudControl): HudControl? {
        val code = when (source.kind) {
            HudKind.KEY, HudKind.JUMP, HudKind.HOTBAR_MORE -> source.code.takeIf { HudKeys.isBindable(it) } ?: return null
            HudKind.MOUSE -> source.code.takeIf { it in 0..MAX_MOUSE_BUTTON } ?: return null
            HudKind.SCROLL -> if (source.code < 0) HudKeys.SCROLL_DOWN else HudKeys.SCROLL_UP
            HudKind.STICK, HudKind.FLOAT_STICK, HudKind.ACTION -> 0
        }
        val codes = if (source.rebindable) {
            source.codes.filter { HudKeys.isBindable(it) }.distinct().take(HudControl.MAX_KEYS)
        } else {
            emptyList()
        }
        val id = source.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_LABEL).ifEmpty { "key" }
        return source.copy(
            id = id,
            label = source.label.filterNot { it.isISOControl() }.trim().take(MAX_LABEL),
            code = code,
            x = finite(source.x, 0.5f).coerceIn(0.02f, 0.98f),
            y = finite(source.y, 0.5f).coerceIn(0.02f, 0.98f),
            width = finite(source.width, MIN_SIDE).coerceIn(MIN_SIDE, MAX_SIDE),
            height = finite(source.height, MIN_SIDE).coerceIn(MIN_SIDE, MAX_SIDE),
            opacity = source.opacity?.takeIf { it.isFinite() }?.coerceIn(HudLayout.MIN_OPACITY, 1f),
            codes = codes,
            icon = source.icon?.takeIf { it in HudIcons.all },
        )
    }

    private fun finite(value: Float, fallback: Float): Float = if (value.isFinite()) value else fallback
}

class HudStore(private val layout: GameLayout) {

    private val file: File
        get() = File(layout.root, "hud.json")

    fun load(): HudProfiles {
        val current = file
        if (!current.isFile) return HudProfiles.initial()
        val text = runCatching { current.readText() }.getOrNull() ?: return HudProfiles.initial()
        return HudProfiles.parse(text) ?: HudProfiles.initial()
    }

    fun save(value: HudProfiles) {
        layout.ensure()
        file.atomicWriteText(MojangJson.encodeToString(value))
    }
}
