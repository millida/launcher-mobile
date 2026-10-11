package net.millida.launcher.core

import net.millida.launcher.core.loader.LoaderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Телефон с 3 ГБ и меньше честно предупреждается, а «4 ГБ» по коробке — нет,
 * хотя система показывает ему 3,6–3,8 ГиБ. Лимит кучи считается от свободной
 * памяти, а не от общей.
 */
class MemoryTuningTest {

    @Test
    fun `предупреждение о памяти по общей памяти телефона`() {
        data class Case(val totalMb: Int, val nominalGb: Int?, val why: String)

        val cases = listOf(
            Case(3_700, null, "телефон на 4 ГБ показывает меньше номинала — предупреждать нельзя"),
            Case(3_584, null, "ровно на пороге — ещё не мало"),
            Case(3_583, 4, "чуть ниже порога — предупреждение, номинал округляется вверх"),
            Case(2_800, 3, "телефон на 3 ГБ"),
            Case(1_900, 2, "телефон на 2 ГБ"),
            Case(0, null, "неизвестная память не повод пугать"),
        )
        for (case in cases) {
            val notice = MemoryTuning.lowMemory(DeviceMemory(totalMb = case.totalMb, availableMb = 1_000))
            assertEquals(case.why, case.nominalGb, notice?.nominalGb)
            if (notice != null) {
                assertTrue("${case.why}: в тексте есть объём", notice.text.contains("${case.nominalGb} ГБ"))
                assertTrue("${case.why}: в тексте есть совет", notice.text.contains("Fabric") && notice.text.contains("6"))
            }
        }
    }

    @Test
    fun `куча считается от свободной памяти`() {
        data class Case(val availableMb: Int, val heapMb: Int, val canLaunch: Boolean, val why: String)

        val cases = listOf(
            Case(6_000, 2_048, true, "свободно много — даётся столько, сколько просит сборка"),
            Case(2_700, 1_540, true, "свободно мало — куча урезается до свободного минус запас системы"),
            Case(1_630, 512, true, "1,6 ГБ свободно — обычный телефон: играть можно на малой куче, с предупреждением"),
            Case(900, 512, false, "совсем мало — запуск отменяется с понятной причиной"),
        )
        for (case in cases) {
            val plan = MemoryTuning.plan(DeviceMemory(totalMb = 8_000, availableMb = case.availableMb), LoaderKind.FORGE, 40)
            assertEquals(case.why, case.heapMb, plan.heapMb)
            assertEquals(case.why, case.canLaunch, plan.canLaunch)
        }
    }

    @Test
    fun `сборка со своим запуском просит память сама`() {
        data class Case(val availableMb: Int, val packMinMb: Int, val heapMb: Int, val warned: Boolean, val why: String)

        val cases = listOf(
            Case(6_000, 4_096, 4_096, false, "OneBlock просит 4 ГБ, моды у него в libraries — счёт mods/ его не видит"),
            Case(3_500, 4_096, 4_096, true, "свободно меньше минимума OneBlock — минимум всё равно даётся: с урезанной кучей сборка гарантированно падает, игрок предупреждён"),
            Case(1_200, 4_096, 4_096, true, "1,2 ГБ свободно на 8 ГБ телефоне — система освободит фон, куча не урезается до 512"),
            Case(6_000, 0, 1_536, false, "без минимума сборки действует счёт модов"),
            Case(6_000, 1_024, 1_536, false, "минимум меньше счёта модов не урезает кучу"),
        )
        for (case in cases) {
            val plan = MemoryTuning.plan(DeviceMemory(totalMb = 8_000, availableMb = case.availableMb), LoaderKind.FORGE, 3, case.packMinMb)
            assertEquals(case.why, case.heapMb, plan.heapMb)
            assertEquals(case.why, case.warned, plan.warning != null)
        }
        assertEquals("потолок кучи — вся память без запаса системы и самой машины", 6_840, MemoryTuning.heapCeilingMb(8_000))
        assertEquals(0, MemoryTuning.heapCeilingMb(500))
    }

    @Test
    fun `большой телефон получает кучу по своей памяти, малый — прежнюю`() {
        data class Case(val totalMb: Int, val availableMb: Int, val mods: Int, val heapMb: Int, val why: String)

        val cases = listOf(
            Case(3_700, 3_000, 80, 1_840, "4 ГБ: куча по свободной памяти, как раньше"),
            Case(7_500, 6_000, 80, 2_560, "«8 ГБ» показывает меньше 8192 — ступень прежняя"),
            Case(8_192, 7_000, 80, 2_560, "ровно 8 ГБ — граница, рост ещё не начался"),
            Case(11_264, 9_000, 80, 3_456, "12 ГБ: 2560 × 11264/8192 = 3520, вниз до шага 128"),
            Case(11_264, 9_000, 40, 2_816, "12 ГБ, 40 модов: ступень 2048 растёт так же"),
            Case(11_264, 9_000, 10, 2_048, "12 ГБ, 10 модов: ступень 1536 → 2112 → 2048"),
            Case(11_264, 9_000, 0, 2_048, "Forge без модов в mods/ — модовая ступень, она тоже растёт"),
            Case(15_360, 12_000, 80, 4_736, "16 ГБ: почти вдвое"),
            Case(24_576, 20_000, 80, 5_120, "выше 16 ГБ рост останавливается на удвоении"),
            Case(11_264, 3_500, 80, 2_340, "свободной памяти мало — куча урезается до неё, а не до доли телефона"),
        )
        for (case in cases) {
            val plan = MemoryTuning.plan(DeviceMemory(case.totalMb, case.availableMb), LoaderKind.FORGE, case.mods)
            assertEquals(case.why, case.heapMb, plan.heapMb)
        }
        assertEquals(
            "ванильная игра не растёт: ей больше гигабайта не нужно, а лишняя куча удлиняет паузы сборщика",
            1_024,
            MemoryTuning.plan(DeviceMemory(15_360, 12_000), LoaderKind.VANILLA, 0).heapMb,
        )
    }

    @Test
    fun `рост кучи не выходит за долю памяти телефона`() {
        data class Case(val tierMb: Int, val totalMb: Int, val expected: Int, val why: String)

        val cases = listOf(
            Case(2_560, 0, 2_560, "неизвестная память — ступень без изменений"),
            Case(2_560, 6_000, 2_560, "малый телефон не трогается"),
            Case(2_560, 9_000, 2_688, "чуть больше 8 ГБ — небольшой рост с шагом 128"),
            Case(8_192, 12_000, 8_192, "ступень выше доли не урезается: ниже прежнего не опускаем"),
            Case(4_096, 12_000, 4_800, "доля 40% от 12000 = 4800 ограничивает рост 6000"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, MemoryTuning.scaledForDevice(case.tierMb, case.totalMb))
        }
    }

    @Test
    fun `минимум сборки соблюдается и на большом телефоне`() {
        val plan = MemoryTuning.plan(DeviceMemory(11_264, 9_000), LoaderKind.FORGE, 3, packMinMb = 4_096)
        assertEquals("OneBlock просит 4 ГБ — больше роста ступени, берётся минимум сборки", 4_096, plan.heapMb)
        assertTrue("памяти хватает — без предупреждения", plan.warning == null)
    }

    @Test
    fun `потолок ползунка памяти идёт от памяти телефона`() {
        data class Case(val totalMb: Int, val expected: Int, val why: String)

        val cases = listOf(
            Case(11_345, 10_112, "телефон на 12 ГБ: ползунок не упирается в прежние 4 ГБ"),
            Case(7_680, 6_400, "8 ГБ: запас системе и JVM, кратно шагу 128"),
            Case(2_048, 1_024, "малый телефон: ползунок не схлопывается ниже гигабайта"),
            Case(0, 1_024, "память неизвестна: ползунок остаётся рабочим"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, MemoryTuning.settingCeilingMb(case.totalMb))
        }
    }
}
