package net.millida.launcher.core

import net.millida.launcher.core.rubies.NightFlips
import net.millida.launcher.core.rubies.NightMarket
import net.millida.launcher.core.rubies.RubyItem
import net.millida.launcher.core.rubies.ShopCard
import net.millida.launcher.core.rubies.ShopDay
import net.millida.launcher.core.rubies.ShopDayPlan
import net.millida.launcher.core.rubies.ShopSource
import net.millida.launcher.core.rubies.WishStatus
import net.millida.launcher.core.rubies.Wishes
import net.millida.launcher.core.rubies.Workshop
import net.millida.launcher.core.rubies.WorkshopOffer
import net.millida.launcher.core.rubies.WorkshopShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Хочу» and the night market read real prices and real money, so what each
 * wished item turns into and what the top-up asks for is pinned.
 *
 * | вход                                         | вердикт                                               |
 * | -------------------------------------------- | ----------------------------------------------------- |
 * | вещь на ночном рынке, не куплена             | SHOP с источником night и ценой со скидкой            |
 * | вещь уже куплена                             | OWNED, в сумму не входит                              |
 * | вещь в мастерской                            | WORKSHOP с ценой в осколках                           |
 * | вещи нигде нет, но есть в каталоге           | RETURNS; нет в каталоге — пропадает                   |
 * | не хватает 10 рубинов                        | докупить 14: служба берёт только кратное 7            |
 * | перевёрнутые карты старого рынка             | не переносятся на новый                               |
 */
class WishesTest {

    private fun item(code: String) = RubyItem(code = code, name = code)

    private fun card(code: String, price: Long, owned: Boolean = false) =
        ShopCard(item = item(code), price = price, basePrice = price * 2, owned = owned)

    @Test
    fun `статус вещи из «Хочу» берётся из магазина, мастерской и каталога`() {
        val day = ShopDay(
            balance = 50,
            day = ShopDayPlan(items = listOf(card("owned", 30, owned = true))),
            nightMarket = NightMarket(endsAt = "2026-10-03T00:00:00Z", cards = listOf(card("night", 60))),
            wishlist = listOf("returns", "craft", "night", "owned", "gone"),
        )
        val workshop = Workshop(workshop = WorkshopShelf(items = listOf(WorkshopOffer(item("craft"), cost = 500))))
        val list = Wishes.of(day, workshop, 50) { code -> if (code == "returns") item(code) else null }

        assertEquals(
            "порядок: что можно взять сейчас — первым; исчезнувшая вещь не показывается",
            listOf("night" to WishStatus.SHOP, "craft" to WishStatus.WORKSHOP, "returns" to WishStatus.RETURNS, "owned" to WishStatus.OWNED),
            list.items.map { it.item.code to it.status },
        )
        assertEquals("ночная вещь покупается с ночной полки", ShopSource.NIGHT, list.items.first().source)
        assertEquals("сумма — по текущей цене только того, что в магазине", 60L, list.total)
        assertEquals("не хватает разницы с балансом", 10L, list.missing)
        assertEquals("осколочная цена мастерской", 500L, list.items[1].cost)
    }

    @Test
    fun `докупка округляется до целых рублей`() {
        val cases = listOf(1L to 7L, 7L to 7L, 10L to 14L, 15L to 21L)
        for ((missing, want) in cases) {
            assertEquals("не хватает $missing: служба падала на некратном 7", want, Wishes.topUpRubies(missing))
        }
    }

    @Test
    fun `перевёрнутые карты живут только в своём рынке`() {
        val stored = NightFlips.encode("2026-10-03T00:00:00Z", setOf("a", "b", "bad,code"))
        assertEquals("тот же рынок — карты остаются открытыми", setOf("a", "b"), NightFlips.decode(stored, "2026-10-03T00:00:00Z"))
        assertTrue("новый рынок начинается рубашкой вверх", NightFlips.decode(stored, "2026-10-10T00:00:00Z").isEmpty())
        assertTrue("пустое хранилище — ничего не открыто", NightFlips.decode(null, "2026-10-03T00:00:00Z").isEmpty())
    }
}
