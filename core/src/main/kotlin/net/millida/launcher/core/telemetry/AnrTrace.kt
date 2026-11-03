package net.millida.launcher.core.telemetry

/**
 * Where the main thread stood when Android declared the app not responding.
 *
 * Android keeps a thread dump for an ANR (ApplicationExitInfo.getTraceInputStream).
 * Its reason line only says «Input dispatching timed out»; the top of the "main"
 * thread says what held it: 13.10.2026 the dialog «Millida не отвечает» came up
 * on 340 installs a day and nothing told which call was to blame.
 */
object AnrTrace {
    const val MAX = 400
    private const val MAX_FRAMES = 8
    private val JAVA = Regex("""^\s*at\s+([\w.$<>]+)\(([^)]*)\)""")
    private val NATIVE = Regex("""^\s*native:\s+#\d+\s+pc\s+\S+\s+(\S+)(?:\s+\(([^)]*)\))?""")

    /** `State: frame < frame < …` for the "main" thread, or null when the dump has none. */
    fun mainStack(dump: String?): String? {
        val lines = dump.orEmpty().lineSequence().toList()
        val head = lines.indexOfFirst { it.startsWith("\"main\" ") }
        if (head < 0) return null
        val state = lines[head].trim().substringAfterLast(' ')
        val frames = ArrayList<String>()
        for (line in lines.drop(head + 1)) {
            if (line.isBlank() || line.startsWith("\"")) break
            val frame = JAVA.find(line)?.let { java(it.groupValues[1], it.groupValues[2]) }
                ?: NATIVE.find(line)?.let { native(it.groupValues[1], it.groupValues[2]) }
                ?: continue
            if (frames.lastOrNull() != frame) frames.add(frame)
            if (frames.size >= MAX_FRAMES) break
        }
        if (frames.isEmpty()) return state.take(MAX)
        return (state + ": " + frames.joinToString(" < ")).take(MAX)
    }

    /**
     * The same line for a thread seen from inside the process.
     *
     * The dump above never came for the game process (1.9.23, 13.10.2026: three ANR
     * exits, none with a trace): the desktop virtual machine takes SIGQUIT for its own
     * thread dump, so Android's request for one is answered by the wrong machine. The
     * game process therefore watches its screen thread itself and reads its stack.
     */
    fun ofThread(trace: Array<StackTraceElement>?): String? {
        val frames = ArrayList<String>()
        for (element in trace.orEmpty()) {
            val frame = frame(element)
            if (frames.lastOrNull() != frame) frames.add(frame)
            if (frames.size >= THREAD_FRAMES) break
        }
        return frames.takeIf { it.isNotEmpty() }?.joinToString(" < ")?.take(THREAD_MAX)
    }

    /** The topmost call that is ours or the game layer's, not the platform's: what the reports are grouped by. */
    fun culprit(trace: Array<StackTraceElement>?): String? {
        val all = trace.orEmpty()
        val own = all.firstOrNull { element -> PLATFORM.none { element.className.startsWith(it) } }
        return (own ?: all.firstOrNull())?.let { simple(it.className) + "." + it.methodName }
    }

    private fun frame(element: StackTraceElement): String {
        val name = simple(element.className) + "." + element.methodName
        return when {
            element.isNativeMethod -> "$name(native)"
            element.lineNumber > 0 -> "$name:${element.lineNumber}"
            else -> name
        }
    }

    private fun simple(className: String): String = className.substringAfterLast('.')

    const val THREAD_MAX = 300
    private const val THREAD_FRAMES = 12
    private val PLATFORM = listOf("android.", "androidx.", "com.android.", "java.", "javax.", "kotlin.", "kotlinx.", "dalvik.", "libcore.", "sun.")

    private fun java(method: String, where: String): String {
        val line = where.substringAfterLast(':', "").takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
        return if (line != null) "$method:$line" else method
    }

    private fun native(library: String, symbol: String): String {
        val lib = library.substringAfterLast('/')
        val name = symbol.substringBefore('+').trim()
        return if (name.isEmpty()) lib else "$lib!$name"
    }
}
