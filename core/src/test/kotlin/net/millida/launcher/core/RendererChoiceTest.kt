package net.millida.launcher.core

import net.millida.launcher.core.runtime.DeviceGraphics
import net.millida.launcher.core.runtime.GpuFamily
import net.millida.launcher.core.runtime.RendererCatalog
import net.millida.launcher.core.runtime.RendererChoice
import net.millida.launcher.core.runtime.RendererFallback
import net.millida.launcher.core.runtime.RendererKind
import net.millida.launcher.core.runtime.RendererVerdict
import net.millida.launcher.core.runtime.RuntimeImport
import net.millida.launcher.core.runtime.RuntimeManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RendererChoiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun entry(id: String, label: String, library: String) =
        RuntimeManifest.RendererEntry(id = id, label = label, url = "", sha256 = "", library = library)

    private val krypton = entry("krypton", "Krypton (GLES 3)", "libng_gl4es.so")
    private val gl4es = entry("gl4es", "GL4ES 1.1.4", "libgl4es_114.so")
    private val zink = RendererCatalog.asEntry(RendererCatalog.zink)
    private val all = listOf(krypton, gl4es, zink)

    private val mali = DeviceGraphics(GpuFamily.MALI, vulkan11 = true)
    private val maliNoVulkan = DeviceGraphics(GpuFamily.MALI, vulkan11 = false)
    private val adreno = DeviceGraphics(GpuFamily.ADRENO, vulkan11 = true)

    private data class Case(
        val version: String,
        val released: String,
        val graphics: DeviceGraphics,
        val offered: List<RuntimeManifest.RendererEntry>,
        val requested: String,
        val expected: String?,
        val why: String,
    )

    private val cases = listOf(
        Case("1.21.4", "", mali, all, "", "krypton",
            "до нового конвейера Mali остаётся на Krypton, как было: на этих версиях жалоб нет"),
        Case("1.21.5", "", mali, all, "", "krypton",
            "телеметрия 7 дней, Mali 1.21.5-11: Krypton доходит до игры в 50% запусков, Zink в 21%"),
        Case("1.21.10", "", mali, all, "", "krypton",
            "патч сравнивается числом: строкой «1.21.10» меньше «1.21.4»; Krypton и тут"),
        Case("26.1", "", mali, all, "", "zink",
            "новая нумерация по годам тоже после 1.21.4"),
        Case("25w14a", "2025-04-02T12:00:00+00:00", mali, all, "", "krypton",
            "снапшот 1.21.5 по дате после 1.21.4, но это не 26.x: Krypton"),
        Case("26w14a", "2026-04-01T12:00:00+00:00", mali, all, "", "zink",
            "снапшот 26.x на Mali: Zink 62% против Krypton 26%"),
        Case("26.3", "", mali, all, "", "zink", "26.x на Mali: Zink"),
        Case("26.3", "", adreno, all, "", "krypton", "26.x на Adreno: Krypton 52%, Zink 39%"),
        Case("1.21.11", "", adreno, all, "", "krypton", "Adreno 1.21.5-11: Krypton 42%, Zink 3%"),
        Case("24w46a", "2024-11-13T12:00:00+00:00", mali, all, "", "krypton",
            "снапшот до 1.21.4 не трогаем"),
        Case("1.21.5", "", adreno, all, "", "krypton",
            "на Adreno Krypton запускает новые версии — поведение не меняем"),
        Case("1.21.5", "", DeviceGraphics.UNKNOWN, all, "", "krypton",
            "GPU не определился — прежнее поведение, а не гадание"),
        Case("1.21.5", "", maliNoVulkan, all, "", "krypton",
            "без Vulkan 1.1 Zink не поднимется, Krypton — единственный шанс"),
        Case("1.21.5", "", mali, listOf(krypton, gl4es), "", "krypton",
            "в пакете нет файлов Zink — остаётся Krypton"),
        Case("1.21.5", "", mali, all, "krypton", "krypton",
            "выбор игрока в настройках сохраняется"),
        Case("1.20.1", "", adreno, all, "gl4es", "gl4es",
            "GL4ES по выбору игрока на старой версии разрешён"),
        Case("1.21.5", "", adreno, all, "gl4es", "krypton",
            "GL4ES не тянет 1.21.5, а Krypton в пакете есть: раньше был отказ и игра не запускалась вовсе"),
        Case("26.1", "", mali, all, "gl4es", "zink",
            "замена из настроек идёт тем же автовыбором: на Mali после 1.21.4 — Zink, не висящий Krypton"),
        Case("1.21.5", "", maliNoVulkan, all, "zink", "krypton",
            "Zink без Vulkan 1.1 не поднимется — берём Krypton вместо отказа"),
        Case("1.21.5", "", mali, listOf(gl4es), "gl4es", null,
            "выбранный не подходит и замены нет — отказ остаётся"),
        Case("1.21.5", "", mali, listOf(gl4es), "", null,
            "подходящего рендерера нет вовсе — отказ с причиной"),
        Case("26.1", "", mali, all, "angle", "zink",
            "неизвестное имя из старых настроек не ломает автовыбор"),
    )

    @Test
    fun `рендерер по версии и GPU`() {
        cases.forEach { case ->
            val verdict = RendererChoice.choose(case.version, case.released, case.graphics, case.offered, case.requested)
            val actual = (verdict as? RendererVerdict.Use)?.renderer?.id
            assertEquals(
                "${case.version} / ${case.graphics} / «${case.requested}»: ${case.why}",
                case.expected,
                actual,
            )
            if (verdict is RendererVerdict.Refused) {
                assertTrue("отказ должен говорить, что делать дальше: ${verdict.message}", verdict.message.contains(case.version))
            }
        }
    }

    @Test
    fun `замена рендерера из настроек видна игроку`() {
        val swapped = RendererChoice.choose("1.21.5", "", adreno, all, "gl4es") as RendererVerdict.Use
        assertTrue("игрок должен узнать, почему не его рендерер: ${swapped.note}", swapped.note.contains("GL4ES"))
        assertTrue("и на каком запускаем: ${swapped.note}", swapped.note.contains("Krypton"))
        val kept = RendererChoice.choose("1.20.1", "", adreno, all, "gl4es") as RendererVerdict.Use
        assertEquals("подходящий выбор игрока проходит без пометок", "", kept.note)
        val auto = RendererChoice.choose("1.21.5", "", adreno, all, "") as RendererVerdict.Use
        assertEquals("автовыбор без заказа — не замена, пометка не нужна", "", auto.note)
    }

    @Test
    fun `рендерер, упавший при запуске сборки, автовыбор обходит`() {
        data class Case(
            val graphics: DeviceGraphics,
            val offered: List<RuntimeManifest.RendererEntry>,
            val requested: String,
            val failed: Set<String>,
            val expected: String?,
            val noted: Boolean,
            val why: String,
        )

        val cases = listOf(
            Case(mali, all, "", setOf("zink"), "krypton", true,
                "Vanilla Perfected на Mali: Zink упал с OpenGL error 1281 — следующий запуск на Krypton, и игрок знает почему"),
            Case(mali, all, "", emptySet(), "zink", false,
                "без памяти о падении Mali после 1.21.4 остаётся на Zink"),
            Case(mali, all, "zink", setOf("zink"), "zink", false,
                "Zink, выбранный игроком в настройках, не подменяется: так он возвращает его сам"),
            Case(mali, listOf(gl4es, zink), "", setOf("zink"), "zink", false,
                "другого подходящего рендерера нет — запуск на том же, а не отказ"),
            Case(adreno, all, "", setOf("zink"), "krypton", false,
                "на Adreno автовыбор и так Krypton — пометка не нужна"),
            Case(mali, all, "gl4es", setOf("zink"), "krypton", false,
                "замена неподходящего GL4ES из настроек тоже обходит упавший Zink, а пометка говорит о GL4ES"),
        )
        cases.forEach { case ->
            val verdict = RendererChoice.choose("26.1.2", "", case.graphics, case.offered, case.requested, case.failed)
            val use = verdict as? RendererVerdict.Use
            assertEquals(case.why, case.expected, use?.renderer?.id)
            if (case.noted) {
                assertTrue("${case.why}: ${use?.note}", use?.note.orEmpty().contains("Zink"))
            } else if (case.requested.isEmpty()) {
                assertEquals(case.why, "", use?.note)
            }
        }
    }

    @Test
    fun `падение при запуске узнаётся по отчёту игры`() {
        data class Case(val kind: RendererKind, val text: String, val expected: Boolean, val why: String)

        val report = """
            ---- Minecraft Crash Report ----
            Description: Initializing game

            java.lang.IllegalStateException: OpenGL error 1281
            	at com.mojang.blaze3d.opengl.GlDevice.createTexture(GlDevice.java:221)
        """.trimIndent()
        val cases = listOf(
            Case(RendererKind.ZINK, report, true, "исходный вылет Vanilla Perfected на Mali-G925 с Zink"),
            Case(RendererKind.KRYPTON, report, false, "Krypton — запасной рендерер, его падение не уводит на Zink"),
            Case(RendererKind.ZINK, report.replace("Initializing game", "Rendering screen"), false,
                "ошибка GL посреди игры — не повод менять рендерер навсегда"),
            Case(RendererKind.ZINK, report.replace("OpenGL error 1281", "Mixin apply failed"), false,
                "падение мода при запуске к рендереру не относится"),
            Case(RendererKind.ZINK, "", false, "нет ни отчёта, ни журнала — ничего не запоминаем"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, RendererFallback.crashedAtInit(case.kind, case.text))
        }
    }

    @Test
    fun `память о падении привязана к сборке и версии`() {
        data class Case(val profile: String, val version: String, val expected: Set<String>, val why: String)

        val stored = setOf(
            RendererFallback.entry("zink", "vanilla-perfected", "fabric-loader-0.17.2-26.1.2"),
            "сломанная запись",
        )
        val cases = listOf(
            Case("vanilla-perfected", "fabric-loader-0.17.2-26.1.2", setOf("zink"), "та же сборка и версия — Zink обходится"),
            Case("vanilla-perfected", "fabric-loader-0.17.2-26.2", emptySet(), "новая версия игры даёт Zink ещё один шанс"),
            Case("fabulously-optimized", "fabric-loader-0.17.2-26.1.2", emptySet(), "соседняя сборка не наследует чужое падение"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, RendererFallback.failedAtInit(stored, case.profile, case.version))
        }
    }

    @Test
    fun `GPU узнаётся по строкам драйвера`() {
        listOf(
            Triple("ARM", "Mali-G57 MC2", GpuFamily.MALI) to "MediaTek Dimensity/Helio G — Mali",
            Triple("ARM", "Mali-G610 MC6", GpuFamily.MALI) to "новые Mali Valhall",
            Triple("Qualcomm", "Adreno (TM) 619", GpuFamily.ADRENO) to "Snapdragon",
            Triple("Imagination Technologies", "PowerVR Rogue GE8320", GpuFamily.POWERVR) to "дешёвые Helio с PowerVR — не Mali",
            Triple("Samsung Electronics Co., Ltd.", "Samsung Xclipse 920", GpuFamily.XCLIPSE) to "Exynos на AMD",
            Triple("HUAWEI", "Maleoon 910", GpuFamily.OTHER) to "Maleoon не Mali, хоть и похоже",
            Triple(null, null, GpuFamily.UNKNOWN) to "проба EGL не удалась",
        ).forEach { (input, why) ->
            assertEquals(why, input.third, GpuFamily.of(input.first, input.second))
        }
    }

    private fun pack(withZinkDriver: Boolean): ByteArray {
        val description = """
            {
              "javaHome": "jre",
              "bridge": ["bridge/lwjgl-glfw-classes.jar"],
              "rendererDir": "native",
              "renderers": [
                {"id": "krypton", "label": "Krypton", "library": "libng_gl4es.so"}
              ]
            }
        """.trimIndent()
        val files = mutableListOf(
            "millida-runtime.json" to description,
            "jre/bin/java" to "elf",
            "jre/lib/server/libjvm.so" to "elf",
            "bridge/lwjgl-glfw-classes.jar" to "jar",
            "native/libng_gl4es.so" to "elf",
            "native/libglxshim.so" to "elf",
            "native/libEGL_mesa.so" to "elf",
        )
        if (withZinkDriver) files += "native/libzink_dri.so" to "elf"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `Zink из пакета предлагается, только если все его файлы на месте`() {
        val full = GameLayout(temp.newFolder()).also { it.ensure() }
        RuntimeImport.install(full, pack(withZinkDriver = true).inputStream())
        assertEquals(
            "пакет несёт Zink, но не описывает его — настройки всё равно должны его показать",
            listOf("krypton", "zink"),
            RuntimeImport.renderers(full).map { it.id },
        )

        val partial = GameLayout(temp.newFolder()).also { it.ensure() }
        RuntimeImport.install(partial, pack(withZinkDriver = false).inputStream())
        assertEquals(
            "без libzink_dri.so Zink не стартует — предлагать его нельзя",
            listOf("krypton"),
            RuntimeImport.renderers(partial).map { it.id },
        )

        val installed = RuntimeImport.installed(full) ?: error("пакет не установился")
        val offered = RendererCatalog.offered(
            RuntimeImport.renderers(full).map { RendererCatalog.asEntry(it) },
            installed.rendererDir,
        )
        assertEquals("Zink не дублируется, когда он уже в списке", listOf("krypton", "zink"), offered.map { it.id })
        assertTrue("каталог пакета узнаётся как свой", RuntimeImport.owns(full, installed.rendererDir))
    }
}
