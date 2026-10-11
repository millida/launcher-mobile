package net.millida.launcher.core.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.util.zip.ZipFile

enum class ArchiveShape {
    NOT_AN_ARCHIVE,
    MOD,
    RESOURCE_PACK,
    DATA_PACK,
    SHADER_PACK,
    UNKNOWN,
}

data class ContentFinding(val text: String, val strong: Boolean)

data class ContentFacts(
    val fileName: String,
    val shape: ArchiveShape,
    val modId: String = "",
    val title: String = "",
    val version: String = "",
    val loaders: Set<LoaderKind> = emptySet(),
    val gameVersionRange: String = "",
    val hasCode: Boolean = false,
    val hasModMetadata: Boolean = false,
    val findings: List<ContentFinding> = emptyList(),
    val unreadable: String = "",
) {
    val strongFindings: Int get() = findings.count { it.strong }

    val label: String get() = title.ifBlank { fileName }

    fun kind(): ContentKind? = when (shape) {
        ArchiveShape.MOD -> ContentKind.MOD
        ArchiveShape.RESOURCE_PACK -> ContentKind.RESOURCE_PACK
        ArchiveShape.DATA_PACK -> ContentKind.DATA_PACK
        ArchiveShape.SHADER_PACK -> ContentKind.SHADER
        ArchiveShape.NOT_AN_ARCHIVE, ArchiveShape.UNKNOWN -> null
    }

    /**
     * Quilt runs Fabric mods, so a Fabric jar in a Quilt build is fine; every
     * other crossing is the crash the player would otherwise meet in game.
     */
    fun runsOn(loader: LoaderKind): Boolean {
        if (shape != ArchiveShape.MOD) return true
        if (loaders.isEmpty()) return true
        if (loader == LoaderKind.VANILLA) return false
        if (loader in loaders) return true
        return loader == LoaderKind.QUILT && LoaderKind.FABRIC in loaders
    }

    fun loaderLabel(): String = loaders.joinToString(", ") { it.label }.ifBlank { "неизвестный загрузчик" }

    fun fitsGameVersion(gameVersion: String): Boolean? =
        GameVersionRange.satisfies(gameVersionRange, gameVersion)
}

/**
 * One pass over an archive answering what the file is, who built it and whether
 * it does things a mod has no business doing. Reading is capped: the scan is a
 * cheap first look before a launch, not an antivirus.
 */
object ContentInspector {

    private const val MAX_SCAN_BYTES = 96L * 1024 * 1024
    private const val MAX_ENTRIES = 4000
    private const val MAX_META_BYTES = 1 * 1024 * 1024
    private const val HEAD_BYTES = 512 * 1024

    private val ARCHIVE_EXTENSIONS = setOf("jar", "zip")
    private const val DISABLED_SUFFIX = ".disabled"

    private val MOD_DESCRIPTORS = setOf(
        "fabric.mod.json",
        "quilt.mod.json",
        "META-INF/mods.toml",
        "META-INF/neoforge.mods.toml",
        "mcmod.info",
    )

    private data class Marker(val needle: String, val reason: String, val strong: Boolean)

    private val MARKERS = listOf(
        Marker("defineClass", "грузит классы в обход загрузчика модов", true),
        Marker("pastebin.com", "тянет код с pastebin", true),
        Marker("discord.com/api/webhooks", "шлёт данные в Discord-вебхук", true),
        Marker("launcher_profiles.json", "читает файл сессии лаунчера", true),
        Marker("java/lang/Runtime", "запускает сторонние процессы", false),
        Marker("ProcessBuilder", "запускает сторонние процессы", false),
        Marker("raw.githubusercontent.com", "тянет файлы напрямую с GitHub", false),
        Marker("javax/crypto/Cipher", "расшифровывает вложенные данные", false),
        Marker("java/awt/Robot", "управляет мышью и клавиатурой", false),
    )

    private val HARMLESS_EXTENSIONS = listOf(".png", ".txt", ".json", ".lang", ".dat", ".bin")
    private val CLASS_MAGIC = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())

    fun inspect(file: File): ContentFacts {
        val name = file.name
        if (!file.isFile) return ContentFacts(name, ArchiveShape.NOT_AN_ARCHIVE, unreadable = "файла нет на месте")
        if (!hasArchiveExtension(name)) {
            return ContentFacts(name, ArchiveShape.NOT_AN_ARCHIVE, unreadable = "это не jar и не zip")
        }
        if (file.length() > MAX_SCAN_BYTES) {
            return ContentFacts(name, ArchiveShape.UNKNOWN, unreadable = "файл слишком большой для проверки")
        }
        return try {
            ZipFile(file).use { read(name, it) }
        } catch (broken: Exception) {
            ContentFacts(
                fileName = name,
                shape = ArchiveShape.NOT_AN_ARCHIVE,
                findings = listOf(ContentFinding("Файл не открывается как архив", true)),
                unreadable = "файл не открывается как архив",
            )
        }
    }

    private fun read(name: String, zip: ZipFile): ContentFacts {
        val names = ArrayList<String>()
        val findings = LinkedHashMap<String, ContentFinding>()
        var hasCode = false
        var index = 0

        for (entry in zip.entries()) {
            if (index++ >= MAX_ENTRIES) break
            if (entry.isDirectory) continue
            val path = entry.name
            names += path
            val head = runCatching {
                zip.getInputStream(entry).use { stream ->
                    val buffer = ByteArray(minOf(HEAD_BYTES.toLong(), maxOf(entry.size, 1L)).toInt())
                    var read = 0
                    while (read < buffer.size) {
                        val step = stream.read(buffer, read, buffer.size - read)
                        if (step <= 0) break
                        read += step
                    }
                    buffer.copyOf(read)
                }
            }.getOrDefault(ByteArray(0))

            when {
                path.endsWith(".class") -> {
                    hasCode = true
                    val text = String(head, Charsets.ISO_8859_1)
                    for (marker in MARKERS) {
                        if (text.contains(marker.needle)) {
                            findings.putIfAbsent(marker.reason, ContentFinding(marker.reason, marker.strong))
                        }
                    }
                }

                disguisedClass(path, head) -> findings.putIfAbsent(
                    "класс под видом ресурса",
                    ContentFinding("В архиве лежит класс под видом ресурса", true),
                )

                path.endsWith(".jar") && head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() ->
                    findings.putIfAbsent("вложенный jar", ContentFinding("Внутри лежит ещё один jar", false))
            }
        }

        val descriptor = describe(zip)
        val hasModMetadata = names.any { it in MOD_DESCRIPTORS }
        if (hasCode && !hasModMetadata) {
            findings.putIfAbsent(
                "код без описания",
                ContentFinding("Код есть, а описания мода нет — загрузчик такой файл не признаёт", true),
            )
        }

        return ContentFacts(
            fileName = name,
            shape = shapeOf(hasModMetadata, hasCode, names),
            modId = descriptor.modId,
            title = descriptor.title,
            version = descriptor.version,
            loaders = descriptor.loaders,
            gameVersionRange = descriptor.gameVersionRange,
            hasCode = hasCode,
            hasModMetadata = hasModMetadata,
            findings = findings.values.toList(),
        )
    }

    private fun shapeOf(hasModMetadata: Boolean, hasCode: Boolean, names: List<String>): ArchiveShape = when {
        hasModMetadata -> ArchiveShape.MOD
        names.any { it.startsWith("shaders/") } -> ArchiveShape.SHADER_PACK
        names.any { it.startsWith("data/") } && names.any { it == "pack.mcmeta" } -> ArchiveShape.DATA_PACK
        names.any { it == "pack.mcmeta" } -> ArchiveShape.RESOURCE_PACK
        hasCode -> ArchiveShape.MOD
        else -> ArchiveShape.UNKNOWN
    }

    private data class Descriptor(
        val modId: String = "",
        val title: String = "",
        val version: String = "",
        val loaders: Set<LoaderKind> = emptySet(),
        val gameVersionRange: String = "",
    )

    private fun describe(zip: ZipFile): Descriptor {
        val fabric = entryText(zip, "fabric.mod.json")?.let { fabricDescriptor(it, LoaderKind.FABRIC) }
        val quilt = entryText(zip, "quilt.mod.json")?.let { quiltDescriptor(it) }
        val neoforge = entryText(zip, "META-INF/neoforge.mods.toml")?.let { tomlDescriptor(it, LoaderKind.NEOFORGE) }
        val forge = entryText(zip, "META-INF/mods.toml")?.let { tomlDescriptor(it, LoaderKind.FORGE) }

        val parts = listOfNotNull(fabric, quilt, neoforge, forge)
        if (parts.isEmpty()) return Descriptor()
        val first = parts.first()
        return Descriptor(
            modId = parts.firstOrNull { it.modId.isNotBlank() }?.modId.orEmpty(),
            title = parts.firstOrNull { it.title.isNotBlank() }?.title ?: first.modId,
            version = parts.firstOrNull { it.version.isNotBlank() }?.version.orEmpty(),
            loaders = parts.flatMap { it.loaders }.toSet(),
            gameVersionRange = parts.firstOrNull { it.gameVersionRange.isNotBlank() }?.gameVersionRange.orEmpty(),
        )
    }

    private fun fabricDescriptor(text: String, loader: LoaderKind): Descriptor? {
        val root = runCatching { MojangJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val id = root.string("id")
        if (id.isBlank()) return null
        return Descriptor(
            modId = id,
            title = root.string("name").ifBlank { id },
            version = root.string("version"),
            loaders = setOf(loader),
            gameVersionRange = root["depends"]?.let { depends ->
                (depends as? JsonObject)?.get("minecraft")?.let { versionRange(it) }
            }.orEmpty(),
        )
    }

    private fun quiltDescriptor(text: String): Descriptor? {
        val root = runCatching { MojangJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val meta = (root["quilt_loader"] as? JsonObject) ?: return null
        val id = meta.string("id")
        if (id.isBlank()) return null
        val minecraft = (meta["depends"] as? JsonArray)?.firstNotNullOfOrNull { entry ->
            val block = entry as? JsonObject ?: return@firstNotNullOfOrNull null
            if (block.string("id").substringAfterLast(':') != "minecraft") null else block["versions"]?.let { versionRange(it) }
        }
        return Descriptor(
            modId = id,
            title = (meta["metadata"] as? JsonObject)?.string("name")?.ifBlank { id } ?: id,
            version = meta.string("version"),
            loaders = setOf(LoaderKind.QUILT),
            gameVersionRange = minecraft.orEmpty(),
        )
    }

    private fun tomlDescriptor(text: String, loader: LoaderKind): Descriptor? {
        val block = firstModsBlock(text)
        val id = block["modId"]?.takeIf { it.isNotBlank() } ?: return null
        return Descriptor(
            modId = id,
            title = block["displayName"]?.takeIf { it.isNotBlank() } ?: id,
            version = block["version"].orEmpty(),
            loaders = setOf(loader),
            gameVersionRange = minecraftRangeFromToml(text),
        )
    }

    private fun versionRange(element: kotlinx.serialization.json.JsonElement): String = when (element) {
        is JsonPrimitive -> element.content
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(" || ")
        else -> ""
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content.orEmpty()

    private fun entryText(zip: ZipFile, path: String): String? {
        val entry = zip.getEntry(path) ?: return null
        if (entry.isDirectory || entry.size > MAX_META_BYTES) return null
        return runCatching {
            zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    private fun firstModsBlock(text: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        var inBlock = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                if (inBlock) break
                inBlock = line.startsWith("[[mods]]")
                continue
            }
            if (!inBlock) continue
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim()
            val value = tomlValue(line.substring(separator + 1).trim())
            if (value.isNotEmpty() && key !in values) values[key] = value
        }
        return values
    }

    /**
     * Forge writes the game requirement as a dependency block whose modId is
     * minecraft, so the range is found by walking blocks rather than by key.
     */
    private fun minecraftRangeFromToml(text: String): String {
        var inDependency = false
        var isMinecraft = false
        var range = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                if (isMinecraft && range.isNotEmpty()) return range
                inDependency = line.startsWith("[[dependencies")
                isMinecraft = false
                range = ""
                continue
            }
            if (!inDependency) continue
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim()
            val value = tomlValue(line.substring(separator + 1).trim())
            if (key == "modId" && value == "minecraft") isMinecraft = true
            if (key == "versionRange") range = value
        }
        return if (isMinecraft) range else ""
    }

    private fun tomlValue(raw: String): String {
        for (quote in listOf("\"\"\"", "'''")) {
            if (raw.startsWith(quote)) {
                val end = raw.indexOf(quote, quote.length)
                return if (end < 0) raw.substring(quote.length).trim() else raw.substring(quote.length, end)
            }
        }
        for (quote in listOf('"', '\'')) {
            if (raw.startsWith(quote)) {
                val end = raw.indexOf(quote, 1)
                return if (end < 0) raw.substring(1).trim() else raw.substring(1, end)
            }
        }
        return raw.substringBefore('#').trim()
    }

    private fun disguisedClass(name: String, head: ByteArray): Boolean {
        val low = name.lowercase()
        if (HARMLESS_EXTENSIONS.none { low.endsWith(it) }) return false
        if (head.size < CLASS_MAGIC.size) return false
        return CLASS_MAGIC.indices.all { head[it] == CLASS_MAGIC[it] }
    }

    private fun hasArchiveExtension(name: String): Boolean {
        val base = name.removeSuffix(DISABLED_SUFFIX)
        return base.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTENSIONS && base.length > 4
    }
}
