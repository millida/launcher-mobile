package net.millida.launcher.core.skins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.http.Downloader
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

private const val TOO_BIG = "PNG больше 2 МБ — возьми файл поменьше"
private const val READ_CHUNK = 16 * 1024

/** A texture that passed every check and is ready to be applied or stored. */
data class ImportedTexture(
    val bytes: ByteArray,
    val shape: TextureShape,
    val kind: TextureKind,
    val name: String,
    val source: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ImportedTexture && source == other.source && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * source.hashCode() + bytes.contentHashCode()
}

/**
 * Import of a texture from outside the launcher. Two doors only: a player
 * nickname, where the address is built here from a sanitised nickname, and a
 * link the player pasted, which is untrusted and goes through the allowlist.
 */
class SkinImport(
    private val client: OkHttpClient = Downloader.shared(),
    private val apiBase: String = MILLIDA_API,
) {
    suspend fun byNick(nick: String, kind: TextureKind = TextureKind.SKIN): ImportedTexture {
        val clean = cleanNick(nick)
        val url = SkinCatalog.skinUrlStrict(clean, apiBase)
        val bytes = download(url, "Ник не найден", untrusted = false)
        val shape = TexturePng.validate(kind, bytes)
        return ImportedTexture(bytes, shape, kind, clean, url)
    }

    suspend fun byUrl(raw: String, kind: TextureKind = TextureKind.SKIN): ImportedTexture {
        val url = validateUrl(raw)
        val bytes = download(url.toString(), "По ссылке ничего нет", untrusted = true)
        val shape = TexturePng.validate(kind, bytes)
        val name = url.pathSegments.lastOrNull()
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: url.host
        return ImportedTexture(bytes, shape, kind, name, url.toString())
    }

    private suspend fun download(url: String, missing: String, untrusted: Boolean): ByteArray =
        withContext(Dispatchers.IO) {
            val response = try {
                client.newCall(Request.Builder().url(url).build()).execute()
            } catch (failure: IOException) {
                throw TextureError("Текстура не скачалась — проверь сеть и повтори", failure)
            }
            response.use {
                if (it.code == 404) throw TextureError(missing)
                if (!it.isSuccessful) throw TextureError("Ссылка ответила ${it.code} — возьми другую ссылку")
                // The shared client follows redirects, so the address the bytes
                // came from is checked again, not only the one that was asked.
                val finalUrl = it.request.url
                if (finalUrl.toString() != url) {
                    if (untrusted) {
                        validateUrl(finalUrl.toString())
                    } else if (hostIsLocal(finalUrl.host)) {
                        throw TextureError("Ссылка увела в локальную сеть")
                    }
                }
                val body = it.body ?: throw TextureError("Пустой ответ по ссылке")
                readCapped(body)
            }
        }

    private fun readCapped(body: ResponseBody): ByteArray {
        if (body.contentLength() > TexturePng.MAX_BYTES) throw TextureError(TOO_BIG)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > TexturePng.MAX_BYTES) throw TextureError(TOO_BIG)
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    companion object {
        private val NICK = Regex("^[A-Za-z0-9_]{3,16}$")

        /**
         * Hosts the launcher itself links textures from: Mojang's texture CDN,
         * our own API and CDN, and the Modrinth CDN that serves pack assets.
         */
        val TEXTURE_HOSTS: List<String> = listOf(
            "textures.minecraft.net",
            "api.millida.net",
            "cdn.millida.net",
            "millida.net",
            "cdn.millida.trade",
            "millida.trade",
            "cdn.modrinth.com",
        )

        /** Our own domains; wardrobe textures live on cdn.millida.trade. */
        val TEXTURE_HOST_SUFFIXES: List<String> = listOf(".millida.net", ".millida.trade")

        fun cleanNick(raw: String): String {
            val nick = raw.trim()
            if (!NICK.matches(nick)) {
                throw TextureError("Ник — от 3 до 16 символов: латиница, цифры и подчёркивание")
            }
            return nick
        }

        /**
         * Names and literals that resolve inside the phone or the player's home
         * network. The fetch happens in the core with no browser policy around
         * it, so without this a pasted link could read back local answers.
         */
        fun hostIsLocal(rawHost: String): Boolean {
            val host = rawHost.trim().trim('[', ']').trimEnd('.').lowercase()
            if (host.isEmpty()) return true
            if (host == "localhost" || host.endsWith(".localhost") ||
                host.endsWith(".local") || host.endsWith(".internal")
            ) {
                return true
            }
            if (!looksLikeIpLiteral(host)) return false
            val address = try {
                InetAddress.getByName(host)
            } catch (unknown: UnknownHostException) {
                return true
            }
            if (address.isLoopbackAddress || address.isAnyLocalAddress ||
                address.isLinkLocalAddress || address.isSiteLocalAddress
            ) {
                return true
            }
            val bytes = address.address
            if (bytes.size == 4) {
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                // carrier-grade NAT, 100.64.0.0/10
                if (first == 100 && second in 64..127) return true
                if (first == 255) return true
            }
            // unique local fc00::/7, which Java does not count as site-local
            if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return true
            return false
        }

        private fun looksLikeIpLiteral(host: String): Boolean =
            host.contains(':') || host.all { it.isDigit() || it == '.' }

        /**
         * A link the player pasted. Everything about it is untrusted: the
         * scheme, the host and where a redirect would land.
         */
        fun validateUrl(raw: String): HttpUrl {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw TextureError("Пустая ссылка")
            if (trimmed.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                throw TextureError("Некорректная ссылка")
            }
            val url = trimmed.toHttpUrlOrNull() ?: throw TextureError("Некорректная ссылка")
            if (url.scheme != "https") throw TextureError("Нужна ссылка https на PNG")
            val host = url.host.trimEnd('.').lowercase()
            if (host.isEmpty() || hostIsLocal(host)) {
                throw TextureError("Скины из локальной сети лаунчер не забирает")
            }
            // Suffix, never substring: "api.millida.net.evil.com" contains an
            // allowed host but is served by evil.com.
            val allowed = TEXTURE_HOSTS.any { it == host } ||
                TEXTURE_HOST_SUFFIXES.any { host.endsWith(it) }
            if (!allowed) {
                throw TextureError("Скины берём только с проверенных сайтов: ${TEXTURE_HOSTS.joinToString(", ")}")
            }
            return url
        }
    }
}
