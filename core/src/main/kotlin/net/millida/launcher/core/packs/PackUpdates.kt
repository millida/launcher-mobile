package net.millida.launcher.core.packs

import net.millida.launcher.core.catalog.MillidaFile
import net.millida.launcher.core.catalog.MillidaItem

/** Where a newer version of a build is asked for. */
enum class PackUpdateRoute { MODRINTH, CURSEFORGE, MILLIDA_CATALOG, MILLIDA_MIRROR, MILLIDA_CODE }

/** A newer version than the installed one, as the build screen offers it. */
data class PackUpdateOffer(
    val route: PackUpdateRoute,
    val packId: String,
    val from: String,
    val to: String,
    val versionId: String,
)

object PackUpdates {

    /**
     * A build installed from our mirror before the mirror had a source of its
     * own was written down as a file with the card's address; that address is
     * all an update needs, so such a build counts as mirrored.
     */
    fun routeOf(origin: PackOrigin?): PackUpdateRoute? {
        if (origin == null || origin.id.isBlank()) return null
        return when (origin.source) {
            PackSource.MODRINTH.id -> PackUpdateRoute.MODRINTH
            PackSource.CURSEFORGE.id -> PackUpdateRoute.CURSEFORGE
            PackSource.MILLIDA_CATALOG.id -> PackUpdateRoute.MILLIDA_CATALOG.takeIf { catalogSlugValid(origin.id) }
            PackSource.MILLIDA_CODE.id -> PackUpdateRoute.MILLIDA_CODE
            PackSource.MILLIDA_MIRROR.id, PackSource.FILE.id -> PackUpdateRoute.MILLIDA_MIRROR.takeIf { mirrorSlug(origin.id) }
            else -> null
        }
    }

    /** Only a different version is an update; an unknown newest one is not offered. */
    fun offer(route: PackUpdateRoute, origin: PackOrigin, newestId: String, newestName: String): PackUpdateOffer? {
        if (newestId.isBlank() || newestId == origin.versionId) return null
        return PackUpdateOffer(
            route = route,
            packId = origin.id,
            from = origin.versionName.ifBlank { origin.versionId.ifBlank { "установленная" } },
            to = newestName.ifBlank { newestId },
            versionId = newestId,
        )
    }

    /** A catalogue card names its version and nothing else; the desktop compares the same string. */
    fun catalogOffer(origin: PackOrigin, publishedVersion: String): PackUpdateOffer? {
        val published = publishedVersion.trim()
        if (published.isEmpty() || published == origin.versionName.trim()) return null
        return PackUpdateOffer(
            route = PackUpdateRoute.MILLIDA_CATALOG,
            packId = origin.id,
            from = origin.versionName.ifBlank { "установленная" },
            to = published,
            versionId = "",
        )
    }

    /**
     * The mirrored modpack file a card offers, in the shape the installer
     * takes. Null when the card has no mirrored .mrpack or .zip: such a pack is
     * installed from the author's page, not by the phone.
     */
    fun mirroredFile(item: MillidaItem, file: MillidaFile?, title: String): MirroredPackFile? {
        if (file == null || !file.mirrored || item.articleSlug.isEmpty()) return null
        val name = file.fileName.lowercase()
        if (!name.endsWith(".mrpack") && !name.endsWith(".zip")) return null
        return MirroredPackFile(
            slug = item.slug,
            title = title,
            article = item.articleSlug,
            fileId = file.id,
            fileName = file.fileName,
            sha1 = file.sha1,
            size = file.size,
            version = file.version,
            gameVersion = file.primaryGameVersion.ifEmpty { file.gameVersions.firstOrNull().orEmpty() },
            loader = file.loaders.firstOrNull().orEmpty(),
            icon = item.icon.orEmpty(),
        )
    }

    private val MIRROR_SLUG = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")

    private fun mirrorSlug(id: String): Boolean = MIRROR_SLUG.matches(id)
}
