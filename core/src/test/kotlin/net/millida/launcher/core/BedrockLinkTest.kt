package net.millida.launcher.core

import net.millida.launcher.core.bedrock.BedrockAddress
import net.millida.launcher.core.bedrock.bedrockAddServerUri
import net.millida.launcher.core.bedrock.bedrockConnectUri
import net.millida.launcher.core.bedrock.isBedrockAddress
import net.millida.launcher.core.bedrock.parseBedrockAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launcher cannot run the Bedrock client, it hands the server over to it.
 * A deep link that fails does so silently - the client shows no error - so the
 * address and the link are pinned here instead.
 */
class BedrockLinkTest {

    @Test
    fun `адрес разбирается во всех видах, в которых он к нам приходит`() {
        val cases = listOf(
            // вход                                  -> хост, порт, зачем закреплено
            "play.millida.net" to BedrockAddress("play.millida.net", 19132),
            "play.millida.net:19133" to BedrockAddress("play.millida.net", 19133),
            "minecraft://play.millida.net:19132/" to BedrockAddress("play.millida.net", 19132),
            " 203.0.113.12:19132 " to BedrockAddress("203.0.113.12", 19132),
            "[2a01:4f8::1]:19132" to BedrockAddress("2a01:4f8::1", 19132),
            "[2a01:4f8::1]" to BedrockAddress("2a01:4f8::1", 19132),
        )
        cases.forEach { (raw, expected) ->
            assertEquals("адрес «$raw» должен читаться как $expected", expected, parseBedrockAddress(raw))
        }
    }

    @Test
    fun `мусор вместо адреса не превращается в ссылку`() {
        listOf("", "   ", "play millida net", "host:", "host:0", "host:70000", "host:port", "a|b:19132")
            .forEach { raw ->
                assertNull("«$raw» не адрес, ссылку по нему строить нельзя", parseBedrockAddress(raw))
            }
    }

    @Test
    fun `ссылка входа и ссылка сохранения совпадают с форматом клиента`() {
        val address = BedrockAddress("play.millida.net", 19132)
        assertEquals(
            "клиент заходит только через свой connect с serverUrl и serverPort",
            "minecraft://connect?serverUrl=play.millida.net&serverPort=19132",
            bedrockConnectUri(address),
        )
        assertEquals(
            "в addExternalServer значение позиционное: имя|хост:порт",
            "minecraft://?addExternalServer=Millida Выживание|play.millida.net:19132",
            bedrockAddServerUri("Millida Выживание", address),
        )
    }

    @Test
    fun `разделитель в имени сервера не подменяет адрес`() {
        val uri = bedrockAddServerUri("Пиво|evil.host:19132|", BedrockAddress("play.millida.net", 19132))
        assertEquals(
            "имя с разделителем увело бы игрока на чужой сервер",
            "minecraft://?addExternalServer=Пиво evil.host:19132 |play.millida.net:19132",
            uri,
        )
    }

    @Test
    fun `к Bedrock уходит только то, что стоит на его порту`() {
        assertTrue("порт 19132 — это RakNet, Java туда не зайдёт", isBedrockAddress("play.millida.net:19132"))
        assertFalse("Java-сервер по умолчанию остаётся у нашего рантайма", isBedrockAddress("play.millida.net"))
        assertFalse("явный порт Java не Bedrock", isBedrockAddress("play.millida.net:25565"))
    }
}
