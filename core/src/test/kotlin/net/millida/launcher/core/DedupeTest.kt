package net.millida.launcher.core

import net.millida.launcher.core.maintenance.Dedupe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The same mod in five builds is five copies of the same bytes. Collapsing them
 * must never cost a build its file, so both halves are pinned: what counts as a
 * duplicate, and that a build still has its file afterwards.
 */
class DedupeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun mod(build: String, name: String, body: ByteArray): File {
        val dir = File(folder.root, "$build/mods").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(body) }
    }

    private fun payload(seed: Int, size: Int = 200 * 1024): ByteArray =
        ByteArray(size) { index -> ((index + seed) % 251).toByte() }

    @Test
    fun `одинаковые файлы в разных сборках видны как одна группа`() {
        val body = payload(1)
        mod("one", "sodium.jar", body)
        mod("two", "sodium.jar", body)
        mod("three", "other.jar", payload(2))

        val report = Dedupe(listOf(folder.root)).scan()

        assertEquals("группа ровно одна: одинаковы только две копии", 1, report.groups.size)
        assertEquals("в группе обе копии", 2, report.groups.first().files.size)
        assertEquals(
            "освободится размер одной копии, а не двух: один файл остаётся",
            body.size.toLong(),
            report.reclaimable,
        )
    }

    @Test
    fun `файлы одного размера, но разного содержимого, не считаются копиями`() {
        mod("one", "a.jar", payload(1))
        mod("two", "b.jar", payload(2))

        val report = Dedupe(listOf(folder.root)).scan()

        assertTrue("совпадение размера — не повод их склеивать", report.groups.isEmpty())
        assertEquals("оба файла всё равно просмотрены", 2, report.scannedFiles)
    }

    @Test
    fun `после склейки файл на месте у каждой сборки и содержимое то же`() {
        val body = payload(7)
        val first = mod("one", "sodium.jar", body)
        val second = mod("two", "sodium.jar", body)

        val dedupe = Dedupe(listOf(folder.root))
        val result = dedupe.run(dedupe.scan())

        assertEquals("склеена одна копия", 1, result.linked)
        assertEquals("сбоев нет", 0, result.failed)
        assertTrue("файл первой сборки на месте", first.isFile)
        assertTrue("файл второй сборки на месте", second.isFile)
        assertTrue("содержимое не изменилось", second.readBytes().contentEquals(body))
        assertTrue(
            "временных остатков не осталось",
            folder.root.walkTopDown().none { it.name.endsWith(".dedupe") },
        )
    }

    @Test
    fun `мелочь не трогается`() {
        val body = ByteArray(1024) { 1 }
        mod("one", "tiny.jar", body)
        mod("two", "tiny.jar", body)

        val report = Dedupe(listOf(folder.root)).scan()

        assertTrue("на мелких файлах ссылка не выгоднее самого файла", report.groups.isEmpty())
    }

    @Test
    fun `прерванная склейка возвращает копию на место`() {
        val body = payload(9)
        val file = mod("one", "sodium.jar", body)
        val leftover = File(file.parentFile, file.name + ".dedupe")
        file.renameTo(leftover)

        val restored = Dedupe(listOf(folder.root)).collectGarbage()

        assertEquals("остаток опознан", 1, restored)
        assertTrue("сборка снова со своим файлом", file.isFile)
        assertTrue("содержимое то же", file.readBytes().contentEquals(body))
    }
}
