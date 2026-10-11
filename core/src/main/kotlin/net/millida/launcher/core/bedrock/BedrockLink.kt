package net.millida.launcher.core.bedrock

const val BEDROCK_PACKAGE = "com.mojang.minecraftpe"
const val BEDROCK_DEFAULT_PORT = 19132
const val BEDROCK_STORE_URL = "https://play.google.com/store/apps/details?id=$BEDROCK_PACKAGE"

data class BedrockAddress(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"
}

/**
 * Addresses reach us as the player typed them, as the hosting printed them and
 * as the rating stored them: with a scheme, with a trailing slash, with the
 * port glued on or missing. One parser decides what a Bedrock address is, so
 * the deep link and the badge can never disagree about it.
 */
fun parseBedrockAddress(raw: String, defaultPort: Int = BEDROCK_DEFAULT_PORT): BedrockAddress? {
    var text = raw.trim().removeSuffix("/")
    if (text.isEmpty()) return null
    listOf("minecraft://", "tcp://", "udp://", "http://", "https://").forEach { scheme ->
        if (text.startsWith(scheme, ignoreCase = true)) text = text.substring(scheme.length)
    }
    text = text.trim().removeSuffix("/")
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null

    val host: String
    val port: Int
    if (text.startsWith("[")) {
        val close = text.indexOf(']')
        if (close <= 1) return null
        host = text.substring(1, close)
        val rest = text.substring(close + 1)
        port = when {
            rest.isEmpty() -> defaultPort
            rest.startsWith(":") -> rest.drop(1).toIntOrNull() ?: return null
            else -> return null
        }
    } else {
        val colon = text.lastIndexOf(':')
        if (colon <= 0) {
            host = text
            port = defaultPort
        } else {
            val tail = text.substring(colon + 1)
            if (tail.isEmpty()) return null
            val parsed = tail.toIntOrNull() ?: return null
            host = text.substring(0, colon)
            port = parsed
        }
    }
    if (host.isEmpty() || port !in 1..65535) return null
    if (host.any { it == '|' || it == '?' || it == '&' || it == '#' || it == '/' }) return null
    return BedrockAddress(host, port)
}

/**
 * The official client connects straight away only through its own "connect"
 * handler; the address is not part of a path, so nothing but the value needs
 * escaping.
 */
fun bedrockConnectUri(address: BedrockAddress): String =
    "minecraft://connect?serverUrl=${address.host}&serverPort=${address.port}"

/**
 * Older clients ignore "connect" and only remember a server. The value is
 * positional - name, address and port separated by "|" and ":" - so a name
 * carrying a separator would silently produce a different server.
 */
fun bedrockAddServerUri(name: String, address: BedrockAddress): String {
    val label = name.trim()
        .map { symbol -> if (symbol == '|' || symbol == '&' || symbol == '#' || symbol == '?') ' ' else symbol }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .take(40)
        .ifEmpty { "Millida" }
    return "minecraft://?addExternalServer=$label|${address.host}:${address.port}"
}

/**
 * Bedrock speaks RakNet on its own port, and neither edition can join the
 * other's. Anything on the Bedrock port is handed to the Bedrock client, and
 * everything else stays with the Java runtime we ship.
 */
fun isBedrockAddress(raw: String): Boolean =
    parseBedrockAddress(raw, defaultPort = 0)?.port == BEDROCK_DEFAULT_PORT
