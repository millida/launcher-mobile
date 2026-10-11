package net.millida.launcher.core.packs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.io.IOException

/**
 * Turns an unpacked archive into a plan. Modrinth and CurseForge describe a
 * build in completely different files, and the point of this class is that they
 * stop differing here: everything below the reader installs one shape.
 */
class PackReader(
    private val downloader: Downloader,
    private val curseForgeBase: String = "$MILLIDA_API/launcher/cf",
) {

    @Throws(IOException::class)
    suspend fun read(unpacked: File, nameHint: String = ""): PackContent {
        val root = PackArchive.root(unpacked)
        return when {
            File(root, MRPACK_INDEX).isFile -> readMrpack(root, nameHint)
            File(root, CF_MANIFEST).isFile -> readCurseForge(root, nameHint)
            File(root, MILLIDA_MANIFEST).isFile -> readMillida(root, nameHint)
            else -> throw IOException(
                "в архиве нет ни modrinth.index.json, ни manifest.json — это не файл сборки, выбери .mrpack или zip со сборкой",
            )
        }
    }

    @Throws(IOException::class)
    fun readMrpack(root: File, nameHint: String = ""): PackContent = packJson(BROKEN_FILE_LIST) {
        val index = parse(File(root, MRPACK_INDEX), "modrinth.index.json")
        val dependencies = index["dependencies"]?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.content }
            ?.filterValues { it.isNotEmpty() }
            .orEmpty()
        val gameVersion = dependencies["minecraft"]
            ?: throw IOException("в сборке не указана версия Minecraft — установить её не из чего")
        val (loader, loaderVersion) = PackLoaders.fromDependencies(dependencies)

        val files = (index["files"] as? JsonArray).orEmpty().mapNotNull { element ->
            val entry = element.jsonObject
            if (entry["env"]?.jsonObject?.get("client")?.jsonPrimitive?.content == "unsupported") return@mapNotNull null
            val path = entry.text("path").ifEmpty { return@mapNotNull null }
            val hashes = entry["hashes"]?.jsonObject
            val urls = (entry["downloads"] as? JsonArray).orEmpty()
                .map { it.jsonPrimitive.content }
                .filter { it.isNotEmpty() }
            if (urls.isEmpty()) throw IOException("у файла $path в сборке нет ссылки на скачивание")
            PackFileEntry(
                path = path,
                urls = urls,
                sha1 = hashes?.text("sha1")?.takeIf { it.isNotEmpty() }
                    ?: hashes?.text("sha512")?.takeIf { it.isNotEmpty() },
                size = entry["fileSize"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }

        val plan = PackPlan(
            name = index.text("name").ifEmpty { nameHint.ifEmpty { "Сборка" } },
            gameVersion = gameVersion,
            loader = loader,
            loaderVersion = loaderVersion,
            versionName = index.text("versionId"),
            summary = index.text("summary"),
            source = PackSource.FILE,
            files = files,
        )
        PackContent(plan, overrideTrees(root, null))
    }

    @Throws(IOException::class)
    suspend fun readCurseForge(root: File, nameHint: String = ""): PackContent = packJson(BROKEN_FILE_LIST) {
        val manifest = parse(File(root, CF_MANIFEST), "manifest.json")
        val minecraft = manifest["minecraft"]?.jsonObject
            ?: throw IOException("в manifest.json нет раздела minecraft — архив повреждён")
        val gameVersion = minecraft.text("version").ifEmpty {
            throw IOException("в сборке не указана версия Minecraft — установить её не из чего")
        }
        val loaderId = (minecraft["modLoaders"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .let { loaders ->
                loaders.firstOrNull { it["primary"]?.jsonPrimitive?.booleanOrNull == true } ?: loaders.firstOrNull()
            }
            ?.text("id")
            .orEmpty()
        val (loader, loaderVersion) = PackLoaders.fromId(loaderId)

        val files = (manifest["files"] as? JsonArray).orEmpty().mapNotNull { element ->
            val entry = element.jsonObject
            val projectId = entry["projectID"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val fileId = entry["fileID"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val required = entry["required"]?.jsonPrimitive?.booleanOrNull ?: true
            resolveCurseForgeFile(projectId.toString(), fileId.toString(), required)
        }

        val plan = PackPlan(
            name = manifest.text("name").ifEmpty { nameHint.ifEmpty { "Сборка CurseForge" } },
            gameVersion = gameVersion,
            loader = loader,
            loaderVersion = loaderVersion,
            versionName = manifest.text("version"),
            source = PackSource.CURSEFORGE,
            files = files,
        )
        PackContent(plan, overrideTrees(root, manifest.text("overrides")))
    }

    /**
     * A Millida archive is already a build folder: it carries its own manifest
     * and every file beside it, so nothing is downloaded and the whole tree is
     * copied over the new build.
     */
    @Throws(IOException::class)
    fun readMillida(root: File, nameHint: String = ""): PackContent = packJson(BROKEN_FILE_LIST) {
        val manifest = parse(File(root, MILLIDA_MANIFEST), MILLIDA_MANIFEST)
        val gameVersion = manifest.text("game").ifEmpty {
            throw IOException("сборка не сказала, под какую версию Minecraft она собрана")
        }
        val (loader, loaderVersion) = PackLoaders.fromId(manifest.text("loader"))
        val plan = PackPlan(
            name = manifest.text("title").ifEmpty { nameHint.ifEmpty { "Сборка Millida" } },
            gameVersion = gameVersion,
            loader = loader,
            loaderVersion = loaderVersion.ifEmpty { manifest.text("loaderVersion") },
            versionName = manifest.text("version"),
            source = PackSource.MILLIDA_CATALOG,
        )
        PackContent(plan, listOf(root))
    }

    @Throws(IOException::class)
    private suspend fun resolveCurseForgeFile(
        projectId: String,
        fileId: String,
        required: Boolean,
    ): PackFileEntry? {
        val data = try {
            val body = downloader.text("$curseForgeBase/v1/mods/$projectId/files/$fileId")
            packJson("CurseForge ответил непонятно") { MojangJson.parseToJsonElement(body).jsonObject["data"]?.jsonObject }
        } catch (error: IOException) {
            if (required) throw IOException("CurseForge не отдал файл #$fileId сборки: ${error.message}") else null
        } ?: run {
            if (required) throw IOException("CurseForge не отдал файл #$fileId сборки — попробуй позже")
            return null
        }
        val fileName = SafePath.fileName(data.text("fileName"))
        if (fileName.isEmpty() || fileName == "file") {
            if (required) throw IOException("у файла #$fileId сборки нет имени — CurseForge ответил неполно")
            return null
        }
        val sha1 = (data["hashes"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .firstOrNull { it["algo"]?.jsonPrimitive?.longOrNull == 1L }
            ?.text("value")
            ?.takeIf { it.isNotEmpty() }
        val direct = data.text("downloadUrl")
        val cdn = if (sha1 != null) ForgeCdn.urls(fileId.toLongOrNull() ?: 0, data.text("fileName")) else emptyList()
        val urls = when {
            direct.isNotEmpty() -> listOf(direct)
            cdn.isNotEmpty() -> cdn
            required -> listOf(directLink(projectId, fileId))
            else -> return null
        }
        return PackFileEntry(
            path = "mods/$fileName",
            urls = urls,
            sha1 = sha1,
            size = data["fileLength"]?.jsonPrimitive?.longOrNull ?: 0,
            required = required,
            projectId = projectId,
            versionId = fileId,
        )
    }

    @Throws(IOException::class)
    private suspend fun directLink(projectId: String, fileId: String): String {
        val body = downloader.text("$curseForgeBase/v1/mods/$projectId/files/$fileId/download-url")
        val url = packJson("CurseForge ответил непонятно") {
            MojangJson.parseToJsonElement(body).jsonObject["data"]?.jsonPrimitive?.content.orEmpty()
        }
        if (url.isEmpty() || url == "null") {
            throw IOException("автор мода запретил скачивание файла #$fileId — поставь его вручную с сайта CurseForge")
        }
        return url
    }

    /**
     * The overrides folder is named by the archive, so the name is a path the
     * pack chose: it goes through SafePath like every other name in there.
     */
    @Throws(IOException::class)
    private fun overrideTrees(root: File, declared: String?): List<File> {
        val names = buildList {
            declared?.takeIf { it.isNotBlank() }?.let { add(it) }
            add("overrides")
            add("client-overrides")
        }.distinct()
        return names.mapNotNull { name -> SafePath.join(root, name).takeIf { it.isDirectory } }
    }

    @Throws(IOException::class)
    private fun parse(file: File, label: String): JsonObject = try {
        MojangJson.parseToJsonElement(file.readText()).jsonObject
    } catch (broken: Exception) {
        throw IOException("файл $label в сборке не читается — скачай сборку заново")
    }

    private fun JsonObject.text(key: String): String =
        this[key]?.jsonPrimitive?.content?.takeIf { it != "null" }.orEmpty()

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    private companion object {
        const val MRPACK_INDEX = "modrinth.index.json"
        const val CF_MANIFEST = "manifest.json"
        const val MILLIDA_MANIFEST = "millida.pack.json"
        const val BROKEN_FILE_LIST = "описание сборки в архиве повреждено — скачай сборку заново"
    }
}
