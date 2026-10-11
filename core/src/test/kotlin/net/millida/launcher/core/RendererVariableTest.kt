package net.millida.launcher.core

import net.millida.launcher.core.runtime.RendererVariable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Sodium stops the game when POJAV_RENDERER is in the environment. What is
 * pinned: the renamed runtime never gets the old name, and a runtime that
 * still reads it is never left without the renderer.
 */
class RendererVariableTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `какие переменные рендерера ставятся`() {
        data class Case(val layer: Boolean, val bridge: Boolean, val sdl: Boolean, val expected: List<String>, val why: String)

        val own = listOf(RendererVariable.OWN)
        val both = listOf(RendererVariable.OWN, RendererVariable.LEGACY)
        val cases = listOf(
            Case(true, true, false, own, "свежий рантайм на GLFW: старого имени нет, Sodium запускается"),
            Case(true, true, true, own, "свежий рантайм на SDL3: Sodium на 26.3 запускается"),
            Case(true, false, true, own, "на SDL3 мост не грузится, его старое имя ничего не решает"),
            Case(true, false, false, both, "мост GLFW ещё старый и без POJAV_RENDERER падает на null"),
            Case(false, true, false, both, "оконный слой ещё старый и без POJAV_RENDERER не найдёт рендерер"),
            Case(false, true, true, both, "старый оконный слой грузится и на SDL3"),
            Case(false, false, false, both, "весь рантайм старый — как было до переименования"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, RendererVariable.names(case.layer, case.bridge, case.sdl))
        }
    }

    @Test
    fun `переименование узнаётся по байтам`() {
        data class Case(val bytes: ByteArray, val expected: Boolean, val why: String)

        val cases = listOf(
            Case(bytes("TMPDIR\u0000MILLIDA_RENDER\u0000opengles"), true, "строка переименована скриптом компонентов"),
            Case(bytes("TMPDIR\u0000POJAV_RENDERER\u0000opengles"), false, "библиотека из старого пакета рантайма"),
            Case(bytes("MILLIDA_RENDE"), false, "обрывок имени не считается переименованием"),
            Case(bytes("MILLIDA_RENDER"), true, "имя в самом конце файла тоже находится"),
            Case(ByteArray(0), false, "пустой файл не переименован"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, RendererVariable.renamed(case.bytes))
        }
    }

    @Test
    fun `рантайм на телефоне проверяется по файлам`() {
        data class Case(val layer: String?, val jars: List<File>, val expectedLayer: Boolean, val expectedBridge: Boolean, val why: String)

        val renamedJar = jar("renamed.jar", RendererVariable.BRIDGE_CLASS to "\u0001\u0000\u000eMILLIDA_RENDER")
        val oldJar = jar("old.jar", RendererVariable.BRIDGE_CLASS to "\u0001\u0000\u000ePOJAV_RENDERER")
        val millidaGlfw = jar("millida-glfw.jar", "net/millida/glfw/Window.class" to "class")
        val ownGlfw = jar("own-glfw.jar", RendererVariable.BRIDGE_CLASS to "\u0001\u0000\u0004none")
        val renamedGl = jar("renamed-gl.jar", "org/lwjgl/opengl/GL.class" to "\u0001\u0000\u000eMILLIDA_RENDER")
        val oldGl = jar("old-gl.jar", "org/lwjgl/opengl/GL.class" to "\u0001\u0000\u000ePOJAV_RENDERER")
        val cases = listOf(
            Case("x\u0000MILLIDA_RENDER\u0000", listOf(millidaGlfw, renamedJar), true, true, "оба файла из свежего пакета"),
            Case("x\u0000POJAV_RENDERER\u0000", listOf(oldJar), false, false, "оба файла из старого пакета"),
            Case(null, listOf(oldJar), false, false, "нет оконного слоя — считается старым, старое имя остаётся"),
            Case("x\u0000MILLIDA_RENDER\u0000", listOf(millidaGlfw), true, true, "без классов GLFW в мосте старое имя никто не читает"),
            Case("x\u0000MILLIDA_RENDER\u0000", listOf(ownGlfw, renamedGl), true, true, "свой GLFW без имён первым в списке не прячет остальные классы: так POJAV_RENDERER попадал в окружение у всех"),
            Case("x\u0000MILLIDA_RENDER\u0000", listOf(ownGlfw, oldGl), true, false, "старый GL за своим GLFW всё ещё читает старое имя"),
            Case("x\u0000MILLIDA_RENDER\u0000", listOf(File(temp.root, "missing.jar"), oldJar), true, false, "пропавший jar не прячет старый мост за ним"),
        )
        cases.forEach { case ->
            val dir = temp.newFolder()
            case.layer?.let { File(dir, RendererVariable.WINDOW_LAYER).writeBytes(bytes(it)) }
            assertEquals(case.why, case.expectedLayer, RendererVariable.windowLayerRenamed(dir))
            assertEquals(case.why, case.expectedBridge, RendererVariable.bridgeRenamed(case.jars))
        }
    }

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun jar(name: String, vararg entries: Pair<String, String>): File {
        val file = File(temp.root, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (entry, content) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(bytes(content))
                zip.closeEntry()
            }
        }
        return file
    }
}
