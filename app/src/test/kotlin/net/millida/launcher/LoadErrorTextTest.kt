package net.millida.launcher

import net.millida.launcher.core.account.Unauthorized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class LoadErrorTextTest {

    private val signIn = "Вход Millida истёк — войди заново"
    private val offline = "Нет сети — проверь подключение и нажми «Повторить»"
    private val fallback = "Не загрузилось — нажми «Повторить»"

    @Test
    fun `ошибка загрузки раздела переводится в понятный игроку текст`() {
        data class Case(val error: Throwable, val expected: String, val why: String)
        listOf(
            Case(Unauthorized(), signIn, "истёкшая сессия просит войти, а не показывает техническое сообщение"),
            Case(IOException("обёртка", Unauthorized()), signIn, "сессия узнаётся и в цепочке причин"),
            Case(UnknownHostException("api.millida.net"), offline, "нет DNS — значит нет сети, имя хоста игроку не нужно"),
            Case(ConnectException("refused"), offline, "отказ соединения тоже сеть"),
            Case(RuntimeException("x", SocketTimeoutException("timeout")), offline, "таймаут в причине — сеть"),
            Case(IllegalStateException("  сервер занят "), "Сервер занят", "сообщение сервера показывается обрезанным и с заглавной"),
            Case(RuntimeException(), fallback, "без сообщения остаётся подсказка повторить"),
            Case(RuntimeException("   "), fallback, "пустое сообщение не превращается в пустую плашку"),
        ).forEach { case ->
            assertEquals("${case.error}: ${case.why}", case.expected, loadErrorText(case.error))
        }
    }

    @Test
    fun `сессия важнее сети, когда обе в цепочке`() {
        val error = UnknownHostException("dns").apply { initCause(Unauthorized()) }

        assertEquals("войти заново — единственное, что поможет, даже если в цепочке есть сеть", signIn, loadErrorText(error))
    }

    @Test
    fun `каждый экран принадлежит одной из пяти вкладок`() {
        Screen.entries.forEach { screen ->
            val root = rootTabOf(screen)
            assertTrue("$screen ведёт на $root, которой нет в нижней панели — подсветка вкладки потеряется", root.isRoot)
        }
        rootTabs.forEach { tab ->
            assertEquals("корневая вкладка $tab должна подсвечивать саму себя", tab, rootTabOf(tab))
        }
        assertEquals("сборка открывается с главной и подсвечивает её", Screen.HOME, rootTabOf(Screen.BUILD))
        assertEquals("настройки живут в «Ещё»", Screen.MORE, rootTabOf(Screen.SETTINGS))
        assertEquals("в нижней панели ровно пять мест", 5, rootTabs.size)
    }
}
