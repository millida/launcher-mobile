package net.millida.launcher.core.runtime

import kotlinx.serialization.Serializable
import net.millida.launcher.core.mojang.VersionManifest

/**
 * The server's say on which game versions a phone should be offered, read from the runtime
 * manifest as `"phoneVersions": {"default": "26.2", "risky": ["26.3", "26.4*"]}`. Both keys are optional.
 * [risky] present (even empty) replaces the built-in rule (RenderPearl), so the server can both
 * mark a version and clear one; a trailing `*` matches a prefix.
 */
@Serializable
data class PhoneVersionRules(
    val default: String = "",
    val risky: List<String>? = null,
)

/**
 * Which version a new build on a phone starts with. The newest release is not always playable:
 * RenderPearl versions (26.3+) show a black screen on most phones, so they stay in the list
 * but are never the preselected one.
 */
class PhoneVersions(private val rules: PhoneVersionRules? = null) {

    fun isRisky(id: String, releaseTime: String = ""): Boolean {
        val listed = rules?.risky ?: return GamePipeline.isRenderPearl(id, releaseTime)
        val version = id.trim()
        return listed.any { entry ->
            val pattern = entry.trim()
            when {
                pattern.isEmpty() -> false
                pattern.endsWith("*") -> version.startsWith(pattern.dropLast(1))
                else -> version == pattern
            }
        }
    }

    /** Newest release that is not risky; the server's default wins when that version exists. */
    fun recommended(versions: List<VersionManifest.ManifestEntry>): VersionManifest.ManifestEntry? {
        val releases = versions.filter { it.type == "release" }
        val wanted = rules?.default?.trim().orEmpty()
        if (wanted.isNotEmpty()) releases.firstOrNull { it.id == wanted }?.let { return it }
        return releases.firstOrNull { !isRisky(it.id, it.releaseTime.ifEmpty { it.time }) } ?: releases.firstOrNull()
    }

    /** The same for bare ids (a catalogue item's versions), newest number first. */
    fun recommendedId(ids: List<String>, newest: Comparator<String>): String? {
        val sorted = ids.map { it.trim() }.sortedWith(newest.reversed())
        val wanted = rules?.default?.trim().orEmpty()
        if (wanted.isNotEmpty() && wanted in sorted) return wanted
        return sorted.firstOrNull { !isRisky(it) } ?: sorted.firstOrNull()
    }

    companion object {
        val DEFAULT = PhoneVersions()
    }
}
