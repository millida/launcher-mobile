package net.millida.launcher.core.portal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.millida.launcher.core.account.MillidaAuth

@Serializable
data class PortalProfile(
    val nickname: String = "",
    val avatarUrl: String = "",
    val balanceKopecks: Long = 0,
)

@Serializable
data class Privacy(
    val showActivity: Boolean = true,
    val showFriends: Boolean = true,
    val showPlaytime: Boolean = true,
    val showAchievements: Boolean = true,
    val showMarket: Boolean = true,
    val showServers: Boolean = true,
    val duoInvites: String = DUO_INVITES_DEFAULT,
) {
    companion object {
        const val DUO_INVITES_DEFAULT = "all"
        val DUO_INVITES = listOf("all" to "Все", "friends" to "Друзья", "none" to "Никто")

        fun duoInvitesValid(value: String): Boolean = DUO_INVITES.any { it.first == value }
    }
}

/**
 * The account as the site knows it. The launcher shows this and changes only
 * what is safe to change from a phone; anything that needs a password or a code
 * is opened on the site instead of being half-implemented here.
 */
@Serializable
data class AccountDetails(
    val nickname: String = "",
    val displayName: String = "",
    val email: String = "",
    val emailVerified: Boolean = false,
    val emailIsSynthetic: Boolean = false,
    val twoFactorEnabled: Boolean = false,
    val nicknameCanChange: Boolean = true,
    val nicknameNextChangeAt: String = "",
    val nicknameCooldownDays: Int = 0,
    val publicSlug: String = "",
    val avatarUrl: String = "",
    val discordLinked: Boolean = false,
    val discordUsername: String = "",
    val telegramLinked: Boolean = false,
    val telegramUsername: String = "",
    val banned: Boolean = false,
    val banReason: String = "",
)

@Serializable
data class AccountSession(
    val id: String = "",
    val device: String = "",
    val place: String = "",
    val lastSeen: String = "",
    val current: Boolean = false,
)

class PortalClient(private val auth: MillidaAuth) {

    suspend fun profile(): PortalProfile {
        val me = auth.authorized("/users/me", "GET", null)
        val wallet = runCatching { auth.authorized("/core/wallet/me/display", "GET", null) }.getOrNull()
        return PortalProfile(
            nickname = me.text("nickname"),
            avatarUrl = me.text("avatarUrl"),
            balanceKopecks = wallet?.text("availableKopecks")?.toLongOrNull() ?: 0,
        )
    }

    suspend fun details(): AccountDetails {
        val me = auth.authorized("/users/me", "GET", null)
        return AccountDetails(
            nickname = me.text("nickname"),
            displayName = me.text("displayName"),
            email = me.text("email"),
            emailVerified = me.flag("emailVerified"),
            emailIsSynthetic = me.flag("emailIsSynthetic"),
            twoFactorEnabled = me.flag("twoFactorEnabled"),
            nicknameCanChange = me.flag("nicknameCanChange", missing = true),
            nicknameNextChangeAt = me.text("nicknameNextChangeAt"),
            nicknameCooldownDays = me.text("nicknameCooldownDays").toIntOrNull() ?: 0,
            publicSlug = me.text("publicSlug"),
            avatarUrl = me.text("avatarUrl"),
            discordLinked = me.flag("discordLinked"),
            discordUsername = me.text("discordUsername"),
            telegramLinked = me.flag("tgLinked"),
            telegramUsername = me.text("tgUsername"),
            banned = me.flag("banned"),
            banReason = me.text("banReason"),
        )
    }

    /** Answers whether the nick is free before the player spends the change. */
    suspend fun nicknameFree(value: String): Boolean {
        val response = auth.authorized(
            "/users/me/nickname/check?value=" + java.net.URLEncoder.encode(value, "UTF-8"),
            "GET",
            null,
        )
        return response.flag("available") || response.flag("free") || response.text("status") == "free"
    }

    suspend fun changeNickname(value: String): AccountDetails {
        auth.authorized("/users/me/nickname", "POST", buildJsonObject { put("nickname", value) })
        return details()
    }

    /** The link to bind an account elsewhere: the launcher only opens it. */
    suspend fun discordLinkUrl(): String =
        auth.authorized("/users/me/discord/link-url", "GET", null).let { response ->
            response.text("url").ifEmpty { response.text("link") }
        }

    suspend fun unlinkDiscord() {
        auth.authorized("/users/me/discord/unlink", "POST", buildJsonObject { })
    }

    suspend fun telegramLinkUrl(): String =
        auth.authorized("/users/me/tg/link", "POST", buildJsonObject { }).let { response ->
            response.text("url").ifEmpty { response.text("link") }
        }

    suspend fun unlinkTelegram() {
        auth.authorized("/users/me/tg/unlink", "POST", buildJsonObject { })
    }

    suspend fun sessions(): List<AccountSession> {
        val element = auth.authorizedElement("/users/me/sessions", "GET", null)
        val array = when (element) {
            is kotlinx.serialization.json.JsonArray -> element
            is JsonObject -> element["items"]?.jsonArray ?: element["sessions"]?.jsonArray
            else -> null
        } ?: return emptyList()
        return array.map { entry ->
            val item = entry.jsonObject
            AccountSession(
                id = item.text("id"),
                device = item.text("device").ifEmpty { item.text("userAgent") },
                place = item.text("place").ifEmpty { item.text("ip") },
                lastSeen = item.text("lastSeenAt").ifEmpty { item.text("updatedAt") },
                current = item.flag("current"),
            )
        }
    }

    suspend fun revokeSession(id: String) {
        auth.authorized("/users/me/sessions/$id", "DELETE", null)
    }

    suspend fun revokeOtherSessions() {
        auth.authorized("/users/me/sessions/revoke-all", "POST", buildJsonObject { })
    }

    suspend fun privacy(): Privacy = auth.authorized("/users/me/privacy", "GET", null).toPrivacy()

    suspend fun updatePrivacy(field: String, value: Boolean): Privacy =
        auth.authorized("/users/me/privacy", "PATCH", buildJsonObject { put(field, value) }).toPrivacy()

    suspend fun updateDuoInvites(value: String): Privacy {
        require(Privacy.duoInvitesValid(value)) { "неизвестное значение приглашений: $value" }
        return auth.authorized("/users/me/privacy", "PATCH", buildJsonObject { put("duoInvites", value) }).toPrivacy()
    }

    private fun JsonObject.toPrivacy() = Privacy(
        showActivity = flag("showActivity", missing = true),
        showFriends = flag("showFriends", missing = true),
        showPlaytime = flag("showPlaytime", missing = true),
        showAchievements = flag("showAchievements", missing = true),
        showMarket = flag("showMarket", missing = true),
        showServers = flag("showServers", missing = true),
        duoInvites = text("duoInvites").takeIf(Privacy::duoInvitesValid) ?: Privacy.DUO_INVITES_DEFAULT,
    )

    /**
     * A field the server did not send is not a yes: read as true, a missing
     * `banned` showed the account as banned. Only privacy, open by default,
     * asks for that.
     */
    private fun JsonObject.flag(key: String, missing: Boolean = false): Boolean =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: missing

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
}

fun Long.asRubles(): String {
    val sign = if (this < 0) "-" else ""
    val magnitude = kotlin.math.abs(this)
    val whole = magnitude / 100
    val cents = (magnitude % 100).toInt()
    return if (cents == 0) "$sign$whole ₽" else "$sign$whole,${cents.toString().padStart(2, '0')} ₽"
}
