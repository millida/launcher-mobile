package net.millida.launcher.core.hub

import kotlinx.serialization.json.JsonObject
import net.millida.launcher.core.mojang.MojangJson

/** A mode tile's pictures: the mosaic background, the 16x16 item and, when drawn, the mode's rig. */
data class ModeArt(val code: String, val background: String, val icon: String, val rig: String?)

/**
 * The designer's set of mode pictures (the same files millida.net/katalog and
 * the desktop launcher use), shipped in the app under [ROOT]. The manifest
 * names the codes that have art; a code outside it has no picture.
 */
object ModeArts {
    const val ROOT = "mode-art"
    const val ONEBLOCK_BACKGROUND = "$ROOT/oneblock/bg.webp"
    const val ONEBLOCK_LOGO = "$ROOT/oneblock/logo.webp"
    const val ONEBLOCK_RIG = "$ROOT/oneblock/rig-47.webp"

    private val CODE = Regex("^[A-Z_]{2,24}$")

    fun parse(manifest: String): Map<String, ModeArt> {
        val root = runCatching { MojangJson.parseToJsonElement(manifest) as? JsonObject }.getOrNull() ?: return emptyMap()
        return root.entries
            .filter { (code, value) -> CODE.matches(code) && value is JsonObject }
            .associate { (code, value) ->
                val rig = (value as JsonObject)["rig"] is JsonObject
                code to ModeArt(
                    code = code,
                    background = "$ROOT/$code-bg.png",
                    icon = "$ROOT/$code.png",
                    rig = if (rig) "$ROOT/rigs/$code.webp" else null,
                )
            }
    }
}
