package net.millida.launcher

import net.millida.launcher.core.rubies.CraftFragments
import net.millida.launcher.core.rubies.CraftResult
import net.millida.launcher.core.rubies.FragmentProgress
import net.millida.launcher.core.rubies.RubyItem
import net.millida.launcher.core.rubies.WelcomeGift
import net.millida.launcher.core.rubies.WorkshopOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShopRevealTest {

    private val hat = RubyItem(code = "hat_crown", name = "Корона", slot = "HAT", rarity = "EPIC")

    @Test
    fun `тир сундука по названию из сервиса игрового времени`() {
        data class Case(val name: String?, val expected: String?, val why: String)
        listOf(
            Case(null, null, "без названия нечего рисовать"),
            Case("   ", null, "пробелы — тоже без названия"),
            Case("Легендарный сундук", "LEGEND", "легендарный узнаётся по началу слова"),
            Case("  ЭПИЧЕСКИЙ ", "EPIC", "регистр и пробелы не мешают"),
            Case("Редкий", "RARE", "редкий"),
            Case("Обычный сундук", "COMMON", "обычный"),
            Case("Mystery", "COMMON", "незнакомое название рисуется обычным сундуком, а не пропадает"),
        ).forEach { case ->
            assertEquals("«${case.name}»: ${case.why}", case.expected, chestTierOfName(case.name))
        }
    }

    @Test
    fun `подарок новичку без свежего предмета не показывается`() {
        assertNull("без предмета карточки нет", welcomeCard(WelcomeGift(item = null)))
        assertNull("предмет без кода — не подарок", welcomeCard(WelcomeGift(item = RubyItem(code = " "))))
        assertNull("сервис явно не выдал — карточку не показываем", welcomeCard(WelcomeGift(item = hat, granted = false)))
    }

    @Test
    fun `подарок новичку осколками показывает полосу сбора`() {
        val card = welcomeCard(
            WelcomeGift(item = hat, granted = true, fragments = FragmentProgress(have = 3, need = 10, topUp = 70)),
        )

        assertEquals("осколки показываются осколками, а не целым предметом", RewardKind.FRAGMENTS, card?.kind)
        assertEquals("сумма — сколько осколков у игрока", 3L, card?.amount)
        assertEquals(3, card?.have)
        assertEquals(10, card?.need)
        assertEquals("прирост равен выданному", 3, card?.gain)
        assertEquals("цена докупки доходит до карточки", 70L, card?.topUp)
        assertEquals("редкость берётся у предмета", "EPIC", card?.rarity)
        assertEquals(hat, card?.item)
    }

    @Test
    fun `подарок новичку целым предметом`() {
        data class Case(val gift: WelcomeGift, val why: String)
        listOf(
            Case(WelcomeGift(item = hat), "без осколков сервис выдал предмет целиком"),
            Case(WelcomeGift(item = hat, fragments = FragmentProgress(have = 0, need = 0)), "нулевая цель сбора — тоже целый предмет"),
        ).forEach { case ->
            val card = welcomeCard(case.gift)
            assertEquals(case.why, RewardKind.ITEM, card?.kind)
            assertEquals(case.why, hat, card?.item)
            assertEquals(case.why, "EPIC", card?.rarity)
        }
    }

    @Test
    fun `обмен осколков в мастерской показывает фрагменты, а не целую вещь`() {
        data class Case(val made: CraftResult, val kind: String, val gain: Int, val topUp: Long, val why: String)
        val offer = WorkshopOffer(item = hat, cost = 200)
        listOf(
            Case(CraftResult(item = hat, fragments = CraftFragments(amount = 8, have = 8, need = 16, topUp = 80)), RewardKind.FRAGMENTS, 8, 80L, "служба выдала половину фрагментов: игрок видит полосу 8/16 и цену докупки, а не «вещь твоя»"),
            Case(CraftResult(item = hat, fragments = CraftFragments(amount = 4, have = 16, need = 16, topUp = 10)), RewardKind.FRAGMENTS, 4, 0L, "собранной вещи докупать нечего"),
            Case(CraftResult(item = hat), RewardKind.ITEM, 0, 0L, "старая служба без фрагментов выдавала вещь целиком"),
        ).forEach { case ->
            val card = craftCard(case.made, offer)
            assertEquals(case.why, case.kind, card.kind)
            assertEquals(case.why, case.gain, card.gain)
            assertEquals(case.why, case.topUp, card.topUp)
            assertEquals(case.why, hat, card.item)
        }
    }

    @Test
    fun `обмен без кода вещи в ответе берёт вещь с полки`() {
        val card = craftCard(CraftResult(fragments = CraftFragments(amount = 8, have = 8, need = 16)), WorkshopOffer(item = hat, cost = 200))
        assertEquals("пустой item ответа не должен стереть карточку", hat, card.item)
    }
}
