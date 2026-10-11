package net.millida.launcher

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.millida.launcher.core.hosting.HOSTING_SOURCE_FTB
import net.millida.launcher.core.hosting.HOSTING_SOURCE_MODRINTH
import net.millida.launcher.core.hosting.HOSTING_WORLD_NAME
import net.millida.launcher.core.hosting.HostingApiKey
import net.millida.launcher.core.hosting.HostingBackup
import net.millida.launcher.core.hosting.HostingCatalogHit
import net.millida.launcher.core.hosting.HostingClient
import net.millida.launcher.core.hosting.HostingCore
import net.millida.launcher.core.hosting.HostingError
import net.millida.launcher.core.hosting.HostingFileEntry
import net.millida.launcher.core.hosting.HostingInstall
import net.millida.launcher.core.hosting.HostingInstallUpdate
import net.millida.launcher.core.hosting.HostingJob
import net.millida.launcher.core.hosting.HostingNetwork
import net.millida.launcher.core.hosting.HostingPaths
import net.millida.launcher.core.hosting.HostingSftp
import net.millida.launcher.core.hosting.HostingShare
import net.millida.launcher.core.hosting.HostingToolAccess
import net.millida.launcher.core.hosting.HostingWorld
import net.millida.launcher.core.media.UiSoundEvent
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

data class HostingRights(val serverId: String, val access: HostingToolAccess)

data class HostingTransfer(
    val serverId: String,
    val title: String,
    val note: String = "",
    val done: Long = 0,
    val total: Long = 0,
) {
    val share: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

data class HostingFilesState(
    val serverId: String = "",
    val dir: String = "",
    val entries: List<HostingFileEntry>? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val extracting: HostingJob? = null,
)

data class HostingEditorState(
    val serverId: String,
    val path: String,
    val original: String = "",
    val draft: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val name: String get() = path.substringAfterLast('/')
    val dirty: Boolean get() = !loading && error == null && draft != original
}

data class HostingBackupsState(
    val serverId: String = "",
    val items: List<HostingBackup>? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val restoring: HostingJob? = null,
)

data class HostingWorldsState(
    val serverId: String = "",
    val items: List<HostingWorld>? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class HostingNetworkState(
    val serverId: String = "",
    val network: HostingNetwork? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val dnsHint: String = "",
)

data class HostingAccessState(
    val serverId: String = "",
    val shares: List<HostingShare>? = null,
    val keys: List<HostingApiKey>? = null,
    val sftp: HostingSftp? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val freshKey: String? = null,
    val sftpPassword: String? = null,
)

data class HostingScheduleDraft(
    val key: String,
    val raw: JsonObject,
    val kind: String,
    val enabled: Boolean,
    val minute: Int,
    val days: Int,
    val command: String,
    val everyMin: Int,
    val steps: Int,
    val lastRunAt: String,
)

data class HostingScheduleState(
    val serverId: String = "",
    val items: List<HostingScheduleDraft>? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val dirty: Boolean = false,
)

data class HostingContentState(
    val serverId: String = "",
    val installs: List<HostingInstall>? = null,
    val updates: List<HostingInstallUpdate> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class HostingCatalogState(
    val serverId: String = "",
    val source: String = HOSTING_SOURCE_MODRINTH,
    val kind: String = HOSTING_KIND_MODPACK,
    val query: String = "",
    val hits: List<HostingCatalogHit>? = null,
    val nextOffset: Int? = null,
    val searching: Boolean = false,
    val error: String? = null,
)

data class HostingCoresState(
    val cores: List<HostingCore>? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

const val HOSTING_KIND_MODPACK = "modpack"

class HostingToolsModel(
    private val client: HostingClient,
    private val scope: CoroutineScope,
    context: Context,
    private val notify: (String, Boolean) -> Unit,
    private val sound: (UiSoundEvent) -> Unit,
) {
    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val noticeLock = Any()
    private val staleWarned = HashSet<String>()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _rights = MutableStateFlow<HostingRights?>(null)
    val rights: StateFlow<HostingRights?> = _rights.asStateFlow()

    private val _transfer = MutableStateFlow<HostingTransfer?>(null)
    val transfer: StateFlow<HostingTransfer?> = _transfer.asStateFlow()

    private val _files = MutableStateFlow(HostingFilesState())
    val files: StateFlow<HostingFilesState> = _files.asStateFlow()

    private val _editor = MutableStateFlow<HostingEditorState?>(null)
    val editor: StateFlow<HostingEditorState?> = _editor.asStateFlow()

    private val _backups = MutableStateFlow(HostingBackupsState())
    val backups: StateFlow<HostingBackupsState> = _backups.asStateFlow()

    private val _worlds = MutableStateFlow(HostingWorldsState())
    val worlds: StateFlow<HostingWorldsState> = _worlds.asStateFlow()

    private val _network = MutableStateFlow(HostingNetworkState())
    val network: StateFlow<HostingNetworkState> = _network.asStateFlow()

    private val _access = MutableStateFlow(HostingAccessState())
    val access: StateFlow<HostingAccessState> = _access.asStateFlow()

    private val _schedule = MutableStateFlow(HostingScheduleState())
    val schedule: StateFlow<HostingScheduleState> = _schedule.asStateFlow()

    private val _content = MutableStateFlow(HostingContentState())
    val content: StateFlow<HostingContentState> = _content.asStateFlow()

    private val _catalog = MutableStateFlow(HostingCatalogState())
    val catalog: StateFlow<HostingCatalogState> = _catalog.asStateFlow()

    private val _cores = MutableStateFlow(HostingCoresState())
    val cores: StateFlow<HostingCoresState> = _cores.asStateFlow()

    private val _planAsks = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val planAsks: SharedFlow<String> = _planAsks.asSharedFlow()
    val planAskListeners: StateFlow<Int> = _planAsks.subscriptionCount

    private val rightsSeq = AtomicLong()
    private val filesSeq = AtomicLong()
    private val editorSeq = AtomicLong()
    private val backupsSeq = AtomicLong()
    private val worldsSeq = AtomicLong()
    private val networkSeq = AtomicLong()
    private val accessSeq = AtomicLong()
    private val scheduleSeq = AtomicLong()
    private val draftSeq = AtomicLong()
    private val contentSeq = AtomicLong()
    private val catalogSeq = AtomicLong()
    private val coresSeq = AtomicLong()

    private var transferJob: Job? = null
    private var backupWatch: Job? = null
    private var backupWatchServer: String? = null

    @Volatile
    private var transferStopped = false

    @Volatile
    private var lastReportAt = 0L

    @Volatile
    private var lastNoticeAt = 0L

    private var noticeLive = false

    fun key(serverId: String, action: String): String = "$serverId|$action"

    fun tell(text: String, isError: Boolean = false) = notify(text, isError)

    fun askForPlan(serverId: String) {
        _planAsks.tryEmit(serverId)
    }

    fun ensureRights(serverId: String, force: Boolean = false) {
        if (serverId.isBlank()) return
        if (!force && _rights.value?.serverId == serverId) return
        val ticket = rightsSeq.incrementAndGet()
        scope.launch {
            val result = outcome { client.toolAccess(serverId) }
            if (rightsSeq.get() != ticket) return@launch
            result.onSuccess { _rights.value = HostingRights(serverId, it) }
        }
    }

    private suspend fun rightsFor(serverId: String): HostingToolAccess? {
        _rights.value?.takeIf { it.serverId == serverId }?.let { return it.access }
        val fetched = outcome { client.toolAccess(serverId) }.getOrNull() ?: return null
        _rights.update { current -> if (current?.serverId == serverId) current else HostingRights(serverId, fetched) }
        return fetched
    }

    fun cancelTransfer() {
        transferStopped = true
        transferJob?.cancel()
    }

    private fun startTransfer(serverId: String, title: String, block: suspend () -> String?) {
        if (transferJob?.isActive == true) {
            notify("Дождись конца текущей передачи или отмени её", true)
            return
        }
        transferStopped = false
        _transfer.value = HostingTransfer(serverId, title)
        synchronized(noticeLock) { noticeLive = true }
        HostingTransferService.start(appContext, title)
        transferJob = scope.launch {
            try {
                val said = block()
                said?.let { notify(it, false) }
            } catch (cancelled: CancellationException) {
                if (transferStopped) notify("Передача остановлена", false)
                throw cancelled
            } catch (error: Throwable) {
                notify(readable(error), true)
            } finally {
                _transfer.value = null
                synchronized(noticeLock) {
                    noticeLive = false
                    HostingTransferService.stop(appContext)
                }
            }
        }
    }

    private fun report(title: String, note: String, done: Long, total: Long) {
        val now = SystemClock.uptimeMillis()
        val current = _transfer.value ?: return
        val sameStage = current.title == title && current.note == note
        if (sameStage && done < total && now - lastReportAt < REPORT_EVERY_MS) return
        lastReportAt = now
        _transfer.value = current.copy(title = title, note = note, done = done, total = total)
        if (!sameStage || now - lastNoticeAt >= NOTICE_EVERY_MS) {
            lastNoticeAt = now
            synchronized(noticeLock) {
                if (noticeLive) HostingTransferService.progress(appContext, title, done, total)
            }
        }
    }

    fun openFiles(serverId: String) {
        ensureRights(serverId)
        val current = _files.value
        if (current.serverId == serverId && (current.entries != null || current.loading)) return
        loadFiles(serverId, if (current.serverId == serverId) current.dir else "")
    }

    fun loadFiles(serverId: String, dir: String) {
        val ticket = filesSeq.incrementAndGet()
        _files.update {
            if (it.serverId == serverId) {
                it.copy(dir = dir, entries = if (it.dir == dir) it.entries else null, loading = true, error = null)
            } else {
                HostingFilesState(serverId = serverId, dir = dir, loading = true)
            }
        }
        scope.launch {
            val result = outcome { client.files(serverId, dir.ifEmpty { "." }) }
            if (filesSeq.get() != ticket) return@launch
            _files.update { state ->
                if (state.serverId != serverId || state.dir != dir) {
                    state
                } else {
                    result.fold(
                        onSuccess = { list -> state.copy(entries = list.sortedWith(FILE_ORDER), loading = false, error = null) },
                        onFailure = { error -> state.copy(loading = false, error = readable(error)) },
                    )
                }
            }
            if (dir.isEmpty()) warnStaleUploads(serverId, result.getOrNull().orEmpty())
        }
    }

    private fun warnStaleUploads(serverId: String, entries: List<HostingFileEntry>) {
        if (_transfer.value?.serverId == serverId) return
        val stale = entries.filter { !it.dir && it.name.startsWith(UPLOAD_TEMP_PREFIX) && "$serverId|${it.name}" !in staleWarned }
        if (stale.isEmpty()) return
        stale.forEach { staleWarned += "$serverId|${it.name}" }
        notify("В файлах остался архив прерванной загрузки «${stale.first().name}» — удали его, если мир уже в списке", true)
    }

    fun makeFolder(serverId: String, dir: String, name: String, onDone: () -> Unit = {}) =
        perform(serverId, "files:mkdir", onDone = onDone) {
            val clean = plainName(name, "Назови папку")
            client.makeDirectory(serverId, HostingPaths.join(dir.ifEmpty { "." }, clean))
            reloadFilesIf(serverId, dir)
            "Папка «$clean» создана"
        }

    fun renameEntry(serverId: String, dir: String, from: String, to: String, onDone: () -> Unit = {}) =
        perform(serverId, "files:rename:$from", onDone = onDone) {
            val next = plainName(to, "Впиши новое имя")
            if (next == from) return@perform null
            client.renameFile(serverId, HostingPaths.join(dir.ifEmpty { "." }, from), HostingPaths.join(dir.ifEmpty { "." }, next))
            reloadFilesIf(serverId, dir)
            "Переименовано в «$next»"
        }

    fun deleteEntry(serverId: String, dir: String, entry: HostingFileEntry) =
        perform(serverId, "files:delete:${entry.name}", UiSoundEvent.Delete) {
            client.deleteFile(serverId, HostingPaths.join(dir.ifEmpty { "." }, entry.name))
            _files.update { state ->
                if (state.serverId == serverId && state.dir == dir) {
                    state.copy(entries = state.entries?.filterNot { it.name == entry.name })
                } else {
                    state
                }
            }
            null
        }

    fun extractEntry(serverId: String, dir: String, entry: HostingFileEntry) =
        perform(serverId, "files:extract:${entry.name}") {
            client.extractFile(serverId, HostingPaths.join(dir.ifEmpty { "." }, entry.name), dir.ifEmpty { "." })
            _files.update { if (it.serverId == serverId) it.copy(extracting = HostingJob(state = "running", name = entry.name)) else it }
            try {
                awaitJob(
                    read = { client.extractJob(serverId) },
                    onTick = { job -> _files.update { if (it.serverId == serverId) it.copy(extracting = job) else it } },
                    silentEndMeansDone = true,
                    failedText = "Архив не распаковался",
                    unknownText = "Не удалось узнать, чем кончилась распаковка — проверь папку и обнови список",
                )
            } finally {
                _files.update { if (it.serverId == serverId) it.copy(extracting = null) else it }
            }
            reloadFilesIf(serverId, dir)
            "Архив «${entry.name}» распакован"
        }

    fun uploadFile(serverId: String, dir: String, uri: Uri) = startTransfer(serverId, "Загружаем файл") {
        val picked = withContext(Dispatchers.IO) { describe(uri) }
        val name = plainName(picked.name, "У файла нет имени — переименуй его и выбери снова")
        val target = HostingPaths.join(dir.ifEmpty { "." }, name)
        val title = "Загружаем $name"
        report(title, "", 0, picked.size)
        withContext(Dispatchers.IO) {
            openInput(uri).use { input ->
                client.uploadStream(serverId, target, input, picked.size) { sent, total -> report(title, "", sent, total) }
            }
        }
        reloadFilesIf(serverId, dir)
        "Загружено: $name"
    }

    fun downloadFile(serverId: String, dir: String, name: String, target: Uri) = startTransfer(serverId, "Скачиваем $name") {
        val path = HostingPaths.join(dir.ifEmpty { "." }, name)
        val title = "Скачиваем $name"
        writeDocument(target) { sink ->
            client.downloadFileTo(serverId, path, sink) { got, total -> report(title, "", got, total) }
        }
        "Скачано на телефон: $name"
    }

    fun openEditor(serverId: String, path: String) {
        val ticket = editorSeq.incrementAndGet()
        _editor.value = HostingEditorState(serverId = serverId, path = path)
        scope.launch {
            val result = outcome { client.readFile(serverId, path) }
            if (editorSeq.get() != ticket) return@launch
            _editor.update { state ->
                if (state == null || state.serverId != serverId || state.path != path) {
                    state
                } else {
                    result.fold(
                        onSuccess = { file -> state.copy(original = file.content, draft = file.content, loading = false, error = null) },
                        onFailure = { error -> state.copy(loading = false, error = readable(error)) },
                    )
                }
            }
        }
    }

    fun retryEditor() {
        val current = _editor.value ?: return
        openEditor(current.serverId, current.path)
    }

    fun editDraft(text: String) {
        _editor.update { it?.copy(draft = text) }
    }

    fun closeEditor() {
        editorSeq.incrementAndGet()
        _editor.value = null
    }

    fun saveEditor() {
        val current = _editor.value ?: return
        if (current.loading || current.saving || !current.dirty) return
        val serverId = current.serverId
        val path = current.path
        val content = current.draft
        _editor.update { state -> if (state != null && state.path == path) state.copy(saving = true) else state }
        perform(serverId, "files:save:$path") {
            try {
                client.writeFile(serverId, path, content)
                _editor.update { state ->
                    if (state != null && state.serverId == serverId && state.path == path) state.copy(original = content) else state
                }
            } finally {
                _editor.update { state -> if (state != null && state.path == path) state.copy(saving = false) else state }
            }
            "Сохранено — применится после перезапуска"
        }
    }

    fun openBackups(serverId: String) {
        ensureRights(serverId)
        val current = _backups.value
        if (current.serverId == serverId && (current.items != null || current.loading)) return
        loadBackups(serverId)
    }

    fun loadBackups(serverId: String) {
        val ticket = backupsSeq.incrementAndGet()
        _backups.update {
            if (it.serverId == serverId) it.copy(loading = true, error = null) else HostingBackupsState(serverId = serverId, loading = true)
        }
        scope.launch {
            val result = outcome { client.backups(serverId) }
            if (backupsSeq.get() != ticket) return@launch
            _backups.update { state ->
                if (state.serverId != serverId) {
                    state
                } else {
                    result.fold(
                        onSuccess = { list -> state.copy(items = list, loading = false, error = null) },
                        onFailure = { error -> state.copy(loading = false, error = readable(error)) },
                    )
                }
            }
            if (result.getOrNull().orEmpty().any { it.copyPending }) watchBackups(serverId)
        }
    }

    fun createBackup(serverId: String) = perform(serverId, "backups:create") {
        val result = client.createBackup(serverId)
        loadBackups(serverId)
        watchBackups(serverId)
        result.message
    }

    fun restoreBackup(serverId: String, backup: HostingBackup) = perform(serverId, "backups:restore") {
        client.restoreBackup(serverId, backup.id)
        _backups.update { if (it.serverId == serverId) it.copy(restoring = HostingJob(state = "running")) else it }
        val confirmed = try {
            awaitJob(
                read = { client.restoreJob(serverId) },
                onTick = { job -> _backups.update { if (it.serverId == serverId) it.copy(restoring = job) else it } },
                silentEndMeansDone = true,
                failedText = "Копия не развернулась",
                unknownText = "Не удалось узнать, чем кончилось восстановление — проверь мир и обнови список",
            )
        } finally {
            _backups.update { if (it.serverId == serverId) it.copy(restoring = null) else it }
        }
        if (confirmed) "Сервер восстановлен из копии" else "Восстановление больше не видно в задачах — проверь мир"
    }

    fun deleteBackup(serverId: String, backup: HostingBackup) =
        perform(serverId, "backups:delete:${backup.id}", UiSoundEvent.Delete) {
            client.deleteBackup(serverId, backup.id)
            _backups.update { state ->
                if (state.serverId == serverId) state.copy(items = state.items?.filterNot { it.id == backup.id }) else state
            }
            null
        }

    private fun watchBackups(serverId: String) {
        if (backupWatch?.isActive == true && backupWatchServer == serverId) return
        backupWatch?.cancel()
        backupWatchServer = serverId
        backupWatch = scope.launch {
            val deadline = SystemClock.uptimeMillis() + BACKUP_WATCH_MS
            while (SystemClock.uptimeMillis() < deadline) {
                delay(BACKUP_POLL_MS)
                if (_backups.value.serverId != serverId) return@launch
                val before = _backups.value.items.orEmpty()
                val list = outcome { client.backups(serverId) }.getOrNull() ?: continue
                val ticket = backupsSeq.incrementAndGet()
                if (_backups.value.serverId != serverId || backupsSeq.get() != ticket) return@launch
                _backups.update { it.copy(items = list, loading = false, error = null) }
                val waited = before.filter { it.copyPending }.map { it.id }.toSet()
                val finished = list.filter { it.id in waited && !it.copyPending }
                if (finished.any { it.copyFailed }) notify("Копия не собралась — создай её ещё раз", true)
                if (finished.any { !it.copyFailed }) notify("Копия готова", false)
                if (list.none { it.copyPending }) return@launch
            }
        }
    }

    fun openWorlds(serverId: String) {
        ensureRights(serverId, force = true)
        val current = _worlds.value
        if (current.serverId == serverId && (current.items != null || current.loading)) return
        loadWorlds(serverId)
    }

    fun loadWorlds(serverId: String) {
        val ticket = worldsSeq.incrementAndGet()
        _worlds.update {
            if (it.serverId == serverId) it.copy(loading = true, error = null) else HostingWorldsState(serverId = serverId, loading = true)
        }
        scope.launch {
            val result = outcome { client.worlds(serverId) }
            if (worldsSeq.get() != ticket) return@launch
            _worlds.update { state ->
                if (state.serverId != serverId) {
                    state
                } else {
                    result.fold(
                        onSuccess = { list -> state.copy(items = list, loading = false, error = null) },
                        onFailure = { error -> state.copy(loading = false, error = readable(error)) },
                    )
                }
            }
        }
    }

    fun switchWorld(serverId: String, world: HostingWorld) = perform(serverId, "worlds:switch:${world.name}") {
        val result = client.switchWorld(serverId, world.name)
        loadWorlds(serverId)
        if (rightsFor(serverId)?.running == false) "Активный мир: ${world.name}" else result.message
    }

    fun regenerateWorld(serverId: String, backup: Boolean) = perform(serverId, "worlds:regenerate") {
        val result = client.regenerateWorld(serverId, backup)
        loadWorlds(serverId)
        if (backup) loadBackups(serverId)
        if (result.detail.isEmpty()) result.message else "${result.message}, ${result.detail}"
    }

    fun uploadWorld(serverId: String, uri: Uri) = startTransfer(serverId, "Загружаем мир") {
        val picked = withContext(Dispatchers.IO) { describe(uri) }
        val lower = picked.name.lowercase()
        val extension = when {
            lower.endsWith(".zip") -> "zip"
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> "tar.gz"
            else -> throw HostingError("Нужен архив мира: .zip или .tar.gz")
        }
        val worldName = worldNameFrom(picked.name)
        if (client.toolAccess(serverId).running) {
            throw HostingError("Сначала останови сервер — он держит файлы мира открытыми")
        }
        val temp = "$UPLOAD_TEMP_PREFIX${System.currentTimeMillis()}.$extension"
        var reached = false
        var unpacking = false
        try {
            withContext(Dispatchers.IO) {
                openInput(uri).use { input ->
                    client.uploadStream(serverId, temp, input, picked.size) { sent, total ->
                        reached = true
                        report("Загружаем мир", worldName, sent, total)
                    }
                }
            }
            report("Распаковываем мир", worldName, 0, 0)
            val background = client.startWorldImport(serverId, temp, worldName)
            if (background) {
                unpacking = true
                try {
                    awaitJob(
                        read = { client.worldImportJob(serverId) },
                        onTick = { job -> report("Распаковываем мир", worldName, job.bytes, job.total) },
                        silentEndMeansDone = false,
                        failedText = "Мир не распаковался",
                        unknownText = "Не удалось узнать, распаковался ли мир — проверь список миров",
                    )
                } catch (failed: HostingJobFailed) {
                    unpacking = false
                    throw failed
                }
                unpacking = false
            }
            val active = !background || outcome { client.switchWorld(serverId, worldName) }.isSuccess
            loadWorlds(serverId)
            if (active) {
                "Мир «$worldName» загружен и стал активным"
            } else {
                notify("Мир «$worldName» загружен, но не стал активным — включи его в списке миров", true)
                null
            }
        } finally {
            if (reached && unpacking) {
                notify("Архив $temp остался в корне файлов сервера — удали его, когда мир появится в списке", true)
            } else if (reached) {
                val cleaned = withContext(NonCancellable) { outcome { client.deleteFile(serverId, temp) } }
                if (cleaned.isFailure) notify("Временный архив $temp остался в файлах сервера — удали его вручную", true)
            }
        }
    }

    fun downloadWorld(serverId: String, target: Uri) = startTransfer(serverId, "Скачиваем мир") {
        writeDocument(target) { sink ->
            client.downloadWorldTo(
                serverId,
                sink,
                onPacking = { report("Собираем архив мира", "", 0, 0) },
            ) { got, total -> report("Скачиваем мир", "", got, total) }
        }
        "Мир сохранён на телефон"
    }

    fun openNetwork(serverId: String) {
        ensureRights(serverId, force = true)
        val current = _network.value
        if (current.serverId == serverId && (current.network != null || current.loading)) return
        loadNetwork(serverId)
    }

    fun loadNetwork(serverId: String) {
        val ticket = networkSeq.incrementAndGet()
        _network.update {
            if (it.serverId == serverId) it.copy(loading = true, error = null) else HostingNetworkState(serverId = serverId, loading = true)
        }
        scope.launch {
            val result = outcome { client.network(serverId) }
            if (networkSeq.get() != ticket) return@launch
            _network.update { state ->
                if (state.serverId != serverId) {
                    state
                } else {
                    result.fold(
                        onSuccess = { value -> state.copy(network = value, loading = false, error = null) },
                        onFailure = { error -> state.copy(loading = false, error = readable(error)) },
                    )
                }
            }
        }
    }

    fun changeAddress(serverId: String, slug: String, onDone: () -> Unit = {}) = perform(serverId, "network:slug", onDone = onDone) {
        val clean = slug.trim().lowercase()
        if (clean.isEmpty()) throw HostingError("Впиши новый адрес")
        val result = client.changeAddress(serverId, clean)
        ensureRights(serverId, force = true)
        loadNetwork(serverId)
        result.message
    }

    fun setDomain(serverId: String, domain: String, onDone: () -> Unit = {}) = perform(serverId, "network:domain", onDone = onDone) {
        val result = client.setDomain(serverId, domain.trim().lowercase())
        _network.update { if (it.serverId == serverId) it.copy(dnsHint = result.detail) else it }
        ensureRights(serverId, force = true)
        result.message
    }

    fun addPort(serverId: String, note: String, onDone: () -> Unit = {}) = perform(serverId, "network:port", onDone = onDone) {
        val result = client.addPort(serverId, note.trim())
        loadNetwork(serverId)
        result.message
    }

    fun removePort(serverId: String, port: Int) = perform(serverId, "network:port:$port", UiSoundEvent.Delete) {
        client.removePort(serverId, port)
        _network.update { state ->
            val current = state.network
            if (state.serverId == serverId && current != null) {
                state.copy(network = current.copy(ports = current.ports.filterNot { it.port == port }))
            } else {
                state
            }
        }
        null
    }

    fun openAccess(serverId: String) {
        val current = _access.value
        if (current.serverId == serverId && (current.shares != null || current.sftp != null || current.loading)) return
        loadAccess(serverId)
    }

    fun loadAccess(serverId: String) {
        val ticket = accessSeq.incrementAndGet()
        _access.update {
            if (it.serverId == serverId) it.copy(loading = true, error = null) else HostingAccessState(serverId = serverId, loading = true)
        }
        scope.launch {
            val rights = rightsFor(serverId)
            val owner = rights?.owner ?: true
            val files = rights?.can("files") ?: true
            val shares = if (owner) outcome { client.shares(serverId) } else null
            val keys = if (owner) outcome { client.apiKeys(serverId) } else null
            val sftp = if (files) outcome { client.sftp(serverId) } else null
            if (accessSeq.get() != ticket) return@launch
            val failure = listOfNotNull(shares, keys, sftp).firstNotNullOfOrNull { it.exceptionOrNull() }
            _access.update { state ->
                if (state.serverId != serverId) {
                    state
                } else {
                    state.copy(
                        shares = shares?.getOrNull() ?: state.shares.takeIf { owner },
                        keys = keys?.getOrNull() ?: state.keys.takeIf { owner },
                        sftp = sftp?.getOrNull() ?: state.sftp.takeIf { files },
                        loading = false,
                        error = failure?.let { readable(it) },
                    )
                }
            }
        }
    }

    fun addShare(serverId: String, login: String, permissions: Set<String>, onDone: () -> Unit = {}) =
        perform(serverId, "access:share", onDone = onDone) {
            val clean = login.trim()
            if (clean.isEmpty()) throw HostingError("Впиши ник или почту аккаунта Millida")
            if (permissions.isEmpty()) throw HostingError("Отметь хотя бы одно право")
            val list = client.addShare(serverId, clean, orderedPermissions(permissions))
            _access.update { if (it.serverId == serverId) it.copy(shares = list) else it }
            "Доступ выдан: $clean"
        }

    fun updateShare(serverId: String, share: HostingShare, permissions: Set<String>, onDone: () -> Unit = {}) =
        perform(serverId, "access:share:${share.userId}", onDone = onDone) {
            if (permissions.isEmpty()) throw HostingError("Оставь хотя бы одно право или забери доступ целиком")
            val list = client.updateShare(serverId, share.userId, orderedPermissions(permissions))
            _access.update { if (it.serverId == serverId) it.copy(shares = list) else it }
            null
        }

    fun removeShare(serverId: String, share: HostingShare, onDone: () -> Unit = {}) =
        perform(serverId, "access:share:${share.userId}", UiSoundEvent.Delete, onDone) {
            client.removeShare(serverId, share.userId)
            _access.update { state ->
                if (state.serverId == serverId) state.copy(shares = state.shares?.filterNot { it.userId == share.userId }) else state
            }
            null
        }

    fun createApiKey(serverId: String, name: String, permissions: Set<String>, onDone: () -> Unit = {}) =
        perform(serverId, "access:key", onDone = onDone) {
            val clean = name.trim()
            if (clean.isEmpty()) throw HostingError("Дай ключу имя, например «бот Discord»")
            if (permissions.isEmpty()) throw HostingError("Отметь хотя бы одно право")
            val issued = client.createApiKey(serverId, clean, orderedPermissions(permissions))
            if (issued.token.isBlank()) throw HostingError("Ключ создан, но не пришёл — отзови его и создай заново")
            _access.update { if (it.serverId == serverId) it.copy(freshKey = issued.token) else it }
            val list = outcome { client.apiKeys(serverId) }
            list.onSuccess { keys -> _access.update { if (it.serverId == serverId) it.copy(keys = keys) else it } }
            null
        }

    fun revokeApiKey(serverId: String, key: HostingApiKey) = perform(serverId, "access:key:${key.id}", UiSoundEvent.Delete) {
        client.revokeApiKey(serverId, key.id)
        _access.update { state ->
            if (state.serverId == serverId) state.copy(keys = state.keys?.filterNot { it.id == key.id }) else state
        }
        null
    }

    fun issueSftp(serverId: String) = perform(serverId, "access:sftp") {
        val issued = client.issueSftp(serverId)
        if (issued.password.isBlank()) throw HostingError("Доступ выдан, но пароль не пришёл — нажми «Новый пароль»")
        _access.update { if (it.serverId == serverId) it.copy(sftp = issued.copy(password = ""), sftpPassword = issued.password) else it }
        null
    }

    fun revokeSftp(serverId: String) = perform(serverId, "access:sftp", UiSoundEvent.Delete) {
        client.revokeSftp(serverId)
        _access.update { state ->
            if (state.serverId == serverId) state.copy(sftp = state.sftp?.copy(active = false), sftpPassword = null) else state
        }
        null
    }

    fun dismissSecret() {
        _access.update { it.copy(freshKey = null, sftpPassword = null) }
    }

    fun openSchedule(serverId: String) {
        ensureRights(serverId)
        val current = _schedule.value
        if (current.serverId == serverId && (current.items != null || current.loading)) return
        loadSchedule(serverId)
    }

    fun loadSchedule(serverId: String) {
        val ticket = scheduleSeq.incrementAndGet()
        _schedule.update {
            if (it.serverId == serverId) it.copy(loading = true, error = null) else HostingScheduleState(serverId = serverId, loading = true)
        }
        scope.launch {
            val result = outcome { client.scheduleEntries(serverId) }
            if (scheduleSeq.get() != ticket) return@launch
            _schedule.update { state ->
                if (state.serverId != serverId) {
                    state
                } else {
                    result.fold(
                        onSuccess = { list -> state.copy(items = list.map { draftOf(it) }, loading = false, error = null, dirty = false) },
                        onFailure = { error -> state.copy(loading = false, error = readable(error)) },
                    )
                }
            }
        }
    }

    fun addSchedule(serverId: String): String? {
        val current = _schedule.value
        if (current.serverId != serverId || current.items == null) return null
        if (current.items.size >= MAX_SCHEDULES) {
            notify("Не больше $MAX_SCHEDULES задач на сервер — убери лишнюю", true)
            return null
        }
        val draft = HostingScheduleDraft(
            key = "new-${draftSeq.incrementAndGet()}",
            raw = JsonObject(emptyMap()),
            kind = "restart",
            enabled = true,
            minute = DEFAULT_MINUTE,
            days = ALL_DAYS,
            command = "",
            everyMin = 0,
            steps = 0,
            lastRunAt = "",
        )
        _schedule.update { it.copy(items = it.items.orEmpty() + draft, dirty = true) }
        return draft.key
    }

    fun editSchedule(serverId: String, key: String, change: (HostingScheduleDraft) -> HostingScheduleDraft) {
        _schedule.update { state ->
            if (state.serverId != serverId) {
                state
            } else {
                state.copy(items = state.items?.map { if (it.key == key) change(it) else it }, dirty = true)
            }
        }
    }

    fun removeSchedule(serverId: String, key: String) {
        _schedule.update { state ->
            if (state.serverId != serverId) state else state.copy(items = state.items?.filterNot { it.key == key }, dirty = true)
        }
    }

    fun saveSchedule(serverId: String) = perform(serverId, "schedule:save") {
        val drafts = _schedule.value.takeIf { it.serverId == serverId }?.items ?: return@perform null
        drafts.forEach { draft ->
            if ((draft.days and ALL_DAYS) == 0) throw HostingError("Выбери хотя бы один день недели")
            if (draft.kind == "command" && draft.steps == 0 && draft.command.isBlank()) {
                throw HostingError("Впиши команду для задачи «Команда»")
            }
        }
        val saved = client.replaceScheduleEntries(serverId, drafts.map { entryOf(it) })
        scheduleSeq.incrementAndGet()
        _schedule.update { state ->
            if (state.serverId == serverId) {
                state.copy(items = saved.map { draftOf(it) }, dirty = false, loading = false, error = null)
            } else {
                state
            }
        }
        "Расписание сохранено"
    }

    fun openContent(serverId: String) {
        ensureRights(serverId)
        val current = _content.value
        if (current.serverId == serverId && (current.installs != null || current.loading)) return
        loadContent(serverId)
    }

    fun loadContent(serverId: String) {
        val ticket = contentSeq.incrementAndGet()
        _content.update {
            if (it.serverId == serverId) it.copy(loading = true, error = null) else HostingContentState(serverId = serverId, loading = true)
        }
        scope.launch {
            val result = outcome { client.installs(serverId) }
            if (contentSeq.get() != ticket) return@launch
            _content.update { state ->
                if (state.serverId != serverId) {
                    state
                } else {
                    result.fold(
                        onSuccess = { list -> state.copy(installs = list, loading = false, error = null) },
                        onFailure = { error -> state.copy(loading = false, error = readable(error)) },
                    )
                }
            }
            if (result.isFailure) return@launch
            // Update checks ask Modrinth per install; a silent source must not hide the list itself.
            val updates = outcome { client.installUpdates(serverId) }.getOrNull() ?: return@launch
            if (contentSeq.get() != ticket) return@launch
            _content.update { state ->
                if (state.serverId == serverId) state.copy(updates = updates.filter { it.updateAvailable }) else state
            }
        }
    }

    fun removeInstall(serverId: String, install: HostingInstall) =
        perform(serverId, "content:remove:${install.id}", UiSoundEvent.Delete) {
            client.removeInstall(serverId, install.id)
            _content.update { state ->
                if (state.serverId == serverId) {
                    state.copy(
                        installs = state.installs?.filterNot { it.id == install.id },
                        updates = state.updates.filterNot { it.installId == install.id },
                    )
                } else {
                    state
                }
            }
            "«${install.name}» убран — применится после перезапуска"
        }

    fun applyUpdate(serverId: String, update: HostingInstallUpdate) {
        val install = _content.value.takeIf { it.serverId == serverId }?.installs?.firstOrNull { it.id == update.installId }
        if (install == null) {
            notify("Этого больше нет в списке — обнови раздел", true)
            return
        }
        perform(serverId, "content:update:${update.installId}", UiSoundEvent.Install) {
            client.installFromCatalog(
                serverId,
                install.projectId,
                install.source.ifEmpty { HOSTING_SOURCE_MODRINTH },
                update.latestVersionId.ifEmpty { null },
            )
            loadContent(serverId)
            "«${install.name}» обновится при следующем запуске"
        }
    }

    fun installHit(serverId: String, source: String, hit: HostingCatalogHit, onDone: () -> Unit = {}) =
        perform(serverId, "content:install:$source:${hit.id}", UiSoundEvent.Install, onDone) {
            client.installFromCatalog(serverId, hit.id, source)
            loadContent(serverId)
            "«${hit.name}» встанет при следующем запуске"
        }

    fun openCatalog(serverId: String, version: String) {
        val current = _catalog.value
        if (current.serverId == serverId && (current.hits != null || current.searching || current.error != null)) return
        if (current.serverId != serverId) _catalog.value = HostingCatalogState(serverId = serverId)
        searchCatalog(serverId, version)
    }

    fun setCatalogQuery(serverId: String, query: String) {
        _catalog.update { if (it.serverId == serverId) it.copy(query = query) else HostingCatalogState(serverId = serverId, query = query) }
    }

    fun setCatalogFilter(serverId: String, version: String, source: String? = null, kind: String? = null) {
        _catalog.update { state ->
            val base = if (state.serverId == serverId) state else HostingCatalogState(serverId = serverId)
            val nextKind = kind ?: base.kind
            val wanted = source ?: base.source
            val nextSource = if (wanted == HOSTING_SOURCE_FTB && nextKind != HOSTING_KIND_MODPACK) HOSTING_SOURCE_MODRINTH else wanted
            base.copy(kind = nextKind, source = nextSource)
        }
        searchCatalog(serverId, version)
    }

    fun searchCatalog(serverId: String, version: String, more: Boolean = false) {
        val current = _catalog.value.takeIf { it.serverId == serverId } ?: HostingCatalogState(serverId = serverId)
        if (more && current.searching) return
        val offset = if (more) current.nextOffset ?: return else 0
        val ticket = catalogSeq.incrementAndGet()
        _catalog.value = current.copy(
            searching = true,
            error = null,
            hits = if (more) current.hits else null,
            nextOffset = if (more) current.nextOffset else null,
        )
        scope.launch {
            val result = outcome { client.searchContent(current.source, current.kind, current.query, version, offset) }
            if (catalogSeq.get() != ticket) return@launch
            _catalog.update { state ->
                if (state.serverId != serverId) {
                    state
                } else {
                    result.fold(
                        onSuccess = { page ->
                            val merged = if (more) state.hits.orEmpty() + page.hits else page.hits
                            state.copy(hits = merged.distinctBy { it.id }, nextOffset = page.nextOffset, searching = false, error = null)
                        },
                        onFailure = { error ->
                            if (more) state.copy(searching = false) else state.copy(searching = false, error = readable(error))
                        },
                    )
                }
            }
            if (more) result.exceptionOrNull()?.let { notify(readable(it), true) }
        }
    }

    fun loadCores(force: Boolean = false) {
        val current = _cores.value
        if (!force && (current.cores != null || current.loading)) return
        val ticket = coresSeq.incrementAndGet()
        _cores.update { it.copy(loading = true, error = null) }
        scope.launch {
            val result = outcome { client.cores() }
            if (coresSeq.get() != ticket) return@launch
            _cores.update { state ->
                result.fold(
                    onSuccess = { list -> state.copy(cores = list, loading = false, error = null) },
                    onFailure = { error -> state.copy(loading = false, error = readable(error)) },
                )
            }
        }
    }

    fun changeCore(serverId: String, core: HostingCore, version: String, running: Boolean, onDone: () -> Unit = {}) =
        perform(serverId, "content:core", UiSoundEvent.Install, onDone) {
            client.changeCore(serverId, core.id, version)
            loadContent(serverId)
            "Ядро сервера: ${core.name} $version" + if (running) " — сервер перезапустится" else ""
        }

    private fun perform(
        serverId: String,
        action: String,
        event: UiSoundEvent? = null,
        onDone: () -> Unit = {},
        block: suspend () -> String?,
    ): Job {
        val slot = key(serverId, action)
        return scope.launch {
            if (!claim(slot)) return@launch
            try {
                val said = block()
                event?.let(sound)
                said?.let { notify(it, false) }
                onDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                notify(readable(error), true)
            } finally {
                _busy.update { it - slot }
            }
        }
    }

    private fun claim(slot: String): Boolean {
        var claimed = false
        _busy.update { current ->
            if (slot in current) {
                claimed = false
                current
            } else {
                claimed = true
                current + slot
            }
        }
        return claimed
    }

    // The node reports a task it no longer tracks, or a node error, as "none", so one such read decides nothing.
    private suspend fun awaitJob(
        read: suspend () -> HostingJob,
        onTick: (HostingJob) -> Unit,
        silentEndMeansDone: Boolean,
        failedText: String,
        unknownText: String,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + JOB_WAIT_MS
        var seenRunning = false
        var misses = 0
        var silent = 0
        while (SystemClock.uptimeMillis() < deadline) {
            delay(JOB_POLL_MS)
            val job = try {
                read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                misses += 1
                if (misses >= JOB_MISSES) throw error
                continue
            }
            misses = 0
            when {
                job.running -> {
                    silent = 0
                    seenRunning = true
                    onTick(job)
                }
                job.failed -> throw HostingJobFailed(job.error.ifBlank { failedText })
                job.finished -> {
                    onTick(job)
                    return true
                }
                else -> {
                    silent += 1
                    if (silent < JOB_NONE_LIMIT) continue
                    if (seenRunning || !silentEndMeansDone) throw HostingError(unknownText)
                    return false
                }
            }
        }
        throw HostingError("Задача на сервере идёт слишком долго — проверь результат позже")
    }

    private suspend fun writeDocument(target: Uri, block: suspend (OutputStream) -> Unit) {
        try {
            withContext(Dispatchers.IO) {
                val stream = resolver.openOutputStream(target, "w")
                    ?: throw HostingError("Файл на телефоне не открылся для записи — выбери другое место")
                stream.use { block(it) }
            }
        } catch (error: Throwable) {
            val removed = withContext(NonCancellable + Dispatchers.IO) {
                try {
                    DocumentsContract.deleteDocument(resolver, target)
                } catch (refused: Exception) {
                    false
                }
            }
            if (error is CancellationException || removed) throw error
            throw HostingError("${readable(error)}. Неполный файл остался на телефоне — удали его", error)
        }
    }

    private fun openInput(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw HostingError("Файл не открылся — выбери его заново")

    private fun describe(uri: Uri): PickedFile {
        var name = ""
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn).orEmpty()
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        if (size < 0) {
            size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }
        if (size < 0) throw HostingError("Не удалось узнать размер файла — выбери его через «Файлы»")
        if (size == 0L) throw HostingError("Файл пустой — загружать нечего")
        return PickedFile(name, size)
    }

    private fun reloadFilesIf(serverId: String, dir: String) {
        val current = _files.value
        if (current.serverId == serverId && current.dir == dir) loadFiles(serverId, dir)
    }

    private fun draftOf(entry: JsonObject): HostingScheduleDraft {
        val id = entry.text("id")
        return HostingScheduleDraft(
            key = id.ifEmpty { "saved-${draftSeq.incrementAndGet()}" },
            raw = entry,
            kind = entry.text("kind").ifEmpty { "restart" },
            enabled = entry.text("enabled") != "false",
            minute = entry.whole("minute").coerceIn(0, LAST_MINUTE),
            days = entry.whole("days").let { if (it in 1..ALL_DAYS) it else ALL_DAYS },
            command = entry.text("command"),
            everyMin = entry.whole("everyMin"),
            steps = (entry["steps"] as? JsonArray)?.size ?: 0,
            lastRunAt = entry.text("lastRunAt"),
        )
    }

    private fun entryOf(draft: HostingScheduleDraft): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>(draft.raw)
        fields["kind"] = JsonPrimitive(draft.kind)
        fields["enabled"] = JsonPrimitive(draft.enabled)
        fields["minute"] = JsonPrimitive(draft.minute)
        fields["days"] = JsonPrimitive(draft.days)
        if (draft.kind == "command") fields["command"] = JsonPrimitive(draft.command.trim()) else fields.remove("command")
        return JsonObject(fields)
    }

    private data class PickedFile(val name: String, val size: Long)

    private companion object {
        const val REPORT_EVERY_MS = 150L
        const val NOTICE_EVERY_MS = 1_000L
        const val UPLOAD_TEMP_PREFIX = ".millida-upload-"
        const val JOB_POLL_MS = 2_000L
        const val JOB_MISSES = 5
        const val JOB_NONE_LIMIT = 3
        const val JOB_WAIT_MS = 2 * 60 * 60 * 1000L
        const val BACKUP_POLL_MS = 4_000L
        const val BACKUP_WATCH_MS = 30 * 60 * 1000L
        const val MAX_SCHEDULES = 10
        const val ALL_DAYS = 127
        const val DEFAULT_MINUTE = 5 * 60
        const val LAST_MINUTE = 24 * 60 - 1
        const val NAME_MAX = 120

        val FILE_ORDER: Comparator<HostingFileEntry> =
            compareByDescending<HostingFileEntry> { it.dir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

        suspend fun <T> outcome(block: suspend () -> T): Result<T> = try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }

        fun readable(error: Throwable): String =
            error.message?.trim()?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
                ?: "Не получилось — повтори"

        // A typed or picked name must stay one path segment so it cannot walk out of the folder.
        fun plainName(raw: String, empty: String): String {
            val clean = raw.trim()
            if (clean.isEmpty()) throw HostingError(empty)
            if (clean == "." || clean == ".." || clean.contains('/') || clean.contains('\\')) {
                throw HostingError("Имя «$clean» не подходит — без «/» и точек вместо имени")
            }
            if (clean.length > NAME_MAX) throw HostingError("Имя длиннее $NAME_MAX знаков — сократи его")
            return clean
        }

        fun worldNameFrom(fileName: String): String {
            val base = fileName.replace(Regex("""\.(zip|tar\.gz|tgz|gz)$""", RegexOption.IGNORE_CASE), "")
            val clean = base.replace(Regex("""[^A-Za-z0-9._-]"""), "-").take(40)
            return clean.takeIf { HOSTING_WORLD_NAME.matches(it) } ?: "world-${System.currentTimeMillis() % 10_000}"
        }

        fun orderedPermissions(selected: Set<String>): List<String> {
            val known = net.millida.launcher.core.hosting.HOSTING_PERMISSIONS.map { it.first }
            return known.filter { it in selected } + selected.filterNot { it in known }
        }

        fun JsonObject.text(field: String): String {
            val value = this[field]
            if (value == null || value is JsonNull) return ""
            return (value as? JsonPrimitive)?.content.orEmpty()
        }

        fun JsonObject.whole(field: String): Int = text(field).toDoubleOrNull()?.toInt() ?: 0
    }
}

private class HostingJobFailed(message: String) : IOException(message)

val HostingBackup.copyPending: Boolean get() = status.equals("pending", ignoreCase = true)

val HostingBackup.copyFailed: Boolean get() = status.equals("failed", ignoreCase = true)

fun HostingRights?.forServer(serverId: String): HostingToolAccess? = this?.takeIf { it.serverId == serverId }?.access
