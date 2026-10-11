package net.millida.launcher.core.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.io.IOException

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val gameVersion: String,
    val loader: LoaderKind = LoaderKind.VANILLA,
    val loaderVersion: String = "",
    val versionId: String = gameVersion,
    val memoryMb: Int = 0,
    val rendererId: String = "",
    val extraJvmArgs: List<String> = emptyList(),
    val lastPlayedAt: Long = 0,
    val playtimeSec: Long = 0,
    val fixedByMillida: Boolean = false,
) {
    val label: String
        get() = if (loader == LoaderKind.VANILLA) gameVersion else "$gameVersion · ${loader.label}"
}

class ProfileStore(
    private val layout: GameLayout,
    private val onBackupFailure: (IOException) -> Unit = {},
) {
    private val file: File get() = File(layout.root, "profiles.json")
    private val lastGood: File get() = File(layout.root, "profiles.json.bak")

    /**
     * A broken file is moved aside instead of being reported as "no builds":
     * otherwise the next save would overwrite it and lose every build. The
     * copy of the last good save takes its place, so a following upsert keeps
     * the builds instead of saving a list of one.
     */
    fun load(): List<Profile> = synchronized(LOCK) {
        if (!file.isFile) return@synchronized emptyList()
        decode(file) ?: run {
            val broken = File(file.parentFile, "profiles.json.broken")
            if (broken.exists()) broken.delete()
            file.renameTo(broken)
            val restored = if (lastGood.isFile) decode(lastGood) else null
            restored?.also { file.atomicWriteText(encode(it)) }.orEmpty()
        }
    }

    fun save(profiles: List<Profile>) = synchronized(LOCK) {
        val text = encode(profiles)
        file.atomicWriteText(text)
        // The list itself is already written; a missing copy only weakens the repair of a later broken file.
        try {
            lastGood.atomicWriteText(text)
        } catch (error: IOException) {
            onBackupFailure(error)
        }
    }

    fun upsert(profile: Profile): List<Profile> = synchronized(LOCK) {
        val current = load().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) current[index] = profile else current += profile
        save(current)
        current
    }

    fun remove(id: String): List<Profile> = synchronized(LOCK) {
        val remaining = load().filterNot { it.id == id }
        save(remaining)
        remaining
    }

    fun directory(profile: Profile): File = SafePath.join(layout.profiles, profile.id).also { it.mkdirs() }

    /**
     * A folder without a list entry still holds somebody's worlds and mods: a
     * lost list or a half-finished install leaves one, and a new build must not
     * move into it.
     */
    fun newId(name: String): String = synchronized(LOCK) {
        val base = SafePath.fileName(name.lowercase()).ifEmpty { "profile" }
        val folders = layout.profiles.list().orEmpty().toList()
        val taken = load().map { it.id }.toSet() + folders + folders.map { it.lowercase() }
        if (base !in taken) return@synchronized base
        var index = 2
        while ("$base-$index" in taken) index++
        "$base-$index"
    }

    // Duplicate identifiers reach the list from an older file or a restored
    // copy, and the screen that keys its rows by them refuses to draw.
    private fun decode(source: File): List<Profile>? = try {
        MojangJson.decodeFromString(ListSerializer(Profile.serializer()), source.readText()).distinctBy { it.id }
    } catch (error: Exception) {
        null
    }

    private fun encode(profiles: List<Profile>): String =
        MojangJson.encodeToString(ListSerializer(Profile.serializer()), profiles)

    private companion object {
        /** Builds are read and written from several screens and installers at once. */
        val LOCK = Any()
    }
}
