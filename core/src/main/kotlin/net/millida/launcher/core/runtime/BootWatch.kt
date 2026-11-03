package net.millida.launcher.core.runtime

/**
 * What a start that has drawn nothing yet is doing, judged by its log.
 *
 * Until 1.9.24 the answer was a clock: no frame in a minute meant a failed renderer,
 * and at two minutes the loading screen was taken away. Telemetry of 13.10.2026 says
 * the clock is wrong for one start in six: of 63 sessions that did draw, 11 drew
 * their first frame later than a minute (median 27 s, nine in ten by 73 s), and a
 * pack of 228 mods on 1.7.10 has no window at all for minutes. Those players had
 * the loading screen removed over a game that was still loading — the «чёрный
 * экран» they wrote about — and their renderer marked as failed for nothing.
 *
 * A game that is loading keeps writing its log. One that hung in its renderer
 * goes quiet. So past the first minute the log decides, and the clock is only
 * the far limit.
 */
object BootWatch {
    /** A loading game is not silent this long; a hung one is. */
    const val QUIET_MS = 60_000L

    /** No frame by now is a failed start whatever the log does: errors repeated every frame also fill a log. */
    const val CAP_MS = 5 * 60_000L

    enum class Verdict {
        /** Inside the first minute nothing is judged. */
        WAITING,

        /** Past the minute, the log is alive: the loading screen stays and the renderer is not blamed. */
        LOADING,

        /** Past the minute and the log went quiet: a black screen. */
        SILENT,

        /** The far limit. */
        OVERDUE,
    }

    fun verdict(elapsedMs: Long, quietMs: Long): Verdict = when {
        elapsedMs < RendererFallback.NO_FRAME_GRACE_MS -> Verdict.WAITING
        elapsedMs >= CAP_MS -> Verdict.OVERDUE
        quietMs >= QUIET_MS -> Verdict.SILENT
        else -> Verdict.LOADING
    }

    /** `2:05` for the loading screen, shown once the start is past its first minute. */
    fun clock(elapsedMs: Long): String {
        val seconds = (elapsedMs / 1000).coerceAtLeast(0)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}
