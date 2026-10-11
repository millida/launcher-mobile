package net.millida.launcher.core

import net.millida.launcher.core.packs.PackArchive
import net.millida.launcher.core.packs.PackLaunches
import net.millida.launcher.core.packs.PhoneFit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Catalogue archives packed from a partner's folder are written the way yazl
 * streams them: every entry has its sizes and checksum after the data (a data
 * descriptor), and jars are stored uncompressed. Android's ZipInputStream
 * reads the first such stored jar as empty and then ends the archive with no
 * error, so OneBlock unpacked to `config/` alone, lost its launch description
 * and started as bare 1.7.10. Pinned here: an archive of that shape unpacks
 * whole, the description included.
 */
class PackArchiveDescriptorTest {

    private lateinit var root: File

    @Before
    fun start() {
        root = File(System.getProperty("java.io.tmpdir"), "millida-pack-descriptor-test-" + System.nanoTime())
        root.mkdirs()
    }

    @After
    fun stop() {
        root.deleteRecursively()
    }

    private class Entry(val name: String, val data: ByteArray, val stored: Boolean)

    /** The byte layout yazl writes for read streams: flag bit 3, zero sizes in the local header, a descriptor after the data. */
    private fun yazlArchive(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        entries.forEach { entry ->
            val crc = CRC32().apply { update(entry.data) }.value
            val body = if (entry.stored) entry.data else deflate(entry.data)
            val name = entry.name.toByteArray(Charsets.UTF_8)
            val method = if (entry.stored) 0 else 8
            val offset = out.size()
            out.int32(0x04034b50); out.int16(20); out.int16(0x0808); out.int16(method); out.int16(0); out.int16(0x5021)
            out.int32(0); out.int32(0); out.int32(0); out.int16(name.size); out.int16(0); out.write(name)
            out.write(body)
            out.int32(0x08074b50); out.int32(crc.toInt()); out.int32(body.size); out.int32(entry.data.size)
            central.int32(0x02014b50); central.int16(20); central.int16(20); central.int16(0x0808); central.int16(method)
            central.int16(0); central.int16(0x5021); central.int32(crc.toInt()); central.int32(body.size); central.int32(entry.data.size)
            central.int16(name.size); central.int16(0); central.int16(0); central.int16(0); central.int16(0); central.int32(0)
            central.int32(offset); central.write(name)
        }
        val centralAt = out.size()
        val directory = central.toByteArray()
        out.write(directory)
        out.int32(0x06054b50); out.int16(0); out.int16(0); out.int16(entries.size); out.int16(entries.size)
        out.int32(directory.size); out.int32(centralAt); out.int16(0)
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        DeflaterOutputStream(buffer, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { it.write(data) }
        return buffer.toByteArray()
    }

    private fun ByteArrayOutputStream.int16(value: Int) {
        write(value and 0xff); write((value shr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.int32(value: Int) {
        int16(value and 0xffff); int16((value ushr 16) and 0xffff)
    }

    @Test
    fun `архив из папки партнёра распаковывается целиком вместе с описанием запуска`() {
        val spec = """{"slug":"oneblock-metalabs","mainClass":"com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread","classPath":["libraries","versions/1.7.10ANGELICA/1.7.10ANGELICA.jar"],"javaMajor":21}"""
        val cases = listOf(
            Entry("config/lwjgl3ify.cfg", "general {}".toByteArray(), stored = false) to "текстовые файлы сжаты и шли и раньше",
            Entry("libraries/lwjgl3/lwjgl-sdl-3.4.2.jar", ByteArray(4096) { it.toByte() }, stored = true) to "первый несжатый jar с дескриптором — на нём Android обрывал архив",
            Entry(PhoneFit.PACK_LAUNCH_FILE, spec.toByteArray(), stored = false) to "описание запуска лежит после libraries по алфавиту и пропадало вместе с ними",
            Entry("mods/1.7.10/lwjgl3ify-3.0.0.jar", ByteArray(2048) { (it * 7).toByte() }, stored = true) to "моды сборки после описания",
            Entry("versions/1.7.10ANGELICA/1.7.10ANGELICA.jar", ByteArray(1024) { (it * 3).toByte() }, stored = true) to "клиент игры — последний в архиве",
        )
        val archive = File(root, "pack.zip").also { it.writeBytes(yazlArchive(cases.map { it.first })) }
        val unpacked = PackArchive.unpack(archive, File(root, "unpacked"))

        cases.forEach { (entry, why) ->
            val file = File(unpacked, entry.name)
            assertTrue("${entry.name}: $why — файла нет после распаковки", file.isFile)
            assertTrue("${entry.name}: $why — содержимое не совпало", entry.data.contentEquals(file.readBytes()))
        }
        assertEquals("корень описания найден — установка пойдёт своим запуском, а не голой 1.7.10", unpacked, PackLaunches.launchRoot(unpacked))
    }
}
