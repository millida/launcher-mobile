package net.millida.launcher.core.packs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.io.IOException

/**
 * A catalogue pack that brings its own game: its own libraries, its own client
 * jar, and mods loaded out of those libraries rather than out of `mods/`.
 * Rebuilding such a pack from parts gives a folder that starts without most of
 * the content, so the pack describes its own launch and the launcher runs that
 * description. Everything it names must stay inside the pack folder: the file
 * comes from an archive someone else prepared and decides a command line.
 */
data class PackLaunch(
    val slug: String,
    val mainClass: String,
    val assetIndex: String,
    val assetsDir: String,
    val nativesDir: String,
    val classPath: List<String>,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val vanillaAssets: String,
    val javaMajor: Int,
    val minRamMb: Int,
    val maxRamMb: Int,
    val needsToken: Boolean,
)

/** What the launch description of an unpacked archive or an installed build allows. */
enum class LaunchShape {
    PLAIN,

    /** The archive index names the file; it is read once the archive is unpacked. */
    OWN_DECLARED,
    OWN_READY,

    /** A readable description that asks for the Java 8 runtime the phone does not ship yet. */
    OWN_LEGACY,
    OWN_BROKEN,
}

object PackLaunches {
    const val DEFAULT_JAVA = 17
    const val MODERN_JAVA = 17
    private const val MAX_ENTRIES = 4000
    private const val MAX_GAME_ARGS = 64
    private const val MAX_ARG_LEN = 512
    private const val NATIVES_DEPTH = 8

    /** The phone is never one of the desktop keys, so desktop-only flags stay out. */
    val PHONE_PLATFORMS: List<String> = listOf("android-arm64", "android")

    private val DEFAULT_GAME_ARGS = listOf(
        "--username", "\${auth_player_name}",
        "--version", "\${version_name}",
        "--gameDir", "\${game_directory}",
        "--assetsDir", "\${assets_root}",
        "--assetIndex", "\${assets_index_name}",
        "--uuid", "\${auth_uuid}",
        "--accessToken", "\${auth_access_token}",
        "--userType", "\${user_type}",
        "--versionType", "\${version_type}",
    )

    /**
     * The phone brings its own window library: a desktop LWJGL jar from the
     * pack next to it makes the game report mismatched Java and native parts.
     * `lwjgl3ify` is a mod, not the library, and stays.
     */
    private val DESKTOP_LWJGL = Regex("^lwjgl([_-][a-z]+)*-\\d[\\w.+-]*\\.jar$")

    fun parse(raw: JsonElement?, platforms: List<String> = PHONE_PLATFORMS): PackLaunch? {
        val root = raw as? JsonObject ?: return null
        val mainClass = root.text("mainClass")
        if (!classNameOk(mainClass)) return null
        val gameArgs = gameArgs(root["gameArgs"]) ?: return null
        val vanillaAssets = root.text("vanillaAssets")
        if (!vanillaAssets.all { it.isLetterOrDigit() && it.code < 0x80 || it in ".-_" }) return null
        val classPath = root.list("classPath")
        if (classPath.isEmpty() || classPath.any { !relativePathOk(it) }) return null
        val assetsDir = root.text("assetsDir").ifEmpty { "assets" }
        val nativesDir = platformValue(root["nativesByPlatform"], platforms)
            ?.let { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.takeIf { it.isNotEmpty() }
            ?: root.text("nativesDir").ifEmpty { "natives" }
        if (!relativePathOk(assetsDir) || !relativePathOk(nativesDir)) return null
        val platformJvm = (platformValue(root["jvmArgsByPlatform"], platforms) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .orEmpty()
        val javaMajor = root.number("javaMajor")?.takeIf { it in 8..99 }?.toInt() ?: DEFAULT_JAVA
        return PackLaunch(
            slug = root.text("slug"),
            mainClass = mainClass,
            assetIndex = root.text("assetIndex"),
            assetsDir = assetsDir,
            nativesDir = nativesDir,
            classPath = classPath,
            jvmArgs = (root.list("jvmArgs") + platformJvm).filter { PackJvmArgs.allowed(it) },
            gameArgs = gameArgs,
            vanillaAssets = vanillaAssets,
            javaMajor = javaMajor,
            minRamMb = root.number("minRamMb")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            maxRamMb = root.number("maxRamMb")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            needsToken = (root["needsToken"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    fun read(dir: File): PackLaunch? {
        val file = File(dir, PhoneFit.PACK_LAUNCH_FILE)
        if (!file.isFile) return null
        val raw = try {
            MojangJson.parseToJsonElement(file.readText())
        } catch (broken: Exception) {
            return null
        }
        return parse(raw)
    }

    fun shape(dir: File, legacyRuntime: Boolean = PhoneFit.legacyRuntime()): LaunchShape {
        if (!File(dir, PhoneFit.PACK_LAUNCH_FILE).isFile) return LaunchShape.PLAIN
        val spec = read(dir) ?: return LaunchShape.OWN_BROKEN
        return if (spec.javaMajor < MODERN_JAVA && !legacyRuntime) LaunchShape.OWN_LEGACY else LaunchShape.OWN_READY
    }

    /**
     * The folder that holds the description: the archive root, or its one
     * wrapper folder, the same two depths the archive index is read at.
     */
    fun launchRoot(unpacked: File): File? {
        if (File(unpacked, PhoneFit.PACK_LAUNCH_FILE).isFile) return unpacked
        val entries = unpacked.listFiles().orEmpty()
        val wrapper = entries.singleOrNull()?.takeIf { it.isDirectory } ?: return null
        return wrapper.takeIf { File(it, PhoneFit.PACK_LAUNCH_FILE).isFile }
    }

    /**
     * The description is honoured only for a build the launcher itself
     * installed from our catalogue, and the slug comes from that install
     * record, never from the file: any mrpack can carry a file of this name.
     */
    fun trusted(profileDir: File): PackLaunch? {
        val origin = PackOriginStore.read(profileDir) ?: return null
        if (origin.source != PackSource.MILLIDA_CATALOG.id || !catalogSlugValid(origin.id)) return null
        return read(profileDir)?.copy(slug = origin.id)
    }

    /**
     * A catalogue build that carries a description the launcher cannot honour.
     * Starting it the plain way gives bare Minecraft instead of the pack, so
     * such a start is refused rather than quietly downgraded.
     */
    fun unusable(profileDir: File): Boolean {
        if (!File(profileDir, PhoneFit.PACK_LAUNCH_FILE).isFile) return false
        val origin = PackOriginStore.read(profileDir) ?: return false
        if (origin.source != PackSource.MILLIDA_CATALOG.id) return false
        return trusted(profileDir) == null
    }

    /**
     * Folders become every jar under them, ordered by path so two devices
     * build the same class path: with two jars defining one class, the order
     * decides which wins.
     */
    @Throws(IOException::class)
    fun classpath(profileDir: File, spec: PackLaunch): List<File> {
        val kept = everyJar(profileDir, spec).filterNot { DESKTOP_LWJGL.matches(it.name) || it.invariantSeparatorsPath.contains("/org/lwjgl/") }
        if (kept.isEmpty()) throw IOException("сборка не назвала ни одной библиотеки для запуска — напиши в поддержку")
        return kept
    }

    /**
     * The pack opens its window through SDL3 when its own class path carries
     * the LWJGL SDL binding or its natives folder carries the library. Read
     * before [classpath] drops the pack's LWJGL jars for the phone's own.
     */
    @Throws(IOException::class)
    fun needsSdl(profileDir: File, spec: PackLaunch): Boolean {
        if (everyJar(profileDir, spec).any { PhoneFit.sdlFile(it.name) }) return true
        val natives = try {
            SafePath.join(profileDir, spec.nativesDir)
        } catch (escape: IOException) {
            return false
        }
        if (!natives.isDirectory) return false
        return natives.walkTopDown().maxDepth(NATIVES_DEPTH).any { it.isFile && PhoneFit.sdlFile(it.name) }
    }

    private fun everyJar(profileDir: File, spec: PackLaunch): List<File> {
        val out = ArrayList<File>()
        for (entry in spec.classPath) {
            if (!relativePathOk(entry)) throw outside(entry)
            val joined = try {
                SafePath.join(profileDir, entry)
            } catch (escape: IOException) {
                throw outside(entry)
            }
            val path = if (joined.exists()) joined else ignoringCase(profileDir, entry) ?: joined
            when {
                path.isDirectory -> out += jarsUnder(profileDir, path)
                path.isFile -> out += path
                else -> throw IOException("в сборке нет файла «$entry» — переустанови её из каталога")
            }
            if (out.size > MAX_ENTRIES) throw IOException("в сборке слишком много библиотек — она собрана неверно, напиши в поддержку")
        }
        return out
    }

    fun gameArgsOrDefault(spec: PackLaunch): List<String> = spec.gameArgs.ifEmpty { DEFAULT_GAME_ARGS }

    /** Syntactic half of the path check; [SafePath.join] does the canonical half where the path is used. */
    fun relativePathOk(raw: String): Boolean {
        val cleaned = raw.replace('\\', '/')
        if (cleaned.isBlank() || cleaned.startsWith("/") || cleaned.contains(':')) return false
        var depth = 0
        for (segment in cleaned.split('/')) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment == ".." || segment.any { it.code < 0x20 }) return false
            depth++
        }
        return depth > 0
    }

    /**
     * Packs are put together on Windows, where `Libraries` and `libraries` are
     * one folder; the phone's disk tells them apart. Only a single
     * case-insensitive match counts, and the result stays inside the pack.
     */
    fun ignoringCase(profileDir: File, entry: String): File? {
        var current = profileDir
        for (segment in entry.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }) {
            val exact = File(current, segment)
            current = if (exact.exists()) {
                exact
            } else {
                current.listFiles()?.filter { it.name.equals(segment, ignoreCase = true) }?.singleOrNull() ?: return null
            }
        }
        return current.takeIf { SafePath.isInside(profileDir, it) }
    }

    private fun jarsUnder(profileDir: File, dir: File): List<File> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .onEach { if (!SafePath.isInside(profileDir, it)) throw outside(it.name) }
            .sortedWith { a, b -> compareSegments(a.relativeTo(dir).invariantSeparatorsPath, b.relativeTo(dir).invariantSeparatorsPath) }
            .toList()

    private fun compareSegments(a: String, b: String): Int {
        val left = a.split('/')
        val right = b.split('/')
        for (index in 0 until minOf(left.size, right.size)) {
            val order = left[index].compareTo(right[index])
            if (order != 0) return order
        }
        return left.size - right.size
    }

    private fun outside(entry: String) =
        IOException("сборка указала путь вне своей папки: «$entry» — запуск остановлен, напиши в поддержку")

    private fun classNameOk(name: String): Boolean =
        name.isNotEmpty() && name.all { (it.isLetterOrDigit() && it.code < 0x80) || it in ".\$_" }

    /**
     * Game arguments land after the main class verbatim, so a list that could
     * not come from a real description is refused whole rather than trimmed
     * into a different command.
     */
    private fun gameArgs(raw: JsonElement?): List<String>? {
        val list = raw as? JsonArray ?: return emptyList()
        if (list.size > MAX_GAME_ARGS) return null
        return list.map { item ->
            val primitive = item as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            val value = primitive.content
            if (value.isEmpty() || value.length > MAX_ARG_LEN || value.any { it.isISOControl() }) return null
            value
        }
    }

    private fun platformValue(table: JsonElement?, platforms: List<String>): JsonElement? {
        val map = table as? JsonObject ?: return null
        return platforms.firstNotNullOfOrNull { key -> map[key]?.takeUnless { it is kotlinx.serialization.json.JsonNull } }
    }

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.list(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
}

/**
 * JVM flags from outside the launcher: the pack's own and the launch token our
 * API issues. Anything that hands the JVM code to run or changes which classes
 * load is refused; a `-javaagent` in someone's archive would run on every start.
 */
object PackJvmArgs {
    private val BLOCKED_PREFIXES = listOf(
        "-javaagent", "-agentlib", "-agentpath", "-xrun", "-xbootclasspath",
        "-xx:onerror", "-xx:onoutofmemoryerror", "-xx:abortvmonexception", "-xx:vmoptionsfile",
        "-xx:flightrecorderoptions", "--class-path=", "--module-path=", "--upgrade-module-path=",
        "--patch-module=", "-djava.library.path", "-dlog4j.configurationfile", "-dlog4j2.configurationfile",
        "-dfabric.addmods", "-dfabric.loadmods", "-djava.system.class.loader", "-djava.security.manager",
        "-djava.security.policy", "-djdk.attach.allowattachself", "-xx:heapdumppath", "-xx:errorfile",
        "-xx:logfile", "-xx:startflightrecording", "-xlog", "-xloggc", "-xshare:", "-xx:sharedarchivefile",
    )

    private val BLOCKED_EXACT = setOf(
        "-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path", "--patch-module",
    )

    /**
     * RetroFuturaBootstrap (lwjgl3ify, 1.7.10 on a modern Java) needs its own
     * system class loader; its class lives in the pack's jars, whose mods run
     * their own code anyway, so exactly this value grants nothing extra.
     */
    private val PACK_EXACT = setOf(
        "-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader",
    )

    fun allowed(arg: String): Boolean {
        val value = arg.trim()
        if (value.any { it.isISOControl() }) return false
        if (value in PACK_EXACT) return true
        if (!value.startsWith("-")) return false
        val low = value.lowercase()
        return low !in BLOCKED_EXACT && BLOCKED_PREFIXES.none { low.startsWith(it) }
    }
}
