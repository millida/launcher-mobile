package net.millida.launcher.core.rubies

import net.millida.launcher.core.cosmetics.CosmeticItem
import net.millida.launcher.core.cosmetics.WornCosmetic
import net.millida.launcher.core.cosmetics.defaultVariant

/**
 * What the try-on costs, as the desktop wardrobe counts it (Skins.tsx,
 * fittingBill): only pieces sold for rubies and not owned yet. PLUS pieces and
 * pieces a channel hands out stay on the figure but are not on the bill.
 */
data class WardrobeBill(val items: List<CosmeticItem>, val total: Long) {

    val isEmpty: Boolean get() = items.isEmpty()

    /** Unknown balance is not a shortfall: the service checks it and answers anyway. */
    fun shortfall(balance: Long?): Long = if (balance == null) 0L else (total - balance).coerceAtLeast(0L)

    companion object {
        fun of(fitting: List<CosmeticItem>, owned: Set<String>): WardrobeBill {
            val items = fitting.filter { it.onSale && it.id !in owned }.distinctBy { it.id }
            return WardrobeBill(items, items.sumOf { (it.priceRubies ?: 0).toLong() })
        }

        /** The set after a purchase: bought pieces take their slots, everything else stays as worn. */
        fun dressAfter(worn: List<WornCosmetic>, bought: List<CosmeticItem>): List<WornCosmetic> {
            val slots = bought.map { it.slot }.toSet()
            return worn.filterNot { it.slot in slots } +
                bought.distinctBy { it.slot }.map { WornCosmetic(it.id, it.slot, defaultVariant(it.variants)?.name.orEmpty()) }
        }
    }
}
