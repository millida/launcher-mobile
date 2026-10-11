package net.millida.launcher.core.catalog

import net.millida.launcher.core.loader.LoaderKind

/**
 * «Новая сборка» from a catalogue row: a build for the item's own newest
 * release and loader, so the player never picks a version by hand and never
 * ends up with an item made for another one (desktop: newBuildFrom.ts).
 */
data class NewBuildPlan(val version: String, val loader: LoaderKind)

object NewBuild {
    private const val NAME_MAX = 24
    private val RELEASE = Regex("^\\d+\\.\\d+(\\.\\d+)?$")
    private val LOADER_ORDER = listOf(LoaderKind.FABRIC, LoaderKind.NEOFORGE, LoaderKind.FORGE, LoaderKind.QUILT)

    fun newestRelease(versions: List<String>): String? = versions
        .map { it.trim() }
        .filter { RELEASE.matches(it) }
        .maxWithOrNull { left, right -> compareReleases(left, right) }

    /** Shaders need Iris, and Iris lives on Fabric; packs of textures and data need no loader at all. */
    fun plan(kind: String, versions: List<String>, loaders: List<String>): NewBuildPlan? {
        val version = newestRelease(versions) ?: return null
        val ids = loaders.map { it.trim().lowercase() }.toSet()
        return when (kind) {
            "mod" -> LOADER_ORDER.firstOrNull { it.id in ids }?.let { NewBuildPlan(version, it) }
            "shader" -> NewBuildPlan(version, LoaderKind.FABRIC)
            "resourcepack", "datapack" -> NewBuildPlan(version, LoaderKind.VANILLA)
            else -> null
        }
    }

    fun name(title: String): String = title.trim().take(NAME_MAX)

    private fun compareReleases(left: String, right: String): Int {
        val first = left.split('.').map { it.toIntOrNull() ?: 0 }
        val second = right.split('.').map { it.toIntOrNull() ?: 0 }
        for (part in 0 until 3) {
            val diff = first.getOrElse(part) { 0 } - second.getOrElse(part) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}
