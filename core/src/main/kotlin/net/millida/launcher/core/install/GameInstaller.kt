package net.millida.launcher.core.install

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import net.millida.launcher.core.http.TransferProgress
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.launch.Log4jGuard
import net.millida.launcher.core.loader.ForgeGameFiles
import net.millida.launcher.core.mojang.Artifact
import net.millida.launcher.core.mojang.AssetIndex
import net.millida.launcher.core.mojang.MOJANG_RESOURCES_URL
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.mojang.ResolvedVersion
import net.millida.launcher.core.mojang.VersionResolver
import java.io.File
import java.io.IOException

sealed interface InstallStage {
    data class Meta(val versionId: String) : InstallStage
    data class Client(val progress: TransferProgress) : InstallStage
    data class Libraries(val progress: TransferProgress) : InstallStage
    data class Assets(val progress: TransferProgress) : InstallStage
    data object Done : InstallStage
}

class GameInstaller(
    private val layout: GameLayout,
    private val downloader: Downloader,
    private val resolver: VersionResolver,
) : ForgeGameFiles {

    override suspend fun clientJar(gameVersion: String): File {
        val version = resolver.resolve(gameVersion)
        val clientJar = version.clientJar ?: throw IOException(tr("в описании версии {gameVersion} нет клиента", gameVersion))
        val target = layout.versionJar(version.jarVersionId)
        downloader.fetch(RemoteFile(clientJar.url, target, clientJar.sha1, clientJar.size))
        return target
    }

    override suspend fun clientMappings(gameVersion: String): Artifact? {
        resolver.ensureVersionJson(gameVersion)
        return resolver.readLocal(gameVersion).downloads["client_mappings"]
    }
    suspend fun install(versionId: String, onStage: (InstallStage) -> Unit = {}): ResolvedVersion {
        layout.ensure()
        onStage(InstallStage.Meta(versionId))
        val version = resolver.resolve(versionId)

        val clientJar = version.clientJar ?: throw IOException(tr("в описании версии нет клиента"))
        downloader.fetchAll(
            listOf(
                RemoteFile(
                    url = clientJar.url,
                    target = layout.versionJar(version.jarVersionId),
                    sha1 = clientJar.sha1,
                    size = clientJar.size,
                )
            )
        ) { onStage(InstallStage.Client(it)) }

        val (produced, published) = version.libraries.partition { it.produced }
        val libraryFiles = published.map {
            RemoteFile(it.url, layout.library(it.path), it.sha1, it.size)
        }
        downloader.fetchAll(libraryFiles) { onStage(InstallStage.Libraries(it)) }
        // The loader's installer built these on this phone; their checksum is not the published one.
        val unbuilt = produced.filterNot { layout.library(it.path).let { file -> file.isFile && file.length() > 0 } }
        if (unbuilt.isNotEmpty()) throw LoaderFilesMissing(versionId, unbuilt.map { it.name })

        version.logging?.file?.takeIf { Log4jGuard.needsConfig(version) && it.id.isNotEmpty() && it.url.isNotEmpty() }?.let { config ->
            val sum = config.sha1 ?: throw IOException(tr("у настройки журнала {id} нет контрольной суммы", config.id))
            downloader.fetch(RemoteFile(config.url, layout.logConfig(config.id), sum, config.size))
        }

        version.assetIndex?.let { index ->
            val indexFile = layout.assetIndex(index.id)
            downloader.fetch(RemoteFile(index.url, indexFile, index.sha1, index.size))
            val parsed = MojangJson.decodeFromString(AssetIndex.serializer(), indexFile.readText())
            val objects = parsed.objects.values.map { asset ->
                RemoteFile(
                    url = MOJANG_RESOURCES_URL + asset.hash.substring(0, 2) + "/" + asset.hash,
                    target = layout.assetObject(asset.hash),
                    sha1 = asset.hash,
                    size = asset.size,
                )
            }
            downloader.fetchAll(objects) { onStage(InstallStage.Assets(it)) }
            if (parsed.virtual || parsed.mapToResources) {
                materializeVirtualAssets(index.id, parsed)
            }
        }

        onStage(InstallStage.Done)
        return version
    }

    /**
     * What [install] would download for [versionId] right now. A file that is
     * there counts as done without reading its checksum: the estimate is for a
     * question to the player, not for deciding what to fetch.
     */
    suspend fun missingBytes(versionId: String): Long {
        val version = resolver.resolve(versionId)
        var total = 0L
        version.clientJar?.let { jar ->
            if (!layout.versionJar(version.jarVersionId).isFile) total += jar.size
        }
        version.libraries.filterNot { it.produced }.forEach { library ->
            if (!layout.library(library.path).isFile) total += library.size
        }
        version.assetIndex?.let { index ->
            val indexFile = layout.assetIndex(index.id)
            val parsed = if (indexFile.isFile) {
                runCatching { MojangJson.decodeFromString(AssetIndex.serializer(), indexFile.readText()) }.getOrNull()
            } else {
                null
            }
            total += parsed?.objects?.values?.distinctBy { it.hash }?.sumOf { asset ->
                if (layout.assetObject(asset.hash).isFile) 0L else asset.size
            } ?: (index.totalSize + index.size)
        }
        return total
    }

    fun verify(version: ResolvedVersion): List<String> {
        val missing = ArrayList<String>()
        if (!layout.versionJar(version.jarVersionId).isFile) missing += tr("клиент {jarVersionId}.jar", version.jarVersionId)
        version.libraries.forEach { library ->
            if (!layout.library(library.path).isFile) missing += library.name
        }
        return missing
    }

    private fun materializeVirtualAssets(indexId: String, index: AssetIndex) {
        val root = java.io.File(java.io.File(layout.assets, "virtual"), indexId)
        for ((name, asset) in index.objects) {
            val source = layout.assetObject(asset.hash)
            val target = net.millida.launcher.core.SafePath.join(root, name)
            if (target.isFile && target.length() == asset.size) continue
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }
}

/** Files the loader's installer builds are gone: the loader has to be installed again, not downloaded. */
class LoaderFilesMissing(val versionId: String, val names: List<String>) :
    IOException(tr("загрузчик сборки собран не полностью ({0}) — ставим его заново", names.first().substringAfterLast(':')))
