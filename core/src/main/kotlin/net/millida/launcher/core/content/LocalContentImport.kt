package net.millida.launcher.core.content

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.Hashing
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.atomicCopyFrom
import net.millida.launcher.core.profile.Profile
import java.io.File
import java.io.IOException

/**
 * A file the player picked themselves: what it is, where it belongs, and what
 * has to be said out loud before it is copied in.
 */
data class LocalFileCandidate(
    val source: File,
    val fileName: String,
    val kind: ContentKind?,
    val facts: ContentFacts,
    val warnings: List<String>,
    val rejection: String,
    val needsConsent: Boolean,
) {
    val accepted: Boolean get() = rejection.isEmpty() && kind != null
}

class LocalContentImport(
    private val layout: GameLayout,
    private val lookup: CatalogLookup? = null,
) {

    fun identify(source: File, profile: Profile): LocalFileCandidate {
        val facts = ContentInspector.inspect(source)
        val fileName = SafePath.fileName(source.name)
        val warnings = ArrayList<String>()

        if (facts.shape == ArchiveShape.NOT_AN_ARCHIVE) {
            return reject(source, fileName, facts, facts.unreadable.ifBlank { "это не jar и не zip" })
        }
        val kind = facts.kind()
            ?: return reject(source, fileName, facts, "не удалось понять, что это за файл")

        if (facts.strongFindings >= 2) {
            warnings += "Внутри файла есть то, что обычному моду не нужно: " +
                facts.findings.filter { it.strong }.joinToString(", ") { it.text }
        }

        var needsConsent = facts.strongFindings >= 2
        if (kind == ContentKind.MOD && !facts.runsOn(profile.loader)) {
            val wanted = profile.loader.label
            warnings += "Файл собран под ${facts.loaderLabel()}, а сборка на $wanted — игра с ним не запустится"
            needsConsent = true
        }
        if (facts.fitsGameVersion(profile.gameVersion) == false) {
            warnings += "Файл собран под ${facts.gameVersionRange}, а сборка на ${profile.gameVersion}"
            needsConsent = true
        }

        return LocalFileCandidate(source, fileName, kind, facts, warnings, "", needsConsent)
    }

    /**
     * Copying goes through the same transfer the catalogue uses: a half-written
     * file never appears under the real name, and an identical copy is a no-op
     * instead of a second download.
     */
    @Throws(IOException::class)
    suspend fun add(
        profileDir: File,
        candidate: LocalFileCandidate,
        consent: Boolean = false,
        kindOverride: ContentKind? = null,
    ): File {
        if (candidate.rejection.isNotEmpty()) throw IOException(candidate.rejection)
        val kind = kindOverride ?: candidate.kind ?: throw IOException("не удалось понять, что это за файл")
        if (candidate.needsConsent && !consent) {
            throw IOException(candidate.warnings.firstOrNull() ?: "файл требует подтверждения")
        }
        if (!candidate.source.isFile) throw IOException("файл ${candidate.fileName} пропал")

        val folder = contentFolder(layout, profileDir, kind).also { it.mkdirs() }
        val target = SafePath.join(folder, candidate.fileName)
        // A picked file is copied as it is: the downloader only takes web addresses.
        if (target.isFile && Hashing.matches(target, Hashing.sha1(candidate.source))) return target
        target.atomicCopyFrom(candidate.source)
        return target
    }

    /** Best effort: the catalogue name is nicer than the file name, and nothing breaks without it. */
    suspend fun catalogTitle(source: File): String? {
        val client = lookup ?: return null
        val sha1 = runCatching { Hashing.sha1(source) }.getOrNull() ?: return null
        val match = client.byHash(sha1) ?: return null
        return client.project(match.projectId)?.title
    }

    private fun reject(source: File, fileName: String, facts: ContentFacts, why: String) = LocalFileCandidate(
        source = source,
        fileName = fileName,
        kind = null,
        facts = facts,
        warnings = emptyList(),
        rejection = "Файл $fileName не годится для сборки: $why",
        needsConsent = false,
    )
}
