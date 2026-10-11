package net.millida.launcher.core.cabinet

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import net.millida.launcher.core.account.MillidaAuth
import java.net.URLEncoder

@Serializable
data class NewsItem(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val publishedAt: String = "",
    val imageUrl: String = "",
    val link: String = "",
    val sourceName: String = "",
    val sourceKind: String = "",
    val isWipe: Boolean = false,
    val wipeAt: String = "",
    val keywords: List<String> = emptyList(),
)

@Serializable
data class NewsPage(
    val items: List<NewsItem> = emptyList(),
    val total: Int = 0,
)

/**
 * The update radar: posts servers and hostings publish about themselves. The
 * feed is a public endpoint, but it is reached through the same authorised call
 * as the rest of the cabinet so one lapsed session gives one verdict.
 */
class CabinetNewsClient(private val auth: MillidaAuth) {

    suspend fun feed(
        limit: Int = 20,
        offset: Int = 0,
        keyword: String = "",
        serverSlug: String = "",
        wipesOnly: Boolean = false,
        entity: String = "",
    ): NewsPage {
        val query = buildList {
            add("limit=" + limit.coerceIn(1, 100))
            add("offset=" + offset.coerceAtLeast(0))
            if (keyword.isNotBlank()) add("keyword=" + encode(keyword))
            if (serverSlug.isNotBlank()) add("server=" + encode(serverSlug))
            if (wipesOnly) add("wipes=1")
            if (entity == "server" || entity == "hosting") add("entity=$entity")
        }.joinToString("&")
        val response = auth.cabinetElement("/rating/news?$query")
        val items = response.itemsArray("items").objects().map(::item)
        return NewsPage(
            items = items,
            total = (response as? JsonObject)?.count("total") ?: items.size,
        )
    }

    private fun item(entry: JsonObject): NewsItem {
        val owner = entry.child("entity")
        val body = entry.text("text").trim()
        val href = owner?.text("href").orEmpty()
        return NewsItem(
            id = entry.text("id"),
            title = headline(body).ifEmpty { owner?.text("name").orEmpty() },
            body = body,
            publishedAt = entry.text("publishedAt"),
            imageUrl = owner?.text("logoUrl").orEmpty(),
            link = entry.text("sourceUrl").ifEmpty { if (href.isEmpty()) "" else MILLIDA_SITE + href },
            sourceName = owner?.text("name").orEmpty(),
            sourceKind = owner?.text("kind").orEmpty(),
            isWipe = entry.flag("isWipe"),
            wipeAt = entry.text("wipeAt"),
            keywords = entry.strings("keywords"),
        )
    }

    /**
     * Posts arrive as one block of text with no title of their own, so the card
     * takes the first line and the body keeps the whole post.
     */
    private fun headline(body: String): String {
        val first = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (first.length <= HEADLINE_LIMIT) return first
        val cut = first.take(HEADLINE_LIMIT)
        val space = cut.lastIndexOf(' ')
        return (if (space > HEADLINE_LIMIT / 2) cut.take(space) else cut).trimEnd() + "…"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val HEADLINE_LIMIT = 90
    }
}
