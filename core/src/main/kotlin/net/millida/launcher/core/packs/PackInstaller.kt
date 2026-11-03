package net.millida.launcher.core.packs

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileStore
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

/**
 * Picks the version id a build will be launched by. The loader metadata lives in
 * the loader module, and the installer has no business talking to it directly:
 * the caller hands in whatever it already uses to create a build by hand, so
 * there is one place that decides what a Fabric build is called.
 */
fun interface PackVersionResolver {
    suspend fun resolve(gameVersion: String, loader: LoaderKind, loaderVersion: String): PackVersionChoice

    companion object {
        /** Vanilla only: a build is launched by the game version itself. */
        fun plain(): PackVersionResolver = PackVersionResolver { gameVersion, _, loaderVersion ->
            PackVersionChoice(gameVersion, loaderVersion)
        }
    }
}

data class PackVersionChoice(val versionId: String, val loaderVersion: String = "")

/**
 * Puts a plan on the disk. Everything is assembled in a work folder first and
 * only swapped in once it is complete: a failed or cancelled install must leave
 * the build that is already there exactly as it was, mods included.
 */
class PackInstaller(
    private val layout: GameLayout,
    private val profiles: ProfileStore,
    private val downloader: Downloader,
    private val versions: PackVersionResolver = PackVersionResolver.plain(),
    private val hosts: PackHostPolicy = PackHostPolicy(),
    private val gate: PackDownloadGate = PackDownloadGate.OPEN,
) {

    @Throws(IOException::class)
    suspend fun install(
        content: PackContent,
        target: Profile? = null,
        onProgress: (PackProgress) -> Unit = {},
    ): Profile {
        val stage = newStage(content.plan)
        var keepStage = false
        try {
            onProgress(PackProgress(tr("Готовим файлы сборки")))
            content.trees.forEach { PackArchive.copyTree(it, stage) }
            File(stage, PackOriginStore.FILE_NAME).delete()
            File(stage, "millida.pack.json").delete()
            File(stage, PackKeep.SHIPPED_FILE).delete()
            File(stage, PackEdits.SHIPPED_MODS_FILE).delete()
            val ownLaunch = content.ownLaunch && content.plan.source == PackSource.MILLIDA_CATALOG
            if (!ownLaunch) File(stage, PhoneFit.PACK_LAUNCH_FILE).delete()
            val catalogue = content.plan.source == PackSource.MILLIDA_CATALOG && catalogSlugValid(content.plan.sourceId)

            val bytes = content.plan.files.sumOf { it.size.coerceAtLeast(0) }
            if (bytes > 0 && !gate.allow(bytes, content.plan.name)) throw PackDownloadDeclined()
            val landed = try {
                download(content.plan.files, stage, onProgress)
            } catch (error: IOException) {
                // Files already on disk stay: the next try goes on from where the network broke.
                keepStage = true
                throw error
            }

            val shipped = PackKeep.shipped(stage)

            onProgress(PackProgress(tr("Определяем версию игры")))
            val choice = if (ownLaunch) {
                PackVersionChoice(content.plan.gameVersion, content.plan.loaderVersion)
            } else {
                versions.resolve(content.plan.gameVersion, content.plan.loader, content.plan.loaderVersion)
            }

            val profile = target?.let { existing ->
                val installed = PackOriginStore.read(profiles.directory(existing))
                existing.copy(
                    name = PackNames.renamed(existing.name, installed, takenNames(existing.id)) ?: existing.name,
                    gameVersion = content.plan.gameVersion,
                    loader = content.plan.loader,
                    loaderVersion = choice.loaderVersion,
                    versionId = choice.versionId,
                )
            } ?: newProfile(content.plan, choice)

            onProgress(PackProgress(tr("Переносим файлы сборки")))
            val directory = profiles.directory(profile)
            val previous = PackOriginStore.read(directory)
            commit(stage, directory, PackMerge.previouslyShipped(directory, previous))
            // A build that was an own-launch pack before must not keep running that description after a plain install.
            if (!ownLaunch) File(directory, PhoneFit.PACK_LAUNCH_FILE).delete()
            PackKeep.recordShipped(directory, shipped)
            PackEdits.recordShippedMods(directory)
            if (catalogue) {
                PackKeep.restore(directory, PackKeep.keepDir(layout.root, content.plan.sourceId))
            }

            PackOriginStore.write(
                directory,
                PackOrigin(
                    source = content.plan.source.id,
                    id = content.plan.sourceId,
                    versionId = content.plan.versionId,
                    versionName = content.plan.versionName,
                    name = content.plan.name,
                    installedAt = System.currentTimeMillis(),
                    shareCode = previous?.shareCode.orEmpty(),
                    files = landed,
                    buildId = profile.id,
                    autoUpdate = previous?.takeIf { it.buildId == profile.id }?.autoUpdate,
                ),
            )
            profiles.upsert(profile)
            onProgress(PackProgress(tr("Сборка установлена")))
            return profile
        } finally {
            if (!keepStage) stage.deleteRecursively()
            content.workDir?.deleteRecursively()
        }
    }

    @Throws(IOException::class)
    private suspend fun download(
        files: List<PackFileEntry>,
        stage: File,
        onProgress: (PackProgress) -> Unit,
    ): List<PackOriginFile> {
        val results = arrayOfNulls<PackOriginFile>(files.size)
        val finished = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = kotlinx.coroutines.sync.Semaphore(PARALLEL_FILES)
        kotlinx.coroutines.coroutineScope {
            files.forEachIndexed { index, entry ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    gate.withPermit {
                        val target = SafePath.join(stage, entry.path)
                        var lastError: IOException? = null
                        // A break of the link is waited out: three rounds with a growing pause before a file is given up.
                        for (round in 0 until ROUNDS) {
                            if (round > 0) kotlinx.coroutines.delay(ROUND_PAUSE_MS * round)
                            for (url in entry.urls) {
                                try {
                                    hosts.check(url)
                                    downloader.fetch(RemoteFile(url = url, target = target, sha1 = entry.sha1, size = entry.size))
                                    results[index] = PackOriginFile(
                                        path = entry.path,
                                        url = url,
                                        sha1 = entry.sha1.orEmpty(),
                                        size = entry.size,
                                        projectId = entry.projectId,
                                        versionId = entry.versionId,
                                    )
                                    break
                                } catch (error: IOException) {
                                    lastError = error
                                }
                            }
                            if (results[index] != null) break
                        }
                        if (results[index] == null) {
                            if (entry.required) {
                                throw IOException(
                                    tr("не скачался файл сборки {path}: {0} — проверь сеть и попробуй ещё раз", entry.path, lastError?.message ?: tr("нет ответа")),
                                )
                            }
                            target.delete()
                        }
                        onProgress(finished.incrementAndGet().let { n -> PackProgress(tr("Файлы сборки: {0} из {1}", n, files.size), n, files.size, "") })
                    }
                }
            }
        }
        return results.filterNotNull()
    }

    private fun takenNames(exceptId: String): Set<String> =
        profiles.load().filterNot { it.id == exceptId }.map { it.name }.toSet()

    private fun newProfile(plan: PackPlan, choice: PackVersionChoice): Profile {
        val name = PackNames.fresh(plan.name, plan.versionName, takenNames(""))
        return Profile(
            id = profiles.newId(name),
            name = name,
            gameVersion = plan.gameVersion,
            loader = plan.loader,
            loaderVersion = choice.loaderVersion,
            versionId = choice.versionId,
        )
    }

    /**
     * Swaps the folders a pack owns and leaves everything else alone: worlds,
     * screenshots and options belong to the player, not to the pack. The old
     * folder is moved aside rather than deleted, so a failure halfway through
     * puts the previous build back instead of leaving a build without mods.
     * Resource and shader packs are shared with the player: what they added
     * there is carried into the new folder, only the old pack's own files go.
     */
    @Throws(IOException::class)
    private fun commit(stage: File, directory: File, previouslyShipped: Set<String>) {
        directory.mkdirs()
        PackParking.recover(directory)
        val stamp = System.currentTimeMillis()
        val parked = ArrayList<Pair<File, File>>()
        val added = ArrayList<File>()
        try {
            stage.listFiles().orEmpty().forEach { entry ->
                val target = SafePath.join(directory, entry.name)
                if (entry.isDirectory) {
                    var aside: File? = null
                    if (target.exists()) {
                        val moved = File(directory, PackParking.parkedName(entry.name, stamp))
                        if (!target.renameTo(moved)) {
                            throw IOException(tr("не удалось освободить папку {name} — закрой игру и повтори установку", entry.name))
                        }
                        parked += target to moved
                        aside = moved
                    } else {
                        added += target
                    }
                    if (!entry.renameTo(target)) {
                        PackArchive.copyTree(entry, target)
                    }
                    if (aside != null && entry.name in PackMerge.SHARED_FOLDERS) {
                        carryPlayerFiles(entry.name, aside, target, previouslyShipped)
                    }
                } else {
                    if (target.exists()) {
                        val moved = File(directory, PackParking.parkedName(entry.name, stamp))
                        if (!target.renameTo(moved)) {
                            throw IOException(tr("не удалось освободить файл {name} — закрой игру и повтори установку", entry.name))
                        }
                        parked += target to moved
                    } else {
                        added += target
                    }
                    if (!entry.renameTo(target)) entry.copyTo(target, overwrite = true)
                }
            }
        } catch (failure: Exception) {
            added.forEach { it.deleteRecursively() }
            parked.forEach { (target, aside) ->
                target.deleteRecursively()
                aside.renameTo(target)
            }
            throw failure
        }
        parked.forEach { (_, aside) -> aside.deleteRecursively() }
    }

    /** Copied, not moved: a failure later in the commit restores the parked folder whole. */
    @Throws(IOException::class)
    private fun carryPlayerFiles(folder: String, aside: File, target: File, previouslyShipped: Set<String>) {
        val incoming = target.list().orEmpty().toSet()
        PackMerge.carried(folder, aside.list().orEmpty().toList(), incoming, previouslyShipped).forEach { name ->
            val from = File(aside, name)
            val into = SafePath.join(target, name)
            val copied = runCatching { from.copyRecursively(into, overwrite = false) }.getOrDefault(false)
            if (!copied) {
                into.deleteRecursively()
                throw IOException(tr("не удалось перенести «{folder}/{name}» в обновлённую сборку — освободи место и повтори", folder, name))
            }
        }
    }

    /** One stage per pack version, so a broken download is continued, not started over. */
    private fun newStage(plan: PackPlan): File {
        val root = File(layout.caches, "packs")
        root.mkdirs()
        val now = System.currentTimeMillis()
        root.listFiles { f -> f.isDirectory && f.name.startsWith("stage-") }.orEmpty()
            .filter { now - it.lastModified() > STAGE_KEEP_MS }
            .forEach { it.deleteRecursively() }
        val key = "${plan.source.id}|${plan.sourceId}|${plan.versionId}|${plan.name}".hashCode().toUInt().toString(16)
        val stage = File(root, "stage-$key")
        stage.mkdirs()
        stage.setLastModified(now)
        return stage
    }

    private companion object {
        const val STAGE_KEEP_MS = 2 * 24 * 3_600_000L
        const val PARALLEL_FILES = 6
        const val ROUNDS = 3
        const val ROUND_PAUSE_MS = 4_000L
    }
}

/**
 * The previous copy of a folder or file is parked beside the build while the
 * new one moves in. A process killed between the two moves leaves only the
 * parked copy, and the build is put back from it before the next install.
 */
internal object PackParking {
    private const val MARK = ".old-"

    fun parkedName(name: String, stamp: Long): String = "$name$MARK$stamp"

    fun recover(directory: File) {
        val parked = directory.listFiles().orEmpty().mapNotNull { file ->
            val at = file.name.lastIndexOf(MARK)
            if (at <= 0) return@mapNotNull null
            val stamp = file.name.substring(at + MARK.length).toLongOrNull() ?: return@mapNotNull null
            Triple(file.name.substring(0, at), stamp, file)
        }
        parked.groupBy { it.first }.forEach { (name, copies) ->
            val live = File(directory, name)
            if (live.exists()) return@forEach
            copies.maxByOrNull { it.second }?.third?.renameTo(live)
        }
    }
}

/**
 * Which entries of the player's resource and shader packs survive an install
 * over an existing build. The new pack wins a name clash; a file the previous
 * pack put there itself is the pack's and leaves with it.
 */
internal object PackMerge {
    val SHARED_FOLDERS: Set<String> = setOf("resourcepacks", "shaderpacks")

    fun carried(folder: String, existing: List<String>, incoming: Set<String>, previouslyShipped: Set<String>): List<String> =
        existing.filter { name -> name !in incoming && "$folder/$name" !in previouslyShipped }

    /** The previous pack's own entries: the record of what it shipped plus the files it downloaded. */
    fun previouslyShipped(directory: File, previous: PackOrigin?): Set<String> {
        val recorded = PackKeep.shippedEntries(directory).orEmpty()
        val downloaded = previous?.files.orEmpty().map { it.path }
        return recorded + downloaded
    }
}
