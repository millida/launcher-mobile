package net.millida.launcher.core.cabinet

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.portal.asRubles
import java.net.URLEncoder

@Serializable
data class WalletBalance(
    val availableKopecks: Long = 0,
    val heldKopecks: Long = 0,
    val withdrawableKopecks: Long = 0,
    val currency: String = "RUB",
)

@Serializable
data class WalletOperation(
    val id: String = "",
    val at: String = "",
    val deltaKopecks: Long = 0,
    val category: String = "",
    val title: String = "",
    val subtitle: String = "",
    val description: String = "",
    val source: String = "",
    val held: Boolean = false,
    val heldUntil: String = "",
) {
    val incoming: Boolean get() = deltaKopecks >= 0
}

@Serializable
data class WalletHistoryPage(
    val items: List<WalletOperation> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
)

/**
 * The wallet as the cabinet shows it. Paying happens on the site: the phone
 * only opens the top-up page, so no payment flow lives here.
 */
class CabinetWalletClient(private val auth: MillidaAuth) {

    suspend fun balance(): WalletBalance {
        val response = auth.cabinetObject("/core/wallet/me/display")
        return WalletBalance(
            availableKopecks = response.number("availableKopecks"),
            heldKopecks = response.number("heldKopecks"),
            withdrawableKopecks = response.number("withdrawableKopecks"),
            currency = response.text("currency").ifEmpty { "RUB" },
        )
    }

    suspend fun history(
        category: String = "",
        limit: Int = 50,
        offset: Int = 0,
    ): WalletHistoryPage {
        val query = buildList {
            add("limit=" + limit.coerceIn(1, 300))
            add("offset=" + offset.coerceAtLeast(0))
            if (category.isNotBlank() && category != "all") add("category=" + encode(category))
        }.joinToString("&")
        val response = auth.cabinetElement("/core/wallet/history?$query")
        val items = response.itemsArray("items").objects().map { entry ->
            WalletOperation(
                id = entry.text("id"),
                at = entry.text("at"),
                deltaKopecks = entry.number("deltaKopecks"),
                category = entry.text("category"),
                title = entry.text("title"),
                subtitle = entry.text("subtitle"),
                description = entry.text("description"),
                source = entry.text("source"),
                held = entry.flag("held"),
                heldUntil = entry.text("heldUntil"),
            )
        }
        val envelope = response as? JsonObject
        return WalletHistoryPage(
            items = items,
            total = envelope?.count("total") ?: items.size,
            offset = envelope?.count("offset") ?: offset,
            limit = envelope?.count("limit") ?: limit,
        )
    }

    /** Top-up is finished on the site; the phone only opens this address. */
    fun topUpUrl(): String = "$MILLIDA_SITE/profile?tab=wallet"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/**
 * Rubles are shown through the one formatter the account chip already uses, so
 * the wallet tab and the chip can never disagree. Only the sign is added here:
 * that formatter cannot render a negative amount on its own.
 */
fun Long.kopecksAsRubles(): String {
    if (this >= 0) return asRubles()
    val magnitude = if (this == Long.MIN_VALUE) Long.MAX_VALUE else -this
    return "-" + magnitude.asRubles()
}

fun WalletBalance.availableText(): String = availableKopecks.kopecksAsRubles()

fun WalletOperation.amountText(): String =
    if (deltaKopecks > 0) "+" + deltaKopecks.kopecksAsRubles() else deltaKopecks.kopecksAsRubles()
