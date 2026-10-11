package net.millida.launcher.core.packs

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.mojang.MojangJson
import java.io.IOException

/** A build somebody published, as the code screen shows it before installing. */
data class SharedPackView(
    val code: String,
    val name: String,
    val summary: String = "",
    val gameVersion: String = "",
    val loader: String = "",
    val author: String = "",
    val files: Int = 0,
    val sizeBytes: Long = 0,
    val installs: Long = 0,
    val updatedAt: String = "",
)

/** A code this player handed out, as the share window lists it. */
data class MyPack(
    val code: String,
    val name: String,
    val summary: String = "",
    val files: Int = 0,
    val installs: Long = 0,
    val updatedAt: String = "",
) {
    val url: String get() = PackCodes.link(code)
}

data class SharedPackResult(
    val code: String,
    val url: String,
    val files: Int,
    val skipped: List<String>,
)

/** A pack from the Millida catalogue, with the client half to download. */
data class CatalogPackCard(
    val slug: String,
    val title: String,
    val summary: String = "",
    val cover: String = "",
    val gameVersion: String = "",
    val loader: String = "",
    val loaderVersion: String = "",
    val version: String = "",
    val clientFileId: String = "",
    val sizeBytes: Long = 0,
    val sha512: String = "",
    val accessRequired: Boolean = false,
    val accessBuyUrl: String = "",
    val ownLaunch: Boolean = false,
) {
    val phoneCard: PhoneCard get() = PhoneCard(loader, gameVersion, ownLaunch, accessRequired, sizeBytes)
}

data class PackDownloadLink(val url: String, val sha512: String, val sizeBytes: Long)

/** A file of a catalogue card served from our mirror, as the resolver hands it out. */
data class MirrorFileLink(val url: String, val name: String)

/**
 * The account has no access to a paid pack. Told apart by type, not by the
 * server's wording: the key window has to open even after the text is rephrased.
 */
class PackAccessDenied(message: String) : IOException(message)

/**
 * Codes are read aloud and typed by hand, so the alphabet drops what gets
 * confused: O against 0, I against 1, and lowercase entirely.
 */
object PackCodes {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 8

    fun normalize(raw: String): String = raw
        .substringAfterLast('/')
        .trim()
        .uppercase()
        .filter { it.isLetterOrDigit() }

    fun valid(raw: String): Boolean {
        val code = normalize(raw)
        return code.length == LENGTH && code.all { it in ALPHABET }
    }

    fun link(code: String): String = "https://millida.net/p/$code"
}

/** A catalogue address is a path segment and a temp file name, nothing else. */
internal fun catalogSlugValid(slug: String): Boolean =
    slug.isNotEmpty() && slug.length <= 80 && slug.all { it.isDigit() || it in 'a'..'z' || it == '-' }

/**
 * The Millida half of packs: the catalogue of ready builds, and the codes
 * players pass to each other. Publishing and the list of one's own codes need a
 * signed-in player, reading a code by hand does not.
 */
class MillidaPacks(
    private val downloader: Downloader,
    private val auth: MillidaAuth? = null,
    private val apiBase: String = MILLIDA_API,
    private val hosts: PackHostPolicy = PackHostPolicy(),
) : PackAccessApi {

    override fun signedIn(): Boolean = auth?.signedIn() == true

    override suspend fun accessRequired(slug: String): Boolean = catalogPack(slug).accessRequired

    @Throws(IOException::class)
    override suspend fun access(slug: String): PackAccess? {
        if (!catalogSlugValid(slug)) throw IOException("некорректный адрес сборки: $slug")
        return PackAccess.from(authorized("/catalog/packs/$slug/access", "POST", buildJsonObject { }))
    }

    @Throws(IOException::class)
    override suspend fun launchToken(slug: String): List<String> {
        if (!catalogSlugValid(slug)) throw IOException("некорректный адрес сборки: $slug")
        return PackLaunchToken.args(authorized("/catalog/packs/$slug/launch-token", "POST", buildJsonObject { }))
    }

    @Throws(IOException::class)
    suspend fun catalogPack(slug: String): CatalogPackCard {
        if (!catalogSlugValid(slug)) throw IOException("некорректный адрес сборки: $slug")
        val body = downloader.text("$apiBase/catalog/packs/$slug")
        return packJson("карточка сборки пришла повреждённой — попробуй позже") { catalogCard(slug, body) }
    }

    private fun catalogCard(slug: String, body: String): CatalogPackCard {
        val card = MojangJson.parseToJsonElement(body).jsonObject
        val client = (card["files"] as? JsonArray)
            ?.map { it.jsonObject }
            ?.firstOrNull { it.text("side") == "client" }
            ?: throw IOException("у этой сборки нет клиентской части — её ставят только на сервер")
        return CatalogPackCard(
            slug = slug,
            title = card.text("title").ifEmpty { slug },
            summary = card.text("summary"),
            cover = card.text("cover"),
            gameVersion = card.text("game"),
            loader = card.text("loader"),
            loaderVersion = card.text("loaderVersion"),
            version = card.text("version"),
            clientFileId = client.text("id"),
            sizeBytes = client["size"]?.jsonPrimitive?.longOrNull ?: 0,
            sha512 = client.text("sha512"),
            accessRequired = card["accessRequired"]?.jsonPrimitive?.booleanOrNull == true,
            accessBuyUrl = card.text("accessBuyUrl").trim().takeIf { it.startsWith("https://") }.orEmpty(),
            ownLaunch = PhoneFit.ownLaunchIn(card),
        )
    }

    /**
     * Entry names of the archive before it is downloaded. Null when the server
     * keeps no index for this archive: the unpacked tree then tells the same,
     * at the cost of the download.
     */
    @Throws(IOException::class)
    suspend fun catalogPackIndex(fileId: String): List<String>? {
        if (!fileIdValid(fileId)) return null
        val response = authorized("/catalog/packs/files/$fileId/index", "GET", null)
        val rows = response["entries"] as? JsonArray ?: return null
        return rows.mapNotNull { row -> ((row as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull }
    }

    /**
     * Only the card and the file are named here; the address comes from our API,
     * so a page that opened the launcher cannot hand it a file of its own.
     */
    @Throws(IOException::class)
    suspend fun mirrorFileLink(article: String, fileId: String): MirrorFileLink {
        if (!catalogSlugValid(article) || !fileIdValid(fileId)) throw IOException("у карточки нет файла для скачивания")
        val response = client().publicCall("/forum/articles/$article/download?file=$fileId", "GET", null) as? JsonObject
            ?: throw IOException("сервер вернул не тот ответ, что ожидался")
        val url = response.text("url")
        if (url.isEmpty()) throw IOException("сервер не выдал ссылку на файл — попробуй позже")
        return MirrorFileLink(url = url, name = response.text("name"))
    }

    private fun fileIdValid(fileId: String): Boolean =
        fileId.isNotEmpty() && fileId.length <= 64 && fileId.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /**
     * The signed link is produced from the row the file is served from, so it is
     * the fresher of the two answers and its digest wins over the card's.
     */
    @Throws(IOException::class)
    suspend fun catalogPackLink(fileId: String): PackDownloadLink {
        if (fileId.isEmpty()) throw IOException("ответ сервера не содержит файла сборки")
        val response = authorized("/catalog/packs/files/$fileId/link", "POST", buildJsonObject { })
        val url = response.text("url")
        if (url.isEmpty()) throw IOException("сервер не выдал ссылку на сборку — попробуй позже")
        return PackDownloadLink(
            url = url,
            sha512 = response.text("sha512"),
            sizeBytes = response["size"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }

    @Throws(IOException::class)
    suspend fun sharedPack(code: String): Pair<SharedPackView, PackManifest> {
        val plain = PackCodes.normalize(code)
        if (!PackCodes.valid(plain)) throw IOException("такого кода не бывает — проверь, что ввёл")
        val body = downloader.text("$apiBase/launcher/packs/$plain")
        val response = packJson("сборка друга пришла повреждённой — попробуй позже") { MojangJson.parseToJsonElement(body).jsonObject }
        val manifest = PackManifest.parse(response["manifest"] as? JsonObject, hosts)
        val view = SharedPackView(
            code = plain,
            name = manifest.name,
            summary = manifest.summary,
            gameVersion = manifest.game,
            loader = manifest.loader,
            author = response.text("author"),
            files = manifest.files.size,
            sizeBytes = manifest.files.sumOf { it.size },
            installs = response["installs"]?.jsonPrimitive?.longOrNull ?: 0,
            updatedAt = response.text("updatedAt"),
        )
        return view to manifest
    }

    /** Counted after the files land, so a failed install inflates nobody's counter. */
    suspend fun reportInstalled(code: String): Boolean {
        val client = auth ?: return false
        return try {
            client.publicCall("/launcher/packs/$code/installed", "POST", null, withSession = false)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            false
        }
    }

    @Throws(IOException::class)
    suspend fun myPacks(): List<MyPack> {
        val response = client().authorizedElement("/launcher/packs/mine", "GET", null)
        val items = when (response) {
            is JsonArray -> response
            is JsonObject -> response["items"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return items.map { it.jsonObject }.mapNotNull { entry ->
            val code = PackCodes.normalize(entry.text("code"))
            if (!PackCodes.valid(code)) return@mapNotNull null
            MyPack(
                code = code,
                name = entry.text("name").ifEmpty { code },
                summary = entry.text("summary"),
                files = entry["files"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                installs = entry["installs"]?.jsonPrimitive?.longOrNull ?: 0,
                updatedAt = entry.text("updatedAt"),
            )
        }
    }

    /**
     * Republishing the same build keeps its code: a link a player already sent to
     * friends must not go stale because they added one mod.
     */
    @Throws(IOException::class)
    suspend fun publish(manifest: PackManifest): String {
        val response = client().authorized("/launcher/packs", "POST", manifest.toJson())
        val code = PackCodes.normalize(response.text("code"))
        if (!PackCodes.valid(code)) throw IOException("сервер не выдал код сборки — попробуй поделиться ещё раз")
        return code
    }

    @Throws(IOException::class)
    suspend fun unshare(code: String) {
        val plain = PackCodes.normalize(code)
        if (!PackCodes.valid(plain)) throw IOException("некорректный код сборки")
        client().authorized("/launcher/packs/$plain", "DELETE", null)
    }

    /** Versions of a Modrinth modpack that actually carry a .mrpack file. */
    @Throws(IOException::class)
    suspend fun modpackVersions(slug: String): List<ModpackVersion> {
        val body = downloader.text("$apiBase/launcher/mr/v2/project/$slug/version")
        return MojangJson.parseToJsonElement(body).jsonArray
            .map { it.jsonObject }
            .filter { version -> mrpackFile(version) != null }
            .map { version ->
                ModpackVersion(
                    id = version.text("id"),
                    name = version.text("name"),
                    versionNumber = version.text("version_number"),
                    date = version.text("date_published"),
                    changelog = version.text("changelog"),
                    type = version.text("version_type"),
                )
            }
    }

    @Throws(IOException::class)
    suspend fun modpackFile(slug: String, versionId: String?): Pair<ModpackVersion, String> {
        val body = downloader.text("$apiBase/launcher/mr/v2/project/$slug/version")
        val versions = MojangJson.parseToJsonElement(body).jsonArray.map { it.jsonObject }
        val picked = if (versionId.isNullOrEmpty()) {
            versions.firstOrNull { mrpackFile(it) != null }
                ?: throw IOException("у сборки $slug нет ни одного файла .mrpack")
        } else {
            versions.firstOrNull { it.text("id") == versionId }
                ?: throw IOException("версия сборки не найдена — обнови список версий")
        }
        val url = mrpackFile(picked) ?: throw IOException("в этой версии сборки нет файла .mrpack")
        return ModpackVersion(
            id = picked.text("id"),
            name = picked.text("name"),
            versionNumber = picked.text("version_number"),
            date = picked.text("date_published"),
            changelog = picked.text("changelog"),
            type = picked.text("version_type"),
        ) to url
    }

    /**
     * Recognises a local file by its digest, which is how a hand-assembled build
     * still gets a shareable manifest instead of "nothing to share here".
     */
    suspend fun versionByHash(sha1: String): PackManifestFile? {
        if (sha1.length != 40) return null
        val body = runCatching {
            downloader.text("$apiBase/launcher/mr/v2/version_file/$sha1?algorithm=sha1")
        }.getOrNull() ?: return null
        val version = runCatching { MojangJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val file = (version["files"] as? JsonArray)
            ?.map { it.jsonObject }
            ?.firstOrNull { it["hashes"]?.jsonObject?.text("sha1").equals(sha1, ignoreCase = true) }
            ?: return null
        return PackManifestFile(
            path = "",
            kind = "mod",
            projectId = version.text("project_id"),
            versionId = version.text("id"),
            sha1 = sha1,
            sha512 = file["hashes"]?.jsonObject?.text("sha512").orEmpty(),
            size = file["size"]?.jsonPrimitive?.longOrNull ?: 0,
            url = file.text("url"),
        )
    }

    private fun mrpackFile(version: JsonObject): String? = (version["files"] as? JsonArray)
        ?.map { it.jsonObject }
        ?.firstOrNull { it.text("filename").endsWith(".mrpack") }
        ?.text("url")
        ?.takeIf { it.isNotEmpty() }

    @Throws(IOException::class)
    private suspend fun authorized(path: String, method: String, payload: JsonObject?): JsonObject =
        client().authorized(path, method, payload)

    @Throws(IOException::class)
    private fun client(): MillidaAuth =
        auth ?: throw IOException("войди в аккаунт Millida — без него сборки аккаунта недоступны")

    private fun JsonObject.text(key: String): String =
        this[key]?.jsonPrimitive?.content?.takeIf { it != "null" }.orEmpty()
}

data class ModpackVersion(
    val id: String,
    val name: String = "",
    val versionNumber: String = "",
    val date: String = "",
    val changelog: String = "",
    val type: String = "release",
)
