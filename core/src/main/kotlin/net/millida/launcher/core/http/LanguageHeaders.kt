package net.millida.launcher.core.http

import net.millida.launcher.core.i18n.I18n
import net.millida.launcher.core.i18n.Market
import okhttp3.Request

/**
 * The desktop's langHeaders and marketFor (api.ts): the Millida API answers catalogue, rating and
 * shop texts in the player's language, and keeps a separate server rating per market, picked by
 * header the way cobblo.net asks for the English one.
 */
object LanguageHeaders {
    private const val HOST = "api.millida.net"
    private val MARKET_PATHS = listOf("/v2/rating/", "/v2/launcher/premium")

    fun apply(request: Request): Request {
        if (request.url.host != HOST) return request
        val lang = I18n.code
        val builder = request.newBuilder()
        if (request.header("Accept-Language") == null) builder.header("Accept-Language", lang)
        if (request.header("X-Lang") == null) builder.header("X-Lang", lang)
        val market = marketFor(request.url.encodedPath, I18n.market)
        if (market != null && request.header("X-Market") == null) builder.header("X-Market", market)
        return builder.build()
    }

    fun marketFor(path: String, market: Market): String? {
        if (MARKET_PATHS.none { path.startsWith(it) }) return null
        return if (market == Market.RU) null else "EN"
    }
}
