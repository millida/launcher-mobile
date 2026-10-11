package net.millida.launcher.core.friends

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.core.mojang.MojangJson

data class ServerInvite(val addr: String, val name: String, val version: String? = null)

/**
 * "Позвать играть" travels as an ordinary chat message with a marker in front,
 * the format the desktop launcher and the site read as a "Зайти" card
 * (src/lib/invite.ts, trade-web/src/lib/chat/invite.ts). The server has no
 * invite endpoint of its own, so the text format is the contract.
 */
object ServerInvites {

    const val PREFIX = "⟪mc-invite⟫"

    const val NAME_LIMIT = 48

    private val ADDRESS = Regex("^[a-zA-Z0-9]([a-zA-Z0-9._-]{1,118})?(:\\d{1,5})?$")

    private val VERSION = Regex("^\\d{1,4}(\\.\\d{1,3}){1,2}$")

    fun isServerAddr(addr: String): Boolean = ADDRESS.matches(addr.trim())

    fun cleanVersion(value: String?): String {
        val trimmed = value.orEmpty().trim()
        return if (VERSION.matches(trimmed)) trimmed else ""
    }

    fun encode(addr: String, name: String, version: String? = null): String {
        val clean = cleanVersion(version)
        val body = buildJsonObject {
            put("addr", addr)
            put("name", name)
            if (clean.isNotEmpty()) put("version", clean)
        }
        return PREFIX + body.toString()
    }

    fun parse(text: String): ServerInvite? {
        if (!text.startsWith(PREFIX)) return null
        val body = runCatching { MojangJson.parseToJsonElement(text.removePrefix(PREFIX)) }.getOrNull() as? JsonObject
            ?: return null
        val addr = body.plain("addr")
        if (addr.isEmpty()) return null
        val version = cleanVersion(body.plain("version")).ifEmpty { null }
        return ServerInvite(addr = addr, name = body.plain("name").ifEmpty { "Сервер" }, version = version)
    }

    /** A running server with an address first, otherwise any with an address. */
    fun pickServer(list: List<HostingServerState>): HostingServerState? =
        list.firstOrNull { it.address.isNotBlank() && it.running } ?: list.firstOrNull { it.address.isNotBlank() }

    fun serverTitle(server: HostingServerState): String = server.name.ifBlank { "Мой сервер" }

    fun forServer(server: HostingServerState): String =
        encode(server.address, serverTitle(server).take(NAME_LIMIT), server.version)

    /** The lobby "Позвать" is a plain line, not a card: it names what is picked to play, if anything. */
    fun lobbyCall(modeTitle: String?): String =
        modeTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { "Го играть: $it" } ?: "Го играть"

    private fun JsonObject.plain(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}
