package net.millida.launcher.core.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.util.zip.ZipFile

/**
 * Looks at the mods of a build before the game starts and finds what makes Fabric, Quilt,
 * Forge or NeoForge refuse to start: a mod made for another game version or loader, two
 * files of one mod, a mod that wants another mod in a version that is not there, a declared
 * break. The answer is a list of mods to switch off, each with a short reason.
 *
 * Descriptions are written by mod authors and read as untrusted: a jar that cannot be read
 * or a range that is not understood is "no problem", never a reason to switch a mod off.
 * Mods missing from the folder are not repaired here; [missingForge] only names them
 * (Fabric and Quilt are covered by [ModDependencies], which also looks inside jars).
 */
object ModCompat {

    enum class Kind { GAME, LOADER, DUPLICATE, DEPENDENCY, BREAKS, CASCADE }

    /** [name] is the mod shown to the player, [other] the second party of the problem, [disable] the files to switch off. */
    data class Problem(val kind: Kind, val name: String, val other: String, val disable: List<File>) {
        fun text(): String = when (kind) {
            Kind.GAME -> tr("{0}: сделан для другой версии игры", name)
            Kind.LOADER -> tr("{0}: сделан для другого загрузчика", name)
            Kind.DUPLICATE -> tr("{0}: лежит два раза", name)
            Kind.DEPENDENCY -> tr("{0}: не подходит версия {1}", name, other)
            Kind.BREAKS -> tr("{0}: ломает {1}", name, other)
            Kind.CASCADE -> tr("{0}: без {1}, который выключен", name, other)
        }
    }

    private class Dep(val id: String, val range: String)

    private class Info(
        val file: File,
        val id: String,
        val name: String,
        val version: String,
        val loaders: Set<LoaderKind>,
        val provides: Set<String>,
        val depends: List<Dep>,
        val breaks: List<Dep>,
    )

    private const val META_MAX_BYTES = 1 shl 20
    private const val NESTED_MAX = 40
    private const val NAME_MAX_CHARS = 48
    private const val GAME = "minecraft"

    private val BUILT_IN = setOf(
        GAME, "java", "forge", "neoforge", "fml", "javafml", "lowcodefml", "mixin", "mixinextras",
        "fabricloader", "fabric-loader", "quilt_loader", "quilt_base", "quilted_fabric_api",
    )

    /** Mods that let one loader run the mods of another: their jars cross the loader check. */
    private val BRIDGES = setOf("connector", "sinytra_connector", "forgified_fabric_api")

    fun problems(modsDir: File, gameVersion: String, loader: LoaderKind): List<Problem> {
        if (loader == LoaderKind.VANILLA) return emptyList()
        val mods = read(modsDir, loader).filter { it.loaders.isNotEmpty() || it.id.isNotEmpty() }
        if (mods.isEmpty()) return emptyList()
        val bridged = mods.any { it.id in BRIDGES }
        val disabled = LinkedHashMap<File, Problem>()
        fun flag(info: Info, kind: Kind, other: String = "") {
            if (info.file !in disabled) disabled[info.file] = Problem(kind, shown(info), other, listOf(info.file))
        }

        for ((_, same) in mods.groupBy { it.id }) {
            if (same.size < 2) continue
            val keep = same.maxWithOrNull(
                Comparator<Info> { a, b -> ModVersionRange.compare(a.version, b.version) }
                    .thenBy { it.file.lastModified() }.thenBy { it.file.name },
            )
            same.filter { it !== keep }.forEach { flag(it, Kind.DUPLICATE) }
        }

        val versions = HashMap<String, MutableList<String>>()
        mods.forEach { versions.getOrPut(it.id) { ArrayList() } += it.version }

        for (mod in mods) {
            if (mod.file in disabled) continue
            if (!bridged && !runsOn(mod, loader, gameVersion)) {
                flag(mod, Kind.LOADER)
                continue
            }
            val game = mod.depends.firstOrNull { it.id == GAME }
            if (game != null && ModVersionRange.satisfies(game.range, gameVersion) == false) {
                flag(mod, Kind.GAME)
                continue
            }
            val wrong = mod.depends.firstOrNull { dep ->
                val have = versions[dep.id]?.firstOrNull { it.isNotBlank() } ?: return@firstOrNull false
                ModVersionRange.satisfies(dep.range, have) == false
            }
            if (wrong != null) {
                flag(mod, Kind.DEPENDENCY, ModDependencies.libraryName(wrong.id))
                continue
            }
            val broken = mod.breaks.firstOrNull { dep ->
                val have = versions[dep.id] ?: return@firstOrNull false
                dep.range.isBlank() || have.any { ModVersionRange.satisfies(dep.range, it) != false }
            }
            if (broken != null) flag(mod, Kind.BREAKS, ModDependencies.libraryName(broken.id))
        }

        // A mod switched off takes with it everything that cannot start without it.
        var changed = true
        while (changed) {
            changed = false
            for (mod in mods) {
                if (mod.file in disabled) continue
                val lost = mod.depends.firstOrNull { dep ->
                    if (dep.id in BUILT_IN) return@firstOrNull false
                    val providers = mods.filter { dep.id == it.id || dep.id in it.provides }
                    providers.isNotEmpty() && providers.all { it.file in disabled && !stillHeld(it, mods, disabled) }
                }
                if (lost != null) {
                    flag(mod, Kind.CASCADE, ModDependencies.libraryName(lost.id))
                    changed = true
                }
            }
        }
        return disabled.values.toList()
    }

    /** A disabled duplicate does not take its mod away: the copy that is kept still provides it. */
    private fun stillHeld(gone: Info, mods: List<Info>, disabled: Map<File, Problem>): Boolean =
        mods.any { it !== gone && it.id == gone.id && it.file !in disabled }

    /** Mandatory dependencies of Forge and NeoForge mods that no mod in the folder, and no jar inside one, provides. */
    fun missingForge(modsDir: File, loader: LoaderKind): List<MissingMod> {
        if (loader != LoaderKind.FORGE && loader != LoaderKind.NEOFORGE) return emptyList()
        val mods = read(modsDir, loader)
        if (mods.isEmpty()) return emptyList()
        val provided = HashSet<String>(BUILT_IN)
        mods.forEach {
            provided += it.id
            provided += it.provides
        }
        val absent = mods.flatMap { it.depends }.map { it.id }.filterNot { it in provided }.toHashSet()
        if (absent.isEmpty()) return emptyList()
        for (mod in mods) {
            absent -= nestedIds(mod.file)
            if (absent.isEmpty()) return emptyList()
        }
        val wanted = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (mod in mods) {
            for (dep in mod.depends) {
                if (dep.id !in absent) continue
                wanted.getOrPut(ModDependencies.libraryName(dep.id)) { ArrayList() } += dep.id to shown(mod)
            }
        }
        return wanted.map { (name, asked) ->
            val ids = asked.map { it.first }.distinct()
            MissingMod(name, ids, asked.map { it.second }.distinct(), ModDependencies.projectOf(ids.first()))
        }.sortedByDescending { it.requiredBy.size }
    }

    /** Quilt runs Fabric mods; NeoForge still ran Forge mods on 1.20.1 and stopped after it. */
    private fun runsOn(mod: Info, loader: LoaderKind, gameVersion: String): Boolean =
        mod.loaders.isEmpty() || loader in mod.loaders ||
            (loader == LoaderKind.QUILT && LoaderKind.FABRIC in mod.loaders) ||
            (loader == LoaderKind.NEOFORGE && LoaderKind.FORGE in mod.loaders && gameVersion == "1.20.1")

    private fun shown(mod: Info): String = mod.name.ifBlank { mod.id }.replace(Regex("[\\s\\p{Cntrl}]+"), " ").trim().take(NAME_MAX_CHARS)

    private fun read(modsDir: File, loader: LoaderKind): List<Info> =
        modsDir.listFiles { file -> file.isFile && file.name.endsWith(".jar", ignoreCase = true) }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull { runCatching { describe(it, loader) }.getOrNull() }

    // -- descriptions --------------------------------------------------------------------

    private fun describe(jar: File, loader: LoaderKind): Info? = ZipFile(jar).use { zip ->
        val fabric = text(zip, "fabric.mod.json")?.let { fabric(jar, it) }
        val quilt = text(zip, "quilt.mod.json")?.let { quilt(jar, it) }
        val neoforge = text(zip, "META-INF/neoforge.mods.toml")?.let { toml(jar, it, LoaderKind.NEOFORGE) }
        val forge = text(zip, "META-INF/mods.toml")?.let { toml(jar, it, LoaderKind.FORGE) }
        val found = listOfNotNull(fabric, quilt, neoforge, forge)
        if (found.isEmpty()) return null
        val own = when (loader) {
            LoaderKind.QUILT -> quilt ?: fabric
            LoaderKind.FABRIC -> fabric
            LoaderKind.NEOFORGE -> neoforge ?: forge
            LoaderKind.FORGE -> forge
            LoaderKind.VANILLA -> null
        }
        val chosen = own ?: found.first()
        if (chosen.id.isEmpty()) return null
        val loaders = found.flatMap { it.loaders }.toSet()
        // The descriptions of other loaders say nothing about whether this one can start the mod.
        if (own == null) Info(jar, chosen.id, chosen.name, chosen.version, loaders, chosen.provides, emptyList(), emptyList())
        else Info(jar, own.id, own.name, own.version, loaders, own.provides, own.depends, own.breaks)
    }

    private fun text(zip: ZipFile, path: String): String? {
        val entry = zip.getEntry(path) ?: return null
        if (entry.isDirectory || entry.size > META_MAX_BYTES) return null
        return zip.getInputStream(entry).use { it.readBytes().decodeToString() }
    }

    private fun fabric(jar: File, text: String): Info? {
        val root = MojangJson.parseToJsonElement(text) as? JsonObject ?: return null
        if (root.text("environment") == "server") return null
        val id = root.text("id").lowercase()
        if (id.isEmpty()) return null
        fun ranges(key: String): List<Dep> = (root[key] as? JsonObject).orEmptyMap().map { (dep, range) -> Dep(dep.lowercase(), versions(range)) }
        return Info(
            jar, id, root.text("name"), version(root.text("version")), setOf(LoaderKind.FABRIC),
            (root["provides"] as? JsonArray).orEmptyList().mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() }.toSet(),
            ranges("depends"), ranges("breaks"),
        )
    }

    private fun quilt(jar: File, text: String): Info? {
        val root = MojangJson.parseToJsonElement(text) as? JsonObject ?: return null
        val meta = root["quilt_loader"] as? JsonObject ?: return null
        val id = meta.text("id").lowercase()
        if (id.isEmpty()) return null
        fun entries(key: String, soft: Boolean): List<Dep> = (meta[key] as? JsonArray).orEmptyList().mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> Dep(element.content.substringAfterLast(':').lowercase(), "")
                is JsonObject -> {
                    if (soft && (element["optional"] as? JsonPrimitive)?.content == "true") return@mapNotNull null
                    Dep(element.text("id").substringAfterLast(':').lowercase(), element["versions"]?.let { versions(it) }.orEmpty())
                }
                else -> null
            }
        }.filter { it.id.isNotEmpty() }
        return Info(
            jar, id, (meta["metadata"] as? JsonObject)?.text("name").orEmpty(), version(meta.text("version")),
            setOf(LoaderKind.QUILT),
            (meta["provides"] as? JsonArray).orEmptyList().mapNotNull {
                ((it as? JsonObject)?.text("id") ?: (it as? JsonPrimitive)?.content)?.substringAfterLast(':')?.lowercase()
            }.toSet(),
            entries("depends", soft = true), entries("breaks", soft = false),
        )
    }

    private class Block(val owner: String, val values: MutableMap<String, String> = LinkedHashMap())

    private fun toml(jar: File, text: String, loader: LoaderKind): Info? {
        val mods = ArrayList<Block>()
        val deps = ArrayList<Block>()
        var current: Block? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                val header = line.trim('[', ']').trim()
                current = when {
                    header == "mods" -> Block("").also { mods += it }
                    header.startsWith("dependencies.") -> Block(header.removePrefix("dependencies.").trim('"', '\'').lowercase()).also { deps += it }
                    else -> null
                }
                continue
            }
            val block = current ?: continue
            val at = line.indexOf('=')
            if (at <= 0) continue
            block.values.putIfAbsent(line.substring(0, at).trim(), tomlValue(line.substring(at + 1).trim()))
        }
        val ids = mods.mapNotNull { it.values["modId"]?.lowercase()?.takeIf(String::isNotEmpty) }
        val id = ids.firstOrNull() ?: return null
        val first = mods.first()
        fun wanted(block: Block): Boolean = block.owner in ids && block.values["side"].orEmpty().uppercase() != "SERVER"
        val required = deps.filter { block ->
            wanted(block) && when (val type = block.values["type"]?.lowercase()) {
                null -> block.values["mandatory"] != "false"
                else -> type == "required"
            }
        }.map { Dep(it.values["modId"].orEmpty().lowercase(), it.values["versionRange"].orEmpty()) }
        val incompatible = deps.filter { wanted(it) && it.values["type"]?.lowercase() == "incompatible" }
            .map { Dep(it.values["modId"].orEmpty().lowercase(), it.values["versionRange"].orEmpty()) }
        return Info(
            jar, id, first.values["displayName"].orEmpty(), version(first.values["version"].orEmpty()), setOf(loader),
            ids.drop(1).toSet(), required.filter { it.id.isNotEmpty() }, incompatible.filter { it.id.isNotEmpty() },
        )
    }

    private fun tomlValue(raw: String): String {
        if (raw.startsWith("\"") || raw.startsWith("'")) {
            val quote = raw.first()
            val end = raw.indexOf(quote, 1)
            return if (end > 0) raw.substring(1, end) else raw.drop(1)
        }
        return raw.substringBefore('#').trim()
    }

    /** `${file.jarVersion}` and friends are filled in by the build, not by the author: no version. */
    private fun version(raw: String): String = if (raw.contains("\${")) "" else raw

    private fun versions(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.content
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(" || ")
        else -> ""
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun JsonArray?.orEmptyList(): List<JsonElement> = this ?: emptyList()

    private fun JsonObject?.orEmptyMap(): Map<String, JsonElement> = this ?: emptyMap()

    /** Mod ids of the jars Forge carries inside a mod (JarJar); read shallowly, with caps. */
    private fun nestedIds(jar: File): Set<String> = runCatching {
        ZipFile(jar).use { zip ->
            val ids = HashSet<String>()
            val entries = zip.entries().asSequence()
                .filter { it.name.startsWith("META-INF/jarjar/") && it.name.endsWith(".jar") && it.size in 1..NESTED_BYTES }
                .take(NESTED_MAX).toList()
            for (entry in entries) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val tmp = File.createTempFile("nested", ".jar")
                try {
                    tmp.writeBytes(bytes)
                    describe(tmp, LoaderKind.FORGE)?.let {
                        ids += it.id
                        ids += it.provides
                    }
                } finally {
                    tmp.delete()
                }
            }
            ids
        }
    }.getOrDefault(emptySet())

    private const val NESTED_BYTES = 48L * 1024 * 1024
}
