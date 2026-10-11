package net.millida.launcher.core

import net.millida.launcher.core.runtime.RuntimeImport
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The pack carries several ways to draw, and the wrong one shows either a black
 * screen or broken blocks. What the settings offer and what the game is started
 * with have to be the same list, so both are pinned here.
 */
class RuntimeRendererChoiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val description = """
        {
          "javaHome": "jre",
          "bridge": ["bridge/lwjgl-glfw-classes.jar"],
          "rendererDir": "native",
          "renderers": [
            {"id": "krypton", "label": "Krypton", "library": "libng_gl4es.so", "environment": {"LIBGL_ES": "3"}},
            {"id": "angle", "label": "ANGLE", "library": "libng_gl4es.so", "eglLibrary": "libEGL_angle.so"},
            {"id": "zink", "label": "Zink", "library": "libglxshim.so", "eglLibrary": "libEGL_mesa.so"}
          ]
        }
    """.trimIndent()

    private fun pack(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf(
                "millida-runtime.json" to description,
                "jre/bin/java" to "elf",
                "jre/lib/server/libjvm.so" to "elf",
                "bridge/lwjgl-glfw-classes.jar" to "jar",
                "native/libng_gl4es.so" to "elf",
                "native/libglxshim.so" to "elf",
                "native/libEGL_mesa.so" to "elf",
                "native/libEGL_angle.so" to "elf",
            ).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `рендерер выбирается по своему имени, а неизвестное имя берёт первый`() {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        RuntimeImport.install(layout, pack().inputStream())

        val offered = RuntimeImport.renderers(layout)
        assertEquals(
            "настройки показывают ровно то, что лежит в пакете",
            listOf("krypton", "angle", "zink"),
            offered.map { it.id },
        )

        val zink = RuntimeImport.installed(layout, rendererId = "zink")
        assertEquals("выбран заказанный рендерер", "libglxshim.so", zink?.renderer?.library)
        assertEquals("вместе с ним едет его же слой EGL", "libEGL_mesa.so", zink?.renderer?.eglLibrary)

        val unknown = RuntimeImport.installed(layout, rendererId = "нет такого")
        assertEquals(
            "неизвестное имя не оставляет игру без рендерера",
            "libng_gl4es.so",
            unknown?.renderer?.library,
        )

        val krypton = RuntimeImport.installed(layout, rendererId = "krypton")
        assertEquals(
            "окружение берётся от выбранного рендерера",
            mapOf("LIBGL_ES" to "3"),
            krypton?.renderer?.environment,
        )
    }
}
