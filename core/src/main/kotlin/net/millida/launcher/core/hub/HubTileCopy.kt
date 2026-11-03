package net.millida.launcher.core.hub

import net.millida.launcher.core.i18n.tr

/**
 * Words on the lobby section tiles, the desktop's HubTile.tsx: a subtitle shows
 * only when something happened (a bonus waits, something new, the skin was
 * never opened), so a quiet tile is just its name.
 */
object HubTileCopy {
    enum class Kind { SHOP, WARDROBE, SERVER }

    data class Copy(val title: String, val subtitle: String?, val ribbon: String?, val alert: Boolean)

    /**
     * [bonusReady] - the daily bonus is not taken; [shopNew] - anything else
     * waits in the shop; [skinSeen] - the skin tile was opened once;
     * [hostedCount] - the player's own servers.
     */
    fun of(kind: Kind, bonusReady: Boolean, shopNew: Boolean, skinSeen: Boolean, hostedCount: Int): Copy = when (kind) {
        Kind.SHOP -> {
            val gift = shopNew || bonusReady
            val subtitle = when {
                bonusReady -> tr("Бонус ждёт")
                gift -> tr("Есть новое")
                else -> null
            }
            Copy(tr("Магазин"), subtitle, ribbon = null, alert = gift)
        }
        Kind.WARDROBE -> Copy(tr("Мой скин"), if (skinSeen) null else tr("Загрузи свой"), ribbon = null, alert = !skinSeen)
        Kind.SERVER -> Copy(tr("Играть с друзьями"), null, ribbon = null, alert = false)
    }
}
