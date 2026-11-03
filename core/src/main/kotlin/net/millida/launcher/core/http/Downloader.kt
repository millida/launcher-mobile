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
import net.millida.launcher.core.i18n.tr
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
    IOException(tr("сервер ответил {code} на {url}", code, url))

/**
 * Our mirror of game files (millida-services, file-mirror.service.ts): the same bytes of Mojang
 * and the loaders, asked from api.millida.net. The list is the mirror's own list of hosts;
 * anything else it refuses, so nothing else is sent there.
 */
object FileMirror {
    private const val BASE = "https://api.millida.net/v2/launcher/dl"

    val HOSTS: Set<String> = setOf(
        "launchermeta.mojang.com",
        "piston-meta.mojang.com",
        "piston-data.mojang.com",
        "launcher.mojang.com",
        "libraries.minecraft.net",
        "resources.download.minecraft.net",
        "meta.fabricmc.net",
        "maven.fabricmc.net",
        "meta.quiltmc.org",
        "maven.quiltmc.org",
        "files.minecraftforge.net",
        "maven.minecraftforge.net",
        "maven.neoforged.net",
    )

    /** The mirror address of [url], or null when the mirror does not serve its host. */
    fun of(url: String): String? {
        if (!url.startsWith("https://") || url.startsWith(BASE)) return null
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return null
        if (host !in HOSTS) return null
        return BASE + "?url=" + java.net.URLEncoder.encode(url, "UTF-8")
    }
}

class Downloader(
    private val client: OkHttpClient = shared(),
    private val concurrency: Int = 6,
    private val attempts: Int = 5,
    private val allowedHosts: Set<String>? = null,
    private val mirrorOf: (String) -> String? = FileMirror::of,
) {
    /** Hosts that answered only through the mirror: the next files skip the dead address. */
    private val mirroredHosts = ConcurrentHashMap<String, Long>()

    suspend fun text(url: String, headers: Map<String, String> = emptyMap()): String =
        viaMirror(url) { address -> textDirect(address, headers) }

    private suspend fun textDirect(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            requireWeb(url)
            retry(url) {
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw HttpError(response.code, url)
                    response.body?.string() ?: throw IOException(tr("пустой ответ от {url}", url))
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
                    val body = response.body ?: throw IOException(tr("пустой ответ от {url}", url))
                    val data = body.byteStream().readBytes()
                    if (data.size > limit) throw IOException(tr("файл слишком большой: {0} КБ", data.size / 1024))
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

    suspend fun fetch(file: RemoteFile, onChunk: (Long) -> Unit = {}) {
        viaMirror(file.url) { address -> fetchDirect(if (address == file.url) file else file.copy(url = address), onChunk) }
    }

    /**
     * Game files of Mojang and the loaders through our mirror when their own address does not
     * answer. On 13.10.2026 «Не удалось определить адрес resources.download.minecraft.net» and its
     * kin kept 70 phones a day from installing the game at all, while the desktop launcher has
     * taken the same files through the mirror for months. A refusal of the file itself (404, 403)
     * is not a reason to ask the mirror: it would answer the same. The first error is the one
     * reported, and a host that needed the mirror is asked through it for the next ten minutes,
     * so thousands of assets do not each wait out the dead address.
     */
    private suspend fun <T> viaMirror(url: String, direct: suspend (String) -> T): T {
        val host = hostOf(url)
        val remembered = host?.let { mirroredHosts[it] }?.takeIf { it > System.currentTimeMillis() }
        val first = if (remembered != null) mirrorOf(url) ?: url else url
        try {
            return direct(first)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            if (error is HttpError && error.code in 400..499 && error.code != 408 && error.code != 429) throw error
            val other = (if (first == url) mirrorOf(url) else url) ?: throw error
            val result = try {
                direct(other)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw error
            }
            if (host != null) {
                if (first == url) mirroredHosts[host] = System.currentTimeMillis() + MIRROR_MEMORY_MS else mirroredHosts.remove(host)
            }
            return result
        }
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()

    private suspend fun fetchDirect(file: RemoteFile, onChunk: (Long) -> Unit) = withContext(Dispatchers.IO) {
        lockFor(file.target).withLock {
            if (isReady(file)) return@withLock
            requireWeb(file.url)
            checkHost(file.url)
            file.target.parentFile?.mkdirs()
            val temp = File(file.target.parentFile, file.target.name + ".part")
            // A part left by a broken-off try is continued, when the file's size or hash tells it from a stranger.
            val resumable = file.sha1 != null || file.size > 0
            if (temp.exists() && (!resumable || (file.size > 0 && temp.length() >= file.size))) temp.delete()

            var firstAttempt = true
            var keepPart = false
            try {
                retry(file.url, progressed = { if (temp.isFile) temp.length() else 0L }) {
                    val have = if (temp.isFile) temp.length() else 0L
                    if (have == 0L && !firstAttempt) onChunk(-1)
                    if (have > 0L && firstAttempt) onChunk(have)
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
                            if (response.code == 206 || response.code == 416) throw IOException(tr("докачка не совпала с началом — качаем заново {url}", file.url))
                        }
                        if (!response.isSuccessful) throw HttpError(response.code, file.url)
                        val body = response.body ?: throw IOException(tr("пустой ответ от {url}", file.url))
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
            } catch (error: IOException) {
                keepPart = resumable && temp.isFile
                throw error
            } finally {
                if (!keepPart && temp.exists()) temp.delete()
            }
        }
    }

    /**
     * Addresses come from version descriptions and catalogues: a `file://` or a
     * bare path there would copy a file of the phone into the game folder.
     */
    private fun requireWeb(url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw IOException(tr("адрес «{0}» не из сети — такой файл не скачивается", url.substringBefore('?').take(80)))
        }
    }

    private fun promote(file: RemoteFile, temp: File) {
        if (file.sha1 != null && !Hashing.matches(temp, file.sha1)) {
            temp.delete()
            throw IOException(tr("хеш не совпал: {name}", file.target.name))
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
        val host = url.toHttpHostOrNull() ?: throw IOException(tr("непонятный адрес: {url}", url))
        if (host !in hosts) throw IOException(tr("адрес {host} не в списке разрешённых", host))
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
                delay(1_000L)
                continue
            }
            failed++
            if (failed >= attempts) break
            delay(minOf(1_000L shl (failed - 1), 10_000L))
        }
        // Cause first: telemetry keeps the first 120 characters, and a long address used to push the reason out.
        throw IOException(tr("{0} — не удалось скачать {1}", lastError?.message ?: tr("нет ответа"), url.substringBefore('?')), lastError)
    }

    private fun lockFor(target: File): Mutex =
        LOCKS.getOrPut(target.absolutePath) { Mutex() }

    private fun rangeStart(contentRange: String?): Long? =
        contentRange?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-')?.toLongOrNull()

    companion object {
        /** How long a host that answered only through the mirror keeps being asked through it. */
        private const val MIRROR_MEMORY_MS = 10 * 60_000L
        private const val MAX_RESUMES = 50
        private val LOCKS = ConcurrentHashMap<String, Mutex>()

        /**
         * The product name of the desktop launcher: a server tells our client from any other by
         * it. The version sits behind "android-": whatever reads a desktop version out of this
         * header must not take a phone for a computer.
         */
        const val USER_AGENT = "MillidaLauncher/android-$LAUNCHER_VERSION (+https://millida.net)"

        @Volatile
        private var sharedClient: OkHttpClient? = null

        /** One client per application: a client per screen leaks sockets and threads. */
        fun shared(): OkHttpClient = sharedClient ?: synchronized(this) {
            sharedClient ?: defaultClient().also { sharedClient = it }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(DohDns.shared())
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    if (request.header("User-Agent") != null) request
                    else request.newBuilder().header("User-Agent", USER_AGENT).build(),
                )
            }
            .addInterceptor { chain -> chain.proceed(LanguageHeaders.apply(chain.request())) }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private fun <K, V> ConcurrentHashMap<K, V>.getOrPut(key: K, produce: () -> V): V =
    this[key] ?: produce().let { created -> putIfAbsent(key, created) ?: created }
