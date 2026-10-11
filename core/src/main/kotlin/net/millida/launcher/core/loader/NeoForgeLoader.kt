package net.millida.launcher.core.loader

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import net.millida.launcher.core.mojang.MojangJson

const val NEOFORGE_VERSIONS_URL =
    "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge"
const val NEOFORGE_MAVEN_BASE = "https://maven.neoforged.net/releases/net/neoforged/neoforge"

/**
 * NeoForge for 1.20.1 is the fork's first branch: it is published as
 * `net.neoforged:forge` with builds named "1.20.1-47.1.106", and the
 * `neoforge` artifact has nothing for that version at all.
 */
const val NEOFORGE_LEGACY_GAME = "1.20.1"
const val NEOFORGE_LEGACY_VERSIONS_URL =
    "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/forge"
const val NEOFORGE_LEGACY_MAVEN_BASE = "https://maven.neoforged.net/releases/net/neoforged/forge"

/**
 * A prerelease build is marked by a qualifier ("21.1.0-beta",
 * "26.1.0.0-alpha.1+snapshot-1"); release numbers carry digits only. The
 * 1.20.1 branch puts the game version before its own number with a dash.
 */
fun neoForgePrerelease(build: String): Boolean = build.removePrefix("$NEOFORGE_LEGACY_GAME-").contains('-')

/** Builds of the 1.20.1 branch, newest first. */
fun neoForgeLegacyBuilds(versionsJson: String, gameVersion: String): List<String> {
    if (gameVersion != NEOFORGE_LEGACY_GAME) return emptyList()
    val list = (MojangJson.parseToJsonElement(versionsJson) as? JsonObject)
        ?.get("versions") as? JsonArray ?: return emptyList()
    return list.mapNotNull { it.asText() }
        .filter { it.startsWith("$gameVersion-") }
        .sortedWith(compareBy(NUMERIC) { it })
        .asReversed()
}

/**
 * NeoForge tracks the Minecraft version: "1.A.B" -> "A.B.", "1.A" -> "A.0."
 * (MC 1.21.1 -> 21.1.x, MC 1.21 -> 21.0.x). Year-based releases keep their own
 * number and gain a patch element instead (MC 26.2 -> 26.2.0.x), so the longer
 * prefix is tried first and the plain one covers a future patch layout.
 */
fun neoForgePrefixes(gameVersion: String): List<String> {
    val parts = gameVersion.split('.')
    if (parts.firstOrNull() == "1") {
        val major = parts.getOrNull(1).orEmpty()
        val minor = parts.getOrNull(2) ?: "0"
        return listOf("$major.$minor.")
    }
    return if (parts.size == 2) listOf("$gameVersion.0.", "$gameVersion.") else listOf("$gameVersion.")
}

/** Builds for one Minecraft version, newest first, prereleases included. */
fun neoForgeBuilds(versionsJson: String, gameVersion: String): List<String> {
    val list = (MojangJson.parseToJsonElement(versionsJson) as? JsonObject)
        ?.get("versions") as? JsonArray ?: return emptyList()
    val all = list.mapNotNull { it.asText() }
    for (prefix in neoForgePrefixes(gameVersion)) {
        val candidates = all.filter { it.startsWith(prefix) }
        if (candidates.isEmpty()) continue
        // Sorting by string puts 21.1.9 above 21.1.248, and the recommended build
        // would then be the wrong one.
        return candidates.sortedWith(compareBy(NUMERIC) { it }).asReversed()
    }
    return emptyList()
}

private val NUMERIC = Comparator<String> { left, right ->
    val a = numberKey(left)
    val b = numberKey(right)
    var index = 0
    while (index < maxOf(a.size, b.size)) {
        val diff = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
        if (diff != 0) return@Comparator diff
        index += 1
    }
    left.compareTo(right)
}

private fun numberKey(version: String): List<Long> =
    version.split('.', '-', '+').map { part ->
        part.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L
    }

class NeoForgeVersions(
    private val mirror: LoaderMirror,
    private val versionsUrl: String = NEOFORGE_VERSIONS_URL,
    private val mavenBase: String = NEOFORGE_MAVEN_BASE,
    private val legacyVersionsUrl: String = NEOFORGE_LEGACY_VERSIONS_URL,
    private val legacyMavenBase: String = NEOFORGE_LEGACY_MAVEN_BASE,
) : ForgeVersionSource {
    override val kind: LoaderKind = LoaderKind.NEOFORGE

    override suspend fun builds(gameVersion: String): List<LoaderVersion> {
        val found = if (gameVersion == NEOFORGE_LEGACY_GAME) {
            neoForgeLegacyBuilds(mirror.text(legacyVersionsUrl), gameVersion)
        } else {
            neoForgeBuilds(mirror.text(versionsUrl), gameVersion)
        }
        return found.map { LoaderVersion(it, stable = !neoForgePrerelease(it)) }
    }

    /**
     * A release is preferred, but a branch that only ever shipped prereleases
     * still has to be installable.
     */
    override suspend fun recommended(gameVersion: String): String? {
        val builds = builds(gameVersion)
        return (builds.firstOrNull { it.stable } ?: builds.firstOrNull())?.version
    }

    override fun installers(gameVersion: String, build: String): List<ForgeInstallerRef> {
        if (gameVersion == NEOFORGE_LEGACY_GAME) {
            // A CurseForge manifest names the 1.20.1 branch by its own number ("neoforge-47.1.106").
            val full = if (build.startsWith("$gameVersion-")) build else "$gameVersion-$build"
            return listOf(
                ForgeInstallerRef(
                    url = "$legacyMavenBase/$full/forge-$full-installer.jar",
                    versionId = "${LoaderKind.NEOFORGE.id}-$full",
                )
            )
        }
        return listOf(
            ForgeInstallerRef(
                url = "$mavenBase/$build/neoforge-$build-installer.jar",
                versionId = "${LoaderKind.NEOFORGE.id}-$build",
            )
        )
    }
}
