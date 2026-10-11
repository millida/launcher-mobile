package net.millida.launcher.ui.home

import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.VersionManifest
import net.millida.launcher.core.runtime.GamePipeline

internal enum class LoaderGate { Ready, Unsupported }

private val NUMERIC_ID = Regex("^\\d+(\\.\\d+)*$")

/** Release dates of the lines the phone treats differently; snapshots have no number to compare, only a date. */
private const val FLATTENING_RELEASE = "2018-07-18"

/** Fabric meta answers 400 below 1.14 (its first snapshot is 18w43b); Quilt starts no earlier. */
private val FABRIC_FIRST = listOf(1, 14)
private const val FABRIC_FIRST_RELEASE = "2018-10-24"

/** NeoForge forked at 1.20.1: there is no build for anything older. */
private val NEOFORGE_FIRST = listOf(1, 20, 1)
private const val NEOFORGE_FIRST_RELEASE = "2023-06-12"

private fun numbers(id: String): List<Int>? {
    val trimmed = id.trim()
    if (!NUMERIC_ID.matches(trimmed)) return null
    return trimmed.split('.').map { it.toInt() }
}

private fun compareNumbers(left: List<Int>, right: List<Int>): Int {
    for (index in 0 until maxOf(left.size, right.size)) {
        val compared = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
        if (compared != 0) return compared
    }
    return 0
}

/** Before 1.13 the game needs Java 8 and the old window library, which the phone runs only partly. */
internal fun isLegacyVersion(entry: VersionManifest.ManifestEntry?): Boolean =
    olderThan(entry, listOf(1, 13), FLATTENING_RELEASE)

private fun olderThan(entry: VersionManifest.ManifestEntry?, first: List<Int>, firstReleased: String): Boolean {
    if (entry == null) return false
    val parsed = numbers(entry.id)
    if (parsed != null) return compareNumbers(parsed, first) < 0
    val released = entry.releaseTime.ifEmpty { entry.time }
    return released.isNotEmpty() && released < firstReleased
}

/** After 1.21.4 the game draws through a new pipeline, and the picture tears in places on a phone. */
internal fun isNewRenderer(entry: VersionManifest.ManifestEntry?): Boolean {
    if (entry == null) return false
    return GamePipeline.isBlaze3d(entry.id, entry.releaseTime.ifEmpty { entry.time })
}

/**
 * Forge before 1.13 needs Java 8, which the runtime pack does not carry, and
 * Fabric, Quilt and NeoForge publish nothing for versions older than their
 * first: offering them would only end in a failed creation.
 */
internal fun loaderGate(loader: LoaderKind, entry: VersionManifest.ManifestEntry?): LoaderGate {
    val missing = when (loader) {
        LoaderKind.VANILLA -> false
        LoaderKind.FABRIC, LoaderKind.QUILT -> olderThan(entry, FABRIC_FIRST, FABRIC_FIRST_RELEASE)
        LoaderKind.NEOFORGE -> olderThan(entry, NEOFORGE_FIRST, NEOFORGE_FIRST_RELEASE)
        LoaderKind.FORGE -> isLegacyVersion(entry)
    }
    return if (missing) LoaderGate.Unsupported else LoaderGate.Ready
}

internal fun filterVersions(
    all: List<VersionManifest.ManifestEntry>,
    query: String,
    snapshots: Boolean,
): List<VersionManifest.ManifestEntry> {
    val needle = query.trim().lowercase()
    return all.filter { entry ->
        (snapshots || entry.type == "release") && (needle.isEmpty() || entry.id.lowercase().contains(needle))
    }
}
