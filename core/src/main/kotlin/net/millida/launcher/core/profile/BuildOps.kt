package net.millida.launcher.core.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.catalog.Catalog
import net.millida.launcher.core.catalog.CatalogItem
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.install.GameInstaller
import net.millida.launcher.core.install.InstallStage
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.MojangJson
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Folders and files that belong to the player and not to a game version: they
 * are carried into a migrated build as they are, unlike mod jars which have to
 * be looked up again for the new version.
 */
private val CARRIED = listOf(
    "config",
    "saves",
    "resourcepacks",
    "shaderpacks",
    "datapacks",
    "schematics",
    "screenshots",
    "options.txt",
    "servers.dat",
)

private val COVER_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

private const val MAX_COVER_BYTES = 12L * 1024 * 1024

@Serializable
data class BuildMeta(
    val group: String = "",
    val icon: String = "",
    /** File name inside the build folder, not an absolute path: the game root can move. */
    val cover: String = "",
)

/**
 * Group, icon and cover live beside profiles.json rather than inside it, so a
 * build keeps its looks even when the profile list is rewritten by another part
 * of the launcher.
 */
class BuildMetaStore(private val layout: GameLayout) {
    private val file: File get() = File(layout.root, "build-meta.json")

    private val serializer = MapSerializer(String.serializer(), BuildMeta.serializer())

    fun all(): Map<String, BuildMeta> {
        if (!file.isFile) return emptyMap()
        return try {
            MojangJson.decodeFromString(serializer, file.readText())
        } catch (broken: Exception) {
            emptyMap()
        }
    }

    fun of(profileId: String): BuildMeta = all()[profileId] ?: BuildMeta()

    fun groups(): List<String> = all().values
        .map { it.group }
        .filter { it.isNotBlank() }
        .distinct()
        .sortedBy { it.lowercase() }

    fun inGroup(group: String): List<String> = all()
        .filterValues { it.group.equals(group.trim(), ignoreCase = true) }
        .keys
        .sorted()

    fun setGroup(profileId: String, group: String): BuildMeta =
        update(profileId) { it.copy(group = group.trim().take(60)) }

    fun setIcon(profileId: String, icon: String): BuildMeta =
        update(profileId) { it.copy(icon = icon.trim().take(60)) }

    /**
     * The picked image is copied into the build folder under a name the launcher
     * chose: the source lives in the gallery and can disappear the moment the
     * picker closes.
     */
    @Throws(IOException::class)
    fun setCover(profileId: String, source: File): BuildMeta {
        if (!source.isFile) throw IOException("картинка не найдена: ${source.name}")
        if (source.length() > MAX_COVER_BYTES) throw IOException("картинка больше 12 МБ — возьми поменьше")
        val extension = source.name.substringAfterLast('.', "").lowercase()
        if (extension !in COVER_EXTENSIONS) throw IOException("обложка может быть PNG, JPEG или WebP")

        val folder = SafePath.join(layout.profiles, profileId).also { it.mkdirs() }
        val target = File(folder, "cover.$extension")
        val staged = File(folder, "cover.$extension.new")
        // The old cover is only dropped once the new one is fully on disk.
        source.copyTo(staged, overwrite = true)
        if (target.exists()) target.delete()
        if (!staged.renameTo(target)) {
            staged.copyTo(target, overwrite = true)
            staged.delete()
        }
        dropCoversExcept(folder, target.name)
        return update(profileId) { it.copy(cover = target.name) }
    }

    fun clearCover(profileId: String): BuildMeta {
        val folder = File(layout.profiles, profileId)
        val current = of(profileId).cover
        if (current.isNotBlank()) runCatching { SafePath.join(folder, current).delete() }
        return update(profileId) { it.copy(cover = "") }
    }

    fun coverFile(profileId: String): File? {
        val name = of(profileId).cover.takeIf { it.isNotBlank() } ?: return null
        val file = runCatching { SafePath.join(layout.profiles, "$profileId/$name") }.getOrNull() ?: return null
        return file.takeIf { it.isFile }
    }

    fun forget(profileId: String) {
        val current = all()
        if (profileId !in current) return
        save(current - profileId)
    }

    private fun update(profileId: String, change: (BuildMeta) -> BuildMeta): BuildMeta {
        val current = all().toMutableMap()
        val next = change(current[profileId] ?: BuildMeta())
        if (next == BuildMeta()) current.remove(profileId) else current[profileId] = next
        save(current)
        return next
    }

    private fun save(map: Map<String, BuildMeta>) {
        file.atomicWriteText(MojangJson.encodeToString(serializer, map))
    }

    private fun dropCoversExcept(folder: File, keep: String) {
        folder.listFiles()
            ?.filter { it.isFile && it.name.startsWith("cover.") && it.name != keep }
            ?.forEach { it.delete() }
    }
}

@Serializable
data class PlayEntry(
    val seconds: Long = 0,
    val sessions: Long = 0,
    val lastAt: Long = 0,
)

@Serializable
private data class PlayDoc(
    val builds: Map<String, PlayEntry> = emptyMap(),
    val lastBuild: String = "",
    val lastAt: Long = 0,
)

data class BuildPlaytime(
    val profileId: String,
    val seconds: Long,
    val sessions: Long,
    val lastAt: Long,
)

data class PlayStats(
    val totalSeconds: Long,
    val sessions: Long,
    val lastBuild: String,
    val lastAt: Long,
    val builds: List<BuildPlaytime>,
)

/**
 * Playtime is written in slices while the game runs, so a phone that kills the
 * process loses one slice instead of the whole session. Only the first slice of
 * a launch counts as a session.
 */
class PlaytimeStore(
    private val layout: GameLayout,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val file: File get() = File(layout.root, "playtime.json")

    fun record(profileId: String, seconds: Long, newSession: Boolean = false) {
        if (profileId.isBlank()) return
        if (seconds <= 0 && !newSession) return
        val now = clockSeconds()
        val doc = read()
        val previous = doc.builds[profileId] ?: PlayEntry()
        val merged = previous.copy(
            seconds = previous.seconds + seconds.coerceAtLeast(0),
            sessions = previous.sessions + if (newSession) 1 else 0,
            lastAt = now,
        )
        write(doc.copy(builds = doc.builds + (profileId to merged), lastBuild = profileId, lastAt = now))
    }

    fun of(profileId: String): PlayEntry = read().builds[profileId] ?: PlayEntry()

    fun stats(): PlayStats {
        val doc = read()
        val builds = doc.builds
            .map { (id, entry) -> BuildPlaytime(id, entry.seconds, entry.sessions, entry.lastAt) }
            .sortedWith(compareByDescending<BuildPlaytime> { it.seconds }.thenByDescending { it.lastAt })
        return PlayStats(
            totalSeconds = builds.sumOf { it.seconds },
            sessions = builds.sumOf { it.sessions },
            lastBuild = doc.lastBuild,
            lastAt = doc.lastAt,
            builds = builds,
        )
    }

    fun forget(profileId: String) {
        val doc = read()
        if (profileId !in doc.builds) return
        write(
            doc.copy(
                builds = doc.builds - profileId,
                lastBuild = if (doc.lastBuild == profileId) "" else doc.lastBuild,
            )
        )
    }

    /**
     * Statistics are not worth a scare: an unreadable file reads as "nothing
     * played yet" and the next launch writes a healthy one over it.
     */
    private fun read(): PlayDoc {
        if (!file.isFile) return PlayDoc()
        return try {
            MojangJson.decodeFromString(PlayDoc.serializer(), file.readText())
        } catch (broken: Exception) {
            PlayDoc()
        }
    }

    private fun write(doc: PlayDoc) {
        file.atomicWriteText(MojangJson.encodeToString(PlayDoc.serializer(), doc))
    }
}

data class MigrateItem(
    val fileName: String,
    val title: String,
    val projectId: String,
    val source: CatalogSource?,
    val versionNumber: String,
    val ok: Boolean,
    val note: String,
)

data class MigratePlan(
    val items: List<MigrateItem>,
    val ready: Int,
    val missing: Int,
    /**
     * Jars the catalogue cannot be asked about - added by hand or without a mod
     * descriptor. They stay behind instead of being copied into a build they
     * would crash.
     */
    val unlinked: List<String>,
    val suggestedName: String,
)

data class MigrateResult(
    val profile: Profile,
    val moved: Int,
    val failed: List<String>,
)

data class MigrateProgress(val done: Int, val total: Int, val title: String)

data class RepairReport(
    val versionId: String,
    val missing: List<String>,
    val brokenContent: List<String>,
)

/**
 * Everything a build can have done to it beyond create, rename and delete:
 * moving it to another game version, copying it, dressing it up, repairing it
 * and counting the hours spent in it.
 */
class BuildOps(
    private val layout: GameLayout,
    private val store: ProfileStore,
    private val catalog: Catalog,
    private val content: ProfileContent = ProfileContent(layout),
    val meta: BuildMetaStore = BuildMetaStore(layout),
    val playtime: PlaytimeStore = PlaytimeStore(layout),
) {

    suspend fun migratePlan(
        profile: Profile,
        gameVersion: String,
        loader: LoaderKind,
    ): MigratePlan {
        val resolved = resolveMods(profile, gameVersion, loader)
        val items = resolved.map { it.toItem() }
            .sortedWith(compareByDescending<MigrateItem> { it.ok }.thenBy { it.title.lowercase() })
        val ready = items.count { it.ok }
        return MigratePlan(
            items = items,
            ready = ready,
            missing = items.size - ready,
            unlinked = unlinkedMods(profile).sorted(),
            suggestedName = suggestedName(profile, gameVersion),
        )
    }

    /**
     * The migration always builds a new profile: the player keeps a working copy
     * on the old version while the new one is still missing half its mods.
     */
    suspend fun migrate(
        profile: Profile,
        gameVersion: String,
        loader: LoaderKind,
        loaderVersion: String = "",
        name: String? = null,
        onProgress: (MigrateProgress) -> Unit = {},
    ): MigrateResult {
        val resolved = resolveMods(profile, gameVersion, loader)
        val wanted = name?.trim()?.takeIf { it.isNotEmpty() } ?: suggestedName(profile, gameVersion)
        val id = store.newId(wanted)
        val from = store.directory(profile)
        val to = SafePath.join(layout.profiles, id)
        if (to.exists() && to.list()?.isNotEmpty() == true) {
            throw IOException("папка сборки $id уже занята")
        }

        try {
            to.mkdirs()
            carry(from, to)
            File(to, ContentKind.MOD.folder).mkdirs()
        } catch (error: Exception) {
            to.deleteRecursively()
            throw error
        }

        var moved = 0
        val failed = ArrayList<String>()
        resolved.forEachIndexed { index, entry ->
            onProgress(MigrateProgress(index, resolved.size, entry.file.title))
            val version = entry.version
            if (version == null) {
                failed += "${entry.file.title} — ${entry.note}"
                return@forEachIndexed
            }
            try {
                catalog.install(version, ContentKind.MOD, to)
                moved++
            } catch (error: Exception) {
                failed += "${entry.file.title} — ${error.message ?: "не удалось скачать"}"
            }
        }
        onProgress(MigrateProgress(resolved.size, resolved.size, ""))

        val migrated = profile.copy(
            id = id,
            name = wanted,
            gameVersion = gameVersion,
            loader = loader,
            loaderVersion = if (loader == LoaderKind.VANILLA) "" else loaderVersion.trim(),
            // The loader for the new version is installed on the next launch;
            // until then the build points at plain Minecraft.
            versionId = gameVersion,
            lastPlayedAt = 0,
            playtimeSec = 0,
        )
        store.upsert(migrated)
        carryLooks(profile.id, id)
        return MigrateResult(migrated, moved, failed)
    }

    /**
     * A copy shares nothing with the original: files are copied, not linked, so
     * deleting a mod in the copy leaves the original build playable.
     */
    @Throws(IOException::class)
    fun duplicate(profile: Profile, withContent: Boolean = true): Profile {
        val name = "${profile.name} (копия)"
        val id = store.newId(name)
        val from = store.directory(profile)
        val to = SafePath.join(layout.profiles, id)
        to.mkdirs()
        if (withContent && from.isDirectory) {
            try {
                from.copyRecursively(to, overwrite = false)
            } catch (error: Exception) {
                to.deleteRecursively()
                throw IOException("не удалось скопировать файлы сборки: ${error.message}")
            }
        }
        val copy = profile.copy(id = id, name = name, lastPlayedAt = 0, playtimeSec = 0)
        store.upsert(copy)
        carryLooks(profile.id, id)
        return copy
    }

    /**
     * Reinstall with the installer's own checksum rules, then say what is still
     * missing instead of letting the launch die with a stack trace.
     */
    suspend fun repair(
        profile: Profile,
        installer: GameInstaller,
        onStage: (InstallStage) -> Unit = {},
    ): RepairReport {
        val resolved = installer.install(profile.versionId.ifBlank { profile.gameVersion }, onStage)
        return RepairReport(
            versionId = resolved.jarVersionId,
            missing = installer.verify(resolved),
            brokenContent = brokenContent(profile),
        )
    }

    /** Called when a build is deleted: a new build must not inherit its hours or its group. */
    fun forget(profileId: String) {
        meta.forget(profileId)
        playtime.forget(profileId)
    }

    private fun suggestedName(profile: Profile, gameVersion: String): String =
        "${profile.name.trim()} ${gameVersion.trim()}".trim()

    private fun carry(from: File, to: File) {
        for (entry in CARRIED) {
            val source = File(from, entry)
            if (!source.exists()) continue
            val target = File(to, entry)
            if (source.isDirectory) {
                target.mkdirs()
                source.copyRecursively(target, overwrite = false)
            } else {
                source.copyTo(target, overwrite = true)
            }
        }
    }

    private fun carryLooks(fromId: String, toId: String) {
        val source = meta.of(fromId)
        if (source.group.isNotBlank()) meta.setGroup(toId, source.group)
        if (source.icon.isNotBlank()) meta.setIcon(toId, source.icon)
        val cover = meta.coverFile(fromId) ?: return
        runCatching { meta.setCover(toId, cover) }
    }

    private fun brokenContent(profile: Profile): List<String> {
        val dir = store.directory(profile)
        val broken = ArrayList<String>()
        for (kind in ContentKind.entries) {
            val folder = File(dir, kind.folder)
            val files = folder.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                val base = file.name.removeSuffix(DISABLED_SUFFIX).lowercase()
                if (!base.endsWith(".jar") && !base.endsWith(".zip")) continue
                val readable = try {
                    ZipFile(file).use { it.entries().hasMoreElements() }
                } catch (error: Exception) {
                    false
                }
                if (!readable) broken += file.name
            }
        }
        return broken.sorted()
    }

    private suspend fun resolveMods(
        profile: Profile,
        gameVersion: String,
        loader: LoaderKind,
    ): List<Resolved> {
        val dir = store.directory(profile)
        return content.list(dir, ContentKind.MOD)
            .filter { it.modId.isNotBlank() }
            .map { resolveOne(it, gameVersion, loader) }
    }

    private fun unlinkedMods(profile: Profile): List<String> =
        content.list(store.directory(profile), ContentKind.MOD)
            .filter { it.modId.isBlank() }
            .map { it.name }

    private suspend fun resolveOne(
        file: ContentFile,
        gameVersion: String,
        loader: LoaderKind,
    ): Resolved {
        val found = try {
            findProject(file, gameVersion, loader)
        } catch (error: Exception) {
            return Resolved(file, null, null, error.message ?: "каталог не ответил")
        } ?: return Resolved(file, null, null, "не нашли в каталоге")

        val versions = try {
            catalog.versions(found, gameVersion, loader)
        } catch (error: Exception) {
            return Resolved(file, found, null, error.message ?: "каталог не ответил")
        }
        val best = versions.firstOrNull { it.primaryFile() != null }
            ?: return Resolved(file, found, null, "нет файла под $gameVersion · ${loader.label}")
        return Resolved(file, found, best, "")
    }

    /**
     * Mobile keeps no catalogue link next to a jar, so the project is matched by
     * the identifier inside the mod itself. A loose search hit is refused on
     * purpose: installing a same-named stranger is worse than saying "не нашли".
     */
    private suspend fun findProject(
        file: ContentFile,
        gameVersion: String,
        loader: LoaderKind,
    ): CatalogItem? {
        val query = file.title.ifBlank { file.modId }
        val hits = catalog.search(
            query = query,
            kind = ContentKind.MOD,
            gameVersion = gameVersion,
            loader = loader,
            limit = 10,
        )
        val modId = file.modId.lowercase()
        val title = file.title.trim().lowercase()
        return hits.firstOrNull { it.slug.lowercase() == modId }
            ?: hits.firstOrNull { it.title.trim().lowercase() == title }
            ?: hits.firstOrNull { it.slug.lowercase() == title.replace(' ', '-') }
    }

    private data class Resolved(
        val file: ContentFile,
        val item: CatalogItem?,
        val version: CatalogVersion?,
        val note: String,
    ) {
        fun toItem(): MigrateItem = MigrateItem(
            fileName = file.name,
            title = file.title.ifBlank { file.name },
            projectId = item?.projectId.orEmpty(),
            source = item?.source,
            versionNumber = version?.versionNumber.orEmpty(),
            ok = version != null,
            note = note,
        )
    }
}
