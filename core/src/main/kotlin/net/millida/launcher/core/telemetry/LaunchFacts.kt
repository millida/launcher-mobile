package net.millida.launcher.core.telemetry

import kotlinx.serialization.json.JsonPrimitive
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.http.HttpError
import net.millida.launcher.core.packs.PhoneUnsupported
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What a game start on a phone ran with. The desktop sends build, version,
 * loader and pack; the phone adds what decides whether the game starts at all
 * there: renderer, window layer, Java and the memory it was given.
 */
data class LaunchFacts(
    val build: String,
    val mc: String,
    val loader: String,
    val pack: String? = null,
    val modpack: String? = null,
    val renderer: String? = null,
    val layer: String? = null,
    val java: Int? = null,
    val heapMb: Int? = null,
    val mods: Int? = null,
) {
    fun data(): Map<String, JsonPrimitive> = EventData()
        .put("build", build)
        .put("mc", mc)
        .put("loader", loader)
        .put("pack", pack)
        .put("modpack", modpack)
        .put("renderer", renderer)
        .put("layer", layer)
        .put("java", java?.takeIf { it > 0 })
        .put("heapMb", heapMb?.takeIf { it > 0 })
        .put("mods", mods)
        .build()

    companion object {
        /** Window layers by the names the dashboard groups them under. */
        const val LAYER_PACK = "glfw"
        const val LAYER_OWN = "own"
        const val LAYER_SDL = "sdl3"

        /** A pack from our catalogue is a `pack`; one from Modrinth or CurseForge is a `modpack`, as on the desktop. */
        fun origin(source: String?, id: String?): Pair<String?, String?> {
            val slug = id?.trim().orEmpty()
            if (slug.isEmpty()) return null to null
            return when (source) {
                "millida-catalog", "millida-mirror" -> slug to null
                "modrinth", "curseforge" -> null to slug
                else -> null to null
            }
        }
    }
}

/**
 * The desktop's error classes (src/lib/errorKind.ts) for what can go wrong on a
 * phone, plus the launcher's own refusals: a start the launcher declined on
 * purpose is not a failure of the game and must not drown in `other`.
 */
object FailureKind {
    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("""Свободно всего \d+""", RegexOption.IGNORE_CASE) to "refused_memory",
        Regex("""не запустит \S+:|нет подходящего рендерера""", RegexOption.IGNORE_CASE) to "refused_renderer",
        Regex("""Millida Launcher на компьютере|на телефоне такие сборки|телефон его не поддерживает""", RegexOption.IGNORE_CASE) to "refused_device",
        Regex("""OutOfMemoryError|Could not reserve enough space|не хватает (?:свободной )?памяти|lowmemorykiller""", RegexOption.IGNORE_CASE) to "out_of_memory",
        Regex("""No space left|ENOSPC|освободи место""", RegexOption.IGNORE_CASE) to "disk_full",
        Regex("""UnsatisfiedLinkError|dlopen failed|cannot locate symbol|SIGSEGV|SIGABRT|libc\.so""", RegexOption.IGNORE_CASE) to "native_crash",
        Regex(
            """EGL_BAD|EGL_NOT_INITIALIZED|GL error|GL_INVALID_|GL_OUT_OF_MEMORY|GLException|failed to create (?:the |an? )?(?:\S+ ){0,2}context|could not create (?:the |an? )?(?:\S+ ){0,2}context|glfw error|Vulkan error|VK_ERROR_|(?:zink|gl4es)[^\n]*\berror\b|\berror\b[^\n]*(?:zink|gl4es)""",
            RegexOption.IGNORE_CASE,
        ) to "graphics",
        Regex("""UnknownHost|Unable to resolve host|timed? ?out|SocketException|ConnectException|Connection reset|Failed to connect|Software caused connection abort|Нет сети|проверь интернет""", RegexOption.IGNORE_CASE) to "network",
        Regex("""Failed to fetch metadata|authlib|yggdrasil|Unauthorized|сессия|Вход Millida истёк""", RegexOption.IGNORE_CASE) to "auth",
        Regex("""рантайм|runtime|Java \d+""", RegexOption.IGNORE_CASE) to "runtime",
        // Every Fabric log names Mixin while loading; only its failures say a mod broke.
        Regex("""MixinApplyError|InvalidMixinException|MixinTransformerError|Mixin apply.* failed|ModResolutionException|Incompatible mods|mod .* requires""", RegexOption.IGNORE_CASE) to "mod_conflict",
    )

    fun of(text: String?): String {
        val hay = text.orEmpty()
        if (hay.isBlank()) return "unknown"
        return RULES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(hay) }?.second ?: "other"
    }

    /**
     * By the whole cause chain: the launcher wraps a failure in its own
     * sentence, and the class that names it (a 502, a dropped connection) sits
     * one or two causes down.
     */
    fun of(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
        chain.firstNotNullOfOrNull(::byType)?.let { return it }
        return of(chain.joinToString("\n") { (it.message ?: "") + " " + it.javaClass.name })
    }

    private fun byType(error: Throwable): String? = when {
        error is PhoneUnsupported -> "refused_device"
        error is Unauthorized -> "auth"
        error is HttpError && error.code == 401 -> "auth"
        error is HttpError && error.code in 500..599 -> "server"
        error is UnknownHostException || error is ConnectException ||
            error is NoRouteToHostException || error is SocketTimeoutException -> "network"
        else -> null
    }

    /**
     * The line that names the failure in a game log, without paths. A Minecraft
     * crash report names it in its description; otherwise the deepest cause of
     * the latest stack trace does, since earlier traces are usually warnings the
     * game survived.
     */
    fun cause(log: String?): String? {
        val lines = log.orEmpty().lineSequence().map { it.trim() }.filterNot(::noise).toList()
        val line = fromCrashReport(lines) ?: latestTrace(lines) ?: lines.lastOrNull { CAUSE_LINE.containsMatchIn(it) }
            ?: return null
        return TelemetryPrivacy.scrub(line).take(CAUSE_MAX)
    }

    private fun noise(line: String): Boolean =
        line.startsWith(LAUNCHER_MARK) || NOISE.any { line.contains(it, ignoreCase = true) }

    private fun fromCrashReport(lines: List<String>): String? {
        val report = lines.indexOfFirst { it.startsWith(CRASH_REPORT) }
        if (report < 0) return null
        val description = (report + 1 until lines.size).firstOrNull { lines[it].startsWith(DESCRIPTION) } ?: return null
        val head = (description + 1 until lines.size).firstOrNull { isHead(lines, it) }
        val root = head?.let { rootOf(lines, it) } ?: return lines[description]
        return "${lines[description]} — $root"
    }

    private fun latestTrace(lines: List<String>): String? {
        val head = lines.indices.lastOrNull { isHead(lines, it) } ?: return null
        return rootOf(lines, head)
    }

    private fun isHead(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        if (line.startsWith(FRAME) || line.startsWith(CAUSED_BY) || line.startsWith(SUPPRESSED)) return false
        if (!CAUSE_LINE.containsMatchIn(line)) return false
        return lines.getOrNull(index + 1)?.startsWith(FRAME) == true
    }

    private fun rootOf(lines: List<String>, head: Int): String {
        var root = lines[head]
        for (index in head + 1 until lines.size) {
            val line = lines[index]
            when {
                line.startsWith(CAUSED_BY) -> root = line
                line.startsWith(FRAME) || line.startsWith(ELIDED) || line.startsWith(SUPPRESSED) -> Unit
                else -> break
            }
        }
        return root
    }

    private val CAUSE_LINE = Regex("""(?:Exception|Error)(?::|\b)|FATAL|signal \d+""")
    private val NOISE = listOf("glGetError() always return GL_NOERROR")
    private const val LAUNCHER_MARK = "[millida]"
    private const val CRASH_REPORT = "---- Minecraft Crash Report ----"
    private const val DESCRIPTION = "Description:"
    private const val FRAME = "at "
    private const val CAUSED_BY = "Caused by:"
    private const val SUPPRESSED = "Suppressed:"
    private const val ELIDED = "..."
    private const val CAUSE_MAX = 300
    private const val MAX_CAUSES = 8
}
