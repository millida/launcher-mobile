package net.millida.launcher.core.runtime

/**
 * How a game that ended inside the launcher's process is written down.
 *
 * The Java machine leaves through the C library's exit, and inside an application that
 * runs the destructors of everything loaded, the phone's graphics driver among them,
 * under a screen that is still drawing: the screen hangs until the system offers to
 * close the app. An abort is worse: the window layer catches it and waits for a
 * launcher to take the code, and with nobody answering the game stays a frozen picture
 * with its sound still playing. So both are intercepted and reported with their code.
 */
data class GameEnd(val ok: Boolean, val code: String) {
    companion object {
        /**
         * [crashReported] covers the interceptor's fallback, which knows that the game
         * left but not with which code and says 0 for every exit.
         */
        fun of(code: Int, signal: Boolean, crashReported: Boolean): GameEnd = GameEnd(
            ok = !signal && code == 0 && !crashReported,
            code = if (signal) "signal_$code" else "exit_$code",
        )
    }
}

object ExitHook {
    /** A renderer whose manifest entry sets this to 0 starts without the interceptor: a switch that needs no release. */
    const val SWITCH = "MILLIDA_EXIT_HOOK"
    const val HOOKS = "libbytehook.so"

    /** The Java class libbytehook.so looks up in its JNI_OnLoad; it comes from the bytehook dependency. */
    const val HOOKS_CLASS = "com.bytedance.android.bytehook.ByteHook"
    const val INTERCEPTOR = "libexithook.so"

    fun enabled(rendererEnvironment: Map<String, String>): Boolean = rendererEnvironment[SWITCH] != "0"

    /**
     * The interceptor patches every loaded library, and nobody tried it on each phone the
     * launcher runs on. Every start that installs it counts itself in a mark, and the mark
     * is cleared only by a game that proved it lived on: it drew, or its Java machine left
     * with an exit code of its own. Two starts in a row that proved nothing, and this version
     * of the launcher goes without the interceptor on this phone. Starts that worked before
     * must keep working, even if they end the old way. A player closing the game himself
     * proves nothing, and neither does an abort: a start the interceptor hung or broke ends
     * exactly like that.
     */
    const val SUSPECT_LIMIT = 2

    fun mark(version: String, unconfirmed: Int): String = "$version$SEPARATOR$unconfirmed"

    /** How many starts of [version] in a row installed the interceptor and proved nothing; a mark of another version counts as none. */
    fun unconfirmed(mark: String, version: String): Int {
        val at = mark.lastIndexOf(SEPARATOR)
        if (at <= 0 || mark.substring(0, at) != version) return 0
        return mark.substring(at + 1).toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    fun refused(mark: String, version: String): Boolean = unconfirmed(mark, version) >= SUSPECT_LIMIT

    private const val SEPARATOR = ':'
}
