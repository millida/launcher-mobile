package net.millida.launcher.core.media

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.SafePath
import java.io.File
import java.io.FileInputStream
import java.io.IOException

data class Screenshot(
    val profileId: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val takenAt: Long,
    val width: Int,
    val height: Int,
)

private const val FOLDER = "screenshots"
private const val EXTENSION = ".png"

/** Signature plus length, "IHDR" and the two dimensions: the first 24 bytes of a PNG. */
private const val HEADER_BYTES = 24

/**
 * The gallery decodes every tile it shows, so the list itself must stay short;
 * the counter is not capped and still tells the player the honest total.
 */
private const val MAX_LISTED = 500

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)

private val IHDR = byteArrayOf(0x49, 0x48, 0x44, 0x52)

private val NO_SIZE = 0 to 0

class Screenshots(private val layout: GameLayout) {

    /** A blank build identifier means every build: the gallery has an "all builds" mode. */
    @Throws(IOException::class)
    fun list(profileId: String = ""): List<Screenshot> {
        val builds = if (profileId.isBlank()) allProfileIds() else listOf(profileId)
        return builds
            .flatMap { ofProfile(it) }
            .sortedWith(compareByDescending<Screenshot> { it.takenAt }.thenBy { it.name.lowercase() })
            .take(MAX_LISTED)
    }

    @Throws(IOException::class)
    fun count(profileId: String = ""): Int {
        val builds = if (profileId.isBlank()) allProfileIds() else listOf(profileId)
        return builds.sumOf { build ->
            folder(build).listFiles().orEmpty().count { it.isFile && isPng(it.name) }
        }
    }

    /** The resolved file, for the app layer to hand to the system share sheet. */
    @Throws(IOException::class)
    fun file(profileId: String, name: String): File = resolve(profileId, name)

    @Throws(IOException::class)
    fun describe(profileId: String, name: String): Screenshot =
        card(profileId, resolve(profileId, name)) ?: throw IOException("Скриншот не найден: $name")

    @Throws(IOException::class)
    fun delete(profileId: String, name: String): Boolean {
        val target = resolve(profileId, name)
        if (!target.isFile) return false
        if (!target.delete()) throw IOException("Не удалось удалить скриншот: $name")
        return true
    }

    @Throws(IOException::class)
    fun rename(profileId: String, name: String, newName: String): Screenshot {
        val source = resolve(profileId, name)
        if (!source.isFile) throw IOException("Скриншот не найден: $name")
        val target = File(source.parentFile, pngName(newName))
        if (target.name == source.name) return describe(profileId, name)
        if (target.exists()) throw IOException("Скриншот с именем ${target.name} уже есть")
        if (!source.renameTo(target)) throw IOException("Не удалось переименовать скриншот")
        return card(profileId, target) ?: throw IOException("Скриншот не найден: ${target.name}")
    }

    @Throws(IOException::class)
    fun folder(profileId: String): File {
        if (profileId.isBlank()) throw IOException("Не указана сборка")
        return File(SafePath.join(layout.profiles, profileId), FOLDER)
    }

    private fun allProfileIds(): List<String> =
        layout.profiles.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()

    private fun ofProfile(profileId: String): List<Screenshot> {
        val entries = folder(profileId).listFiles() ?: return emptyList()
        return entries.filter { it.isFile && isPng(it.name) }.mapNotNull { card(profileId, it) }
    }

    /**
     * The name arrives from the interface layer and turns into a path that is
     * deleted, renamed and uploaded, so anything but a plain png file name of
     * this build is refused instead of resolved.
     */
    @Throws(IOException::class)
    private fun resolve(profileId: String, name: String): File {
        val file = SafePath.join(folder(profileId), name)
        if (file.name != name || !isPng(name)) throw IOException("Это не скриншот: $name")
        return file
    }

    private fun card(profileId: String, file: File): Screenshot? {
        if (!file.isFile) return null
        val size = pngSize(file)
        return Screenshot(
            profileId = profileId,
            name = file.name,
            path = file.absolutePath,
            sizeBytes = file.length(),
            takenAt = file.lastModified(),
            width = size.first,
            height = size.second,
        )
    }

    @Throws(IOException::class)
    private fun pngName(raw: String): String {
        val cleaned = SafePath.fileName(raw.trim())
        val base = if (isPng(cleaned)) cleaned.dropLast(EXTENSION.length) else cleaned
        if (base.isBlank() || base == "file") throw IOException("Пустое имя скриншота")
        return base + EXTENSION
    }

    private fun isPng(name: String): Boolean =
        name.length > EXTENSION.length && name.endsWith(EXTENSION, ignoreCase = true)

    /**
     * Dimensions come straight out of the header: decoding a hundred 4K frames to
     * learn their size would freeze the gallery on a phone. A file that only looks
     * like a PNG reports nothing rather than an invented size.
     */
    private fun pngSize(file: File): Pair<Int, Int> = try {
        FileInputStream(file).use { input ->
            val head = ByteArray(HEADER_BYTES)
            var read = 0
            while (read < HEADER_BYTES) {
                val step = input.read(head, read, HEADER_BYTES - read)
                if (step < 0) break
                read += step
            }
            if (read < HEADER_BYTES || !head.matches(PNG_SIGNATURE, 0) || !head.matches(IHDR, 12)) {
                NO_SIZE
            } else {
                val width = head.int(16)
                val height = head.int(20)
                if (width <= 0 || height <= 0) NO_SIZE else width to height
            }
        }
    } catch (unreadable: IOException) {
        NO_SIZE
    }
}

private fun ByteArray.matches(expected: ByteArray, offset: Int): Boolean =
    expected.indices.all { this[offset + it] == expected[it] }

private fun ByteArray.int(offset: Int): Int =
    (this[offset].toInt() and 0xFF shl 24) or
        (this[offset + 1].toInt() and 0xFF shl 16) or
        (this[offset + 2].toInt() and 0xFF shl 8) or
        (this[offset + 3].toInt() and 0xFF)
