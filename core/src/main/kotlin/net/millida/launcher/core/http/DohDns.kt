package net.millida.launcher.core.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Some mobile networks answer nothing for a name their operator dislikes, while
 * the browser on the same phone opens it through its own resolver. The system
 * resolver stays first; DNS-over-HTTPS is only a second try for public names.
 */
class DohDns(
    private val system: Dns = Dns.SYSTEM,
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    resolver: OkHttpClient? = null,
) : Dns {

    private val cache = ConcurrentHashMap<String, Cached>()
    private val resolverClient: OkHttpClient by lazy { resolver ?: bootstrapClient() }

    override fun lookup(hostname: String): List<InetAddress> {
        val systemFailure = try {
            val direct = system.lookup(hostname)
            if (direct.isNotEmpty()) return direct
            UnknownHostException(hostname)
        } catch (failure: UnknownHostException) {
            failure
        }

        if (!worthAsking(hostname)) throw systemFailure

        val key = hostname.lowercase()
        cached(key)?.let { return it }

        val resolved = askEndpoints(hostname)
        if (resolved.isEmpty()) {
            throw UnknownHostException(NetworkFaults.nameLookupAdvice(hostname))
                .apply { initCause(systemFailure) }
        }
        cache[key] = Cached(resolved, clock() + cacheTtlMillis)
        return resolved
    }

    private fun cached(key: String): List<InetAddress>? {
        val entry = cache[key] ?: return null
        if (entry.expiresAt <= clock()) {
            cache.remove(key, entry)
            return null
        }
        return entry.addresses
    }

    private fun askEndpoints(hostname: String): List<InetAddress> {
        for (endpoint in endpoints) {
            val answer = runCatching { ask(endpoint, hostname) }.getOrDefault(emptyList())
            if (answer.isNotEmpty()) return answer
        }
        return emptyList()
    }

    private fun ask(endpoint: String, hostname: String): List<InetAddress> {
        val url = endpoint.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("name", hostname)
            ?.addQueryParameter("type", "A")
            ?.build()
            ?: return emptyList()
        val request = Request.Builder().url(url)
            .header("accept", "application/dns-json")
            .build()
        val text = resolverClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        val reply = runCatching { DOH_JSON.decodeFromString(Reply.serializer(), text) }.getOrNull()
            ?: return emptyList()
        return reply.answers
            .filter { it.type == TYPE_A }
            .mapNotNull { record -> address(hostname, record.data) }
    }

    private fun worthAsking(hostname: String): Boolean =
        !isLiteral(hostname) && hostname.contains('.') && !hostname.endsWith(".local", ignoreCase = true)

    private class Cached(val addresses: List<InetAddress>, val expiresAt: Long)

    @Serializable
    private class Reply(
        @SerialName("Status") val status: Int = 0,
        @SerialName("Answer") val answers: List<Record> = emptyList(),
    )

    @Serializable
    private class Record(
        @SerialName("type") val type: Int = 0,
        @SerialName("data") val data: String = "",
    )

    companion object {
        val DEFAULT_ENDPOINTS = listOf(
            "https://dns.google/resolve",
            "https://cloudflare-dns.com/dns-query",
        )

        const val DEFAULT_CACHE_TTL_MILLIS = 5L * 60 * 1000

        private const val TYPE_A = 1

        private val DOH_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        @Volatile
        private var instance: DohDns? = null

        fun shared(): DohDns = instance ?: synchronized(this) {
            instance ?: DohDns().also { instance = it }
        }

        fun isLiteral(hostname: String): Boolean =
            hostname.contains(':') || ipv4Bytes(hostname) != null

        /**
         * A resolver answer is data from the network: only a literal A record is
         * accepted, otherwise `getByName` would start a lookup of its own.
         */
        fun address(hostname: String, data: String): InetAddress? {
            val bytes = ipv4Bytes(data) ?: return null
            return runCatching { InetAddress.getByAddress(hostname, bytes) }.getOrNull()
        }

        fun ipv4Bytes(text: String): ByteArray? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            parts.forEachIndexed { index, part ->
                if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
                val value = part.toIntOrNull() ?: return null
                if (value !in 0..255) return null
                bytes[index] = value.toByte()
            }
            return bytes
        }

        private fun bootstrapClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(BootstrapDns)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

/** The broken part is name resolution, so the resolvers themselves are reached by address. */
private object BootstrapDns : Dns {

    private val known = mapOf(
        "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
        "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
    )

    override fun lookup(hostname: String): List<InetAddress> {
        val literals = known[hostname.lowercase()] ?: return Dns.SYSTEM.lookup(hostname)
        val addresses = literals.mapNotNull { DohDns.address(hostname, it) }
        if (addresses.isEmpty()) throw UnknownHostException(hostname)
        return addresses
    }
}
