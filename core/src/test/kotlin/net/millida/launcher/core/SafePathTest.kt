package net.millida.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SafePathTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `путь наружу корня отклоняется`() {
        val root = temp.newFolder("root")
        val rejected = listOf(
            "../secret.txt",
            "mods/../../secret.txt",
            "/etc/passwd",
            "C:/Windows/win.ini",
        )

        for (candidate in rejected) {
            try {
                SafePath.join(root, candidate)
                fail("путь $candidate вышел из корня, но был принят")
            } catch (expected: IOException) {
                assertTrue("ошибка должна объяснять причину", expected.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `нормальные пути внутри корня принимаются`() {
        val root = temp.newFolder("root")
        val accepted = listOf("mods/sodium.jar", "config/sodium/options.json", "saves/Мир 1/level.dat")

        for (candidate in accepted) {
            val target = SafePath.join(root, candidate)
            assertTrue("$candidate должен остаться внутри корня", SafePath.isInside(root, target))
        }
    }

    @Test
    fun `имя файла чистится от опасных символов`() {
        data class Case(val raw: String, val expected: String)

        val cases = listOf(
            Case("sodium-0.5.8.jar", "sodium-0.5.8.jar"),
            Case("../../evil.jar", ".._.._evil.jar"),
            Case("моды/жир.jar", "моды_жир.jar"),
            Case("   ", "file"),
        )

        for (case in cases) {
            assertEquals("имя ${case.raw} очищено неверно", case.expected, SafePath.fileName(case.raw))
        }
    }
}
