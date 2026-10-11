package net.millida.launcher.core.packs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.millida.launcher.core.loader.LoaderKind
import java.io.IOException

const val PACK_MANIFEST_FORMAT = 1
private const val MAX_FILES = 1000
private const val MAX_SUMMARY = 300
private const val MAX_NAME = 64

/**
 * One catalogue file of a shared build: project, version, digest, size. A
 * manifest of a two-gigabyte pack is a few kilobytes, and the receiving side
 * fetches every file from the catalogue itself instead of trusting an archive a
 * stranger uploaded.
 */
data class PackManifestFile(
    val path: String,
    val kind: String = "mod",
    val projectId: String = "",
    val versionId: String = "",
    val sha1: String = "",
    val sha512: String = "",
    val size: Long = 0,
    val url: String,
)

data class PackManifest(
    val formatVersion: Int = PACK_MANIFEST_FORMAT,
    val name: String,
    val summary: String = "",
    val game: String,
    val loader: String = LoaderKind.VANILLA.id,
    val loaderVersion: String = "",
    val icon: String = "",
    val files: List<PackManifestFile> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("formatVersion", formatVersion)
        put("name", name)
        put("summary", summary)
        put("game", game)
        put("loader", loader)
        put("loaderVersion", loaderVersion)
        put("icon", icon)
        put(
            "files",
            buildJsonArray {
                files.forEach { file ->
                    add(
                        buildJsonObject {
                            put("path", file.path)
                            put("kind", file.kind)
                            put("projectId", file.projectId)
                            put("versionId", file.versionId)
                            put("sha1", file.sha1)
                            put("size", file.size)
                            put("url", file.url)
                        },
                    )
                }
            },
        )
        putJsonObject("settings") { }
    }

    @Throws(IOException::class)
    fun toPlan(code: String): PackPlan {
        val (loaderKind, version) = PackLoaders.fromId(loader)
        return PackPlan(
            name = name,
            gameVersion = game,
            loader = loaderKind,
            loaderVersion = version.ifEmpty { loaderVersion },
            summary = summary,
            source = PackSource.MILLIDA_CODE,
            sourceId = code,
            iconUrl = icon,
            files = files.map { file ->
                PackFileEntry(
                    path = file.path,
                    urls = listOf(file.url),
                    sha1 = file.sha1.ifEmpty { null },
                    size = file.size,
                    projectId = file.projectId,
                    versionId = file.versionId,
                )
            },
        )
    }

    companion object {
        /**
         * Everything in a manifest is untrusted: it came over the network from
         * another player. A hostile line is dropped instead of failing the whole
         * pack — one bad entry should not cost the player the other four hundred.
         */
        @Throws(IOException::class)
        fun parse(raw: JsonObject?, hosts: PackHostPolicy = PackHostPolicy()): PackManifest {
            val root = raw ?: throw IOException("сборка записана в неизвестном формате — обнови лаунчер")
            return packJson("описание сборки повреждено — попроси автора поделиться ею заново") { read(root, hosts) }
        }

        private fun read(root: JsonObject, hosts: PackHostPolicy): PackManifest {
            val format = root["formatVersion"]?.jsonPrimitive?.longOrNull?.toInt() ?: PACK_MANIFEST_FORMAT
            if (format > PACK_MANIFEST_FORMAT) {
                throw IOException("сборку сделали в более новой версии лаунчера — обнови лаунчер")
            }
            val game = root.text("game")
            if (game.isBlank()) throw IOException("в сборке не указана версия Minecraft")
            val files = (root["files"] as? JsonArray).orEmpty()
                .map { it.jsonObject }
                .take(MAX_FILES)
                .mapNotNull { entry ->
                    val url = entry.text("url")
                    val path = entry.text("path")
                    val sha1 = entry.text("sha1")
                    val safe = hosts.allows(url) &&
                        sha1.length == 40 &&
                        sha1.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } &&
                        path.isNotEmpty() &&
                        !path.contains("..")
                    if (!safe) return@mapNotNull null
                    PackManifestFile(
                        path = path,
                        kind = entry.text("kind").ifEmpty { "mod" },
                        projectId = entry.text("projectId"),
                        versionId = entry.text("versionId"),
                        sha1 = sha1,
                        size = entry["size"]?.jsonPrimitive?.longOrNull ?: 0,
                        url = url,
                    )
                }
            return PackManifest(
                formatVersion = format,
                name = clean(root.text("name")).take(MAX_NAME).ifEmpty { "Сборка друга" },
                summary = clean(root.text("summary")).take(MAX_SUMMARY),
                game = game,
                loader = root.text("loader").ifEmpty { LoaderKind.VANILLA.id },
                loaderVersion = root.text("loaderVersion"),
                icon = root.text("icon").takeIf { hosts.allows(it) }.orEmpty(),
                files = files,
            )
        }

        private fun clean(value: String): String = value.filter { it.code >= 0x20 || it == '\n' }.trim()

        private fun JsonObject.text(key: String): String =
            this[key]?.jsonPrimitive?.content?.takeIf { it != "null" }.orEmpty()

        private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
    }
}
