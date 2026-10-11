package net.millida.launcher.core.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import java.net.URLEncoder
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale

enum class Pricing {
    FREE,
    ONE_TIME,
    SUBSCRIPTION;

    companion object {
        fun of(raw: String?): Pricing = when (raw) {
            "ONE_TIME" -> ONE_TIME
            "SUBSCRIPTION" -> SUBSCRIPTION
            else -> FREE
        }
    }
}

/** What a failed purchase, subscription or file link means for the player. */
enum class PaidVerdict { LOGIN, FUNDS, PRICE_CHANGED, NO_ACCESS, OWNED, FROZEN, BUSY, ERROR }

/** A row of «Мои покупки»: one per item, newest first. */
data class Purchase(
    val id: String,
    val slug: String,
    val title: String,
    val type: String,
    val iconUrl: String?,
    val kind: String,
    val status: String,
    val amountKopecks: Long,
    val accessUntil: String?,
    val createdAt: String?,
) {
    val section: String? get() = MillidaPaid.sectionOfType(type)
}

/**
 * Paid catalogue items without the network: the same answers, verdicts and
 * labels the desktop launcher and the site use, so a price reads the same
 * everywhere and a refusal leads to the same next step.
 */
object MillidaPaid {
    const val WALLET_URL = "https://millida.net/profile?tab=wallet"

    private val PAY_DOMAINS = listOf("antilopay.com", "enot.io", "millida.net")

    private val TYPE_SECTION = mapOf(
        "MOD" to "mods",
        "MODPACK" to "modpacks",
        "TEXTURE_PACK" to "texture-packs",
        "SHADER" to "shaders",
        "DATA_PACK" to "data-packs",
        "MAP" to "maps",
    )

    private val RULES: List<Pair<Regex, PaidVerdict>> = listOf(
        Regex("^http 401$|^unauthorized$|войдите в аккаунт|войди в аккаунт|сессия millida|вход в millida", RegexOption.IGNORE_CASE) to PaidVerdict.LOGIN,
        Regex("^http 402$|insufficient_funds|не хватает (денег|средств)", RegexOption.IGNORE_CASE) to PaidVerdict.FUNDS,
        Regex("price_changed|цена изменилась", RegexOption.IGNORE_CASE) to PaidVerdict.PRICE_CHANGED,
        Regex("already_owned|доступ уже есть|это ваш материал|own_item", RegexOption.IGNORE_CASE) to PaidVerdict.OWNED,
        Regex("balance_frozen|баланс заморожен", RegexOption.IGNORE_CASE) to PaidVerdict.FROZEN,
        Regex("in_progress|покупка уже идёт", RegexOption.IGNORE_CASE) to PaidVerdict.BUSY,
        Regex(
            "^http 403$|no_access|доступен после покупки|нет доступа|доступ истёк|доступна по подписке|подписка .*закончилась",
            RegexOption.IGNORE_CASE,
        ) to PaidVerdict.NO_ACCESS,
    )

    fun isPaid(pricing: Pricing, kopecks: Long?): Boolean =
        pricing != Pricing.FREE && kopecks != null && kopecks > 0

    fun rubles(kopecks: Long): String {
        val format = NumberFormat.getNumberInstance(Locale("ru", "RU"))
        if (kopecks % 100 == 0L) {
            format.maximumFractionDigits = 0
        } else {
            format.minimumFractionDigits = 2
            format.maximumFractionDigits = 2
        }
        return format.format(kopecks / 100.0) + " ₽"
    }

    /** «300 ₽» for a purchase, «300 ₽/мес» for a subscription, empty for a free item. */
    fun priceTag(pricing: Pricing, kopecks: Long?): String {
        if (!isPaid(pricing, kopecks)) return ""
        return rubles(kopecks ?: 0) + if (pricing == Pricing.SUBSCRIPTION) "/мес" else ""
    }

    fun buyLabel(pricing: Pricing): String = if (pricing == Pricing.SUBSCRIPTION) "Подписаться" else "Купить"

    /** `/catalog/items/:slug/access`: an owner has access too. */
    fun accessOpen(body: JsonObject): Boolean =
        body.flag("hasAccess") || body.flag("isOwner")

    /** The price the server charges right now; the listing is a shared cache and may remember an older one. */
    fun livePriceKopecks(body: JsonObject): Long? {
        val pricing = Pricing.of(body.text("pricing"))
        val price = (body["priceKopecks"] as? JsonPrimitive)?.longOrNull
        return price?.takeIf { pricing != Pricing.FREE && it > 0 }
    }

    /** How much the wallet lacks; null when the balance is unknown and the server decides. */
    fun shortfallKopecks(price: Long, balance: Long?): Long? = balance?.let { (price - it).coerceAtLeast(0) }

    fun verdict(message: String?): PaidVerdict {
        val raw = message.orEmpty().trim()
        return RULES.firstOrNull { (rule, _) -> rule.containsMatchIn(raw) }?.second ?: PaidVerdict.ERROR
    }

    /** A short sentence for a toast; null when the verdict has a screen of its own. */
    fun verdictText(verdict: PaidVerdict): String? = when (verdict) {
        PaidVerdict.LOGIN -> "Войди в аккаунт Millida"
        PaidVerdict.FUNDS -> "Не хватает денег на балансе"
        PaidVerdict.PRICE_CHANGED -> "Цена изменилась — проверь и подтверди"
        PaidVerdict.NO_ACCESS -> "Сначала купи — потом установка"
        PaidVerdict.OWNED -> "Уже куплено"
        PaidVerdict.FROZEN -> "Баланс заморожен — напиши в поддержку"
        PaidVerdict.BUSY -> "Покупка уже идёт — подожди минуту"
        PaidVerdict.ERROR -> null
    }

    /**
     * A dropped connection may already have charged the wallet, so the retry
     * goes with the same idempotency key instead of a fresh one.
     */
    fun transient(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(8).toList()
        if (chain.any { it is UnknownHostException || it is SocketException || it is InterruptedIOException }) return true
        val message = error.message.orEmpty()
        return Regex("сервер ответил (5\\d\\d|429)").containsMatchIn(message) ||
            message.contains("too many requests", ignoreCase = true)
    }

    fun sectionOfType(type: String): String? = TYPE_SECTION[type]

    /** Purchases answer → rows: no refunds, no bundles, one row per item, expired access dropped. */
    fun purchasesFrom(body: JsonElement?, now: Instant = Instant.now()): List<Purchase> {
        val rows = (body as? JsonArray).orEmpty()
        val seen = HashSet<String>()
        val out = ArrayList<Purchase>()
        rows.forEach { element ->
            val row = element as? JsonObject ?: return@forEach
            val item = row["item"] as? JsonObject ?: return@forEach
            val slug = item.text("slug")
            if (slug.isEmpty()) return@forEach
            val status = row.text("status")
            if (status == "REFUNDED" || status == "FAILED" || status == "PENDING") return@forEach
            val type = item.text("type")
            if (sectionOfType(type) == null) return@forEach
            val until = row.text("accessUntil").ifEmpty { null }
            val untilAt = until?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (untilAt != null && untilAt.isBefore(now)) return@forEach
            if (!seen.add(slug)) return@forEach
            out += Purchase(
                id = row.text("id").ifEmpty { slug },
                slug = slug,
                title = item.text("title").ifEmpty { slug },
                type = type,
                iconUrl = item.text("iconUrl").ifEmpty { null },
                kind = row.text("kind").ifEmpty { "ONE_TIME" },
                status = status,
                amountKopecks = (row["amountKopecks"] as? JsonPrimitive)?.longOrNull ?: 0,
                accessUntil = until,
                createdAt = row.text("createdAt").ifEmpty { null },
            )
        }
        return out
    }

    /** Where the launcher may send a player to pay: the payment gateways and the site itself. */
    fun isPaymentUrl(url: String?): Boolean {
        val uri = runCatching { URI(url.orEmpty().trim()) }.getOrNull() ?: return false
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        return PAY_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    /** The seller's page with the launcher's mark, the same the desktop key window opens. */
    fun packBuyLink(url: String, slug: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
        val marks = listOf("utm_source" to "launcher", "utm_medium" to "key_banner", "utm_campaign" to slug)
            .joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, "UTF-8") }
        val kept = uri.rawQuery.orEmpty().split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') !in setOf("utm_source", "utm_medium", "utm_campaign") }
        val query = (kept + marks).joinToString("&")
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "https://" + uri.rawAuthority + uri.rawPath.orEmpty() + "?" + query + fragment
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.flag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
