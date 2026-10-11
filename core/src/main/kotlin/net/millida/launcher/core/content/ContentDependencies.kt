package net.millida.launcher.core.content

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.catalog.Catalog
import net.millida.launcher.core.catalog.CatalogItem
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileContent
import java.io.File
import java.io.IOException

data class DependencyNode(
    val projectId: String,
    val source: CatalogSource,
    val title: String,
    val versionId: String,
    val versionNumber: String,
    val fileName: String,
    val sizeBytes: Long,
    val requiredBy: String,
    val version: CatalogVersion,
)

data class UnresolvedDependency(
    val projectId: String,
    val title: String,
    val requiredBy: String,
    val problem: String,
)

/**
 * The answer to "что ещё придётся поставить". Nothing here touches the disk:
 * the player sees the whole list first and agrees to it once.
 */
data class DependencyPlan(
    val title: String,
    val missing: List<DependencyNode>,
    val satisfied: List<String>,
    val unresolved: List<UnresolvedDependency>,
    val truncated: Boolean,
) {
    val empty: Boolean get() = missing.isEmpty() && unresolved.isEmpty()
}

data class DependencyInstallReport(
    val installed: List<String>,
    val failed: Map<String, String>,
)

class ContentDependencies(
    private val layout: GameLayout,
    private val catalog: Catalog,
    private val lookup: CatalogLookup,
    private val content: ProfileContent = ProfileContent(layout),
) {

    @Throws(IOException::class)
    suspend fun plan(
        profile: Profile,
        profileDir: File,
        root: CatalogVersion,
        kind: ContentKind = ContentKind.MOD,
    ): DependencyPlan {
        val installed = InstalledIndex.of(content.list(profileDir, kind))
        val rootTitle = lookup.project(root.projectId)?.title?.takeIf { it.isNotBlank() }
            ?: root.name.ifBlank { root.projectId }

        val missing = ArrayList<DependencyNode>()
        val satisfied = ArrayList<String>()
        val unresolved = ArrayList<UnresolvedDependency>()
        var truncated = false

        // A project that appears twice in the graph, or points back at something
        // already walked, is skipped by identifier - that is what keeps a mutual
        // dependency from spinning forever.
        val seen = HashSet<String>()
        seen += root.projectId
        val queue = ArrayDeque<Pending>()
        root.dependencies.forEach { queue += Pending(it, rootTitle) }

        while (queue.isNotEmpty()) {
            if (missing.size + unresolved.size >= MAX_NODES) {
                truncated = true
                break
            }
            val pending = queue.removeFirst()
            val dependency = pending.dependency
            if (dependency.dependencyType != "required") continue
            val projectId = dependency.projectId?.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(projectId)) continue

            val meta = lookup.project(projectId)
            val title = meta?.title?.takeIf { it.isNotBlank() } ?: projectId
            if (installed.has(*(meta?.names() ?: arrayOf(projectId)))) {
                satisfied += title
                continue
            }

            val version = pick(projectId, dependency.versionId, profile, kind)
            if (version == null) {
                unresolved += UnresolvedDependency(
                    projectId = projectId,
                    title = title,
                    requiredBy = pending.requiredBy,
                    problem = "нет версии под ${profile.gameVersion} и ${profile.loader.label}",
                )
                continue
            }

            val primary = version.primaryFile()
            val node = DependencyNode(
                projectId = projectId,
                source = version.source,
                title = title,
                versionId = version.id,
                versionNumber = version.versionNumber.ifBlank { version.name },
                fileName = primary?.filename.orEmpty(),
                sizeBytes = primary?.size ?: 0L,
                requiredBy = pending.requiredBy,
                version = version.copy(projectId = projectId),
            )
            missing += node
            version.dependencies.forEach { queue += Pending(it, title) }
        }

        return DependencyPlan(rootTitle, missing, satisfied, unresolved, truncated)
    }

    /**
     * Installs the agreed list. The index is updated as files land, so a project
     * that two mods both depend on is fetched once.
     */
    @Throws(IOException::class)
    suspend fun install(
        profileDir: File,
        nodes: List<DependencyNode>,
        kind: ContentKind = ContentKind.MOD,
    ): DependencyInstallReport {
        val installed = InstalledIndex.of(content.list(profileDir, kind))
        val done = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        for (node in nodes) {
            if (installed.has(node.projectId, node.title)) continue
            try {
                catalog.install(node.version, kind, profileDir)
                installed.remember(node.projectId, node.title)
                done += node.title
            } catch (error: IOException) {
                failed[node.title] = error.message ?: "не удалось скачать"
            }
        }
        return DependencyInstallReport(done, failed)
    }

    private suspend fun pick(
        projectId: String,
        versionId: String?,
        profile: Profile,
        kind: ContentKind,
    ): CatalogVersion? {
        val item = CatalogItem(projectId = projectId, source = CatalogSource.MODRINTH)
        val versions = runCatching {
            catalog.versions(item, profile.gameVersion, profile.loader)
        }.getOrDefault(emptyList()).filter { it.primaryFile() != null }
        if (versions.isEmpty()) return null
        // A pinned version is honoured only while it still fits this build;
        // otherwise the pin would install a file the game cannot load.
        versionId?.takeIf { it.isNotBlank() }?.let { pinned ->
            versions.firstOrNull { it.id == pinned }?.let { return it }
        }
        return versions.firstOrNull { it.versionType == "release" } ?: versions.first()
    }

    private data class Pending(val dependency: CatalogVersion.Dependency, val requiredBy: String)

    private companion object {
        const val MAX_NODES = 64
    }
}
