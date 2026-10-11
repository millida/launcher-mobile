package net.millida.launcher.core.rating

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.mojang.MojangJson
import java.net.URLEncoder

@Serializable
data class RatedServer(
    val name: String = "",
    val slug: String = "",
    val ip: String = "",
    val online: Int = 0,
    @SerialName("isOnline") val up: Boolean = false,
    val avgOnline: Int = 0,
    val shortDesc: String = "",
    val motd: String = "",
    val license: String = "",
    val logoUrl: String? = null,
    val bannerUrl: String? = null,
    val categories: List<String> = emptyList(),
    val versionMajors: List<String> = emptyList(),
    val address: String = "",
    val onlineApprox: Boolean = false,
    val aiDescription: String = "",
    val description: String = "",
    val screenshots: List<String> = emptyList(),
    val websiteUrl: String? = null,
    val discordInviteUrl: String? = null,
    val votes30d: Int = 0,
    val accessType: String = "",
    val bedrockAddress: String? = null,
    val onMillidaHosting: Boolean = false,
) {
    /** The rating hides the address of servers joined through their own launcher or by application. */
    val joinAddress: String get() = address.ifBlank { ip }.trim()

    val summary: String
        get() = shortDesc.ifBlank { aiDescription }.replace('\n', ' ').trim()
}

@Serializable
data class RatingPage(
    val servers: List<RatedServer> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class VersionFacet(
    val version: String = "",
    val servers: Int = 0,
)

enum class RatingSort(val id: String) {
    RATING("rating"),
    ONLINE("online"),
    NEW("new"),
    NAME("name"),
}

enum class LicenseFilter(val id: String?) {
    ANY(null),
    CRACKED("CRACKED"),
    LICENSE("LICENSE"),
}

class RatingClient(
    private val downloader: Downloader,
    private val apiBase: String = MILLIDA_API,
) {
    suspend fun servers(
        search: String = "",
        sort: RatingSort = RatingSort.RATING,
        license: LicenseFilter = LicenseFilter.ANY,
        category: String? = null,
        version: String? = null,
        minOnline: Int = 0,
        offset: Int = 0,
        limit: Int = 30,
    ): RatingPage {
        val url = buildString {
            append(apiBase).append("/rating/servers?limit=").append(limit.coerceIn(1, 50))
            append("&offset=").append(offset.coerceAtLeast(0))
            append("&sort=").append(sort.id)
            license.id?.let { append("&license=").append(it) }
            category?.takeIf { it.isNotBlank() }?.let { append("&category=").append(encode(it)) }
            version?.takeIf { it.isNotBlank() }?.let { append("&version=").append(encode(it.take(16))) }
            if (minOnline > 0) append("&minOnline=").append(minOnline)
            search.trim().takeIf { it.isNotEmpty() }?.let { append("&search=").append(encode(it.take(60))) }
        }
        val body = downloader.text(url)
        return MojangJson.decodeFromString(RatingPage.serializer(), body)
    }

    suspend fun versions(): List<VersionFacet> {
        val body = downloader.text("$apiBase/rating/servers/versions")
        return MojangJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(VersionFacet.serializer()),
            body,
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

private val VERSION_SHAPE = Regex("""\d{1,4}(\.\d{1,4}){0,3}""")

private object VersionOrder : Comparator<String> {
    override fun compare(left: String, right: String): Int {
        val first = left.split('.')
        val second = right.split('.')
        for (part in 0 until maxOf(first.size, second.size)) {
            val one = first.getOrNull(part)?.toIntOrNull() ?: 0
            val other = second.getOrNull(part)?.toIntOrNull() ?: 0
            if (one != other) return one.compareTo(other)
        }
        return 0
    }
}

fun List<VersionFacet>.newestFirst(): List<VersionFacet> = this
    .filter { VERSION_SHAPE.matches(it.version.trim()) }
    .sortedWith(compareByDescending(VersionOrder) { it.version.trim() })

/**
 * Two badges saying "26.2" and "26.1" read as a mistake, not as a range. One
 * badge with the edges of the range says the same thing and says it once.
 */
fun versionRangeLabel(versions: List<String>): String? {
    val known = versions
        .map { it.trim() }
        .filter { VERSION_SHAPE.matches(it) }
        .distinct()
        .sortedWith(VersionOrder)
    return when {
        known.isEmpty() -> null
        known.size == 1 -> known.first()
        else -> "${known.first()}-${known.last()}"
    }
}
