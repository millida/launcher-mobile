package net.millida.launcher.core.premium

/**
 * «Ещё популярные премиум-сборки» at the foot of a pack page, as on the desktop
 * (components/premium/PremiumMore.tsx): best sellers first in the desktop's
 * order, then by downloads. Arcania is the studio's own pack; the rest are
 * translations, which is what the card says about them.
 */
object PremiumMore {

    const val LIMIT = 3

    private val BEST_SELLERS: List<Regex> = listOf(
        "^arcania", "homestead", "deceasedcraft", "nightfallcraft", "prominence", "dread", "create chronicles", "linggango",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val OWN = Regex("^arcania", RegexOption.IGNORE_CASE)

    fun isOwn(pack: PremiumPack): Boolean = OWN.containsMatchIn(pack.slug.orEmpty()) || OWN.containsMatchIn(pack.title)

    fun pick(all: List<PremiumPack>, current: String?): List<PremiumPack> =
        all.withIndex()
            .filter { (_, pack) -> !pack.slug.isNullOrBlank() && pack.slug != current && !pack.coverUrl.isNullOrBlank() }
            .sortedWith(
                compareBy<IndexedValue<PremiumPack>> { rank(it.value) }
                    .thenByDescending { it.value.downloads ?: 0L }
                    .thenBy { it.index },
            )
            .take(LIMIT)
            .map { it.value }

    private fun rank(pack: PremiumPack): Int {
        val hit = BEST_SELLERS.indexOfFirst { it.containsMatchIn(pack.title) || it.containsMatchIn(pack.slug.orEmpty()) }
        return if (hit < 0) BEST_SELLERS.size else hit
    }
}
