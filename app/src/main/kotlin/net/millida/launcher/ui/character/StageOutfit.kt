package net.millida.launcher.ui.character

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.millida.launcher.core.cosmetics.AnimationClip
import net.millida.launcher.core.cosmetics.CosmeticAnchor
import net.millida.launcher.core.cosmetics.CosmeticAnimation
import net.millida.launcher.core.cosmetics.CosmeticCover
import net.millida.launcher.core.cosmetics.CosmeticGeometry
import net.millida.launcher.core.cosmetics.CosmeticItem
import net.millida.launcher.core.cosmetics.CosmeticModelFile
import net.millida.launcher.core.cosmetics.CosmeticPlacement
import net.millida.launcher.core.cosmetics.CosmeticPose
import net.millida.launcher.core.cosmetics.CosmeticRig
import net.millida.launcher.core.cosmetics.DuoEmotes
import net.millida.launcher.core.cosmetics.EmotePlay
import net.millida.launcher.core.cosmetics.EmoteSequence
import net.millida.launcher.core.cosmetics.EmoteSkeleton
import net.millida.launcher.core.cosmetics.wornTexture

private const val LOG_TAG = "millida"

/** One worn piece ready for the stage: its bones, which of them stay hidden, its picture and its clip. */
class StagePiece(
    val key: String,
    val rig: CosmeticRig,
    val hidden: BooleanArray,
    val textureUrl: String,
    val textureWidth: Float,
    val textureHeight: Float,
    val clip: AnimationClip?,
    /** An emote's own props run on the emote's clock, or they drift off the hands. */
    val followsEmote: Boolean,
) {
    /** Filled every frame by the stage, which draws on one thread. */
    val matrices: Array<FloatArray> = CosmeticPose.buffers(rig)

    /** The part of the player each bone ends up on: a child bone rides with its root. */
    val anchors: Array<CosmeticAnchor> = Array(rig.bones.size) { CosmeticAnchor.ROOT }.also { out ->
        rig.bones.forEachIndexed { at, bone ->
            out[at] = if (bone.parent in 0 until at) out[bone.parent] else bone.anchor ?: CosmeticAnchor.ROOT
        }
    }
}

@Immutable
class StageOutfit(
    val pieces: List<StagePiece>,
    val emote: EmotePlay?,
    val masks: List<String>,
    val hideCape: Boolean,
) {
    val animated: Boolean = emote != null || pieces.any { it.clip != null }

    val isEmpty: Boolean get() = pieces.isEmpty() && emote == null && masks.isEmpty() && !hideCape
}

private class Loaded(val item: CosmeticItem, val file: CosmeticModelFile?)

/**
 * Dresses the figure the way the desktop wardrobe does: models only for what is
 * worn or tried on, an emote's clip in place of the figure's own motion, cover
 * rules and skin masks from the whole outfit. Returns the names that did not
 * arrive, so the player hears why a piece is missing instead of guessing.
 */
internal suspend fun buildStageOutfit(
    dressed: List<CosmeticItem>,
    slim: Boolean,
    model: suspend (String) -> CosmeticModelFile?,
): Pair<StageOutfit, List<String>> {
    val shown = dressed.filter { it.model.isNotEmpty() && (it.wornTexture().isNotEmpty() || it.isEmote) }
    val loaded = coroutineScope {
        shown.map { item -> async { Loaded(item, model(item.model)) } }.awaitAll()
    }
    val lost = loaded.filter { it.file == null }.map { it.item.name }
    return withContext(Dispatchers.Default) {
        val emote = loaded.firstOrNull { it.item.isEmote && it.file?.animations != null }
        val emoteFile = emote?.file
        val emoteClips = emoteFile?.let { CosmeticAnimation.read(it.animations) }.orEmpty()
        val chosen = emoteFile?.let { CosmeticAnimation.emoteClip(emoteClips, emote?.item?.animation) }
        // A paired emote plays each half alone and without the model's skeleton, as the mod does.
        val duo = DuoEmotes.sceneOf(emote?.item)
        val sequence = if (duo != null) EmoteSequence.solo(chosen) else EmoteSequence.of(emoteClips, chosen)
        val play = sequence?.let { EmotePlay(it, if (duo != null) null else EmoteSkeleton.of(emoteFile?.geometry)) }
        val pieces = loaded.mapNotNull { got -> piece(got, dressed, slim, followsEmote = got === emote && play != null) }
        val outfit = StageOutfit(
            pieces = pieces,
            emote = play,
            masks = CosmeticCover.maskUrls(dressed, slim, dressed.firstOrNull { it.isEmote }).filter(::isRemoteImage),
            hideCape = CosmeticCover.hidesRegularCape(dressed),
        )
        outfit to lost
    }
}

private fun piece(got: Loaded, dressed: List<CosmeticItem>, slim: Boolean, followsEmote: Boolean): StagePiece? {
    val file = got.file ?: return null
    val item = got.item
    val texture = item.wornTexture()
    if (!isRemoteImage(texture)) return null
    return try {
        val geometry = if (slim && file.geometrySlim != null) file.geometrySlim else file.geometry
        val mesh = CosmeticGeometry.read(geometry, CosmeticGeometry.inflateFor(item.slot)) ?: return null
        val rig = CosmeticPlacement.rig(mesh)
        val hidden = CosmeticCover.hiddenJoints(rig.bones, CosmeticCover.hiddenBones(dressed, item), item.side)
        val drawn = rig.bones.indices.any { !hidden[it] && rig.bones[it].quads.isNotEmpty() }
        if (!drawn) return null
        StagePiece(
            key = item.id,
            rig = rig,
            hidden = hidden,
            textureUrl = texture,
            textureWidth = mesh.textureWidth,
            textureHeight = mesh.textureHeight,
            clip = CosmeticAnimation.pickClip(CosmeticAnimation.read(file.animations), item.animation),
            followsEmote = followsEmote,
        )
    } catch (broken: RuntimeException) {
        // A broken model must not take the whole stage down: the piece just stays off the figure.
        Log.w(LOG_TAG, "cosmetic ${item.id} could not be built: ${broken.message}")
        null
    }
}

/**
 * The outfit on the figure, rebuilt when the dressed set or the arms change.
 * The previous outfit stays on while the next one loads, so the figure does
 * not flash undressed between two taps.
 */
@Composable
internal fun rememberStageOutfit(
    dressed: List<CosmeticItem>,
    slim: Boolean,
    cosmetics: CosmeticsModel,
    onLost: (List<String>) -> Unit,
): State<StageOutfit?> {
    val lostCallback = rememberUpdatedState(onLost)
    return produceState<StageOutfit?>(initialValue = null, dressed, slim) {
        if (dressed.isEmpty()) {
            value = null
            return@produceState
        }
        val built = try {
            buildStageOutfit(dressed, slim, cosmetics::model)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "outfit could not be built: ${failure.message}")
            null
        }
        value = built?.first
        val lost = built?.second.orEmpty()
        if (lost.isNotEmpty()) lostCallback.value(lost)
    }
}

/** Pictures named by the catalogue come from the network only; a file or content address in it is refused. */
private fun isRemoteImage(url: String): Boolean = url.startsWith("https://", ignoreCase = true)
