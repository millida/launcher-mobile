package net.millida.launcher.core

import net.millida.launcher.core.rating.LicenseFilter
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.core.rating.ServerArt
import net.millida.launcher.core.rating.ServerFilters
import net.millida.launcher.core.servers.OwnServers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFiltersTest {

    @Test
    fun `подписи фильтров находятся по коду, незнакомый код не подписывается`() {
        assertEquals("«Все» — пустой код, иначе фильтр по умолчанию уйдёт параметром", "Все", ServerFilters.categoryLabel(""))
        assertEquals("код категории службы", "Выживание", ServerFilters.categoryLabel("SURVIVAL"))
        assertNull("код в другом регистре — не наш код, служба его не знает", ServerFilters.categoryLabel("survival"))
        assertNull("незнакомая категория не получает чужую подпись", ServerFilters.categoryLabel("HARDCORE"))
        assertEquals("«Сейчас есть игроки»", "Сейчас есть игроки", ServerFilters.onlineLabel(ServerFilters.LIVE))
        assertEquals("порог 100", "От 100 игроков", ServerFilters.onlineLabel("100"))
        assertNull("порог, которого нет в меню, не подписывается", ServerFilters.onlineLabel("50"))
        assertEquals(
            "подписи лицензии для каждого значения фильтра",
            listOf("Любая лицензия", "Без лицензии", "Только лицензия"),
            LicenseFilter.values().map { ServerFilters.licenseLabel(it) },
        )
    }

    @Test
    fun `меню фильтров не содержит повторов и каждый порог понятен службе`() {
        val categoryCodes = ServerFilters.CATEGORIES.map { it.first }
        assertEquals("повтор кода категории — две строки меню выбирают одно", categoryCodes.distinct(), categoryCodes)
        assertEquals("«Все» стоит первой и выбрана по умолчанию", "", categoryCodes.first())
        for ((value, label) in ServerFilters.ONLINE) {
            val threshold = ServerFilters.minOnline(value)
            if (value.isEmpty()) {
                assertEquals("«$label» не должен фильтровать", 0, threshold)
            } else {
                assertTrue("«$label» обязан превращаться в порог больше нуля, иначе пункт меню ничего не делает", threshold > 0)
            }
        }
        assertEquals("отрицательный порог от ручного ввода не уходит в службу", 0, ServerFilters.minOnline("-5"))
    }

    @Test
    fun `заглушка сервера держится за слаг, потом за имя, потом за адрес`() {
        val cases = listOf(
            RatedServer(slug = "surv", name = "Выживание", ip = "a.net") to "surv",
            RatedServer(slug = "", name = "Выживание", ip = "a.net") to "Выживание",
            RatedServer(slug = "", name = "", ip = "a.net") to "a.net",
            RatedServer() to "",
        )
        for ((server, key) in cases) {
            assertEquals("ключ заглушки для $server — смена ключа перекрасит карточку при каждом обновлении", key, ServerArt.keyOf(server))
        }
        val block = ServerArt.block("surv")
        assertEquals(
            "путь к иконке блока из ассетов приложения",
            "file:///android_asset/block-icons/Block${block}Millida.png",
            ServerArt.blockAsset(block),
        )
        for (name in listOf("", "a", "Выживание", "x".repeat(500))) {
            assertTrue("«$name»: блок вне списка — картинки нет в ассетах", ServerArt.block(name) in ServerArt.BLOCKS)
            assertTrue("«$name»: подложка вне палитры", ServerArt.tint(name) in 0 until ServerArt.TINTS)
        }
    }

    @Test
    fun `адрес своего сервера приводится к одному виду`() {
        val cases = listOf(
            " Play.Example.NET. " to "play.example.net",
            "play.example.net:25565" to "play.example.net",
            "play.example.net.:25566" to "play.example.net:25566",
            "play.example.net:025566" to "play.example.net:025566",
            "play.example.net:abc" to "play.example.net:abc",
            ":25565" to "",
            "" to "",
        )
        for ((input, expected) in cases) {
            assertEquals("«$input» — лог игры и карточка рейтинга должны совпасть", expected, OwnServers.canonical(input))
        }
        assertNull("пустой адрес не совпадает со своим сервером", OwnServers.of(null, "   "))
        assertNull("пустой слаг не совпадает со своим сервером", OwnServers.of("  ", null))
        assertEquals("слаг с пробелами из ссылки всё равно узнаётся", OwnServers.ONEBLOCK, OwnServers.of(" oneblock-7 ", null))
    }
}
