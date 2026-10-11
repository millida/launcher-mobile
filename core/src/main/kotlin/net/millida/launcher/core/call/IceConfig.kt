package net.millida.launcher.core.call

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max

data class IceServerSpec(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

data class IceConfig(val servers: List<IceServerSpec>, val relayOnly: Boolean)

/**
 * Relay credentials come from GET /friends/call/ice, the same source the
 * desktop launcher uses (call-ice.service.ts in trade-api): Cloudflare TURN or
 * our own coturn with hourly logins. Nothing here is a permanent secret.
 */
object IceConfigs {

    /**
     * The last resort when the server did not answer: without a relay the
     * connection will not form for everyone, but a silent call is worse than
     * a call without TURN.
     */
    val FALLBACK = IceConfig(
        servers = listOf(IceServerSpec(listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"))),
        relayOnly = false,
    )

    private val SCHEMES = setOf("stun", "stuns", "turn", "turns")

    /** Null when the answer holds no usable server: the caller then falls back. */
    fun parse(response: JsonObject): Pair<IceConfig, Long>? {
        val servers = (response["iceServers"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val urls = when (val raw = row["urls"]) {
                is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                is JsonPrimitive -> listOfNotNull(raw.takeIf { it.isString }?.content)
                else -> emptyList()
            }.map { it.trim() }.filter(::acceptable)
            if (urls.isEmpty()) null else IceServerSpec(urls, row.string("username"), row.string("credential"))
        }
        if (servers.isEmpty()) return null
        val relayOnly = response.flag("relayOnly") ?: false
        return IceConfig(servers, relayOnly) to cacheSeconds(response.finite("ttlSeconds")?.toLong())
    }

    /** Refreshed five minutes early: a login handed out at the edge would expire mid-ring. */
    fun cacheSeconds(ttlSeconds: Long?): Long = max(300L, (ttlSeconds ?: 3600L) - 300L)

    private fun acceptable(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase()
        return scheme in SCHEMES && url.length > scheme.length + 1 && url.none { it.isWhitespace() }
    }
}
