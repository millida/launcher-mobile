package net.millida.launcher.runtime

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.MemoryTuning
import net.millida.launcher.core.launch.LaunchSpec
import net.millida.launcher.core.runtime.InstalledRuntime
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

sealed interface GameState {
    data object Idle : GameState
    data class Starting(val note: String) : GameState
    data object Running : GameState
    data class Stopped(val reason: String?) : GameState
}

class GameRunner(
    private val context: Context,
    private val layout: GameLayout,
) {
    private val state = MutableStateFlow<GameState>(GameState.Idle)
    val gameState: StateFlow<GameState> = state

    val abi: String
        get() = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" } ?: Build.SUPPORTED_ABIS.first()

    // The desktop virtual machine can be created once per process; a second one crashes in JNI_CreateJavaVM.
    suspend fun start(
        spec: LaunchSpec,
        runtime: InstalledRuntime,
        heapMb: Int,
        logFile: File,
    ): Boolean {
        if (RuntimeBridge.running()) throw IOException("игра уже запущена")
        if (started.get()) {
            throw IOException("после выхода из игры перезапусти приложение — виртуальная машина Java поднимается один раз")
        }

        // Loading the library here, on a background thread, keeps the failure
        // catchable: the same call from the surface callback would land on the
        // main thread as an uncatchable UnsatisfiedLinkError.
        state.value = GameState.Starting("готовим мост")
        try {
            RuntimeBridge.load()
        } catch (error: Throwable) {
            state.value = GameState.Stopped("не загрузился мост: ${error.message}")
            throw IOException("не загрузился нативный мост: ${error.message}", error)
        }

        state.value = GameState.Starting("ждём поверхность отрисовки")
        if (!RuntimeBridge.awaitSurface()) {
            state.value = GameState.Stopped("экран игры не успел появиться")
            throw IOException("экран игры не открылся — попробуй снова")
        }

        val classpath = (runtime.bridgeClasspath + spec.classpath).joinToString(":") { it.absolutePath }
        val libraryPath = listOf(
            runtime.rendererDir.absolutePath,
            File(runtime.javaHome, "lib").absolutePath,
            context.applicationInfo.nativeLibraryDir,
        ).joinToString(":")

        // Handing over the exact file: the game looks the bridge up by name in
        // java.library.path, and whether that name resolves depends on how the
        // application was packaged.
        val bridgeLibrary = findBridgeLibrary()
        applyEnvironment(runtime, libraryPath, bridgeLibrary)

        val jvmArgs = ArrayList<String>()
        jvmArgs += "-Djava.class.path=$classpath"
        jvmArgs += "-Djava.library.path=$libraryPath"
        jvmArgs += "-Djna.boot.library.path=$libraryPath"
        // The library shipped inside the jna jar is built for desktop Linux and
        // asks for libc.so.6; the one from the runtime folder is the Android build.
        jvmArgs += "-Djna.nounpack=true"
        jvmArgs += "-Djna.nosys=false"
        // LWJGL looks for its own .so files through this property, not through
        // java.library.path
        jvmArgs += "-Dorg.lwjgl.librarypath=$libraryPath"
        jvmArgs += "-Dorg.lwjgl.system.allocator=system"
        jvmArgs += "-Dorg.lwjgl.opengl.libname=${runtime.renderer.library.ifEmpty { "libGL.so" }}"
        jvmArgs += "-Duser.home=${layout.root.absolutePath}"
        jvmArgs += "-Djava.io.tmpdir=${File(layout.caches, "tmp").apply { mkdirs() }.absolutePath}"
        jvmArgs += "-Dmillida.renderer=${runtime.renderer.id}"
        bridgeLibrary?.let { jvmArgs += "-Dmillida.bridge.lib=${it.absolutePath}" }
        jvmArgs += MemoryTuning.gcFlags(heapMb)
        jvmArgs += spec.jvmArgs.filterNot { it.startsWith("-Djava.class.path") }

        started.set(true)
        state.value = GameState.Running

        // A blocking native call must not sit on a shared dispatcher thread: it
        // holds the thread for the whole session.
        var failure: Throwable? = null
        val ok = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val thread = Thread({
                    val result = runCatching {
                        RuntimeBridge.startGame(
                            javaHome = runtime.javaHome.absolutePath,
                            jvmArgs = jvmArgs.toTypedArray(),
                            mainClass = spec.mainClass,
                            gameArgs = spec.gameArgs.toTypedArray(),
                            runtimeDir = layout.runtimes.absolutePath,
                            // The game writes logs/latest.log relative to the
                            // working directory, so it must be the build folder.
                            workDir = spec.gameDir.absolutePath,
                            logPath = logFile.absolutePath,
                        )
                    }
                    failure = result.exceptionOrNull()
                    if (continuation.isActive) continuation.resume(result.getOrDefault(false))
                }, "millida-game")
                thread.isDaemon = false
                thread.start()
                continuation.invokeOnCancellation { RuntimeBridge.requestClose() }
            }
        }

        val reason = when {
            ok -> null
            failure != null -> failure?.message ?: failure.toString()
            else -> "игра закрылась с ошибкой — журнал в ${logFile.name}"
        }
        state.value = GameState.Stopped(reason)
        return ok
    }

    private fun findBridgeLibrary(): File? {
        val name = "libmillida_bridge.so"
        val candidates = listOf(
            File(context.applicationInfo.nativeLibraryDir, name),
            File(File(context.applicationInfo.dataDir, "lib"), name),
        )
        return candidates.firstOrNull { it.isFile }
    }

    private fun applyEnvironment(
        runtime: InstalledRuntime,
        libraryPath: String,
        bridgeLibrary: File?,
    ) {
        RuntimeBridge.putEnv("HOME", layout.root.absolutePath)
        RuntimeBridge.putEnv("TMPDIR", File(layout.caches, "tmp").absolutePath)
        RuntimeBridge.putEnv("LD_LIBRARY_PATH", libraryPath)
        RuntimeBridge.putEnv("MILLIDA_RENDERER_DIR", runtime.rendererDir.absolutePath)
        RuntimeBridge.putEnv("MILLIDA_RENDERER_LIB", runtime.renderer.library)
        RuntimeBridge.putEnv("MILLIDA_EGL_LIB", runtime.renderer.eglLibrary)
        bridgeLibrary?.let { RuntimeBridge.putEnv("MILLIDA_BRIDGE_LIB", it.absolutePath) }
        runtime.renderer.environment.forEach { (key, value) -> RuntimeBridge.putEnv(key, value) }
    }

    private companion object {
        val started = AtomicBoolean(false)
    }
}
