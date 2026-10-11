package net.millida.launcher.core

import net.millida.launcher.core.skins.TextureError
import net.millida.launcher.core.skins.TextureKind
import net.millida.launcher.core.skins.TexturePng
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TexturePngTest {

    private val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    private fun header(width: Int, height: Int, tag: String = "IHDR"): ByteArray =
        signature + byteArrayOf(0, 0, 0, 13) + tag.toByteArray() + be32(width) + be32(height) + byteArrayOf(8, 6, 0, 0, 0)

    private fun be32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private data class SizeCase(val bytes: ByteArray, val size: Pair<Int, Int>?, val why: String)

    @Test
    fun `размер читается из заголовка IHDR и только из него`() {
        val cases = listOf(
            SizeCase(header(64, 64), 64 to 64, "обычный скин"),
            SizeCase(header(128, 64), 128 to 64, "HD-плащ"),
            SizeCase(header(300, 70000), 300 to 70000, "старшие байты размера не теряются"),
            SizeCase(header(64, 64).copyOf(23), null, "обрезанный заголовок не читается за краем массива"),
            SizeCase(header(64, 64, tag = "IDAT"), null, "первым идёт не IHDR — размера в этом месте нет"),
            SizeCase(header(0, 64), null, "нулевая ширина — битая картинка"),
            SizeCase(header(64, 0), null, "нулевая высота — битая картинка"),
            SizeCase(header(-1, 64), null, "ширина со старшим битом — отрицательная, не огромная"),
            SizeCase("GIF89a-------------------".toByteArray(), null, "не PNG"),
            SizeCase(ByteArray(0), null, "пустой массив"),
        )
        for (case in cases) {
            assertEquals("${case.why}: размер", case.size, TexturePng.size(case.bytes))
        }
    }

    @Test
    fun `сигнатура PNG проверяется по первым байтам`() {
        assertTrue("заголовок PNG узнаётся", TexturePng.isPng(header(64, 64)))
        assertFalse("три байта сигнатуры — ещё не PNG", TexturePng.isPng(signature.copyOf(3)))
        assertFalse("пустой массив — не PNG", TexturePng.isPng(ByteArray(0)))
        assertFalse("JPEG — не PNG", TexturePng.isPng(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
    }

    @Test
    fun `base64 из data-URL и с переносами читается обратно в те же байты`() {
        val bytes = header(64, 32)
        val url = TexturePng.dataUrl(bytes)

        assertTrue("data-URL помечен как PNG", url.startsWith("data:image/png;base64,"))
        assertArrayEquals("data-URL раскодируется без потерь", bytes, TexturePng.decode(url))
        val wrapped = TexturePng.encode(bytes).chunked(16).joinToString("\n")
        assertArrayEquals("переносы строк из буфера обмена не ломают картинку", bytes, TexturePng.decode(wrapped))
    }

    private data class DecodeCase(val input: String, val message: String, val why: String)

    @Test
    fun `битый base64 отвергается строго и с текстом для игрока`() {
        val cases = listOf(
            DecodeCase("", "Пустая картинка", "пустая строка"),
            DecodeCase("data:image/png;base64,", "Пустая картинка", "data-URL без данных"),
            DecodeCase("   \n ", "Пустая картинка", "одни пробелы"),
            DecodeCase("iVBORw0KGgo=!!", "Битый PNG", "мусор в конце не пропускается мягким декодером"),
            DecodeCase("Привет, это не картинка", "Битый PNG", "вставленная фраза не превращается в байты"),
        )
        for (case in cases) {
            val error = runCatching { TexturePng.decode(case.input) }.exceptionOrNull()
            assertTrue("«${case.input}»: ${case.why} — нужен TextureError, а не $error", error is TextureError)
            assertEquals("«${case.input}»: ${case.why}", case.message, error!!.message)
        }
    }

    @Test
    fun `форма принятой текстуры несёт масштаб и признак старого скина`() {
        val hd = TexturePng.validate(TextureKind.SKIN, header(256, 128))
        assertEquals("256x128 — старый скин в четыре раза крупнее", 4, hd.scale)
        assertTrue(hd.legacy)
        assertFalse("у старого скина нет второго слоя", hd.hasOverlay)

        val modern = TexturePng.validate(TextureKind.SKIN, header(128, 128))
        assertEquals(2, modern.scale)
        assertTrue("квадратный скин рисуется со вторым слоем", modern.hasOverlay)

        val cape = TexturePng.validate(TextureKind.CAPE, header(64, 32))
        assertEquals(1, cape.scale)
        assertTrue("лист плаща — формат 64x32", cape.legacy)
    }

    @Test
    fun `ошибка размера называет фактический размер`() {
        val error = runCatching { TexturePng.validate(TextureKind.SKIN, header(65, 64)) }.exceptionOrNull()
        assertEquals("Скин должен быть 64x64 или 64x32, а тут 65x64", error?.message)

        val broken = runCatching { TexturePng.validate(TextureKind.SKIN, signature) }.exceptionOrNull()
        assertTrue("PNG без IHDR — понятная ошибка, а не выход за массив", broken is TextureError)
        assertTrue(broken!!.message!!.contains("размер"))
    }

    private data class KindCase(val raw: String, val kind: TextureKind?, val why: String)

    @Test
    fun `вид текстуры узнаётся по папке и по имени в протоколе`() {
        val cases = listOf(
            KindCase("skin", TextureKind.SKIN, "имя в запросе к серверу"),
            KindCase("skins", TextureKind.SKIN, "имя папки библиотеки"),
            KindCase("CAPE", TextureKind.CAPE, "регистр не важен"),
            KindCase("capes", TextureKind.CAPE, "папка плащей"),
            KindCase("hat", null, "неизвестный вид — ошибка, а не скин по умолчанию"),
            KindCase("", null, "пустая строка"),
        )
        for (case in cases) {
            val result = runCatching { TextureKind.of(case.raw) }
            assertEquals("TextureKind.of(«${case.raw}»): ${case.why}", case.kind, result.getOrNull())
            if (case.kind == null) assertTrue("«${case.raw}»: ${case.why}", result.exceptionOrNull() is TextureError)
        }
        assertNull("вид с пробелом не угадывается молча", runCatching { TextureKind.of("cape ") }.getOrNull())
    }
}
