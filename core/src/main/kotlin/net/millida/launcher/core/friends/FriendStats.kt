package net.millida.launcher.core.friends

import kotlinx.serialization.json.buildJsonObject
import net.millida.launcher.core.account.MillidaAuth
import java.io.IOException

/**
 * Playtime the friends see. Only the hiding half lives here: `/friends/stats`
 * keeps one client snapshot per account and seeds the server-side playtime
 * baseline from it, so a phone posting its own small total would overwrite the
 * desktop's figures. Turning "Наигранное время" off wipes what was already
 * published, the same call the desktop makes (src/state/playStats.ts).
 */
class FriendStatsClient(private val auth: MillidaAuth) {

    suspend fun hide() {
        try {
            auth.authorized("/friends/stats/hide", "POST", buildJsonObject { })
        } catch (already: FriendsError) {
            throw already
        } catch (error: IOException) {
            throw FriendsErrors.wrap(error, "Не удалось скрыть наигранное время")
        }
    }
}
