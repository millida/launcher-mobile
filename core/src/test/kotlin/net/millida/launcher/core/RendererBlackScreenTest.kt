package net.millida.launcher.core

import net.millida.launcher.core.runtime.DeviceGraphics
import net.millida.launcher.core.runtime.GpuFamily
import net.millida.launcher.core.runtime.RendererChoice
import net.millida.launcher.core.runtime.RendererFallback
import net.millida.launcher.core.runtime.RendererKind
import net.millida.launcher.core.runtime.RendererVerdict
import net.millida.launcher.core.runtime.RuntimeManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererBlackScreenTest {
    private fun entry(id: String, library: String) =
        RuntimeManifest.RendererEntry(id = id, label = id, url = "", sha256 = "", library = library, eglLibrary = "")

    private val krypton = entry("krypton", "libng_gl4es.so")
    private val gl4es = entry("gl4es", "libgl4es_114.so")
    private val mobileGlues = entry("mobileglues", "libmobileglues.so")
    private val all = listOf(krypton, gl4es, mobileGlues)
    private val adreno = DeviceGraphics(GpuFamily.ADRENO, vulkan11 = true)

    private fun pick(version: String, failed: Set<String> = emptySet()): String =
        (RendererChoice.choose(version, "", adreno, all, "", failed) as RendererVerdict.Use).renderer.id

    @Test
    fun `без сбоев выбор прежний, после чёрного экрана Krypton уступает MobileGlues`() {
        assertEquals("krypton", pick("1.21.4"))
        assertEquals("mobileglues", pick("1.21.4", setOf("krypton")))
        assertEquals("gl4es", pick("1.16.5", setOf("krypton")))
    }

    @Test
    fun `после чёрного экрана на MobileGlues берётся другой`() {
        assertEquals("krypton", pick("1.21.4", setOf("mobileglues")))
    }

    @Test
    fun `сбой инициализации GL ловится у Zink и MobileGlues`() {
        val text = "Description: Initializing game\njava.lang.IllegalStateException: OpenGL error 1282"
        assertTrue(RendererFallback.crashedAtInit(RendererKind.MOBILEGLUES, text))
        assertFalse(RendererFallback.crashedAtInit(RendererKind.KRYPTON, text))
    }

    private val lwjglAbort = "FATAL ERROR in native method: [LWJGL] Thread[#3,Render thread,5,main]: " +
        "No context is current or a function that is not available in the current context was called. " +
        "The JVM will abort execution.\n\tat org.lwjgl.opengl.GL33C.glVertexAttribDivisor(Native Method)"

    @Test
    fun `вызов функции GL, которой у слоя нет, считается сбоем любого рендерера`() {
        for (kind in RendererKind.entries) {
            assertTrue(
                "LWJGL обрывает игру, когда слой не дал функцию: $kind надо сменить, иначе сборка падает на каждом запуске",
                RendererFallback.crashedAtInit(kind, lwjglAbort),
            )
        }
        assertFalse(
            "обычный вылет мода рендерер не трогает",
            RendererFallback.crashedAtInit(RendererKind.KRYPTON, "java.lang.NullPointerException: mod"),
        )
    }

    @Test
    fun `запуск, оборвавшийся вместе с процессом, разбирается по своему журналу при следующем`() {
        data class Case(val pending: String, val log: String, val failed: String?, val why: String)

        val entry = RendererFallback.entry("krypton", "profile-1", "26.3")
        val pending = RendererFallback.pendingStart(entry, RendererKind.KRYPTON, "game-20261012-150330.log")
        val cases = listOf(
            Case(pending, lwjglAbort, entry, "процесс умер на вызове GL — рендерер запоминается, хотя после сбоя не выполнилось ни строки"),
            Case(pending, "[12:00] [Render thread/INFO]: Stopping!", null, "журнал без сбоя рендерера: игру убила система или закрыл игрок"),
            Case(pending, "", null, "журнала уже нет — гадать нельзя"),
            Case("", lwjglAbort, null, "записи о запуске нет"),
            Case("мусор", lwjglAbort, null, "запись другого формата не читается как сбой"),
            Case(RendererFallback.pendingStart(entry, RendererKind.KRYPTON, ""), lwjglAbort, null, "запись без имени журнала ничего не доказывает"),
        )
        for (case in cases) {
            val asked = ArrayList<String>()
            val failed = RendererFallback.diedAtInit(case.pending) { name -> asked += name; case.log }
            assertEquals("оборванный запуск: ${case.why}", case.failed, failed)
            assertTrue("читается только журнал самого запуска: ${case.why}", asked.all { it == "game-20261012-150330.log" })
        }
        assertEquals(
            "запомненный рендерер пропускается автовыбором именно для этой сборки и версии",
            setOf("krypton"),
            RendererFallback.failedAtInit(setOf(entry), "profile-1", "26.3"),
        )
    }

    @Test
    fun `Unexpected error на Zink запоминается, на Krypton нет`() {
        val text = "---- Minecraft Crash Report ----\nDescription: Unexpected error"
        assertTrue(RendererFallback.crashedAtInit(RendererKind.ZINK, text))
        assertFalse(RendererFallback.crashedAtInit(RendererKind.KRYPTON, text))
    }
}
