package net.millida.launcher.core.friends

import java.net.IDN

import net.millida.launcher.core.i18n.tr
data class SharedMark(
    val name: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val dimension: String,
    val address: String,
)

/**
 * A waypoint shared from the game travels as a chat line the game mod writes
 * and reads (millida-mod WaypointShare.java, desktop src/lib/waypointShare.ts).
 * The rules mirror that parser exactly, so a line the game would refuse never
 * becomes a card here; a changed layout gets a new marker, never a looser pattern.
 */
object WaypointShare {

    const val PREFIX = "⟪mark⟫"

    private const val MAX_NAME = 32
    private const val MAX_HORIZONTAL = 30_000_000
    private const val MAX_Y = 4096
    private const val DEFAULT_PORT = 25565

    // JS `\v` is only U+000B, Java's would also take line separators, so it is spelled out.
    private val LINE = Regex(
        "⟪mark⟫ «([^«»]{1,64})» (-?[0-9]{1,8}) (-?[0-9]{1,5}) (-?[0-9]{1,8}) · ([a-z0-9_.:/-]{1,114}) · ([^ \\t\\n\\x0B\\f\\r«»]{1,96})",
    )
    private val DIMENSION = Regex("[a-z0-9_.-]{1,48}:[a-z0-9_./-]{1,64}")
    private val HOST = Regex("[a-z0-9._\\-\\[\\]]{1,90}")
    private val PORT = Regex("[0-9]{1,5}")
    private val SHORT_DIMENSIONS = mapOf(
        "overworld" to "minecraft:overworld",
        "nether" to "minecraft:the_nether",
        "end" to "minecraft:the_end",
    )

    fun parse(text: String?): SharedMark? {
        if (text == null || !text.startsWith(PREFIX)) return null
        val match = LINE.matchEntire(text) ?: return null
        val (name, xs, ys, zs, dim, addr) = match.destructured
        if (name != cleanName(name)) return null
        val x = xs.toIntOrNull() ?: 0
        val y = ys.toIntOrNull() ?: 0
        val z = zs.toIntOrNull() ?: 0
        if (kotlin.math.abs(x) > MAX_HORIZONTAL || kotlin.math.abs(z) > MAX_HORIZONTAL || kotlin.math.abs(y) > MAX_Y) return null
        val dimension = fullDimension(dim) ?: return null
        val server = serverKey(addr) ?: return null
        val address = server.removeSuffix(":$DEFAULT_PORT")
        return SharedMark(name, x, y, z, dimension, address)
    }

    fun coords(mark: SharedMark): String = "${mark.x} ${mark.y} ${mark.z}"

    fun dimensionLabel(dimension: String): String = when (dimension) {
        "minecraft:overworld" -> tr("Верхний мир")
        "minecraft:the_nether" -> tr("Незер")
        "minecraft:the_end" -> tr("Энд")
        else -> dimension
    }

    fun preview(mark: SharedMark): String = tr("Метка «{name}»", mark.name)

    private fun fullDimension(token: String): String? =
        SHORT_DIMENSIONS[token] ?: token.takeIf { DIMENSION.matches(it) }

    private fun isHidden(c: Int): Boolean {
        if (c <= 0x1f || c in 0x7f..0x9f || c == 0xad) return true
        if (c in 0x200b..0x200f || c in 0x2028..0x202e || c in 0x2060..0x206f || c == 0xfeff) return true
        val type = Character.getType(c)
        return type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt()
    }

    private fun safeClean(raw: String, max: Int): String {
        val out = StringBuilder()
        var space = false
        var kept = 0
        var i = 0
        while (i < raw.length && kept < max) {
            val c = raw.codePointAt(i)
            i += Character.charCount(c)
            if (c == 0xa7) {
                if (i < raw.length) i += Character.charCount(raw.codePointAt(i))
                continue
            }
            if (Character.isWhitespace(c) || isHidden(c)) {
                space = out.isNotEmpty()
                continue
            }
            if (space) {
                out.append(' ')
                kept++
                space = false
                if (kept >= max) break
            }
            out.appendCodePoint(c)
            kept++
        }
        return out.toString()
    }

    private fun cleanName(raw: String): String = safeClean(raw, MAX_NAME).replace("«", "").replace("»", "").trim()

    private fun asciiHost(host: String): String? {
        val ascii = if (host.any { it.code > 0x7f }) {
            runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase() }.getOrNull() ?: return null
        } else {
            host
        }
        return ascii.takeIf { value -> value.split('.').all { it.isNotEmpty() && it.length <= 63 } }
    }

    /** Mirrors the game's WaypointScope.server: lower case host, numeric port, null for anything else. */
    private fun serverKey(raw: String): String? {
        var address = raw.trim().lowercase()
        while (address.endsWith('.')) address = address.dropLast(1)
        if (address.isEmpty() || address.length > 96) return null
        var host = address
        var port = DEFAULT_PORT.toString()
        val colon = address.lastIndexOf(':')
        if (colon > 0 && address.indexOf(':') == colon) {
            host = address.substring(0, colon)
            port = address.substring(colon + 1)
            if (!PORT.matches(port)) return null
        }
        val ascii = asciiHost(host) ?: return null
        if (!HOST.matches(ascii)) return null
        val number = port.toInt()
        if (number !in 1..65535) return null
        return "$ascii:$number"
    }
}
