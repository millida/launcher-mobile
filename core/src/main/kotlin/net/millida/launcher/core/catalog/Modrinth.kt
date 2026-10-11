package net.millida.launcher.core.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * Both catalogues are reached through our own proxy: the key for CurseForge
 * stays on the server, and the answers still arrive where a direct connection to
 * those content networks is blocked - on mobile networks that is common.
 */
private const val MODRINTH_API = "${net.millida.launcher.core.account.MILLIDA_API}/launcher/mr/v2"

private const val MODRINTH_SITE = "https://modrinth.com"

/** Which catalogue an item came from: they are installed the same way but asked differently. */
enum class CatalogSource(val id: String, val label: String) {
    MODRINTH("modrinth", "Modrinth"),
    CURSEFORGE("curseforge", "CurseForge"),
}

enum class ContentKind(val slug: String, val folder: String) {
    MOD("mod", "mods"),
    RESOURCE_PACK("resourcepack", "resourcepacks"),
    SHADER("shader", "shaderpacks"),
    DATA_PACK("datapack", "datapacks"),
}

@Serializable
data class CatalogItem(
    @SerialName("project_id") val projectId: String = "",
    val slug: String = "",
    val title: String = "",
    val description: String = "",
    val downloads: Long = 0,
    @SerialName("icon_url") val iconUrl: String? = null,
    val categories: List<String> = emptyList(),
    @SerialName("project_type") val projectType: String = "mod",
    val source: CatalogSource = CatalogSource.MODRINTH,
    val author: String = "",
    val gallery: List<String> = emptyList(),
    @SerialName("featured_gallery") val featuredGallery: String? = null,
    @SerialName("versions") val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val pageUrl: String = "",
) {
    /** The same project id can exist in both catalogues, so the source is part of the identity. */
    val key: String get() = "${source.id}:$projectId"

    val cover: String? get() = featuredGallery?.takeIf { it.isNotBlank() } ?: gallery.firstOrNull { it.isNotBlank() }

    /** Modrinth lists loaders among the categories; CurseForge reports them apart. */
    val loaderIds: List<String>
        get() = loaders.ifEmpty { categories.filter { it.lowercase() in LOADER_IDS } }.map { it.lowercase() }.distinct()
}

private val LOADER_IDS = setOf(
    "fabric", "quilt", "forge", "neoforge", "iris", "optifine", "canvas", "vanilla", "minecraft", "datapack",
)

@Serializable
private data class SearchResponse(
    val hits: List<CatalogItem> = emptyList(),
    @SerialName("total_hits") val totalHits: Int = 0,
)

@Serializable
private data class ProjectResponse(
    val slug: String = "",
    val body: String = "",
    @SerialName("project_type") val projectType: String = "mod",
    val gallery: List<GalleryImage> = emptyList(),
)

@Serializable
private data class GalleryImage(
    val url: String = "",
    val featured: Boolean = false,
    val ordering: Int = 0,
)

internal data class SourcePage(
    val items: List<CatalogItem>,
    val fetched: Int,
    val total: Int,
)

@Serializable
data class CatalogFile(
    val url: String,
    val filename: String,
    val size: Long = 0,
    val hashes: Map<String, String> = emptyMap(),
    val primary: Boolean = false,
)

@Serializable
data class CatalogVersion(
    val id: String,
    val name: String = "",
    @SerialName("version_number") val versionNumber: String = "",
    @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    @SerialName("version_type") val versionType: String = "release",
    val files: List<CatalogFile> = emptyList(),
    val dependencies: List<Dependency> = emptyList(),
    @SerialName("project_id") val projectId: String = "",
    val source: CatalogSource = CatalogSource.MODRINTH,
) {
    @Serializable
    data class Dependency(
        @SerialName("project_id") val projectId: String? = null,
        @SerialName("version_id") val versionId: String? = null,
        @SerialName("dependency_type") val dependencyType: String = "required",
    )

    fun primaryFile(): CatalogFile? = files.firstOrNull { it.primary } ?: files.firstOrNull()
}

class ModrinthCatalog(
    private val downloader: Downloader,
    private val apiBase: String = MODRINTH_API,
) {

    internal suspend fun searchPage(
        query: String,
        section: CatalogSection,
        gameVersion: String?,
        loaders: List<LoaderKind>,
        offset: Int,
        limit: Int,
    ): SourcePage {
        val facets = ArrayList<String>()
        facets += """["project_type:${section.modrinthType}"]"""
        gameVersion?.takeIf { it.isNotBlank() }?.let { facets += """["versions:$it"]""" }
        val wanted = loaders.filter { it != LoaderKind.VANILLA }
        if (section.filtersByLoader && wanted.isNotEmpty()) {
            facets += wanted.joinToString(",", "[", "]") { "\"categories:${it.id}\"" }
        }
        val url = buildString {
            append("$apiBase/search?limit=$limit&offset=${offset.coerceAtLeast(0)}")
            append("&index=").append(if (query.isBlank()) "downloads" else "relevance")
            if (query.isNotBlank()) append("&query=").append(encode(query))
            append("&facets=").append(encode("[${facets.joinToString(",")}]"))
        }
        val body = downloader.text(url, mapOf("User-Agent" to USER_AGENT))
        val parsed = MojangJson.decodeFromString(SearchResponse.serializer(), body)
        val items = parsed.hits.map { hit ->
            hit.copy(
                source = CatalogSource.MODRINTH,
                pageUrl = hit.pageUrl.ifEmpty {
                    "$MODRINTH_SITE/${section.modrinthType}/${hit.slug.ifEmpty { hit.projectId }}"
                },
            )
        }
        return SourcePage(items, parsed.hits.size, parsed.totalHits)
    }

    suspend fun project(projectId: String): CatalogProject {
        val body = downloader.text("$apiBase/project/$projectId", mapOf("User-Agent" to USER_AGENT))
        val parsed = MojangJson.decodeFromString(ProjectResponse.serializer(), body)
        val gallery = parsed.gallery
            .sortedWith(compareByDescending<GalleryImage> { it.featured }.thenBy { it.ordering })
            .map { it.url }
            .filter { it.isNotBlank() }
        return CatalogProject(
            body = parsed.body,
            gallery = gallery,
            pageUrl = "$MODRINTH_SITE/${parsed.projectType}/${parsed.slug.ifEmpty { projectId }}",
        )
    }

    suspend fun versions(projectId: String, gameVersion: String, loader: LoaderKind): List<CatalogVersion> =
        versionsFor(projectId, gameVersion, if (loader == LoaderKind.VANILLA) emptyList() else listOf(loader.id))

    /** A null version or an empty loader list means "any": a resource pack has no loader to match. */
    suspend fun versionsFor(projectId: String, gameVersion: String?, loaders: List<String>): List<CatalogVersion> {
        val filters = ArrayList<String>()
        gameVersion?.takeIf { it.isNotBlank() }?.let { filters += "game_versions=[\"$it\"]" }
        if (loaders.isNotEmpty()) filters += "loaders=" + loaders.joinToString(",", "[", "]") { "\"$it\"" }
        val query = if (filters.isEmpty()) "" else "?" + filters.joinToString("&")
        val body = downloader.text(
            encodeQuery("$apiBase/project/$projectId/version$query"),
            mapOf("User-Agent" to USER_AGENT),
        )
        return MojangJson.decodeFromString(ListSerializer(CatalogVersion.serializer()), body)
            .map { version -> if (version.projectId.isEmpty()) version.copy(projectId = projectId) else version }
    }

    suspend fun install(
        version: CatalogVersion,
        kind: ContentKind,
        profileDir: File,
        onProgress: (Float) -> Unit = {},
    ): File {
        val file = version.primaryFile() ?: throw IOException("у версии нет файла для скачивания")
        val folder = File(profileDir, kind.folder).also { it.mkdirs() }
        val target = SafePath.join(folder, SafePath.fileName(file.filename))
        var received = 0L
        downloader.fetch(
            RemoteFile(
                url = file.url,
                target = target,
                sha1 = file.hashes["sha1"],
                size = file.size,
            )
        ) { chunk ->
            received = if (chunk < 0) 0L else received + chunk
            if (file.size > 0) onProgress((received.toFloat() / file.size).coerceIn(0f, 1f))
        }
        return target
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encodeQuery(url: String): String =
        url.replace("[", "%5B").replace("]", "%5D").replace("\"", "%22")

    companion object {
        const val USER_AGENT = "millida-mobile/1.0 (support@millida.net)"
        val ALLOWED_HOSTS = setOf(
            "api.millida.net",
            "cdn.millida.trade",
            "cdn.modrinth.com",
            "api.modrinth.com",
            "edge.forgecdn.net",
            "mediafilez.forgecdn.net",
            "media.forgecdn.net",
        )
    }
}
