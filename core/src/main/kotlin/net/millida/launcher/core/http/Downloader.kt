package net.millida.launcher.core.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.millida.launcher.core.Hashing
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class RemoteFile(
    val url: String,
    val target: File,
    val sha1: String? = null,
    val size: Long = 0,
    val executable: Boolean = false,
)

data class TransferProgress(
    val doneFiles: Int,
    val totalFiles: Int,
    val doneBytes: Long,
    val totalBytes: Long,
    val current: String,
)

class HttpError(val code: Int, val url: String) :
    IOException("сервер ответил $code на $url")

class Downloader(
    private val client: OkHttpClient = shared(),
    private val concurrency: Int = 6,
    private val attempts: Int = 4,
    private val allowedHosts: Set<String>? = null,
) {
    suspend fun text(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            requireWeb(url)
            retry(url) {
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw HttpError(response.code, url)
                    response.body?.string() ?: throw IOException("пустой ответ от $url")
                }
            }
        }

    /** The bytes themselves, for what has to be read and not saved: a texture. */
    suspend fun bytes(url: String, limit: Int = 2 * 1024 * 1024): ByteArray =
        withContext(Dispatchers.IO) {
            requireWeb(url)
            retry(url) {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw HttpError(response.code, url)
                    val body = response.body ?: throw IOException("пустой ответ от $url")
                    val data = body.byteStream().readBytes()
                    if (data.size > limit) throw IOException("файл слишком большой: ${data.size / 1024} КБ")
                    data
                }
            }
        }

    suspend fun fetchAll(
        files: List<RemoteFile>,
        onProgress: (TransferProgress) -> Unit = {},
    ) = coroutineScope {
        val pending = files.filterNot { isReady(it) }
        val totalBytes = pending.sumOf { if (it.size > 0) it.size else 0L }
        val doneBytes = AtomicLong(0)
        val doneFiles = AtomicLong(0)
        val gate = Semaphore(concurrency)

        if (pending.isEmpty()) {
            onProgress(TransferProgress(files.size, files.size, totalBytes, totalBytes, ""))
            return@coroutineScope
        }

        pending.forEach { file ->
            launch(Dispatchers.IO) {
                gate.withPermit {
                    var counted = 0L
                    fetch(file) { chunk ->
                        // a repeated attempt restarts the file, so the shared
                        // counter is corrected instead of double-counting
                        if (chunk < 0) {
                            doneBytes.addAndGet(-counted)
                            counted = 0
                        } else {
                            counted += chunk
                            doneBytes.addAndGet(chunk)
                        }
                        report(onProgress, doneFiles.get(), pending.size, doneBytes.get(), totalBytes, file)
                    }
                    val finished = doneFiles.incrementAndGet()
                    report(onProgress, finished, pending.size, doneBytes.get(), totalBytes, file)
                }
            }
        }
    }

    suspend fun fetch(file: RemoteFile, onChunk: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        lockFor(file.target).withLock {
            if (isReady(file)) return@withLock
            requireWeb(file.url)
            checkHost(file.url)
            file.target.parentFile?.mkdirs()
            val temp = File(file.target.parentFile, file.target.name + ".part")
            if (temp.exists()) temp.delete()

            var firstAttempt = true
            try {
                retry(file.url, progressed = { if (temp.isFile) temp.length() else 0L }) {
                    val have = if (temp.isFile) temp.length() else 0L
                    if (have == 0L && !firstAttempt) onChunk(-1)
                    firstAttempt = false
                    val request = Request.Builder().url(file.url).apply {
                        if (have > 0) header("Range", "bytes=$have-")
                    }.build()
                    client.newCall(request).execute().use { response ->
                        val resumed = have > 0 && response.code == 206 && rangeStart(response.header("Content-Range")) == have
                        if (have > 0 && !resumed) {
                            onChunk(-1)
                            temp.delete()
                            // A 206 from another offset or a 416 cannot be appended; the next attempt starts over.
                            if (response.code == 206 || response.code == 416) throw IOException("докачка не совпала с началом — качаем заново ${file.url}")
                        }
                        if (!response.isSuccessful) throw HttpError(response.code, file.url)
                        val body = response.body ?: throw IOException("пустой ответ от ${file.url}")
                        FileOutputStream(temp, resumed).use { out ->
                            val buffer = ByteArray(1 shl 16)
                            body.byteStream().use { input ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    out.write(buffer, 0, read)
                                    onChunk(read.toLong())
                                }
                            }
                        }
                    }
                    promote(file, temp)
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }

    /**
     * Addresses come from version descriptions and catalogues: a `file://` or a
     * bare path there would copy a file of the phone into the game folder.
     */
    private fun requireWeb(url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw IOException("адрес «${url.substringBefore('?').take(80)}» не из сети — такой файл не скачивается")
        }
    }

    private fun promote(file: RemoteFile, temp: File) {
        if (file.sha1 != null && !Hashing.matches(temp, file.sha1)) {
            temp.delete()
            throw IOException("хеш не совпал: ${file.target.name}")
        }
        if (file.target.exists()) file.target.delete()
        if (!temp.renameTo(file.target)) {
            temp.copyTo(file.target, overwrite = true)
            temp.delete()
        }
        if (file.executable) file.target.setExecutable(true, true)
    }

    private fun isReady(file: RemoteFile): Boolean = when {
        file.sha1 != null -> Hashing.matches(file.target, file.sha1)
        // meta APIs of the loaders hand out libraries without a hash; a file of
        // the declared size is treated as installed instead of re-downloading
        // tens of megabytes on every launch
        file.size > 0 -> file.target.isFile && file.target.length() == file.size
        else -> file.target.isFile && file.target.length() > 0
    }

    private fun checkHost(url: String) {
        val hosts = allowedHosts ?: return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val host = url.toHttpHostOrNull() ?: throw IOException("непонятный адрес: $url")
        if (host !in hosts) throw IOException("адрес $host не в списке разрешённых")
    }

    private fun String.toHttpHostOrNull(): String? =
        runCatching { java.net.URI(this).host?.lowercase() }.getOrNull()

    private fun report(
        onProgress: (TransferProgress) -> Unit,
        doneFiles: Long,
        totalFiles: Int,
        doneBytes: Long,
        totalBytes: Long,
        file: RemoteFile,
    ) {
        onProgress(
            TransferProgress(
                doneFiles.toInt(),
                totalFiles,
                doneBytes.coerceAtLeast(0),
                totalBytes,
                file.target.name,
            )
        )
    }

    /**
     * An attempt that moved [progressed] forward does not spend one of [attempts]: on a slow link
     * a big archive breaks off many times, and every break used to restart it from the first byte
     * until the attempts ran out (a 981 MB pack never finished, 08.10.2026).
     */
    private suspend fun <T> retry(url: String, progressed: () -> Long = { 0L }, block: suspend () -> T): T {
        var lastError: Throwable? = null
        var failed = 0
        var resumes = 0
        while (true) {
            val before = progressed()
            try {
                return block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: HttpError) {
                if (error.code in 400..499 && error.code != 408 && error.code != 429) throw error
                lastError = error
            } catch (error: IOException) {
                lastError = error
            }
            if (progressed() > before && resumes < MAX_RESUMES) {
                resumes++
                failed = 0
                delay(400L)
                continue
            }
            failed++
            if (failed >= attempts) break
            delay(400L * (1L shl (failed - 1)))
        }
        // Cause first: telemetry keeps the first 120 characters, and a long address used to push the reason out.
        throw IOException("${lastError?.message ?: "нет ответа"} — не удалось скачать ${url.substringBefore('?')}", lastError)
    }

    private fun lockFor(target: File): Mutex =
        LOCKS.getOrPut(target.absolutePath) { Mutex() }

    private fun rangeStart(contentRange: String?): Long? =
        contentRange?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-')?.toLongOrNull()

    companion object {
        private const val MAX_RESUMES = 50
        private val LOCKS = ConcurrentHashMap<String, Mutex>()

        @Volatile
        private var sharedClient: OkHttpClient? = null

        /** One client per application: a client per screen leaks sockets and threads. */
        fun shared(): OkHttpClient = sharedClient ?: synchronized(this) {
            sharedClient ?: defaultClient().also { sharedClient = it }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(DohDns.shared())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private fun <K, V> ConcurrentHashMap<K, V>.getOrPut(key: K, produce: () -> V): V =
    this[key] ?: produce().let { created -> putIfAbsent(key, created) ?: created }
