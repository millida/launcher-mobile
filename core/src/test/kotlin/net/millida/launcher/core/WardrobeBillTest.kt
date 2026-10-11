package net.millida.launcher.core

import net.millida.launcher.core.cosmetics.CosmeticItem
import net.millida.launcher.core.cosmetics.CosmeticVariant
import net.millida.launcher.core.cosmetics.WornCosmetic
import net.millida.launcher.core.rubies.WardrobeBill
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The try-on bill charges real rubies, so what lands on it and what the figure
 * wears afterwards is pinned.
 *
 * | вход                                         | вердикт                                               |
 * | -------------------------------------------- | ----------------------------------------------------- |
 * | продаётся за рубины, не куплена              | в счёте, цена в сумме                                 |
 * | уже своя                                     | не в счёте: второй раз не списываем                   |
 * | PLUS / за задание / бесплатная               | не в счёте: за рубины не продаётся                    |
 * | цена 0 или нет цены                          | не в счёте: служба ответит «не продаётся»             |
 * | одна вещь дважды                             | одна строка                                           |
 * | баланс неизвестен / хватает / не хватает     | нехватка 0 / 0 / разница                              |
 * | куплено на занятый слот                      | старая вещь снята, новая надета с расцветкой по умолч.|
 */
class WardrobeBillTest {

    private fun item(id: String, slot: String = "HAT", access: String = "PURCHASE", price: Int? = 100, variants: List<CosmeticVariant> = emptyList()) =
        CosmeticItem(id = id, name = id, slot = slot, access = access, priceRubies = price, variants = variants)

    @Test
    fun `в счёт попадают только продающиеся и не свои`() {
        data class Case(val fitting: List<CosmeticItem>, val owned: Set<String>, val ids: List<String>, val total: Long, val why: String)

        val cases = listOf(
            Case(listOf(item("CAP")), emptySet(), listOf("CAP"), 100, "обычная покупка"),
            Case(listOf(item("CAP"), item("WINGS", slot = "BACK", price = 250)), emptySet(), listOf("CAP", "WINGS"), 350, "сет из двух вещей"),
            Case(listOf(item("CAP")), setOf("CAP"), emptyList(), 0, "своя вещь не продаётся второй раз"),
            Case(listOf(item("HALO", access = "PLUS", price = null)), emptySet(), emptyList(), 0, "PLUS открывает подписка, а не рубины"),
            Case(listOf(item("MEDAL", price = 0)), emptySet(), emptyList(), 0, "без цены служба откажет — в счёт не берём"),
            Case(listOf(item("TOKEN", access = "FREE")), emptySet(), emptyList(), 0, "бесплатное не покупается"),
            Case(listOf(item("CAP"), item("CAP")), emptySet(), listOf("CAP"), 100, "одна вещь не списывается дважды"),
            Case(emptyList(), emptySet(), emptyList(), 0, "пустая примерка — пустой счёт"),
        )
        for (case in cases) {
            val bill = WardrobeBill.of(case.fitting, case.owned)
            assertEquals(case.why, case.ids, bill.items.map { it.id })
            assertEquals(case.why, case.total, bill.total)
        }
    }

    @Test
    fun `нехватка рубинов`() {
        data class Case(val total: Long, val balance: Long?, val missing: Long, val why: String)

        val cases = listOf(
            Case(300, null, 0, "баланс не загрузился — решает служба, а не догадка"),
            Case(300, 300, 0, "ровно хватает"),
            Case(300, 1_000, 0, "с запасом"),
            Case(300, 120, 180, "не хватает разницы — её и предлагаем докупить"),
        )
        for (case in cases) {
            assertEquals(case.why, case.missing, WardrobeBill(emptyList(), case.total).shortfall(case.balance))
        }
    }

    @Test
    fun `купленное встаёт на свои места`() {
        val preferred = CosmeticVariant("default")
        data class Case(val worn: List<WornCosmetic>, val bought: List<CosmeticItem>, val after: List<WornCosmetic>, val why: String)

        val cases = listOf(
            Case(
                listOf(WornCosmetic("OLD_HAT", "HAT"), WornCosmetic("CAPE1", "CAPE")),
                listOf(item("CAP")),
                listOf(WornCosmetic("CAPE1", "CAPE"), WornCosmetic("CAP", "HAT")),
                "новая шляпа вытесняет старую, плащ остаётся",
            ),
            Case(
                emptyList(),
                listOf(item("CAP~red", variants = listOf(CosmeticVariant("red"), preferred))),
                listOf(WornCosmetic("CAP~red", "HAT", "default")),
                "расцветка по умолчанию, как при обычном «Надеть»",
            ),
            Case(
                listOf(WornCosmetic("CAPE1", "CAPE")),
                emptyList(),
                listOf(WornCosmetic("CAPE1", "CAPE")),
                "ничего не куплено — надетое не трогаем",
            ),
        )
        for (case in cases) {
            assertEquals(case.why, case.after, WardrobeBill.dressAfter(case.worn, case.bought))
        }
    }
}
