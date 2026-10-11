package net.millida.launcher.core.skins

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.millida.launcher.core.Hashing
import net.millida.launcher.core.atomicWriteText
import java.io.File

@Serializable
private data class TextureMeta(
    val file: String = "",
    val name: String = "",
    val slim: Boolean = false,
    val slimManual: Boolean = false,
)

/**
 * One texture of the local library. The file is handed over as is so the screen
 * can decode it itself; the bytes are only read when they are actually needed.
 */
data class TextureEntry(
    val kind: TextureKind,
    val file: String,
    val name: String,
    val slim: Boolean,
    val slimManual: Boolean,
    val path: File,
) {
    fun bytes(): ByteArray = path.readBytes()
}

/**
 * Skins and capes kept on disk next to the rest of the launcher data. Belongs
 * to the installation and not to an account, so an offline player has a
 * wardrobe too - the Millida wardrobe on the server is a separate thing.
 */
class TextureLibrary(
    private val root: File,
    private val maxItems: Int = 24,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun dir(kind: TextureKind): File = File(root, kind.dir)

    fun list(kind: TextureKind): List<TextureEntry> = entries(kind, sync(kind))

    fun read(kind: TextureKind, file: String): ByteArray {
        val target = File(dir(kind), safeName(file))
        if (!target.isFile) throw TextureError("Текстура не найдена")
        return target.readBytes()
    }

    fun save(kind: TextureKind, name: String, png: ByteArray, slim: Boolean = false): List<TextureEntry> {
        TexturePng.validate(kind, png)
        val folder = dir(kind)
        folder.mkdirs()
        // content-hashed file name: the same picture saved twice stays one file,
        // and two different textures sharing a display name never collide
        val file = fileStem(name) + "-" + Hashing.sha1(png).take(8) + ".png"
        File(folder, file).writeBytes(png)

        val title = name.trim().removeSuffix(".png").removeSuffix(".PNG").trim()
        val metas = sync(kind).filterNot { it.file == file }.toMutableList()
        metas.add(
            0,
            TextureMeta(
                file = file,
                name = title.ifEmpty { if (kind == TextureKind.CAPE) "Плащ" else "Скин" },
                slim = slim,
                slimManual = false,
            ),
        )
        val kept = metas.take(maxItems)
        for (extra in metas.drop(maxItems)) File(folder, extra.file).delete()
        return store(kind, kept)
    }

    fun rename(kind: TextureKind, file: String, name: String): List<TextureEntry> {
        val title = name.trim()
        if (title.isEmpty()) throw TextureError("Имя не может быть пустым")
        val target = safeName(file)
        val metas = sync(kind).map { if (it.file == target) it.copy(name = title.take(60)) else it }
        return store(kind, metas)
    }

    fun delete(kind: TextureKind, file: String): List<TextureEntry> {
        val target = safeName(file)
        File(dir(kind), target).delete()
        return store(kind, sync(kind).filterNot { it.file == target })
    }

    /**
     * Slim is a property of the texture, not of the moment it is worn: once the
     * player corrects the arm width it must survive every later apply.
     */
    fun setSlim(kind: TextureKind, file: String, slim: Boolean, manual: Boolean = true): List<TextureEntry> {
        val target = safeName(file)
        val metas = sync(kind).map {
            if (it.file == target) it.copy(slim = slim, slimManual = it.slimManual || manual) else it
        }
        return store(kind, metas)
    }

    private fun indexFile(kind: TextureKind): File = File(dir(kind), "index.json")

    private fun readIndex(kind: TextureKind): List<TextureMeta> {
        val file = indexFile(kind)
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(TextureMeta.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    /**
     * The index only defines the order; files on disk win, so a broken
     * index.json cannot lose the library.
     */
    private fun sync(kind: TextureKind): List<TextureMeta> {
        val folder = dir(kind)
        val metas = readIndex(kind)
            .filter { it.file.isNotEmpty() && File(folder, it.file).isFile }
            .toMutableList()
        val known = metas.mapTo(HashSet()) { it.file }
        folder.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".png", ignoreCase = true) && it.name !in known }
            ?.sortedBy { it.name }
            ?.forEach { metas.add(TextureMeta(file = it.name, name = it.name.removeSuffix(".png"))) }
        return metas
    }

    private fun store(kind: TextureKind, metas: List<TextureMeta>): List<TextureEntry> {
        val folder = dir(kind)
        folder.mkdirs()
        indexFile(kind).atomicWriteText(
            json.encodeToString(ListSerializer(TextureMeta.serializer()), metas)
        )
        return entries(kind, metas)
    }

    private fun entries(kind: TextureKind, metas: List<TextureMeta>): List<TextureEntry> {
        val folder = dir(kind)
        return metas.mapNotNull { meta ->
            val path = File(folder, meta.file)
            if (!path.isFile) return@mapNotNull null
            TextureEntry(
                kind = kind,
                file = meta.file,
                name = meta.name.ifEmpty { meta.file.removeSuffix(".png") },
                slim = meta.slim,
                slimManual = meta.slimManual,
                path = path,
            )
        }
    }

    companion object {
        /** A name typed by the player never becomes a path: only one flat segment survives. */
        fun fileStem(raw: String): String {
            val base = raw.trim().removeSuffix(".png").removeSuffix(".PNG")
            val safe = base.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
                .joinToString("")
                .trim('-')
            return if (safe.isEmpty()) "texture" else safe.take(40)
        }

        fun safeName(file: String): String {
            val name = file.trim()
            if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) {
                throw TextureError("Недопустимое имя файла")
            }
            return name
        }
    }
}
