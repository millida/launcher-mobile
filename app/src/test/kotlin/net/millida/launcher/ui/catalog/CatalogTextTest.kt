package net.millida.launcher.ui.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTextTest {

    @Test
    fun `описание проекта показывается словами, а не разметкой`() {
        data class Case(val raw: String, val expected: String, val why: String)
        listOf(
            Case("", "", "пустое описание"),
            Case("   \n  ", "", "одни пробелы — пусто"),
            Case("# Title\n\nSome **bold** text", "Title\n\nSome bold text", "заголовок и жирный Markdown снимаются"),
            Case("![logo](https://cdn/x.png)Hello [site](https://a.b)", "Hello site", "картинка исчезает, у ссылки остаётся текст"),
            Case("<p>One</p><p>Two&nbsp;&amp; three</p>", "One\nTwo & three", "HTML CurseForge: абзацы в переносы, сущности в символы"),
            Case("line<br/>next", "line\nnext", "br превращается в перенос"),
            Case("> quote", "quote", "цитата без маркера"),
            Case("a\n\n\n\n\nb", "a\n\nb", "пустые строки схлопываются до одной"),
            Case("a     b", "a b", "серии пробелов схлопываются"),
            Case("line\r\nnext", "line\nnext", "переводы строк Windows"),
            Case("`code` and ~~gone~~", "code and gone", "код и зачёркивание без значков"),
            Case("a\n---\nb", "a\n\nb", "горизонтальная черта убирается"),
            Case("&lt;tag&gt; &quot;q&quot; &#39;s&#39;", "<tag> \"q\" 's'", "экранированные символы раскрываются после снятия тегов"),
        ).forEach { case ->
            assertEquals("«${case.raw}»: ${case.why}", case.expected, plainText(case.raw))
        }
    }

    @Test
    fun `длинное описание обрезается с многоточием`() {
        val text = plainText("x".repeat(5000))

        assertEquals("лист проекта не должен раздуваться бесконечным текстом", 4001, text.length)
        assertTrue("обрезка видна игроку", text.endsWith("…"))
        assertEquals("короткое описание не трогается", "x".repeat(4000), plainText("x".repeat(4000)))
    }

    @Test
    fun `короткая запись числа скачиваний`() {
        data class Case(val value: Long, val expected: String, val why: String)
        listOf(
            Case(-5, "0", "отрицательное от сервера не показывается"),
            Case(0, "0", "ноль"),
            Case(999, "999", "до тысячи как есть"),
            Case(1_000, "1 тыс.", "тысяча"),
            Case(15_500, "15 тыс.", "тысячи без дробей"),
            Case(1_000_000, "1 млн", "ровный миллион без «,0»"),
            Case(1_240_000, "1,2 млн", "миллионы с одной цифрой после запятой"),
            Case(12_345_678, "12,3 млн", "русская запятая вместо точки"),
        ).forEach { case ->
            assertEquals("${case.value}: ${case.why}", case.expected, compactCount(case.value))
        }
    }

    @Test
    fun `названия загрузчиков в карточке`() {
        data class Case(val id: String, val expected: String, val why: String)
        listOf(
            Case("fabric", "Fabric", "известный загрузчик"),
            Case("NEOFORGE", "NeoForge", "регистр от источника не важен"),
            Case("optifine", "OptiFine", "фирменное написание"),
            Case("iris", "Iris", "шейдерный загрузчик"),
            Case("sodium", "Sodium", "незнакомое имя хотя бы с заглавной"),
            Case("", "", "пустое остаётся пустым, без падения"),
        ).forEach { case ->
            assertEquals("${case.id}: ${case.why}", case.expected, loaderName(case.id))
        }
    }

    @Test
    fun `число материалов склоняется`() {
        data class Case(val count: Int, val expected: String)
        listOf(
            Case(0, "0 материалов"),
            Case(1, "1 материал"),
            Case(3, "3 материала"),
            Case(11, "11 материалов"),
            Case(21, "21 материал"),
            Case(1500, "1 500 материалов"),
        ).forEach { case ->
            assertEquals("${case.count}: склонение и разряды в строке результатов фильтра", case.expected, materials(case.count))
        }
    }
}
