package net.millida.launcher.core.friends

/**
 * Voice messages as the desktop launcher records and plays them
 * (src/lib/voice.ts, src/components/VoiceMessage.tsx).
 *
 * The desktop uploads MP3; Android has no MP3 encoder, so the phone records AAC
 * in an MP4 box, which the server accepts too (friends/voice-format.ts sniffs it).
 */
object VoiceNotes {

    /** The desktop recording limit: the recorder stops by itself here. */
    const val MAX_MS = 120_000L

    /** Shorter than this is a slipped finger, not a message. */
    const val MIN_MS = 700L

    /** Envelope points stored with the note, as the desktop computes them. */
    const val PEAKS = 48

    /** The server refuses a voice file above this (MAX_VOICE_BYTES). */
    const val MAX_BYTES = 3 * 1024 * 1024

    /** Brands the server accepts for an MP4 voice note (voice-format.ts M4A_BRANDS). */
    private val M4A_BRANDS = setOf("M4A ", "mp42", "isom", "iso6", "dash")

    /**
     * Levels of the whole take folded into [PEAKS] points of 0..100, scaled to
     * the loudest moment: the same envelope the desktop sends next to its file.
     */
    fun envelope(levels: List<Float>): List<Int> {
        if (levels.isEmpty()) return emptyList()
        val peak = levels.max().takeIf { it > 0f } ?: 1f
        val per = levels.size.toDouble() / PEAKS
        return List(PEAKS) { index ->
            val from = (index * per).toInt()
            val to = maxOf(from + 1, ((index + 1) * per).toInt())
            var max = 0f
            for (k in from until minOf(to, levels.size)) max = maxOf(max, levels[k])
            Math.round(max / peak * 100)
        }
    }

    /** The check the server makes, made before the upload so the error names the real cause. */
    fun isM4a(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (String(bytes, 4, 4, Charsets.US_ASCII) != "ftyp") return false
        return String(bytes, 8, 4, Charsets.US_ASCII) in M4A_BRANDS
    }

    /** A speech level on a linear scale sits low; the root spreads a quiet voice across the live strip. */
    fun liveBar(level: Float): Float = minOf(1f, kotlin.math.sqrt(level.coerceAtLeast(0f)) * 1.35f)

    /** Bars drawn in one bubble: at the recorded 48 points they merge into a solid block. */
    const val WAVE_BARS = 26

    private const val FLAT_BAR = 20

    /** Buckets are merged by their peak, so the loud parts stay loud. */
    fun downsample(peaks: List<Int>, count: Int = WAVE_BARS): List<Int> {
        if (count <= 0) return emptyList()
        if (peaks.isEmpty()) return List(count) { FLAT_BAR }
        if (peaks.size <= count) return peaks.map { it.coerceIn(0, 100) }
        val per = peaks.size.toDouble() / count
        return List(count) { index ->
            val from = (index * per).toInt()
            val to = ((index + 1) * per).toInt().coerceAtMost(peaks.size)
            (from until to).maxOfOrNull { peaks[it] }?.coerceIn(0, 100) ?: 0
        }
    }

    fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1000
        return "${total / 60}:" + (total % 60).toString().padStart(2, '0')
    }
}
