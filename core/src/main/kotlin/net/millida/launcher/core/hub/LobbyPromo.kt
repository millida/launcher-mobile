package net.millida.launcher.core.hub

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Slides of the banner on the right of the landscape lobby, in the desktop's
 * order (src/components/lobby/promo.ts). The desktop's anarchy slide is the
 * phone's own event, PrisonRPG and the PvP-tier server are not offered here.
 */
enum class PromoSlide { DISCORD, TELEGRAM, OWN_EVENT, ARCANIA, INVITE }

/** What is known right now: a slide whose card or terms have not arrived stays out. */
data class PromoReady(val ownEvent: Boolean, val arcania: Boolean, val invite: Boolean)

object LobbyPromo {
    const val PATH = "/launcher/promo"
    const val ROTATE_MS = 7_000L
    const val DISCORD_INVITE = "https://discord.gg/ec8xAhDSH7"
    const val TELEGRAM_CHAT = "https://t.me/millida"

    fun slides(ready: PromoReady): List<PromoSlide> = PromoSlide.entries.filter { slide ->
        when (slide) {
            PromoSlide.OWN_EVENT -> ready.ownEvent
            PromoSlide.ARCANIA -> ready.arcania
            PromoSlide.INVITE -> ready.invite
            PromoSlide.DISCORD, PromoSlide.TELEGRAM -> true
        }
    }

    /** Where a player starts today: every slide gets its share of first looks over the days. */
    fun start(count: Int, day: Long, salt: Int): Int {
        if (count <= 0) return 0
        return Math.floorMod(day + salt, count.toLong()).toInt()
    }

    /** The slide [step] turns away from [start]; steps back past the first wrap to the last. */
    fun at(count: Int, start: Int, step: Int): Int {
        if (count <= 0) return 0
        return Math.floorMod(start.toLong() + step, count.toLong()).toInt()
    }

    /**
     * Whether the API keeps the banner in the lobby (`promo.lobby`). Only an
     * explicit null takes it away: a missing or unknown field is the default
     * the release shipped with, as on the desktop (state/promo.ts).
     */
    fun lobbyShown(body: JsonElement?): Boolean {
        val promo = (body as? JsonObject)?.get("promo") as? JsonObject ?: return true
        return promo["lobby"] !is JsonNull
    }
}
