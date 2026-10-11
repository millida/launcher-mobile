package net.millida.launcher.core.runtime

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
    fun vulkanLoader(sdl: Boolean): String? = if (sdl) null else "-Dorg.lwjgl.vulkan.libname=$SYSTEM_VULKAN"

    /**
     * Never the translation layer on the GLES path: it is not loaded there, and
     * LWJGL opening it on its own would put a second GL beside SDL's context.
     */
    fun openglLibrary(renderer: File, graphics: SdlGraphics?): String =
        if (graphics == SdlGraphics.NATIVE_GLES) SYSTEM_GLES else renderer.absolutePath

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

    const val HINT_OPENGL_LIBRARY = "SDL_OPENGL_LIBRARY"
    const val HINT_EGL_LIBRARY = "SDL_EGL_LIBRARY"
    const val GLES_SHIM = "libmillida_gles.so"
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
