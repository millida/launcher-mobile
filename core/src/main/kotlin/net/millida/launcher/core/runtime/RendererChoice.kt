package net.millida.launcher.core.runtime

import java.io.File

enum class GpuFamily {
    ADRENO,
    MALI,
    POWERVR,
    XCLIPSE,
    OTHER,
    UNKNOWN,
    ;

    companion object {
        fun of(vendor: String?, renderer: String?): GpuFamily {
            val name = renderer.orEmpty().lowercase()
            val maker = vendor.orEmpty().trim().lowercase()
            return when {
                name.isBlank() && maker.isBlank() -> UNKNOWN
                "adreno" in name || "qualcomm" in maker -> ADRENO
                "mali" in name || maker == "arm" -> MALI
                "powervr" in name || "imagination" in maker -> POWERVR
                "xclipse" in name -> XCLIPSE
                else -> OTHER
            }
        }
    }
}

data class DeviceGraphics(val gpu: GpuFamily, val vulkan11: Boolean) {
    companion object {
        val UNKNOWN = DeviceGraphics(GpuFamily.UNKNOWN, vulkan11 = false)
    }
}

enum class RendererKind {
    KRYPTON,
    GL4ES,
    ZINK,
    MOBILEGLUES,
    OTHER,
    ;

    companion object {
        fun of(entry: RuntimeManifest.RendererEntry): RendererKind {
            val library = entry.library
            return when {
                library.contains("mobileglues") -> MOBILEGLUES
                library.contains("ng_gl4es") -> KRYPTON
                library.contains("gl4es") -> GL4ES
                library.contains("glxshim") -> ZINK
                else -> OTHER
            }
        }
    }
}

/**
 * 1.21.5 is the first release on the rewritten Blaze3D pipeline (25w09a+), and
 * 25w37a raised the floor to an OpenGL 3.3 core profile. The GL 2.1 translation
 * layer cannot serve either.
 */
object GamePipeline {
    private const val LAST_CLASSIC = "1.21.4"
    private const val LAST_CLASSIC_RELEASED = "2024-12-03T10:12:57"
    private const val LAST_BEFORE_MOBILEGLUES = "1.16.5"
    private const val LAST_BEFORE_MOBILEGLUES_RELEASED = "2021-01-14T16:05:32"
    private val NUMERIC_ID = Regex("^\\d+(\\.\\d+)*$")

    fun isBlaze3d(gameVersion: String, releaseTime: String): Boolean =
        after(gameVersion, releaseTime, LAST_CLASSIC, LAST_CLASSIC_RELEASED)

    /** MobileGlues serves the core-profile pipeline of 1.17 and later, not the fixed-function GL of older versions. */
    fun runsOnMobileGlues(gameVersion: String, releaseTime: String): Boolean =
        after(gameVersion, releaseTime, LAST_BEFORE_MOBILEGLUES, LAST_BEFORE_MOBILEGLUES_RELEASED)

    /** Releases numbered by year (26.1 and later) and their snapshots (26w14a). */
    fun isYearRelease(gameVersion: String): Boolean {
        val major = Regex("^(\\d+)[.w-]").find(gameVersion.trim())?.groupValues?.get(1)?.toIntOrNull()
        return major != null && major >= FIRST_YEAR_MAJOR
    }

    private const val FIRST_YEAR_MAJOR = 26

    private fun after(gameVersion: String, releaseTime: String, last: String, lastReleased: String): Boolean {
        val parsed = numbers(gameVersion)
        if (parsed != null) return compare(parsed, numbers(last).orEmpty()) > 0
        return releaseTime.isNotEmpty() && releaseTime > lastReleased
    }

    private fun numbers(id: String): List<Int>? {
        val trimmed = id.trim()
        if (!NUMERIC_ID.matches(trimmed)) return null
        return trimmed.split('.').map { it.toIntOrNull() ?: return null }
    }

    private fun compare(left: List<Int>, right: List<Int>): Int {
        for (index in 0 until maxOf(left.size, right.size)) {
            val compared = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (compared != 0) return compared
        }
        return 0
    }
}

/**
 * The native part of the runtime pack carries the Mesa Zink stack even when its
 * description does not list it, so the launcher offers it whenever every file
 * it needs is present.
 */
object RendererCatalog {
    const val ZINK_ID = "zink"
    private const val ZINK_LIBRARY = "libglxshim.so"
    private const val ZINK_EGL = "libEGL_mesa.so"
    private const val ZINK_DRIVER = "libzink_dri.so"

    val zink = RendererOption(
        id = ZINK_ID,
        label = "Zink (Vulkan)",
        library = ZINK_LIBRARY,
        eglLibrary = ZINK_EGL,
        nativeId = "opengles3_desktopgl_zink_kopper",
        environment = mapOf("LIBGL_ES" to "3"),
    )

    fun withDiscovered(declared: List<RendererOption>, rendererDir: File?): List<RendererOption> {
        if (rendererDir == null || declared.isEmpty()) return declared
        if (declared.any { it.library.contains("glxshim") } || !zinkPresent(rendererDir)) return declared
        return declared + zink
    }

    fun offered(
        declared: List<RuntimeManifest.RendererEntry>,
        rendererDir: File,
    ): List<RuntimeManifest.RendererEntry> {
        val present = declared.filter { it.library.isEmpty() || File(rendererDir, it.library).isFile }
        if (present.isEmpty() || present.any { RendererKind.of(it) == RendererKind.ZINK }) return present
        if (!zinkPresent(rendererDir)) return present
        return present + asEntry(zink, component = rendererDir.name)
    }

    fun asEntry(option: RendererOption, component: String = ""): RuntimeManifest.RendererEntry =
        RuntimeManifest.RendererEntry(
            id = option.id,
            label = option.label,
            url = "",
            sha256 = "",
            library = option.library,
            eglLibrary = option.eglLibrary,
            nativeId = option.nativeId,
            environment = option.environment,
            shaders = option.shaders,
            component = component,
        )

    private fun zinkPresent(dir: File): Boolean =
        listOf(ZINK_LIBRARY, ZINK_EGL, ZINK_DRIVER).all { File(dir, it).isFile }
}

sealed interface RendererVerdict {
    /** [note] is for the player: set when the renderer from the settings was replaced. */
    data class Use(
        val renderer: RuntimeManifest.RendererEntry,
        val reason: String,
        val note: String = "",
    ) : RendererVerdict
    data class Refused(val message: String) : RendererVerdict
}

/**
 * On Mali, Krypton Wrapper hangs on the Blaze3D versions, and Zink over the
 * system Vulkan driver is what runs them there. A renderer named in the
 * settings wins unless it cannot start this version at all; then the automatic
 * choice runs the game and the player is told, since refusing would block a
 * version another renderer in the pack starts fine.
 */
object RendererChoice {

    /**
     * [failedAtInit] names renderers that crashed this build before its first
     * frame: the automatic choice passes over them while another one fits.
     * [sodium] is set when the build carries Sodium or one of its forks.
     */
    fun choose(
        gameVersion: String,
        releaseTime: String,
        graphics: DeviceGraphics,
        offered: List<RuntimeManifest.RendererEntry>,
        requested: String,
        failedAtInit: Set<String> = emptySet(),
        sodium: Boolean = false,
    ): RendererVerdict {
        val game = Game(
            blaze3d = GamePipeline.isBlaze3d(gameVersion, releaseTime),
            mobileGlues = GamePipeline.runsOnMobileGlues(gameVersion, releaseTime),
            yearRelease = GamePipeline.isYearRelease(gameVersion),
            sodium = sodium,
        )
        val fitting = offered.filter { problem(it, game, graphics) == null }
        val suitable = fitting.filterNot { it.id in failedAtInit }.ifEmpty { fitting }

        val named = offered.firstOrNull { requested.isNotEmpty() && it.id == requested }
        if (named != null) {
            val problem = problem(named, game, graphics)
                ?: return RendererVerdict.Use(named, "выбран в настройках")
            val instead = automatic(suitable, game, graphics, retry = failedAtInit.isNotEmpty())
                ?: return RendererVerdict.Refused(
                    "${label(named)} не запустит $gameVersion: $problem. " +
                        "Подходящего рендерера в пакете рантайма нет — обнови пакет рантайма.",
                )
            return RendererVerdict.Use(
                instead.renderer,
                "${label(named)} из настроек не подходит: ${instead.reason}",
                "${label(named)} не запустит $gameVersion: $problem. Запускаем на ${label(instead.renderer)}.",
            )
        }

        if (suitable.isEmpty()) {
            val reasons = offered.joinToString("; ") { "${label(it)} — ${problem(it, game, graphics)}" }
            return RendererVerdict.Refused(
                "Для $gameVersion на этом телефоне нет подходящего рендерера" +
                    (if (reasons.isEmpty()) "" else " ($reasons)") +
                    ". Возьми версию до 1.21.4 или обнови пакет рантайма.",
            )
        }

        val chosen = automatic(suitable, game, graphics, retry = failedAtInit.isNotEmpty()) ?: RendererVerdict.Use(suitable.first(), "по умолчанию")
        val usual = automatic(fitting, game, graphics)?.renderer
        if (usual == null || usual.id == chosen.renderer.id) return chosen
        return chosen.copy(
            reason = "${label(usual)} упал при запуске этой сборки",
            note = "${label(usual)} в прошлый раз не запустил эту сборку — запускаем на ${label(chosen.renderer)}. " +
                "Чтобы вернуть ${label(usual)}, выбери его в настройках.",
        )
    }

    private class Game(val blaze3d: Boolean, val mobileGlues: Boolean, val sodium: Boolean, val yearRelease: Boolean)

    /**
     * Sodium compiles its terrain shaders as desktop GLSL with implicit type
     * conversions. Krypton hands them to the GLES compiler, which rejects them,
     * and the world is drawn without blocks; MobileGlues translates the GLSL.
     */
    private fun automatic(
        suitable: List<RuntimeManifest.RendererEntry>,
        game: Game,
        graphics: DeviceGraphics,
        retry: Boolean = false,
    ): RendererVerdict.Use? {
        if (game.sodium) {
            suitable.firstOrNull { RendererKind.of(it) == RendererKind.MOBILEGLUES }?.let {
                return RendererVerdict.Use(it, "в сборке Sodium: его шейдеры собирает MobileGlues")
            }
        }
        // Seven days of launches (share that reached play, Mali): 26.x Zink 62% (8761) against
        // Krypton 26% (2532); 1.21.5-1.21.11 Zink 21% (5811) against Krypton 50% (2032).
        // Adreno: Krypton on every version, Zink 3-39%.
        if (game.blaze3d && game.yearRelease && graphics.gpu == GpuFamily.MALI) {
            suitable.firstOrNull { RendererKind.of(it) == RendererKind.ZINK }?.let {
                return RendererVerdict.Use(it, "Mali и версия 26.x: Zink через Vulkan")
            }
        }
        // After a failed start (crash at init or a black screen) on 1.17+, MobileGlues
        // (OpenGL 4.0 over GLES, Zalith's main renderer) is the first fallback.
        if (retry && game.mobileGlues) {
            suitable.firstOrNull { RendererKind.of(it) == RendererKind.MOBILEGLUES }?.let {
                return RendererVerdict.Use(it, "прошлый рендерер не дал кадра: MobileGlues")
            }
        }
        return suitable.firstOrNull()?.let { RendererVerdict.Use(it, "по умолчанию") }
    }

    private fun problem(entry: RuntimeManifest.RendererEntry, game: Game, graphics: DeviceGraphics): String? =
        when (RendererKind.of(entry)) {
            RendererKind.GL4ES -> if (game.blaze3d) "GL4ES тянет версии только до 1.21.4" else null
            RendererKind.ZINK -> if (!graphics.vulkan11) "Zink работает через Vulkan 1.1, а телефон его не даёт" else null
            RendererKind.MOBILEGLUES -> if (!game.mobileGlues) "MobileGlues тянет версии только с 1.17" else null
            RendererKind.KRYPTON, RendererKind.OTHER -> null
        }

    private fun label(entry: RuntimeManifest.RendererEntry): String = entry.label.ifEmpty { entry.id }
}

/**
 * Zink over the Mali Vulkan driver fails some Blaze3D builds with a GL error
 * while the game initializes, where Krypton on Adreno gets past the same
 * point. The failure is remembered per build and version id, so the next
 * start takes another renderer instead of the same crash, and another game or
 * loader version gives Zink a new chance.
 */
object RendererFallback {

    fun crashedAtInit(rendererKind: RendererKind, crashText: String): Boolean =
        (rendererKind == RendererKind.ZINK || rendererKind == RendererKind.MOBILEGLUES) &&
            INIT_STEP in crashText && GL_ERROR.containsMatchIn(crashText) ||
            // Minecraft 26.2 on Zink dies with a report that has no description beyond this
            // line (361 of 1461 starts in a week of telemetry, none on 26.1 or 26.3).
            rendererKind == RendererKind.ZINK && UNEXPECTED_ERROR in crashText

    /**
     * A black screen is not a crash: the game runs, no frame ever comes, and the player
     * kills it. A start with no frame drawn by this time counts as failed.
     */
    const val NO_FRAME_GRACE_MS = 60_000L

    fun entry(rendererId: String, profileId: String, versionId: String): String =
        listOf(rendererId, profileId, versionId).joinToString(SEPARATOR)

    fun failedAtInit(entries: Set<String>, profileId: String, versionId: String): Set<String> =
        entries.mapNotNull { stored ->
            val parts = stored.split(SEPARATOR)
            parts.takeIf { it.size == 3 && it[1] == profileId && it[2] == versionId }?.first()
        }.toSet()

    private const val SEPARATOR = "\n"
    private const val INIT_STEP = "Description: Initializing game"
    private const val UNEXPECTED_ERROR = "Description: Unexpected error"
    private val GL_ERROR = Regex("""IllegalStateException: OpenGL error \d+""")
}
