package net.millida.launcher.core.runtime

import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.packs.PhoneBlock
import java.io.File

/** Which window library a start goes through. */
sealed interface WindowChoice {
    /** The GLFW layer of the runtime pack: every pack that does not ask for SDL3. */
    data object Glfw : WindowChoice

    /** SDL3 from the runtime component, with the LWJGL build it was made for. */
    data object Sdl : WindowChoice

    /** The pack needs SDL3 and the phone cannot give it right now. */
    data class Refused(val block: PhoneBlock) : WindowChoice
}

object WindowChoices {

    /**
     * SDL3 is a beta path: a pack that needs it starts only with the player's
     * beta switch on and the component published. A pack that does not need it
     * never takes it, so the GLFW path stays exactly as it was.
     */
    fun choose(needsSdl: Boolean, legacyRuntime: Boolean, sdlAvailable: Boolean): WindowChoice = when {
        !needsSdl -> WindowChoice.Glfw
        legacyRuntime && sdlAvailable -> WindowChoice.Sdl
        else -> WindowChoice.Refused(PhoneBlock.SDL_WINDOW)
    }
}

/** How the game's GL reaches the phone's graphics on the SDL3 path. */
enum class SdlGraphics {
    /** Desktop GL through the translation layer SDL takes its entry points from. */
    TRANSLATED,

    /**
     * Angelica 2.2+ accepts only a desktop core profile, which no translation
     * layer reports, or GLES 3.2, which it then drives itself. SDL makes a
     * plain GLES context of the phone and nothing sits in between.
     */
    NATIVE_GLES,
}

/**
 * What the SDL3 path changes in a start, kept apart from the Android side so
 * the order and the values can be pinned by tests.
 */
object SdlLaunch {

    /**
     * Minecraft 26.3 and later open the window through SDL3 and list the LWJGL
     * binding for it among their libraries. The class jar and every natives
     * entry count: the resolver drops the whole LWJGL family afterwards, so
     * this is read from the raw coordinates.
     */
    fun versionNeedsSdl(libraryNames: List<String>): Boolean =
        libraryNames.any { it.trim().split(':').take(2) == SDL_COORDINATE }

    fun graphics(gameDir: File): SdlGraphics {
        val mods = File(gameDir, "mods").list().orEmpty()
        return if (mods.any(::angelicaWithGles)) SdlGraphics.NATIVE_GLES else SdlGraphics.TRANSLATED
    }

    /**
     * On Android 10 and older a game on Java 25 cannot load its own libraries through
     * SDL3's LWJGL: the system linker refuses them («not accessible for the namespace»),
     * and 96 of 100 such starts ended in a crash. The same libraries load under Java 21,
     * so packs on it are let through.
     */
    fun androidProblem(sdkInt: Int, javaMajor: Int, gameVersion: String): String? {
        if (sdkInt >= FIRST_ANDROID_FOR_JAVA_25 || javaMajor < JAVA_25) return null
        return tr("Minecraft {gameVersion} на Android 10 и старше не запускается: система не даёт игре загрузить её библиотеки. ", gameVersion) +
            tr("Нужен Android 11 или новее. На этом телефоне идут версии игры до 1.21.11")
    }

    /** The GLES profile and the core-profile check arrived together in Angelica 2.2.0. */
    fun angelicaWithGles(fileName: String): Boolean {
        val match = ANGELICA_JAR.matchEntire(fileName) ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        val minor = match.groupValues[2].toIntOrNull() ?: return false
        return major > ANGELICA_GLES_MAJOR || (major == ANGELICA_GLES_MAJOR && minor >= ANGELICA_GLES_MINOR)
    }

    /**
     * The component's jars go first and replace the pack's own LWJGL: Java
     * classes and natives of LWJGL must come from one build, down to the patch
     * version, or the first native call fails.
     */
    fun classpath(sdl: InstalledSdl, game: List<File>): List<File> = sdl.classpath + game

    /** The component's natives are found before the GLFW pack's LWJGL of another version. */
    fun libraryPath(sdl: InstalledSdl, packNatives: File): String =
        listOf(sdl.dir, packNatives).joinToString(":") { it.absolutePath }

    /**
     * LWJGL modules that bind a plain shared library rather than a JNI one,
     * carried by the component under the names LWJGL 3.4.2 asks for. Only the
     * files the installed component really has: an older component lacks them.
     */
    fun bundledLibraries(sdl: InstalledSdl): Map<String, File> =
        BUNDLED_LIBRARIES.mapValues { File(sdl.dir, it.value) }.filterValues { it.isFile }

    fun systemProperties(sdl: InstalledSdl, graphics: SdlGraphics): List<String> = buildList {
        add("-Dorg.lwjgl.sdl.libname=${sdl.library.absolutePath}")
        bundledLibraries(sdl).forEach { (module, file) -> add("-Dorg.lwjgl.$module.libname=${file.absolutePath}") }
        if (graphics == SdlGraphics.NATIVE_GLES) add("-D$ANGELICA_PROFILE=es")
    }

    /**
     * Minecraft 26.3 loads VMA natively as soon as LWJGL finds a Vulkan loader,
     * and the component has no Android build of VMA. Without a name LWJGL looks
     * for libvulkan.so.1, which Android does not have, so the game takes its own
     * path for a PC without Vulkan and renders on OpenGL through the renderer.
     */
    fun vulkanLoader(sdl: Boolean, shim: File? = null): String? =
        if (sdl) null else "-Dorg.lwjgl.vulkan.libname=${shim?.absolutePath ?: SYSTEM_VULKAN}"

    /**
     * A mod that draws through Vulkan itself (VulkanMod) is a desktop renderer: it hands
     * the surface's current transform back as the swapchain's pre-transform and draws
     * upright. Android reports the screen rotation there, so the picture came out on its
     * side and stretched. The shim sits between LWJGL and the phone's loader and reports
     * an upright surface, which leaves the turn to the compositor. A renderer whose
     * manifest entry sets [VK_SHIM_SWITCH] to 0 goes without it.
     */
    fun vulkanShim(shim: File, sdl: Boolean, rendererEnvironment: Map<String, String> = emptyMap()): File? =
        shim.takeIf { !sdl && rendererEnvironment[VK_SHIM_SWITCH] != "0" && it.isFile }

    /**
     * Never the translation layer on the GLES path: it is not loaded there, and
     * LWJGL opening it on its own would put a second GL beside SDL's context.
     * With [glxShim] LWJGL asks the shim, which asks the layer.
     */
    fun openglLibrary(renderer: File, graphics: SdlGraphics?, glxShim: File? = null): String = when {
        graphics == SdlGraphics.NATIVE_GLES -> SYSTEM_GLES
        glxShim != null -> glxShim.absolutePath
        else -> renderer.absolutePath
    }

    /**
     * LWJGL 3.4 of the SDL3 path fills its GL function table itself. For a context that
     * is not forward compatible it asks for the deprecated entry points of a GL version
     * first and, when one is missing, leaves the core ones of that version empty. The
     * translation layers lack the packed vertex attributes of GL 3.3, so the game aborted
     * on the first glVertexAttribDivisor. The shim hands those names a call that does
     * nothing and passes every other name to the layer. Only the GL4ES family gets it:
     * Mesa and MobileGlues answer for every name themselves, and their starts stay exactly
     * as they were. A renderer whose manifest entry sets [GLX_SHIM_SWITCH] to 0 goes without
     * it: the switch needs no new application.
     */
    fun glxShim(
        shim: File,
        renderer: File?,
        graphics: SdlGraphics?,
        kind: RendererKind,
        rendererEnvironment: Map<String, String> = emptyMap(),
    ): File? = shim.takeIf {
        graphics == SdlGraphics.TRANSLATED && (kind == RendererKind.KRYPTON || kind == RendererKind.GL4ES) &&
            rendererEnvironment[GLX_SHIM_SWITCH] != "0" && it.isFile && renderer != null && renderer.isFile
    }

    /** The shim is told which library to ask through the environment: LWJGL passes it nothing but a name. */
    fun glxShimEnvironment(renderer: File): Map<String, String> = mapOf(GLX_REAL_LIBRARY to renderer.absolutePath)

    /**
     * SDL reads hints from the environment. The renderer library becomes the
     * place SDL takes GL entry points from, so the game draws through the
     * translation layer and not straight into the phone's GLES. HIDAPI stays
     * off: gamepads reach the game through the launcher's own input layer.
     * [rendererEgl] is for a layer that keeps its own record of EGL contexts
     * (MobileGlues): SDL then creates them through it, as the GLFW layer does.
     */
    fun environment(rendererLibrary: File?, graphics: SdlGraphics, rendererEgl: File? = null): Map<String, String> = buildMap {
        if (graphics == SdlGraphics.TRANSLATED) {
            rendererLibrary?.takeIf { it.isFile }?.let { put(HINT_OPENGL_LIBRARY, it.absolutePath) }
            rendererEgl?.takeIf { it.isFile }?.let { put(HINT_EGL_LIBRARY, it.absolutePath) }
        }
        put(HINT_JOYSTICK_HIDAPI, "0")
    }

    /**
     * Angelica on GLES leaves 1.7.10 texture uploads in BGRA, which GLES
     * rejects, so every texture stays empty. SDL then takes both its EGL and
     * its GL entry points from the launcher's shim, which swizzles those
     * uploads and forwards the rest: LWJGL gets every GL function from SDL.
     */
    fun glesShim(shim: File, graphics: SdlGraphics): Map<String, String> {
        if (graphics != SdlGraphics.NATIVE_GLES || !shim.isFile) return emptyMap()
        // Only the EGL side: with SDL_OPENGL_LIBRARY on the shim the game's first GL call found no function.
        return mapOf(HINT_EGL_LIBRARY to shim.absolutePath)
    }

    private const val FIRST_ANDROID_FOR_JAVA_25 = 30
    private const val JAVA_25 = 25

    const val HINT_OPENGL_LIBRARY = "SDL_OPENGL_LIBRARY"
    const val HINT_EGL_LIBRARY = "SDL_EGL_LIBRARY"
    const val GLES_SHIM = "libmillida_gles.so"
    const val GLX_SHIM = "libmillida_glx.so"
    const val GLX_REAL_LIBRARY = "MILLIDA_GL_LIBRARY"
    const val GLX_SHIM_SWITCH = "MILLIDA_GLX_SHIM"
    const val VK_SHIM = "libmillida_vk.so"
    const val VK_SHIM_SWITCH = "MILLIDA_VK_SHIM"
    const val HINT_JOYSTICK_HIDAPI = "SDL_JOYSTICK_HIDAPI"
    const val ANGELICA_PROFILE = "angelica.glProfile"
    const val SYSTEM_GLES = "libGLESv2.so"
    const val SYSTEM_VULKAN = "libvulkan.so"

    val BUNDLED_LIBRARIES: Map<String, String> = mapOf(
        "shaderc" to "libshaderc.so",
        "spvc" to "libspirv-cross.so",
    )

    private val SDL_COORDINATE = listOf("org.lwjgl", "lwjgl-sdl")
    private const val ANGELICA_GLES_MAJOR = 2
    private const val ANGELICA_GLES_MINOR = 2
    private val ANGELICA_JAR = Regex("(?i)angelica-(\\d{1,4})\\.(\\d{1,4})[^/\\\\]*\\.jar")
}
