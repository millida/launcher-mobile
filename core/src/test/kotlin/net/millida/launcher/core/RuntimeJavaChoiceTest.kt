package net.millida.launcher.core

import net.millida.launcher.core.runtime.RuntimeImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeJavaChoiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val description = """
        {
          "jres": [
            {"major": 21, "path": "jre-21"},
            {"major": 25, "path": "jre-25"}
          ],
          "javaHome": "jre-25",
          "bridge": ["bridge/millida-glfw.jar"],
          "rendererId": "gl4es",
          "rendererDir": "native",
          "rendererLibrary": "libgl4es_114.so"
        }
    """.trimIndent()

    private fun bundle(): ByteArray {
        val entries = listOf(
            "millida-runtime.json" to description,
            "jre-21/bin/java" to "elf",
            "jre-21/lib/server/libjvm.so" to "elf",
            "jre-25/bin/java" to "elf",
            "jre-25/lib/server/libjvm.so" to "elf",
            "bridge/millida-glfw.jar" to "jar",
            "native/libgl4es_114.so" to "elf",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `java выбирается по требованию сборки`() {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        RuntimeImport.install(layout, bundle().inputStream())

        assertEquals("в пакете две версии Java", listOf(21, 25), RuntimeImport.availableJava(layout))

        data class Case(val required: Int, val expected: String, val why: String)

        val cases = listOf(
            Case(8, "jre-21", "для старой сборки годится любая новее"),
            Case(17, "jre-21", "ближайшая подходящая, а не самая новая"),
            Case(21, "jre-21", "точное совпадение"),
            Case(22, "jre-25", "22 нет — берётся следующая старшая"),
            Case(25, "jre-25", "точное совпадение на новой версии"),
        )

        for (case in cases) {
            val runtime = RuntimeImport.installed(layout, case.required)
            assertNotNull("для Java ${case.required} рантайм должен находиться", runtime)
            assertEquals(
                "Java ${case.required}: ${case.why}",
                case.expected,
                runtime!!.javaHome.name,
            )
        }
    }

    @Test
    fun `требование новее всего пакета объясняется словами`() {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        RuntimeImport.install(layout, bundle().inputStream())

        try {
            RuntimeImport.installed(layout, 33)?.let {
                fail("сборка требует Java 33, а в пакете максимум 25 — молча запускать нельзя")
            }
        } catch (expected: IOException) {
            assertTrue(
                "в ошибке должны быть и требование, и что есть в пакете",
                expected.message!!.contains("33") && expected.message!!.contains("25"),
            )
        }
    }

    @Test
    fun `без требования берётся самая новая java`() {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        val runtime = RuntimeImport.install(layout, bundle().inputStream())
        assertEquals("при установке показываем самую новую", "jre-25", runtime.javaHome.name)
    }
}
