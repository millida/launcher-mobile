package net.millida.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeUnzipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val limits = SafeUnzip.Limits(
        maxEntries = 10,
        maxBytes = 1024,
        broken = { cause -> "повреждён: $cause" },
        tooMany = "слишком много",
        tooBig = "слишком большой",
    )

    private fun deflated(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Uncompressed entries whose sizes come after the data, in a descriptor:
     * what archivers write when packing a folder on the fly. ZipInputStream on
     * Android read such an entry as empty and ended the archive silently.
     */
    private fun storedWithDescriptors(entries: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        entries.forEach { (name, data) ->
            val nameBytes = name.toByteArray()
            val crc = CRC32().also { it.update(data) }.value.toInt()
            val offset = body.size()
            body.write(le(4 + 2 + 2 + 2 + 2 + 2 + 4 + 4 + 4 + 2 + 2) {
                putInt(0x04034b50); putShort(20); putShort(0x08); putShort(0); putShort(0); putShort(0)
                putInt(0); putInt(0); putInt(0); putShort(nameBytes.size.toShort()); putShort(0)
            })
            body.write(nameBytes)
            body.write(data)
            body.write(le(16) { putInt(0x08074b50); putInt(crc); putInt(data.size); putInt(data.size) })
            central.write(le(46) {
                putInt(0x02014b50); putShort(20); putShort(20); putShort(0x08); putShort(0); putShort(0); putShort(0)
                putInt(crc); putInt(data.size); putInt(data.size); putShort(nameBytes.size.toShort()); putShort(0); putShort(0)
                putShort(0); putShort(0); putInt(0); putInt(offset)
            })
            central.write(nameBytes)
        }
        val centralStart = body.size()
        val centralBytes = central.toByteArray()
        body.write(centralBytes)
        body.write(le(22) {
            putInt(0x06054b50); putShort(0); putShort(0); putShort(entries.size.toShort()); putShort(entries.size.toShort())
            putInt(centralBytes.size); putInt(centralStart); putShort(0)
        })
        return body.toByteArray()
    }

    private fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    private fun archive(bytes: ByteArray): File = tmp.newFile("a-" + System.nanoTime() + ".zip").also { it.writeBytes(bytes) }

    private data class Case(val bytes: ByteArray, val expected: Map<String, String>?, val why: String)

    @Test
    fun `архив читается по центральному каталогу и не выпускает файлы наружу`() {
        val files = listOf("world/level.dat" to "level".toByteArray(), "world/region/r.0.0.mca" to "region".toByteArray())
        val cases = listOf(
            Case(deflated(files), mapOf("world/level.dat" to "level", "world/region/r.0.0.mca" to "region"),
                "обычный сжатый архив"),
            Case(storedWithDescriptors(files), mapOf("world/level.dat" to "level", "world/region/r.0.0.mca" to "region"),
                "несжатые записи с дескриптором: потоковое чтение обрезало их до пустых и теряло остальные"),
            Case(deflated(listOf("../evil.jar" to "x".toByteArray())), null, "выход из папки наверх"),
            Case(deflated(listOf("/abs.jar" to "x".toByteArray())), null, "абсолютный путь"),
            Case(deflated((1..11).map { "f$it" to "x".toByteArray() }), null, "записей больше предела"),
            Case(deflated(listOf("big" to ByteArray(2048))), null, "распаковка больше предела — zip-бомба"),
            Case("не архив".toByteArray(), null, "мусор вместо архива — IOException, а не падение"),
        )
        for (case in cases) {
            val target = tmp.newFolder("out-" + System.nanoTime())
            val result = runCatching { SafeUnzip.unpack(archive(case.bytes), target, limits) }
            if (case.expected == null) {
                assertTrue("ожидался отказ: ${case.why}; было $result", result.exceptionOrNull() is IOException)
                assertFalse("ничего не вышло за папку: ${case.why}", File(target.parentFile, "evil.jar").exists())
            } else {
                assertEquals("число файлов: ${case.why}", case.expected.size, result.getOrThrow())
                case.expected.forEach { (path, text) ->
                    assertEquals("содержимое $path: ${case.why}", text, File(target, path).readText())
                }
            }
        }
    }

    @Test
    fun `поток сохраняется в файл перед чтением`() {
        val file = File(tmp.root, "spool/in.zip")
        SafeUnzip.spool(ByteArrayInputStream(deflated(listOf("a" to "b".toByteArray()))), file)
        val target = tmp.newFolder("spooled")
        assertEquals("из сохранённого потока читается всё", 1, SafeUnzip.unpack(file, target, limits))
        assertEquals("b", File(target, "a").readText())
    }
}
