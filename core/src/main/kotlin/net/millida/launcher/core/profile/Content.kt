package net.millida.launcher.core.profile

import kotlinx.serialization.Serializable
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

const val DISABLED_SUFFIX = ".disabled"

private val ARCHIVE_EXTENSIONS = setOf("jar", "zip")

data class ContentFile(
    val name: String,
    val kind: ContentKind,
    val sizeBytes: Long,
    val enabled: Boolean,
    val modId: String,
    val title: String,
    val version: String,
)

/**
 * A profile directory arrives from the interface layer, so it is checked against
 * the store root before any rename or delete touches the disk.
 */
internal fun profileFolder(layout: GameLayout, profileDir: File, folder: String): File {
    if (!SafePath.isInside(layout.profiles, profileDir)) {
        throw IOException("папка сборки лежит вне хранилища профилей: ${profileDir.name}")
    }
    return File(profileDir, folder)
}

class ProfileContent(private val layout: GameLayout) {

    @Throws(IOException::class)
    fun list(profileDir: File, kind: ContentKind): List<ContentFile> {
        val folder = profileFolder(layout, profileDir, kind.folder)
        val entries = folder.listFiles() ?: return emptyList()
        return entries
            .filter { it.isFile && isArchive(it.name) }
            .map { describe(it, kind) }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.name.lowercase() }))
    }

    @Throws(IOException::class)
    fun setEnabled(profileDir: File, file: ContentFile, enabled: Boolean): ContentFile {
        val folder = profileFolder(layout, profileDir, file.kind.folder)
        val source = SafePath.join(folder, file.name)
        if (!source.isFile) throw IOException("файл ${file.name} не найден в папке ${file.kind.folder}")

        val targetName = if (enabled) file.name.removeSuffix(DISABLED_SUFFIX) else disabledName(file.name)
        if (targetName == file.name) return file

        val target = SafePath.join(folder, targetName)
        if (target.exists()) throw IOException("в папке ${file.kind.folder} уже лежит файл $targetName")

        // Move without REPLACE_EXISTING: a failure leaves the original name in place
        // instead of erasing the only copy of the mod.
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
        return file.copy(name = targetName, enabled = enabled)
    }

    @Throws(IOException::class)
    fun delete(profileDir: File, file: ContentFile): Boolean {
        val folder = profileFolder(layout, profileDir, file.kind.folder)
        val target = SafePath.join(folder, file.name)
        return target.isFile && target.delete()
    }

    private fun describe(file: File, kind: ContentKind): ContentFile {
        val enabled = !file.name.endsWith(DISABLED_SUFFIX)
        val meta = readMeta(file)
        val fallback = titleFromName(file.name)
        return ContentFile(
            name = file.name,
            kind = kind,
            sizeBytes = file.length(),
            enabled = enabled,
            modId = meta?.modId ?: "",
            title = meta?.title?.takeIf { it.isNotBlank() } ?: fallback,
            version = meta?.version ?: "",
        )
    }

    private fun readMeta(file: File): ModMeta? = try {
        ZipFile(file).use { zip ->
            fabricMeta(zip) ?: forgeMeta(zip)
        }
    } catch (broken: Exception) {
        null
    }

    private fun fabricMeta(zip: ZipFile): ModMeta? {
        val text = entryText(zip, "fabric.mod.json") ?: return null
        val parsed = try {
            MojangJson.decodeFromString(FabricMod.serializer(), text)
        } catch (broken: Exception) {
            return null
        }
        if (parsed.id.isBlank()) return null
        return ModMeta(
            modId = parsed.id,
            title = parsed.name.ifBlank { parsed.id },
            version = resolvePlaceholder(parsed.version, zip),
        )
    }

    private fun forgeMeta(zip: ZipFile): ModMeta? {
        val text = entryText(zip, "META-INF/neoforge.mods.toml")
            ?: entryText(zip, "META-INF/mods.toml")
            ?: return null
        val values = firstModsBlock(text)
        val modId = values["modId"]?.takeIf { it.isNotBlank() } ?: return null
        return ModMeta(
            modId = modId,
            title = values["displayName"]?.takeIf { it.isNotBlank() } ?: modId,
            version = resolvePlaceholder(values["version"].orEmpty(), zip),
        )
    }

    /**
     * Forge build scripts leave `${file.jarVersion}` in the descriptor and expect the
     * loader to take the real number from the jar manifest.
     */
    private fun resolvePlaceholder(version: String, zip: ZipFile): String {
        if (!version.contains("\${")) return version
        val manifest = entryText(zip, "META-INF/MANIFEST.MF") ?: return ""
        return manifest.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Implementation-Version:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()
    }

    private fun entryText(zip: ZipFile, path: String): String? {
        val entry = zip.getEntry(path) ?: return null
        if (entry.isDirectory || entry.size > MAX_META_BYTES) return null
        return zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
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

    private fun isArchive(name: String): Boolean {
        val base = name.removeSuffix(DISABLED_SUFFIX)
        return base.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTENSIONS && base.length > 4
    }

    private fun disabledName(name: String): String =
        if (name.endsWith(DISABLED_SUFFIX)) name else name + DISABLED_SUFFIX

    private fun titleFromName(name: String): String =
        name.removeSuffix(DISABLED_SUFFIX).substringBeforeLast('.')

    private data class ModMeta(val modId: String, val title: String, val version: String)

    @Serializable
    private data class FabricMod(
        val id: String = "",
        val name: String = "",
        val version: String = "",
    )

    private companion object {
        const val MAX_META_BYTES = 1L * 1024 * 1024
    }
}
