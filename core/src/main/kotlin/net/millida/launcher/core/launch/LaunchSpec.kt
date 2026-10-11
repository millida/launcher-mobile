package net.millida.launcher.core.launch

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.account.GameSession
import net.millida.launcher.core.mojang.Maven
import net.millida.launcher.core.mojang.ResolvedVersion
import java.io.File

data class LaunchRequest(
    val version: ResolvedVersion,
    val session: GameSession,
    val gameDir: File,
    val memoryMb: Int,
    val windowWidth: Int,
    val windowHeight: Int,
    val extraJvmArgs: List<String> = emptyList(),
    val extraGameArgs: List<String> = emptyList(),
    val authlibInjector: File? = null,
    val quickPlayServer: String? = null,
    val launcherName: String = "millida-mobile",
    val launcherVersion: String = "1.0",
)

data class LaunchSpec(
    val mainClass: String,
    val classpath: List<File>,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val gameDir: File,
) {
    fun classpathString(): String = classpath.joinToString(":") { it.absolutePath }
}

/**
 * log4j older than 2.10 resolves lookups inside chat messages and has no
 * switch to stop it, so only the version's own logging configuration helps
 * there. Newer builds are left alone: that configuration prints log events as
 * XML, which is meant for the official launcher and only clutters the game log.
 */
object Log4jGuard {
    private const val CORE = "org.apache.logging.log4j:log4j-core"
    private const val LOOKUPS_SWITCH_MINOR = 10

    fun needsConfig(version: ResolvedVersion): Boolean {
        if (version.logging?.file == null) return false
        val log4j = version.libraries.firstOrNull { Maven.groupArtifact(it.name) == CORE } ?: return false
        val parts = Maven.versionOf(log4j.name).split('.', '-')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return true
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return true
        return major < 2 || (major == 2 && minor < LOOKUPS_SWITCH_MINOR)
    }
}

class LaunchSpecBuilder(private val layout: GameLayout) {

    fun build(request: LaunchRequest): LaunchSpec {
        val version = request.version
        val classpath = ArrayList<File>()
        version.libraries.forEach { classpath += layout.library(it.path) }
        classpath += layout.versionJar(version.jarVersionId)

        val assetsRoot = layout.assets
        val nativesDir = File(layout.runtimes, "natives")
        val placeholders = mapOf(
            "auth_player_name" to request.session.nick,
            "auth_uuid" to request.session.uuid,
            // an empty token would be dropped together with its flag, and the
            // game then reads the next flag as a token value
            "auth_access_token" to request.session.accessToken.ifEmpty { "0" },
            "auth_session" to "token:${request.session.accessToken.ifEmpty { "0" }}:${request.session.uuid}",
            "auth_xuid" to request.session.xuid,
            "clientid" to "",
            "user_type" to request.session.userType,
            "version_name" to version.id,
            "version_type" to "Millida",
            "game_directory" to request.gameDir.absolutePath,
            "assets_root" to assetsRoot.absolutePath,
            "game_assets" to File(File(assetsRoot, "virtual"), version.assetsId).absolutePath,
            "assets_index_name" to version.assetsId,
            "resolution_width" to request.windowWidth.toString(),
            "resolution_height" to request.windowHeight.toString(),
            "launcher_name" to request.launcherName,
            "launcher_version" to request.launcherVersion,
            "natives_directory" to nativesDir.absolutePath,
            "library_directory" to layout.libraries.absolutePath,
            "classpath_separator" to ":",
            "classpath" to classpath.joinToString(":") { it.absolutePath },
            "user_properties" to "{}",
        )

        val jvmArgs = ArrayList<String>()
        jvmArgs += "-Xms${(request.memoryMb / 2).coerceAtLeast(128)}M"
        jvmArgs += "-Xmx${request.memoryMb}M"
        request.authlibInjector?.let { jar ->
            if (request.session.yggdrasil.isNotEmpty()) {
                jvmArgs += "-javaagent:${jar.absolutePath}=${request.session.yggdrasil}"
            }
        }
        // The native path is left to the runtime layer on purpose: it knows the
        // folder the libraries actually live in, and a later copy of the same
        // property would win over it inside the virtual machine.
        jvmArgs += resolveJvmArguments(version.jvmArguments, placeholders)
        loggingArgument(version)?.let { jvmArgs += it }
        jvmArgs += request.extraJvmArgs

        val gameArgs = ArrayList<String>(resolveGameArguments(version.gameArguments, placeholders))
        if (gameArgs.none { it == "--gameDir" }) {
            gameArgs += listOf("--gameDir", request.gameDir.absolutePath)
        }
        request.quickPlayServer?.let { address ->
            val host = address.substringBefore(':')
            val port = address.substringAfter(':', "25565")
            gameArgs += listOf("--quickPlayMultiplayer", "$host:$port")
        }
        gameArgs += request.extraGameArgs

        return LaunchSpec(
            mainClass = version.mainClass,
            classpath = classpath,
            jvmArgs = jvmArgs,
            gameArgs = gameArgs,
            gameDir = request.gameDir,
        )
    }

    private fun loggingArgument(version: ResolvedVersion): String? {
        if (!Log4jGuard.needsConfig(version)) return null
        val logging = version.logging ?: return null
        val file = logging.file ?: return null
        if (logging.argument.isEmpty() || file.id.isEmpty()) return null
        val config = layout.logConfig(file.id)
        if (!config.isFile) return null
        return logging.argument.replace("\${path}", config.absolutePath)
    }

    /**
     * The class path is passed to the virtual machine host as a property, so the
     * declared `-cp <classpath>` pair is dropped here — before substitution,
     * while the placeholder is still recognisable.
     */
    private fun resolveJvmArguments(raw: List<String>, values: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        var index = 0
        while (index < raw.size) {
            val token = raw[index]
            if (token == "-cp" || token == "-classpath" || token == "--class-path") {
                index += 2
                continue
            }
            if (token.startsWith("-Djava.library.path")) {
                index += 1
                continue
            }
            if (token.contains("\${classpath}")) {
                index += 1
                continue
            }
            val substituted = substitute(token, values)
            if (substituted.isNotBlank()) out += substituted
            index += 1
        }
        return out
    }

    /**
     * A flag whose value resolves to nothing is dropped with its value: the game
     * parses `--xuid --userType msa` as an xuid of "--userType".
     */
    private fun resolveGameArguments(raw: List<String>, values: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        var index = 0
        while (index < raw.size) {
            val token = raw[index]
            val substituted = substitute(token, values)
            val next = raw.getOrNull(index + 1)
            val nextIsPlaceholder = next != null && next.trim().startsWith("\${")
            if (token.startsWith("-") && nextIsPlaceholder) {
                val nextValue = substitute(next!!, values)
                if (nextValue.isBlank()) {
                    index += 2
                    continue
                }
                out += substituted
                out += nextValue
                index += 2
                continue
            }
            if (substituted.isNotBlank()) out += substituted
            index += 1
        }
        return out
    }

    private fun substitute(raw: String, values: Map<String, String>): String {
        if (!raw.contains("\${")) return raw
        var out = raw
        for ((key, value) in values) {
            out = out.replace("\${$key}", value)
        }
        return out
    }
}
