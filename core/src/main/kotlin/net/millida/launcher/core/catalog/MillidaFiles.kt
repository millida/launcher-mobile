package net.millida.launcher.core.catalog

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.game.Worlds
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.packs.PackOriginFile
import net.millida.launcher.core.packs.PackOriginStore
import java.io.File
import java.io.IOException
import java.net.URI
import java.time.Instant

/**
 * What of the Millida catalogue goes into a build as a file, by the site's
 * section. A modpack is not here: it becomes a build of its own.
 */
enum class MillidaKind(val id: String, val section: String, val content: ContentKind?, val noun: String) {
    MOD("mod", "mods", ContentKind.MOD, "Мод"),
    RESOURCE_PACK("resourcepack", "texture-packs", ContentKind.RESOURCE_PACK, "Ресурс-пак"),
    SHADER("shader", "shaders", ContentKind.SHADER, "Шейдер"),
    DATA_PACK("datapack", "data-packs", ContentKind.DATA_PACK, "Дата-пак"),
    WORLD("world", "maps", null, "Карта");

    /** Only mods are bound to a loader; a map, a shader or a resource pack fits any build of its version. */
    val loaderBound: Boolean get() = this == MOD

    val extensions: Set<String> get() = if (this == MOD) setOf("jar", "zip", "litemod") else setOf("zip")

    companion object {
        fun ofSection(section: String): MillidaKind? = entries.firstOrNull { it.section == section }

        fun of(kind: ContentKind): MillidaKind = entries.first { it.content == kind }
    }
}

/** The file chosen for a build, and whether it was made for that build at all. */
data class MillidaFilePick(val file: MillidaFile, val fits: Boolean, val versions: List<String>)

data class MillidaFileLink(val url: String, val fileName: String, val size: Long, val sha512: String)

/**
 * A file of the Millida catalogue — a mod, resource pack, shader, data pack or
 * map an author uploaded, free or paid — put into a build. The phone names the
 * item and the file; the address comes from our API, and a paid file's
 * address is issued only to an account with access.
 */
class MillidaFiles(
    private val auth: MillidaAuth,
    private val downloader: Downloader,
    private val worlds: Worlds,
    private val apiBase: String = MILLIDA_API,
) {

    /**
     * Downloads the file into the build: into its content folder, or for a map
     * unpacked into `saves/`. Returns the name the player will see there.
     */
    @Throws(IOException::class)
    suspend fun install(
        profileDir: File,
        kind: MillidaKind,
        slug: String,
        file: MillidaFile,
        title: String,
        scratch: File,
        onProgress: (Float) -> Unit = {},
    ): String {
        if (!MillidaCatalog.catalogSlug(slug) || !fileIdValid(file.id)) throw IOException("некорректный материал каталога")
        val link = link(file)
        if (!fileUrlAllowed(link.url)) throw IOException("сервер вернул ссылку на чужой адрес — установка остановлена")
        val digest = digest(link.sha512, file.sha1)
            ?: throw IOException("у файла нет контрольной суммы — такой файл ставить нельзя, напиши в поддержку")
        val fallback = "$slug.${if (kind == MillidaKind.MOD) "jar" else "zip"}"
        val fileName = SafePath.fileName(link.fileName.ifBlank { file.fileName.ifBlank { fallback } })
        if (fileName.substringAfterLast('.', "").lowercase() !in kind.extensions) {
            throw IOException("файл такого типа в сборку не добавить")
        }
        val size = if (link.size > 0) link.size else file.size
        val report: (Long) -> Unit = progress(size, onProgress)
        val content = kind.content
            ?: return installWorld(profileDir, RemoteFile(url = link.url, target = worldArchive(scratch, slug), sha1 = digest, size = size), title.ifBlank { slug }, report)
        val folder = File(profileDir, content.folder)
        folder.mkdirs()
        val target = SafePath.join(folder, fileName)
        downloader.fetch(RemoteFile(url = link.url, target = target, sha1 = digest, size = size), report)
        PackOriginStore.record(
            profileDir,
            PackOriginFile(
                path = "${content.folder}/$fileName",
                // A signed address expires in minutes: nothing may replay it later.
                url = "",
                sha1 = file.sha1.takeIf { hex(it, SHA1) }?.lowercase().orEmpty(),
                size = target.length(),
                projectId = projectId(slug),
                versionId = file.id,
            ),
        )
        return fileName
    }

    /** A map is unpacked by the same checked importer the worlds tab uses: zip-slip and a world shape are its job. */
    @Throws(IOException::class)
    private suspend fun installWorld(profileDir: File, remote: RemoteFile, title: String, report: (Long) -> Unit): String {
        try {
            downloader.fetch(remote, report)
            return remote.target.inputStream().use { input -> worlds.importWorld(profileDir, input, title).folder }
        } finally {
            remote.target.delete()
        }
    }

    @Throws(IOException::class)
    private fun worldArchive(scratch: File, slug: String): File {
        scratch.mkdirs()
        return SafePath.join(scratch, SafePath.fileName("millida-world-$slug-${System.nanoTime()}.zip"))
    }

    /**
     * Signed in, the address is asked for with the account, which is how a
     * paid file is checked. A guest gets only a free file, by the catalogue's
     * counted address, proven by the card's sha1.
     */
    @Throws(IOException::class)
    private suspend fun link(file: MillidaFile): MillidaFileLink {
        if (!auth.signedIn()) {
            return MillidaFileLink("$apiBase/catalog/files/${file.id}/download", "", 0, "")
        }
        val body: JsonObject = auth.authorized("/catalog/files/${file.id}/link", "POST", buildJsonObject { })
        return MillidaFileLink(
            url = body.text("url"),
            fileName = body.text("fileName"),
            size = (body["size"] as? JsonPrimitive)?.longOrNull ?: 0,
            sha512 = body.text("sha512"),
        )
    }

    private fun progress(size: Long, onProgress: (Float) -> Unit): (Long) -> Unit {
        var done = 0L
        return { chunk ->
            done = if (chunk < 0) 0L else done + chunk
            if (size > 0) onProgress((done.toFloat() / size).coerceIn(0f, 1f))
        }
    }

    companion object {
        private const val SHA1 = 40
        private const val SHA512 = 128

        /** Hosts a signed or mirrored catalogue file may come from, the same list the desktop launcher keeps. */
        private val FILE_HOSTS = listOf("millida.net", "millida.trade", "cdn.modrinth.com", "edge.forgecdn.net", "mediafilez.forgecdn.net")

        private val LOADERLESS = setOf(MillidaKind.RESOURCE_PACK, MillidaKind.DATA_PACK, MillidaKind.WORLD, MillidaKind.SHADER)

        fun projectId(slug: String): String = "millida:$slug"

        fun fileUrlAllowed(raw: String): Boolean {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
            if (!"https".equals(uri.scheme, ignoreCase = true) || uri.rawUserInfo != null) return false
            val host = uri.host?.lowercase() ?: return false
            return FILE_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        /** The server's own rule for a file id. */
        fun fileIdValid(id: String): Boolean =
            id.length in 10..40 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }

        /** The link's sha512 is fresher than the card's sha1; with neither nothing is downloaded. */
        fun digest(sha512: String?, sha1: String?): String? = when {
            hex(sha512.orEmpty(), SHA512) -> sha512!!.lowercase()
            hex(sha1.orEmpty(), SHA1) -> sha1!!.lowercase()
            else -> null
        }

        /**
         * The newest file made for the build's game version and loader. Without
         * one, the newest for the loader (or at all) with `fits = false`: the
         * player decides, as with a Modrinth install.
         */
        fun pickFile(files: List<MillidaFile>, gameVersion: String?, loader: LoaderKind?, kind: MillidaKind): MillidaFilePick? {
            val sorted = files.filter { it.id.isNotEmpty() }
                .withIndex()
                .sortedWith(compareByDescending<IndexedValue<MillidaFile>> { released(it.value) }.thenBy { it.index })
                .map { it.value }
            val newest = sorted.firstOrNull() ?: return null
            val versions = sorted.flatMap { it.gameVersions }.distinct().take(MAX_VERSIONS)
            if (gameVersion == null && loader == null) return MillidaFilePick(newest, true, versions)
            val byLoader = sorted.filter { loaderFits(it, loader, kind) }
            val exact = byLoader.firstOrNull { file ->
                gameVersion.isNullOrEmpty() || file.gameVersions.isEmpty() || gameVersion in file.gameVersions
            }
            if (exact != null) return MillidaFilePick(exact, true, versions)
            return MillidaFilePick(byLoader.firstOrNull() ?: newest, false, versions)
        }

        private const val MAX_VERSIONS = 8

        private fun loaderFits(file: MillidaFile, loader: LoaderKind?, kind: MillidaKind): Boolean {
            if (kind in LOADERLESS || loader == null || loader == LoaderKind.VANILLA || file.loaders.isEmpty()) return true
            if (loader.id in file.loaders) return true
            return loader == LoaderKind.QUILT && LoaderKind.FABRIC.id in file.loaders
        }

        private fun released(file: MillidaFile): Long =
            runCatching { Instant.parse(file.releasedAt).toEpochMilli() }.getOrDefault(0L)

        private fun hex(value: String, length: Int): Boolean =
            value.length == length && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
}

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
