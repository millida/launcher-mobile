package net.millida.launcher.core.profile

import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.runtime.PhoneVersions

/**
 * What the lobby's «Начни отсюда» shows when the player has picked nothing, and the order of the
 * pick list. A plain game version starts fast; a build with a loader is a modpack or a modded
 * game and starts slower, so versions go first.
 */
object StartHere {
    fun isPlain(profile: Profile): Boolean = profile.loader == LoaderKind.VANILLA

    /** Newest plain version the phone plays well (never RenderPearl's black 26.3), else the first build. */
    fun default(profiles: List<Profile>, phone: PhoneVersions): Profile? {
        val plain = profiles.filter { isPlain(it) && !phone.isRisky(it.gameVersion) }
        return plain.maxWithOrNull { left, right -> compare(left.gameVersion, right.gameVersion) }
            ?: profiles.firstOrNull { !phone.isRisky(it.gameVersion) }
            ?: profiles.firstOrNull()
    }

    /** Plain versions first, newest on top; every other build keeps the order it had. */
    fun ordered(profiles: List<Profile>): List<Profile> {
        val (plain, rest) = profiles.partition(::isPlain)
        return plain.sortedWith { left, right -> compare(right.gameVersion, left.gameVersion) } + rest
    }

    private fun compare(left: String, right: String): Int {
        val first = left.split('.').map { it.toIntOrNull() ?: 0 }
        val second = right.split('.').map { it.toIntOrNull() ?: 0 }
        for (part in 0 until maxOf(first.size, second.size)) {
            val diff = first.getOrElse(part) { 0 } - second.getOrElse(part) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}
