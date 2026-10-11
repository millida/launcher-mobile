package net.millida.launcher.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.profile.ContentFile
import net.millida.launcher.core.runtime.DeviceGraphics
import net.millida.launcher.core.runtime.GpuFamily
import net.millida.launcher.core.runtime.MobileGluesSetup
import net.millida.launcher.core.runtime.RendererCatalog
import net.millida.launcher.core.runtime.RendererChoice
import net.millida.launcher.core.runtime.RendererKind
import net.millida.launcher.core.runtime.RendererVerdict
import net.millida.launcher.core.runtime.RuntimeManifest
import net.millida.launcher.core.runtime.SdlGraphics
import net.millida.launcher.core.runtime.SdlLaunch
import net.millida.launcher.core.runtime.SodiumFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MobileGluesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun entry(id: String, label: String, library: String, egl: String = "") =
        RuntimeManifest.RendererEntry(id = id, label = label, url = "", sha256 = "", library = library, eglLibrary = egl)

    private val krypton = entry("krypton", "Krypton (GLES 3)", "libng_gl4es.so")
    private val gl4es = entry("gl4es", "GL4ES 1.1.4", "libgl4es_114.so")
    private val mobileGlues = entry("mobileglues", "MobileGlues (для Sodium)", "libmobileglues.so", "libmobileglues.so")
    private val zink = RendererCatalog.asEntry(RendererCatalog.zink)
    private val all = listOf(krypton, gl4es, zink, mobileGlues)

    private val adreno = DeviceGraphics(GpuFamily.ADRENO, vulkan11 = true)
    private val mali = DeviceGraphics(GpuFamily.MALI, vulkan11 = true)

    @Test
    fun `Sodium и его форки узнаются по id и по имени файла`() {
        data class Case(val modId: String, val fileName: String, val expected: Boolean, val why: String)

        val cases = listOf(
            Case("sodium", "sodium-fabric-0.8.12+mc26.1.2.jar", true, "исходная жалоба: Sodium 0.8.12 на 26.1.2 без блоков на Krypton"),
            Case("embeddium", "embeddium-1.0.11+mc1.20.1.jar", true, "форк Sodium для Forge"),
            Case("rubidium", "rubidium-0.7.1.jar", true, "старый форк Sodium для Forge"),
            Case("iris", "iris-fabric-1.8.0+mc1.21.jar", true, "Iris ставится вместе с Sodium и тоже шлёт свой GLSL"),
            Case("oculus", "oculus-mc1.20.1-1.7.0.jar", true, "порт Iris для Forge"),
            Case("nvidium", "nvidium-0.2.4-beta.jar", true, "дополнение к Sodium со своими шейдерами"),
            Case("sodium-extra", "sodium-extra-0.6.0+mc1.21.jar", false, "Sodium Extra без самого Sodium не рисует мир"),
            Case("reeses-sodium-options", "reeses-sodium-options-1.8.jar", false, "меню настроек Sodium — не рендер"),
            Case("lithium", "lithium-fabric-0.15.jar", false, "оптимизация логики, к шейдерам отношения нет"),
            Case("", "sodium-fabric-mc1.20.1-0.5.11.jar", true, "у jar нет описания мода — решает имя файла"),
            Case("", "Sodium-0.6.0.jar", true, "регистр в имени файла не важен"),
            Case("", "sodium-extra-0.5.jar", false, "по имени файла Sodium Extra тоже не считается"),
            Case("", "irisshaders-addon-1.0.jar", false, "похожее начало имени без номера версии — не Iris"),
            Case("sodium", "renamed-by-player.jar", true, "id из описания важнее имени файла"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, SodiumFamily.isMember(case.modId, case.fileName))
        }
    }

    @Test
    fun `выключенный Sodium не уводит сборку на MobileGlues`() {
        fun mod(name: String, id: String, enabled: Boolean) =
            ContentFile(name, ContentKind.MOD, 1, enabled, id, id, "")

        assertTrue(
            "включённый Sodium среди других модов должен найтись",
            SodiumFamily.present(listOf(mod("lithium.jar", "lithium", true), mod("sodium.jar", "sodium", true))),
        )
        assertFalse(
            "игрок выключил Sodium — Krypton снова справляется, рендерер менять не за чем",
            SodiumFamily.present(listOf(mod("sodium.jar.disabled", "sodium", false))),
        )
        assertFalse("пустая папка модов — ванильная сборка", SodiumFamily.present(emptyList()))
    }

    @Test
    fun `сборка с Sodium запускается на MobileGlues`() {
        data class Case(
            val version: String,
            val graphics: DeviceGraphics,
            val offered: List<RuntimeManifest.RendererEntry>,
            val requested: String,
            val sodium: Boolean,
            val expected: String?,
            val why: String,
        )

        val cases = listOf(
            Case("26.1.2", adreno, all, "", true, "mobileglues",
                "Adreno 825, Sodium 0.8.12: на Krypton шейдеры чанков не собирались и мир был без блоков"),
            Case("26.1.2", adreno, all, "", false, "krypton",
                "сборка без Sodium остаётся на прежнем рендерере"),
            Case("26.1.2", adreno, all, "krypton", true, "krypton",
                "рендерер, выбранный игроком в настройках, не подменяется"),
            Case("26.1.2", adreno, listOf(krypton, gl4es, zink), "", true, "krypton",
                "в старом пакете рантайма MobileGlues нет — запуск как раньше, а не отказ"),
            Case("26.1.2", mali, all, "", true, "mobileglues",
                "для Sodium MobileGlues берётся и на Mali, как в Zalith и FCL: правило Mali сдвигается за ним"),
            Case("26.1.2", mali, all, "", false, "zink",
                "правило Mali для сборок без Sodium не тронуто"),
            Case("1.16.5", adreno, all, "", true, "krypton",
                "MobileGlues начинается с 1.17, Sodium для 1.16.5 остаётся на Krypton"),
            Case("1.17.1", adreno, all, "", true, "mobileglues",
                "1.17 — первая версия, которую тянет MobileGlues"),
            Case("1.12.2", adreno, all, "mobileglues", false, "krypton",
                "MobileGlues из настроек на старой версии заменяется, а не роняет игру"),
            Case("26.1.2", adreno, all, "gl4es", true, "mobileglues",
                "неподходящий GL4ES из настроек заменяется автовыбором, а он для Sodium — MobileGlues"),
        )
        cases.forEach { case ->
            val verdict = RendererChoice.choose(case.version, "", case.graphics, case.offered, case.requested, sodium = case.sodium)
            assertEquals(
                "${case.version} / ${case.graphics.gpu} / «${case.requested}»: ${case.why}",
                case.expected,
                (verdict as? RendererVerdict.Use)?.renderer?.id,
            )
        }
    }

    @Test
    fun `замена MobileGlues на старой версии видна игроку`() {
        val swapped = RendererChoice.choose("1.12.2", "", adreno, all, "mobileglues") as RendererVerdict.Use
        assertTrue("игрок должен узнать, что MobileGlues не тянет его версию: ${swapped.note}", swapped.note.contains("1.17"))
    }

    @Test
    fun `MobileGlues узнаётся по имени библиотеки`() {
        assertEquals(
            "по виду рендерера автовыбор и окружение запуска отличают MobileGlues от Krypton с тем же nativeId",
            RendererKind.MOBILEGLUES,
            RendererKind.of(mobileGlues.copy(nativeId = "opengles3")),
        )
        assertEquals("Krypton не путается с MobileGlues", RendererKind.KRYPTON, RendererKind.of(krypton))
    }

    @Test
    fun `настройки MobileGlues держат системный GLES`() {
        val config = Json.parseToJsonElement(MobileGluesSetup.config).jsonObject
        assertEquals(
            "ANGLE из пакета рантайма лежит на пути библиотек — MobileGlues обязан взять GLES телефона",
            2,
            config.getValue("enableANGLE").jsonPrimitive.int,
        )
        assertEquals("вычислительные шейдеры GLES на телефонах нестабильны", 0, config.getValue("enableExtComputeShader").jsonPrimitive.int)
        assertTrue("без кэша GLSL каждый запуск заново переводит все шейдеры", config.getValue("maxGlslCacheSize").jsonPrimitive.int > 0)
    }

    @Test
    fun `папка настроек MobileGlues создаётся и чинится`() {
        val gameDir = temp.newFolder()
        val folder = MobileGluesSetup.folder(gameDir)
        assertTrue("первый запуск пишет config.json", MobileGluesSetup.writeConfig(folder))
        assertEquals("файл совпадает с настройками лаунчера", MobileGluesSetup.config, File(folder, "config.json").readText())
        assertFalse("тот же файл не переписывается на каждом запуске", MobileGluesSetup.writeConfig(folder))
        File(folder, "config.json").writeText("{\"enableANGLE\": 1}")
        assertTrue("испорченный файл возвращается к настройкам лаунчера", MobileGluesSetup.writeConfig(folder))
        assertEquals(
            "без MG_DIR_PATH MobileGlues уходит в /sdcard/MG и игнорирует config.json",
            mapOf("MG_DIR_PATH" to folder.absolutePath),
            MobileGluesSetup.environment(folder),
        )
    }

    @Test
    fun `на пути SDL3 MobileGlues отдаёт и EGL`() {
        val dir = temp.newFolder()
        val library = File(dir, "libmobileglues.so").apply { writeText("elf") }
        val translated = SdlLaunch.environment(library, SdlGraphics.TRANSLATED, library)
        assertEquals(
            "контексты SDL создаются через MobileGlues, иначе он не знает о текущем контексте",
            library.absolutePath,
            translated[SdlLaunch.HINT_EGL_LIBRARY],
        )
        assertEquals("функции GL тоже из MobileGlues", library.absolutePath, translated[SdlLaunch.HINT_OPENGL_LIBRARY])
        assertFalse(
            "у других рендереров EGL остаётся системным, как было",
            SdlLaunch.environment(library, SdlGraphics.TRANSLATED).containsKey(SdlLaunch.HINT_EGL_LIBRARY),
        )
        assertFalse(
            "на прямом GLES слой перевода не грузится вовсе",
            SdlLaunch.environment(library, SdlGraphics.NATIVE_GLES, library).containsKey(SdlLaunch.HINT_EGL_LIBRARY),
        )
    }
}
