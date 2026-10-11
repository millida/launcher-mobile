package net.millida.launcher.core.skins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.account.MillidaAuth
import java.net.URLEncoder

enum class ShowcaseKind(val wire: String, val title: String) {
    TOP("top", "Популярные"),
    NEW("new", "Новые"),
    RANDOM("random", "Случайные"),
}

data class ShowcaseCard(val name: String, val textureId: String, val model: String, val views: Long)

/**
 * The skin showcase of millida.net, the same list the desktop launcher shows
 * (src/lib/skinShowcase.ts): a skin found on the site has to be found here too.
 * The address takes guests, since the catalogue is browsed before signing up.
 */
class SkinShowcase(private val auth: MillidaAuth) {
    suspend fun list(kind: ShowcaseKind, limit: Int = DEFAULT_LIMIT): List<ShowcaseCard> =
        parse(auth.publicCall("/players/showcase/list?kind=${kind.wire}&limit=$limit", "GET", null))

    companion object {
        const val DEFAULT_LIMIT = 48

        fun parse(body: JsonElement?): List<ShowcaseCard> {
            val rows = body as? JsonArray ?: return emptyList()
            return rows.mapNotNull { row ->
                val item = row as? JsonObject ?: return@mapNotNull null
                val name = (item["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                ShowcaseCard(
                    name = name,
                    textureId = (item["textureId"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                    model = (item["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                    views = (item["views"] as? JsonPrimitive)?.longOrNull ?: 0L,
                )
            }
        }

        private fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** A card without a texture id is taken by nick, exactly as the desktop catalogue does. */
        fun skinUrl(card: ShowcaseCard, apiBase: String = MILLIDA_API): String =
            if (card.textureId.isEmpty()) {
                SkinCatalog.skinUrl(segment(card.name), apiBase)
            } else {
                "${apiBase.trimEnd('/')}/heads/texture/" + segment(card.textureId) + "?kind=skin"
            }

        fun catalogSkin(card: ShowcaseCard): CatalogSkin =
            CatalogSkin(key = "sc:" + card.name, label = card.name, nick = card.name, url = skinUrl(card))
    }
}
