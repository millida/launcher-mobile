package net.millida.launcher.core

import net.millida.launcher.core.runtime.DeviceGraphics
import net.millida.launcher.core.runtime.GpuFamily
import net.millida.launcher.core.runtime.RendererCatalog
import net.millida.launcher.core.runtime.RendererChoice
import net.millida.launcher.core.runtime.RendererVerdict
import net.millida.launcher.core.runtime.RuntimeManifest
import net.millida.launcher.core.runtime.TurnipDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TurnipRendererTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun gpu(name: String, family: GpuFamily = GpuFamily.ADRENO) =
        DeviceGraphics(family, vulkan11 = true, glRenderer = name)

    private val krypton = RuntimeManifest.RendererEntry(id = "krypton", label = "Krypton", url = "", sha256 = "", library = "libng_gl4es.so")
    private val zink = RendererCatalog.asEntry(RendererCatalog.zink)
    private val turnip = RendererCatalog.asEntry(RendererCatalog.zinkTurnip)

    private fun packDir(vararg files: String): File = temp.newFolder().also { dir -> files.forEach { File(dir, it).writeText("x") } }

    private val zinkFiles = arrayOf("libglxshim.so", "libEGL_mesa.so", "libzink_dri.so", "libng_gl4es.so")

    @Test
    fun `показ только на Adreno 6xx и 7xx`() {
        val all = listOf(krypton, zink, turnip)
        assertTrue(RendererCatalog.visibleOn(all, gpu("Adreno (TM) 610")).contains(turnip))
        assertTrue(RendererCatalog.visibleOn(all, gpu("Adreno (TM) 740")).contains(turnip))
        assertTrue(RendererCatalog.visibleOn(all, gpu("Adreno (TM) 506")).none { it.id == turnip.id })
        assertTrue(RendererCatalog.visibleOn(all, gpu("Adreno (TM) 830")).none { it.id == turnip.id })
        assertTrue(RendererCatalog.visibleOn(all, gpu("Mali-G57", GpuFamily.MALI)).none { it.id == turnip.id })
        assertTrue(RendererCatalog.visibleOn(all, DeviceGraphics.UNKNOWN).none { it.id == turnip.id })
        assertTrue("Zink остаётся", RendererCatalog.visibleOn(all, gpu("Mali-G57", GpuFamily.MALI)).contains(zink))
    }

    @Test
    fun `предлагается только с драйвером и крючком линкера`() {
        val none = RendererCatalog.offered(listOf(krypton), packDir(*zinkFiles))
        assertFalse(none.any { it.id == turnip.id })

        val noHook = RendererCatalog.offered(listOf(krypton), packDir(*zinkFiles, "libvulkan_freedreno.so"))
        assertFalse(noHook.any { it.id == turnip.id })

        val inPack = RendererCatalog.offered(listOf(krypton), packDir(*zinkFiles, "libvulkan_freedreno.so", "liblinkerhook.so"))
        assertTrue(inPack.any { it.id == turnip.id })
        assertTrue(inPack.any { it.id == zink.id })

        val debugDir = packDir("libvulkan_freedreno.so")
        val inDebug = RendererCatalog.offered(listOf(krypton), packDir(*zinkFiles, "liblinkerhook.so"), listOf(debugDir))
        assertTrue(inDebug.any { it.id == turnip.id })
    }

    @Test
    fun `выбранный вручную Turnip запускается на Adreno 610`() {
        val verdict = RendererChoice.choose("26.3", "", gpu("Adreno (TM) 610"), listOf(krypton, zink, turnip), "zink-turnip")
        assertTrue(verdict is RendererVerdict.Use)
        assertEquals("zink-turnip", (verdict as RendererVerdict.Use).renderer.id)
    }

    @Test
    fun `выбранный вручную Turnip на Mali заменяется`() {
        val verdict = RendererChoice.choose("1.20.1", "", gpu("Mali-G57", GpuFamily.MALI), listOf(krypton, zink, turnip), "zink-turnip")
        assertEquals("krypton", (verdict as RendererVerdict.Use).renderer.id)
    }

    @Test
    fun `автовыбор Turnip не берёт никогда`() {
        val onlyTurnip = RendererChoice.choose("1.20.1", "", gpu("Adreno (TM) 740"), listOf(turnip, krypton), "")
        assertEquals("krypton", (onlyTurnip as RendererVerdict.Use).renderer.id)
        val failed = RendererChoice.choose("1.20.1", "", gpu("Adreno (TM) 740"), listOf(krypton, turnip), "", failedAtInit = setOf("krypton"))
        assertTrue(failed is RendererVerdict.Use)
        assertFalse((failed as RendererVerdict.Use).renderer.id == "zink-turnip")
    }

    @Test
    fun `папка драйвера собирается из крючка пакета и драйвера`() {
        val pack = packDir("liblinkerhook.so")
        val source = File(temp.newFolder(), "libvulkan_freedreno.so").also { it.writeText("turnip") }
        val target = File(temp.newFolder(), "turnip-driver")
        TurnipDriver.prepare(pack, source, target)
        assertEquals("turnip", File(target, "libvulkan_freedreno.so").readText())
        assertTrue(File(target, "liblinkerhook.so").isFile)
        source.writeText("turnip-newer-build")
        source.setLastModified(System.currentTimeMillis() + 5_000)
        TurnipDriver.prepare(pack, source, target)
        assertEquals("turnip-newer-build", File(target, "libvulkan_freedreno.so").readText())
        assertNull(RendererCatalog.turnipSource(temp.newFolder()))
        assertNotNull(RendererCatalog.turnipSource(source.parentFile))
    }
}
