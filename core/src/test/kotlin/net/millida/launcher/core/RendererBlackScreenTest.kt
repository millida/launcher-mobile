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

    @Test
    fun `Unexpected error на Zink запоминается, на Krypton нет`() {
        val text = "---- Minecraft Crash Report ----\nDescription: Unexpected error"
        assertTrue(RendererFallback.crashedAtInit(RendererKind.ZINK, text))
        assertFalse(RendererFallback.crashedAtInit(RendererKind.KRYPTON, text))
    }
}
