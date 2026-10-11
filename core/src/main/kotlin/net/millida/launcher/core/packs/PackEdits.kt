package net.millida.launcher.core.packs

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Whether a catalogue build is still the author's. An update brings the
 * author's mods folder whole, so a build the player changed, or a copy of one,
 * is never brought up to date behind their back.
 */
object PackEdits {
    const val SHIPPED_MODS_FILE = "millida-pack-mods.json"

    private const val MODS = "mods"
    private const val CLOCK_SLACK_MS = 2_000L
    private val MOD_EXTENSIONS = setOf("jar", "zip", "litemod")

    /** What the pack put into `mods/`, written right after it was placed, before anything touched it. */
    @Throws(IOException::class)
    fun recordShippedMods(profileDir: File) {
        val names = enabledMods(profileDir).map { it.first }
        File(profileDir, SHIPPED_MODS_FILE)
            .atomicWriteText(MojangJson.encodeToString(ListSerializer(String.serializer()), names))
    }

    /**
     * Builds installed before the list existed are judged by the install time:
     * a mod file that arrived later is the player's. Without even that the
     * build may hold the player's mods and counts as changed.
     */
    fun modsModified(profileDir: File, installedAt: Long): Boolean {
        val mods = enabledMods(profileDir)
        val shipped = readShippedMods(profileDir)
        if (shipped != null) return shipped != mods.map { it.first }.toSet()
        if (installedAt <= 0) return true
        val limit = installedAt + CLOCK_SLACK_MS
        return mods.any { (_, arrived) -> arrived > limit }
    }

    /** A copy carries the record of the build it was copied from; it is the player's until they claim it. */
    fun copied(origin: PackOrigin, profileId: String): Boolean =
        origin.buildId.isNotEmpty() && origin.buildId != profileId

    fun autoUpdateOn(chosen: Boolean?, modified: Boolean, copied: Boolean): Boolean =
        if (copied) false else chosen ?: !modified

    fun state(profileDir: File, profileId: String, origin: PackOrigin?): PackAutoUpdate {
        if (origin == null || origin.source != PackSource.MILLIDA_CATALOG.id || !catalogSlugValid(origin.id)) {
            return PackAutoUpdate(catalog = false, on = false, modified = false, copied = false, chosen = null)
        }
        val copied = copied(origin, profileId)
        val chosen = origin.autoUpdate.takeUnless { copied }
        val modified = modsModified(profileDir, origin.installedAt)
        return PackAutoUpdate(
            catalog = true,
            on = autoUpdateOn(chosen, modified, copied),
            modified = modified,
            copied = copied,
            chosen = chosen,
        )
    }

    /** The player's answer claims the build: a copy that was switched on is no longer a stranger's copy. */
    @Throws(IOException::class)
    fun choose(profileDir: File, profileId: String, on: Boolean): PackAutoUpdate {
        val origin = PackOriginStore.read(profileDir)
            ?: throw IOException("эта сборка поставлена не из каталога Millida — обновлять её неоткуда")
        if (origin.source != PackSource.MILLIDA_CATALOG.id) {
            throw IOException("эта сборка поставлена не из каталога Millida — обновлять её неоткуда")
        }
        PackOriginStore.write(profileDir, origin.copy(autoUpdate = on, buildId = profileId))
        val saved = state(profileDir, profileId, PackOriginStore.read(profileDir))
        if (saved.chosen != on) {
            throw IOException("настройка не сохранилась — проверь, что на телефоне есть место")
        }
        return saved
    }

    /** Launcher jars come and go at every start; they are not the player's edit. */
    internal fun launcherPlaced(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("millida-") || lower.startsWith("customskinloader")
    }

    internal fun modFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in MOD_EXTENSIONS

    /** `mods/` and one level below it: 1.7.10 packs keep `mods/1.7.10`. A disabled jar is not a mod file. */
    private fun enabledMods(profileDir: File): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        collect(File(profileDir, MODS), "", 0, out)
        return out.sortedBy { it.first }
    }

    private fun collect(dir: File, prefix: String, depth: Int, out: MutableList<Pair<String, Long>>) {
        dir.listFiles().orEmpty().forEach { entry ->
            val name = entry.name
            if (name.startsWith(".")) return@forEach
            val relative = prefix + name
            when {
                entry.isDirectory && depth == 0 -> collect(entry, "$relative/", depth + 1, out)
                entry.isFile && modFile(name) && !launcherPlaced(name) -> out += relative to arrivedAt(entry)
            }
        }
    }

    private fun arrivedAt(file: File): Long = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).creationTime().toMillis()
    } catch (unreadable: Exception) {
        file.lastModified()
    }

    private fun readShippedMods(profileDir: File): Set<String>? {
        val file = File(profileDir, SHIPPED_MODS_FILE)
        if (!file.isFile) return null
        return try {
            MojangJson.decodeFromString(ListSerializer(String.serializer()), file.readText()).toSet()
        } catch (broken: Exception) {
            null
        }
    }
}

data class PackAutoUpdate(
    val catalog: Boolean,
    val on: Boolean,
    val modified: Boolean,
    val copied: Boolean,
    val chosen: Boolean?,
) {
    /** Desktop wording: the switch explains itself only when its state is not what the player would guess. */
    val note: String?
        get() = when {
            on && modified -> "Твои изменения модов заменятся версией автора"
            !on && copied && chosen == null -> "Выключено: это копия сборки"
            !on && modified && chosen == null -> "Выключено: ты менял моды"
            else -> null
        }
}
