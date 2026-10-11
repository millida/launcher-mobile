package net.millida.launcher.core.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.catalog.ModrinthCatalog
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.mojang.MojangJson
import java.io.IOException

data class HashMatch(
    val projectId: String,
    val versionId: String,
    val versionNumber: String,
)

data class ProjectMeta(
    val projectId: String,
    val slug: String,
    val title: String,
) {
    fun names(): Array<String> = arrayOf(projectId, slug, title)
}

/**
 * The two questions the catalogue answers about a file that is already on disk:
 * "which project are these bytes" and "what is this project called". Searching
 * and installing live in Catalog; this only identifies, and so stays here
 * instead of growing a second copy of either.
 */
class CatalogLookup(
    private val downloader: Downloader,
    private val apiBase: String = "$MILLIDA_API/launcher/mr/v2",
) {
    private val projects = HashMap<String, ProjectMeta?>()

    suspend fun byHash(sha1: String): HashMatch? {
        if (sha1.length != 40) return null
        val body = fetch("$apiBase/version_file/${sha1.lowercase()}?algorithm=sha1") ?: return null
        val parsed = runCatching {
            MojangJson.decodeFromString(VersionFileResponse.serializer(), body)
        }.getOrNull() ?: return null
        if (parsed.projectId.isBlank() || parsed.id.isBlank()) return null
        return HashMatch(parsed.projectId, parsed.id, parsed.versionNumber)
    }

    suspend fun project(projectId: String): ProjectMeta? {
        if (projectId.isBlank()) return null
        projects[projectId]?.let { return it }
        if (projects.containsKey(projectId)) return null
        val body = fetch("$apiBase/project/$projectId")
        val meta = body?.let {
            runCatching { MojangJson.decodeFromString(ProjectResponse.serializer(), it) }.getOrNull()
        }?.let { ProjectMeta(it.id.ifBlank { projectId }, it.slug, it.title) }
        projects[projectId] = meta
        return meta
    }

    /**
     * A miss is an answer here, not a failure: unknown files are ordinary, and
     * turning a dead catalogue into an exception would stop a whole update plan
     * over one file nobody can name.
     */
    private suspend fun fetch(url: String): String? = try {
        downloader.text(url, mapOf("User-Agent" to ModrinthCatalog.USER_AGENT))
    } catch (offline: IOException) {
        null
    }

    @Serializable
    private data class VersionFileResponse(
        val id: String = "",
        @SerialName("project_id") val projectId: String = "",
        @SerialName("version_number") val versionNumber: String = "",
    )

    @Serializable
    private data class ProjectResponse(
        val id: String = "",
        val slug: String = "",
        val title: String = "",
    )
}
