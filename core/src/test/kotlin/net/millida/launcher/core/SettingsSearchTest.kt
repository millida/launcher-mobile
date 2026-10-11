package net.millida.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone finds a setting by the same words the desktop does (SetKit.tsx useMatch). */
class SettingsSearchTest {

    private class Case(val why: String, val query: String, val text: String, val want: Boolean)

    @Test
    fun `поиск настроек как на десктопе`() {
        val text = "Лобби Музыка сейчас радио Громкость радио Гайд по лаунчеру тёмная"
        val cases = listOf(
            Case("пустой запрос показывает всё", "", text, true),
            Case("одни пробелы — тоже пустой запрос", "   ", text, true),
            Case("регистр не важен", "МУЗЫКА", text, true),
            Case("«е» находит «ё», как и на десктопе", "темная", text, true),
            Case("«ё» в запросе тоже работает", "тёмная", text, true),
            Case("все слова должны найтись, в любом порядке", "радио громкость", text, true),
            Case("одно лишнее слово — строки нет", "радио рендерер", text, false),
            Case("часть слова находит строку", "гайд лаунч", text, true),
            Case("чужое слово не находит", "кэш", text, false),
        )
        cases.forEach { assertEquals(it.why, it.want, SettingsSearch.matches(it.query, it.text)) }
    }
}
