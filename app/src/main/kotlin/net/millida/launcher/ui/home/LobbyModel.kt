package net.millida.launcher.ui.home

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.millida.launcher.Container
import net.millida.launcher.JoinPlan
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.content.LocalFileCandidate
import net.millida.launcher.core.mojang.VersionManifest
import net.millida.launcher.core.profile.BuildMeta
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.core.rating.RatingSort
import net.millida.launcher.core.servers.OwnServers
import net.millida.launcher.knownVersions
import net.millida.launcher.loadErrorText
import net.millida.launcher.pingVersions
import net.millida.launcher.serverVersions
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

data class JoinPrompt(
    val address: String,
    val serverName: String,
    val wanted: List<String>,
    val plan: JoinPlan,
    val version: String,
)

data class CreateAsk(val version: String?)

data class LocalImportPrompt(
    val profileId: String,
    val fileName: String,
    val kind: ContentKind?,
    val warnings: List<String>,
    internal val candidate: LocalFileCandidate,
    internal val staging: File,
)

data class LobbyState(
    val metas: Map<String, BuildMeta> = emptyMap(),
    val covers: Map<String, String> = emptyMap(),
    val coverStamps: Map<String, Long> = emptyMap(),
    val playtime: Map<String, Long> = emptyMap(),
    val totalSeconds: Long = 0,
    val lastPlayedId: String = "",
    val versions: List<VersionManifest.ManifestEntry> = emptyList(),
    val versionsLoading: Boolean = false,
    val versionsError: String? = null,
    val joining: String = "",
    val join: JoinPrompt? = null,
    val importing: Boolean = false,
    val importPrompt: LocalImportPrompt? = null,
    val coverBusy: Boolean = false,
    val createAsk: CreateAsk? = null,
    val topServers: List<RatedServer> = emptyList(),
    val serversLoading: Boolean = false,
    val serversError: String? = null,
)

class LobbyModel(
    private val container: Container,
    private val scope: CoroutineScope,
    private val notify: (String, Boolean) -> Unit,
) {
    private val mutable = MutableStateFlow(LobbyState())
    val state: StateFlow<LobbyState> = mutable.asStateFlow()

    private val refreshSeq = AtomicLong()
    private val topSeq = AtomicLong()
    private var topLoaded = false

    fun refresh(profiles: List<Profile>) {
        val ticket = refreshSeq.incrementAndGet()
        scope.launch {
            val read = try {
                withContext(Dispatchers.IO) {
                    val metas = container.buildMeta.all()
                    val coverFiles = profiles.mapNotNull { profile ->
                        container.buildMeta.coverFile(profile.id)?.let { profile.id to it }
                    }
                    val covers = coverFiles.associate { (id, file) -> id to file.absolutePath }
                    val stamps = coverFiles.associate { (id, file) -> id to file.lastModified() }
                    val stats = container.playtime.stats()
                    val hours = stats.builds.associate { it.profileId to it.seconds }
                    Triple(metas, covers to stamps, stats to hours)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (broken: Throwable) {
                Log.w("millida", "сведения о сборках не прочитались: ${broken.message}")
                return@launch
            }
            if (ticket != refreshSeq.get()) return@launch
            val (metas, art, playtime) = read
            val (covers, stamps) = art
            val (stats, hours) = playtime
            mutable.update {
                it.copy(
                    metas = metas,
                    covers = covers,
                    coverStamps = stamps,
                    playtime = hours,
                    totalSeconds = stats.totalSeconds,
                    lastPlayedId = stats.lastBuild,
                )
            }
        }
    }

    fun loadVersions() {
        if (mutable.value.versionsLoading || mutable.value.versions.isNotEmpty()) return
        mutable.update { it.copy(versionsLoading = true, versionsError = null) }
        scope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) { container.resolver.manifest() }
                mutable.update { it.copy(versions = manifest.versions, versionsLoading = false) }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(versionsLoading = false) }
                throw cancelled
            } catch (error: Throwable) {
                mutable.update {
                    it.copy(
                        versionsLoading = false,
                        versionsError = "Список версий не загрузился — проверь сеть и нажми «Повторить»",
                    )
                }
            }
        }
    }

    fun retryVersions() {
        mutable.update { it.copy(versions = emptyList(), versionsError = null) }
        loadVersions()
    }

    fun loadTopServers(force: Boolean = false) {
        if (!force && (topLoaded || mutable.value.serversLoading)) return
        val ticket = topSeq.incrementAndGet()
        mutable.update { it.copy(serversLoading = true, serversError = null) }
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    container.rating.servers(sort = RatingSort.RATING, limit = TOP_SERVERS)
                }
                if (ticket != topSeq.get()) return@launch
                topLoaded = true
                mutable.update { it.copy(topServers = OwnServers.forPhone(page.servers), serversLoading = false) }
            } catch (cancelled: CancellationException) {
                if (ticket == topSeq.get()) mutable.update { it.copy(serversLoading = false) }
                throw cancelled
            } catch (error: Throwable) {
                if (ticket != topSeq.get()) return@launch
                mutable.update { it.copy(serversLoading = false, serversError = loadErrorText(error)) }
            }
        }
    }

    fun beginJoin(address: String): Boolean {
        var started = false
        mutable.update {
            if (it.joining.isNotEmpty()) {
                it
            } else {
                started = true
                it.copy(joining = address)
            }
        }
        return started
    }

    fun endJoin(address: String) {
        mutable.update { if (it.joining == address) it.copy(joining = "") else it }
    }

    fun askJoin(prompt: JoinPrompt) {
        mutable.update { it.copy(join = prompt) }
    }

    fun dismissJoin() {
        mutable.update { it.copy(join = null) }
    }

    fun requestCreate(version: String?) {
        val ask = CreateAsk(version?.takeIf { it.isNotBlank() })
        mutable.update { it.copy(createAsk = ask) }
    }

    fun consumeCreate(): CreateAsk? {
        var taken: CreateAsk? = null
        mutable.update {
            taken = it.createAsk
            it.copy(createAsk = null)
        }
        return taken
    }

    /**
     * The rating knows a server's versions; a server outside it is asked
     * directly. The launcher's own ping allows a few seconds to answer, and a
     * server that stays silent is a question for the player, not a guess.
     */
    suspend fun wantedVersions(address: String, reported: List<String>, releaseIds: List<String>): List<String> {
        val known = knownVersions(releaseIds)
        val listed = serverVersions(reported, known)
        if (listed.isNotEmpty()) return listed
        // A blocking socket ignores cancellation: only the wait is bounded, the ping still fills the cache.
        val pending = scope.async(Dispatchers.IO) { container.serverStatuses.status(address) }
        val answer = withTimeoutOrNull(PING_WAIT_MS) { pending.await() }
        return pingVersions(answer?.version.orEmpty(), known)
    }

    fun setCover(profile: Profile, resolver: ContentResolver, uri: Uri, profiles: List<Profile>) {
        if (mutable.value.coverBusy) return
        mutable.update { it.copy(coverBusy = true) }
        scope.launch {
            var staged: File? = null
            try {
                withContext(Dispatchers.IO) {
                    val extension = imageExtension(resolver, uri)
                        ?: throw IOException("обложка может быть PNG, JPEG или WebP")
                    val folder = File(container.layout.caches, "cover-pick").also { it.mkdirs() }
                    val target = File(folder, "cover.$extension")
                    staged = target
                    val input = resolver.openInputStream(uri)
                        ?: throw IOException("картинка не открылась — выбери другую")
                    input.use { source -> target.outputStream().use { source.copyTo(it) } }
                    container.buildMeta.setCover(profile.id, target)
                }
                notify("Обложка обновлена", false)
                refresh(profiles)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                notify(humanError(error, "Обложка не сохранилась — выбери другую картинку"), true)
            } finally {
                staged?.delete()
                mutable.update { it.copy(coverBusy = false) }
            }
        }
    }

    fun clearCover(profile: Profile, profiles: List<Profile>) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { container.buildMeta.clearCover(profile.id) }
                refresh(profiles)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                notify(humanError(error, "Обложка не убралась — повтори"), true)
            }
        }
    }

    /**
     * The file is copied out of the picker first: a document address is only
     * good while the picker's grant lasts. A file that raises no question goes
     * straight in; one that does waits for [confirmImport].
     */
    fun importLocalFile(resolver: ContentResolver, uri: Uri, profile: Profile, onAdded: (ContentKind?) -> Unit) {
        if (mutable.value.importing) return
        cancelImport()
        mutable.update { it.copy(importing = true) }
        scope.launch {
            var staging: File? = null
            var kept = false
            try {
                val prompt = withContext(Dispatchers.IO) {
                    val name = SafePath.fileName(displayName(resolver, uri) ?: "file.jar")
                    val folder = File(File(container.layout.caches, "local-import"), System.nanoTime().toString())
                    folder.mkdirs()
                    staging = folder
                    val copy = File(folder, name)
                    val input = resolver.openInputStream(uri)
                        ?: throw IOException("файл не открылся — выбери его ещё раз")
                    input.use { source -> copy.outputStream().use { source.copyTo(it) } }
                    val candidate = container.localContent.identify(copy, profile)
                    if (candidate.rejection.isNotEmpty()) throw IOException(candidate.rejection)
                    LocalImportPrompt(
                        profileId = profile.id,
                        fileName = candidate.fileName,
                        kind = candidate.kind,
                        warnings = candidate.warnings,
                        candidate = candidate,
                        staging = folder,
                    )
                }
                if (prompt.warnings.isEmpty() && !prompt.candidate.needsConsent) {
                    addStaged(prompt, profile, consent = false)
                    onAdded(prompt.kind)
                } else {
                    kept = true
                    mutable.update { it.copy(importPrompt = prompt) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                notify(humanError(error, "Файл не добавился — выбери jar или zip"), true)
            } finally {
                if (!kept) staging?.deleteRecursively()
                mutable.update { it.copy(importing = false) }
            }
        }
    }

    fun confirmImport(profile: Profile, onAdded: (ContentKind?) -> Unit) {
        val prompt = mutable.value.importPrompt ?: return
        if (prompt.profileId != profile.id || mutable.value.importing) return
        mutable.update { it.copy(importing = true, importPrompt = null) }
        scope.launch {
            try {
                addStaged(prompt, profile, consent = true)
                onAdded(prompt.kind)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                notify(humanError(error, "Файл не добавился — повтори"), true)
            } finally {
                prompt.staging.deleteRecursively()
                mutable.update { it.copy(importing = false) }
            }
        }
    }

    fun cancelImport() {
        val prompt = mutable.value.importPrompt ?: return
        mutable.update { it.copy(importPrompt = null) }
        scope.launch(Dispatchers.IO) { prompt.staging.deleteRecursively() }
    }

    private suspend fun addStaged(prompt: LocalImportPrompt, profile: Profile, consent: Boolean) {
        withContext(Dispatchers.IO) {
            container.localContent.add(container.profiles.directory(profile), prompt.candidate, consent = consent)
        }
        notify("Файл добавлен: ${prompt.fileName}", false)
    }

    internal fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun imageExtension(resolver: ContentResolver, uri: Uri): String? {
        when (resolver.getType(uri)?.lowercase()) {
            "image/png" -> return "png"
            "image/jpeg", "image/jpg" -> return "jpg"
            "image/webp" -> return "webp"
        }
        val extension = displayName(resolver, uri)?.substringAfterLast('.', "")?.lowercase()
        return extension?.takeIf { it in COVER_EXTENSIONS }
    }

    private fun humanError(error: Throwable, fallback: String): String =
        error.message?.trim()?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() } ?: fallback

    private companion object {
        const val PING_WAIT_MS = 6_000L
        const val TOP_SERVERS = 10
        val COVER_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
