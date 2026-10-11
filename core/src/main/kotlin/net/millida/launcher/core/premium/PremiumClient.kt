package net.millida.launcher.core.premium

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.catalog.MillidaCatalog
import java.io.IOException
import java.net.URLEncoder

/** The addresses of the desktop `lib/premium.ts`, one to one. */
class PremiumClient(private val auth: MillidaAuth, private val catalog: MillidaCatalog) {

    /**
     * A failed subscription address is not the player's problem: the showcase
     * falls back to the paid packs of the catalogue, and only a failure of both
     * reaches the screen.
     */
    @Throws(IOException::class)
    suspend fun showcase(): PremiumShowcase {
        val shown = try {
            Premium.showcase(auth.publicCall("/launcher/premium", "GET", null))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: Exception) {
            null
        }
        if (shown != null && shown.packs.isNotEmpty()) return shown
        return Premium.combine(shown, catalog.premiumPacks())
    }

    @Throws(IOException::class)
    suspend fun pack(base: PremiumPack): PremiumPackDetail {
        requireId(base.id)
        return Premium.detail(auth.publicCall("/launcher/premium/${encode(base.id)}", "GET", null), base)
    }

    /** A catalogue pack has no premium page on the server: its art, video and frames come from the catalogue card. */
    @Throws(IOException::class)
    suspend fun packView(slug: String): PackView {
        requireId(slug)
        return PackView.parse(auth.publicCall("/catalog/packs/${encode(slug)}", "GET", null), slug)
    }

    /** The server answers with the gateway's address; the launcher only opens it. */
    @Throws(IOException::class)
    suspend fun subscribe(planId: String, packId: String): String {
        requireId(packId)
        return catalog.subscribePremium(planId, packId)
    }

    @Throws(IOException::class)
    suspend fun buy(packId: String): String {
        requireId(packId)
        val body = auth.authorized("/launcher/premium/${encode(packId)}/buy", "POST", buildJsonObject { })
        return body.paymentUrl()
    }

    private fun JsonObject.paymentUrl(): String = (this["paymentUrl"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    @Throws(IOException::class)
    private fun requireId(id: String) {
        if (!MillidaCatalog.catalogSlug(id)) throw IOException("некорректный адрес сборки: $id")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
