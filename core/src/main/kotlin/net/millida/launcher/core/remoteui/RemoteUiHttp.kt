package net.millida.launcher.core.remoteui

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * GET of the site's public files. The edge in front of millida.net hands a cookie ticket to anything that
 * asks like a browser page and answers 401 «cookie-shield» to the rest: on that answer the same request
 * is repeated once with a page-like Accept, and the ticket is kept by the client's cookie jar.
 */
class RemoteUiHttp(private val client: OkHttpClient) : RemoteUiFetcher {

    @Throws(IOException::class)
    override fun get(url: String): ByteArray {
        require(url.startsWith("https://") || url.startsWith("http://127.0.0.1")) { "https only" }
        return once(url, null) ?: once(url, "text/html,application/xhtml+xml,*/*;q=0.8")
            ?: throw IOException("сайт не выдал билет на $url")
    }

    /** The body, or null on the shield's 401 when [accept] is not set yet. */
    private fun once(url: String, accept: String?): ByteArray? {
        val request = Request.Builder().url(url).apply { accept?.let { header("Accept", it) } }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 && accept == null && response.header("WWW-Authenticate").orEmpty().contains("cookie-shield")) return null
            if (!response.isSuccessful) throw IOException("сервер ответил ${response.code} на $url")
            val body = response.body ?: throw IOException("пустой ответ от $url")
            val declared = body.contentLength()
            if (declared > MAX_BYTES) throw IOException("файл слишком большой: $declared")
            val bytes = body.byteStream().readBytes()
            if (bytes.size > MAX_BYTES) throw IOException("файл слишком большой: ${bytes.size}")
            return bytes
        }
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
