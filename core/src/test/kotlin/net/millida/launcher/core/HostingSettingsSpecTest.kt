package net.millida.launcher.core

import net.millida.launcher.core.hosting.HostingField
import net.millida.launcher.core.hosting.HostingSettingsSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostingSettingsSpecTest {

    private data class NumberCase(val text: String, val expected: Int?, val why: String)

    private val view = HostingField.Number("viewDistance", "Прорисовка", 3, 32, 10)

    @Test
    fun `слоты тарифа ограничивают максимум игроков`() {
        val cases = listOf(
            100 to 20 to "больше слотов, чем по умолчанию, — по умолчанию 20",
            20 to 20 to "ровно 20 слотов",
            5 to 5 to "маленький тариф: по умолчанию не больше слотов тарифа, иначе служба отклонит",
        )
        for ((pair, why) in cases) {
            val field = HostingSettingsSpec.main(pair.first).fields.filterIsInstance<HostingField.Number>().single { it.key == "maxPlayers" }
            assertEquals("слотов ${pair.first}: $why", pair.second, field.default)
            assertEquals("слотов ${pair.first}: верхняя граница — слоты тарифа", pair.first, field.max)
            assertEquals("слотов ${pair.first}: минимум один игрок", 1, field.min)
        }
    }

    @Test
    fun `ключи настроек уникальны и умолчания лежат в границах`() {
        for (free in listOf(true, false)) {
            val fields = (listOf(HostingSettingsSpec.main(20)) + HostingSettingsSpec.advanced(free)).flatMap { it.fields }
            val keys = fields.map { it.key }
            assertEquals("повтор ключа — два поля пишут одно правило (free=$free)", keys.distinct(), keys)
            for (field in fields) {
                when (field) {
                    is HostingField.Number -> assertTrue(
                        "${field.key}: умолчание ${field.default} вне ${field.min}..${field.max} — поле нельзя сохранить нетронутым",
                        field.default in field.min..field.max,
                    )
                    is HostingField.Choice -> assertTrue(
                        "${field.key}: умолчание «${field.default}» не входит в варианты",
                        field.options.any { it.first == field.default },
                    )
                    else -> Unit
                }
            }
        }
        val line2 = { free: Boolean ->
            HostingSettingsSpec.advanced(free).flatMap { it.fields }.single { it.key == "motdLine2" }.hint
        }
        assertTrue("на бесплатном тарифе игрок должен знать про подпись Millida", line2(true).orEmpty().contains("Millida"))
        assertNull("на платном тарифе подсказки про подпись нет", line2(false))
    }

    @Test
    fun `сырое значение правила`() {
        val rules = mapOf("a" to "  value ", "b" to "null", "c" to "   ", "d" to "")
        assertEquals("пробелы вокруг срезаются", "value", HostingSettingsSpec.raw(rules, "a"))
        assertNull("слово null из JSON — отсутствие значения", HostingSettingsSpec.raw(rules, "b"))
        assertNull("пробелы — отсутствие значения", HostingSettingsSpec.raw(rules, "c"))
        assertNull("пустая строка — отсутствие значения", HostingSettingsSpec.raw(rules, "d"))
        assertNull("нет ключа — нет значения", HostingSettingsSpec.raw(rules, "e"))
        assertTrue("TRUE заглавными — тоже true", HostingSettingsSpec.toggle(mapOf("pvp" to "TRUE"), "pvp", false))
        assertFalse("«1» не булево — умолчание", HostingSettingsSpec.toggle(mapOf("pvp" to "1"), "pvp", false))
    }

    @Test
    fun `числа и варианты читаются из того, что прислала служба`() {
        assertEquals("дробь от службы читается целым", 12, HostingSettingsSpec.number(mapOf("viewDistance" to "12.0"), view))
        assertEquals("мусор — умолчание поля", 10, HostingSettingsSpec.number(mapOf("viewDistance" to "далеко"), view))
        assertEquals("нет значения — умолчание поля", 10, HostingSettingsSpec.number(emptyMap(), view))

        val java = HostingField.Choice("javaVersion", "Java", HostingSettingsSpec.JAVA, "0")
        assertEquals("версия Java числом с дробью узнаётся", "21", HostingSettingsSpec.choice(mapOf("javaVersion" to "21.0"), java))
        assertEquals("версия Java, которой нет в меню, — авто", "0", HostingSettingsSpec.choice(mapOf("javaVersion" to "19"), java))
        val mode = HostingField.Choice("gamemode", "Режим", listOf("survival" to "Выживание", "creative" to "Творческий"), "survival")
        assertEquals("регистр варианта не угадывается — умолчание", "survival", HostingSettingsSpec.choice(mapOf("gamemode" to "CREATIVE"), mode))
        assertEquals("известный вариант с пробелами", "creative", HostingSettingsSpec.choice(mapOf("gamemode" to " creative "), mode))
    }

    @Test
    fun `ввод числа принимается только в границах службы`() {
        val compression = HostingField.Number("networkCompressionThreshold", "Сжатие", -1, 65_536, 256)
        val cases = listOf(
            NumberCase("3", 3, "нижняя граница включительно"),
            NumberCase("32", 32, "верхняя граница включительно"),
            NumberCase(" 16 ", 16, "пробелы от клавиатуры срезаются"),
            NumberCase("2", null, "ниже минимума — служба отклонит"),
            NumberCase("33", null, "выше максимума — служба отклонит"),
            NumberCase("10.5", null, "дробь не целое число чанков"),
            NumberCase("", null, "пустое поле не отправляется нулём"),
            NumberCase("99999999999", null, "переполнение Int не превращается в мусорное число"),
        )
        for (case in cases) {
            assertEquals("«${case.text}»: ${case.why}", case.expected, HostingSettingsSpec.parseNumber(case.text, view))
        }
        assertEquals("-1 — допустимое «выключить» для сжатия", -1, HostingSettingsSpec.parseNumber("-1", compression))
        assertNull("-2 вне границ сжатия", HostingSettingsSpec.parseNumber("-2", compression))
    }
}
