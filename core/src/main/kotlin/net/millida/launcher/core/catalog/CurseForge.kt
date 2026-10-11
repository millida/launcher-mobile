package net.millida.launcher.core.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.MojangJson
import java.io.IOException
import java.net.URLEncoder

/**
 * The second half of the content the desktop launcher offers. Both catalogues go
 * through our own proxy: the key for CurseForge stays on the server, and players
 * whose connection to those content networks is broken still get the files.
 */
class CurseForgeCatalog(
    private val downloader: Downloader,
    private val apiBase: String = "$MILLIDA_API/launcher/cf",
) {
    suspend fun search(
        query: String,
        kind: ContentKind,
        gameVersion: String?,
        loader: LoaderKind,
        offset: Int = 0,
        limit: Int = 20,
    ): List<CatalogItem> = searchPage(
        query = query,
        section = CatalogSection.of(kind),
        gameVersion = gameVersion,
        loaders = if (loader == LoaderKind.VANILLA) emptyList() else listOf(loader),
        offset = offset,
        limit = limit,
    ).items

    internal suspend fun searchPage(
        query: String,
        section: CatalogSection,
        gameVersion: String?,
        loaders: List<LoaderKind>,
        offset: Int,
        limit: Int,
    ): SourcePage {
        val size = limit.coerceIn(1, MAX_PAGE)
        val index = offset.coerceIn(0, MAX_WINDOW - size)
        val single = loaders.singleOrNull()?.let { loaderOf(it) }
        val url = buildString {
            append("$apiBase/v1/mods/search?gameId=$MINECRAFT")
            append("&classId=").append(section.curseForgeClass)
            append("&index=$index&pageSize=$size")
            append("&sortField=").append(if (query.isBlank()) SORT_DOWNLOADS else SORT_POPULARITY)
            append("&sortOrder=desc")
            if (query.isNotBlank()) append("&searchFilter=").append(encode(query))
            gameVersion?.takeIf { it.isNotEmpty() }?.let { append("&gameVersion=").append(encode(it)) }
            if (section == CatalogSection.MOD && single != null) append("&modLoaderType=").append(single)
        }
        val body = downloader.text(url)
        val root = MojangJson.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return SourcePage(emptyList(), 0, 0)
        val parsed = data.mapNotNull { entry -> itemOf(entry.jsonObject, section) }
        val wanted = loaders.map { it.id }.toSet()
        // CurseForge cannot be asked for modpacks by loader, so the ones the
        // phone cannot start are dropped here instead of failing after the download.
        val items = if (section == CatalogSection.MODPACK && wanted.isNotEmpty()) {
            parsed.filter { item -> item.loaders.isEmpty() || item.loaders.any { it in wanted } }
        } else {
            parsed
        }
        val total = root["pagination"]?.jsonObject?.get("totalCount")?.jsonPrimitive?.intOrNull
            ?: (index + data.size)
        return SourcePage(items, data.size, total)
    }

    private fun itemOf(item: JsonObject, section: CatalogSection): CatalogItem? {
        val id = item["id"]?.jsonPrimitive?.intOrNull ?: return null
        val indexes = item["latestFilesIndexes"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .orEmpty()
        return CatalogItem(
            projectId = id.toString(),
            slug = item.text("slug"),
            title = item.text("name"),
            description = item.text("summary"),
            downloads = item["downloadCount"]?.jsonPrimitive?.let {
                runCatching { it.long }.getOrDefault(0L)
            } ?: 0L,
            iconUrl = item.child("logo")?.text("thumbnailUrl")?.ifEmpty { null }
                ?: item.child("logo")?.text("url")?.ifEmpty { null },
            categories = item.list("categories").mapNotNull { it.text("name") }.filter { it.isNotEmpty() },
            projectType = section.modrinthType,
            source = CatalogSource.CURSEFORGE,
            author = item.list("authors").firstOrNull()?.text("name").orEmpty(),
            gallery = item.list("screenshots")
                .map { it.text("thumbnailUrl").ifEmpty { it.text("url") } }
                .filter { it.isNotEmpty() },
            gameVersions = indexes.map { it.text("gameVersion") }.filter { it.isNotEmpty() }.distinct(),
            loaders = indexes
                .mapNotNull { index -> index["modLoader"]?.jsonPrimitive?.intOrNull?.let { loaderIdOf(it) } }
                .distinct(),
            pageUrl = item.child("links")?.text("websiteUrl").orEmpty(),
        )
    }

    suspend fun project(projectId: String): CatalogProject {
        val body = downloader.text("$apiBase/v1/mods/$projectId")
        val data = MojangJson.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?: throw IOException("CurseForge не нашёл этот проект — обнови список")
        val description = try {
            val answer = downloader.text("$apiBase/v1/mods/$projectId/description")
            MojangJson.parseToJsonElement(answer).jsonObject["data"]?.jsonPrimitive?.content.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (missing: Exception) {
            ""
        }
        return CatalogProject(
            body = description,
            gallery = data.list("screenshots")
                .map { it.text("url").ifEmpty { it.text("thumbnailUrl") } }
                .filter { it.isNotEmpty() },
            pageUrl = data.child("links")?.text("websiteUrl").orEmpty(),
        )
    }

    /**
     * A file of a CurseForge project is what a version is on Modrinth, so it is
     * reported in the same shape: one place in the launcher installs both.
     */
    suspend fun versions(projectId: String, gameVersion: String, loader: LoaderKind): List<CatalogVersion> {
        val url = buildString {
            append("$apiBase/v1/mods/$projectId/files?pageSize=50")
            if (gameVersion.isNotEmpty()) append("&gameVersion=").append(encode(gameVersion))
            loaderOf(loader)?.let { append("&modLoaderType=").append(it) }
        }
        val body = downloader.text(url)
        val data = MojangJson.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { entry ->
            val file = entry.jsonObject
            val id = file["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val name = file.text("fileName")
            val direct = file.text("downloadUrl")
            val tags = file["gameVersions"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content }
                ?: emptyList()
            val tagged = tags.map { it.lowercase() }.filter { it in LOADER_TAGS }
            CatalogVersion(
                id = id.toString(),
                name = file.text("displayName").ifEmpty { name },
                versionNumber = file.text("displayName"),
                gameVersions = tags,
                loaders = tagged.ifEmpty { listOfNotNull(loader.id.takeIf { loader != LoaderKind.VANILLA }) },
                versionType = when (file["releaseType"]?.jsonPrimitive?.intOrNull) {
                    2 -> "beta"
                    3 -> "alpha"
                    else -> "release"
                },
                files = listOf(
                    CatalogFile(
                        url = direct,
                        filename = name,
                        size = file["fileLength"]?.jsonPrimitive?.let {
                            runCatching { it.long }.getOrDefault(0L)
                        } ?: 0L,
                        hashes = file["hashes"]?.jsonArray?.mapNotNull { hash ->
                            val entryObject = hash.jsonObject
                            val algorithm = entryObject["algo"]?.jsonPrimitive?.intOrNull
                            val value = entryObject.text("value")
                            when {
                                value.isEmpty() -> null
                                algorithm == 1 -> "sha1" to value
                                algorithm == 2 -> "md5" to value
                                else -> null
                            }
                        }?.toMap() ?: emptyMap(),
                        primary = true,
                    )
                ),
                projectId = projectId,
                source = CatalogSource.CURSEFORGE,
            )
        }
    }

    /**
     * Some projects hide the direct link, and the launcher has to ask for it by
     * file: without this step the install ends on an empty address.
     */
    suspend fun downloadUrl(projectId: String, fileId: String): String {
        val body = downloader.text("$apiBase/v1/mods/$projectId/files/$fileId/download-url")
        val data = MojangJson.parseToJsonElement(body).jsonObject["data"]?.jsonPrimitive?.content
        return data?.takeIf { it.isNotEmpty() } ?: throw IOException("файл недоступен для скачивания")
    }

    private fun loaderOf(loader: LoaderKind): Int? = when (loader) {
        LoaderKind.FABRIC -> 4
        LoaderKind.QUILT -> 5
        LoaderKind.FORGE -> 1
        LoaderKind.NEOFORGE -> 6
        LoaderKind.VANILLA -> null
    }

    private fun loaderIdOf(code: Int): String? = when (code) {
        1 -> LoaderKind.FORGE.id
        4 -> LoaderKind.FABRIC.id
        5 -> LoaderKind.QUILT.id
        6 -> LoaderKind.NEOFORGE.id
        else -> null
    }

    private fun JsonObject.text(key: String): String =
        this[key]?.let { runCatching { it.jsonPrimitive }.getOrNull() }
            ?.let { if (it.content == "null") "" else it.content }
            .orEmpty()

    private fun JsonObject.child(key: String): JsonObject? =
        this[key]?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonObject.list(key: String): List<JsonObject> =
        this[key]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .orEmpty()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val MINECRAFT = 432
        const val SORT_POPULARITY = 2
        const val SORT_DOWNLOADS = 6
        const val MAX_PAGE = 50
        const val MAX_WINDOW = 10_000
        val LOADER_TAGS = setOf("fabric", "quilt", "forge", "neoforge")
    }
}
