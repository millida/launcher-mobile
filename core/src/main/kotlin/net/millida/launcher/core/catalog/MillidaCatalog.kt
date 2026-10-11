package net.millida.launcher.core.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.packs.PhoneCard
import net.millida.launcher.core.packs.PhoneFit
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

/** A row of the Millida catalogue as the site's listing returns it. */
data class MillidaCard(
    val slug: String,
    val title: String,
    val summary: String = "",
    val cover: String? = null,
    val icon: String? = null,
    val author: String = "",
    val downloads: Long = 0,
    val versions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val launcherOnly: Boolean = false,
    val pricing: Pricing = Pricing.FREE,
    val priceKopecks: Long? = null,
    /** A pack sold by access (`/catalog/packs` → `accessRequired`): bought by key or on the seller's page. */
    val premium: Boolean = false,
    /** The pack brings its own game and launch (`ownLaunch`, or `protectedContent` in the catalogue's list). */
    val ownLaunch: Boolean = false,
) {
    val paid: Boolean get() = MillidaPaid.isPaid(pricing, priceKopecks)
}

data class MillidaListing(
    val items: List<MillidaCard>,
    val page: Int,
    val pages: Int,
    val total: Int = 0,
)

data class MillidaFile(
    val id: String,
    val version: String = "",
    val gameVersions: List<String> = emptyList(),
    val primaryGameVersion: String = "",
    val loaders: List<String> = emptyList(),
    val fileName: String = "",
    val size: Long = 0,
    val sha1: String = "",
    /** The file lies on our mirror; without it the resolver hands out the author's page, not a file. */
    val mirrored: Boolean = false,
    val releasedAt: String = "",
)

data class MillidaItem(
    val slug: String,
    val title: String,
    val summary: String = "",
    val icon: String? = null,
    val cover: String? = null,
    val author: String = "",
    val sourceUrl: String = "",
    val launcherOnly: Boolean = false,
    val articleSlug: String = "",
    val files: List<MillidaFile> = emptyList(),
    val pricing: Pricing = Pricing.FREE,
    val priceKopecks: Long? = null,
)

data class PremiumPlan(val id: String, val title: String, val priceKopecks: Long, val period: String)

/** A paid pack's offer: subscription levels, a one-off price, and whether its seller issues keys. */
data class PremiumOffer(
    val id: String,
    val plans: List<PremiumPlan> = emptyList(),
    val acceptsKeys: Boolean = false,
    val priceKopecks: Long? = null,
    val owned: Boolean = false,
) {
    /** The desktop shows the level buttons only when there is a real choice between them. */
    val planChoice: Boolean get() = plans.size > 1

    fun priceLabel(): String =
        if (owned) "" else priceKopecks?.takeIf { it > 0 }?.let { MillidaPaid.rubles(it) }.orEmpty()
}

data class AccessAnswer(val open: Boolean, val livePriceKopecks: Long?)

/**
 * The Millida catalogue the site shows at millida.net/modpacks: the same
 * addresses, so sections, prices and access agree with the site to the digit.
 */
class MillidaCatalog(private val auth: MillidaAuth) {

    @Throws(IOException::class)
    suspend fun listing(section: String, query: String, page: Int, filters: MillidaFilters = MillidaFilters()): MillidaListing {
        val params = listingParams(section, query, page, filters)
        val body = auth.publicCall("/catalog/listing?$params", "GET", null) as? JsonObject
            ?: throw IOException("каталог Millida ответил не тем — попробуй позже")
        val items = (body["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::card) }
        return MillidaListing(
            items = items,
            page = body.long("page")?.toInt() ?: page,
            pages = body.long("pages")?.toInt() ?: page,
            total = body.long("total")?.toInt() ?: items.size,
        )
    }

    /** The section's versions and categories with their counts, as the site's filter column shows them. */
    @Throws(IOException::class)
    suspend fun facets(section: String, version: String?): MillidaFacets {
        val params = buildList {
            add("section=" + encode(section))
            version?.takeIf { it.isNotBlank() }?.let { add("version=" + encode(it)) }
        }.joinToString("&")
        val body = auth.publicCall("/catalog/facets?$params", "GET", null) as? JsonObject
            ?: throw IOException("фильтры каталога не загрузились — попробуй позже")
        return MillidaFacets(versions = facetsOf(body["versions"]), categories = facetsOf(body["categories"]))
    }

    /** Slugs of the packs sold by access, with the row a pack gets when the listing page lacks it. */
    @Throws(IOException::class)
    suspend fun premiumPacks(): List<MillidaCard> {
        val body = auth.publicCall("/catalog/packs", "GET", null)
        return (body as? JsonArray).orEmpty().mapNotNull { element ->
            val pack = element as? JsonObject ?: return@mapNotNull null
            if (pack.flag("accessRequired") != true) return@mapNotNull null
            val slug = pack.text("slug").takeIf { catalogSlug(it) } ?: return@mapNotNull null
            MillidaCard(
                slug = slug,
                title = pack.text("title").ifEmpty { slug },
                summary = pack.text("summary"),
                cover = pack.text("cover").ifEmpty { null },
                author = pack.text("author"),
                downloads = pack.long("downloads") ?: 0,
                versions = listOfNotNull(pack.text("game").ifEmpty { null }),
                loaders = listOfNotNull(pack.text("loader").ifEmpty { null }),
                launcherOnly = true,
                premium = true,
                ownLaunch = PhoneFit.ownLaunchIn(pack),
            )
        }
    }

    @Throws(IOException::class)
    suspend fun item(slug: String): MillidaItem {
        requireSlug(slug)
        val body = auth.publicCall("/catalog/items/${encode(slug)}", "GET", null) as? JsonObject
            ?: throw IOException("в каталоге Millida нет «$slug»")
        val files = (body["files"] as? JsonArray).orEmpty().mapNotNull { element ->
            val file = element as? JsonObject ?: return@mapNotNull null
            val id = file.text("id").ifEmpty { return@mapNotNull null }
            MillidaFile(
                id = id,
                version = file.text("version"),
                gameVersions = file.strings("gameVersions"),
                primaryGameVersion = file.text("primaryGameVersion"),
                loaders = file.strings("loaders").map { it.lowercase() },
                fileName = file.text("fileName"),
                size = file.long("size") ?: 0,
                sha1 = file.text("sha1"),
                mirrored = file.flag("mirrored") == true,
                releasedAt = file.text("releasedAt"),
            )
        }
        return MillidaItem(
            slug = body.text("slug").ifEmpty { slug },
            title = body.text("title").ifEmpty { slug },
            summary = body.text("summary"),
            icon = body.text("icon").ifEmpty { null },
            cover = body.text("cover").ifEmpty { null },
            author = body.text("author"),
            sourceUrl = body.text("sourceUrl"),
            launcherOnly = body.flag("launcherOnly") == true,
            articleSlug = body.text("articleSlug"),
            files = files,
            pricing = Pricing.of(body.text("pricing")),
            priceKopecks = body.long("priceKopecks"),
        )
    }

    @Throws(IOException::class)
    suspend fun access(slug: String): AccessAnswer {
        requireSlug(slug)
        val body = auth.authorized("/catalog/items/${encode(slug)}/access", "GET", null)
        return AccessAnswer(MillidaPaid.accessOpen(body), MillidaPaid.livePriceKopecks(body))
    }

    /** Charged from the Millida wallet; the key makes a retry after a dropped connection charge once. */
    @Throws(IOException::class)
    suspend fun buy(slug: String, idempotencyKey: String, expectedPriceKopecks: Long) {
        requireSlug(slug)
        auth.authorized(
            "/catalog/items/${encode(slug)}/buy",
            "POST",
            buildJsonObject {
                put("idempotencyKey", idempotencyKey)
                put("expectedPriceKopecks", expectedPriceKopecks)
            },
        )
    }

    /** A subscription is paid at the gateway: the answer is the address to open in the browser. */
    @Throws(IOException::class)
    suspend fun subscribe(slug: String): String {
        requireSlug(slug)
        val body = auth.authorized("/catalog/items/${encode(slug)}/subscribe", "POST", buildJsonObject { })
        return body.text("paymentUrl")
    }

    @Throws(IOException::class)
    suspend fun purchases(): List<Purchase> =
        MillidaPaid.purchasesFrom(auth.authorizedElement("/catalog/purchases/me", "GET", null))

    @Throws(IOException::class)
    suspend fun redeem(slug: String, code: String) {
        requireSlug(slug)
        val key = code.trim()
        if (key.length < MIN_KEY || key.length > MAX_KEY || key.any { it.isISOControl() }) {
            throw IOException("ключ не похож на настоящий — проверь, что скопировал его целиком")
        }
        auth.authorized("/catalog/packs/${encode(slug)}/redeem", "POST", buildJsonObject { put("code", key) })
    }

    /** Null when the pack is sold without levels: the key and the seller's page are then the whole offer. */
    suspend fun premiumOffer(slug: String): PremiumOffer? {
        if (!catalogSlug(slug)) return null
        val body = try {
            auth.publicCall("/launcher/premium/${encode(slug)}", "GET", null) as? JsonObject
        } catch (unknown: IOException) {
            null
        } ?: return null
        val plans = (body["plans"] as? JsonArray).orEmpty().mapNotNull { element ->
            val plan = element as? JsonObject ?: return@mapNotNull null
            val id = plan.text("id").ifEmpty { return@mapNotNull null }
            PremiumPlan(id, plan.text("title"), plan.long("priceKopecks") ?: 0, plan.text("period"))
        }
        return PremiumOffer(
            id = body.text("id").ifEmpty { slug },
            plans = plans,
            acceptsKeys = body.flag("acceptsKeys") == true,
            priceKopecks = body.long("priceKopecks"),
            owned = body.flag("owned") == true,
        )
    }

    @Throws(IOException::class)
    suspend fun subscribePremium(planId: String, packId: String): String {
        val body = auth.authorized(
            "/launcher/premium/subscribe",
            "POST",
            buildJsonObject {
                put("planId", planId)
                put("packId", packId)
            },
        )
        return body.text("paymentUrl")
    }

    private fun card(body: JsonObject): MillidaCard? {
        val slug = body.text("slug").takeIf { catalogSlug(it) } ?: return null
        return MillidaCard(
            slug = slug,
            title = displayName(body.text("title").ifEmpty { slug }),
            summary = body.text("summary"),
            cover = body.text("cover").ifEmpty { null },
            icon = body.text("icon").ifEmpty { null },
            author = body.text("author"),
            downloads = body.long("downloads") ?: 0,
            versions = body.strings("versions"),
            loaders = body.strings("loaders").map { it.lowercase() },
            launcherOnly = body.flag("launcherOnly") == true,
            pricing = Pricing.of(body.text("pricing")),
            priceKopecks = body.long("priceKopecks"),
            ownLaunch = PhoneFit.ownLaunchIn(body),
        )
    }

    @Throws(IOException::class)
    private fun requireSlug(slug: String) {
        if (!catalogSlug(slug)) throw IOException("некорректный адрес материала: $slug")
    }

    companion object {
        const val PER_PAGE = 20
        private const val MIN_KEY = 8
        private const val MAX_KEY = 128
        private const val ARCANIA = "arcania"
        private val SLUG = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")
        private val MODRINTH_HOST = Regex("^(www\\.)?modrinth\\.com$", RegexOption.IGNORE_CASE)
        private val TITLE_DOWNLOAD = Regex("\\s*[—–-]\\s*скач(?:ать|ай)(?:\\s.*)?$", RegexOption.IGNORE_CASE)
        private val TITLE_KIND = Regex(
            "\\s*[—–-]\\s*(?:плагин|мод|карта|сборка|шейдер|ресурс-?пак|дата-?пак)\\s*$",
            RegexOption.IGNORE_CASE,
        )
        private val TITLE_GAME = Regex(
            "\\s+(?:для|под)\\s+(?:minecraft|майнкрафт)(?:\\s+(?:pe|java|bedrock))?(?:\\s+[0-9][0-9a-z.+-]*)?\\s*$",
            RegexOption.IGNORE_CASE,
        )

        fun catalogSlug(slug: String): Boolean = SLUG.matches(slug)

        fun newIdempotencyKey(): String = UUID.randomUUID().toString()

        /** A title written for search engines ("Iris — скачать мод на Fabric") shown as a name. */
        fun displayName(title: String): String {
            val base = title.replace(TITLE_DOWNLOAD, "").trim().ifEmpty { title }
            val noKind = base.replace(TITLE_KIND, "").trim().ifEmpty { base }
            return noKind.replace(TITLE_GAME, "").trim().ifEmpty { noKind }
        }

        /** The Modrinth slug behind a mirrored card: the phone installs such a pack by the Modrinth road. */
        fun modrinthSlugOf(sourceUrl: String?): String? {
            val uri = runCatching { URI(sourceUrl.orEmpty().trim()) }.getOrNull() ?: return null
            if (!"https".equals(uri.scheme, ignoreCase = true)) return null
            if (!MODRINTH_HOST.matches(uri.host.orEmpty())) return null
            val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
            val slug = parts.getOrNull(1) ?: return null
            return slug.takeIf { SLUG.matches(it) }
        }

        /**
         * The modpack file a link or a tap means: the one made for the version and
         * loader the site button named, mirrored first; otherwise the newest.
         */
        fun modpackFile(files: List<MillidaFile>, versions: List<String>, loaders: List<String>): MillidaFile? {
            val version = versions.firstOrNull().orEmpty()
            val loader = loaders.firstOrNull().orEmpty()
            val fit = files.filter { file ->
                (version.isEmpty() || version in file.gameVersions) &&
                    (loader.isEmpty() || (loader != "vanilla" && (file.loaders.isEmpty() || loader in file.loaders ||
                        (loader == "quilt" && "fabric" in file.loaders))))
            }
            return fit.firstOrNull { it.mirrored } ?: fit.firstOrNull() ?: files.firstOrNull()
        }

        /**
         * In the popular feed only the showcase pack is pinned first; the other
         * paid packs stay mixed with the free ones where the listing put them.
         */
        fun pinShowcase(page: List<MillidaCard>, premium: List<MillidaCard>, query: String): List<MillidaCard> {
            val marked = premium.associateBy { it.slug }
            val merged = page.map { card ->
                val pack = marked[card.slug] ?: return@map card
                card.copy(premium = true, ownLaunch = card.ownLaunch || pack.ownLaunch)
            }
            val needle = query.trim().lowercase().takeIf { it.length >= 2 }
            val showcase = merged.firstOrNull { it.slug == ARCANIA }
                ?: marked[ARCANIA]?.takeIf { pack ->
                    needle == null || (pack.title + " " + pack.summary).lowercase().contains(needle)
                }
                ?: return merged
            return listOf(showcase) + merged.filter { it.slug != ARCANIA }
        }

        /**
         * Whether a row may be listed on this phone: some loader and version of
         * it passes [PhoneFit.listed]. A row that names none is unknown and stays.
         */
        fun phoneListed(
            card: MillidaCard,
            legacyRuntime: Boolean = PhoneFit.legacyRuntime(),
            deviceTotalMb: Int = PhoneFit.deviceTotalMb(),
        ): Boolean {
            val loaders = card.loaders.ifEmpty { listOf("") }
            val versions = card.versions.ifEmpty { listOf("") }
            return loaders.any { loader ->
                versions.any { version ->
                    PhoneFit.listed(PhoneCard(loader, version, card.ownLaunch, card.premium), legacyRuntime, deviceTotalMb)
                }
            }
        }

        fun forPhone(cards: List<MillidaCard>): List<MillidaCard> = cards.filter { phoneListed(it) }

        /** The site's listing query: «Рекомендуемые» goes without a sort, «Популярные» and «Новые» as a parameter. */
        fun listingParams(section: String, query: String, page: Int, filters: MillidaFilters): String = buildList {
            add("section=" + encode(section))
            filters.version?.takeIf { it.isNotBlank() }?.let { add("version=" + encode(it)) }
            filters.category?.takeIf { it.isNotBlank() }?.let { add("category=" + encode(it)) }
            query.trim().takeIf { it.length >= 2 }?.let { add("q=" + encode(it)) }
            filters.sort?.wire?.let { add("sort=$it") }
            if (page > 1) add("page=$page")
            add("perPage=$PER_PAGE")
        }.joinToString("&")

        fun facetsOf(element: JsonElement?): List<MillidaFacet> = (element as? JsonArray).orEmpty().mapNotNull { row ->
            val value = (row as? JsonObject)?.text("value")?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            MillidaFacet(value, row.long("count")?.toInt() ?: 0)
        }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

enum class MillidaSort(val wire: String, val label: String) {
    POPULAR("popular", "Популярные"),
    NEW("new", "Новые"),
}

data class MillidaFilters(val sort: MillidaSort? = null, val version: String? = null, val category: String? = null) {
    val active: Boolean get() = sort != null || !version.isNullOrBlank() || !category.isNullOrBlank()
}

data class MillidaFacet(val value: String, val count: Int)

data class MillidaFacets(val versions: List<MillidaFacet> = emptyList(), val categories: List<MillidaFacet> = emptyList())

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
