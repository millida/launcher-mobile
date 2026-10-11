package net.millida.launcher

import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.profile.Profile

sealed interface JoinPlan {
    data class Create(val version: String) : JoinPlan

    data class Unknown(val build: Profile) : JoinPlan

    data class Launch(val build: Profile) : JoinPlan

    data class Switch(val build: Profile) : JoinPlan

    data class Mismatch(val build: Profile) : JoinPlan
}

private val NUMERIC_VERSION = Regex("^\\d+(\\.\\d+)*$")
private val PING_RANGE = Regex("\\d+\\.\\d+(\\.\\d+)?\\s*[-–—]\\s*\\d+\\.\\d+(\\.\\d+)?")
private val PING_WILDCARD = Regex("\\.([xX*])\\b")
private val PING_TOKEN = Regex("\\d{1,4}\\.\\d{1,2}(\\.\\d{1,3})?")

/** The rating names a server by major line ("1.21"), a build carries a full release ("1.21.4"). */
fun versionMajor(version: String): String {
    val trimmed = version.trim()
    val parts = trimmed.split('.')
    return if (parts.size > 2) parts.take(2).joinToString(".") else trimmed
}

fun versionFits(buildVersion: String, wanted: List<String>): Boolean {
    if (wanted.isEmpty()) return true
    val build = buildVersion.trim()
    if (build.isEmpty()) return false
    return wanted.any { it == build || versionMajor(it) == versionMajor(build) }
}

fun knownVersions(releaseIds: List<String>): Set<String> {
    val known = HashSet<String>()
    for (raw in releaseIds) {
        val id = raw.trim()
        if (!NUMERIC_VERSION.matches(id)) continue
        known += id
        known += versionMajor(id)
    }
    return known
}

/**
 * Server owners type their versions by hand, so releases that never existed
 * ("1.22".."1.29") reach the launcher. Only Mojang's list tells a typo from a
 * release; while that list is unknown every numeric version is kept.
 */
fun serverVersions(reported: List<String>, known: Set<String>): List<String> {
    val out = ArrayList<String>()
    for (raw in reported) {
        val version = raw.trim()
        if (!NUMERIC_VERSION.matches(version) || version in out) continue
        if (known.isNotEmpty() && version !in known) continue
        out += version
    }
    return out
}

/**
 * A ping answers with free text: "Paper 1.20.1", "1.16.5", or a proxy range
 * "1.8.x-1.21.x". An exact version is taken; a range admits any client in it,
 * so there is nothing to object to.
 */
fun pingVersions(reported: String, known: Set<String>): List<String> {
    val text = reported.trim()
    if (text.isEmpty() || PING_RANGE.containsMatchIn(text.replace(PING_WILDCARD, ""))) return emptyList()
    val first = PING_TOKEN.find(text)?.value ?: return emptyList()
    return serverVersions(listOf(first), known)
}

/** A server is joined with a clean build: Forge, NeoForge and Quilt builds are modpacks a server does not expect. */
fun joinableBuilds(profiles: List<Profile>): List<Profile> =
    profiles.filter { it.loader == LoaderKind.VANILLA || it.loader == LoaderKind.FABRIC }

fun pickBuildForServer(builds: List<Profile>, wanted: List<String>): Profile? {
    if (wanted.isEmpty()) return null
    return builds.firstOrNull { it.gameVersion.trim() in wanted }
        ?: builds.firstOrNull { versionFits(it.gameVersion, wanted) }
}

/** [available] is newest first, so a major line resolves to its latest patch. */
fun pickVersionForServer(available: List<String>, wanted: List<String>): String {
    for (version in wanted) if (version in available) return version
    for (version in wanted) {
        val near = available.firstOrNull { versionMajor(it) == versionMajor(version) }
        if (near != null) return near
    }
    return ""
}

fun joinPlan(builds: List<Profile>, selectedId: String?, wanted: List<String>): JoinPlan {
    if (builds.isEmpty()) return JoinPlan.Create(wanted.firstOrNull().orEmpty())
    val current = builds.firstOrNull { it.id == selectedId } ?: builds.first()
    if (wanted.isEmpty()) return JoinPlan.Unknown(current)
    if (versionFits(current.gameVersion, wanted)) return JoinPlan.Launch(current)
    val fit = pickBuildForServer(builds, wanted)
    if (fit != null) return JoinPlan.Switch(fit)
    return JoinPlan.Mismatch(current)
}
