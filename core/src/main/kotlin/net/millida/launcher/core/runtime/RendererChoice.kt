package net.millida.launcher.core.runtime

import java.io.File

import net.millida.launcher.core.i18n.tr
enum class GpuFamily {
    ADRENO,
    MALI,

    /** The Rogue driver line (GE8xxx, GM9xxx); B-Series and later run another driver. */
    POWERVR_ROGUE,
    POWERVR,
    XCLIPSE,
    MALEOON,
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
                "powervr rogue" in name -> POWERVR_ROGUE
                "powervr" in name || "imagination" in maker -> POWERVR
                "xclipse" in name -> XCLIPSE
                "maleoon" in name -> MALEOON
                else -> OTHER
            }
        }
    }
}

data class DeviceGraphics(val gpu: GpuFamily, val vulkan11: Boolean, val glRenderer: String? = null) {
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

    /**
     * 26.3-snapshot-5 moved rendering to RenderPearl: shaders are compiled through ShaderC with
     * explicit locations and the terrain is drawn by multi-draw. Krypton draws nothing there.
     */
    fun isRenderPearl(gameVersion: String, releaseTime: String): Boolean =
        after(gameVersion, releaseTime, LAST_BEFORE_RENDERPEARL, LAST_BEFORE_RENDERPEARL_RELEASED)

    private const val LAST_BEFORE_RENDERPEARL = "26.2"
    private const val LAST_BEFORE_RENDERPEARL_RELEASED = "2026-07-16T13:59:30"

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

    /**
     * Zink over the Turnip Vulkan driver (Mesa) instead of the phone's own. Manual choice only:
     * the automatic choice never takes it until a phone has shown a frame with it.
     */
    const val ZINK_TURNIP_ID = "zink-turnip"
    const val TURNIP_LIBRARY = "libvulkan_freedreno.so"
    private const val LINKER_HOOK = "liblinkerhook.so"

    val zinkTurnip = RendererOption(
        id = ZINK_TURNIP_ID,
        label = "Zink + Turnip (Adreno)",
        library = ZINK_LIBRARY,
        eglLibrary = ZINK_EGL,
        nativeId = "opengles3_desktopgl_zink_kopper",
        environment = mapOf("LIBGL_ES" to "3"),
    )

    fun isTurnip(entry: RuntimeManifest.RendererEntry): Boolean = entry.id == ZINK_TURNIP_ID

    /** Where a Turnip build may come from: the pack's own folder, then the extra folders (debug builds). */
    fun turnipSource(rendererDir: File, extraDirs: List<File> = emptyList()): File? =
        (listOf(rendererDir) + extraDirs).map { File(it, TURNIP_LIBRARY) }.firstOrNull { it.isFile }

    /** The Turnip entry shows only on Adreno 6xx/7xx, the generations the driver is built for. */
    fun visibleOn(entries: List<RuntimeManifest.RendererEntry>, graphics: DeviceGraphics): List<RuntimeManifest.RendererEntry> =
        if (RendererChoice.turnipFits(graphics)) entries else entries.filterNot { isTurnip(it) }

    fun withDiscovered(declared: List<RendererOption>, rendererDir: File?): List<RendererOption> {
        if (rendererDir == null || declared.isEmpty()) return declared
        if (declared.any { it.library.contains("glxshim") } || !zinkPresent(rendererDir)) return declared
        return declared + zink
    }

    fun offered(
        declared: List<RuntimeManifest.RendererEntry>,
        rendererDir: File,
        turnipDirs: List<File> = emptyList(),
    ): List<RuntimeManifest.RendererEntry> {
        val present = declared.filter { it.library.isEmpty() || File(rendererDir, it.library).isFile }
        if (present.isEmpty()) return present
        val withZink = when {
            present.any { RendererKind.of(it) == RendererKind.ZINK } || !zinkPresent(rendererDir) -> present
            else -> present + asEntry(zink, component = rendererDir.name)
        }
        if (withZink.any { isTurnip(it) } || !zinkPresent(rendererDir)) return withZink
        if (!File(rendererDir, LINKER_HOOK).isFile || turnipSource(rendererDir, turnipDirs) == null) return withZink
        return withZink + asEntry(zinkTurnip, component = rendererDir.name)
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
 * No renderer in the pack suits every GPU and every version, so the automatic
 * choice goes by what is known to draw there. A renderer named in the settings
 * wins unless it cannot start this version at all; then the automatic choice
 * runs the game and the player is told, since refusing would block a version
 * another renderer in the pack starts fine.
 */
object RendererChoice {

    /**
     * [failedAtInit] names renderers that crashed this build before its first
     * frame: the automatic choice passes over them while another one fits.
     * [sodium] is set when the build carries Sodium or one of its forks, [sdl]
     * when the game opens its window through SDL3 instead of the GLFW layer.
     */
    /** How many other renderers than [chosenId] fit this game and have not failed it: zero means a repeat starts the same one. */
    fun alternatives(
        gameVersion: String,
        releaseTime: String,
        graphics: DeviceGraphics,
        offered: List<RuntimeManifest.RendererEntry>,
        chosenId: String,
        failedAtInit: Set<String>,
        sodium: Boolean = false,
        sdl: Boolean = false,
    ): Int {
        val game = Game(
            blaze3d = GamePipeline.isBlaze3d(gameVersion, releaseTime),
            mobileGlues = GamePipeline.runsOnMobileGlues(gameVersion, releaseTime),
            yearRelease = GamePipeline.isYearRelease(gameVersion),
            renderPearl = GamePipeline.isRenderPearl(gameVersion, releaseTime),
            sodium = sodium,
            sdl = sdl,
        )
        return offered.count {
            it.id != chosenId && it.id !in failedAtInit && !RendererCatalog.isTurnip(it) && problem(it, game, graphics) == null
        }
    }

    fun choose(
        gameVersion: String,
        releaseTime: String,
        graphics: DeviceGraphics,
        offered: List<RuntimeManifest.RendererEntry>,
        requested: String,
        failedAtInit: Set<String> = emptySet(),
        sodium: Boolean = false,
        sdl: Boolean = false,
    ): RendererVerdict {
        val game = Game(
            blaze3d = GamePipeline.isBlaze3d(gameVersion, releaseTime),
            mobileGlues = GamePipeline.runsOnMobileGlues(gameVersion, releaseTime),
            yearRelease = GamePipeline.isYearRelease(gameVersion),
            renderPearl = GamePipeline.isRenderPearl(gameVersion, releaseTime),
            sodium = sodium,
            sdl = sdl,
        )
        // Zink + Turnip is for a hand-picked start only, until a phone has shown a frame with it.
        val fitting = offered.filter { problem(it, game, graphics) == null && !RendererCatalog.isTurnip(it) }
        val suitable = fitting.filterNot { it.id in failedAtInit }.ifEmpty { fitting }

        val named = offered.firstOrNull { requested.isNotEmpty() && it.id == requested }
        if (named != null && RendererCatalog.isTurnip(named) && problem(named, game, graphics) == null) {
            return RendererVerdict.Use(named, tr("выбран в настройках (драйвер Turnip)"))
        }
        if (named != null) {
            val problem = problem(named, game, graphics)
                ?: return RendererVerdict.Use(named, tr("выбран в настройках"))
            val instead = automatic(suitable, game, graphics, retry = failedAtInit.isNotEmpty())
                ?: return RendererVerdict.Refused(
                    tr("{0} не запустит {gameVersion}: {problem}. ", label(named), gameVersion, problem) +
                        tr("Подходящего рендерера в пакете рантайма нет — обнови пакет рантайма."),
                )
            return RendererVerdict.Use(
                instead.renderer,
                tr("{0} из настроек не подходит: {reason}", label(named), instead.reason),
                tr("{0} не запустит {gameVersion}: {problem}. Запускаем на {1}.", label(named), gameVersion, problem, label(instead.renderer)),
            )
        }

        if (suitable.isEmpty()) {
            val reasons = offered.joinToString("; ") { "${label(it)} — ${problem(it, game, graphics)}" }
            return RendererVerdict.Refused(
                if (reasons.isEmpty()) {
                    tr("Для {gameVersion} на этом телефоне нет подходящего рендерера. Возьми версию до 1.21.4 или обнови пакет рантайма.", gameVersion)
                } else {
                    tr("Для {gameVersion} на этом телефоне нет подходящего рендерера ({reasons}). Возьми версию до 1.21.4 или обнови пакет рантайма.", gameVersion, reasons)
                },
            )
        }

        val chosen = automatic(suitable, game, graphics, retry = failedAtInit.isNotEmpty()) ?: RendererVerdict.Use(suitable.first(), tr("по умолчанию"))
        val usual = automatic(fitting, game, graphics)?.renderer
        if (usual == null || usual.id == chosen.renderer.id) return chosen
        return chosen.copy(
            reason = tr("{0} упал при запуске этой сборки", label(usual)),
            note = tr("{0} в прошлый раз не запустил эту сборку — запускаем на {1}. ", label(usual), label(chosen.renderer)) +
                tr("Чтобы вернуть {0}, выбери его в настройках.", label(usual)),
        )
    }

    private class Game(
        val blaze3d: Boolean,
        val mobileGlues: Boolean,
        val sodium: Boolean,
        val yearRelease: Boolean,
        val sdl: Boolean,
        val renderPearl: Boolean = false,
    )

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
                return RendererVerdict.Use(it, tr("в сборке Sodium: его шейдеры собирает MobileGlues"))
            }
        }
        // Three days of 1.10.x on 26.3: a black screen for 65% of the players (727 of 1124),
        // against 7-8% on 26.2 and 1.21.11; on Krypton it is every GPU, Adreno 610 among them.
        if (game.renderPearl) {
            suitable.firstOrNull { RendererKind.of(it) == RendererKind.MOBILEGLUES }?.let {
                return RendererVerdict.Use(it, tr("версия 26.3 и новее: MobileGlues"))
            }
        }
        // The Rogue driver draws integer vertex attributes wrong, and the game's shaders use
        // them since 1.17: the world and the text come out black. GL4ES 1.1.4 feeds them as
        // floats; Krypton hands them over as they are. From 1.21.5 on GL4ES cannot run the
        // game, and MobileGlues, which rewrites the shaders, is what draws there.
        if (graphics.gpu == GpuFamily.POWERVR_ROGUE && game.mobileGlues) {
            val wanted = if (game.blaze3d) RendererKind.MOBILEGLUES else RendererKind.GL4ES
            suitable.firstOrNull { RendererKind.of(it) == wanted }?.let {
                return RendererVerdict.Use(it, tr("PowerVR Rogue: Krypton рисует мир чёрным с 1.17"))
            }
        }
        // Krypton draws 26.x wrong on Maleoon and aborts 26.3 there on a GL call it lacks.
        if (graphics.gpu == GpuFamily.MALEOON && game.yearRelease) {
            suitable.firstOrNull { RendererKind.of(it) == RendererKind.MOBILEGLUES }?.let {
                return RendererVerdict.Use(it, tr("Maleoon и версия 26.x: MobileGlues"))
            }
        }
        // Ten days to 27.10, crashes per launch. Xclipse: 1.21 Krypton 50% (769), MobileGlues 35% (284);
        // 26.x Krypton 49% (397), MobileGlues 20% (191). PowerVR on 26.x: Krypton 46%, MobileGlues 25%.
        if ((graphics.gpu == GpuFamily.XCLIPSE && game.blaze3d) ||
            (graphics.gpu == GpuFamily.POWERVR && game.yearRelease)
        ) {
            suitable.firstOrNull { RendererKind.of(it) == RendererKind.MOBILEGLUES }?.let {
                return RendererVerdict.Use(it, tr("Xclipse и PowerVR на новых версиях: MobileGlues"))
            }
        }
        // Four days of 1.9.27-1.9.29 on Mali, 26.x, crashes per launch. Through SDL3: MobileGlues
        // 8% (2595), Krypton 55% (264), Zink 91% (1628, all but five before the first frame).
        // Through the GLFW layer: MobileGlues 29% (1697), Krypton 47% (195). Zink is no longer
        // offered on Mali at all (see problem()), so MobileGlues takes 26.x whatever opens the window.
        if (game.blaze3d && game.yearRelease && graphics.gpu == GpuFamily.MALI) {
            suitable.firstOrNull { RendererKind.of(it) == RendererKind.MOBILEGLUES }?.let {
                return RendererVerdict.Use(it, tr("Mali и версия 26.x: MobileGlues"))
            }
        }
        // After a failed start (crash at init or a black screen) on 1.17+, MobileGlues
        // (OpenGL 4.0 over GLES, Zalith's main renderer) is the first fallback.
        if (retry && game.mobileGlues) {
            suitable.firstOrNull { RendererKind.of(it) == RendererKind.MOBILEGLUES }?.let {
                return RendererVerdict.Use(it, tr("прошлый рендерер не дал кадра: MobileGlues"))
            }
        }
        return suitable.firstOrNull()?.let { RendererVerdict.Use(it, tr("по умолчанию")) }
    }

    /** Turnip is built for Adreno 6xx and 7xx; the family alone is not enough, the model number decides. */
    fun turnipFits(graphics: DeviceGraphics): Boolean =
        graphics.gpu == GpuFamily.ADRENO && ADRENO_6_7.containsMatchIn(graphics.glRenderer.orEmpty())

    private fun problem(entry: RuntimeManifest.RendererEntry, game: Game, graphics: DeviceGraphics): String? =
        if (RendererCatalog.isTurnip(entry)) {
            if (turnipFits(graphics)) null else tr("Turnip собран для Adreno 6xx и 7xx")
        } else regularProblem(entry, game, graphics)

    private fun regularProblem(entry: RuntimeManifest.RendererEntry, game: Game, graphics: DeviceGraphics): String? =
        when (RendererKind.of(entry)) {
            RendererKind.GL4ES -> if (game.blaze3d) tr("GL4ES тянет версии только до 1.21.4") else null
            RendererKind.ZINK -> when {
                !graphics.vulkan11 -> tr("Zink работает через Vulkan 1.1, а телефон его не даёт")
                graphics.gpu == GpuFamily.POWERVR_ROGUE -> tr("Zink не запускается на драйвере Vulkan у PowerVR Rogue")
                // 1628 launches of 26.x on Mali in four days, 91% died before the first frame.
                graphics.gpu == GpuFamily.MALI -> tr("Zink на Mali не доходит до картинки")
                // Redmi Note 13 (Adreno 610), 26.10: Zink died within 15 s on 1.20.1 and on 26.3.
                graphics.gpu == GpuFamily.ADRENO && ADRENO_6.containsMatchIn(graphics.glRenderer.orEmpty()) ->
                    tr("Zink на Adreno 6xx не доходит до картинки")
                else -> null
            }
            RendererKind.MOBILEGLUES -> if (!game.mobileGlues) tr("MobileGlues тянет версии только с 1.17") else null
            RendererKind.KRYPTON, RendererKind.OTHER -> null
        }

    private val ADRENO_6 = Regex("(?i)adreno[^0-9]*6\\d\\d")
    private val ADRENO_6_7 = Regex("(?i)adreno[^0-9]*[67]\\d\\d")

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
        // LWJGL aborts the Java machine when the game calls a GL entry point the layer did
        // not give it; the same build needs a layer that has it, whichever one failed.
        MISSING_GL_FUNCTION in crashText ||
            rendererKind in CHOSEN_OVER_KRYPTON && INIT_STEP in crashText && GL_ERROR.containsMatchIn(crashText) ||
            // Krypton on Mali gives 1.18-1.21 a framebuffer the game refuses, at start or in the
            // world (102 players in a week, all Mali); MobileGlues builds it.
            rendererKind == RendererKind.KRYPTON && FRAMEBUFFER_UNSUPPORTED in crashText ||
            // Minecraft 26.2 on Zink dies with a report that has no description beyond this
            // line (361 of 1461 starts in a week of telemetry, none on 26.1 or 26.3).
            rendererKind == RendererKind.ZINK && UNEXPECTED_ERROR in crashText

    /**
     * An abort inside the Java machine takes the whole game process with it, so nothing
     * after the crash can write the failure down. The start leaves this record instead,
     * and the next start reads the log it points at.
     */
    fun pendingStart(entry: String, rendererKind: RendererKind, logName: String): String =
        listOf(rendererKind.name, logName, entry).joinToString(FIELD)

    /** The entry to remember as failed when the start [pending] describes died in its renderer, else null. */
    fun diedAtInit(pending: String, logTail: (logName: String) -> String): String? {
        val parts = pending.split(FIELD, limit = 3)
        if (parts.size != 3 || parts[1].isEmpty() || parts[2].isEmpty()) return null
        val kind = RendererKind.entries.firstOrNull { it.name == parts[0] } ?: return null
        return parts[2].takeIf { crashedAtInit(kind, logTail(parts[1])) }
    }

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
    private const val FIELD = "\u001F"
    const val MISSING_GL_FUNCTION = "No context is current or a function that is not available in the current context was called"
    private const val INIT_STEP = "Description: Initializing game"
    private const val UNEXPECTED_ERROR = "Description: Unexpected error"
    private const val FRAMEBUFFER_UNSUPPORTED = "RuntimeException: GL_FRAMEBUFFER_UNSUPPORTED"
    private val GL_ERROR = Regex("""IllegalStateException: OpenGL error \d+|RuntimeException: GL_FRAMEBUFFER_\w+""")

    /** Renderers the automatic choice takes instead of Krypton somewhere: a failure of theirs sends the build back. */
    private val CHOSEN_OVER_KRYPTON = setOf(RendererKind.ZINK, RendererKind.MOBILEGLUES, RendererKind.GL4ES)
}
