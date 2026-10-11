package net.millida.launcher.ui.build

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.Container
import net.millida.launcher.core.catalog.MillidaCatalog
import net.millida.launcher.core.packs.MirroredPackFile
import net.millida.launcher.core.packs.PackAutoUpdate
import net.millida.launcher.core.packs.PackEdits
import net.millida.launcher.core.packs.PackOriginStore
import net.millida.launcher.core.packs.PackUpdateOffer
import net.millida.launcher.core.packs.PackUpdateRoute
import net.millida.launcher.core.packs.PackUpdates
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.ui.catalog.CatalogModel
import net.millida.launcher.ui.catalog.MillidaShelfHost
import net.millida.launcher.ui.catalog.MillidaShelfModel
import java.util.concurrent.atomic.AtomicLong

data class PackUpdateState(
    val profileId: String = "",
    val offer: PackUpdateOffer? = null,
    val auto: PackAutoUpdate? = null,
    /** The build was changed by the player: the update brings the author's mods folder back. */
    val edited: Boolean = false,
    val asking: Boolean = false,
    val mirror: MirroredPackFile? = null,
)

/**
 * «Обновить сборку» on the build screen, for packs from Modrinth, CurseForge
 * and the Millida catalogue. The new version is installed beside the old one
 * and switched in only once complete; a build the player changed, or a copy
 * of one, never updates by itself.
 */
class PackUpdateModel(
    private val container: Container,
    private val scope: CoroutineScope,
    private val catalog: CatalogModel,
    private val shelf: MillidaShelfModel,
    private val host: MillidaShelfHost,
) {
    private val mutable = MutableStateFlow(PackUpdateState())
    val state: StateFlow<PackUpdateState> = mutable.asStateFlow()

    private val seq = AtomicLong()
    private val api = MillidaCatalog(container.millidaAuth)

    /** A failed check only means no offer this time; the next visit asks again. */
    fun refresh(profile: Profile) {
        val ticket = seq.incrementAndGet()
        if (mutable.value.profileId != profile.id) mutable.update { PackUpdateState(profileId = profile.id) }
        scope.launch {
            val checked = try {
                withContext(Dispatchers.IO) { check(profile) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(LOG_TAG, "обновление сборки ${profile.id} не проверено: ${error.message}")
                null
            }
            if (ticket != seq.get()) return@launch
            mutable.update { current ->
                checked ?: current.copy(offer = null)
            }
        }
    }

    private suspend fun check(profile: Profile): PackUpdateState {
        val dir = container.profiles.directory(profile)
        val origin = PackOriginStore.read(dir)
        val auto = PackEdits.state(dir, profile.id, origin)
        val edited = origin != null && PackEdits.modsModified(dir, origin.installedAt)
        val mirror = if (PackUpdates.routeOf(origin) == PackUpdateRoute.MILLIDA_MIRROR && origin != null) {
            val item = api.item(origin.id)
            val file = MillidaCatalog.modpackFile(item.files, listOf(profile.gameVersion), listOf(profile.loader.id))
            PackUpdates.mirroredFile(item, file, origin.name.ifBlank { MillidaCatalog.displayName(item.title) })
        } else {
            null
        }
        val offer = container.packs.findUpdate(profile, mirror)
        return PackUpdateState(profileId = profile.id, offer = offer, auto = auto, edited = edited, mirror = mirror)
    }

    fun ask() = mutable.update { if (it.offer != null) it.copy(asking = true) else it }

    fun dismiss() = mutable.update { it.copy(asking = false) }

    fun confirm(profile: Profile) {
        mutable.update { it.copy(asking = false) }
        start(profile, then = null)
    }

    /**
     * «Играть» takes a waiting catalogue update first when the build updates
     * by itself, as the desktop launcher does; otherwise the game starts as is.
     */
    fun playWithUpdate(profile: Profile, play: (Profile) -> Unit) {
        val current = mutable.value
        val offer = current.offer
        val due = current.profileId == profile.id &&
            offer != null &&
            offer.route == PackUpdateRoute.MILLIDA_CATALOG &&
            current.auto?.on == true
        if (!due) {
            play(profile)
            return
        }
        if (!start(profile, then = play)) play(profile)
    }

    fun setAuto(profile: Profile, on: Boolean) {
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    PackEdits.choose(container.profiles.directory(profile), profile.id, on)
                }
                mutable.update { if (it.profileId == profile.id) it.copy(auto = saved) else it }
                host.notice(if (on) "Сборка будет обновляться сама" else "Сборка обновляется только по кнопке «Обновить»", false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                host.notice("Не удалось сохранить автообновление: ${host.errorText(error).lowerFirst()}", true)
            }
        }
    }

    private fun start(profile: Profile, then: ((Profile) -> Unit)?): Boolean {
        val current = mutable.value
        val offer = current.offer?.takeIf { current.profileId == profile.id } ?: return false
        val title = profile.name
        return catalog.runModpack(
            key = key(profile.id),
            title = title,
            onFailure = { error -> shelf.explain(error, offer.packId.takeIf { offer.route == PackUpdateRoute.MILLIDA_CATALOG }.orEmpty(), title) },
            onInstalled = { updated ->
                refresh(updated)
                then?.invoke(updated)
            },
        ) { onStep ->
            container.packs.update(
                profile = profile,
                versionId = offer.versionId.ifEmpty { null },
                mirror = current.mirror,
                onProgress = onStep,
            )
        }
    }

    companion object {
        private const val LOG_TAG = "millida"

        fun key(profileId: String): String = "update:$profileId"
    }
}

private fun String.lowerFirst(): String = replaceFirstChar { it.lowercase() }
