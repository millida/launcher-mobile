package net.millida.launcher.core.loader

import kotlinx.serialization.Serializable
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.HttpError
import net.millida.launcher.core.mojang.MojangJson

enum class LoaderKind(val id: String, val label: String) {
    VANILLA("vanilla", "Ваниль"),
    FABRIC("fabric", "Fabric"),
    QUILT("quilt", "Quilt"),
    FORGE("forge", "Forge"),
    NEOFORGE("neoforge", "NeoForge"),
}

@Serializable
data class LoaderVersion(
    val version: String,
    val stable: Boolean = true,
)

interface LoaderInstaller {
    val kind: LoaderKind

    suspend fun versions(gameVersion: String): List<LoaderVersion>

    suspend fun install(gameVersion: String, loaderVersion: String): String
}

private const val FABRIC_META = "https://meta.fabricmc.net/v2"
private const val QUILT_META = "https://meta.quiltmc.org/v3"

class MetaLoaderInstaller(
    override val kind: LoaderKind,
    private val layout: GameLayout,
    private val downloader: Downloader,
    private val metaBase: String,
) : LoaderInstaller {

    @Serializable
    private data class LoaderEntry(val loader: Loader) {
        @Serializable
        data class Loader(val version: String, val stable: Boolean = false)
    }

    /** Empty when the loader has no build for this game version: the meta answers that with 400 or 404. */
    override suspend fun versions(gameVersion: String): List<LoaderVersion> {
        val body = try {
            downloader.text("$metaBase/versions/loader/$gameVersion")
        } catch (absent: HttpError) {
            if (absent.code == 400 || absent.code == 404) return emptyList()
            throw absent
        }
        val entries = MojangJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(LoaderEntry.serializer()),
            body,
        )
        return entries.map { LoaderVersion(it.loader.version, it.loader.stable) }
    }

    override suspend fun install(gameVersion: String, loaderVersion: String): String {
        val profile = downloader.text("$metaBase/versions/loader/$gameVersion/$loaderVersion/profile/json")
        val id = "$gameVersion-${kind.id}-$loaderVersion"
        val target = layout.versionJson(id)
        target.atomicWriteText(rewriteId(profile, id))
        return id
    }

    private fun rewriteId(profileJson: String, id: String): String {
        val element = MojangJson.parseToJsonElement(profileJson) as kotlinx.serialization.json.JsonObject
        val patched = kotlinx.serialization.json.buildJsonObject {
            element.forEach { (key, value) -> put(key, value) }
            put("id", kotlinx.serialization.json.JsonPrimitive(id))
        }
        return MojangJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), patched)
    }

    companion object {
        fun fabric(layout: GameLayout, downloader: Downloader) =
            MetaLoaderInstaller(LoaderKind.FABRIC, layout, downloader, FABRIC_META)

        fun quilt(layout: GameLayout, downloader: Downloader) =
            MetaLoaderInstaller(LoaderKind.QUILT, layout, downloader, QUILT_META)
    }
}
