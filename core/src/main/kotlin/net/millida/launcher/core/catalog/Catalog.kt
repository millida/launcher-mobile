package net.millida.launcher.core.catalog

import kotlinx.coroutines.CancellationException
import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.packs.ForgeCdn
import net.millida.launcher.core.packs.PackOriginFile
import net.millida.launcher.core.packs.PackOriginStore
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/** What the player is looking through: one catalogue or both at once. */
enum class CatalogFilter(val label: String) {
    ALL("Всё"),
    MODRINTH("Modrinth"),
    CURSEFORGE("CurseForge"),
}

/**
 * A shelf of the catalogue. Modpacks are not content of a build - they become
 * a build of their own - so they have no [kind] and no folder. Maps go into a
 * build's saves, not a content folder, and come from the Millida catalogue
 * only: neither foreign catalogue is searched for them.
 */
enum class CatalogSection(
    val kind: ContentKind?,
    val modrinthType: String,
    val curseForgeClass: Int,
    val filtersByLoader: Boolean,
) {
    MOD(ContentKind.MOD, "mod", 6, true),
    MODPACK(null, "modpack", 4471, true),
    RESOURCE_PACK(ContentKind.RESOURCE_PACK, "resourcepack", 12, false),
    SHADER(ContentKind.SHADER, "shader", 6552, false),
    DATA_PACK(ContentKind.DATA_PACK, "datapack", 6945, false),
    WORLD(null, "", 17, false);

    val foreign: Boolean get() = this != WORLD

    companion object {
        fun of(kind: ContentKind): CatalogSection = entries.first { it.kind == kind }
    }
}

data class CatalogPage(
    val items: List<CatalogItem>,
    val more: Boolean,
)

data class CatalogProject(
    val body: String,
    val gallery: List<String>,
    val pageUrl: String,
)

/**
 * One door to the content for the whole launcher. The screen asks for mods and
 * gets mods; which catalogue answered, and whether the file had to come through
 * our mirror, is decided here and nowhere else.
 */
class Catalog(
    private val downloader: Downloader,
    private val modrinth: ModrinthCatalog = ModrinthCatalog(downloader),
    private val curseforge: CurseForgeCatalog = CurseForgeCatalog(downloader),
    private val mirrorBase: String = "$MILLIDA_API/launcher/dl",
) {
    suspend fun search(
        query: String,
        kind: ContentKind,
        gameVersion: String?,
        loader: LoaderKind,
        filter: CatalogFilter = CatalogFilter.ALL,
        offset: Int = 0,
        limit: Int = 20,
    ): List<CatalogItem> = searchPage(
        query = query,
        section = CatalogSection.of(kind),
        gameVersion = gameVersion,
        loaders = if (loader == LoaderKind.VANILLA) emptyList() else listOf(loader),
        filter = filter,
        offset = offset,
        limit = limit,
    ).items

    /**
     * [offset] is per catalogue: each of them is asked for [limit] results
     * starting there, so the next page is always `offset + limit`.
     */
    suspend fun searchPage(
        query: String,
        section: CatalogSection,
        gameVersion: String?,
        loaders: List<LoaderKind>,
        filter: CatalogFilter = CatalogFilter.ALL,
        offset: Int = 0,
        limit: Int = PAGE_SIZE,
    ): CatalogPage {
        if (!section.foreign) return CatalogPage(emptyList(), false)
        val answers = ArrayList<Result<SourcePage>>(2)
        if (filter != CatalogFilter.CURSEFORGE) {
            answers += attemptSource { modrinth.searchPage(query, section, gameVersion, loaders, offset, limit) }
        }
        if (filter != CatalogFilter.MODRINTH) {
            answers += attemptSource { curseforge.searchPage(query, section, gameVersion, loaders, offset, limit) }
        }

        // One catalogue being down is not a reason to show an empty screen, but
        // both being down has to be said out loud.
        val items = answers.flatMap { it.getOrNull()?.items.orEmpty() }
        if (items.isEmpty()) {
            answers.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        }
        val more = answers.any { answer ->
            val page = answer.getOrNull()
            page != null && page.fetched > 0 && offset + page.fetched < page.total
        }
        return CatalogPage(ranked(items.distinctBy { it.mergeKey }, query), more)
    }

    // A search sorted by downloads buries the exact title under popular fuzzy
    // hits; the sort is stable, so each source's own relevance order survives
    // inside one score.
    private fun ranked(items: List<CatalogItem>, query: String): List<CatalogItem> {
        val phrase = query.trim().lowercase()
        val terms = phrase.split(WORD_BREAK).filter { it.length > 1 }
        if (terms.isEmpty()) return items.sortedByDescending { it.downloads }
        return items.sortedByDescending { item ->
            val title = item.title.lowercase()
            terms.count { title.contains(it) } + if (title.contains(phrase)) terms.size else 0
        }
    }

    suspend fun versions(
        item: CatalogItem,
        gameVersion: String,
        loader: LoaderKind,
    ): List<CatalogVersion> = when (item.source) {
        CatalogSource.MODRINTH -> modrinth.versions(item.projectId, gameVersion, loader)
        CatalogSource.CURSEFORGE -> curseforge.versions(item.projectId, gameVersion, loader)
    }

    /** Versions under a looser filter: a null [gameVersion] or [loader] means any. */
    suspend fun versionsFor(
        item: CatalogItem,
        gameVersion: String?,
        loader: LoaderKind?,
    ): List<CatalogVersion> = when (item.source) {
        CatalogSource.MODRINTH -> modrinth.versionsFor(
            item.projectId,
            gameVersion,
            loader?.takeIf { it != LoaderKind.VANILLA }?.let { listOf(it.id) }.orEmpty(),
        )
        CatalogSource.CURSEFORGE -> curseforge.versions(item.projectId, gameVersion.orEmpty(), loader ?: LoaderKind.VANILLA)
    }

    suspend fun project(item: CatalogItem): CatalogProject = when (item.source) {
        CatalogSource.MODRINTH -> modrinth.project(item.projectId)
        CatalogSource.CURSEFORGE -> curseforge.project(item.projectId)
    }

    suspend fun install(
        version: CatalogVersion,
        kind: ContentKind,
        profileDir: File,
        onProgress: (Float) -> Unit = {},
    ): File {
        if (version.source != CatalogSource.CURSEFORGE || !version.primaryFile()?.url.isNullOrEmpty()) {
            return installWithMirror(version, kind, profileDir, onProgress)
        }
        val primary = version.primaryFile()
        // The CDN address is only taken when a sha1 is known to check the download against.
        val cdn = if (primary == null || primary.hashes["sha1"].isNullOrEmpty()) {
            emptyList()
        } else {
            ForgeCdn.urls(version.id.toLongOrNull() ?: 0, primary.filename)
        }
        for (url in cdn) {
            val ready = version.withUrl(url)
            try {
                return modrinth.install(ready, kind, profileDir, onProgress)
                    .also { remember(ready, kind, profileDir, it) }
            } catch (error: IOException) {
                continue
            }
        }
        val address = try {
            curseforge.downloadUrl(version.projectId, version.id)
        } catch (error: IOException) {
            val first = cdn.firstOrNull() ?: throw error
            val mirrored = version.withUrl(mirror(first))
            return modrinth.install(mirrored, kind, profileDir, onProgress)
                .also { remember(version.withUrl(first), kind, profileDir, it) }
        }
        return installWithMirror(version.withUrl(address), kind, profileDir, onProgress)
    }

    private suspend fun installWithMirror(
        ready: CatalogVersion,
        kind: ContentKind,
        profileDir: File,
        onProgress: (Float) -> Unit,
    ): File {
        val installed = try {
            modrinth.install(ready, kind, profileDir, onProgress)
        } catch (error: IOException) {
            // A blocked content network is the usual reason a download dies on a
            // phone; the same file comes through our mirror.
            val mirrored = ready.copy(files = ready.files.map { it.copy(url = mirror(it.url)) })
            modrinth.install(mirrored, kind, profileDir, onProgress)
        }
        remember(ready, kind, profileDir, installed)
        return installed
    }

    private fun CatalogVersion.withUrl(url: String): CatalogVersion = copy(files = files.map { it.copy(url = url) })

    /**
     * The record is written after the file is there, never before: a failed
     * download must not leave a build claiming a mod it does not have.
     */
    private fun remember(version: CatalogVersion, kind: ContentKind, profileDir: File, file: File) {
        val primary = version.primaryFile()
        val relative = runCatching {
            file.relativeTo(profileDir).path.replace(File.separatorChar, '/')
        }.getOrNull().orEmpty()
        if (relative.isEmpty() || relative.startsWith("..")) return
        runCatching {
            PackOriginStore.record(
                profileDir,
                PackOriginFile(
                    path = relative,
                    url = primary?.url.orEmpty(),
                    sha1 = primary?.hashes?.get("sha1").orEmpty(),
                    size = if (file.isFile) file.length() else primary?.size ?: 0,
                    projectId = version.projectId,
                    versionId = version.id,
                ),
            )
        }
    }

    private fun mirror(url: String): String =
        if (url.startsWith(mirrorBase)) url else "$mirrorBase?url=" + URLEncoder.encode(url, "UTF-8")

    companion object {
        const val PAGE_SIZE = 20
    }
}

private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")
private val TITLE_BRACKETS = Regex("[(\\[{][^)\\]}]*[)\\]}]")
private val TITLE_NOISE = Regex("\\b(fabric|forge|neoforge|quilt|rift|edition|mod|port|unofficial|official|for)\\b")
private val TITLE_REST = Regex("[^a-zа-я0-9]+")

// The same project is "Sodium" on Modrinth and "Sodium (Fabric)" on CurseForge;
// without the loader and edition words the twins share one key.
val CatalogItem.mergeKey: String
    get() = title.lowercase()
        .replace(TITLE_BRACKETS, " ")
        .replace(TITLE_NOISE, " ")
        .replace(TITLE_REST, "")
        .ifEmpty { slug.lowercase() }
        .ifEmpty { key }

/** A catalogue that failed is an answer; a cancelled search is not, it has to stop. */
private inline fun <T> attemptSource(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
