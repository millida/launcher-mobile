package net.millida.launcher.core

import net.millida.launcher.core.loader.LoaderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            Case(2_700, 2_040, true, "мгновенно свободно мало, но Android освободит кэш: считается 40% от 8 ГБ"),
            Case(1_630, 2_040, true, "1,6 ГБ свободно на 8 ГБ телефоне — не отказ, система держит кэши"),
            Case(900, 2_040, true, "даже 900 МБ мгновенно свободно на 8 ГБ — запуск, а не отказ"),
        )
        for (case in cases) {
            val plan = MemoryTuning.plan(DeviceMemory(totalMb = 8_000, availableMb = case.availableMb), LoaderKind.FORGE, 40)
            assertEquals(case.why, case.heapMb, plan.heapMb)
            assertEquals(case.why, case.canLaunch, plan.canLaunch)
        }
    }

    @Test
    fun `отказ только когда не хватит даже на минимальную кучу`() {
        val tiny = MemoryTuning.plan(DeviceMemory(totalMb = 2_048, availableMb = 600), LoaderKind.FORGE, 40)
        assertFalse("2 ГБ телефон: даже с кэшем меньше гигабайта", tiny.canLaunch)
        assertTrue(tiny.warning!!.contains("Свободно всего 600"))
        val low = MemoryTuning.plan(DeviceMemory(totalMb = 3_072, availableMb = 700), LoaderKind.FORGE, 40)
        assertTrue("3 ГБ телефон с малым availMem запускается на малой куче", low.canLaunch)
        assertTrue("и с предупреждением", low.warning != null)
    }

    @Test
    fun `авто-куча не больше доли телефона и 2 ГБ`() {
        assertEquals(1_024, MemoryTuning.autoHeapCapMb(3_000))
        assertEquals(1_036, MemoryTuning.autoHeapCapMb(3_700))
        assertEquals(2_048, MemoryTuning.autoHeapCapMb(24_576))
        assertEquals(2_048, MemoryTuning.autoHeapCapMb(0))
    }

    @Test
    fun `сборка со своим запуском просит память сама`() {
        data class Case(val availableMb: Int, val packMinMb: Int, val heapMb: Int, val warned: Boolean, val why: String)

        val cases = listOf(
            Case(6_000, 4_096, 2_560, false, "OneBlock просит 4 ГБ — на 8 ГБ телефоне хватает 2,5 ГБ, 4 ГБ загоняли систему в своп"),
            Case(2_500, 4_096, 2_560, true, "свободно меньше минимума OneBlock — минимум всё равно даётся: с урезанной кучей сборка гарантированно падает, игрок предупреждён"),
            Case(1_200, 4_096, 2_560, true, "1,2 ГБ свободно на 8 ГБ телефоне — система освободит фон, куча не урезается до 512"),
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
            Case(3_700, 3_000, 80, 1_036, "4 ГБ: 28% телефона, а не вся свободная память"),
            Case(7_500, 6_000, 80, 2_048, "«8 ГБ» показывает меньше 8192 — ступень прежняя"),
            Case(8_192, 7_000, 80, 2_048, "ровно 8 ГБ — граница, рост ещё не начался"),
            Case(11_264, 9_000, 80, 2_048, "12 ГБ: 2560 × 11264/8192 = 3520, вниз до шага 128"),
            Case(11_264, 9_000, 40, 2_048, "12 ГБ, 40 модов: ступень 2048 растёт так же"),
            Case(11_264, 9_000, 10, 2_048, "12 ГБ, 10 модов: ступень 1536 → 2112 → 2048"),
            Case(11_264, 9_000, 0, 2_048, "Forge без модов в mods/ — модовая ступень, она тоже растёт"),
            Case(15_360, 12_000, 80, 2_048, "16 ГБ: почти вдвое"),
            Case(24_576, 20_000, 80, 2_048, "выше 16 ГБ рост останавливается на удвоении"),
            Case(11_264, 3_500, 80, 2_048, "свободной памяти мало — куча урезается до неё, а не до доли телефона"),
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
    fun `в 32-битной системе куча не выходит за адреса процесса`() {
        data class Case(val asked: Int, val narrow: Boolean, val expected: Int, val why: String)

        val cases = listOf(
            Case(2048, true, 768, "куча на 2 ГБ не оставит 32-битному процессу адресов на машину Java и драйвер графики"),
            Case(768, true, 768, "ровно потолок проходит"),
            Case(512, true, 512, "меньшая куча не раздувается"),
            Case(4096, false, 4096, "64-битному процессу потолок не нужен"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, MemoryTuning.fitAddressSpace(case.asked, case.narrow))
        }
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
        assertEquals("OneBlock просит 4 ГБ — на 11 ГБ телефоне до 32% памяти", 3_584, plan.heapMb)
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

    // Доли и вылеты — из телеметрии 19-26.10.2026 (low_memory на телефонах до 6 ГБ).
    @Test
    fun `на телефоне до 6 ГБ просимая куча не выше доли памяти`() {
        data class Case(val heapMb: Int, val totalMb: Int, val want: Int, val why: String)
        listOf(
            Case(4_096, 5_632, 2_176, "сборка просит 4 ГБ на 5,5 ГБ — 40% телефона"),
            Case(2_048, 3_700, 1_408, "телефон 4 ГБ"),
            Case(2_048, 2_800, 1_024, "совсем слабый — но не ниже 1 ГБ"),
            Case(1_536, 5_632, 1_536, "просьба меньше доли не трогается"),
            Case(4_096, 8_000, 4_096, "выше 6 ГБ правило не действует"),
            Case(4_096, 0, 4_096, "память телефона неизвестна"),
        ).forEach { case ->
            assertEquals(case.why, case.want, MemoryTuning.fitSmallPhone(case.heapMb, case.totalMb))
        }
    }
}
