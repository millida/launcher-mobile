package net.millida.launcher.core.support

private const val REDACTED = "<redacted>"
private const val MASK_MIN = 4

/** A report leaves the phone without tokens, signed links or a home folder that carries the user's name. */
object ReportRedaction {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""\beyJ[\w-]{8,}\.[\w-]{8,}\.[\w-]{8,}""") to REDACTED,
        Regex("""\b[\w-]{20,}\.[\w-]{20,}\.[\w-]{20,}\b""") to REDACTED,
        Regex("""\b(Bearer)\s+[\w.~+/=-]+""", RegexOption.IGNORE_CASE) to "\$1 $REDACTED",
        Regex(
            """(--(?:accessToken|clientToken|session|username|uuid|xuid)[\s,=]+)[^\s,\]]+""",
            RegexOption.IGNORE_CASE,
        ) to "\$1$REDACTED",
        Regex(
            """\b([\w-]*?(?:token|passw(?:or)?d|secret|session(?:[_-]?id)?|api[_-]?key|signature)|x-amz-[\w-]+|sig|key-pair-id)(["']?\s*[:=]\s*["']?)[^\s"'&,;}]+""",
            RegexOption.IGNORE_CASE,
        ) to "\$1\$2$REDACTED",
        Regex("""(Setting user:\s*)\S+""", RegexOption.IGNORE_CASE) to "\$1<nick>",
        Regex(
            """[A-Za-z]:[\\/]+(?:Users|Documents and Settings)[\\/]+[^\\/\r\n"'<>|:*?]+(?=[\\/])""",
            RegexOption.IGNORE_CASE,
        ) to "~",
        Regex(
            """[A-Za-z]:[\\/]+(?:Users|Documents and Settings)[\\/]+[^\\/\s"'<>|:*?,;)\]]+""",
            RegexOption.IGNORE_CASE,
        ) to "~",
        Regex("""/(?:home|Users)/[^/\s"'<>]+""") to "~",
    )

    fun redact(text: String): String = rules.fold(text) { current, (pattern, replacement) ->
        pattern.replace(current, replacement)
    }

    /** Values shorter than four characters are left alone: masking "Max" would eat every "Maximum". */
    fun mask(text: String, pairs: List<Pair<String?, String>>): String = pairs
        .mapNotNull { (value, placeholder) -> value?.trim()?.takeIf { it.length >= MASK_MIN }?.let { it to placeholder } }
        .sortedByDescending { it.first.length }
        .fold(text) { current, (value, placeholder) -> current.replace(value, placeholder) }

    fun clean(text: String, pairs: List<Pair<String?, String>> = emptyList()): String = mask(redact(text), pairs)
}

data class CrashRecord(
    val version: String,
    val device: String,
    val moment: String,
    val thread: String,
    val name: String,
    val message: String,
    val stack: String,
)

object CrashFile {
    private const val PRODUCT = "Millida "
    private const val THREAD_MARK = " · поток "
    private const val UNNAMED = "AndroidCrash"
    private const val SILENT = "Лаунчер упал без описания"

    fun format(version: String, device: String, moment: String, thread: String, stack: String): String = buildString {
        append(PRODUCT).append(version).append('\n')
        append(device).append('\n')
        append(moment).append(THREAD_MARK).append(thread).append('\n').append('\n')
        append(stack)
    }

    fun parse(text: String): CrashRecord {
        val normalized = text.replace("\r\n", "\n")
        val header = normalized.substringBefore("\n\n", missingDelimiterValue = "")
        val lines = header.lines()
        val known = lines.size >= 3 && lines[0].startsWith(PRODUCT)
        val stack = if (known) normalized.substringAfter("\n\n") else normalized
        val stamp = if (known) lines[2] else ""
        val first = stack.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val candidate = first.substringBefore(':').trim()
        return CrashRecord(
            version = if (known) lines[0].removePrefix(PRODUCT).trim() else "",
            device = if (known) lines[1].trim() else "",
            moment = stamp.substringBefore(THREAD_MARK).trim(),
            thread = if (THREAD_MARK in stamp) stamp.substringAfter(THREAD_MARK).trim() else "",
            name = candidate.takeIf { it.isNotEmpty() && candidate.none { char -> char.isWhitespace() } } ?: UNNAMED,
            message = first.ifEmpty { SILENT },
            stack = stack.trim(),
        )
    }
}

data class ErrorReport(
    val level: String,
    val name: String,
    val message: String,
    val stack: String,
    val release: String,
    val context: Map<String, String>,
) {
    companion object {
        const val NAME_MAX = 200
        const val MESSAGE_MAX = 500
        const val STACK_MAX = 12_000

        fun fromCrash(record: CrashRecord, release: String, masks: List<Pair<String?, String>> = emptyList()): ErrorReport {
            val facts = linkedMapOf(
                "platform" to "android",
                "device" to record.device,
                "thread" to record.thread,
                "crashedAt" to record.moment,
                "crashedVersion" to record.version,
            )
            return ErrorReport(
                level = "FATAL",
                name = record.name.take(NAME_MAX),
                message = ReportRedaction.clean(record.message, masks).take(MESSAGE_MAX),
                stack = ReportRedaction.clean(record.stack, masks).take(STACK_MAX),
                release = release,
                context = facts.filterValues { it.isNotBlank() }.mapValues { ReportRedaction.clean(it.value, masks) },
            )
        }
    }
}

enum class BugKind(val category: String, val label: String, val severity: String) {
    CRASH("crash", "Вылет", "high"),
    GAME_LAUNCH("game_launch", "Игра не запускается", "high"),
    DOWNLOAD("download", "Не качается", "medium"),
    AUTH("auth", "Не пускает в аккаунт", "high"),
    VISUAL("visual", "Что-то съехало", "medium"),
    OTHER("other", "Другое", "medium"),
}

data class BugReport(
    val category: String,
    val severity: String,
    val title: String,
    val description: String,
) {
    companion object {
        const val TITLE_MAX = 200
        const val DESCRIPTION_MAX = 4_000
        const val TEXT_MIN = 10
        private const val TITLE_TEXT = 60
        private const val LOG_HEADER = "\n\nЖурнал:\n"
        private const val LOG_MIN = 200
        private const val REVIEW_TITLE = "Отзыв о лаунчере Android"
        private val SPACES = Regex("\\s+")

        fun bug(kind: BugKind, text: String, facts: String, log: String): BugReport = BugReport(
            category = kind.category,
            severity = kind.severity,
            title = (kind.label + ": " + text.trim().replace(SPACES, " ").take(TITLE_TEXT)).take(TITLE_MAX),
            description = describe(text, facts, log),
        )

        fun review(text: String, stars: Int, facts: String): BugReport {
            val rated = stars in 1..5
            return BugReport(
                category = BugKind.OTHER.category,
                severity = "low",
                title = if (rated) "$REVIEW_TITLE · $stars/5" else REVIEW_TITLE,
                description = describe((if (rated) "Оценка: $stars из 5. " else "") + text.trim(), facts, ""),
            )
        }

        /**
         * The player's words and the device line always fit; the log gives way,
         * and keeps its end, because the end is where a failure is written.
         */
        fun describe(text: String, facts: String, log: String): String {
            val head = buildString {
                append(text.trim())
                if (facts.isNotBlank()) append("\n\n").append(facts.trim())
            }.take(DESCRIPTION_MAX)
            val body = log.trim()
            val room = DESCRIPTION_MAX - head.length - LOG_HEADER.length
            if (body.isEmpty() || room < LOG_MIN) return head
            val tail = if (body.length <= room) body else body.takeLast(room).substringAfter('\n')
            return head + LOG_HEADER + tail
        }
    }
}

data class FeedbackAnswer(
    val granted: Boolean,
    val shards: Int,
    val nextAt: String,
)
