package net.millida.launcher.core.packs

import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileStore

/**
 * What an installed pack is called in the list. The desktop's
 * `modpack_profile_name`: the pack's own name, and its version only when a
 * build of that name already exists. The version the screens show is the
 * game's; the pack's version stays in [PackOrigin] for updates.
 */
object PackNames {

    fun fresh(packName: String, versionName: String, taken: Set<String>): String {
        val base = baseName(packName)
        if (base !in taken) return base
        val version = versionName.trim()
        return unique(if (version.isEmpty()) base else "$base $version".take(MAX_LENGTH), taken)
    }

    /**
     * Builds installed before carry «name version» as their name. Only that
     * exact automatic label is replaced, a name the player typed stays, and
     * the folder is never touched: it hangs on the build id, not on the name.
     */
    fun renamed(current: String, origin: PackOrigin?, taken: Set<String>): String? {
        if (origin == null || origin.versionName.isBlank()) return null
        val base = baseName(origin.name)
        if (current != "$base ${origin.versionName}".take(MAX_LENGTH)) return null
        return base.takeIf { it !in taken }
    }

    fun migrate(store: ProfileStore): List<Profile> {
        val profiles = store.load()
        val names = profiles.map { it.name }.toMutableList()
        var changed = false
        val result = profiles.mapIndexed { index, profile ->
            val origin = PackOriginStore.read(store.directory(profile))?.takeIf { it.buildId.isEmpty() || it.buildId == profile.id }
            val others = names.filterIndexed { other, _ -> other != index }.toSet()
            val name = renamed(profile.name, origin, others) ?: return@mapIndexed profile
            names[index] = name
            changed = true
            profile.copy(name = name)
        }
        if (changed) store.save(result)
        return result
    }

    private fun baseName(packName: String): String = packName.trim().ifEmpty { FALLBACK }.take(MAX_LENGTH)

    private fun unique(base: String, taken: Set<String>): String {
        var name = base
        var index = 2
        while (name in taken) {
            name = "$base ($index)"
            index++
        }
        return name
    }

    private const val MAX_LENGTH = 80
    private const val FALLBACK = "Сборка"
}
