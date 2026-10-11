package net.millida.launcher.core.servers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One answer to a Server List Ping. [motd] keeps the section-sign colour codes
 * the server sent; rendering them is the caller's job.
 */
data class ServerStatus(
    val online: Int,
    val max: Int,
    val version: String,
    val motd: String,
    val iconBase64: String?,
    val latencyMs: Long,
)

/** Bedrock speaks RakNet over UDP, which this launcher does not implement. */
class BedrockPingUnsupportedException(address: String) : IOException(
    "сервер $address похож на Bedrock — его состояние неизвестно",
)

/**
 * Server List Ping for Java Edition servers, 1.7 and newer: handshake, status
 * request, one JSON answer. Everything that comes back is untrusted, so lengths
 * are capped and a malformed answer surfaces as an IOException.
 */
object ServerPing {

    const val DEFAULT_PORT = 25565
    const val BEDROCK_PORT = 19132

    suspend fun ping(address: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ServerStatus =
        withContext(Dispatchers.IO) { pingBlocking(address, timeoutMs) }

    @Throws(IOException::class)
    fun pingBlocking(address: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ServerStatus {
        val (host, port) = splitHostPort(address)
        if (port == BEDROCK_PORT) throw BedrockPingUnsupportedException(address)
        if (host.isEmpty()) throw IOException("пустой адрес сервера")

        val started = System.nanoTime()
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (refused: IOException) {
                throw IOException("сервер не отвечает: ${refused.message ?: "нет соединения"}", refused)
            }

            val output = socket.getOutputStream()
            output.write(packet(handshake(host, port)))
            output.write(packet(byteArrayOf(0x00)))
            output.flush()

            val input = socket.getInputStream()
            readVarInt(input)
            val packetId = readVarInt(input)
            if (packetId != 0x00) throw IOException("сервер ответил не статусом")
            val length = readVarInt(input)
            if (length <= 0 || length > MAX_JSON_BYTES) throw IOException("некорректный ответ сервера")
            val json = readFully(input, length)
            val latencyMs = (System.nanoTime() - started) / 1_000_000
            return parseStatus(String(json, Charsets.UTF_8), latencyMs)
        }
    }

    @Throws(IOException::class)
    fun parseStatus(json: String, latencyMs: Long): ServerStatus {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (broken: Exception) {
            null
        } ?: throw IOException("сервер прислал неразборчивый ответ")

        val players = root["players"] as? JsonObject
        val version = root["version"] as? JsonObject
        return ServerStatus(
            online = players?.get("online").asInt(),
            max = players?.get("max").asInt(),
            version = (version?.get("name") as? JsonPrimitive)?.contentOrEmpty().orEmpty(),
            motd = motdText(root["description"]),
            iconBase64 = (root["favicon"] as? JsonPrimitive)?.contentOrEmpty()
                ?.substringAfter("base64,")
                ?.takeIf { it.isNotBlank() && it.length <= MAX_ICON_CHARS },
            latencyMs = latencyMs,
        )
    }

    /**
     * A bracketed IPv6 literal has to be split before the last colon is taken
     * for a port, otherwise `[::1]:25565` and `::1` both become garbage hosts.
     */
    fun splitHostPort(address: String): Pair<String, Int> {
        val value = address.trim()
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close > 0) {
                val host = value.substring(1, close)
                val port = value.substring(close + 1).removePrefix(":").toIntOrNull()
                return host to port.orDefaultPort()
            }
        }
        val colon = value.lastIndexOf(':')
        if (colon > 0 && !value.substring(0, colon).contains(':')) {
            return value.substring(0, colon) to value.substring(colon + 1).toIntOrNull().orDefaultPort()
        }
        return value to DEFAULT_PORT
    }

    /**
     * The description is either a plain string or a chat component tree. Styles
     * are flattened back into legacy codes because that is what the launcher UI
     * already knows how to colour.
     */
    fun motdText(element: JsonElement?): String {
        val out = StringBuilder()
        flatten(element, out, 0)
        return out.toString().take(MAX_MOTD_CHARS)
    }

    private fun flatten(element: JsonElement?, out: StringBuilder, depth: Int) {
        if (element == null || depth > MAX_MOTD_DEPTH || out.length > MAX_MOTD_CHARS) return
        when (element) {
            is JsonPrimitive -> out.append(element.contentOrEmpty())
            is JsonArray -> element.forEach { flatten(it, out, depth + 1) }
            is JsonObject -> {
                COLORS[(element["color"] as? JsonPrimitive)?.contentOrEmpty().orEmpty()]?.let { out.append('§').append(it) }
                for ((key, code) in FORMATS) {
                    if ((element[key] as? JsonPrimitive)?.booleanOrNull == true) out.append('§').append(code)
                }
                (element["text"] as? JsonPrimitive)?.let { out.append(it.contentOrEmpty()) }
                flatten(element["extra"], out, depth + 1)
            }
        }
    }

    private fun handshake(host: String, port: Int): ByteArray {
        val body = ByteArrayOutputStream()
        writeVarInt(body, 0x00)
        writeVarInt(body, -1)
        val hostBytes = host.toByteArray(Charsets.UTF_8)
        writeVarInt(body, hostBytes.size)
        body.write(hostBytes)
        body.write((port ushr 8) and 0xff)
        body.write(port and 0xff)
        writeVarInt(body, 1)
        return body.toByteArray()
    }

    private fun packet(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 5)
        writeVarInt(out, body.size)
        out.write(body)
        return out.toByteArray()
    }

    internal fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var rest = value
        while (true) {
            val chunk = rest and 0x7f
            rest = rest ushr 7
            if (rest == 0) {
                out.write(chunk)
                return
            }
            out.write(chunk or 0x80)
        }
    }

    @Throws(IOException::class)
    internal fun readVarInt(input: InputStream): Int {
        var value = 0
        var shift = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) throw EOFException("соединение с сервером оборвалось")
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
            if (shift >= 35) throw IOException("сервер прислал слишком длинное число")
        }
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val step = input.read(buffer, read, length - read)
            if (step < 0) throw EOFException("ответ сервера оборвался")
            read += step
        }
        return buffer
    }

    private fun Int?.orDefaultPort(): Int = this?.takeIf { it in 1..65535 } ?: DEFAULT_PORT

    private fun JsonPrimitive.contentOrEmpty(): String = if (this is JsonNull) "" else content

    private fun JsonElement?.asInt(): Int = (this as? JsonPrimitive)?.intOrNull ?: 0

    private const val DEFAULT_TIMEOUT_MS = 4000
    private const val MAX_JSON_BYTES = 2_000_000
    private const val MAX_ICON_CHARS = 1_000_000
    private const val MAX_MOTD_CHARS = 2000
    private const val MAX_MOTD_DEPTH = 16

    private val COLORS = mapOf(
        "black" to '0', "dark_blue" to '1', "dark_green" to '2', "dark_aqua" to '3',
        "dark_red" to '4', "dark_purple" to '5', "gold" to '6', "gray" to '7',
        "dark_gray" to '8', "blue" to '9', "green" to 'a', "aqua" to 'b',
        "red" to 'c', "light_purple" to 'd', "yellow" to 'e', "white" to 'f',
    )

    private val FORMATS = listOf(
        "bold" to 'l',
        "italic" to 'o',
        "underlined" to 'n',
        "strikethrough" to 'm',
        "obfuscated" to 'k',
    )
}
