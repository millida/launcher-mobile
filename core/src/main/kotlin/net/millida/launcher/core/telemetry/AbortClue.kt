package net.millida.launcher.core.telemetry

/**
 * What a native abort of the game process says. 5 006 aborts of 1 648 installs (19-26.10.2026) came
 * with no cause at all: SIGABRT is not a Java trace. Android keeps the tombstone of a native crash
 * (ApplicationExitInfo.getTraceInputStream): a text on Android 11, a protobuf from Android 12. Either
 * way it holds the abort message and the frames, which is what tells a lost window surface from a
 * driver assert. The protobuf is read for its strings only: no schema is needed to find a message
 * and the libraries with the functions in them.
 */
object AbortClue {
    const val MAX = 400
    private const val MAX_FRAMES = 4
    private const val MIN_RUN = 5
    private val TEXT_ABORT = Regex("""^\s*Abort message: '?(.*?)'?\s*$""")
    private val TEXT_FRAME = Regex("""^\s*#\d+\s+pc\s+\S+\s+(\S+)(?:\s+\(([^)]*)\))?""")
    private val IDENTIFIER = Regex("""^[A-Za-z_][\w$@.:<>~]*$""")
    private val FINGERPRINT = Regex("""release-keys|test-keys|^u:r:|^\w+/\w+/""")

    fun summary(bytes: ByteArray): String? =
        (if (isText(bytes)) fromText(String(bytes, Charsets.UTF_8)) else fromProto(bytes))?.take(MAX)

    private fun isText(bytes: ByteArray): Boolean =
        bytes.take(PROBE).toByteArray().let { String(it, Charsets.ISO_8859_1) }.let { "*** ***" in it || "Build fingerprint" in it }

    private fun fromText(text: String): String? {
        val lines = text.lineSequence().toList()
        val message = lines.firstNotNullOfOrNull { TEXT_ABORT.find(it)?.groupValues?.get(1)?.trim() }?.takeIf { it.isNotEmpty() }
        val head = lines.indexOfFirst { it.trim() == "backtrace:" }
        val frames = if (head < 0) emptyList() else lines.drop(head + 1)
            .takeWhile { it.isBlank().not() }
            .mapNotNull { line -> TEXT_FRAME.find(line)?.let { frame(it.groupValues[1], it.groupValues[2].substringBefore('+')) } }
            .distinct().take(MAX_FRAMES)
        return join(message, frames)
    }

    private fun fromProto(bytes: ByteArray): String? {
        val runs = printableRuns(bytes)
        val message = runs.firstOrNull { it.count { c -> c == ' ' } >= 2 && it.length >= MESSAGE_MIN && FINGERPRINT.containsMatchIn(it).not() && it.count { c -> c == '/' } < 2 }
        val frames = ArrayList<String>()
        runs.forEachIndexed { index, run ->
            if (!run.startsWith("/") || !run.endsWith(".so")) return@forEachIndexed
            val function = runs.getOrNull(index - 1)?.takeIf { IDENTIFIER.matches(it) }.orEmpty()
            val frame = frame(run, function)
            if (frame !in frames && frames.size < MAX_FRAMES) frames.add(frame)
        }
        return join(message, frames)
    }

    private fun frame(path: String, function: String): String {
        val library = path.substringAfterLast('/')
        return if (function.isEmpty()) library else "$library!$function"
    }

    private fun join(message: String?, frames: List<String>): String? {
        val trace = frames.joinToString(" < ")
        return listOfNotNull(message, trace.ifEmpty { null }).joinToString(" | ").ifEmpty { null }
    }

    /**
     * The strings of a protobuf, in order, nested messages included. A field is a string when all its
     * bytes are printable; otherwise it is tried as a message. Cutting printable runs out of the raw
     * bytes would glue the length byte to the string when it is itself printable (32 and more).
     */
    private fun printableRuns(bytes: ByteArray): List<String> = ArrayList<String>().also { collect(bytes, 0, bytes.size, 0, it) }

    private fun collect(bytes: ByteArray, from: Int, to: Int, depth: Int, out: MutableList<String>): Boolean {
        val found = ArrayList<String>()
        var at = from
        while (at < to) {
            val (tag, afterTag) = varint(bytes, at, to) ?: return false
            at = afterTag
            if (tag ushr 3 == 0L) return false
            when ((tag and 7).toInt()) {
                0 -> at = (varint(bytes, at, to) ?: return false).second
                1 -> at += 8
                5 -> at += 4
                2 -> {
                    val (length, start) = varint(bytes, at, to) ?: return false
                    if (length < 0 || length > to - start) return false
                    val end = start + length.toInt()
                    if (length >= MIN_RUN && (start until end).all { bytes[it].toInt() in 0x20..0x7E }) {
                        found.add(String(bytes, start, end - start, Charsets.ISO_8859_1))
                    } else if (depth < MAX_DEPTH) {
                        collect(bytes, start, end, depth + 1, found)
                    }
                    at = end
                }
                else -> return false
            }
        }
        if (at != to) return false
        out.addAll(found)
        return true
    }

    private fun varint(bytes: ByteArray, from: Int, to: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var at = from
        while (at < to && shift < VARINT_BITS) {
            val byte = bytes[at++].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return value to at
            shift += 7
        }
        return null
    }

    private const val MAX_DEPTH = 12
    private const val VARINT_BITS = 64
    private const val PROBE = 2048
    private const val MESSAGE_MIN = 12
}
