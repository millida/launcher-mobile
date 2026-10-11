package net.millida.launcher.core.hub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.premium.Premium
import net.millida.launcher.core.premium.PremiumPack
import java.io.IOException
import java.net.URLEncoder

class HubClient(private val auth: MillidaAuth) {

    @Throws(IOException::class)
    suspend fun modes(): List<HubMode> = Hub.modes(auth.publicCall("/rating/modes", "GET", null, withSession = false))

    @Throws(IOException::class)
    suspend fun packHealth(slug: String): PackHealth? {
        val key = slug.trim().lowercase()
        if (key.isEmpty()) return null
        return Hub.health(auth.publicCall("/launcher/pack-health?slugs=" + URLEncoder.encode(key, "UTF-8"), "GET", null), key)
    }

    @Throws(IOException::class)
    suspend fun catalogPacks(): List<PremiumPack> = Premium.catalogPacks(auth.publicCall("/catalog/packs", "GET", null))

    /** Players on our own event right now, from its own rating card: its category sums foreign servers. */
    @Throws(IOException::class)
    suspend fun serverOnline(slug: String): Int? {
        val body = auth.publicCall("/rating/servers/" + URLEncoder.encode(slug, "UTF-8"), "GET", null, withSession = false)
        val online = ((body as? JsonObject)?.get("online") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        return online?.takeIf { it >= 0 }?.toInt()
    }

    /** The fixed top three stands in for a missing answer, the way the desktop does it. */
    @Throws(IOException::class)
    suspend fun popularVersions(): List<PopularVersion> =
        Hub.popularVersions(auth.publicCall("/launcher/popular-versions", "GET", null))
}
