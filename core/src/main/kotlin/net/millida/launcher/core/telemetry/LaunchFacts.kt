package net.millida.launcher.core.telemetry

import kotlinx.serialization.json.JsonPrimitive
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.http.HttpError
import net.millida.launcher.core.i18n.trSource
import net.millida.launcher.core.packs.PhoneUnsupported
import net.millida.launcher.core.runtime.RendererFallback
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
    /** GL_RENDERER of the phone, short: the model of the GPU, no personal data. */
    val gpu: String? = null,
    /** Build.MODEL, short. */
    val device: String? = null,
    /** True once the game drew a frame or showed its window: a crash without it is a black screen. */
    val frame: Boolean? = null,
    /** Milliseconds from the launch start to that first frame. */
    val frameMs: Long? = null,
    /** Median and slow-tail (10th percentile) of the in-game frames per second; null before enough samples. */
    val fpsP50: Int? = null,
    val fpsP10: Int? = null,
    val fpsN: Int? = null,
    /** The share of the screen's pixels the game drew, 30..100. */
    val resolutionPercent: Int? = null,
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
        .put("gpu", gpu)
        .put("device", device)
        .put("frame", frame)
        .put("frameMs", frameMs)
        .build()

    /** What the open-game record keeps: the launch facts and the frame statistics. */
    fun stored(): Map<String, JsonPrimitive> = EventData()
        .putAll(data())
        .put("fpsP50", fpsP50)
        .put("fpsP10", fpsP10)
        .put("fpsN", fpsN)
        .put("resolutionPercent", resolutionPercent)
        .build()

    /**
     * `game_exit` data: only what the server needs for a pack's card (it keeps 16 keys per event):
     * time to the first frame, frames per second and what the game ran on.
     */
    fun exitData(): Map<String, JsonPrimitive> = EventData()
        .put("pack", pack)
        .put("modpack", modpack)
        .put("mc", mc)
        .put("loader", loader)
        .put("renderer", renderer)
        .put("gpu", gpu)
        .put("device", device)
        .put("resolutionPercent", resolutionPercent)
        .put("bootMs", frameMs?.takeIf { frame == true && it > 0 })
        .put("fpsP50", fpsP50)
        .put("fpsP10", fpsP10)
        .put("fpsN", fpsN)
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
    private const val SOURCE_LIMIT = 600

    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("""Свободно всего \d+""", RegexOption.IGNORE_CASE) to "refused_memory",
        Regex("""не запустит \S+:|нет подходящего рендерера""", RegexOption.IGNORE_CASE) to "refused_renderer",
        Regex("""Millida Launcher на компьютере|на телефоне такие сборки|телефон его не поддерживает|Нужен Android \d+""", RegexOption.IGNORE_CASE) to "refused_device",
        // Sodium and other mods refuse to run on Pojav on purpose: not a crash of ours, not an auth error.
        Regex("""using PojavLauncher|PojavLauncher[^\n]*(?:not supported|unsupported|incompatible)""", RegexOption.IGNORE_CASE) to "pojav_check",
        // Before auth/graphics/network: fabric-loader failures list authlib frames and "timed out" lines
        // that used to pull them into `auth` (telemetry 10.2026).
        Regex(
            """ResolutionException|Incompatible mods? (?:found|set)|Duplicate mod|Found duplicate mod|duplicate mods? (?:found|detected)|Mod resolution failed|Some of your mods are incompatible|MixinApplyError|InvalidMixinException|MixinTransformerError|Mixin apply.* failed|mod .* requires""",
            RegexOption.IGNORE_CASE,
        ) to "mod_conflict",
        Regex("""OutOfMemoryError|Could not reserve enough space|не хватает (?:свободной )?памяти|lowmemorykiller""", RegexOption.IGNORE_CASE) to "out_of_memory",
        Regex("""No space left|ENOSPC|освободи место""", RegexOption.IGNORE_CASE) to "disk_full",
        Regex("""UnsatisfiedLinkError|dlopen failed|cannot locate symbol|SIGSEGV""", RegexOption.IGNORE_CASE) to "native_crash",
        Regex(
            """EGL_BAD|EGL_NOT_INITIALIZED|GL error|GL_INVALID_|GL_OUT_OF_MEMORY|GLException|failed to create (?:the |an? )?(?:\S+ ){0,2}context|could not create (?:the |an? )?(?:\S+ ){0,2}context|glfw error|Vulkan error|VK_ERROR_|(?:zink|gl4es)[^\n]*\berror\b|\berror\b[^\n]*(?:zink|gl4es)""",
            RegexOption.IGNORE_CASE,
        ) to "graphics",
        Regex("""UnknownHost|Unable to resolve host|timed? ?out|SocketException|ConnectException|Connection reset|Failed to connect|Software caused connection abort|Нет сети|проверь интернет""", RegexOption.IGNORE_CASE) to "network",
        // Real sign-in failures only: a bare "authlib"/"yggdrasil" is in every start log as a class name.
        Regex("""Failed to fetch metadata[^\n]*yggdrasil|Invalid session|InvalidCredentialsException|AuthenticationException|yggdrasil[^\n]*(?:exception|error|failed)|\b401\b|Unauthorized|сессия|Вход Millida истёк""", RegexOption.IGNORE_CASE) to "auth",
        Regex("""рантайм|Unsupported class file major version|UnsupportedClassVersionError""", RegexOption.IGNORE_CASE) to "runtime",
    )

    fun of(text: String?): String {
        val hay = text.orEmpty()
        if (hay.isBlank()) return "unknown"
        // The rules read the Russian wording; a translated refusal is read back into it, a long log is matched as is.
        val probe = if (hay.length <= SOURCE_LIMIT) trSource(hay) else hay
        return RULES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(probe) || pattern.containsMatchIn(hay) }?.second ?: "other"
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
        val lines = withoutNoise(log)
        val line = fromCrashReport(lines) ?: nativeFatal(lines) ?: modResolution(lines) ?: latestTrace(lines)
            // A bare stack frame mentions "Exception" in its class name and is no cause (967 aborts, 10.2026).
            ?: lines.lastOrNull { CAUSE_LINE.containsMatchIn(it) && !it.startsWith(FRAME) }
            ?: return null
        return TelemetryPrivacy.scrub(line).take(CAUSE_MAX)
    }

    /**
     * What the class of a failure is read from: the cause and the last lines of the log, not
     * all of it. Any log mentions "Java 21" and "libc.so", so reading it whole made `runtime`
     * and `native_crash` out of 3 300 aborts (telemetry 19-26.10.2026).
     */
    fun kindSource(log: String?, cause: String?): String =
        listOfNotNull(cause, withoutNoise(log).takeLast(KIND_TAIL_LINES).joinToString("\n").ifBlank { null }).joinToString("\n")

    /**
     * The log without lines the game survives: a noise line takes its own stack with it, or
     * the frames left behind would be picked as a cause.
     */
    private fun withoutNoise(log: String?): List<String> {
        var inNoise = false
        return log.orEmpty().lineSequence().map { it.trim() }.filter { line ->
            when {
                noise(line) -> { inNoise = true; false }
                inNoise && (line.startsWith(FRAME) || line.startsWith(CAUSED_BY) || line.startsWith(SUPPRESSED) || line.startsWith(ELIDED)) -> false
                else -> { inNoise = false; true }
            }
        }.toList()
    }

    /** The JVM's own report of a crash in native code names the function; the abort in the log only says "signal 6". */
    private fun nativeFatal(lines: List<String>): String? =
        // LWJGL's own abort comes with a stack that names the GL call: latestTrace reads that one.
        lines.indices.lastOrNull { lines[it].contains(NATIVE_FATAL) && lines.getOrNull(it + 1)?.startsWith(FRAME) != true }
            ?.let { lines[it].removePrefix("#").trim() }

    /**
     * Fabric Loader names the mod it cannot load in a list under its exception; the
     * stack below that list only says where the list was printed.
     */
    private fun modResolution(lines: List<String>): String? {
        val head = lines.indexOfLast { it.startsWith(FABRIC_RESOLUTION) }
        if (head < 0) return null
        val details = lines.drop(head + 1).takeWhile { !it.startsWith(FRAME) }
        val named = details.dropWhile { it != FABRIC_DETAILS }.firstOrNull { it.startsWith(LIST_ITEM) }
            ?: details.firstOrNull { it.startsWith(LIST_ITEM) }
            ?: return lines[head]
        return "$FABRIC_INCOMPATIBLE — ${named.removePrefix(LIST_ITEM).trim()}"
    }

    private fun noise(line: String): Boolean =
        line.startsWith(LAUNCHER_MARK) || NOISE.any { line.contains(it, ignoreCase = true) }

    private fun fromCrashReport(lines: List<String>): String? {
        val report = lines.indexOfFirst { it.startsWith(CRASH_REPORT) }
        if (report < 0) return null
        val description = (report + 1 until lines.size).firstOrNull { lines[it].startsWith(DESCRIPTION) } ?: return null
        val head = (description + 1 until lines.size).firstOrNull { isHead(lines, it) }
        val root = head?.let { rootOf(lines, it) }
            // A bare "Unexpected error" names nothing: the cause is the trace or the mod list above it.
            ?: return lines[description].takeUnless { GENERIC_DESCRIPTION.any { mark -> mark in it } }
        return "${lines[description]} — $root"
    }

    private fun latestTrace(lines: List<String>): String? {
        val head = lines.indices.lastOrNull { isHead(lines, it) } ?: return null
        val root = rootOf(lines, head)
        // LWJGL's abort names no function: the frame under it is the GL call that had no entry point.
        val call = lines[head + 1].takeIf { RendererFallback.MISSING_GL_FUNCTION in lines[head] }
        return if (call == null) root else "$root — $call"
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
    // Lines the game survives: they are in the log of sessions that played for minutes (frame 85-100%,
    // median 2-7 min) and of clean exits, but were picked as the cause of 3 600+ aborts (telemetry 26.10.2026).
    private val NOISE = listOf(
        "glGetError() always return GL_NOERROR",
        "GL_TIMESTAMP", // frame generation of our mod, caught by the mod itself
        "SignedJWT", "RealmsServiceException", "Realms authentication error", "Could not authorize you against Realms",
        "Could not retrieve profile key pair", "MinecraftClientHttpException",
        "Unable to load library 'flite'", "Broken pipe", "ExternalMouseReader",
        "[authlib-injector] [ERROR] Failed to fetch metadata", "[authlib-injector] [WARN]",
        "[MobileGlues] Setting:", "Error loading class",
        "CancellationException", "No associated exception found", "ZipFile closed",
        "ExceptionInInitializerError: Exception java.lang.Error:",
    )
    private val GENERIC_DESCRIPTION = listOf("Unexpected error", "Mod loading failures have occurred")
    private const val NATIVE_FATAL = "FATAL ERROR in native method"
    private const val KIND_TAIL_LINES = 30
    private const val LAUNCHER_MARK = "[millida]"
    private const val CRASH_REPORT = "---- Minecraft Crash Report ----"
    private const val DESCRIPTION = "Description:"
    private const val FRAME = "at "
    private const val CAUSED_BY = "Caused by:"
    private const val SUPPRESSED = "Suppressed:"
    private const val FABRIC_RESOLUTION = "net.fabricmc.loader.impl.FormattedException:"
    private const val FABRIC_DETAILS = "More details:"
    private const val FABRIC_INCOMPATIBLE = "Incompatible mods"
    private const val LIST_ITEM = "- "
    private const val ELIDED = "..."
    private const val CAUSE_MAX = 300
    private const val MAX_CAUSES = 8
}
