package net.millida.launcher.core

import net.millida.launcher.core.runtime.RuntimeImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun bundle(vararg entries: Pair<String, String>): ByteArray {
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

    private val description = """
        {
          "javaHome": "jre",
          "bridge": ["bridge/millida-glfw.jar", "bridge/lwjgl-glfw-classes.jar"],
          "rendererId": "gl4es",
          "rendererLabel": "GL4ES 1.1.4",
          "rendererDir": "native",
          "rendererLibrary": "libgl4es_114.so",
          "environment": {"LIBGL_ES": "3"}
        }
    """.trimIndent()

    private fun fullBundle() = bundle(
        "millida-runtime.json" to description,
        "jre/bin/java" to "elf",
        "jre/lib/server/libjvm.so" to "elf",
        "bridge/millida-glfw.jar" to "jar",
        "bridge/lwjgl-glfw-classes.jar" to "jar",
        "native/libgl4es_114.so" to "elf",
        "native/libglapi.so" to "elf",
    )

    @Test
    fun `пакет рантайма ставится и описывается по своему файлу`() {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        assertNull("до установки рантайма нет", RuntimeImport.installed(layout))

        val runtime = RuntimeImport.install(layout, fullBundle().inputStream())

        assertEquals("java home берётся из описания", "jre", runtime.javaHome.name)
        assertTrue(
            "виртуальная машина должна лежать внутри java home",
            File(File(runtime.javaHome, "lib"), "server").isDirectory,
        )
        assertEquals(
            "наш модуль окна обязан идти первым: иначе классы игры возьмутся из чужого jar",
            "millida-glfw.jar",
            runtime.bridgeClasspath.first().name,
        )
        assertEquals("рендерер из описания", "libgl4es_114.so", runtime.renderer.library)
        assertEquals("переменные окружения рендерера сохраняются", "3", runtime.renderer.environment["LIBGL_ES"])
        assertTrue("папка рендерера существует", runtime.rendererDir.isDirectory)

        val remembered = RuntimeImport.installed(layout)
        assertNotNull("после установки рантайм должен находиться сам", remembered)
        assertEquals(runtime.javaHome.absolutePath, remembered!!.javaHome.absolutePath)

        RuntimeImport.clear(layout)
        assertNull("удаление снимает установку", RuntimeImport.installed(layout))
    }

    @Test
    fun `пакет без виртуальной машины отклоняется`() {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        val broken = bundle(
            "millida-runtime.json" to description,
            "bridge/millida-glfw.jar" to "jar",
        )

        try {
            RuntimeImport.install(layout, broken.inputStream())
            fail("пакет без libjvm.so не должен считаться рантаймом")
        } catch (expected: IOException) {
            assertTrue(
                "ошибка обязана объяснять, чего не хватает",
                expected.message!!.contains("Java") || expected.message!!.contains("libjvm"),
            )
        }
        assertNull("после отказа установленного рантайма быть не должно", RuntimeImport.installed(layout))
    }

    @Test
    fun `путь наружу пакета не распаковывается`() {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        val evil = bundle(
            "millida-runtime.json" to description,
            "../outside.txt" to "нет",
        )

        try {
            RuntimeImport.install(layout, evil.inputStream())
            fail("запись за пределы папки рантайма должна отклоняться")
        } catch (expected: IOException) {
            assertTrue("сообщение обязано быть внятным", expected.message!!.isNotBlank())
        }
        assertTrue(
            "файл наружу создаваться не должен",
            !File(layout.root.parentFile, "outside.txt").exists(),
        )
    }
}
