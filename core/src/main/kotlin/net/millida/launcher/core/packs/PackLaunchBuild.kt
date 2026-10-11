package net.millida.launcher.core.packs

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.launch.LaunchRequest
import net.millida.launcher.core.launch.LaunchSpec
import net.millida.launcher.core.launch.LaunchSpecBuilder
import java.io.IOException

/**
 * Turns a pack's own launch description into the same launch the phone builds
 * for every game: placeholders, memory, sign-in agent and the player's flags
 * stay in [LaunchSpecBuilder], and the pack differs only by its main class,
 * class path and arguments. The server is not appended: the pack's own
 * arguments decide how it joins, as on the desktop.
 */
object PackLaunchBuild {

    @Throws(IOException::class)
    fun build(
        layout: GameLayout,
        request: LaunchRequest,
        spec: PackLaunch,
        tokenArgs: List<String>,
    ): LaunchSpec {
        if (spec.needsToken && tokenArgs.isEmpty()) {
            throw IOException("ключ запуска выдаётся на один старт — вернись в лаунчер и нажми «Играть»")
        }
        val classpath = PackLaunches.classpath(request.gameDir, spec)
        val ownAssets = spec.vanillaAssets.isEmpty()
        val assetsRoot = if (ownAssets) SafePath.join(request.gameDir, spec.assetsDir).absolutePath else null
        val version = request.version.copy(
            id = if (spec.slug.isEmpty()) "millida-pack" else "pack-${spec.slug}",
            mainClass = spec.mainClass,
            assetsId = spec.assetIndex.ifEmpty { request.version.assetsId },
            libraries = emptyList(),
            jvmArguments = spec.jvmArgs + tokenArgs.filter { PackJvmArgs.allowed(it) },
            gameArguments = PackLaunches.gameArgsOrDefault(spec).map { argument ->
                if (assetsRoot != null) argument.replace("\${assets_root}", assetsRoot) else argument
            },
        )
        val built = LaunchSpecBuilder(layout).build(request.copy(version = version, quickPlayServer = null))
        return built.copy(classpath = classpath)
    }
}
