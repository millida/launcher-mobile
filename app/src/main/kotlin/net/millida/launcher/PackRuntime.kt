package net.millida.launcher

import android.content.Context
import android.system.Os
import android.view.Surface
import com.movtery.zalithlauncher.bridge.LoggerBridge
import com.movtery.zalithlauncher.bridge.ZLBridge
import com.oracle.dalvik.VMLauncher
import net.millida.launcher.core.game.AngelicaGlesSettings
import net.millida.launcher.core.launch.LaunchSpec
import net.millida.launcher.core.runtime.InstalledRuntime
import net.millida.launcher.core.runtime.InstalledSdl
import net.millida.launcher.core.runtime.JavaHomeLayout
import net.millida.launcher.core.runtime.MobileGluesSetup
import net.millida.launcher.core.runtime.RendererKind
import net.millida.launcher.core.runtime.RendererVariable
import net.millida.launcher.core.runtime.SdlGraphics
import net.millida.launcher.core.runtime.SdlLaunch
import net.millida.launcher.runtime.GameSession
import net.millida.launcher.runtime.RuntimeBridge
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The ready-made window layer from the runtime pack. Everything below the window
 * lives in its native library: the surface, the graphics context, frames and the
 * input queue. The launcher only performs a fixed sequence of calls around it,
 * and the order is part of the contract, not a preference.
 *
 * With [sdl] the window belongs to SDL3 instead: the same Java machine and
 * renderer start the game, but the surface goes to SDL and the GLFW layer of
 * the pack is never handed a window.
 */
class PackRuntime(
    private val context: Context,
    private val runtime: InstalledRuntime,
    private val sessionLog: File,
    private val sdl: InstalledSdl? = null,
) {
    private val nativeDir: File = runtime.rendererDir
    private val javaHome: File = runtime.javaHome
    private var attached: Surface? = null
    private var attachedWidth = 0
    private var attachedHeight = 0
    private var graphics: SdlGraphics? = null

    private val translated: Boolean get() = graphics != SdlGraphics.NATIVE_GLES
    private var debugFlags: Map<String, String> = emptyMap()

    fun note(text: String) = GameSession.note(sessionLog, text)

    fun prepare(gameDir: File, windowWidth: Int, windowHeight: Int) {
        CallbackBridge.setContext(context)
        debugFlags = DebugFlags.read(gameDir)
        if (debugFlags.isNotEmpty()) note("отладочные флаги: $debugFlags")
        graphics = sdl?.let { SdlLaunch.graphics(gameDir) }
        if (graphics == SdlGraphics.NATIVE_GLES && debugFlags["angelicaGles"] != "off") {
            note("графика: GLES 3.2 телефона напрямую, Angelica в режиме ES, слой перевода не грузится")
            applyAngelicaGles(gameDir)
        }
        // The environment goes first: the library reads the renderer and the
        // graphics layer once, while it is being loaded.
        applyEnvironment(gameDir, windowWidth, windowHeight)
        loadLibraries()
        LoggerBridge.start(sessionLog.absolutePath)
        RuntimeBridge.guardSignals("after the window layer loaded")
        LoggerBridge.appendTitle("Millida: готовый оконный слой")
        ZLBridge.setLdLibraryPath(libraryPath())
        note("путь библиотек передан")
        openRuntimeLibraries()
        note("библиотеки рантайма и рендерер открыты")
        RuntimeBridge.guardSignals("after the runtime libraries opened")
        sdl?.let {
            SdlHost.load(it.library, ::note)
            RuntimeBridge.guardSignals("after SDL loaded")
        }
    }

    private fun applyAngelicaGles(gameDir: File) {
        runCatching { AngelicaGlesSettings.apply(gameDir) }
            .onSuccess { changed -> if (changed) note("настройки Angelica переведены на GLES: отрисовка чанков INDIVIDUAL, проверка ошибок GL включена") }
            .onFailure { note("настройки Angelica для GLES не записаны, мир может не рисоваться: ${it.message}") }
    }

    /**
     * The window is handed over once. The same surface must not be handed twice:
     * the graphics layer is already connected to it, and a second attempt only
     * destroys the working one.
     */
    fun attachSurface(surface: Surface, width: Int, height: Int) {
        if (sdl != null) {
            attached = surface
            attachedWidth = width
            attachedHeight = height
            SdlHost.surfaceChanged(surface, width, height)
            note("поверхность отдана SDL3, размер $width на $height")
            return
        }
        // A repeated report of the same size makes the game run its resize
        // handler on a foreign thread, and after that it stops drawing.
        if (attached === surface && width == attachedWidth && height == attachedHeight) return
        // A turned screen is a different window even when the object is the
        // same: the graphics layer keeps the geometry it was given, and the
        // picture comes out sideways if it is not handed over again.
        // The previous window is not released here: the render thread keeps
        // drawing into it until its next frame picks up the new one, and a
        // release from this thread can free it under that frame.
        ZLBridge.setupBridgeWindow(surface)
        attached = surface
        attachedWidth = width
        attachedHeight = height
        PackInputSink.windowSize(width, height) { note("размер окна не ушёл: ${it.message}") }
        note("поверхность отдана мосту, размер $width на $height")
    }

    /**
     * Going to another application takes the window away. The graphics layer has
     * to be told, otherwise it keeps drawing into a surface that no longer exists
     * and the game comes back to a black screen.
     */
    fun detachSurface() {
        if (attached == null) return
        attached = null
        attachedWidth = 0
        attachedHeight = 0
        if (sdl != null) {
            SdlHost.surfaceDestroyed()
            note("окно SDL3 отпущено")
            return
        }
        runCatching { ZLBridge.releaseBridgeWindow() }
            .onFailure { note("окно не отпущено: ${it.message}") }
        note("окно отпущено")
    }

    fun start(
        spec: LaunchSpec,
        heapMb: Int,
        windowWidth: Int,
        windowHeight: Int,
        bridgeJars: List<File>,
    ): Int {
        if (jvmStarted.get()) throw IOException(SECOND_START)
        // Without the queue the window layer keeps only the current state of the
        // buttons, and a short tap falls between two frames of the game.
        CallbackBridge.nativeSetUseInputStackQueue(false)
        runCatching { ZLBridge.setupExitMethod(context.applicationContext) }
            .onFailure { note("выход из игры не перехвачен: ${it.message}") }
        ZLBridge.chdir(spec.gameDir.absolutePath)

        val argv = arguments(spec, heapMb, windowWidth, windowHeight, bridgeJars)
        note("запуск виртуальной машины, аргументов ${argv.size}")
        LoggerBridge.appendTitle("JVM Args")
        argv.forEach { LoggerBridge.append(it) }

        RuntimeBridge.guardSignals("before the virtual machine")
        if (!jvmStarted.compareAndSet(false, true)) throw IOException(SECOND_START)
        val code = VMLauncher.launchJVM(argv)
        note("виртуальная машина вышла с кодом $code")
        return code
    }

    /**
     * Only what the libraries themselves declare as dependencies, in that order.
     * The exit hook and its call interceptor are left out on purpose: the
     * interceptor expects classes of the launcher it was written for and aborts
     * the process without them, while the exit code already comes back from the
     * virtual machine itself.
     */
    private fun loadLibraries() {
        val required = "libpojavexec.so"
        listOf("libdriver_helper.so", required)
            .forEach { name ->
                val file = File(nativeDir, name)
                if (!file.isFile) {
                    if (name == required) throw IOException("в пакете рантайма нет $name — обнови пакет")
                    note("нет $name, пропускаем")
                    return@forEach
                }
                note("загружаем $name")
                try {
                    System.load(file.absolutePath)
                    note("загружено $name")
                } catch (error: Throwable) {
                    if (name == required) throw error
                    note("$name не загрузилась: ${error.message}")
                }
            }
        val inputReady = runCatching { CallbackBridge.getCurrentFps() }.isSuccess
        note("мост ввода отвечает: $inputReady")
    }

    private fun applyEnvironment(gameDir: File, windowWidth: Int, windowHeight: Int) {
        val cache = File(context.cacheDir, "runtime").apply { mkdirs() }
        val environment = LinkedHashMap<String, String>()
        environment["POJAV_NATIVEDIR"] = nativeDir.absolutePath
        environment["JAVA_HOME"] = javaHome.absolutePath
        environment["HOME"] = gameDir.absolutePath
        // The JVM reports Linux here, so mods expect the XDG folders to exist:
        // lwjgl3ify kills the game on start when $HOME/.local/share is missing.
        environment["XDG_DATA_HOME"] = File(gameDir, ".local/share").apply { mkdirs() }.absolutePath
        environment["XDG_CONFIG_HOME"] = File(gameDir, ".config").apply { mkdirs() }.absolutePath
        environment["XDG_CACHE_HOME"] = File(gameDir, ".cache").apply { mkdirs() }.absolutePath
        environment["TMPDIR"] = cache.absolutePath
        environment["LD_LIBRARY_PATH"] = libraryPath()
        environment["PATH"] = File(javaHome, "bin").absolutePath + ":" + (System.getenv("PATH") ?: "")
        environment["ALSOFT_DRIVERS"] = "opensl"
        environment["AWTSTUB_WIDTH"] = windowWidth.toString()
        environment["AWTSTUB_HEIGHT"] = windowHeight.toString()
        val rendererNames = RendererVariable.names(
            windowLayerRenamed = RendererVariable.windowLayerRenamed(nativeDir),
            bridgeRenamed = sdl != null || RendererVariable.bridgeRenamed(runtime.bridgeClasspath),
            sdl = sdl != null,
        )
        rendererNames.forEach { environment[it] = rendererId() }
        if (RendererVariable.LEGACY in rendererNames) {
            note("рантайм читает рендерер из ${RendererVariable.LEGACY}: Sodium с ним не запустится, нужен свежий пакет рантайма")
        }
        // The name is asked for the software driver only. For the desktop layer
        // the graphics come through its own EGL, and a library name here makes
        // the native side open the wrong thing.
        environment["LIB_MESA_NAME"] =
            runtime.renderer.library.takeIf { it.contains("OSMesa") } ?: "null"
        environment["LIBGL_DRIVERS_PATH"] = nativeDir.absolutePath
        runtime.renderer.eglLibrary.takeIf { it.isNotEmpty() }?.let { egl ->
            environment["POJAVEXEC_EGL"] = egl
            environment["EGL_NAME"] = egl
        }
        environment.putAll(rendererEnvironment())
        environment.putAll(runtime.renderer.environment)
        if (mobileGlues) environment.putAll(mobileGluesEnvironment(gameDir))
        graphics?.let { environment.putAll(SdlLaunch.environment(rendererFile(), it, rendererEgl())) }
        if (!translated) runCatching { Os.unsetenv(SdlLaunch.HINT_OPENGL_LIBRARY) }
        graphics?.let { mode ->
            val shim = if (debugFlags["glesShim"] == "off") {
                emptyMap()
            } else {
                SdlLaunch.glesShim(File(context.applicationInfo.nativeLibraryDir, SdlLaunch.GLES_SHIM), mode)
            }
            if (mode == SdlGraphics.NATIVE_GLES && shim.isEmpty()) note("нет ${SdlLaunch.GLES_SHIM}: текстуры BGRA на GLES останутся пустыми")
            environment.putAll(shim)
        }

        debugFlags.filterKeys { it.startsWith(DebugFlags.ENV_PREFIX) }
            .forEach { (key, value) -> environment[key.removePrefix(DebugFlags.ENV_PREFIX)] = value }
        environment.forEach { (key, value) ->
            runCatching { Os.setenv(key, value, true) }
                .onFailure { note("переменная $key не поставилась: ${it.message}") }
        }
        note("окружение поставлено, рендерер ${rendererId()}")
    }

    private fun rendererId(): String = packRendererId(runtime.renderer.nativeId, runtime.renderer.library)

    private val mobileGlues: Boolean get() = RendererKind.of(runtime.renderer) == RendererKind.MOBILEGLUES

    private fun rendererEgl(): File? =
        runtime.renderer.eglLibrary.takeIf { mobileGlues && it.isNotEmpty() }?.let { File(nativeDir, it) }

    private fun mobileGluesEnvironment(gameDir: File): Map<String, String> {
        val folder = MobileGluesSetup.folder(gameDir)
        runCatching { MobileGluesSetup.writeConfig(folder) }
            .onSuccess { written -> if (written) note("настройки MobileGlues записаны: ${folder.absolutePath}") }
            .onFailure { note("настройки MobileGlues не записаны, он возьмёт свои по умолчанию: ${it.message}") }
        return MobileGluesSetup.environment(folder)
    }

    private fun rendererEnvironment(): Map<String, String> {
        val cache = File(context.cacheDir, "shaders").apply { mkdirs() }
        return packRendererEnvironment(rendererId(), cache.absolutePath)
    }

    /**
     * The first three entries are not a preference: the launcher inside the
     * runtime builds exactly this prefix itself and, when the environment does
     * not already start with it, sets the variable and restarts itself through
     * "bin/java" — which a phone forbids, so the game never starts.
     */
    private fun rendererFile(): File? =
        runtime.renderer.library.takeIf { it.isNotEmpty() }?.let { File(nativeDir, it) }

    /**
     * A module the SDL3 component carries is left to it: the runtime pack's
     * build of the same library lacks functions LWJGL 3.4 binds, and a second
     * -D of the same name would override the component's.
     */
    private fun namedLibrary(module: String, file: String): String? {
        if (sdl != null && module in SdlLaunch.bundledLibraries(sdl)) return null
        val candidate = File(nativeDir, file)
        if (!candidate.isFile) return null
        return "-Dorg.lwjgl.$module.libname=${candidate.absolutePath}"
    }

    private fun libraryPath(): String {
        val lib = JavaHomeLayout.nativeDir(javaHome)
        val parts = ArrayList<String>()
        parts += File(lib, "server").absolutePath
        parts += lib.absolutePath
        parts += File(javaHome.parentFile, JavaHomeLayout.nativeDirName(javaHome)).absolutePath
        parts += File(lib, "jli").absolutePath
        parts += nativeDir.absolutePath
        parts += context.applicationInfo.nativeLibraryDir
        parts += "/system/lib64"
        parts += "/vendor/lib64"
        parts += "/vendor/lib64/hw"
        parts += "/system_ext/lib64"
        return parts.joinToString(":")
    }

    private fun openRuntimeLibraries() {
        // The order matters: the loader libraries of the runtime come first,
        // then everything else it carries, then the renderer.
        val ordered = listOf(
            "libjli.so",
            "libjvm.so",
            "libverify.so",
            "libjava.so",
            "libnet.so",
            "libnio.so",
            "libawt.so",
            "libawt_headless.so",
            "libfreetype.so",
            "libfontmanager.so",
        )
        val lib = JavaHomeLayout.nativeDir(javaHome)
        ordered.forEach { name ->
            val direct = File(lib, name)
            val inServer = File(File(lib, "server"), name)
            val inJli = File(File(lib, "jli"), name)
            val file = listOf(direct, inServer, inJli).firstOrNull { it.isFile } ?: return@forEach
            ZLBridge.dlopen(file.absolutePath)
        }
        lib.walkTopDown().filter { it.isFile && it.extension == "so" }.forEach {
            ZLBridge.dlopen(it.absolutePath)
        }

        // The window part of AWT is opened without the loader: the game never
        // asks for a desktop window, and its own start-up sequence waits for a
        // screen that never appears. The libraries in front of the renderer are
        // its own dependencies: the linker looks them up by name only among what
        // is already open, and the pack lives outside its search path.
        val support = listOf(
            "libc++_shared.so",
            "libcutils.so",
            "libglapi.so",
            "libspirv-cross-c-shared.so",
            "libshaderconv.so",
        )
        listOf("libpojavexec_awt.so", "libopenal.so").forEach { name ->
            File(nativeDir, name).takeIf { it.isFile }?.let { ZLBridge.dlopen(it.absolutePath) }
        }

        // The graphics layer looks its own dependencies up by name, and the
        // linker only sees what the application itself has opened - a plain
        // dlopen from the runtime puts them out of its reach.
        (support + runtime.renderer.eglLibrary.takeIf { translated }.orEmpty()).forEach { name ->
            if (name.isEmpty()) return@forEach
            val file = File(nativeDir, name)
            if (!file.isFile) return@forEach
            val loaded = runCatching { System.load(file.absolutePath) }.isSuccess
            if (!loaded) {
                note("$name загружена только внутри рантайма")
                ZLBridge.dlopen(file.absolutePath)
            }
        }
        val renderer = File(nativeDir, runtime.renderer.library)
        if (translated && runtime.renderer.library.isNotEmpty() && renderer.isFile) {
            if (!ZLBridge.dlopen(renderer.absolutePath)) {
                note("рендерер ${renderer.name} не открылся")
            }
        }
    }

    private fun arguments(
        spec: LaunchSpec,
        heapMb: Int,
        windowWidth: Int,
        windowHeight: Int,
        bridgeJars: List<File>,
    ): Array<String> {
        val cache = File(context.cacheDir, "runtime").apply { mkdirs() }
        val out = ArrayList<String>()
        out += File(File(javaHome, "bin"), "java").absolutePath

        out += "-Djava.home=${javaHome.absolutePath}"
        out += "-Djava.io.tmpdir=${cache.absolutePath}"
        out += "-Duser.home=${spec.gameDir.absolutePath}"
        out += "-Duser.language=${java.util.Locale.getDefault().language}"
        out += "-Dos.name=Linux"
        out += "-Dos.version=Android-${android.os.Build.VERSION.RELEASE}"
        out += "-Djna.boot.library.path=${nativeDir.absolutePath}"
        out += "-Dorg.lwjgl.librarypath=" + (sdl?.let { SdlLaunch.libraryPath(it, nativeDir) } ?: nativeDir.absolutePath)
        sdl?.let { out += SdlLaunch.systemProperties(it, graphics ?: SdlGraphics.TRANSLATED) }
        debugFlags["jvm"]?.split(' ')?.filter { it.isNotBlank() }?.let { out += it }
        out += "-Dorg.lwjgl.system.allocator=system"
        SdlLaunch.vulkanLoader(sdl != null)?.let { out += it }
        out += "-Dorg.lwjgl.opengl.libname=" + SdlLaunch.openglLibrary(File(nativeDir, runtime.renderer.library), graphics)
        out += "-Dorg.lwjgl.openal.libname=${File(nativeDir, "libopenal.so").absolutePath}"
        out += "-Dorg.lwjgl.freetype.libname=${File(nativeDir, "libfreetype.so").absolutePath}"
        // Newer versions of the game ask for these by their own names, and the
        // pack carries them under the names its own build used.
        namedLibrary("spvc", "libspirv-cross-c-shared.so")?.let { out += it }
        namedLibrary("shaderc", "libshaderc.so")?.let { out += it }
        namedLibrary("vma", "liblwjgl_vma.so")?.let { out += it }
        out += "-Dglfwstub.windowWidth=$windowWidth"
        out += "-Dglfwstub.windowHeight=$windowHeight"
        out += "-Dglfwstub.initEgl=false"
        out += "-Dlog4j2.formatMsgNoLookups=true"
        out += "-Djdk.lang.Process.launchMechanism=FORK"
        out += "-Dfml.earlyprogresswindow=false"
        out += "-Dfml.ignoreInvalidMinecraftCertificates=true"
        out += "-Dfml.ignorePatchDiscrepancies=true"
        out += "-Dsodium.checks.issue2561=false"
        out += "-Dnet.minecraft.clientmodname=millida-mobile"
        out += "-Dminecraft.launcher.brand=millida-mobile"
        out += "-Dminecraft.launcher.version=1.0"
        out += "-XX:+UnlockExperimentalVMOptions"
        out += "-XX:+UseSerialGC"
        out += "-XX:-OmitStackTraceInFastThrow"
        out += "-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors()}"
        out += "-Xms${(heapMb / 2).coerceAtLeast(128)}M"
        out += "-Xmx${heapMb}M"

        // Anything the version description asks for, minus what belongs to the
        // launcher: paths, memory and the class path are decided here.
        spec.jvmArgs.forEach { argument ->
            if (!isLauncherOwnedJvmArg(argument)) out += argument
        }

        // The window layer of the pack must come before the game libraries:
        // its classes replace the desktop ones the game expects to find.
        out += "-cp"
        val classpath = sdl?.let { SdlLaunch.classpath(it, spec.classpath) } ?: (bridgeJars + spec.classpath)
        out += classpath.joinToString(":") { it.absolutePath }
        out += spec.mainClass
        out += spec.gameArgs
        debugFlags["game"]?.split(' ')?.filter { it.isNotBlank() }?.let { out += it }

        return out.toTypedArray()
    }

    private companion object {
        /** A process hosts one Java machine: a second launchJVM here crashes instead of starting a game. */
        val jvmStarted = AtomicBoolean(false)
        const val SECOND_START = "после выхода из игры перезапусти приложение — виртуальная машина Java поднимается один раз"
    }
}

/**
 * The identifier is what the native side switches on, and it is not the same
 * thing as the library file name: the older translation layer answers to
 * "opengles2", the newer one to "opengles3".
 */
internal fun packRendererId(nativeId: String, library: String): String {
    if (nativeId.isNotEmpty()) return nativeId
    return when {
        library.contains("ng_gl4es") -> "opengles3"
        library.contains("gl4es") -> "opengles2"
        library.contains("glxshim") -> "opengles3_desktopgl_zink_kopper"
        library.contains("OSMesa_8") -> "gallium_freedreno"
        library.contains("OSMesa_2300d") -> "gallium_panfrost"
        library.contains("OSMesa_2121") -> "gallium_virgl"
        else -> "opengles2"
    }
}

/**
 * Each translation layer wants its own environment. The desktop one runs
 * through the graphics driver and needs the version overrides, otherwise the
 * game asks for a profile it will not get and draws nothing at all.
 */
internal fun packRendererEnvironment(id: String, shaderCache: String): Map<String, String> {
    if (id.contains("zink")) {
        return mapOf(
            "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
            "GALLIUM_DRIVER" to "zink",
            "POJAV_ZINK_PREFER_SYSTEM_DRIVER" to "1",
            "MESA_GL_VERSION_OVERRIDE" to "4.6",
            "MESA_GLSL_VERSION_OVERRIDE" to "460",
            "MESA_GLSL_CACHE_DIR" to shaderCache,
            "force_glsl_extensions_warn" to "true",
            "allow_higher_compat_version" to "true",
            "allow_glsl_extension_directive_midshader" to "true",
        )
    }
    if (id.startsWith("gallium")) {
        return mapOf(
            "MESA_GL_VERSION_OVERRIDE" to "4.6",
            "MESA_GLSL_VERSION_OVERRIDE" to "460",
            "MESA_GLSL_CACHE_DIR" to shaderCache,
            "force_glsl_extensions_warn" to "true",
            "allow_higher_compat_version" to "true",
            "allow_glsl_extension_directive_midshader" to "true",
        )
    }
    if (!id.startsWith("opengles2")) {
        return mapOf("LIBGL_ES" to "3")
    }
    return mapOf(
        "LIBGL_ES" to "2",
        "LIBGL_MIPMAP" to "3",
        "LIBGL_NOERROR" to "1",
        "LIBGL_NOINTOVLHACK" to "1",
        "LIBGL_NORMALIZE" to "1",
    )
}

/**
 * Anything the version description asks for, minus what belongs to the
 * launcher: paths, memory and the class path are decided by the launcher.
 */
internal fun isLauncherOwnedJvmArg(argument: String): Boolean =
    argument.startsWith("-Xms") ||
        argument.startsWith("-Xmx") ||
        argument.startsWith("-Djava.library.path") ||
        argument.startsWith("-Djava.class.path") ||
        argument.startsWith("-Dorg.lwjgl.") ||
        argument.startsWith("-Djna.") ||
        argument.startsWith("-Djava.home") ||
        argument.startsWith("-Djava.io.tmpdir") ||
        argument.startsWith("-Duser.home") ||
        argument.startsWith("-XX:MaxMetaspaceSize") ||
        argument.startsWith("-XX:TieredStopAtLevel") ||
        argument.startsWith("-Dmillida.")

/**
 * Debug builds only: a properties file in the build's folder switches parts of
 * the start on and off from adb, so a device experiment needs no new build.
 */
private object DebugFlags {
    const val FILE = "millida-debug.properties"
    const val ENV_PREFIX = "env."

    fun read(gameDir: File): Map<String, String> {
        if (!BuildConfig.DEBUG) return emptyMap()
        val file = File(gameDir, FILE)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val properties = java.util.Properties()
            file.inputStream().use { properties.load(it) }
            properties.stringPropertyNames().associateWith { properties.getProperty(it).trim() }
        }.getOrDefault(emptyMap())
    }
}
