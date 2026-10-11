package net.millida.launcher.core.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.Hashing
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import net.millida.launcher.core.mojang.AssetIndex
import net.millida.launcher.core.mojang.MOJANG_RESOURCES_URL
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.mojang.VersionResolver
import java.io.File
import java.io.IOException

sealed interface UiSoundsResult {
    val files: Map<UiSoundEvent, List<File>>

    data class Ready(override val files: Map<UiSoundEvent, List<File>>) : UiSoundsResult

    data class Partial(
        override val files: Map<UiSoundEvent, List<File>>,
        val missing: Set<UiSoundEvent>,
        val cause: Throwable?,
    ) : UiSoundsResult

    data class Failed(val cause: Throwable) : UiSoundsResult {
        override val files: Map<UiSoundEvent, List<File>> get() = emptyMap()
    }
}

private const val SOUND_PREFIX = "minecraft/sounds/"
private const val STATE_FILE = "ui-sounds.json"
private const val LOCAL_INDEX_LIMIT = 4
private const val PARALLEL_FETCHES = 6

@Serializable
private data class SoundObject(val hash: String, val size: Long = 0) {
    fun valid(): Boolean = size >= 0 && hash.length == 40 && hash.all { it in '0'..'9' || it in 'a'..'f' }
}

@Serializable
private data class SoundState(
    val signature: String = "",
    val events: Map<String, List<SoundObject>> = emptyMap(),
)

private class Fetched(val files: Map<UiSoundEvent, List<File>>, val error: Exception?)

/**
 * Interface sounds are the game's own sound objects, kept in the same object store
 * the game reads, so a phone with any modern version installed downloads nothing.
 * Game assets are not redistributed: they come from Mojang's CDN, checked by SHA-1.
 */
class UiSounds(
    private val layout: GameLayout,
    private val downloader: Downloader,
    private val resolver: VersionResolver,
) {
    suspend fun prepare(): UiSoundsResult = withContext(Dispatchers.IO) {
        try {
            collect()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            UiSoundsResult.Failed(error)
        }
    }

    private suspend fun collect(): UiSoundsResult {
        val total = UiSoundEvent.entries.size
        val known = remembered()
        var picks = known
        var problem: Exception? = null
        if (picks.size < total) picks = picks + bestLocal()
        if (picks.size < total) {
            try {
                picks = picks + pick(latestIndex())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                problem = error
            }
        }
        if (picks != known) remember(picks)
        if (picks.isEmpty()) return UiSoundsResult.Failed(problem ?: IOException("в индексе игры нет звуков интерфейса"))

        val fetched = fetch(picks)
        val missing = UiSoundEvent.entries.filterNot { it in fetched.files }.toSet()
        return when {
            missing.isEmpty() -> UiSoundsResult.Ready(fetched.files)
            fetched.files.isEmpty() -> UiSoundsResult.Failed(
                fetched.error ?: problem ?: IOException("звуки интерфейса не скачались"),
            )
            else -> UiSoundsResult.Partial(fetched.files, missing, fetched.error ?: problem)
        }
    }

    private fun bestLocal(): Map<UiSoundEvent, List<SoundObject>> {
        val indexes = layout.assetIndexes.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(LOCAL_INDEX_LIMIT)
            .orEmpty()
        var best: Map<UiSoundEvent, List<SoundObject>> = emptyMap()
        for (file in indexes) {
            // A local index is only a shortcut past the network: a damaged one is skipped, not fatal.
            val picks = try {
                pick(parse(file))
            } catch (broken: Exception) {
                continue
            }
            if (picks.size > best.size) best = picks
            if (best.size == UiSoundEvent.entries.size) break
        }
        return best
    }

    private suspend fun latestIndex(): AssetIndex {
        val manifest = resolver.manifest()
        val release = manifest.latest.release.ifEmpty { manifest.releases().firstOrNull()?.id.orEmpty() }
        if (release.isEmpty()) throw IOException("в списке версий нет релиза")
        resolver.ensureVersionJson(release)
        val ref = resolver.readLocal(release).assetIndex ?: throw IOException("у версии $release нет индекса ресурсов")
        val target = layout.assetIndex(ref.id)
        downloader.fetch(RemoteFile(ref.url, target, ref.sha1, ref.size))
        return parse(target)
    }

    private fun parse(file: File): AssetIndex =
        MojangJson.decodeFromString(AssetIndex.serializer(), file.readText())

    private fun pick(index: AssetIndex): Map<UiSoundEvent, List<SoundObject>> {
        val out = LinkedHashMap<UiSoundEvent, List<SoundObject>>()
        for (event in UiSoundEvent.entries) {
            val variants = event.variants.mapNotNull { lookup(index, it) }
            val chosen = variants.ifEmpty { listOfNotNull(event.fallback.firstNotNullOfOrNull { lookup(index, it) }) }
            if (chosen.isNotEmpty()) out[event] = chosen
        }
        return out
    }

    private fun lookup(index: AssetIndex, key: String): SoundObject? {
        val asset = index.objects[SOUND_PREFIX + key] ?: return null
        return SoundObject(asset.hash.lowercase(), asset.size).takeIf { it.valid() }
    }

    private suspend fun fetch(picks: Map<UiSoundEvent, List<SoundObject>>): Fetched = coroutineScope {
        val gate = Semaphore(PARALLEL_FETCHES)
        val outcomes = picks.values.flatten().distinctBy { it.hash }.map { sound ->
            async { gate.withPermit { sound.hash to download(sound) } }
        }.awaitAll()
        val failed = outcomes.filter { it.second != null }.map { it.first }.toSet()
        val files = LinkedHashMap<UiSoundEvent, List<File>>()
        for ((event, sounds) in picks) {
            val ready = sounds.filter { it.hash !in failed }.map { layout.assetObject(it.hash) }
            if (ready.isNotEmpty()) files[event] = ready
        }
        Fetched(files, outcomes.firstNotNullOfOrNull { it.second })
    }

    private suspend fun download(sound: SoundObject): Exception? = try {
        downloader.fetch(
            RemoteFile(
                url = MOJANG_RESOURCES_URL + sound.hash.substring(0, 2) + "/" + sound.hash,
                target = layout.assetObject(sound.hash),
                sha1 = sound.hash,
                size = sound.size,
            ),
        )
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        error
    }

    private fun stateFile(): File = File(layout.caches, STATE_FILE)

    private fun remembered(): Map<UiSoundEvent, List<SoundObject>> {
        val file = stateFile()
        if (!file.isFile) return emptyMap()
        val state = try {
            MojangJson.decodeFromString(SoundState.serializer(), file.readText())
        } catch (broken: Exception) {
            file.delete()
            return emptyMap()
        }
        if (state.signature != signature()) return emptyMap()
        val out = LinkedHashMap<UiSoundEvent, List<SoundObject>>()
        for ((id, sounds) in state.events) {
            val event = UiSoundEvent.byId(id) ?: continue
            val valid = sounds.filter { it.valid() }
            if (valid.isNotEmpty()) out[event] = valid
        }
        return out
    }

    private fun remember(picks: Map<UiSoundEvent, List<SoundObject>>) {
        val state = SoundState(signature(), picks.mapKeys { it.key.id })
        // The file only saves parsing an index on the next start; losing it costs nothing else.
        runCatching { stateFile().atomicWriteText(MojangJson.encodeToString(SoundState.serializer(), state)) }
    }

    private fun signature(): String = Hashing.sha1(
        UiSoundEvent.entries.joinToString("|") { it.id + ":" + (it.variants + "/" + it.fallback).joinToString(",") }
            .toByteArray(),
    )
}
