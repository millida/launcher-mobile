package net.millida.launcher.core.premium

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import net.millida.launcher.core.catalog.MillidaCard
import net.millida.launcher.core.catalog.MillidaCatalog
import net.millida.launcher.core.catalog.MillidaPaid
import net.millida.launcher.core.packs.PhoneBlock
import net.millida.launcher.core.packs.PhoneCard
import net.millida.launcher.core.packs.PhoneFit
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PremiumPeriod {
    MONTH,
    YEAR,
    NONE;

    val suffix: String get() = when (this) {
        MONTH -> "/мес"
        YEAR -> "/год"
        NONE -> ""
    }

    companion object {
        fun of(raw: String?): PremiumPeriod = when (raw) {
            "month" -> MONTH
            "year" -> YEAR
            else -> NONE
        }
    }
}

/** Where a showcase card came from: our catalogue has no price or subscription, its access is a key. */
enum class PackSource { PREMIUM, CATALOG }

data class PremiumTier(
    val id: String,
    val title: String,
    val priceKopecks: Long,
    val period: PremiumPeriod,
    val items: List<String> = emptyList(),
)

/** `manageUrl` is the cancel page: cancelling is never hidden. */
data class PremiumSubscription(
    val active: Boolean,
    val planId: String? = null,
    val paidUntil: String? = null,
    val canceled: Boolean = false,
    val manageUrl: String? = null,
)

data class PremiumPack(
    val id: String,
    val title: String,
    val slug: String? = null,
    val tagline: String? = null,
    val coverUrl: String? = null,
    val modsCount: Int? = null,
    val mcVersion: String? = null,
    val loader: String? = null,
    val online: Int? = null,
    val ageRating: String? = null,
    val inSubscription: Boolean = false,
    val priceKopecks: Long? = null,
    val owned: Boolean = false,
    val downloads: Long? = null,
    val hasServer: Boolean = false,
    val author: String? = null,
    val source: PackSource = PackSource.PREMIUM,
    val acceptsKeys: Boolean = false,
    /** A catalogue pack sold by access (`/catalog/packs` → `accessRequired`). */
    val accessRequired: Boolean = false,
    /** The pack brings its own game and launch; the phone decides on it by [Premium.phoneBlock]. */
    val ownLaunch: Boolean = false,
    /** The client archive, 0 when the list does not say. */
    val sizeBytes: Long = 0,
)

data class PremiumPatch(val date: String?, val text: String)

data class PremiumPackDetail(
    val pack: PremiumPack,
    val screenshots: List<String> = emptyList(),
    val videoUrl: String? = null,
    val includes: List<String> = emptyList(),
    val plans: List<PremiumTier> = emptyList(),
    val patches: List<PremiumPatch> = emptyList(),
    val updatedAt: String? = null,
    val subscription: PremiumSubscription? = null,
    val bundle: PremiumSubscription? = null,
    val partnerName: String? = null,
)

data class PremiumShowcase(
    val subscription: PremiumSubscription? = null,
    val plans: List<PremiumTier> = emptyList(),
    val featuredId: String? = null,
    val packs: List<PremiumPack> = emptyList(),
) {
    /** The level the showcase speaks of: the one the player holds, otherwise the first. */
    val plan: PremiumTier? get() = plans.firstOrNull { it.id == subscription?.planId } ?: plans.firstOrNull()

    /** What the phone shows: a pack that cannot start there stays reachable by link, not on the shelf. */
    private val shown: List<PremiumPack> get() = Premium.forPhone(packs)

    val featured: PremiumPack? get() = shown.firstOrNull { it.id == featuredId } ?: shown.firstOrNull()

    val rest: List<PremiumPack> get() = featured?.let { hero -> shown.filter { it.id != hero.id } } ?: shown
}

data class PackTile(val label: String, val value: String)

/** What the main button of a pack does; the same order of checks as the desktop `BuyButton`. */
sealed interface PremiumAction {
    data class Install(val slug: String) : PremiumAction

    data object Soon : PremiumAction

    data class Subscribe(val plan: PremiumTier) : PremiumAction

    data class Buy(val priceKopecks: Long) : PremiumAction

    data object None : PremiumAction

    /** The pack does not start on a phone: the page says why instead of selling or installing it. */
    data class PcOnly(val reason: String) : PremiumAction
}

data class PriceLine(val main: String, val notes: List<String>)

/**
 * The paid-pack showcase without the network: parsing of `/launcher/premium`
 * and the same labels, prices and access rules the desktop launcher shows,
 * so a price reads the same on both and nothing is invented when a field is
 * missing.
 */
object Premium {
    private val LOADERS = mapOf("fabric" to "Fabric", "forge" to "Forge", "neoforge" to "NeoForge", "quilt" to "Quilt")

    fun showcase(body: JsonElement?): PremiumShowcase {
        val root = body as? JsonObject ?: return PremiumShowcase()
        return PremiumShowcase(
            subscription = (root["subscription"] as? JsonObject)?.let(::subscription),
            plans = tiers(root["plans"]),
            featuredId = root.text("featuredId").ifEmpty { null },
            packs = (root["packs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::pack) },
        )
    }

    /** The detail answer laid over the card the player tapped: what the server leaves out stays as the card said. */
    fun detail(body: JsonElement?, base: PremiumPack): PremiumPackDetail {
        val root = body as? JsonObject ?: return PremiumPackDetail(base)
        val over = pack(root) ?: base
        val merged = base.copy(
            title = over.title.takeIf { root.text("title").isNotEmpty() } ?: base.title,
            slug = over.slug ?: base.slug,
            tagline = over.tagline ?: base.tagline,
            coverUrl = over.coverUrl ?: base.coverUrl,
            modsCount = over.modsCount ?: base.modsCount,
            mcVersion = over.mcVersion ?: base.mcVersion,
            loader = over.loader ?: base.loader,
            online = over.online ?: base.online,
            ageRating = over.ageRating ?: base.ageRating,
            inSubscription = if (root.has("inSubscription")) over.inSubscription else base.inSubscription,
            priceKopecks = over.priceKopecks ?: base.priceKopecks,
            owned = if (root.has("owned")) over.owned else base.owned,
            downloads = over.downloads ?: base.downloads,
            hasServer = if (root.has("hasServer")) over.hasServer else base.hasServer,
            author = over.author ?: base.author,
            acceptsKeys = if (root.has("acceptsKeys")) over.acceptsKeys else base.acceptsKeys,
        )
        return PremiumPackDetail(
            pack = merged,
            screenshots = root.strings("screenshots"),
            videoUrl = root.text("videoUrl").ifEmpty { null },
            includes = root.strings("includes"),
            plans = tiers(root["plans"]),
            patches = (root["patches"] as? JsonArray).orEmpty().mapNotNull { element ->
                val patch = element as? JsonObject ?: return@mapNotNull null
                val text = patch.text("text").ifEmpty { return@mapNotNull null }
                PremiumPatch(patch.text("date").ifEmpty { null }, text)
            },
            updatedAt = root.text("updatedAt").ifEmpty { null },
            subscription = (root["subscription"] as? JsonObject)?.let(::subscription),
            bundle = (root["bundle"] as? JsonObject)?.let(::subscription),
            partnerName = (root["partner"] as? JsonObject)?.text("name")?.ifEmpty { null },
        )
    }

    /** A paid pack of our catalogue as a showcase card: no price, subscription or online there, so none is shown. */
    fun fromCatalog(card: MillidaCard): PremiumPack = PremiumPack(
        id = card.slug,
        slug = card.slug,
        title = card.title,
        tagline = card.summary.ifBlank { null },
        coverUrl = card.cover,
        mcVersion = card.versions.firstOrNull(),
        loader = card.loaders.firstOrNull()?.let(::loaderName),
        downloads = card.downloads.takeIf { it > 0 },
        author = card.author.ifBlank { null },
        source = PackSource.CATALOG,
        accessRequired = card.premium,
        ownLaunch = card.ownLaunch,
    )

    /** `GET /catalog/packs`: every pack of our catalogue, free and sold by access, as hub cards. */
    fun catalogPacks(body: JsonElement?): List<PremiumPack> =
        (body as? JsonArray).orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val slug = row.text("slug").takeIf { MillidaCatalog.catalogSlug(it) } ?: return@mapNotNull null
            PremiumPack(
                id = slug,
                slug = slug,
                title = row.text("title").ifEmpty { slug },
                tagline = row.text("summary").ifEmpty { null },
                coverUrl = row.text("cover").ifEmpty { null },
                mcVersion = row.text("game").ifEmpty { null },
                loader = row.text("loader").ifEmpty { null }?.let(::loaderName),
                downloads = row.long("downloads")?.takeIf { it > 0 },
                hasServer = row.flag("hasServer"),
                author = row.text("author").ifEmpty { null },
                source = PackSource.CATALOG,
                accessRequired = row.flag("accessRequired"),
                ownLaunch = PhoneFit.ownLaunchIn(row),
                sizeBytes = row.long("size")?.takeIf { it > 0 } ?: 0,
            )
        }

    /** The future subscription address first; without packs there, the paid packs our catalogue sells today. */
    fun combine(shown: PremiumShowcase?, catalog: List<MillidaCard>): PremiumShowcase {
        if (shown != null && shown.packs.isNotEmpty()) return shown
        return PremiumShowcase(
            subscription = shown?.subscription,
            plans = shown?.plans.orEmpty(),
            packs = catalog.filter { it.premium }.map(::fromCatalog),
        )
    }

    private val ARCANIA = Regex("arcania", RegexOption.IGNORE_CASE)

    fun isArcania(pack: PremiumPack): Boolean = ARCANIA.containsMatchIn(pack.slug ?: pack.title)

    /**
     * The paid packs of the hub: the showcase first, then the catalogue's packs
     * sold by access that the showcase lacks, one card per pack, Arcania first,
     * and only those that can start on this phone.
     */
    fun exclusives(showcase: List<PremiumPack>, catalog: List<PremiumPack>): List<PremiumPack> {
        val seen = showcase.mapTo(HashSet()) { it.slug ?: it.id }
        val all = forPhone(showcase + catalog.filter { it.accessRequired && seen.add(it.slug ?: it.id) })
        return all.filter(::isArcania) + all.filterNot(::isArcania)
    }

    /** Our free packs, the most downloaded first. */
    fun freePacks(catalog: List<PremiumPack>): List<PremiumPack> =
        forPhone(catalog.filter { !it.accessRequired }).sortedByDescending { it.downloads ?: 0 }

    fun loaderName(raw: String): String = LOADERS[raw.lowercase()] ?: raw

    fun planPrice(plan: PremiumTier): String = MillidaPaid.rubles(plan.priceKopecks) + plan.period.suffix

    fun viaCatalog(pack: PremiumPack): Boolean = pack.source == PackSource.CATALOG && !pack.slug.isNullOrBlank()

    fun hasAccess(pack: PremiumPack, sub: PremiumSubscription?): Boolean =
        pack.owned || (sub?.active == true && pack.inSubscription)

    /** The subscription the player uses now: on this pack alone, or on every pack of its partner. */
    fun liveSubscription(detail: PremiumPackDetail?): PremiumSubscription? = when {
        detail == null -> null
        detail.bundle?.active == true -> detail.bundle
        detail.subscription?.active == true -> detail.subscription
        else -> null
    }

    fun planChoice(detail: PremiumPackDetail?): Boolean = (detail?.plans?.size ?: 0) > 1

    /** Only live numbers of the server, joined by a dot. */
    fun facts(pack: PremiumPack): String = buildList {
        pack.modsCount?.takeIf { it > 0 }?.let { add(count(it.toLong()) + " модов") }
        pack.mcVersion?.takeIf { it.isNotBlank() }?.let(::add)
        pack.loader?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" · ")

    fun priceLabel(pack: PremiumPack, plan: PremiumTier?): String = when {
        pack.owned -> ""
        pack.inSubscription && plan != null -> planPrice(plan)
        (pack.priceKopecks ?: 0) > 0 -> MillidaPaid.rubles(pack.priceKopecks ?: 0)
        else -> ""
    }

    /**
     * A pack with access installs by the catalogue's road: a partner's pack is
     * not on Modrinth, so «Установить» never goes there.
     */
    fun action(
        pack: PremiumPack,
        plan: PremiumTier?,
        sub: PremiumSubscription?,
        phone: PhoneBlock? = phoneBlock(pack),
    ): PremiumAction {
        phone?.let { return PremiumAction.PcOnly(PhoneFit.message(it, pack.title, pack.mcVersion.orEmpty())) }
        val slug = pack.slug?.takeIf { it.isNotBlank() }
        if (viaCatalog(pack)) return PremiumAction.Install(slug ?: return PremiumAction.Soon)
        if (hasAccess(pack, sub)) return slug?.let { PremiumAction.Install(it) } ?: PremiumAction.Soon
        if (pack.inSubscription && plan != null) return PremiumAction.Subscribe(plan)
        val price = pack.priceKopecks ?: 0
        if (price > 0) return PremiumAction.Buy(price)
        return PremiumAction.None
    }

    /** What keeps this pack off the phone, from what its card says; null when it may start. */
    fun phoneBlock(pack: PremiumPack): PhoneBlock? = PhoneFit.cardVerdict(
        PhoneCard(
            loader = pack.loader.orEmpty(),
            gameVersion = pack.mcVersion.orEmpty(),
            ownLaunch = pack.ownLaunch,
            accessRequired = pack.accessRequired,
            sizeBytes = pack.sizeBytes,
        ),
    )

    /** Lists on the phone show only what can start there; a link still opens the page, which says why not. */
    fun forPhone(packs: List<PremiumPack>): List<PremiumPack> = packs.filter { phoneBlock(it) == null }

    /** The catalogue card read for the page knows the launch and the size the list left out. */
    fun withView(pack: PremiumPack, view: PackView?): PremiumPack {
        if (view == null) return pack
        return pack.copy(
            ownLaunch = pack.ownLaunch || view.ownLaunch,
            sizeBytes = pack.sizeBytes.takeIf { it > 0 } ?: view.clientBytes,
            mcVersion = pack.mcVersion ?: view.game.ifEmpty { null },
            loader = pack.loader ?: view.loader.ifEmpty { null }?.let(::loaderName),
        )
    }

    /** The subscription and the one-off price on separate lines, so they never argue; the term is said plainly. */
    fun priceLine(pack: PremiumPack, plan: PremiumTier?, sub: PremiumSubscription?, zone: ZoneId = ZoneId.systemDefault()): PriceLine? {
        if (hasAccess(pack, sub)) {
            val until = untilText(sub?.paidUntil, zone)
            return PriceLine("Доступ открыт", if (sub?.active == true && until.isNotEmpty()) listOf("Подписка до $until") else emptyList())
        }
        val main = priceLabel(pack, plan)
        if (main.isEmpty()) return null
        val notes = buildList {
            val once = pack.priceKopecks ?: 0
            if (pack.inSubscription && plan != null && once > 0) add("или ${MillidaPaid.rubles(once)} разово")
            if (!pack.inSubscription) add("Покупка навсегда")
        }
        return PriceLine(main, notes)
    }

    /** Cancelling sits where the subscription is; null when there is nothing to say. */
    fun cancelNote(sub: PremiumSubscription?, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (sub == null) return null
        if (!sub.active) return "Отмена в любой момент"
        val until = untilText(sub.paidUntil, zone)
        return if (sub.canceled) {
            "Продление выключено" + if (until.isNotEmpty()) ", доступ до $until" else ""
        } else {
            "Списание каждый месяц" + if (until.isNotEmpty()) ", следующее $until" else ""
        }
    }

    fun tiles(pack: PremiumPack, updatedAt: String?, zone: ZoneId = ZoneId.systemDefault()): List<PackTile> = buildList {
        pack.modsCount?.takeIf { it > 0 }?.let { add(PackTile("Модов", count(it.toLong()))) }
        pack.mcVersion?.takeIf { it.isNotBlank() }?.let { add(PackTile("Версия", it)) }
        pack.loader?.takeIf { it.isNotBlank() }?.let { add(PackTile("Загрузчик", it)) }
        pack.online?.let { add(PackTile("Сейчас играют", count(it.toLong()))) }
        pack.downloads?.takeIf { it > 0 }?.let { add(PackTile("Скачиваний", count(it))) }
        pack.author?.takeIf { it.isNotBlank() }?.let { add(PackTile("Собрал", it)) }
        untilText(updatedAt, zone).takeIf { it.isNotEmpty() }?.let { add(PackTile("Обновлено", it)) }
    }

    /** The pack's own server is the only thing the catalogue says about what comes with it. */
    fun includes(detail: PremiumPackDetail): List<String> = when {
        detail.includes.isNotEmpty() -> detail.includes
        detail.pack.hasServer -> listOf("Сервер сборки")
        else -> emptyList()
    }

    fun plans(detail: PremiumPackDetail, fallback: PremiumTier?): List<PremiumTier> =
        detail.plans.ifEmpty { listOfNotNull(fallback) }

    /** The partner promotes the bundle: «all packs» comes first and the single pack second. */
    fun orderedPlans(plans: List<PremiumTier>): List<PremiumTier> = plans.sortedBy { if (it.id == SINGLE_PLAN) 1 else 0 }

    fun planLabel(plan: PremiumTier): String = (if (plan.id == SINGLE_PLAN) "Эта сборка" else "Все сборки") + " · " + planPrice(plan)

    /** «12 октября»; empty for a missing or broken date. */
    fun untilText(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        val raw = iso?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val date = parseDate(raw, zone) ?: return ""
        return date.format(DAY_MONTH)
    }

    fun count(value: Long): String = NumberFormat.getIntegerInstance(RU).format(value)

    private fun parseDate(raw: String, zone: ZoneId): LocalDate? =
        runCatching { Instant.parse(raw).atZone(zone).toLocalDate() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()

    private fun pack(body: JsonObject): PremiumPack? {
        val id = body.text("id").ifEmpty { body.text("slug") }.ifEmpty { return null }
        return PremiumPack(
            id = id,
            title = body.text("title").ifEmpty { id },
            slug = body.text("slug").ifEmpty { null },
            tagline = body.text("tagline").ifEmpty { null },
            coverUrl = body.text("coverUrl").ifEmpty { null },
            modsCount = body.long("modsCount")?.toInt(),
            mcVersion = body.text("mcVersion").ifEmpty { null },
            loader = body.text("loader").ifEmpty { null },
            online = body.long("online")?.toInt(),
            ageRating = body.text("ageRating").ifEmpty { null },
            inSubscription = body.flag("inSubscription"),
            priceKopecks = body.long("priceKopecks"),
            owned = body.flag("owned"),
            downloads = body.long("downloads"),
            hasServer = body.flag("hasServer"),
            author = body.text("author").ifEmpty { null },
            source = if (body.text("source") == "catalog") PackSource.CATALOG else PackSource.PREMIUM,
            acceptsKeys = body.flag("acceptsKeys"),
            ownLaunch = PhoneFit.ownLaunchIn(body),
        )
    }

    private fun tiers(element: JsonElement?): List<PremiumTier> =
        (element as? JsonArray).orEmpty().mapNotNull { item ->
            val plan = item as? JsonObject ?: return@mapNotNull null
            val id = plan.text("id").ifEmpty { return@mapNotNull null }
            PremiumTier(
                id = id,
                title = plan.text("title"),
                priceKopecks = plan.long("priceKopecks") ?: 0,
                period = PremiumPeriod.of(plan.text("period")),
                items = plan.strings("items"),
            )
        }

    private fun subscription(body: JsonObject): PremiumSubscription = PremiumSubscription(
        active = body.flag("active"),
        planId = body.text("planId").ifEmpty { null },
        paidUntil = body.text("paidUntil").ifEmpty { null },
        canceled = body.flag("canceled"),
        manageUrl = body.text("manageUrl").ifEmpty { null },
    )

    private const val SINGLE_PLAN = "pack"
    private val RU = Locale("ru", "RU")
    private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", RU)
}

private fun JsonObject.has(key: String): Boolean = (this[key] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull != null } == true

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
