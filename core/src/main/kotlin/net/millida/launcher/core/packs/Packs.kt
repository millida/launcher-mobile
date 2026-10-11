package net.millida.launcher.core.packs

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.Hashing
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.profile.DISABLED_SUFFIX
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileStore
import java.io.File
import java.io.IOException

/**
 * One door to modpacks for the whole launcher. Which of the three worlds a build
 * came from — Modrinth, CurseForge or our own catalogue — is decided here; the
 * screens ask for a build and get a build.
 */
class Packs(
    private val layout: GameLayout,
    private val profiles: ProfileStore,
    private val downloader: Downloader,
    private val api: MillidaPacks,
    private val installer: PackInstaller,
    private val reader: PackReader = PackReader(downloader),
    private val hosts: PackHostPolicy = PackHostPolicy(),
    private val curseForgeBase: String = "$MILLIDA_API/launcher/cf",
    private val catalogPackHosts: PackHostPolicy = PackHostPolicy(setOf(CATALOG_PACK_HOST)),
) {

    fun info(profileDir: File): PackOrigin? = PackOriginStore.read(profileDir)

    suspend fun modpackVersions(slug: String): List<ModpackVersion> = api.modpackVersions(slug)

    suspend fun packPreview(code: String): SharedPackView = api.sharedPack(code).first

    suspend fun myPacks(): List<MyPack> = api.myPacks()

    suspend fun unshare(code: String) = api.unshare(code)

    /** A .mrpack or a CurseForge zip picked on the phone. */
    @Throws(IOException::class)
    suspend fun installFile(
        archive: File,
        target: Profile? = null,
        onProgress: (PackProgress) -> Unit = {},
    ): Profile {
        onProgress(PackProgress("Распаковываем сборку"))
        val work = newWorkDir("file")
        val content = try {
            reader.read(PackArchive.unpack(archive, work), archive.nameWithoutExtension).also { read ->
                PhoneFit.require(read.plan.loader.id, read.plan.gameVersion, read.plan.name.ifEmpty { archive.nameWithoutExtension })
            }
        } catch (failure: Exception) {
            work.deleteRecursively()
            throw failure
        }
        return installer.install(content.copy(workDir = work), target, onProgress)
    }

    /** A Modrinth modpack by its address on the site; no version means the newest. */
    @Throws(IOException::class)
    suspend fun installModrinthModpack(
        slug: String,
        versionId: String? = null,
        target: Profile? = null,
        onProgress: (PackProgress) -> Unit = {},
    ): Profile {
        onProgress(PackProgress("Читаем сборку"))
        val (version, url) = api.modpackFile(slug, versionId)
        val work = newWorkDir("mr-$slug")
        val content = try {
            onProgress(PackProgress("Скачиваем сборку"))
            val archive = fetchArchive(url, File(work, "pack.mrpack"))
            val read = reader.read(PackArchive.unpack(archive, File(work, "unpacked")), slug)
            PhoneFit.require(read.plan.loader.id, read.plan.gameVersion, read.plan.name.ifEmpty { slug })
            read.copy(
                plan = read.plan.copy(
                    source = PackSource.MODRINTH,
                    sourceId = slug,
                    versionId = version.id,
                    versionName = version.versionNumber.ifEmpty { read.plan.versionName },
                ),
                workDir = work,
            )
        } catch (failure: Exception) {
            work.deleteRecursively()
            throw failure
        }
        return installer.install(content, target, onProgress)
    }

    /** A CurseForge modpack through our proxy; no file means the newest client one. */
    @Throws(IOException::class)
    suspend fun installCurseForgeModpack(
        projectId: String,
        fileId: String? = null,
        target: Profile? = null,
        onProgress: (PackProgress) -> Unit = {},
    ): Profile {
        onProgress(PackProgress("Читаем сборку"))
        val file = curseForgeModpackFile(projectId, fileId)
        val work = newWorkDir("cf-$projectId")
        val content = try {
            onProgress(PackProgress("Скачиваем сборку"))
            val archive = fetchModpackArchive(file, File(work, SafePath.fileName(file.name)))
            val read = reader.read(PackArchive.unpack(archive, File(work, "unpacked")), file.name)
            PhoneFit.require(read.plan.loader.id, read.plan.gameVersion, read.plan.name.ifEmpty { file.name })
            read.copy(
                plan = read.plan.copy(
                    source = PackSource.CURSEFORGE,
                    sourceId = projectId,
                    versionId = file.id,
                ),
                workDir = work,
            )
        } catch (failure: Exception) {
            work.deleteRecursively()
            throw failure
        }
        return installer.install(content, target, onProgress)
    }

    /**
     * A ready build from the Millida catalogue: one signed archive from our
     * storage. What the phone cannot run is refused before the download, and a
     * paid pack without access fails with [PackAccessDenied] so the key window
     * can open instead of a plain error.
     */
    @Throws(IOException::class)
    suspend fun installCatalogPack(
        slug: String,
        target: Profile? = null,
        onProgress: (PackProgress) -> Unit = {},
    ): Profile {
        onProgress(PackProgress("Читаем сборку"))
        val card = api.catalogPack(slug)
        val title = card.title.ifEmpty { slug }
        val index = try {
            api.catalogPackIndex(card.clientFileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unknown: IOException) {
            null
        }
        // An archive that describes its own launch brings its own game, so the card's loader rule does not apply to it.
        val launchPaths = index?.let { PhoneFit.launchRelative(it) }
        if (index != null) {
            PhoneFit.requireCard(card.phoneCard.copy(ownLaunch = card.ownLaunch || launchPaths != null), title)
            launchPaths?.let { PhoneFit.ownLaunchProblem(it) }?.let { throw PhoneUnsupported(it, title, card.gameVersion) }
        } else {
            // A paid pack hides its index until access is granted; the card already says whether it brings its
            // own launch, so a pack the phone cannot run is refused before anyone is sent to buy a key for it.
            PhoneFit.requireCard(card.phoneCard, title)
        }
        val link = try {
            api.catalogPackLink(card.clientFileId)
        } catch (denied: IOException) {
            if (card.accessRequired && accessRefusal(denied)) throw PackAccessDenied(denied.message.orEmpty())
            throw denied
        }
        catalogPackHosts.check(link.url)
        val digest = link.sha512.ifEmpty { card.sha512 }
        if (digest.length != 128 || !digest.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            throw IOException("у сборки нет контрольной суммы — такой архив ставить нельзя, напиши в поддержку")
        }
        val work = newWorkDir("catalog-$slug")
        val content = try {
            onProgress(PackProgress("Скачиваем сборку"))
            val archive = fetchArchive(
                link.url,
                File(work, "pack.zip"),
                digest,
                if (link.sizeBytes > 0) link.sizeBytes else card.sizeBytes,
                catalogPackHosts,
            )
            onProgress(PackProgress("Распаковываем сборку"))
            val unpacked = PackArchive.unpack(archive, File(work, "unpacked"))
            archive.delete()
            val launchRoot = PackLaunches.launchRoot(unpacked)
            val shape = launchRoot?.let { PackLaunches.shape(it) } ?: LaunchShape.PLAIN
            PhoneFit.require(card.loader, card.gameVersion, title, shape)
            val ownLaunch = launchRoot != null && shape != LaunchShape.PLAIN
            if (launchPaths != null && !ownLaunch) {
                throw IOException("архив «$title» распаковался без описания запуска — повтори установку, а если не поможет, напиши в поддержку")
            }
            if (launchRoot != null && ownLaunch) {
                val tree = launchRoot.walkTopDown().filter { it.isFile }.map { it.relativeTo(launchRoot).invariantSeparatorsPath }.toList()
                PhoneFit.ownLaunchProblem(tree)?.let { throw PhoneUnsupported(it, title, card.gameVersion) }
            }
            val root = PackArchive.root(unpacked)
            val read = when {
                launchRoot != null && ownLaunch -> catalogTree(card, launchRoot, PackLaunches.read(launchRoot)?.vanillaAssets.orEmpty())
                PackArchive.hasManifest(root) -> reader.read(unpacked, title)
                else -> catalogTree(card, PackArchive.contentRoot(unpacked))
            }
            val cardLoader = card.loader.takeIf { it.isNotBlank() }?.let { PackLoaders.fromId(it).first }
            read.copy(
                plan = read.plan.copy(
                    name = title.ifEmpty { read.plan.name },
                    gameVersion = card.gameVersion.ifEmpty { read.plan.gameVersion },
                    loader = cardLoader ?: read.plan.loader,
                    loaderVersion = card.loaderVersion.ifEmpty { read.plan.loaderVersion },
                    versionName = card.version.ifEmpty { read.plan.versionName },
                    source = PackSource.MILLIDA_CATALOG,
                    sourceId = slug,
                    iconUrl = card.cover,
                ),
                workDir = work,
                ownLaunch = ownLaunch,
            )
        } catch (failure: Exception) {
            work.deleteRecursively()
            throw failure
        }
        return installer.install(content, target, onProgress)
    }

    /** The build installed from this catalogue pack, if one is on the phone. */
    fun installedCatalogPack(builds: List<Profile>, slug: String): Profile? = builds.firstOrNull { build ->
        val origin = PackOriginStore.read(profiles.directory(build))
        origin != null && origin.source == PackSource.MILLIDA_CATALOG.id && origin.id == slug
    }

    /**
     * A catalogue build whose archive brings its own launch but whose folder
     * has no description: it was unpacked partly and starts as bare Minecraft.
     * Only the archive index decides; without the network the answer is no.
     */
    suspend fun ownLaunchLost(profile: Profile): Boolean {
        val dir = profiles.directory(profile)
        val origin = PackOriginStore.read(dir) ?: return false
        if (origin.source != PackSource.MILLIDA_CATALOG.id || !catalogSlugValid(origin.id)) return false
        if (File(dir, PhoneFit.PACK_LAUNCH_FILE).isFile) return false
        return try {
            val card = api.catalogPack(origin.id)
            api.catalogPackIndex(card.clientFileId)?.let { PhoneFit.launchRelative(it) } != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (offline: IOException) {
            false
        }
    }

    /**
     * Moves the player's files of a pack whose access is gone out of the build,
     * before the build is removed. Throws, and the build stays, when any of
     * them could not be moved.
     */
    @Throws(IOException::class)
    fun stashPlayerFiles(profile: Profile, slug: String): Int =
        PackKeep.stash(profiles.directory(profile), PackKeep.keepDir(layout.root, slug))

    /**
     * Only an answer of our API counts as "no access": a lost connection or a
     * missing sign-in must not send a paying player to the key window.
     */
    private fun accessRefusal(error: IOException): Boolean =
        error !is Unauthorized &&
            error !is java.io.InterruptedIOException &&
            error !is java.net.UnknownHostException &&
            error !is java.net.SocketException &&
            error.message?.contains("войди", ignoreCase = true) != true

    /**
     * A catalogue archive is often the game folder itself, with no manifest of
     * its own: the card is then the whole description, as it is on the desktop.
     */
    @Throws(IOException::class)
    private fun catalogTree(card: CatalogPackCard, unpacked: File, gameFallback: String = ""): PackContent {
        val gameVersion = card.gameVersion.ifBlank { gameFallback }
        if (gameVersion.isBlank()) {
            throw IOException("сборка не сказала, под какую версию Minecraft она собрана — напиши в поддержку")
        }
        val (loader, loaderVersion) = PackLoaders.fromId(card.loader)
        return PackContent(
            plan = PackPlan(
                name = card.title.ifEmpty { card.slug },
                gameVersion = gameVersion,
                loader = loader,
                loaderVersion = card.loaderVersion.ifEmpty { loaderVersion },
                versionName = card.version,
                source = PackSource.MILLIDA_CATALOG,
                sourceId = card.slug,
                iconUrl = card.cover,
            ),
            trees = listOf(unpacked),
        )
    }

    /**
     * A modpack of a catalogue card whose .mrpack or zip lies on our mirror.
     * The card's sha1 is the only proof of the bytes the resolver answer lacks,
     * so without it nothing is downloaded.
     */
    @Throws(IOException::class)
    suspend fun installMirroredPack(
        file: MirroredPackFile,
        target: Profile? = null,
        onProgress: (PackProgress) -> Unit = {},
    ): Profile {
        val name = file.fileName.lowercase()
        if (!name.endsWith(".mrpack") && !name.endsWith(".zip")) {
            throw IOException("файл сборки не в формате zip или mrpack — поставь её через лаунчер на компьютере")
        }
        val sha1 = file.sha1.trim().lowercase()
        if (sha1.length != 40 || !sha1.all { it.isDigit() || it in 'a'..'f' }) {
            throw IOException("у файла сборки нет контрольной суммы — установка остановлена, напиши в поддержку")
        }
        PhoneFit.require(file.loader, file.gameVersion, file.title)
        onProgress(PackProgress("Читаем сборку"))
        val link = api.mirrorFileLink(file.article, file.fileId)
        hosts.check(link.url)
        val work = newWorkDir("mirror-${file.slug}")
        val content = try {
            onProgress(PackProgress("Скачиваем сборку"))
            val archiveName = SafePath.fileName(link.name.ifBlank { file.fileName })
            val archive = fetchArchive(link.url, File(work, archiveName), sha1, file.size)
            val read = reader.read(PackArchive.unpack(archive, File(work, "unpacked")), file.title)
            PhoneFit.require(read.plan.loader.id, read.plan.gameVersion, file.title)
            read.copy(
                plan = read.plan.copy(
                    name = file.title.ifEmpty { read.plan.name },
                    versionName = file.version.ifEmpty { read.plan.versionName },
                    versionId = file.fileId,
                    source = PackSource.MILLIDA_MIRROR,
                    sourceId = file.slug,
                    iconUrl = file.icon,
                ),
                workDir = work,
            )
        } catch (failure: Exception) {
            work.deleteRecursively()
            throw failure
        }
        return installer.install(content, target, onProgress)
    }

    /** A build a player passed on by code: the manifest is installed, not an archive. */
    @Throws(IOException::class)
    suspend fun installByCode(
        code: String,
        target: Profile? = null,
        onProgress: (PackProgress) -> Unit = {},
    ): Profile {
        onProgress(PackProgress("Читаем сборку"))
        val (view, manifest) = api.sharedPack(code)
        if (manifest.files.isEmpty()) {
            throw IOException("в этой сборке не осталось файлов, которые можно скачать — попроси автора поделиться заново")
        }
        val plan = manifest.toPlan(view.code)
        PhoneFit.require(plan.loader.id, plan.gameVersion, plan.name)
        val profile = installer.install(PackContent(plan), target, onProgress)
        api.reportInstalled(view.code)
        return profile
    }

    /**
     * Publishes a build and returns the code to pass on. Only files a catalogue
     * serves travel; what stays behind is named, so the player learns it here
     * and not from a friend whose build turned out different.
     */
    @Throws(IOException::class)
    suspend fun share(profile: Profile, summary: String = ""): SharedPackResult {
        val directory = profiles.directory(profile)
        val origin = PackOriginStore.read(directory)
        val known = knownFiles(directory, origin)
        val skipped = ArrayList<String>()
        PACK_SHARED_DIRS.forEach { folder ->
            val source = File(directory, folder)
            if (!source.isDirectory) return@forEach
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(directory).path.replace(File.separatorChar, '/')
                if (file.name.endsWith(DISABLED_SUFFIX) || file.name.startsWith("millida-")) return@forEach
                if (relative !in known) skipped += relative
            }
        }
        if (known.isEmpty()) {
            throw IOException("в сборке нет файлов из Modrinth или CurseForge — передавать нечего, поставь моды из каталога")
        }
        val manifest = PackManifest(
            name = profile.name,
            summary = summary.filter { it.code >= 0x20 || it == '\n' }.take(300).trim(),
            game = profile.gameVersion,
            loader = profile.loader.id,
            loaderVersion = profile.loaderVersion,
            files = known.values.toList(),
        )
        val code = api.publish(manifest)
        origin?.let { PackOriginStore.write(directory, it.copy(shareCode = code)) }
        return SharedPackResult(code, PackCodes.link(code), manifest.files.size, skipped.sorted())
    }

    @Throws(IOException::class)
    suspend fun exportMrpack(
        profile: Profile,
        out: File,
        name: String = "",
        version: String = "",
        summary: String = "",
    ): File {
        val directory = profiles.directory(profile)
        val known = knownFiles(directory, PackOriginStore.read(directory))
        return PackExport.exportMrpack(profile, directory, out, name, version, summary, known, hosts)
    }

    /**
     * Moves a build to another version of the same pack. Nothing of the old one
     * is removed until the new files are on the disk: an update that fails
     * halfway must leave a build that still starts. A pack from our mirror is
     * described by its catalogue card, which the caller reads and hands in.
     */
    @Throws(IOException::class)
    suspend fun update(
        profile: Profile,
        versionId: String? = null,
        mirror: MirroredPackFile? = null,
        onProgress: (PackProgress) -> Unit = {},
    ): Profile {
        val origin = PackOriginStore.read(profiles.directory(profile))
            ?: throw IOException("эта сборка создана не из готовой сборки — обновлять её нечем")
        return when (PackUpdates.routeOf(origin)) {
            PackUpdateRoute.MODRINTH -> installModrinthModpack(origin.id, versionId, profile, onProgress)
            PackUpdateRoute.CURSEFORGE -> installCurseForgeModpack(origin.id, versionId, profile, onProgress)
            PackUpdateRoute.MILLIDA_CATALOG -> installCatalogPack(origin.id, profile, onProgress)
            PackUpdateRoute.MILLIDA_CODE -> installByCode(origin.id, profile, onProgress)
            PackUpdateRoute.MILLIDA_MIRROR -> {
                val file = mirror?.takeIf { it.slug == origin.id }
                    ?: throw IOException("не удалось прочитать карточку сборки в каталоге Millida — попробуй позже")
                installMirroredPack(file, profile, onProgress)
            }
            null -> throw IOException("эта сборка поставлена из файла — обнови её, выбрав новый файл сборки")
        }
    }

    /**
     * The newer published version of the pack a build came from, or null when
     * the build is current or was not installed from a catalogue. A mirrored
     * pack is compared with the file its card offers now.
     */
    @Throws(IOException::class)
    suspend fun findUpdate(profile: Profile, mirror: MirroredPackFile? = null): PackUpdateOffer? {
        val origin = PackOriginStore.read(profiles.directory(profile)) ?: return null
        val route = PackUpdates.routeOf(origin) ?: return null
        return when (route) {
            PackUpdateRoute.MODRINTH -> {
                val newest = api.modpackVersions(origin.id).firstOrNull() ?: return null
                PackUpdates.offer(route, origin, newest.id, newest.versionNumber.ifEmpty { newest.name })
            }
            PackUpdateRoute.CURSEFORGE -> {
                val newest = curseForgeModpackFile(origin.id, null)
                PackUpdates.offer(route, origin, newest.id, newest.name.substringBeforeLast('.'))
            }
            PackUpdateRoute.MILLIDA_CATALOG -> {
                val card = api.catalogPack(origin.id)
                PackUpdates.catalogOffer(origin, card.version)
            }
            PackUpdateRoute.MILLIDA_MIRROR -> {
                val file = mirror?.takeIf { it.slug == origin.id } ?: return null
                PackUpdates.offer(route, origin, file.fileId, file.version.ifEmpty { file.fileName })
            }
            PackUpdateRoute.MILLIDA_CODE -> null
        }
    }

    /** An empty build with its loader installed, the base the AI builder fills with mods. */
    @Throws(IOException::class)
    suspend fun createBuild(
        name: String,
        gameVersion: String,
        loader: net.millida.launcher.core.loader.LoaderKind,
        onProgress: (PackProgress) -> Unit = {},
    ): Profile {
        val title = name.trim().ifEmpty { "Сборка $gameVersion" }
        PhoneFit.require(loader.id, gameVersion, title)
        return installer.install(
            PackContent(PackPlan(name = title, gameVersion = gameVersion, loader = loader, source = PackSource.FILE)),
            null,
            onProgress,
        )
    }

    private fun knownFiles(directory: File, origin: PackOrigin?): Map<String, PackManifestFile> {
        val known = LinkedHashMap<String, PackManifestFile>()
        origin?.files.orEmpty().forEach { file ->
            val target = runCatching { SafePath.join(directory, file.path) }.getOrNull() ?: return@forEach
            if (!target.isFile || !hosts.allows(file.url)) return@forEach
            val sha1 = file.sha1.takeIf { it.length == 40 } ?: Hashing.sha1(target)
            known[file.path] = PackManifestFile(
                path = file.path,
                kind = file.path.substringBefore('/'),
                projectId = file.projectId,
                versionId = file.versionId,
                sha1 = sha1,
                size = if (file.size > 0) file.size else target.length(),
                url = file.url,
            )
        }
        return known
    }

    @Throws(IOException::class)
    private suspend fun fetchArchive(
        url: String,
        target: File,
        digest: String? = null,
        size: Long = 0,
        policy: PackHostPolicy = hosts,
    ): File {
        policy.check(url)
        target.parentFile?.mkdirs()
        downloader.fetch(RemoteFile(url = url, target = target, sha1 = digest?.takeIf { it.isNotEmpty() }, size = size))
        return target
    }

    /**
     * The first file of a CurseForge modpack is often the server pack, which has
     * no manifest.json: installing it would give the player a build with no
     * client half at all.
     */
    @Throws(IOException::class)
    private suspend fun curseForgeModpackFile(projectId: String, fileId: String?): ModpackArchive {
        if (fileId != null) {
            val body = downloader.text("$curseForgeBase/v1/mods/$projectId/files/$fileId")
            val data = MojangJson.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                ?: throw IOException("CurseForge не отдал файл сборки — попробуй позже")
            return archiveOf(data, projectId)
        }
        val body = downloader.text("$curseForgeBase/v1/mods/$projectId/files?pageSize=30")
        val list = (MojangJson.parseToJsonElement(body).jsonObject["data"] as? JsonArray)
            ?.map { it.jsonObject }
            .orEmpty()
        val clientOnly = list.filter { entry ->
            val name = entry["fileName"]?.jsonPrimitive?.content.orEmpty().lowercase()
            entry["isServerPack"]?.jsonPrimitive?.booleanOrNull != true &&
                entry["serverPackFileId"]?.jsonPrimitive?.longOrNull != entry["id"]?.jsonPrimitive?.longOrNull &&
                !name.contains("server")
        }
        val pool = clientOnly.ifEmpty { list }
        val picked = pool.firstOrNull { it["releaseType"]?.jsonPrimitive?.longOrNull == 1L }
            ?: pool.firstOrNull()
            ?: throw IOException("у сборки на CurseForge нет ни одного файла — выбери другую сборку")
        return archiveOf(picked, projectId)
    }

    @Throws(IOException::class)
    private suspend fun archiveOf(
        data: kotlinx.serialization.json.JsonObject,
        projectId: String,
    ): ModpackArchive {
        val numericId = data["id"]?.jsonPrimitive?.longOrNull
        val id = numericId?.toString().orEmpty()
        val rawName = data["fileName"]?.jsonPrimitive?.content?.takeIf { it != "null" }.orEmpty()
        val name = rawName.ifEmpty { "pack.zip" }
        val sha1 = (data["hashes"] as? JsonArray)
            ?.map { it.jsonObject }
            ?.firstOrNull { it["algo"]?.jsonPrimitive?.longOrNull == 1L }
            ?.get("value")?.jsonPrimitive?.content
            ?.takeIf { it.isNotEmpty() }
        val direct = data["downloadUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() && it != "null" }
        val cdn = if (sha1 != null) ForgeCdn.urls(numericId ?: 0, rawName) else emptyList()
        val urls = when {
            direct != null -> listOf(direct)
            cdn.isNotEmpty() -> cdn
            else -> {
                val body = downloader.text("$curseForgeBase/v1/mods/$projectId/files/$id/download-url")
                val link = MojangJson.parseToJsonElement(body).jsonObject["data"]?.jsonPrimitive?.content
                    ?.takeIf { it.isNotEmpty() && it != "null" }
                    ?: throw IOException("автор сборки запретил скачивание — поставь её вручную с сайта CurseForge")
                listOf(link)
            }
        }
        return ModpackArchive(id, name, urls, sha1, data["fileLength"]?.jsonPrimitive?.longOrNull ?: 0)
    }

    @Throws(IOException::class)
    private suspend fun fetchModpackArchive(file: ModpackArchive, target: File): File {
        var lastError: IOException? = null
        for (url in file.urls) {
            try {
                return fetchArchive(url, target, file.sha1, file.size)
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("у сборки на CurseForge нет адреса для скачивания — попробуй позже")
    }

    private fun newWorkDir(tag: String): File {
        val root = File(layout.caches, "packs")
        root.mkdirs()
        val work = File(root, SafePath.fileName("work-$tag-" + System.nanoTime()))
        work.deleteRecursively()
        work.mkdirs()
        return work
    }

    private data class ModpackArchive(
        val id: String,
        val name: String,
        val urls: List<String>,
        val sha1: String?,
        val size: Long,
    )
}

/**
 * The one host a catalogue pack archive is served from. The address is signed
 * by our API, yet a mistaken answer must not aim a two-gigabyte download that
 * is unpacked into a game folder at anything else.
 */
const val CATALOG_PACK_HOST = "garage.millida.net"

/** A catalogue card's modpack file on our mirror, as the card describes it. */
data class MirroredPackFile(
    val slug: String,
    val title: String,
    val article: String,
    val fileId: String,
    val fileName: String,
    val sha1: String,
    val size: Long = 0,
    val version: String = "",
    val gameVersion: String = "",
    val loader: String = "",
    val icon: String = "",
)
