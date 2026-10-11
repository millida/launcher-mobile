package net.millida.launcher.core.hosting

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class HostingServerState(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val status: String = "",
    val address: String = "",
    val core: String = "",
    val version: String = "",
    val planName: String = "",
    val planCode: String = "",
    val planRamMb: Int = 0,
    val ramMb: Int = 0,
    val maxPlayers: Int = 0,
    val planMaxPlayers: Int = 0,
    val playersOnline: Int = 0,
    val planPriceKopecks: Long = 0,
    val worldDeleteAt: String = "",
    val inviteUrl: String = "",
    val pendingRestart: List<String> = emptyList(),
) {
    val running: Boolean get() = status == "RUNNING"
    val crashed: Boolean get() = status == "CRASHED"
    val free: Boolean get() = planPriceKopecks <= 0
}

@Serializable
data class HostingPlayer(
    val id: String = "",
    val nickname: String = "",
    val role: String = "",
    val banned: Boolean = false,
    val lastJoinAt: String = "",
)

@Serializable
data class HostingSubscription(
    val status: String = "",
    val autoRenew: Boolean = false,
    val paidUntil: String = "",
    val periodDays: Int = 0,
    val priceKopecks: Long = 0,
)

@Serializable
data class HostingDetails(
    val server: HostingServerState = HostingServerState(),
    val settings: Map<String, String> = emptyMap(),
    val players: List<HostingPlayer> = emptyList(),
    val customDomain: String = "",
    val iconLocked: Boolean = false,
    val canDelete: Boolean = false,
    val crashReason: String = "",
    val subscription: HostingSubscription? = null,
    val owner: Boolean = true,
    val permissions: List<String> = emptyList(),
    val upgradeCreditKopecks: Long = 0,
) {
    fun can(permission: String): Boolean = owner || permission in permissions
}

@Serializable
data class HostingFreeGate(
    val passed: Boolean = false,
    val botStarted: Boolean = false,
    val subscribed: Boolean = false,
    val botUrl: String = "",
    val botUsername: String = "",
    val channelUrl: String = "",
)

@Serializable
data class HostingStats(
    val running: Boolean = false,
    val memoryUsedMb: Int = 0,
    val memoryLimitMb: Int = 0,
    val cpuPercent: Double = 0.0,
    val diskUsedMb: Int = 0,
    val diskLimitMb: Int = 0,
)

@Serializable
data class HostingOnlinePlayer(val name: String = "", val uuid: String = "")

@Serializable
data class HostingEvent(
    val id: String = "",
    val kind: String = "",
    val message: String = "",
    val actorLabel: String = "",
    val createdAt: String = "",
)

@Serializable
data class HostingCrash(
    val reason: String = "",
    val hintTitle: String = "",
    val hintAdvice: String = "",
    val lines: List<String> = emptyList(),
)

@Serializable
data class HostingPort(val port: Int = 0, val note: String = "")

@Serializable
data class HostingNetwork(
    val address: String = "",
    val limit: Int = 0,
    val ports: List<HostingPort> = emptyList(),
)

@Serializable
data class HostingShare(
    val userId: String = "",
    val email: String = "",
    val nickname: String = "",
    val permissions: List<String> = emptyList(),
    val createdAt: String = "",
)

@Serializable
data class HostingApiKey(
    val id: String = "",
    val name: String = "",
    val prefix: String = "",
    val permissions: List<String> = emptyList(),
    val lastUsedAt: String = "",
    val createdAt: String = "",
)

@Serializable
data class HostingApiKeyIssued(val id: String = "", val token: String = "")

@Serializable
data class HostingSftp(
    val active: Boolean = false,
    val host: String = "",
    val port: Int = 0,
    val login: String = "",
    val password: String = "",
    val expiresAt: String = "",
)

@Serializable
data class HostingWorld(
    val name: String = "",
    val sizeMb: Long = 0,
    val dimension: Boolean = false,
    val active: Boolean = false,
)

@Serializable
data class HostingFileEntry(
    val name: String = "",
    val size: Long = 0,
    val dir: Boolean = false,
    val modTime: String = "",
)

@Serializable
data class HostingFileContent(val path: String = "", val content: String = "")

@Serializable
data class HostingBackup(
    val id: String = "",
    val kind: String = "",
    val sizeMb: Long = 0,
    val locked: Boolean = false,
    val status: String = "",
    val createdAt: String = "",
)

@Serializable
data class HostingUsage(
    val days: Int = 0,
    val runningHours: Double = 0.0,
    val ramGbHours: Double = 0.0,
    val peakPlayers: Int = 0,
    val crashes: Int = 0,
    val uptimePercent: Double = 0.0,
)

/**
 * Every mutating call answers with one of these instead of silence, so the
 * screen always has something truthful to show the player.
 */
@Serializable
data class HostingActionResult(val message: String, val detail: String = "")

/** Server permissions a share or an API key can carry, labelled for the player. */
val HOSTING_PERMISSIONS: List<Pair<String, String>> = listOf(
    "control" to "Запуск и остановка",
    "console" to "Консоль и команды",
    "settings" to "Настройки сервера",
    "content" to "Плагины, моды и версия",
    "players" to "Игроки, баны и вайтлист",
    "world" to "Мир: скачать и пересоздать",
    "backups" to "Резервные копии",
    "files" to "Файлы и конфиги",
    "database" to "База данных",
    "network" to "Адрес, домен и порты",
    "schedule" to "Расписание",
    "security" to "Защита от ботов",
)

internal fun JsonObject.str(key: String): String {
    val value = this[key]
    if (value == null || value is JsonNull) return ""
    return (value as? JsonPrimitive)?.content.orEmpty()
}

internal fun JsonObject.flag(key: String, fallback: Boolean = false): Boolean =
    str(key).toBooleanStrictOrNull() ?: fallback

internal fun JsonObject.int(key: String, fallback: Int = 0): Int =
    str(key).toDoubleOrNull()?.toInt() ?: fallback

internal fun JsonObject.long(key: String, fallback: Long = 0): Long =
    str(key).toDoubleOrNull()?.toLong() ?: fallback

internal fun JsonObject.real(key: String, fallback: Double = 0.0): Double =
    str(key).toDoubleOrNull() ?: fallback

internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

internal fun JsonObject.child(key: String): JsonObject? = this[key] as? JsonObject

/** Our endpoints answer either with a bare array or with it wrapped in a field. */
internal fun JsonElement.asArray(vararg fields: String): List<JsonObject> {
    val array = when (this) {
        is JsonArray -> this
        is JsonObject -> fields.firstNotNullOfOrNull { this[it] as? JsonArray }
        else -> null
    } ?: return emptyList()
    return array.mapNotNull { it as? JsonObject }
}

internal fun JsonObject.toServerState(): HostingServerState = HostingServerState(
    id = str("id"),
    name = str("name"),
    slug = str("slug"),
    status = str("status"),
    address = str("address").ifEmpty { str("ip") },
    core = str("core"),
    version = str("version"),
    planName = str("planName").ifEmpty { str("plan") },
    planCode = str("planCode"),
    planRamMb = int("planRamMb"),
    ramMb = int("ramMb"),
    maxPlayers = int("maxPlayers"),
    planMaxPlayers = int("planMaxPlayers"),
    playersOnline = int("playersOnline"),
    planPriceKopecks = long("planPriceKopecks"),
    worldDeleteAt = str("worldDeleteAt"),
    inviteUrl = str("inviteUrl"),
    pendingRestart = strings("pendingRestart"),
)
