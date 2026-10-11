package net.millida.launcher.core

import net.millida.launcher.core.content.PhoneBlockedMods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PhoneBlockedModsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fabricJar(dir: File, name: String, id: String): File {
        val jar = File(dir, name)
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write("""{"schemaVersion":1,"id":"$id","version":"1.0"}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("a/A.class"))
            zip.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            zip.closeEntry()
        }
        return jar
    }

    @Test
    fun `моды с нативным кодом только для ПК отключаются перед запуском`() {
        data class Case(val file: String, val id: String, val disabled: Boolean, val why: String)

        val cases = listOf(
            Case("Flashback-0.40.0-for-MC26.1.jar", "flashback", true, "Flashback грузит ImGui только под x86 и роняет игру до меню"),
            Case("Axiom-5.4.2-for-MC26.1.jar", "axiom", true, "Axiom грузит тот же ImGui"),
            Case("sodium-0.8.12.jar", "sodium", false, "обычный мод не трогается"),
            Case("FLASHBACK-upper.jar", "Flashback", true, "регистр id не важен"),
        )
        val mods = temp.newFolder("mods")
        cases.forEach { fabricJar(mods, it.file, it.id) }

        val reasons = PhoneBlockedMods.disableIn(mods)

        for (case in cases) {
            assertEquals(
                "«${case.file}» отключён: ${case.why}",
                case.disabled,
                File(mods, case.file + ".disabled").isFile,
            )
            assertEquals("«${case.file}» остался включённым: ${case.why}", !case.disabled, File(mods, case.file).isFile)
        }
        assertEquals("игроку называется каждый отключённый мод", 3, reasons.size)
        assertTrue("повторный запуск ничего не меняет", PhoneBlockedMods.disableIn(mods).isEmpty())
    }
}
