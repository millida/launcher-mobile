package net.millida.launcher.core.game

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.SafeUnzip
import net.millida.launcher.core.profile.profileFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class WorldCard(
    val folder: String,
    val name: String,
    val lastPlayed: Long,
    val sizeBytes: Long,
    val gameMode: Int,
    val hardcore: Boolean,
    val version: String,
    val difficulty: Int = UNKNOWN,
    val cheats: Boolean = false,
    val seed: String = "",
    val backups: Int = 0,
    val readable: Boolean = true,
) {
    companion object {
        const val UNKNOWN = -1
    }
}

data class WorldBackup(
    val file: String,
    val folder: String,
    val createdAt: Long,
    val sizeBytes: Long,
)

class Worlds(private val layout: GameLayout) {

    @Throws(IOException::class)
    fun list(profileDir: File): List<WorldCard> {
        val saves = profileFolder(layout, profileDir, SAVES)
        val folders = saves.listFiles() ?: return emptyList()
        val backups = backups(profileDir)
        return folders
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { card(it, backups.count { backup -> backup.folder == it.name }) }
            .sortedWith(compareByDescending<WorldCard> { it.lastPlayed }.thenBy { it.name.lowercase() })
    }

    @Throws(IOException::class)
    fun details(profileDir: File, folder: String): WorldCard? {
        val dir = worldDir(profileDir, folder)
        if (!dir.isDirectory) return null
        return card(dir, backups(profileDir).count { it.folder == dir.name })
    }

    @Throws(IOException::class)
    fun delete(profileDir: File, folder: String): Boolean {
        val target = worldDir(profileDir, folder)
        return target.isDirectory && target.deleteRecursively()
    }

    /**
     * The folder and the name the game shows are two different things, and a
     * player renaming a world means both: a world called "Дом" sitting in a
     * folder named "New World" is how saves become unfindable.
     */
    @Throws(IOException::class)
    fun rename(profileDir: File, folder: String, newName: String): WorldCard {
        val clean = newName.trim().filter { it.code >= 0x20 }.take(MAX_NAME)
        if (clean.isEmpty()) throw IOException("название мира не может быть пустым")
        val source = worldDir(profileDir, folder)
        if (!source.isDirectory) throw IOException("мир не найден: $folder")

        val saves = savesDir(profileDir)
        val wanted = SafePath.fileName(clean)
        var dir = source
        if (!wanted.equals(source.name, ignoreCase = true)) {
            val target = SafePath.join(saves, uniqueFolder(saves, wanted))
            // The folder move happens first because it is the step that can fail
            // on a busy file; failing here leaves the world exactly as it was.
            if (!source.renameTo(target)) throw IOException("папку мира не удалось переименовать: ${source.name}")
            dir = target
        }
        try {
            writeLevelName(dir, clean)
        } catch (failed: IOException) {
            if (dir != source) dir.renameTo(source)
            throw failed
        }
        return card(dir, backups(profileDir).count { it.folder == dir.name })
            ?: throw IOException("мир не читается после переименования: ${dir.name}")
    }

    @Throws(IOException::class)
    fun duplicate(profileDir: File, folder: String): WorldCard {
        val source = worldDir(profileDir, folder)
        if (!source.isDirectory) throw IOException("мир не найден: $folder")
        val saves = savesDir(profileDir)
        val target = SafePath.join(saves, uniqueFolder(saves, "${source.name}-copy"))
        if (!source.copyRecursively(target, overwrite = false, onError = { _, error -> throw error })) {
            target.deleteRecursively()
            throw IOException("не удалось скопировать мир: ${source.name}")
        }
        // The session lock belongs to the original; a copy carrying it makes the
        // game claim the world is already open.
        File(target, SESSION_LOCK).delete()
        val copied = card(target, 0) ?: throw IOException("копия мира не читается: ${target.name}")
        return runCatching { rename(profileDir, target.name, "${copied.name} (копия)") }.getOrDefault(copied)
    }

    @Throws(IOException::class)
    fun backup(profileDir: File, folder: String): WorldBackup {
        val source = worldDir(profileDir, folder)
        if (!source.isDirectory) throw IOException("мир не найден: $folder")
        val dir = backupsDir(profileDir)
        dir.mkdirs()
        val stamp = System.currentTimeMillis()
        val file = SafePath.join(dir, "${source.name}$STAMP_MARK$stamp$ZIP")
        val partial = File(file.parentFile, "${file.name}.part")
        try {
            partial.outputStream().use { pack(source, it) }
            // The backup becomes visible only once the archive is closed: a half
            // written zip in the list is a restore that fails when it is needed.
            if (!partial.renameTo(file)) throw IOException("копию не удалось сохранить: ${file.name}")
        } finally {
            partial.delete()
        }
        return WorldBackup(file.name, source.name, stamp, file.length())
    }

    @Throws(IOException::class)
    fun backups(profileDir: File, folder: String = ""): List<WorldBackup> {
        val dir = backupsDir(profileDir)
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(ZIP, ignoreCase = true) }
            .map { describeBackup(it) }
            .filter { folder.isEmpty() || it.folder == folder }
            .sortedByDescending { it.createdAt }
    }

    /**
     * The copy is unpacked in full before the live world is touched, and the
     * replaced world is only dropped once the new one is in place: a restore is
     * asked for when something already went wrong, and losing both copies is
     * worse than the failed restore itself.
     */
    @Throws(IOException::class)
    fun restoreBackup(profileDir: File, backupFile: String): WorldCard {
        val archive = backupPath(profileDir, backupFile)
        if (!archive.isFile) throw IOException("копия не найдена: $backupFile")
        val staging = newTempDir(profileDir, "restore")
        try {
            unpack(archive, staging)
            val root = worldRoot(staging) ?: throw IOException("в копии нет мира: level.dat не найден")
            val saves = savesDir(profileDir)
            saves.mkdirs()
            // The folder name comes back from the archive and keeps its spaces:
            // SafePath.join is what rejects a name trying to leave saves/.
            val folder = if (root != staging) root.name else describeBackup(archive).folder
            val target = SafePath.join(saves, folder)
            swapIn(root, target)
            return card(target, backups(profileDir).count { it.folder == target.name })
                ?: throw IOException("восстановленный мир не читается: ${target.name}")
        } finally {
            staging.deleteRecursively()
        }
    }

    @Throws(IOException::class)
    fun deleteBackup(profileDir: File, backupFile: String): Boolean {
        val archive = backupPath(profileDir, backupFile)
        return archive.isFile && archive.delete()
    }

    @Throws(IOException::class)
    fun exportWorld(profileDir: File, folder: String, target: OutputStream) {
        val source = worldDir(profileDir, folder)
        if (!source.isDirectory) throw IOException("мир не найден: $folder")
        pack(source, target)
    }

    /**
     * The archive is untrusted: every entry name goes through [SafePath], and the
     * result has to look like a world before it is allowed into saves/.
     */
    @Throws(IOException::class)
    fun importWorld(profileDir: File, source: InputStream, suggestedName: String = ""): WorldCard {
        val staging = newTempDir(profileDir, "import")
        val spooled = File(staging.parentFile, "${staging.name}$ZIP")
        try {
            unpack(SafeUnzip.spool(source, spooled), staging)
            val root = worldRoot(staging) ?: throw IOException("в архиве нет мира: level.dat не найден")
            val saves = savesDir(profileDir)
            saves.mkdirs()
            val base = when {
                root != staging -> root.name
                suggestedName.isNotBlank() -> SafePath.fileName(suggestedName.removeSuffix(ZIP))
                else -> "World"
            }
            val target = SafePath.join(saves, uniqueFolder(saves, base))
            if (!root.renameTo(target)) {
                if (!root.copyRecursively(target, overwrite = false, onError = { _, error -> throw error })) {
                    target.deleteRecursively()
                    throw IOException("не удалось перенести мир из архива")
                }
            }
            File(target, SESSION_LOCK).delete()
            return card(target, 0) ?: throw IOException("мир из архива не читается: ${target.name}")
        } finally {
            spooled.delete()
            staging.deleteRecursively()
        }
    }

    private fun swapIn(prepared: File, target: File) {
        val replaced = if (target.exists()) File(target.parentFile, "${target.name}$REPLACED${System.currentTimeMillis()}") else null
        if (replaced != null && !target.renameTo(replaced)) {
            throw IOException("мир занят и не заменяется: ${target.name}")
        }
        val moved = prepared.renameTo(target) ||
            prepared.copyRecursively(target, overwrite = true, onError = { _, _ -> throw IOException("не удалось положить мир на место") })
        if (!moved) {
            target.deleteRecursively()
            replaced?.renameTo(target)
            throw IOException("не удалось положить мир на место: ${target.name}")
        }
        File(target, SESSION_LOCK).delete()
        replaced?.deleteRecursively()
    }

    private fun card(dir: File, backups: Int): WorldCard? {
        val level = File(dir, LEVEL)
        val fallback = File(dir, "${LEVEL}_old")
        if (!level.isFile && !fallback.isFile) return null

        val data = levelData(level) ?: levelData(fallback)
        val size = folderSize(dir)
        if (data == null) {
            return WorldCard(
                folder = dir.name,
                name = dir.name,
                lastPlayed = level.lastModified().takeIf { it > 0 } ?: fallback.lastModified(),
                sizeBytes = size,
                gameMode = 0,
                hardcore = false,
                version = "",
                readable = false,
                backups = backups,
            )
        }
        return WorldCard(
            folder = dir.name,
            name = data.text("LevelName")?.takeIf { it.isNotBlank() } ?: dir.name,
            lastPlayed = data.number("LastPlayed")?.takeIf { it > 0 } ?: level.lastModified(),
            sizeBytes = size,
            gameMode = data.number("GameType")?.toInt() ?: 0,
            hardcore = (data.number("hardcore") ?: 0L) != 0L,
            version = data.compound("Version")?.text("Name").orEmpty(),
            difficulty = data.number("Difficulty")?.toInt() ?: WorldCard.UNKNOWN,
            cheats = (data.number("allowCommands") ?: 0L) != 0L,
            seed = seedOf(data),
            backups = backups,
            readable = true,
        )
    }

    /** The seed moved into WorldGenSettings in 1.16; a hidden seed beats a wrong one. */
    private fun seedOf(data: NbtCompound): String {
        val seed = data.compound("WorldGenSettings")?.number("seed") ?: data.number("RandomSeed")
        return seed?.toString().orEmpty()
    }

    private fun levelData(file: File): NbtCompound? = Nbt.read(file)?.root?.compound("Data")

    private fun writeLevelName(dir: File, name: String) {
        val level = File(dir, LEVEL)
        val document = Nbt.read(level) ?: throw IOException("level.dat не читается — мир повреждён")
        if (!document.complete) throw IOException("level.dat прочитан не полностью — переименование отменено")
        val data = document.root.compound("Data") ?: throw IOException("level.dat не похож на файл мира")
        val updated = NbtDocument(
            document.name,
            document.root.with("Data", data.with("LevelName", NbtString(name))),
            complete = true,
        )
        val temp = File(dir, "$LEVEL.new")
        try {
            temp.outputStream().use { Nbt.write(it, updated) }
            // The game itself falls back to level.dat_old, so the previous file is
            // kept as that fallback instead of being overwritten in place.
            level.copyTo(File(dir, "${LEVEL}_old"), overwrite = true)
            if (!temp.renameTo(level)) {
                level.delete()
                if (!temp.renameTo(level)) throw IOException("level.dat не удалось заменить")
            }
        } finally {
            temp.delete()
        }
    }

    private fun pack(source: File, target: OutputStream) {
        ZipOutputStream(target.buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            source.walkTopDown().forEach { file ->
                val relative = file.relativeTo(source).path.replace(File.separatorChar, '/')
                if (relative.isEmpty() || relative == SESSION_LOCK) return@forEach
                val entry = "${source.name}/$relative"
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry("$entry/"))
                    zip.closeEntry()
                } else {
                    zip.putNextEntry(ZipEntry(entry).also { it.time = file.lastModified() })
                    file.inputStream().use { it.copyTo(zip, COPY_BUFFER) }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun unpack(archive: File, staging: File) {
        val files = SafeUnzip.unpack(archive, staging, UNZIP_LIMITS)
        if (files == 0) throw IOException("архив пуст")
    }

    private fun worldRoot(dir: File, depth: Int = WORLD_ROOT_DEPTH): File? {
        if (File(dir, LEVEL).isFile) return dir
        if (depth == 0) return null
        val nested = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return null
        return nested.firstNotNullOfOrNull { worldRoot(it, depth - 1) }
    }

    private fun describeBackup(file: File): WorldBackup {
        val stem = file.name.removeSuffix(ZIP)
        val stamp = stem.substringAfterLast(STAMP_MARK, "").toLongOrNull()
        return WorldBackup(
            file = file.name,
            folder = if (stamp != null) stem.substringBeforeLast(STAMP_MARK) else stem,
            createdAt = stamp ?: file.lastModified(),
            sizeBytes = file.length(),
        )
    }

    private fun backupPath(profileDir: File, backupFile: String): File {
        if (!backupFile.endsWith(ZIP, ignoreCase = true)) throw IOException("это не файл копии: $backupFile")
        return SafePath.join(backupsDir(profileDir), backupFile)
    }

    /** The base is already a legal folder name here; only the collision suffix is added. */
    private fun uniqueFolder(saves: File, base: String): String {
        var name = base
        var index = 2
        while (File(saves, name).exists()) {
            name = "$base-$index"
            index += 1
        }
        return name
    }

    private fun newTempDir(profileDir: File, kind: String): File {
        val root = profileFolder(layout, profileDir, TEMP)
        // Staging lives inside the profile so the finished world is a rename away:
        // a move across filesystems would copy, and a copy can die halfway.
        val dir = File(root, "$kind-${System.nanoTime()}")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private fun savesDir(profileDir: File): File = profileFolder(layout, profileDir, SAVES)

    private fun backupsDir(profileDir: File): File = profileFolder(layout, profileDir, BACKUPS)

    private fun worldDir(profileDir: File, folder: String): File = SafePath.join(savesDir(profileDir), folder)

    private fun folderSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val SAVES = "saves"
        const val BACKUPS = "backups"
        const val TEMP = ".millida-tmp"
        const val LEVEL = "level.dat"
        const val WORLD_ROOT_DEPTH = 3
        const val SESSION_LOCK = "session.lock"
        const val ZIP = ".zip"
        const val STAMP_MARK = "--"
        const val REPLACED = ".replaced-"
        const val MAX_NAME = 64
        const val MAX_ENTRIES = 100_000
        const val MAX_UNPACKED_BYTES = 8L * 1024 * 1024 * 1024
        const val COPY_BUFFER = 1 shl 16
        val UNZIP_LIMITS = SafeUnzip.Limits(
            maxEntries = MAX_ENTRIES,
            maxBytes = MAX_UNPACKED_BYTES,
            broken = { cause -> "архив мира повреждён ($cause) — возьми файл заново" },
            tooMany = "в архиве слишком много файлов",
            tooBig = "архив распаковывается в слишком большой мир",
        )
    }
}
