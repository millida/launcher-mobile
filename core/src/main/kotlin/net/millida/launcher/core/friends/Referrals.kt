package net.millida.launcher.core.friends

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.millida.launcher.core.account.MillidaAuth
import java.io.IOException
import kotlin.math.roundToLong

enum class InviteStatus { PENDING, QUALIFIED, REJECTED }

enum class InviteWait { PLAY, EMAIL, QUEUE, NONE }

data class InviteTier(
    val friends: Int,
    val plusDays: Int,
    val chest: String?,
    val chestName: String?,
    val reached: Boolean,
    val granted: Boolean,
)

data class InvitedFriend(
    val nickname: String?,
    val status: InviteStatus,
    val wait: InviteWait,
    val playSeconds: Long,
    val invitedAt: String,
)

data class InviteNext(val friends: Int, val left: Int)

data class InvitedBy(val nickname: String?, val status: InviteStatus)

/** Answer of `/launcher/referrals/me`, field for field as the desktop reads it (src/lib/referrals.ts). */
data class InviteOverview(
    val code: String,
    val link: String,
    val qualified: Int,
    val pending: Int,
    val perFriendPlusDays: Int,
    val needPlaySeconds: Long,
    val earnedPlusDays: Int,
    val tiers: List<InviteTier>,
    val next: InviteNext?,
    val friends: List<InvitedFriend>,
    val invitedBy: InvitedBy?,
    val canApply: Boolean,
)

/** «Позови друга»: the player's code, the ladder of rewards and entering a friend's code. */
class ReferralsClient(private val auth: MillidaAuth) {

    suspend fun overview(): InviteOverview = guarded("Не удалось загрузить приглашения") {
        Referrals.read(auth.authorized("/launcher/referrals/me", "GET", null))
    }

    suspend fun apply(raw: String): InviteOverview = guarded("Не удалось ввести код") {
        val code = Referrals.normalizeCode(raw)
        if (code.isEmpty()) throw FriendsError("Введи код друга")
        Referrals.read(auth.authorized("/launcher/referrals/apply", "POST", buildJsonObject { put("code", code) }))
    }

    private inline fun <T> guarded(fallback: String, block: () -> T): T = try {
        block()
    } catch (already: FriendsError) {
        throw already
    } catch (error: IOException) {
        throw FriendsErrors.wrap(error, fallback)
    }
}

object Referrals {

    private const val CODE_LIMIT = 16

    private val HOST_LINK = Regex("^https://([a-z0-9-]+\\.)*millida\\.(net|org)(/.*)?$", RegexOption.IGNORE_CASE)

    fun normalizeCode(raw: String): String =
        raw.trim().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(CODE_LIMIT)

    /**
     * The link is copied into other people's messengers, so only our own https
     * address is passed on; anything else leaves the player with the bare code.
     */
    fun read(body: JsonObject): InviteOverview {
        val code = normalizeCode(body.text("code"))
        if (code.isEmpty()) throw FriendsError("Millida не вернула код приглашения")
        val rules = body["rules"] as? JsonObject
        val earned = body["earned"] as? JsonObject
        val next = body["next"] as? JsonObject
        val by = body["invitedBy"] as? JsonObject
        return InviteOverview(
            code = code,
            link = body.text("link").takeIf { HOST_LINK.matches(it) }.orEmpty(),
            qualified = body.int("qualified"),
            pending = body.int("pending"),
            perFriendPlusDays = body.int("perFriendPlusDays"),
            needPlaySeconds = rules?.long("playSeconds") ?: 0L,
            earnedPlusDays = earned?.int("plusDays") ?: 0,
            tiers = body.list("tiers").mapNotNull { entry ->
                val tier = entry as? JsonObject ?: return@mapNotNull null
                InviteTier(
                    friends = tier.int("friends"),
                    plusDays = tier.int("plusDays"),
                    chest = tier.text("chest").ifEmpty { null },
                    chestName = tier.text("chestName").ifEmpty { null },
                    reached = tier.flag("reached"),
                    granted = tier.flag("granted"),
                )
            }.filter { it.friends > 0 }.sortedBy { it.friends },
            next = next?.let { InviteNext(it.int("friends"), it.int("left")) }?.takeIf { it.friends > 0 },
            friends = body.list("friends").mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                InvitedFriend(
                    nickname = row.text("nickname").ifEmpty { null },
                    status = statusOf(row.text("status")),
                    wait = waitOf(row.text("wait")),
                    playSeconds = row.long("playSeconds").coerceAtLeast(0),
                    invitedAt = row.text("invitedAt"),
                )
            },
            invitedBy = by?.let { InvitedBy(it.text("nickname").ifEmpty { null }, statusOf(it.text("status"))) },
            canApply = body.flag("canApply"),
        )
    }

    fun daysText(count: Int): String = "$count " + ruPlural(count.toLong(), "день", "дня", "дней")

    fun friendsText(count: Int): String = "$count " + ruPlural(count.toLong(), "друг", "друга", "друзей")

    fun tierRewardText(tier: InviteTier): String = listOfNotNull(
        tier.chestName,
        if (tier.plusDays > 0) daysText(tier.plusDays) + " PLUS" else null,
    ).joinToString(" + ")

    fun tierProgress(qualified: Int, tiers: List<InviteTier>): Float {
        val next = tiers.firstOrNull { it.friends > qualified } ?: return 1f
        val previous = tiers.lastOrNull { it.friends <= qualified }?.friends ?: 0
        val span = next.friends - previous
        if (span <= 0) return 1f
        return ((qualified - previous).toFloat() / span).coerceIn(0f, 1f)
    }

    fun friendStatusText(friend: InvitedFriend, needSeconds: Long): String = when {
        friend.status == InviteStatus.QUALIFIED -> "Засчитан"
        friend.status == InviteStatus.REJECTED -> "Не засчитан"
        friend.wait == InviteWait.EMAIL -> "Ждёт подтверждения почты"
        friend.wait == InviteWait.QUEUE -> "Засчитаем в ближайшие сутки"
        else -> "Играет " + clock(friend.playSeconds) + " из " + clock(needSeconds)
    }

    fun ruleText(overview: InviteOverview): String {
        val hours = (overview.needPlaySeconds / 3600.0).roundToLong()
        return "+" + daysText(overview.perFriendPlusDays) + " PLUS за друга, который наиграет " + hours + " ч"
    }

    private fun clock(seconds: Long): String {
        val minutes = seconds.coerceAtLeast(0) / 60
        return "${minutes / 60}:" + (minutes % 60).toString().padStart(2, '0')
    }

    private fun statusOf(raw: String): InviteStatus = when (raw) {
        "qualified" -> InviteStatus.QUALIFIED
        "rejected" -> InviteStatus.REJECTED
        else -> InviteStatus.PENDING
    }

    private fun waitOf(raw: String): InviteWait = when (raw) {
        "play" -> InviteWait.PLAY
        "email" -> InviteWait.EMAIL
        "queue" -> InviteWait.QUEUE
        else -> InviteWait.NONE
    }

    private fun JsonObject.list(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()

    private fun JsonObject.text(key: String): String {
        val value = this[key]
        if (value == null || value is JsonNull || value !is JsonPrimitive) return ""
        return value.content
    }

    private fun JsonObject.flag(key: String): Boolean = text(key).toBooleanStrictOrNull() ?: false

    private fun JsonObject.long(key: String): Long = text(key).toDoubleOrNull()?.toLong() ?: 0L

    private fun JsonObject.int(key: String): Int = long(key).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
