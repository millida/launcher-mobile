package net.millida.launcher.core.packs

import net.millida.launcher.core.SafePath
import net.millida.launcher.core.SafeUnzip
import java.io.File
import java.io.IOException

/**
 * The single unpacker for everything pack shaped: .mrpack, a CurseForge zip and
 * a Millida catalogue archive all land here. Entry names come from a file a
 * stranger built, so every one of them goes through SafePath before a byte is
 * written: an absolute name or one climbing out with ".." stops the install.
 */
object PackArchive {
    private const val MAX_ENTRIES = 40_000
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024

    private val LIMITS = SafeUnzip.Limits(
        maxEntries = MAX_ENTRIES,
        maxBytes = MAX_TOTAL_BYTES,
        broken = { cause -> "архив сборки повреждён ($cause) — скачай сборку заново" },
        tooMany = "в архиве сборки слишком много файлов — скачай сборку заново",
        tooBig = "сборка распаковывается в слишком большой объём — установка остановлена",
    )

    @Throws(IOException::class)
    fun unpack(archive: File, target: File): File {
        if (!archive.isFile) throw IOException("архив сборки не найден: ${archive.name}")
        SafeUnzip.unpack(archive, target, LIMITS)
        return target
    }

    /**
     * An archive is often wrapped in one top-level folder, and the manifest then
     * sits a level down. Two levels are enough: deeper nesting is not a pack.
     */
    fun root(unpacked: File): File {
        var current = unpacked
        repeat(2) {
            if (MANIFESTS.any { File(current, it).isFile }) return current
            val folders = current.listFiles()?.filter { it.isDirectory }.orEmpty()
            if (folders.size != 1) return current
            current = folders.first()
        }
        return current
    }

    fun hasManifest(dir: File): Boolean = MANIFESTS.any { File(dir, it).isFile }

    /**
     * The game folder of an archive without a manifest. A single folder that
     * wraps everything is unwrapped, but a lone `mods` or `config` is the game
     * folder itself and stays. Archiver litter does not count as a second entry.
     */
    fun contentRoot(unpacked: File): File {
        var current = unpacked
        repeat(2) {
            val entries = current.listFiles().orEmpty().filterNot { it.name in ARCHIVER_LITTER || it.name.startsWith(".") }
            val only = entries.singleOrNull() ?: return current
            if (!only.isDirectory || only.name.lowercase() in GAME_FOLDERS) return current
            current = only
        }
        return current
    }

    @Throws(IOException::class)
    fun copyTree(from: File, into: File) {
        if (!from.isDirectory) return
        val base = from.canonicalFile
        from.walkTopDown().forEach { entry ->
            if (entry == from) return@forEach
            val relative = entry.canonicalFile.relativeTo(base).path.replace(File.separatorChar, '/')
            if (relative.isEmpty()) return@forEach
            val target = SafePath.join(into, relative)
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                entry.copyTo(target, overwrite = true)
            }
        }
    }

    private val MANIFESTS = listOf("modrinth.index.json", "manifest.json", "millida.pack.json")

    private val ARCHIVER_LITTER = setOf("__MACOSX", "Thumbs.db", "desktop.ini")

    private val GAME_FOLDERS = setOf(
        "mods", "config", "resourcepacks", "shaderpacks", "saves", "datapacks", "scripts", "kubejs",
        "defaultconfigs", "libraries", "versions", "assets", "coremods", "bin",
    )
}
