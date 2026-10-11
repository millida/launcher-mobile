package net.millida.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.ui.servers.onlineText
import net.millida.launcher.ui.servers.serverKey
import net.millida.launcher.ui.servers.webLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotdTest {

    private fun colourAt(text: String, index: Int): Color? =
        motdAnnotated(text).spanStyles.firstOrNull { index >= it.start && index < it.end }?.item?.color

    @Test
    fun `коды форматирования не попадают в текст приветствия`() {
        data class Case(val motd: String, val expected: String, val why: String)
        listOf(
            Case("Hello", "Hello", "простой текст"),
            Case("§aGreen §lBold§r plain", "Green Bold plain", "цвет, жирный и сброс снимаются"),
            Case("§x§f§f§0§0§0§0Red", "Red", "цвет из шести пар §x как у Spigot"),
            Case("§#00ff00Lime", "Lime", "прямой hex-цвет"),
            Case("§kmagic", "magic", "мигающие символы показываются как есть"),
            Case("100§", "100§", "одинокий § в конце остаётся символом"),
            Case("§zword", "§zword", "незнакомый код не съедает текст"),
            Case("§#zzzzzzText", "§#zzzzzzText", "битый hex не принимается за цвет"),
            Case("", "", "пустое приветствие"),
        ).forEach { case ->
            assertEquals("«${case.motd}»: ${case.why}", case.expected, motdAnnotated(case.motd).text)
        }
    }

    @Test
    fun `цвета приветствия совпадают с игрой`() {
        assertEquals("§a — светло-зелёный палитры игры", Color(0xFF55FF55), colourAt("§aGreen", 0))
        assertEquals("§c — красный палитры игры", Color(0xFFFF5555), colourAt("§cRed", 0))
        assertEquals("§x собирает цвет из пар", Color(0xFFFF0000), colourAt("§x§f§f§0§0§0§0Red", 0))
        assertEquals("прямой hex", Color(0xFF00FF00), colourAt("§#00ff00Lime", 0))
        assertEquals("регистр кода не важен", Color(0xFF55FF55), colourAt("§AGreen", 0))
        assertEquals("§r возвращает цвет по умолчанию", Color.Unspecified, colourAt("§aGreen§rPlain", 5))
    }

    @Test
    fun `начертание приветствия`() {
        val styled = motdAnnotated("§lB§r§oI§r§mS§r§nU§r§m§nSU")
        fun styleAt(index: Int) = styled.spanStyles.first { index >= it.start && index < it.end }.item

        assertEquals("BISUSU", styled.text)
        assertEquals("§l — жирный", FontWeight.W700, styleAt(0).fontWeight)
        assertEquals("§o — курсив", FontStyle.Italic, styleAt(1).fontStyle)
        assertEquals("§m — зачёркнутый", TextDecoration.LineThrough, styleAt(2).textDecoration)
        assertEquals("§n — подчёркнутый", TextDecoration.Underline, styleAt(3).textDecoration)
        assertEquals(
            "зачёркнутый и подчёркнутый вместе не теряют одно из двух",
            TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline)),
            styleAt(4).textDecoration,
        )
        assertNull("после сброса жирность не тянется дальше", styleAt(1).fontWeight)
    }

    @Test
    fun `цвет сбрасывает начертание, как в игре`() {
        val styled = motdAnnotated("§lBold§aGreen")

        val green = styled.spanStyles.first { it.start == 4 }.item
        assertNull("в игре код цвета снимает жирный — иначе приветствие выглядит не так, как в списке серверов", green.fontWeight)
    }

    @Test
    fun `теги серверов словами`() {
        data class Case(val code: String, val expected: String)
        listOf(
            Case("SURVIVAL", "Выживание"),
            Case("anarchy", "Анархия"),
            Case("MINIGAMES", "Мини-игры"),
            Case("MODDED", "С модами"),
            Case("BRAND_NEW", "BRAND_NEW"),
        ).forEach { case ->
            assertEquals("${case.code}: игрок читает слова, незнакомый код остаётся как есть", case.expected, categoryLabel(case.code))
        }
    }

    @Test
    fun `ключ и онлайн сервера в рейтинге`() {
        assertEquals("srv:mc-island", serverKey(RatedServer(name = "Island", slug = "mc-island", ip = "mc.island.net")))
        assertEquals("без слага ключом служит адрес", "srv:mc.island.net", serverKey(RatedServer(name = "Island", ip = "mc.island.net")))
        assertEquals("без адреса — имя", "srv:Island", serverKey(RatedServer(name = "Island")))

        assertEquals("1 234", onlineText(RatedServer(online = 1234)))
        assertEquals("приблизительный онлайн помечен тильдой", "~12 000", onlineText(RatedServer(online = 12_000, onlineApprox = true)))
    }

    @Test
    fun `ссылки владельцев сервера открываются только как сайты`() {
        data class Case(val raw: String?, val expected: String?, val why: String)
        listOf(
            Case("https://example.net", "https://example.net", "обычный сайт"),
            Case("  HTTP://example.net  ", "HTTP://example.net", "регистр схемы и пробелы не мешают"),
            Case("intent://scan#Intent;scheme=zxing;end", null, "чужая схема запустила бы другое приложение"),
            Case("javascript:alert(1)", null, "скрипт не ссылка"),
            Case("example.net", null, "без схемы непонятно, что открывать"),
            Case(null, null, "ссылки нет"),
        ).forEach { case ->
            assertEquals("${case.raw}: ${case.why}", case.expected, webLink(case.raw))
        }
    }
}
