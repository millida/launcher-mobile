package net.millida.launcher.core

import net.millida.launcher.core.loader.ForgeProcessorOutput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ForgeProcessorOutputTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `выход шага с чужим хешем принимается только как читаемый непустой архив`() {
        val good = archive("net/minecraft/Main.class" to "class", "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0")
        val truncated = good.copyOf(good.size / 2)

        data class Case(val content: ByteArray?, val sha: (ByteArray) -> String?, val accepted: Boolean, val why: String)

        val other = "0".repeat(40)
        val cases = listOf(
            Case(good, { Hashing.sha1(it) }, true, "хеш совпал — выход верный без вопросов"),
            Case(good, { other }, true, "системный zlib Android 12-14 жмёт иначе, а архив целый — установка не должна падать"),
            Case("patched".toByteArray(), { Hashing.sha1(it) }, true, "совпавший хеш не требует, чтобы выход был архивом"),
            Case("patched".toByteArray(), { other }, false, "не архив с чужим хешем — выход битый"),
            Case(truncated, { other }, false, "обрезанный архив с чужим хешем — шаг не дописал файл"),
            Case(archive(), { other }, false, "пустой архив с чужим хешем — шаг ничего не собрал"),
            Case(ByteArray(0), { other }, false, "пустой файл не выдаётся за собранный"),
            Case(null, { other }, false, "файла нет — шаг ничего не собрал"),
            Case(null, { null }, false, "без хеша отсутствующий файл тоже не принимается"),
            Case("anything".toByteArray(), { null }, true, "без хеша в профиле достаточно, что файл есть"),
        )
        cases.forEachIndexed { index, case ->
            val file = File(folder.root, "out-$index.jar")
            case.content?.let { file.writeBytes(it) }
            val sha = case.sha(case.content ?: ByteArray(0))
            assertEquals("проверка выхода шага сломалась: ${case.why}", case.accepted, ForgeProcessorOutput.accepted(file, sha))
        }
    }
}
