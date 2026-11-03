package net.millida.launcher.core.hud

/**
 * Steps the editor can take back. A drag or a slider sends a change per frame;
 * changes under the same [key] that follow each other are one step, so a
 * single undo returns the control to where the finger picked it up.
 */
class HudHistory private constructor(
    private val past: List<HudProfiles>,
    private val lastKey: String?,
) {
    constructor() : this(emptyList(), null)

    val canUndo: Boolean
        get() = past.isNotEmpty()

    fun record(before: HudProfiles, key: String?): HudHistory {
        if (key != null && key == lastKey && past.isNotEmpty()) return this
        return HudHistory((past + before).takeLast(MAX_STEPS), key)
    }

    /** The state to go back to and the history left after it, or null when there is nothing to undo. */
    fun undo(): Pair<HudProfiles, HudHistory>? {
        val previous = past.lastOrNull() ?: return null
        return previous to HudHistory(past.dropLast(1), null)
    }

    /** A finished gesture closes its step: the next drag of the same control is a step of its own. */
    fun seal(): HudHistory = if (lastKey == null) this else HudHistory(past, null)

    companion object {
        const val MAX_STEPS = 50
    }
}
