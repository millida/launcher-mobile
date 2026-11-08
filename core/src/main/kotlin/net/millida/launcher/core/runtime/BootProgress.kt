package net.millida.launcher.core.runtime

/**
 * The percent of the game's own loading, shown while the window is not up yet.
 *
 * Two sources, the larger wins and the number never goes back: the log lines the loader is known
 * to write (each one is a floor), and the clock against the last measured boot of this build.
 * Without a measurement the clock uses [DEFAULT_MS]. Until the first frame it stays under [CAP].
 */
class BootProgress(private val expectedMs: Long) {
    enum class Phase { START, MODS, RESOURCES, WINDOW }

    private var floor = 0f
    private var shown = 0f
    private var phase = Phase.START

    var modCount = 0
        private set

    /** Feeds new log text; any amount of lines. */
    fun feed(text: String) {
        for (line in text.lineSequence()) {
            val mods = MODS.find(line)
            when {
                mods != null -> {
                    modCount = mods.groupValues[1].toIntOrNull() ?: modCount
                    raise(0.12f, Phase.MODS)
                }
                "Reloading ResourceManager" in line -> raise(0.72f, Phase.RESOURCES)
                ATLAS.containsMatchIn(line) -> raise(0.86f, Phase.RESOURCES)
                "Sound engine started" in line -> raise(0.93f, Phase.WINDOW)
            }
        }
    }

    private fun raise(to: Float, next: Phase) {
        if (to > floor) floor = to
        if (next.ordinal > phase.ordinal) phase = next
    }

    /** Percent 0..95 for the time since the game process began loading. */
    fun percent(elapsedMs: Long): Int {
        val expected = if (expectedMs >= MIN_MS) expectedMs else DEFAULT_MS
        val byClock = elapsedMs.coerceAtLeast(0).toFloat() / expected * CAP
        // A clock past the measured time creeps instead of freezing: the game is still loading.
        val clock = if (byClock > CAP) CAP else byClock
        shown = maxOf(shown, floor, clock).coerceAtMost(CAP)
        return (shown * 100f).toInt()
    }

    val stage: Phase get() = phase

    companion object {
        const val CAP = 0.95f
        const val DEFAULT_MS = 110_000L
        const val MIN_MS = 10_000L
        private val MODS = Regex("Loading (\\d+) mods")
        private val ATLAS = Regex("Created: \\d+x\\d+x\\d+ .*atlas")
    }
}
