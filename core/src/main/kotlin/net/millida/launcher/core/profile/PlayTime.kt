package net.millida.launcher.core.profile


import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.i18n.trPlural
/**
 * Played time as the desktop lobby shows it (src/lib/format.ts fmtPlaytime and
 * src/lib/playTiers.ts): tiers grow like trophies and are never taken away.
 */
object PlayTime {
    val TIERS_H: List<Long> = listOf(1, 10, 50, 100, 250, 500, 1000, 2500, 5000)

    const val SHOWN_FROM_SECONDS = 3_600L

    /** [next] is null once every tier is taken; [progress] runs 0..1 from the last tier to the next. */
    data class Tier(val reached: Int, val next: Long?, val progress: Float)

    fun tier(seconds: Long): Tier {
        val hours = seconds.coerceAtLeast(0) / 3600.0
        val reached = TIERS_H.count { hours >= it }
        val next = TIERS_H.getOrNull(reached)
        val from = if (reached > 0) TIERS_H[reached - 1] else 0L
        val progress = if (next == null) 1f else ((hours - from) / (next - from)).coerceAtMost(1.0).toFloat()
        return Tier(reached, next, progress)
    }

    fun format(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        if (total < 60) return tr("меньше минуты")
        val hours = total / 3600
        val minutes = total % 3600 / 60
        if (hours == 0L) return tr("{minutes} мин", minutes)
        return if (hours >= 100) tr("{hours} ч", hours) else tr("{hours} ч {minutes} м", hours, minutes)
    }

    /** The line under «Играть»; null below an hour, which the desktop does not show. */
    fun inGame(seconds: Long): String? {
        if (seconds < SHOWN_FROM_SECONDS) return null
        val hours = seconds / 3600
        return trPlural(hours, tr("{hours} час в игре", hours), tr("{hours} часа в игре", hours), tr("{hours} часов в игре", hours))
    }
}
