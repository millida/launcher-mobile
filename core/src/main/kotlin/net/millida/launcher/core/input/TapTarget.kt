package net.millida.launcher.core.input

import net.millida.launcher.core.hud.HudKeys

/** What the Millida mod says is under the crosshair. */
enum class TapTarget { NONE, BLOCK, ENTITY }

/**
 * The 16-byte file the mod keeps in the game folder: "MTT1", the target code, its
 * bitwise inverse (a torn write fails the check), two spare bytes, then the mod's
 * clock in milliseconds, big-endian. The layout is mirrored by the mod's
 * `TouchTargetCodec`; change both together.
 */
object TapTargetCodec {
    const val SIZE = 16
    const val FILE = "millida/touch-target.bin"
    const val MAX_AGE_MS = 250L
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())

    /** The target, or null when the file is missing, torn, from an unknown writer or older than [maxAgeMs]. */
    fun decode(bytes: ByteArray?, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): TapTarget? {
        if (bytes == null || bytes.size < SIZE) return null
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
        val code = bytes[4].toInt() and 0xFF
        if ((bytes[5].toInt() and 0xFF) != (code xor 0xFF)) return null
        var stamp = 0L
        for (i in 8 until 16) stamp = (stamp shl 8) or (bytes[i].toLong() and 0xFF)
        val age = nowMs - stamp
        if (age < -maxAgeMs || age > maxAgeMs) return null
        return TapTarget.entries.getOrNull(code)
    }

    fun encode(target: TapTarget, stampMs: Long): ByteArray {
        val out = ByteArray(SIZE)
        MAGIC.copyInto(out)
        out[4] = target.ordinal.toByte()
        out[5] = (target.ordinal xor 0xFF).toByte()
        for (i in 0 until 8) out[8 + i] = (stampMs ushr (56 - 8 * i)).toByte()
        return out
    }
}

/** Which mouse button a tap in the world becomes. */
object TapRouting {
    /**
     * [configured] is the scheme's own tap button. Only the touch scheme (tap uses,
     * hold breaks) is smart: a tap on a creature strikes it. With no word from the
     * mod, or a stale one, the configured button stands.
     */
    fun button(configured: Int, target: TapTarget?): Int =
        if (configured == HudKeys.MOUSE_RIGHT && target == TapTarget.ENTITY) HudKeys.MOUSE_LEFT else configured
}
