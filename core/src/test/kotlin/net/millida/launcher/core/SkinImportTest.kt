package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.skins.SkinImport
import net.millida.launcher.core.skins.TextureError
import net.millida.launcher.core.skins.TextureKind
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class SkinImportTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun importer(): SkinImport =
        SkinImport(client = OkHttpClient(), apiBase = server.url("/v2").toString().trimEnd('/'))

    private fun png(width: Int, height: Int): ByteArray {
        val header = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13)
        return header + "IHDR".toByteArray() + be32(width) + be32(height) + byteArrayOf(8, 6, 0, 0, 0)
    }

    private fun be32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private data class HostCase(val host: String, val local: Boolean, val why: String)

    @Test
    fun `локальные адреса узнаются в любом написании`() {
        val cases = listOf(
            HostCase("localhost", true, "петля по имени"),
            HostCase(" LOCALHOST. ", true, "регистр, пробелы и точка в конце не прячут петлю"),
            HostCase("app.localhost", true, "любое имя под .localhost — петля"),
            HostCase("printer.local", true, "mDNS-имена домашней сети"),
            HostCase("metadata.google.internal", true, "служебные имена облака"),
            HostCase("", true, "пустой хост не пропускается как внешний"),
            HostCase("127.0.0.1", true, "петля цифрами"),
            HostCase("0.0.0.0", true, "любой адрес — тоже сам телефон"),
            HostCase("10.0.0.1", true, "частная сеть 10/8"),
            HostCase("172.16.5.4", true, "частная сеть 172.16/12"),
            HostCase("192.168.1.1", true, "домашний роутер"),
            HostCase("169.254.169.254", true, "link-local, адрес метаданных облака"),
            HostCase("100.64.0.1", true, "CGNAT оператора, 100.64/10"),
            HostCase("100.127.255.255", true, "верхняя граница CGNAT"),
            HostCase("100.128.0.1", false, "сразу за CGNAT — обычный адрес"),
            HostCase("255.255.255.255", true, "широковещательный адрес"),
            HostCase("[::1]", true, "петля IPv6 в скобках, как в ссылке"),
            HostCase("fe80::1", true, "link-local IPv6"),
            HostCase("fd12:3456::1", true, "unique local fc00::/7, Java не считает его site-local"),
            HostCase("fc00::1", true, "нижняя граница fc00::/7"),
            HostCase("2001:4860:4860::8888", false, "публичный IPv6"),
            HostCase("8.8.8.8", false, "публичный IPv4"),
            HostCase("api.millida.net", false, "внешнее имя не резолвится здесь и не считается локальным"),
        )
        for (case in cases) {
            assertEquals("hostIsLocal(«${case.host}»): ${case.why}", case.local, SkinImport.hostIsLocal(case.host))
        }
    }

    private data class NickCase(val raw: String, val clean: String?, val why: String)

    @Test
    fun `ник для импорта проверяется до запроса`() {
        val cases = listOf(
            NickCase("  Notch ", "Notch", "пробелы вокруг вставленного ника срезаются"),
            NickCase("abc", "abc", "три символа — нижняя граница"),
            NickCase("a".repeat(16), "a".repeat(16), "шестнадцать — верхняя граница Minecraft"),
            NickCase("ab", null, "короче трёх — такого ника нет"),
            NickCase("a".repeat(17), null, "длиннее шестнадцати — такого ника нет"),
            NickCase("Not ch", null, "пробел внутри ушёл бы в путь запроса"),
            NickCase("../admin", null, "путь вместо ника не уходит в адрес"),
            NickCase("Стив", null, "кириллица в нике Minecraft невозможна"),
            NickCase("jeb_", "jeb_", "подчёркивание разрешено"),
        )
        for (case in cases) {
            val result = runCatching { SkinImport.cleanNick(case.raw) }
            assertEquals("cleanNick(«${case.raw}»): ${case.why}", case.clean, result.getOrNull())
            if (case.clean == null) {
                assertTrue("«${case.raw}»: отказ объясняет правило ника", result.exceptionOrNull()?.message.orEmpty().contains("от 3 до 16"))
            }
        }
    }

    private data class LinkCase(val raw: String, val message: String?, val why: String)

    @Test
    fun `вставленная ссылка отклоняется с причиной, понятной игроку`() {
        val cases = listOf(
            LinkCase("   ", "Пустая ссылка", "пустая вставка"),
            LinkCase("https://api.millida.net/a\r\nX-Evil: 1", "Некорректная ссылка", "перевод строки внутри ссылки — подстановка заголовка"),
            LinkCase("ftp://api.millida.net/x.png", "Некорректная ссылка", "не http вовсе"),
            LinkCase("http://api.millida.net/x.png", "Нужна ссылка https на PNG", "по http текстуру подменят по дороге"),
            LinkCase("https://10.0.0.5/x.png", "Скины из локальной сети лаунчер не забирает", "частная сеть"),
            LinkCase("https://[::1]/x.png", "Скины из локальной сети лаунчер не забирает", "петля IPv6"),
            LinkCase("https://evilmillida.net/x.png", null, "имя кончается на millida.net, но это чужой домен"),
            LinkCase("https://cdn.millida.net./x.png", "", "точка в конце имени — тот же хост"),
            LinkCase("https://skins.cdn.millida.trade/x.png", "", "поддомены наших доменов разрешены"),
            LinkCase("https://millida.trade/x.png", "", "корневой домен разрешён явно"),
        )
        for (case in cases) {
            val result = runCatching { SkinImport.validateUrl(case.raw) }
            val error = result.exceptionOrNull()
            when (case.message) {
                "" -> assertTrue("«${case.raw}» должна приниматься: ${case.why}; ошибка: ${error?.message}", result.isSuccess)
                null -> {
                    assertTrue("«${case.raw}» должна отклоняться: ${case.why}", error is TextureError)
                    assertTrue("«${case.raw}»: в отказе перечислены проверенные сайты", error!!.message!!.contains("textures.minecraft.net"))
                }
                else -> {
                    assertTrue("«${case.raw}» должна отклоняться: ${case.why}", error is TextureError)
                    assertEquals("«${case.raw}»: ${case.why}", case.message, error!!.message)
                }
            }
        }
    }

    @Test
    fun `редирект ника в локальную сеть останавливает импорт`() {
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${server.port}/router/admin.png"),
            )
            server.enqueue(MockResponse().setBody(Buffer().write(png(64, 64))))

            val failed = runCatching { importer().byNick("Notch") }.exceptionOrNull()

            assertTrue("редирект на адрес телефона или роутера не читается: $failed", failed is TextureError)
            assertEquals("Ссылка увела в локальную сеть", failed!!.message)
        }
    }

    @Test
    fun `ошибка сервиса скинов называет код и что делать`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))

            val failed = runCatching { importer().byNick("Notch") }.exceptionOrNull()

            assertTrue(failed is TextureError)
            assertEquals("Ссылка ответила 503 — возьми другую ссылку", failed!!.message)
        }
    }

    @Test
    fun `обрыв сети при импорте объясняется, а не падает`() {
        runBlocking {
            val dead = MockWebServer()
            dead.start(InetAddress.getByName("127.0.0.1"), 0)
            val base = dead.url("/v2").toString().trimEnd('/')
            dead.shutdown()

            val failed = runCatching { SkinImport(client = OkHttpClient(), apiBase = base).byNick("Notch") }.exceptionOrNull()

            assertTrue("сбой соединения — TextureError с текстом для игрока: $failed", failed is TextureError)
            assertEquals("Текстура не скачалась — проверь сеть и повтори", failed!!.message)
        }
    }

    @Test
    fun `плащ по нику проверяется как плащ`() {
        runBlocking {
            server.enqueue(MockResponse().setBody(Buffer().write(png(64, 64))))
            server.enqueue(MockResponse().setBody(Buffer().write(png(64, 32))))
            val import = importer()

            val square = runCatching { import.byNick("Notch", TextureKind.CAPE) }.exceptionOrNull()
            val cape = import.byNick("Notch", TextureKind.CAPE)

            assertTrue("квадратный скин не принимается за плащ", square is TextureError)
            assertEquals(TextureKind.CAPE, cape.kind)
            assertTrue("источник — строгий адрес без подмены Стивом", cape.source.endsWith("/heads/skin/Notch?fallback=none"))
        }
    }
}
