package net.millida.launcher.core.mojang

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * `coerceInputValues` matters for live answers, not for Mojang: the portal sends
 * explicit nulls for fields that simply have no value yet (`avgOnline: null`),
 * and without coercion the whole page fails to parse.
 */
val MojangJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class VersionManifest(
    val latest: Latest = Latest(),
    val versions: List<ManifestEntry> = emptyList(),
) {
    @Serializable
    data class Latest(val release: String = "", val snapshot: String = "")

    @Serializable
    data class ManifestEntry(
        val id: String,
        val type: String = "release",
        val url: String,
        val time: String = "",
        val releaseTime: String = "",
        val sha1: String? = null,
    )

    fun byId(id: String): ManifestEntry? = versions.firstOrNull { it.id == id }

    fun releases(): List<ManifestEntry> = versions.filter { it.type == "release" }
}

@Serializable
data class Artifact(
    val path: String? = null,
    val sha1: String? = null,
    val size: Long = 0,
    val url: String = "",
)

@Serializable
data class LibraryDownloads(
    val artifact: Artifact? = null,
    val classifiers: Map<String, Artifact> = emptyMap(),
)

@Serializable
data class OsRule(
    val name: String? = null,
    val arch: String? = null,
    val version: String? = null,
)

@Serializable
data class Rule(
    val action: String = "allow",
    val os: OsRule? = null,
    val features: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class Library(
    val name: String,
    val downloads: LibraryDownloads? = null,
    val url: String? = null,
    val rules: List<Rule> = emptyList(),
    val natives: Map<String, String> = emptyMap(),
    val extract: JsonElement? = null,
)

@Serializable
data class AssetIndexRef(
    val id: String,
    val sha1: String? = null,
    val size: Long = 0,
    val totalSize: Long = 0,
    val url: String = "",
)

@Serializable
data class JavaVersionRef(
    val component: String = "jre-legacy",
    val majorVersion: Int = 8,
)

/**
 * Mojang's own logging configuration. Versions before 1.18 carry a log4j that
 * resolves lookups inside chat messages (CVE-2021-44228); the configuration the
 * version names is what switches that off, the system property does not exist
 * in log4j that old.
 */
@Serializable
data class LoggingConfig(
    val argument: String = "",
    val file: LoggingFile? = null,
    val type: String = "",
) {
    @Serializable
    data class LoggingFile(
        val id: String = "",
        val sha1: String? = null,
        val size: Long = 0,
        val url: String = "",
    )
}

@Serializable
data class VersionMeta(
    val id: String,
    val inheritsFrom: String? = null,
    val type: String = "release",
    val mainClass: String = "net.minecraft.client.main.Main",
    val assets: String? = null,
    val assetIndex: AssetIndexRef? = null,
    val downloads: Map<String, Artifact> = emptyMap(),
    val libraries: List<Library> = emptyList(),
    val arguments: JsonElement? = null,
    val minecraftArguments: String? = null,
    val javaVersion: JavaVersionRef? = null,
    val logging: Map<String, LoggingConfig> = emptyMap(),
    val minimumLauncherVersion: Int = 0,
    val releaseTime: String = "",
)

@Serializable
data class AssetIndex(
    val objects: Map<String, AssetObject> = emptyMap(),
    @SerialName("map_to_resources") val mapToResources: Boolean = false,
    val virtual: Boolean = false,
) {
    @Serializable
    data class AssetObject(val hash: String, val size: Long = 0)
}
