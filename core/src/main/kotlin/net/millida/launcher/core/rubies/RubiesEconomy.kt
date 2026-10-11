package net.millida.launcher.core.rubies

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import net.millida.launcher.core.mojang.MojangJson

/** The item inside is visible before paying and the price is fixed: money never buys a random roll. */
@Serializable
data class XrayOffer(
    val id: String = "",
    val tier: String = "",
    val item: RubyItem = RubyItem(),
    val price: Long = 0,
    val expiresAt: String = "",
    val nextAt: String? = null,
)

@Serializable
data class XrayPurchase(val balance: Long = 0, val item: RubyItem = RubyItem(), val chestId: String = "")

@Serializable
data class FragmentGrant(val have: Int = 0, val need: Int = 0, val topUp: Long = 0, val amount: Long = 0)

@Serializable
data class FragmentsCompletion(val balance: Long = 0, val price: Long = 0, val granted: List<RubyItem> = emptyList())

@Serializable
data class PackQuest(
    val code: String = "",
    val title: String = "",
    val pack: String = "",
    val packName: String = "",
    val minutes: Int = 0,
    val needMinutes: Int = 0,
    val done: Boolean = false,
    val claimed: Boolean = false,
    val item: RubyItem = RubyItem(),
    val owned: Boolean = false,
    val fragments: FragmentProgress? = null,
)

@Serializable
data class QuestClaim(val quest: PackQuest = PackQuest(), val item: RubyItem? = null)

sealed interface QuestState {
    data object Claimed : QuestState
    data object Owned : QuestState
    data object Claim : QuestState
    data class Play(val minutesLeft: Int) : QuestState
}

fun questState(quest: PackQuest): QuestState = when {
    quest.claimed -> QuestState.Claimed
    quest.owned -> QuestState.Owned
    quest.done -> QuestState.Claim
    else -> QuestState.Play(maxOf(1, quest.needMinutes - maxOf(0, quest.minutes)))
}

fun questProgress(quest: PackQuest): Float =
    if (quest.needMinutes <= 0) 1f else (quest.minutes.toFloat() / quest.needMinutes).coerceIn(0f, 1f)

/** A quest without an item has nothing to show or to give: the service is mid-rollout, the row is dropped. */
fun questsOf(element: JsonElement): List<PackQuest> {
    val list = (element as? JsonObject)?.get("quests") as? JsonArray ?: return emptyList()
    return list.mapNotNull { entry ->
        val fields = entry as? JsonObject ?: return@mapNotNull null
        if (fields["item"] !is JsonObject) return@mapNotNull null
        try {
            MojangJson.decodeFromJsonElement(PackQuest.serializer(), fields)
        } catch (malformed: SerializationException) {
            null
        } catch (malformed: IllegalArgumentException) {
            null
        }
    }
}

/** A newcomer gets half the fragments of an item; a repeated claim gives nothing and says so with `granted`. */
@Serializable
data class WelcomeGift(
    val item: RubyItem? = null,
    val claimed: Boolean = false,
    val granted: Boolean? = null,
    val equipped: Boolean? = null,
    val fragments: FragmentProgress? = null,
) {
    val fresh: Boolean get() = item != null && item.code.isNotBlank() && granted != false
}

@Serializable
data class WeeklyReward(val kind: String = "", val amount: Long = 0, val item: RubyItem? = null)

@Serializable
data class WeeklyStep(val at: Int = 0, val reward: WeeklyReward = WeeklyReward(), val claimed: Boolean = false)

@Serializable
data class WeeklyTask(
    val code: String = "",
    val title: String = "",
    val stars: Int = 0,
    val earned: Int = 0,
    val progress: Double = 0.0,
    val target: Double = 0.0,
    val unit: String = "",
)

/** Only the last stretch of the path can be bought: paying tops the play up, it does not replace it. */
@Serializable
data class WeeklyBoost(val available: Boolean = false, val missing: Int = 0, val pricePerStar: Long = 0, val price: Long = 0)

@Serializable
data class WeeklyPath(
    val weekKey: Long = 0,
    val resetsAt: String = "",
    val stars: Int = 0,
    val need: Int = 0,
    val prize: RubyItem = RubyItem(),
    val prizePrice: Long = 0,
    val steps: List<WeeklyStep> = emptyList(),
    val tasks: List<WeeklyTask> = emptyList(),
    val plus: Boolean = false,
    val plusHoursBoost: Double = 0.0,
    val boost: WeeklyBoost? = null,
) {
    val prizeStep: WeeklyStep? get() = steps.firstOrNull { it.reward.kind == "ITEM" }
    val done: Boolean get() = need > 0 && stars >= need
    val owned: Boolean get() = prizeStep?.claimed == true
}

@Serializable
data class WeeklyStepClaim(
    val path: WeeklyPath = WeeklyPath(),
    val balance: Long = 0,
    val shards: Long = 0,
    val granted: WeeklyReward = WeeklyReward(),
)

@Serializable
data class WeeklyBoostResult(val path: WeeklyPath = WeeklyPath(), val balance: Long = 0)

/** One /rubies/weekly address answers either the star path or the parcel; a shape we do not know draws nothing. */
sealed interface WeeklyAny {
    data class Path(val path: WeeklyPath) : WeeklyAny
    data class Parcel(val parcel: WeeklyParcel) : WeeklyAny
    data object Unknown : WeeklyAny
}

fun weeklyOf(element: JsonElement): WeeklyAny {
    val fields = element as? JsonObject ?: return WeeklyAny.Unknown
    val tasks = fields["tasks"]
    if (fields["steps"] is JsonArray && (tasks == null || tasks is JsonArray)) {
        return decodeOr(WeeklyAny.Unknown) { WeeklyAny.Path(MojangJson.decodeFromJsonElement(WeeklyPath.serializer(), fields)) }
    }
    val hours = (fields["hours"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    val need = (fields["need"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    val resets = (fields["resetsAt"] as? JsonPrimitive)?.takeIf { it.isString }
    val choices = fields["choices"]
    val choicesFit = choices == null || choices is JsonArray || (choices is JsonPrimitive && choices.content == "null")
    if (hours != null && need != null && need > 0 && resets != null && choicesFit) {
        return decodeOr(WeeklyAny.Unknown) { WeeklyAny.Parcel(MojangJson.decodeFromJsonElement(WeeklyParcel.serializer(), fields)) }
    }
    return WeeklyAny.Unknown
}

private inline fun <T> decodeOr(fallback: T, block: () -> T): T = try {
    block()
} catch (malformed: SerializationException) {
    fallback
} catch (malformed: IllegalArgumentException) {
    fallback
}

@Serializable
data class InventoryItem(
    val code: String = "",
    val name: String = "",
    val slot: String = "",
    val rarity: String = "",
    val rarityTitle: String = "",
    val previewUrl: String? = null,
    val source: String = "",
    val since: String = "",
)

@Serializable
data class Inventory(
    val items: List<InventoryItem> = emptyList(),
    val count: Int = 0,
    val catalogCount: Int = 0,
    val worthRubies: Long = 0,
    val points: Long = 0,
    val byRarity: Map<String, Int> = emptyMap(),
)
