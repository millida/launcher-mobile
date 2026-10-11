package net.millida.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class AtomicFilesHashingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private data class MatchCase(val expected: String?, val verdict: Boolean, val why: String)

    private val abcSha1 = "a9993e364706816aba3e25717850c26c9cd0d89d"
    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val abcSha512 =
        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"

    @Test
    fun `атомарная запись создаёт папки, заменяет файл и не оставляет хвостов`() {
        val target = File(tmp.root, "profiles/build/options.json")
        target.atomicWriteText("первая")
        assertEquals("файл в несуществующей папке не записан", "первая", target.readText())

        target.atomicWriteText("вторая")
        assertEquals("перезапись не заменила старое содержимое", "вторая", target.readText())
        assertFalse("рядом остался .new — при следующем запуске он сойдёт за данные", File(target.parentFile, "options.json.new").exists())
        assertFalse("рядом остался .old", File(target.parentFile, "options.json.old").exists())
        assertEquals("в папке появились лишние файлы", listOf("options.json"), target.parentFile.list()?.toList())
    }

    @Test
    fun `хеши совпадают с эталонами`() {
        val bytes = "abc".toByteArray()
        val file = tmp.newFile("abc.bin").also { it.writeBytes(bytes) }
        assertEquals("SHA-1 от байтов", abcSha1, Hashing.sha1(bytes))
        assertEquals("SHA-1 от файла", abcSha1, Hashing.sha1(file))
        assertEquals("SHA-512 от файла", abcSha512, Hashing.sha512(file))
        assertEquals("SHA-256 через digest", abcSha256, Hashing.digest(file, "SHA-256"))
        assertEquals(
            "хеш пустого файла — проверка не должна ломаться на нулевой длине",
            "da39a3ee5e6b4b0d3255bfef95601890afd80709",
            Hashing.sha1(tmp.newFile("empty.bin")),
        )
    }

    @Test
    fun `большой файл хешируется целиком, а не первым буфером`() {
        val bytes = ByteArray(200_003) { (it * 31 + 7).toByte() }
        val file = tmp.newFile("big.jar").also { it.writeBytes(bytes) }
        val reference = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("хеш файла больше буфера 64 КБ разошёлся с эталоном", reference, Hashing.sha1(file))
    }

    @Test
    fun `сверка хеша выбирает алгоритм по длине и отклоняет мусор`() {
        val file = tmp.newFile("lib.jar").also { it.writeBytes("abc".toByteArray()) }
        val cases = listOf(
            MatchCase(abcSha1, true, "SHA-1 из манифеста Mojang"),
            MatchCase(abcSha256, true, "SHA-256 из каталога"),
            MatchCase(abcSha512, true, "SHA-512 из Modrinth"),
            MatchCase(abcSha1.uppercase(), true, "регистр хеша от источника не важен"),
            MatchCase(abcSha1.replaceFirst('a', 'b'), false, "подменённый файл не проходит"),
            MatchCase(abcSha1.dropLast(1), false, "хеш неизвестной длины не считается совпавшим"),
            MatchCase("z" + abcSha1.drop(1), false, "не-hex символ — мусор, а не хеш"),
            MatchCase(null, true, "нет хеша — достаточно, что файл есть"),
            MatchCase("   ", true, "пустой хеш — то же, что его отсутствие"),
        )
        for (case in cases) {
            assertEquals("«${case.expected}»: ${case.why}", case.verdict, Hashing.matches(file, case.expected))
        }
        val missing = File(tmp.root, "missing.jar")
        assertFalse("несуществующий файл без хеша не считается скачанным", Hashing.matches(missing, null))
        assertFalse("несуществующий файл с хешем не считается скачанным", Hashing.matches(missing, abcSha1))
        assertFalse("папка вместо файла не проходит сверку", Hashing.matches(tmp.newFolder("dir.jar"), null))
        assertTrue("файл остался на месте после сверки", file.isFile)
    }
}
