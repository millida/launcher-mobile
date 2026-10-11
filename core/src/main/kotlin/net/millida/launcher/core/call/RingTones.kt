package net.millida.launcher.core.call

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Ring { INCOMING, OUTGOING, ENDED }

/** [repeatMs] zero means a single signal without repeats. */
data class RingPattern(val notes: List<Int>, val repeatMs: Long, val gain: Float)

/**
 * The call ring and the dialling tone, note for note the desktop ones
 * (src/lib/call/ringtone.ts): one sound for the same event on every device.
 */
object RingTones {

    val PATTERNS: Map<Ring, RingPattern> = mapOf(
        Ring.INCOMING to RingPattern(listOf(880, 1174, 880, 1174), 2_600, 0.5f),
        Ring.OUTGOING to RingPattern(listOf(523, 659), 3_000, 0.22f),
        Ring.ENDED to RingPattern(listOf(659, 440), 0, 0.28f),
    )

    private const val NOTE_STEP_S = 0.22
    private const val NOTE_LENGTH_S = 0.24
    private const val ATTACK_S = 0.02
    private const val DECAY_END_S = 0.2
    private const val FLOOR = 0.0001

    /** Ring loudness from the launcher sound volume, as the desktop scales it. */
    fun level(volumePct: Int, pattern: RingPattern): Float =
        ((volumePct.coerceIn(0, 100) / 100f) * pattern.gain).coerceIn(0f, 1f)

    /**
     * One pass of the pattern as 16-bit mono PCM: each note is a sine with an
     * exponential rise to [level] and an exponential fall, the same envelope
     * the desktop builds with its gain ramps.
     */
    fun render(pattern: RingPattern, level: Float, sampleRate: Int): ShortArray {
        if (pattern.notes.isEmpty() || level <= 0f || sampleRate <= 0) return ShortArray(0)
        val seconds = (pattern.notes.size - 1) * NOTE_STEP_S + NOTE_LENGTH_S
        val out = DoubleArray((seconds * sampleRate).roundToInt())
        pattern.notes.forEachIndexed { index, hz ->
            val start = (index * NOTE_STEP_S * sampleRate).roundToInt()
            val length = (NOTE_LENGTH_S * sampleRate).roundToInt()
            for (i in 0 until length) {
                val at = start + i
                if (at >= out.size) break
                val t = i.toDouble() / sampleRate
                out[at] += sin(2 * PI * hz * t) * envelope(t, level.toDouble())
            }
        }
        return ShortArray(out.size) { (out[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt().toShort() }
    }

    private fun envelope(t: Double, level: Double): Double = when {
        t < ATTACK_S -> ramp(FLOOR, level, t / ATTACK_S)
        t < DECAY_END_S -> ramp(level, FLOOR, (t - ATTACK_S) / (DECAY_END_S - ATTACK_S))
        else -> 0.0
    }

    private fun ramp(from: Double, to: Double, share: Double): Double =
        from * exp(ln(to / from) * share.coerceIn(0.0, 1.0))
}
