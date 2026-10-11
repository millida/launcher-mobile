package net.millida.launcher.core.content

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.Hashing
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.catalog.Catalog
import net.millida.launcher.core.catalog.CatalogItem
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.profile.ContentFile
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.packs.PackOriginStore
import net.millida.launcher.core.profile.ProfileContent
import java.io.File
import java.io.IOException

data class UpdateCandidate(
    val file: ContentFile,
    val projectId: String,
    val source: CatalogSource,
    val title: String,
    val fromVersion: String,
    val toVersion: String,
    val target: CatalogVersion,
) {
    val fileName: String get() = target.primaryFile()?.filename.orEmpty()
}

/**
 * What "Обновить всё" would do, before it does anything. Files nobody can name
 * are listed separately instead of being hidden: a build is usually half
 * catalogue and half hand-picked jars.
 */
data class UpdatePlan(
    val kind: ContentKind,
    val candidates: List<UpdateCandidate>,
    val upToDate: List<ContentFile>,
    val unrecognised: List<ContentFile>,
) {
    val empty: Boolean get() = candidates.isEmpty()
}

data class UpdateOutcome(
    val candidate: UpdateCandidate,
    val installedName: String,
    val error: String,
) {
    val ok: Boolean get() = error.isEmpty()
}

class ContentUpdates(
    private val layout: GameLayout,
    private val catalog: Catalog,
    private val lookup: CatalogLookup,
    private val content: ProfileContent = ProfileContent(layout),
) {

    @Throws(IOException::class)
    suspend fun plan(profile: Profile, profileDir: File, kind: ContentKind = ContentKind.MOD): UpdatePlan {
        val folder = contentFolder(layout, profileDir, kind)
        val candidates = ArrayList<UpdateCandidate>()
        val upToDate = ArrayList<ContentFile>()
        val unrecognised = ArrayList<ContentFile>()

        for (file in content.list(profileDir, kind)) {
            val path = SafePath.join(folder, file.name)
            val match = runCatching { Hashing.sha1(path) }.getOrNull()?.let { lookup.byHash(it) }
            if (match == null) {
                unrecognised += file
                continue
            }
            val newest = newest(match.projectId, profile, kind)
            if (newest == null || newest.id == match.versionId) {
                upToDate += file
                continue
            }
            if (newest.primaryFile() == null) {
                unrecognised += file
                continue
            }
            val title = lookup.project(match.projectId)?.title?.takeIf { it.isNotBlank() }
                ?: file.title
            candidates += UpdateCandidate(
                file = file,
                projectId = match.projectId,
                source = newest.source,
                title = title,
                fromVersion = match.versionNumber.ifBlank { file.version },
                toVersion = newest.versionNumber.ifBlank { newest.name },
                target = newest,
            )
        }
        return UpdatePlan(kind, candidates, upToDate, unrecognised)
    }

    /**
     * One file at a time, and the old one is removed only once the new one is on
     * disk: a download that dies must leave the build exactly as it was, not
     * without the mod.
     */
    @Throws(IOException::class)
    suspend fun apply(profileDir: File, kind: ContentKind, candidate: UpdateCandidate): UpdateOutcome {
        val folder = contentFolder(layout, profileDir, kind)
        val installed = try {
            catalog.install(candidate.target, kind, profileDir)
        } catch (error: IOException) {
            return UpdateOutcome(candidate, "", error.message ?: "не удалось скачать обновление")
        }
        if (!installed.isFile || installed.length() == 0L) {
            return UpdateOutcome(candidate, "", "новый файл не записался на диск")
        }

        if (installed.name != candidate.file.name) {
            val previous = SafePath.join(folder, candidate.file.name)
            // The record of the file that is going away goes with it: the new
            // one was written by the install that just succeeded.
            runCatching {
                PackOriginStore.forget(profileDir, "${kind.folder}/${candidate.file.name}")
            }
            if (previous.isFile && !previous.delete()) {
                return UpdateOutcome(
                    candidate,
                    installed.name,
                    "новая версия скачана, но старый файл ${candidate.file.name} не удалось убрать",
                )
            }
        }

        // A mod the player had turned off stays off after an update: the update
        // is not a decision to enable it again.
        if (!candidate.file.enabled) {
            val fresh = ContentFile(
                name = installed.name,
                kind = kind,
                sizeBytes = installed.length(),
                enabled = true,
                modId = "",
                title = candidate.title,
                version = candidate.toVersion,
            )
            runCatching { content.setEnabled(profileDir, fresh, false) }
                .onFailure { return UpdateOutcome(candidate, installed.name, "обновление поставлено, но осталось включённым") }
        }

        return UpdateOutcome(candidate, installed.name, "")
    }

    /** Every failure is carried out of the loop, so one dead link is not the whole batch. */
    suspend fun applyAll(profileDir: File, plan: UpdatePlan): List<UpdateOutcome> =
        plan.candidates.map { candidate ->
            try {
                apply(profileDir, plan.kind, candidate)
            } catch (error: IOException) {
                UpdateOutcome(candidate, "", error.message ?: "обновление не прошло")
            }
        }

    private suspend fun newest(projectId: String, profile: Profile, kind: ContentKind): CatalogVersion? {
        val item = CatalogItem(projectId = projectId, source = CatalogSource.MODRINTH)
        val versions = runCatching {
            catalog.versions(item, profile.gameVersion, profile.loader)
        }.getOrDefault(emptyList())
        val fitting = versions.filter { it.primaryFile() != null }
        return fitting.firstOrNull { it.versionType == "release" } ?: fitting.firstOrNull()
    }
}
