package net.millida.launcher.core.account

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.UUID

enum class AccountKind { OFFLINE, MICROSOFT, MILLIDA }

@Serializable
data class Account(
    val id: String,
    val kind: AccountKind,
    val nick: String,
    val uuid: String,
    val xuid: String = "",
    val addedAt: Long = 0,
)

data class GameSession(
    val nick: String,
    val uuid: String,
    val accessToken: String,
    val xuid: String = "",
    val yggdrasil: String = "",
) {
    val userType: String get() = if (accessToken.isEmpty()) "legacy" else "msa"

    val licensed: Boolean get() = accessToken.isNotEmpty() && yggdrasil.isEmpty()
}

interface SecretVault {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
}

class MemoryVault : SecretVault {
    private val entries = HashMap<String, String>()
    override fun read(key: String): String? = entries[key]
    override fun write(key: String, value: String) { entries[key] = value }
    override fun remove(key: String) { entries.remove(key) }
}

object OfflineAuth {
    fun session(nick: String): GameSession =
        GameSession(nick = nick, uuid = offlineUuid(nick), accessToken = "", yggdrasil = "")

    fun offlineUuid(nick: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest("OfflinePlayer:$nick".toByteArray())
        md5[6] = ((md5[6].toInt() and 0x0f) or 0x30).toByte()
        md5[8] = ((md5[8].toInt() and 0x3f) or 0x80).toByte()
        var msb = 0L
        var lsb = 0L
        for (i in 0..7) msb = (msb shl 8) or (md5[i].toLong() and 0xff)
        for (i in 8..15) lsb = (lsb shl 8) or (md5[i].toLong() and 0xff)
        return UUID(msb, lsb).toString().replace("-", "")
    }

    private val NICK = Regex("^[A-Za-z0-9_]{3,16}$")

    fun validNick(nick: String): Boolean = NICK.matches(nick)
}
