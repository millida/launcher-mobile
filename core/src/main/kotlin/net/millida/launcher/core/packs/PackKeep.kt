package net.millida.launcher.core.packs

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.io.IOException

/**
 * A paid pack that loses its access leaves the phone, the player's own things
 * do not. Worlds, screenshots, their own resource and shader packs and game
 * settings are moved aside before the pack is removed and come back with the
 * next install of the same pack.
 */
object PackKeep {
    const val SHIPPED_FILE = "millida-pack-files.json"
    private const val KEEP_FOLDER = "pack-keep"

    private val ALWAYS_KEEP = setOf(
        "saves", "screenshots", "schematics", "resourcepacks", "shaderpacks",
        "options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat",
    )

    /** The pack may ship its own resource pack there; only the player's additions are theirs. */
    private val PER_ENTRY = setOf("resourcepacks", "shaderpacks")

    /** The paid content itself never leaves with the player's files, whatever was put there. */
    private val NEVER_KEEP = setOf(
        "libraries", "mods", "natives", "assets", "minecraft.jar", "logs", "crash-reports", ".fabric", ".mixin.out",
    )

    /** The slug names a folder files move in and out of, so it is checked like any path. */
    @Throws(IOException::class)
    fun keepDir(root: File, slug: String): File {
        if (!catalogSlugValid(slug)) throw IOException("некорректный адрес сборки: $slug")
        return SafePath.join(File(root, KEEP_FOLDER), slug)
    }

    /** What the pack itself ships, read from the folder that is about to become the build. */
    fun shipped(stage: File): List<String> {
        val out = names(stage).toMutableList()
        PER_ENTRY.forEach { folder -> out += names(File(stage, folder)).map { "$folder/$it" } }
        return out
    }

    fun recordShipped(profileDir: File, shipped: List<String>) {
        File(profileDir, SHIPPED_FILE).atomicWriteText(MojangJson.encodeToString(ListSerializer(String.serializer()), shipped))
    }

    /**
     * Without a record of what the pack shipped only the well-known player
     * folders count. Launcher files are never the player's: they describe the
     * build they were written for.
     */
    fun playerEntries(profileDir: File): List<String> {
        val shipped = readShipped(profileDir)
        val out = ArrayList<String>()
        for (name in names(profileDir)) {
            if (name in NEVER_KEEP || name.startsWith("millida-")) continue
            if (name in PER_ENTRY) {
                names(File(profileDir, name)).map { "$name/$it" }.filter { shipped == null || it !in shipped }.forEach { out += it }
                continue
            }
            if (name in ALWAYS_KEEP || (shipped != null && name !in shipped)) out += name
        }
        return out
    }

    /**
     * Moves the player's files out. Any failure stops here and the pack is not
     * removed: losing a world is worse than a pack that stays on the phone but
     * no longer gets a launch signature.
     */
    @Throws(IOException::class)
    fun stash(profileDir: File, keep: File): Int {
        val entries = playerEntries(profileDir)
        entries.forEach { relative ->
            val target = freeTarget(SafePath.join(keep, relative))
            if (!move(SafePath.join(profileDir, relative), target)) {
                throw IOException("не удалось сохранить «$relative» — освободи место на телефоне и запусти сборку снова")
            }
        }
        return entries.size
    }

    /**
     * Puts kept files back into a freshly installed pack. What the new version
     * ships wins a clash, and the clashing copy stays in the keep folder, as
     * does anything that failed to move: the next install tries again and
     * nothing of the player's is deleted. Returns what stayed behind.
     */
    fun restore(profileDir: File, keep: File): List<String> {
        if (!keep.isDirectory) return emptyList()
        val left = ArrayList<String>()
        restoreInto(keep, profileDir, "", left)
        keep.delete()
        return left
    }

    private fun restoreInto(from: File, into: File, prefix: String, left: MutableList<String>) {
        for (name in names(from)) {
            val source = File(from, name)
            val target = File(into, name)
            if (prefix.isEmpty() && name in PER_ENTRY && source.isDirectory) {
                restoreInto(source, target, "$name/", left)
                source.delete()
                continue
            }
            if (target.exists() || !move(source, target)) left += "$prefix$name"
        }
    }

    private fun move(from: File, to: File): Boolean {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return true
        val copied = runCatching { from.copyRecursively(to, overwrite = false) }.getOrDefault(false)
        if (!copied) {
            to.deleteRecursively()
            return false
        }
        return from.deleteRecursively()
    }

    private fun freeTarget(path: File): File =
        if (!path.exists()) path else File(path.parentFile, "${path.name} (${System.currentTimeMillis() / 1000})")

    /** What the installed pack recorded as its own; null for a build installed before the record existed. */
    fun shippedEntries(profileDir: File): Set<String>? = readShipped(profileDir)

    private fun readShipped(profileDir: File): Set<String>? {
        val file = File(profileDir, SHIPPED_FILE)
        if (!file.isFile) return null
        return try {
            MojangJson.decodeFromString(ListSerializer(String.serializer()), file.readText()).toSet()
        } catch (broken: Exception) {
            null
        }
    }

    private fun names(dir: File): List<String> = dir.list()?.sorted().orEmpty()
}
