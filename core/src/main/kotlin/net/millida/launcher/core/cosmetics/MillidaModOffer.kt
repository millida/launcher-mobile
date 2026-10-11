package net.millida.launcher.core.cosmetics

import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.content.InstalledIndex
import net.millida.launcher.core.profile.ContentFile

/**
 * The question asked before a start: put the Millida mod into a build that can
 * take it but has none. The phone never installs it on its own, so without the
 * question voice chat and cosmetics stay out of the game for most players.
 */
object MillidaModOffer {

    const val FABRIC_API_PROJECT = "P7dR8mSH"

    private val FABRIC_API_NAMES = arrayOf("fabric-api", "fabric", "Fabric API", "quilted_fabric_api")

    /**
     * Asked only where the answer changes something: a build that already has
     * the jar, even switched off by the player, keeps it as it is.
     */
    fun wanted(state: CosmeticsModState?, declined: Boolean): Boolean =
        !declined && state != null && !state.installed && state.canInstall

    /**
     * What a start does with the mod. The question is only for a build that has
     * none, and «Без мода» answers only that question: a jar the player already
     * has is kept current without asking, like the desktop does on every start,
     * or an old jar keeps drawing nothing against a newer server. A jar switched
     * off by hand is the player's choice and stays as it is.
     */
    fun onLaunch(
        state: CosmeticsModState?,
        intact: Boolean,
        declined: Boolean,
        updateDue: Boolean = true,
    ): LaunchModAction = when {
        state == null -> LaunchModAction.NONE
        state.installed && state.installedEnabled && state.canInstall && updateDue &&
            (state.status == CosmeticsModStatus.OUTDATED || !intact) -> LaunchModAction.UPDATE
        wanted(state, declined) -> LaunchModAction.OFFER
        else -> LaunchModAction.NONE
    }

    /**
     * When the file the CDN serves does not match the catalogue, every update
     * fails the same way, so the same target is tried at most once a day
     * instead of on every start.
     */
    fun updateDue(lastSha: String?, lastAt: Long, sha: String?, now: Long): Boolean {
        if (sha.isNullOrEmpty() || lastSha.isNullOrEmpty()) return true
        if (!lastSha.equals(sha, ignoreCase = true)) return true
        val since = now - lastAt
        return since < 0 || since >= UPDATE_RETRY_MS
    }

    const val UPDATE_RETRY_MS = 24L * 60 * 60 * 1000

    /** The Fabric builds of the mod refuse to load without Fabric API beside them. */
    fun needsFabricApi(loaderId: String): Boolean = loaderId == "fabric" || loaderId == "quilt"

    fun hasFabricApi(files: List<ContentFile>): Boolean = InstalledIndex.of(files).has(*FABRIC_API_NAMES)

    fun pickFabricApi(versions: List<CatalogVersion>): CatalogVersion? {
        val usable = versions.filter { it.primaryFile() != null }
        return usable.firstOrNull { it.versionType == "release" } ?: usable.firstOrNull()
    }
}

enum class LaunchModAction { NONE, OFFER, UPDATE }
