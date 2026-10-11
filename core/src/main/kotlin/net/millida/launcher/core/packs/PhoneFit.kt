package net.millida.launcher.core.packs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import net.millida.launcher.core.MemoryTuning
import net.millida.launcher.core.loader.forgeGameUnsupported
import java.io.IOException

/** Why a pack plays on a computer and not on this phone. */
enum class PhoneBlock {
    /** Forge before 1.13 needs Java 8 and the old window library: only with the beta switch on. */
    OLD_FORGE,

    /** The pack brings its own game and launch, written for a computer: only with the beta switch on. */
    OWN_LAUNCH,

    /** The pack's own launch description is unreadable. */
    BROKEN_LAUNCH,

    /**
     * A paid pack with its own launch: whether it starts on a phone is known
     * only from its archive, and the archive opens only after the purchase.
     */
    PAID_OWN_LAUNCH,

    /** The pack draws its window through SDL3 (lwjgl3ify 3): only with the beta switch on and the SDL3 runtime component published. */
    SDL_WINDOW,

    /** The pack loads native code of its own built only for computers, with no phone build next to it. */
    DESKTOP_NATIVE,

    /** The client alone is bigger than the memory the game could ever get on this phone. */
    TOO_HEAVY,
}

/** What a catalogue card tells before a byte of the pack is downloaded. */
data class PhoneCard(
    val loader: String,
    val gameVersion: String,
    val ownLaunch: Boolean = false,
    val accessRequired: Boolean = false,
    val sizeBytes: Long = 0,
)

/** Thrown before a byte of a pack is downloaded, or right after unpacking when only the archive can tell. */
class PhoneUnsupported(val block: PhoneBlock, val title: String, val gameVersion: String) :
    IOException(PhoneFit.message(block, title, gameVersion))

/**
 * One rule for "can this pack start on a phone at all", asked before the
 * download so nobody waits for gigabytes that end in "not supported".
 */
object PhoneFit {
    const val PACK_LAUNCH_FILE = "millida-pack-launch.json"

    const val BETA_HINT = "Можно попробовать на телефоне: Настройки → Дополнительно → «Старые версии и сборки с ПК (бета)»"

    private const val HEAVY_BASE_MB = 512L

    @Volatile
    private var legacySource: () -> Boolean = { false }

    @Volatile
    private var deviceSource: () -> Int = { 0 }

    @Volatile
    private var sdlSource: () -> Boolean = { false }

    /**
     * The app wires the player's beta switch and the phone's memory here once
     * per process; every check reads them at the moment it is asked, so the
     * switch applies without a restart. Unwired, the beta stays off.
     */
    fun wire(legacyRuntime: () -> Boolean, deviceTotalMb: () -> Int, sdlRuntime: () -> Boolean = { false }) {
        legacySource = legacyRuntime
        deviceSource = deviceTotalMb
        sdlSource = sdlRuntime
    }

    /** The Java 8 + LWJGL 2 runtime and packs with their own launch, behind the player's beta switch. */
    fun legacyRuntime(): Boolean = legacySource()

    fun deviceTotalMb(): Int = deviceSource()

    /** Whether the SDL3 runtime component is on the phone or listed in the runtime manifest. */
    fun sdlRuntime(): Boolean = sdlSource()

    fun verdict(
        loader: String,
        gameVersion: String,
        launch: LaunchShape = LaunchShape.PLAIN,
        legacyRuntime: Boolean = legacyRuntime(),
    ): PhoneBlock? = when (launch) {
        LaunchShape.OWN_DECLARED, LaunchShape.OWN_READY, LaunchShape.OWN_LEGACY ->
            if (legacyRuntime) null else PhoneBlock.OWN_LAUNCH
        LaunchShape.OWN_BROKEN -> PhoneBlock.BROKEN_LAUNCH
        LaunchShape.PLAIN -> {
            val kind = loader.trim().lowercase()
            if (kind == "forge" && forgeGameUnsupported(gameVersion) && !legacyRuntime) PhoneBlock.OLD_FORGE else null
        }
    }

    /** The verdict from the card alone: what lists hide and what the pack page says instead of «Установить». */
    fun cardVerdict(
        card: PhoneCard,
        legacyRuntime: Boolean = legacyRuntime(),
        deviceTotalMb: Int = deviceTotalMb(),
    ): PhoneBlock? {
        if (card.ownLaunch && card.accessRequired) return PhoneBlock.PAID_OWN_LAUNCH
        val launch = if (card.ownLaunch) LaunchShape.OWN_DECLARED else LaunchShape.PLAIN
        verdict(card.loader, card.gameVersion, launch, legacyRuntime)?.let { return it }
        return if (heavy(card.sizeBytes, deviceTotalMb)) PhoneBlock.TOO_HEAVY else null
    }

    /**
     * Whether a list may show the pack on this phone. Only what the card knows
     * hides it; a block the archive alone reveals stays for the pack page.
     */
    fun listed(
        card: PhoneCard,
        legacyRuntime: Boolean = legacyRuntime(),
        deviceTotalMb: Int = deviceTotalMb(),
    ): Boolean = cardVerdict(card, legacyRuntime, deviceTotalMb) == null

    /**
     * The archive is no measure of the heap a pack needs, but a client bigger
     * than everything the game could get is a sure refusal: half a gigabyte of
     * game on top of the files is the least it takes. Unknown sizes pass.
     */
    fun heavy(sizeBytes: Long, deviceTotalMb: Int): Boolean {
        if (sizeBytes <= 0 || deviceTotalMb <= 0) return false
        val needMb = sizeBytes / (1024 * 1024) + HEAVY_BASE_MB
        return needMb > MemoryTuning.heapCeilingMb(deviceTotalMb)
    }

    fun message(
        block: PhoneBlock,
        title: String,
        gameVersion: String,
        legacyRuntime: Boolean = legacyRuntime(),
        sdlRuntime: Boolean = sdlRuntime(),
    ): String {
        val text = when (block) {
            PhoneBlock.OLD_FORGE ->
                "«$title» на Forge ${gameVersion.ifBlank { "до 1.13" }} — на телефоне такие сборки не запускаются." +
                    "Играй в неё через Millida Launcher на компьютере"
            PhoneBlock.OWN_LAUNCH ->
                "У «$title» свой запуск, написанный для компьютера. Играй в неё через Millida Launcher на компьютере"
            PhoneBlock.BROKEN_LAUNCH ->
                "У «$title» свой запуск, телефон его не поддерживает. Играй в неё через Millida Launcher на компьютере"
            PhoneBlock.PAID_OWN_LAUNCH ->
                "«$title» запускается своим способом, написанным для компьютера, — на телефоне она не работает. " +
                    "Играй в неё через Millida Launcher на компьютере"
            PhoneBlock.SDL_WINDOW -> if (sdlRuntime) {
                "«$title» открывает окно игры через SDL3 — на телефоне такие сборки идут только в бете"
            } else {
                "«$title» открывает окно игры через SDL3, а на телефоне этой библиотеки нет. " +
                    "Играй в неё через Millida Launcher на компьютере"
            }
            PhoneBlock.DESKTOP_NATIVE ->
                "В «$title» есть библиотеки, собранные только для компьютера. " +
                    "Играй в неё через Millida Launcher на компьютере"
            PhoneBlock.TOO_HEAVY ->
                "«$title» не поместится в память этого телефона. Играй в неё через Millida Launcher на компьютере"
        }
        val unlockable = block == PhoneBlock.OLD_FORGE || block == PhoneBlock.OWN_LAUNCH ||
            (block == PhoneBlock.SDL_WINDOW && sdlRuntime)
        return if (unlockable && !legacyRuntime) "$text. $BETA_HINT" else text
    }

    /**
     * The archive index lists entry names before the download. The launch
     * description is read from the root of the build, and a pack wrapped in one
     * folder is unwrapped on install, so both depths count.
     */
    fun indexHasOwnLaunch(entries: List<String>): Boolean = launchRelative(entries) != null

    fun indexShape(entries: List<String>?): LaunchShape =
        if (entries != null && indexHasOwnLaunch(entries)) LaunchShape.OWN_DECLARED else LaunchShape.PLAIN

    /**
     * Entry names relative to the folder that holds the launch description,
     * or null when the archive has none at either depth.
     */
    fun launchRelative(entries: List<String>): List<String>? {
        val paths = entries.map { it.replace('\\', '/').trimStart('/') }.filter { it.isNotEmpty() }
        if (PACK_LAUNCH_FILE in paths) return paths
        val tops = paths.map { it.substringBefore('/', missingDelimiterValue = "") }.toSet()
        val wrapper = tops.singleOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        if ("$wrapper/$PACK_LAUNCH_FILE" !in paths) return null
        return paths.map { it.removePrefix("$wrapper/") }
    }

    private val SDL_JAR = Regex("^lwjgl-sdl-\\d[\\w.+-]*\\.jar$")
    private val SDL_NATIVE = Regex("^(lib)?sdl3([._-].*)?\\.(dll|so|dylib)$")

    /** A file of a pack that means its window goes through SDL3: the LWJGL binding or the library itself. */
    fun sdlFile(path: String): Boolean {
        val name = path.replace('\\', '/').substringAfterLast('/').lowercase()
        return SDL_JAR.matches(name) || SDL_NATIVE.matches(name)
    }
    private val NATIVE_EXTENSIONS = setOf("dll", "so", "dylib", "jnilib")

    /**
     * Natives the phone runtime replaces with its own arm64 builds, or that the
     * game never needs there (C runtimes of Windows, the 1.7.10 Twitch stream).
     */
    private val REPLACED_NATIVES = listOf(
        "lwjgl", "openal", "glfw", "sdl3", "jemalloc", "jinput", "tinyfd", "freetype", "harfbuzz",
        "twitchsdk", "avutil-ttv", "swresample-ttv", "mp3lame-ttv", "opengl32",
        "msvcr", "msvcp", "vcruntime", "ucrtbase", "api-ms-",
    )

    /**
     * What a pack with its own launch carries that the phone cannot run,
     * read from paths relative to its launch folder: the archive index before
     * the download, the unpacked tree after it. Natives count only under
     * `natives/`, where the launch description points; a pack that ships an
     * Android folder there has its own phone build of them. An SDL3 pack passes
     * only with the beta switch on and the SDL3 component available, and its
     * other natives are still checked: SDL3 replaces the window, nothing else.
     */
    fun ownLaunchProblem(
        paths: List<String>,
        legacyRuntime: Boolean = legacyRuntime(),
        sdlRuntime: Boolean = sdlRuntime(),
    ): PhoneBlock? {
        val clean = paths.map { it.replace('\\', '/').trimStart('/') }
        if (clean.any { sdlFile(it) } && !(legacyRuntime && sdlRuntime)) return PhoneBlock.SDL_WINDOW
        val natives = clean.filter { path ->
            path.startsWith("natives/", ignoreCase = true) &&
                path.substringAfterLast('.', "").lowercase() in NATIVE_EXTENSIONS
        }
        if (natives.any { path -> path.lowercase().split('/').any { it.startsWith("android") } }) return null
        // LWJGL ships every module for every platform under org/lwjgl; the phone
        // brings its own LWJGL, so only natives outside that tree are foreign.
        val foreign = natives.any { path ->
            val name = path.substringAfterLast('/').lowercase().removePrefix("lib")
            !path.contains("/org/lwjgl/", ignoreCase = true) && REPLACED_NATIVES.none { name.startsWith(it) }
        }
        return if (foreign) PhoneBlock.DESKTOP_NATIVE else null
    }

    /**
     * A pack whose author hides its contents keeps its mods in its own
     * libraries, so it always brings its own launch; `ownLaunch` is the
     * explicit form of the same fact when the server sends it.
     */
    fun ownLaunchIn(row: JsonObject): Boolean = OWN_LAUNCH_KEYS.any { key ->
        (row[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
    }

    private val OWN_LAUNCH_KEYS = listOf("ownLaunch", "protectedContent")

    @Throws(PhoneUnsupported::class)
    fun require(loader: String, gameVersion: String, title: String, launch: LaunchShape = LaunchShape.PLAIN) {
        val block = verdict(loader, gameVersion, launch) ?: return
        throw PhoneUnsupported(block, title, gameVersion)
    }

    @Throws(PhoneUnsupported::class)
    fun requireCard(card: PhoneCard, title: String) {
        val block = cardVerdict(card) ?: return
        throw PhoneUnsupported(block, title, card.gameVersion)
    }
}
