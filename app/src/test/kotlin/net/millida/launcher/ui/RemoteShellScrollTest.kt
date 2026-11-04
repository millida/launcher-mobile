package net.millida.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class RemoteShellScrollTest {
    private class Case(val why: String, val css: String, val blocked: Boolean)

    private val cases = listOf(
        Case(
            "выпущенная оболочка: html забирает overflow себе, body остаётся контейнером прокрутки и обрывает цепочку",
            "html, body { overflow-x: hidden; } body { overscroll-behavior: none; }",
            true,
        ),
        Case(
            "правка: запрет отскока стоит на html и уходит окну, цепочку ему обрывать нечем",
            "html, body { overflow-x: hidden; } html { overscroll-behavior: none; }",
            false,
        ),
        Case(
            "contain обрывает цепочку так же, как none",
            "html, body { overflow-x: hidden; } body { overscroll-behavior: contain; }",
            true,
        ),
        Case(
            "html без overflow: свойство body уходит окну, а сам body контейнером не становится",
            "body { overflow-x: hidden; overscroll-behavior: none; }",
            false,
        ),
        Case(
            "body без overflow не контейнер прокрутки, его overscroll-behavior ни на что не влияет",
            "html { overflow-x: hidden; } body { overscroll-behavior: none; }",
            false,
        ),
    )

    @Test
    fun `таблица оболочек`() {
        cases.forEach { assertEquals(it.why, it.blocked, bodyStopsPageScroll(it.css)) }
    }

    @Test
    fun `оболочка экранов сайта не обрывает прокрутку страницы`() {
        val shell = listOf(
            File("src/main/assets/remote-ui/shell.html"),
            File("app/src/main/assets/remote-ui/shell.html"),
        ).first { it.exists() }.readText()
        val style = Regex("""<style>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL).find(shell)?.groupValues?.get(1).orEmpty()
        assertFalse(
            "body с overscroll-behavior и собственным overflow: с WebView 144 каталог и страница материала " +
                "перестают листаться пальцем, кнопки при этом работают; запрет отскока держать только на html",
            bodyStopsPageScroll(style),
        )
    }

    private fun bodyStopsPageScroll(css: String): Boolean {
        val rules = Regex("""([^{}]+)\{([^{}]*)\}""").findAll(css).map { it.groupValues[1] to it.groupValues[2] }.toList()
        fun declares(element: String, property: String) = rules.any { (selector, body) ->
            selector.split(',').any { it.trim() == element } && body.contains(property)
        }
        return declares("html", "overflow") && declares("body", "overflow") && declares("body", "overscroll-behavior")
    }
}
