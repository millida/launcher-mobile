package net.millida.launcher.core.profile

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.i18n.tr
import java.io.File
import java.io.IOException
import java.io.InputStream

data class BuildFileEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/**
 * The files of one build as a plain folder tree: configs, options.txt, logs,
 * anything a mod keeps. Every path comes from the screen and is joined through
 * SafePath, so nothing outside the build can be listed, written or removed.
 */
class BuildFiles(private val layout: GameLayout) {

    @Throws(IOException::class)
    fun list(profileDir: File, path: String): List<BuildFileEntry> {
        val dir = resolve(profileDir, path)
        if (!dir.isDirectory) throw IOException(tr("папки «{path}» больше нет", path.ifEmpty { "/" }))
        return dir.listFiles().orEmpty()
            .map { file ->
                BuildFileEntry(
                    name = file.name,
                    path = child(path, file.name),
                    directory = file.isDirectory,
                    sizeBytes = if (file.isFile) file.length() else 0L,
                    modifiedAt = file.lastModified(),
                )
            }
            .sortedWith(compareByDescending<BuildFileEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    @Throws(IOException::class)
    fun file(profileDir: File, path: String): File {
        val target = resolve(profileDir, path)
        if (!target.isFile) throw IOException(tr("файла «{path}» больше нет", path))
        return target
    }

    /** The build folder itself is never removed this way: that is what deleting the build is for. */
    @Throws(IOException::class)
    fun delete(profileDir: File, path: String) {
        if (normalize(path).isEmpty()) throw IOException(tr("папку сборки целиком удаляет только удаление сборки"))
        val target = resolve(profileDir, path)
        if (!target.exists()) return
        val gone = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!gone) throw IOException(tr("«{name}» не удалился — закрой игру и попробуй снова", target.name))
    }

    /**
     * Writes a picked file into [folder] under a name that is free there: an
     * existing file is never replaced by an import.
     */
    @Throws(IOException::class)
    fun importInto(profileDir: File, folder: String, suggestedName: String, source: InputStream): BuildFileEntry {
        val dir = resolve(profileDir, folder)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException(tr("папки «{path}» больше нет", folder.ifEmpty { "/" }))
        val name = freeName(dir, SafePath.fileName(suggestedName))

        val target = SafePath.join(dir, name)
        val partial = File(dir, "$name.part")
        try {
            partial.outputStream().use { out -> source.copyTo(out) }
            if (!partial.renameTo(target)) throw IOException(tr("файл не сохранился в сборке — освободи место и повтори"))
        } finally {
            if (partial.exists()) partial.delete()
        }
        return BuildFileEntry(name, child(folder, name), false, target.length(), target.lastModified())
    }

    @Throws(IOException::class)
    private fun resolve(profileDir: File, path: String): File {
        if (!SafePath.isInside(layout.profiles, profileDir)) {
            throw IOException(tr("папка сборки лежит вне хранилища профилей: {name}", profileDir.name))
        }
        val clean = normalize(path)
        return if (clean.isEmpty()) profileDir else SafePath.join(profileDir, clean)
    }

    companion object {
        fun normalize(path: String): String = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

        fun parent(path: String): String = normalize(path).substringBeforeLast('/', "")

        fun child(folder: String, name: String): String = normalize(if (folder.isEmpty()) name else "$folder/$name")

        private fun freeName(dir: File, name: String): String {
            if (!File(dir, name).exists()) return name
            val stem = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var index = 2
            while (File(dir, "$stem ($index)$extension").exists()) index++
            return "$stem ($index)$extension"
        }
    }
}
