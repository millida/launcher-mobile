package net.millida.launcher.ui.shop

import net.millida.launcher.ui.Rarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChestLabelsTest {

    @Test
    fun `название и редкость сундука по тиру`() {
        data class Case(val tier: String, val name: String, val rarity: Rarity, val why: String)
        listOf(
            Case("COMMON", "Обычный", Rarity.COMMON, "обычный"),
            Case("rare", "Редкий", Rarity.RARE, "регистр тира от сервиса не важен"),
            Case("EPIC", "Эпический", Rarity.EPIC, "эпический"),
            Case("LEGEND", "Легендарный", Rarity.LEGENDARY, "короткое имя легендарного"),
            Case("LEGENDARY", "Легендарный", Rarity.LEGENDARY, "полное имя легендарного"),
            Case("", "Обычный", Rarity.COMMON, "пустой тир рисуется обычным сундуком"),
            Case("MYTHIC_NEW", "Обычный", Rarity.COMMON, "незнакомый тир не роняет витрину"),
        ).forEach { case ->
            assertEquals("${case.tier}: ${case.why}", case.name, chestName(case.tier))
            assertEquals("${case.tier}: ${case.why}", case.rarity, chestRarity(case.tier))
        }
    }

    @Test
    fun `крышка сундука открывается с отскоком и встаёт на место`() {
        assertEquals("в начале крышка закрыта", 0f, chestEaseOutBack(0f), 1e-5f)
        assertEquals("в конце крышка ровно на месте", 1f, chestEaseOutBack(1f), 1e-5f)
        val overshoot = (1..99).map { chestEaseOutBack(it / 100f) }.maxOrNull() ?: 0f
        assertTrue("без перелёта за 1 нет отскока, ради которого кривая выбрана; максимум $overshoot", overshoot > 1f)
    }
}
