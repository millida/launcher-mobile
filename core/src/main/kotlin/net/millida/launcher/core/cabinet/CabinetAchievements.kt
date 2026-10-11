package net.millida.launcher.core.cabinet

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import net.millida.launcher.core.account.MillidaAuth

@Serializable
data class Achievement(
    val code: String = "",
    val title: String = "",
    val description: String = "",
    val service: String = "",
    val category: String = "",
    val icon: String = "",
    val tier: String = "",
    val points: Int = 0,
    val hidden: Boolean = false,
    val metric: String = "",
    val unlockedAt: String = "",
    val progress: Long = 0,
    val target: Long = 0,
    val progressUnit: String = "",
) {
    val unlocked: Boolean get() = unlockedAt.isNotEmpty()

    /** Only a stepped award has a bar; a one-off is either taken or not. */
    val stepped: Boolean get() = target > 1

    val progressRatio: Float
        get() = when {
            unlocked -> 1f
            target <= 0 -> 0f
            else -> (progress.toFloat() / target.toFloat()).coerceIn(0f, 1f)
        }

    val progressText: String
        get() = if (!stepped) "" else buildString {
            append(progress.coerceAtMost(target))
            append(" / ")
            append(target)
            if (progressUnit.isNotEmpty()) append(" ").append(progressUnit)
        }
}

@Serializable
data class AchievementsPage(
    val items: List<Achievement> = emptyList(),
    val unlocked: Int = 0,
    val total: Int = 0,
    val points: Int = 0,
) {
    val taken: List<Achievement> get() = items.filter { it.unlocked }
    val pending: List<Achievement> get() = items.filterNot { it.unlocked }
}

class CabinetAchievementsClient(private val auth: MillidaAuth) {

    /** Own awards with progress; the server recounts them before answering. */
    suspend fun mine(): AchievementsPage = page("/achievements/me")

    private suspend fun page(path: String): AchievementsPage {
        val response = auth.cabinetElement(path)
        val items = response.itemsArray("items").objects().map { entry ->
            Achievement(
                code = entry.text("code"),
                title = entry.text("title"),
                description = entry.text("description"),
                service = entry.text("service"),
                category = entry.text("category"),
                icon = entry.text("icon"),
                tier = entry.text("tier"),
                points = entry.count("points"),
                hidden = entry.flag("hidden"),
                metric = entry.text("metric"),
                unlockedAt = entry.text("unlockedAt"),
                progress = entry.number("progress"),
                target = entry.number("target"),
                progressUnit = entry.text("progressUnit"),
            )
        }
        val summary = (response as? JsonObject)?.child("summary")
        return AchievementsPage(
            items = items,
            unlocked = summary?.count("unlocked") ?: items.count { it.unlocked },
            total = summary?.count("total") ?: items.size,
            points = summary?.count("points") ?: items.filter { it.unlocked }.sumOf { it.points },
        )
    }
}
