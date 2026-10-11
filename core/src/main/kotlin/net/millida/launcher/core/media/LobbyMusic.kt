package net.millida.launcher.core.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import net.millida.launcher.core.mojang.AssetIndex
import net.millida.launcher.core.mojang.MOJANG_RESOURCES_URL
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.mojang.VersionResolver
import java.io.File
import java.io.IOException

data class MusicTrack(val key: String, val title: String, val hash: String, val size: Long)

/**
 * The game's own music (C418, Lena Raine) for the lobby. Tracks are objects of
 * the store the game reads, fetched one at a time right before they play and
 * checked by SHA-1, so the launcher never redistributes them and never pulls
 * the whole soundtrack at once.
 */
class LobbyMusic(
    private val layout: GameLayout,
    private val downloader: Downloader,
    private val resolver: VersionResolver,
) {
    suspend fun tracks(): List<MusicTrack> = withContext(Dispatchers.IO) {
        val local = localTracks()
        if (local.isNotEmpty()) return@withContext local
        pick(latestIndex())
    }

    /** The file of [track], downloaded now if this phone does not have it yet. */
    suspend fun file(track: MusicTrack): File = withContext(Dispatchers.IO) {
        val target = layout.assetObject(track.hash)
        downloader.fetch(
            RemoteFile(
                url = MOJANG_RESOURCES_URL + track.hash.substring(0, 2) + "/" + track.hash,
                target = target,
                sha1 = track.hash,
                size = track.size,
            ),
        )
        target
    }

    private fun localTracks(): List<MusicTrack> {
        val indexes = layout.assetIndexes.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(LOCAL_INDEX_LIMIT)
            .orEmpty()
        for (file in indexes) {
            // A local index is only a shortcut past the network: a damaged one is skipped, not fatal.
            val tracks = try {
                pick(MojangJson.decodeFromString(AssetIndex.serializer(), file.readText()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (broken: Exception) {
                continue
            }
            if (tracks.isNotEmpty()) return tracks
        }
        return emptyList()
    }

    private suspend fun latestIndex(): AssetIndex {
        val manifest = resolver.manifest()
        val release = manifest.latest.release.ifEmpty { manifest.releases().firstOrNull()?.id.orEmpty() }
        if (release.isEmpty()) throw IOException("в списке версий нет релиза")
        resolver.ensureVersionJson(release)
        val ref = resolver.readLocal(release).assetIndex ?: throw IOException("у версии $release нет индекса ресурсов")
        val target = layout.assetIndex(ref.id)
        downloader.fetch(RemoteFile(ref.url, target, ref.sha1, ref.size))
        return MojangJson.decodeFromString(AssetIndex.serializer(), target.readText())
    }

    private fun pick(index: AssetIndex): List<MusicTrack> = pickTracks(index.objects.mapValues { it.value.hash to it.value.size })

    companion object {
        const val PREFIX = "minecraft/sounds/music/"
        const val LIMIT = 25
        private const val LOCAL_INDEX_LIMIT = 4
        private val HASH = Regex("^[0-9a-f]{40}$")

        /** The desktop's choice: music `.ogg` keys in key order, at most [LIMIT], with a valid hash. */
        fun pickTracks(objects: Map<String, Pair<String, Long>>): List<MusicTrack> =
            objects.entries
                .filter { (key, value) -> key.startsWith(PREFIX) && key.endsWith(".ogg") && HASH.matches(value.first.lowercase()) }
                .sortedBy { it.key }
                .take(LIMIT)
                .map { (key, value) -> MusicTrack(key, title(key), value.first.lowercase(), value.second) }

        /** «minecraft/sounds/music/game/sweden.ogg» → «Sweden», the desktop's `asset_title`. */
        fun title(key: String): String =
            key.substringAfterLast('/').removeSuffix(".ogg")
                .split('_')
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }
}
