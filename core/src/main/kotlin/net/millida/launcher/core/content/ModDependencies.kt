package net.millida.launcher.core.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.mojang.MojangJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** The newest release that has a file to download, else the newest build of any kind. */
fun List<CatalogVersion>.newestInstallable(): CatalogVersion? {
    val usable = filter { it.primaryFile() != null }
    return usable.firstOrNull { it.versionType == "release" } ?: usable.firstOrNull()
}

/**
 * A mod the build cannot start without. [ids] are the names its dependants ask
 * for; [projectId] is set only for a library the launcher knows by its fixed
 * catalogue id and may therefore install itself.
 */
data class MissingMod(val name: String, val ids: List<String>, val requiredBy: List<String>, val projectId: String?)

/**
 * Fabric Loader refuses to start the game when a mod's hard dependency is not
 * in the mods folder, and that refusal is the most common failed start of a
 * modded build. The same check is made here before the game is started, from
 * the mods' own descriptions, so the missing mods can be installed instead.
 *
 * Everything read here is written by whoever made the mod. The check never
 * invents a missing mod: a folder with a description it cannot read answers
 * «nothing missing». A dependency is never looked up in the catalogue by the
 * name a mod gives it — a project can be registered under any free name — so
 * only libraries listed in [KNOWN] by their permanent project id are offered,
 * and nothing already in the folder is removed on the word of a description.
 */
object ModDependencies {
    private const val DESCRIPTION = "fabric.mod.json"
    private const val NESTED_FOLDER = "META-INF/"
    private const val JAR = ".jar"
    private const val MAX_DEPTH = 3
    private const val NAME_MAX_CHARS = 48
    private const val DESCRIPTION_MAX_BYTES = 1 shl 20
    private const val NESTED_MAX_BYTES = 192L * 1024 * 1024
    private const val NESTED_MAX_ENTRIES = 20_000

    /** Provided by the game and the loader themselves. */
    private val BUILT_IN = setOf("minecraft", "java", "fabricloader", "fabric-loader", "mixinextras", "mixin")

    /** Fabric API went by this id before it was split into modules, each of which is named like the pattern. */
    private val FABRIC_API_MODULE = Regex("^fabric(?:-api-base|-[a-z0-9-]+-v\\d+)?$")

    private class Library(val name: String, val projectId: String)

    private val FABRIC_API = Library("fabric-api", "P7dR8mSH")

    /** Mod id to its Modrinth project. The project ids are permanent, unlike the names in the address. */
    private val KNOWN: Map<String, Library> = mapOf(
        "fabric-api" to FABRIC_API,
        "sodium" to Library("sodium", "AANobbMI"),
        "iris" to Library("iris", "YL57xq9U"),
        "lithium" to Library("lithium", "gvQqBUqZ"),
        "indium" to Library("indium", "Orvt0mRa"),
        "modmenu" to Library("modmenu", "mOgUt4GM"),
        "cloth-config" to Library("cloth-config", "9s6osm5g"),
        "cloth-config2" to Library("cloth-config", "9s6osm5g"),
        "fabric-language-kotlin" to Library("fabric-language-kotlin", "Ha28R6CL"),
        "architectury" to Library("architectury-api", "lhGA9TYQ"),
        "yet_another_config_lib_v3" to Library("yacl", "1eAoo2KR"),
        "geckolib" to Library("geckolib", "8BmcQJ2H"),
        "forgeconfigapiport" to Library("forge-config-api-port", "ohNO6lps"),
        "terrablender" to Library("terrablender", "kkmrDlKT"),
        "glitchcore" to Library("glitchcore", "s3dmwKy5"),
        "balm" to Library("balm", "MBAkmtvl"),
        "puzzleslib" to Library("puzzles-lib", "QAGBst4M"),
        "collective" to Library("collective", "e0M1UDsY"),
        "trinkets" to Library("trinkets", "5aaWibi9"),
        "placeholder-api" to Library("placeholder-api", "eXts2L7r"),
        "midnightlib" to Library("midnightlib", "codAaoxh"),
        "resourcefullib" to Library("resourceful-lib", "G1hIVOrD"),
        "bookshelf" to Library("bookshelf-lib", "uy4Cnpcm"),
        "moonlight" to Library("moonlight", "twkfQtEc"),
        "creativecore" to Library("creativecore", "OsZiaDHq"),
        "player-animator" to Library("playeranimator", "gedNE4y2"),
        "entity_texture_features" to Library("entitytexturefeatures", "BVzZfTc1"),
        "supermartijn642corelib" to Library("supermartijn642s-core-lib", "rOUBggPv"),
        "malilib" to Library("malilib", "GcWjdA9I"),
        "mru" to Library("mru", "SNVQ2c0g"),
    )

    /** Permanent Modrinth project of a library the launcher may install by itself, else null. */
    internal fun projectOf(id: String): String? = library(id.lowercase())?.projectId

    internal fun libraryName(id: String): String = library(id.lowercase())?.name ?: id

    private fun library(id: String): Library? = if (FABRIC_API_MODULE.matches(id)) FABRIC_API else KNOWN[id]

    fun missing(modsDir: File): List<MissingMod> {
        val jars = modsDir.listFiles { file -> file.isFile && file.name.endsWith(JAR, ignoreCase = true) }.orEmpty().sortedBy { it.name }
        val mods = ArrayList<Pair<File, Description>>()
        for (jar in jars) {
            val described = runCatching { describe(jar) }.getOrElse { return emptyList() } ?: continue
            mods += jar to described
        }
        val provided = HashSet<String>(BUILT_IN)
        mods.forEach { (_, mod) ->
            provided += mod.id
            provided += mod.provides
        }
        val absent = mods.flatMap { it.second.depends }.filterNot { it in provided }.toHashSet()
        // Most builds stop here. The jars a mod carries inside are opened only to look for
        // what is still unaccounted for: Fabric API keeps every one of its modules that way.
        if (absent.isNotEmpty()) {
            val budget = Budget()
            for ((jar, mod) in mods) {
                if (mod.nested.isEmpty()) continue
                val carried = runCatching { carriedBy(jar, mod, budget) }.getOrElse { return emptyList() }
                absent -= carried
                if (absent.isEmpty()) return emptyList()
            }
        }

        val wanted = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for ((_, mod) in mods) {
            for (dependency in mod.depends) {
                if (dependency !in absent) continue
                val name = library(dependency)?.name ?: dependency
                wanted.getOrPut(name) { ArrayList() } += dependency to shown(mod.name.ifBlank { mod.id })
            }
        }
        return wanted.map { (name, asked) ->
            val ids = asked.map { it.first }.distinct()
            MissingMod(name, ids, asked.map { it.second }.distinct(), library(ids.first())?.projectId)
        }.sortedByDescending { it.requiredBy.size }
    }

    /** A mod's name is its author's text and ends up in a dialog: one short line of it. */
    private fun shown(name: String): String = name.replace(UNPRINTABLE, " ").trim().take(NAME_MAX_CHARS)

    private val UNPRINTABLE = Regex("[\\s\\p{Cntrl}]+")

    private class Description(
        val id: String,
        val name: String,
        val provides: Set<String>,
        val depends: Set<String>,
        val nested: List<String>,
        val serverOnly: Boolean,
    )

    /** What reading the jars inside jars may cost in total: the files are someone else's and can be built to exhaust it. */
    private class Budget {
        private var bytes = NESTED_MAX_BYTES
        private var entries = NESTED_MAX_ENTRIES

        fun entry() {
            if (--entries < 0) throw IOException(tr("слишком много вложенных файлов"))
        }

        fun read(count: Int) {
            bytes -= count
            if (bytes < 0) throw IOException(tr("вложенные архивы слишком велики"))
        }
    }

    /** Null for a jar that is not a Fabric mod or is meant for a server only. */
    private fun describe(jar: File): Description? = ZipFile(jar).use { zip ->
        val entry = zip.getEntry(DESCRIPTION) ?: return null
        val description = parse(zip.getInputStream(entry).use { readDescription(it) })
        description.takeUnless { it.serverOnly }
    }

    private fun carriedBy(jar: File, mod: Description, budget: Budget): Set<String> = ZipFile(jar).use { zip ->
        val carried = HashSet<String>()
        for (path in mod.nested) {
            val nested = zip.getEntry(path) ?: continue
            zip.getInputStream(nested).use { stream -> carried += providedBy(stream, 1, budget) }
        }
        carried
    }

    /**
     * A nested jar is read as a stream, so its description may come after the jars
     * it carries itself: every jar under META-INF counts as carried.
     */
    private fun providedBy(stream: InputStream, depth: Int, budget: Budget): Set<String> {
        val provided = HashSet<String>()
        val zip = ZipInputStream(stream)
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val entry = zip.nextEntry ?: break
            budget.entry()
            val name = entry.name
            if (name == DESCRIPTION) {
                val description = parse(readDescription(zip))
                provided += description.id
                provided += description.provides
            } else if (depth < MAX_DEPTH && name.startsWith(NESTED_FOLDER) && name.endsWith(JAR)) {
                provided += providedBy(zip, depth + 1, budget)
            }
            // The rest of the entry is drained here, under the budget, instead of inside nextEntry.
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                budget.read(count)
            }
        }
        return provided
    }

    private fun readDescription(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 13)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            if (out.size() > DESCRIPTION_MAX_BYTES) throw IOException(tr("описание мода слишком велико"))
        }
        return out.toByteArray().decodeToString()
    }

    private fun parse(text: String): Description {
        val root = MojangJson.parseToJsonElement(text) as JsonObject
        val id = root.text("id").lowercase()
        require(id.isNotEmpty()) { tr("мод без id") }
        return Description(
            id = id,
            name = root.text("name"),
            provides = (root["provides"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() }.toSet(),
            depends = (root["depends"] as? JsonObject)?.keys.orEmpty().map { it.lowercase() }.toSet(),
            nested = (root["jars"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("file") as? JsonPrimitive)?.content },
            serverOnly = root.text("environment") == "server",
        )
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
