package net.millida.launcher.core

import kotlinx.serialization.json.Json
import net.millida.launcher.core.servers.ServerPing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class ServerPingTest {

    private data class MotdCase(val json: String, val expected: String, val why: String)

    private data class VarIntCase(val value: Int, val size: Int, val why: String)

    private data class AnswerCase(val reply: ByteArray, val messagePart: String, val why: String)

    private fun motd(json: String): String = ServerPing.motdText(Json.parseToJsonElement(json))

    @Test
    fun `описание сервера сводится к legacy-кодам цвета и стиля`() {
        val cases = listOf(
            MotdCase("\"§aПривет\"", "§aПривет", "строка приходит как есть со своими кодами"),
            MotdCase("""{"text":"A","color":"red"}""", "§cA", "именованный цвет переводится в код"),
            MotdCase("""{"text":"A","color":"red","bold":true,"italic":true}""", "§c§l§oA", "цвет идёт раньше стилей, стили в порядке игры"),
            MotdCase("""{"text":"A","underlined":true,"strikethrough":true,"obfuscated":true}""", "§n§m§kA", "все пять стилей узнаются"),
            MotdCase("""{"text":"A","bold":false}""", "A", "явное false не включает стиль"),
            MotdCase("""{"text":"A","color":"#ff8800"}""", "A", "hex-цвет UI не умеет красить — он отбрасывается, текст остаётся"),
            MotdCase("""{"text":"A","color":"blurple"}""", "A", "незнакомый цвет не превращается в мусорный код"),
            MotdCase("""["", {"text":"x","color":"green"}, "y"]""", "§axy", "описание массивом склеивается по порядку"),
            MotdCase("""{"extra":[{"text":"M","color":"gold"},{"text":"illida"}]}""", "§6Millida", "дерево без собственного текста читается по детям"),
            MotdCase("""{"text":null}""", "", "null в тексте не печатается словом null"),
            MotdCase("""{"text":"Line1\nLine2"}""", "Line1\nLine2", "перенос строки двухстрочного MOTD сохраняется"),
        )
        for (case in cases) {
            assertEquals("MOTD ${case.json}: ${case.why}", case.expected, motd(case.json))
        }
    }

    @Test
    fun `пустое и огромное описание не ломают список серверов`() {
        assertEquals("отсутствующее описание — пустая строка, а не исключение", "", ServerPing.motdText(null))
        assertEquals(
            "MOTD длиннее 2000 знаков обрезается — чужой сервер не раздует память экрана",
            2000,
            motd("\"" + "x".repeat(5000) + "\"").length,
        )

        val levels = 40
        val deep = buildString {
            repeat(levels) { append("{\"text\":\"d\",\"extra\":[") }
            append("\"end\"")
            repeat(levels) { append("]}") }
        }
        val flattened = motd(deep)
        assertTrue("глубокое дерево компонентов должно обрываться, а не уходить в рекурсию: $flattened", flattened.length < levels)
        assertTrue("начало глубокого дерева потеряно: $flattened", flattened.startsWith("ddd"))
    }

    @Test
    fun `ответ статуса разбирается при пустых и странных полях`() {
        val status = ServerPing.parseStatus(
            """{"version":{"name":null},"players":{"online":null,"max":"много"},"favicon":"QUJD"}""",
            7L,
        )
        assertEquals("null в версии не печатается словом null", "", status.version)
        assertEquals("null в онлайне читается как ноль", 0, status.online)
        assertEquals("нечисло в максимуме читается как ноль", 0, status.max)
        assertEquals("иконка без префикса data: берётся целиком", "QUJD", status.iconBase64)
        assertEquals("задержка переносится как есть", 7L, status.latencyMs)

        assertNull(
            "пустая иконка после base64, не должна стать картинкой",
            ServerPing.parseStatus("""{"favicon":"data:image/png;base64,"}""", 0L).iconBase64,
        )
        val huge = "A".repeat(1_000_001)
        assertNull(
            "иконка больше миллиона знаков отбрасывается — иначе один сервер съедает память списка",
            ServerPing.parseStatus("""{"favicon":"data:image/png;base64,$huge"}""", 0L).iconBase64,
        )
    }

    @Test
    fun `неразборчивый ответ становится ошибкой, а не нулями`() {
        val cases = listOf(
            "" to "пустой ответ",
            "not json" to "мусор вместо JSON",
            "[1,2,3]" to "массив вместо объекта",
            "\"text\"" to "строка вместо объекта",
            "{\"players\":" to "обрезанный JSON",
        )
        for ((json, why) in cases) {
            try {
                ServerPing.parseStatus(json, 0L)
                fail("$why («$json») прочитался как статус — игрок увидит сервер с нулём игроков вместо ошибки")
            } catch (expected: IOException) {
                assertTrue("$why: в ошибке нет объяснения", expected.message.orEmpty().contains("неразборчивый"))
            }
        }
    }

    @Test
    fun `VarInt пишется и читается так, как требует протокол`() {
        val cases = listOf(
            VarIntCase(0, 1, "ноль — один байт"),
            VarIntCase(127, 1, "последнее однобайтовое значение"),
            VarIntCase(128, 2, "первое двухбайтовое значение — граница, на которой ломаются самописные ридеры"),
            VarIntCase(25565, 3, "стандартный порт Minecraft"),
            VarIntCase(2_097_151, 3, "максимум трёх байт"),
            VarIntCase(2_097_152, 4, "первое четырёхбайтовое"),
            VarIntCase(Int.MAX_VALUE, 5, "максимальная длина пакета"),
            VarIntCase(-1, 5, "версия протокола -1 в рукопожатии — отрицательное занимает пять байт"),
        )
        for (case in cases) {
            val out = ByteArrayOutputStream()
            ServerPing.writeVarInt(out, case.value)
            val bytes = out.toByteArray()
            assertEquals("длина VarInt для ${case.value}: ${case.why}", case.size, bytes.size)
            assertEquals("VarInt ${case.value} не вернулся при чтении: ${case.why}", case.value, ServerPing.readVarInt(ByteArrayInputStream(bytes)))
        }
    }

    @Test
    fun `слишком длинный или оборванный VarInt отклоняется`() {
        try {
            ServerPing.readVarInt(ByteArrayInputStream(ByteArray(6) { 0x80.toByte() }))
            fail("шесть байт продолжения прочитались — враждебный сервер заставит читать бесконечно")
        } catch (expected: EOFException) {
            fail("слишком длинное число должно отличаться от обрыва соединения")
        } catch (expected: IOException) {
            assertTrue("в ошибке не сказано, что не так", expected.message.orEmpty().contains("длинное"))
        }
        try {
            ServerPing.readVarInt(ByteArrayInputStream(byteArrayOf(0x80.toByte())))
            fail("оборванное число прочиталось как значение")
        } catch (expected: EOFException) {
            assertTrue("в ошибке не сказано про обрыв", expected.message.orEmpty().contains("оборвалось"))
        }
    }

    @Test
    fun `чужой ответ сервера отклоняется с понятной причиной`() {
        val cases = listOf(
            AnswerCase(byteArrayOf(0x02, 0x01, 0x00), "не статусом", "сервер ответил другим пакетом — это не Minecraft-статус"),
            AnswerCase(byteArrayOf(0x02, 0x00, 0x00), "некорректный", "нулевая длина JSON не бывает у живого сервера"),
            AnswerCase(
                byteArrayOf(0x05, 0x00, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x04),
                "некорректный",
                "заявленные 8 МБ JSON не должны выделяться на телефоне",
            ),
        )
        for (case in cases) {
            val server = replyingServer(case.reply)
            try {
                ServerPing.pingBlocking("127.0.0.1:${server.localPort}", timeoutMs = 3000)
                fail("${case.why}: ответ принят как статус")
            } catch (expected: IOException) {
                assertTrue(
                    "${case.why}: ожидалось «${case.messagePart}» в «${expected.message}»",
                    expected.message.orEmpty().contains(case.messagePart),
                )
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun `закрытый порт и пустой адрес сообщаются словами`() {
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = closed.localPort
        closed.close()
        try {
            ServerPing.pingBlocking("127.0.0.1:$port", timeoutMs = 2000)
            fail("закрытый порт прочитался как живой сервер")
        } catch (expected: IOException) {
            assertTrue("игрок не поймёт, что сервер не отвечает: ${expected.message}", expected.message.orEmpty().contains("не отвечает"))
        }
        try {
            ServerPing.pingBlocking("   ", timeoutMs = 100)
            fail("пустой адрес ушёл в сеть")
        } catch (expected: IOException) {
            assertTrue("пустой адрес не назван: ${expected.message}", expected.message.orEmpty().contains("пустой"))
        }
    }

    private fun replyingServer(reply: ByteArray): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    skipPacket(input)
                    skipPacket(input)
                    socket.getOutputStream().write(reply)
                    socket.getOutputStream().flush()
                    while (input.read() >= 0) Unit
                }
            } catch (ignored: IOException) {
                return@thread
            }
        }
        return server
    }

    private fun skipPacket(input: InputStream) {
        val length = ServerPing.readVarInt(input)
        repeat(length) { if (input.read() < 0) throw EOFException("клиент оборвал пакет") }
    }
}
