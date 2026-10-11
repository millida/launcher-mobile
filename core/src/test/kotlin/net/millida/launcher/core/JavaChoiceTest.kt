package net.millida.launcher.core

import net.millida.launcher.core.runtime.JavaHomeLayout
import net.millida.launcher.core.runtime.JavaRequirement
import net.millida.launcher.core.runtime.RuntimeImport
import net.millida.launcher.core.runtime.RuntimeManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Java выбирается по описанию версии, а у Forge до 1.13 есть ещё и потолок:
 * LaunchWrapper падает на любой Java новее 8. Здесь закреплены оба правила
 * для пакета на телефоне и для манифеста раздачи.
 */
class JavaChoiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val wrapper = JavaRequirement.LAUNCHWRAPPER_MAIN
    private val vanilla = "net.minecraft.client.main.Main"

    @Test
    fun `требование версии читается из описания и главного класса`() {
        data class Case(val major: Int, val main: String, val expected: JavaRequirement, val why: String)

        val cases = listOf(
            Case(8, wrapper, JavaRequirement(8, 8), "Forge 1.7.10–1.12.2: LaunchWrapper не переживает Java 9+"),
            Case(8, vanilla, JavaRequirement(8, null), "ванильная 1.7.10 идёт и на новой Java, потолка нет"),
            Case(17, vanilla, JavaRequirement(17, null), "обычная современная версия"),
            Case(17, wrapper, JavaRequirement(17, null), "сборка сама просит 17 — потолок 8 сделал бы её незапускаемой"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, JavaRequirement.of(case.major, case.main))
        }
    }

    @Test
    fun `из доступных берётся самая старая подходящая`() {
        data class Case(val requirement: JavaRequirement, val available: List<Int>, val expected: Int?, val why: String)

        val cases = listOf(
            Case(JavaRequirement(8, 8), listOf(8, 21, 25), 8, "старый Forge получает ровно Java 8"),
            Case(JavaRequirement(8, 8), listOf(21, 25), null, "без Java 8 старый Forge не запускается — нельзя подсунуть 21"),
            Case(JavaRequirement(8), listOf(8, 21, 25), 8, "1.7.10 без LaunchWrapper берёт свою родную Java"),
            Case(JavaRequirement(8), listOf(21, 25), 21, "а без неё — ближайшую новее, как раньше"),
            Case(JavaRequirement(17), listOf(8, 21, 25), 21, "Java 8 не годится версии на 17"),
            Case(JavaRequirement(22), listOf(8, 21, 25), 25, "22 нет — следующая старшая"),
            Case(JavaRequirement(33), listOf(8, 21, 25), null, "новее всего пакета — нет ответа"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, case.requirement.pick(case.available))
        }
    }

    @Test
    fun `номер Java читается и в старой схеме 1_x`() {
        data class Case(val version: String, val expected: Int?, val why: String)

        val cases = listOf(
            Case("1.8.0_482", 8, "Java 8 называет себя 1.8 — первое число не версия"),
            Case("21.0.1", 21, "с Java 9 первое число и есть версия"),
            Case("25", 25, "номер без точек"),
            Case("17-ea", 17, "ранняя сборка с суффиксом"),
            Case("abc", null, "мусор не превращается в число"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, RuntimeImport.javaMajorOf(case.version))
        }
    }

    @Test
    fun `папка нативных библиотек Java 8 лежит под архитектурой`() {
        val legacy = temp.newFolder("jre-8")
        File(legacy, "lib/aarch64/server").mkdirs()
        File(legacy, "lib/aarch64/server/libjvm.so").writeText("elf")
        val modern = temp.newFolder("jre-21")
        File(modern, "lib/server").mkdirs()
        File(modern, "lib/server/libjvm.so").writeText("elf")

        assertEquals("у Java 8 библиотеки в lib/aarch64", "lib/aarch64", JavaHomeLayout.nativeDirName(legacy))
        assertEquals("с Java 9 — прямо в lib", "lib", JavaHomeLayout.nativeDirName(modern))
        assertEquals(
            "путь собирается от папки Java, иначе загрузчик среды перезапустит себя через bin/java",
            File(legacy, "lib/aarch64").absolutePath,
            JavaHomeLayout.nativeDir(legacy).absolutePath,
        )
    }

    @Test
    fun `пакет на телефоне соблюдает потолок Java`() {
        data class Case(
            val majors: List<Int>,
            val minimum: Int,
            val maximum: Int?,
            val expected: String?,
            val why: String,
        )

        val cases = listOf(
            Case(listOf(8, 21, 25), 8, 8, "jre-8", "старый Forge берёт Java 8 из пакета"),
            Case(listOf(8, 21, 25), 8, null, "jre-8", "ванильная 1.7.10 тоже предпочитает свою Java"),
            Case(listOf(8, 21, 25), 17, null, "jre-21", "Java 8 не подходит версии на 17"),
            Case(listOf(21, 25), 8, 8, null, "без Java 8 пакет отказывает, и она приходит из сети"),
            Case(listOf(21, 25), 8, null, "jre-21", "без потолка поведение прежнее"),
        )
        for (case in cases) {
            val layout = GameLayout(temp.newFolder()).also { it.ensure() }
            RuntimeImport.install(layout, bundle(case.majors).inputStream())
            val runtime = RuntimeImport.installed(layout, case.minimum, "", case.maximum)
            assertEquals(case.why, case.expected, runtime?.javaHome?.name)
            if (runtime != null) {
                assertEquals(
                    "${case.why}: номер выбранной Java известен запуску",
                    case.expected!!.removePrefix("jre-").toInt(),
                    runtime.javaMajor,
                )
            }
        }
    }

    @Test
    fun `манифест раздачи соблюдает потолок Java`() {
        fun manifest(vararg majors: Int) = RuntimeManifest(
            jre = majors.map { RuntimeManifest.JreEntry("jre-$it", it, "arm64-v8a", "u$it", "s$it") },
        )

        data class Case(val manifest: RuntimeManifest, val minimum: Int, val maximum: Int?, val expected: Int?, val why: String)

        val cases = listOf(
            Case(manifest(8, 21, 25), 8, 8, 8, "LaunchWrapper получает Java 8"),
            Case(manifest(21, 25), 8, 8, null, "без Java 8 в манифесте честный отказ, а не Java 21"),
            Case(manifest(8, 21, 25), 17, null, 21, "версии на 17 Java 8 не достаётся"),
            Case(manifest(21, 25), 8, null, 21, "без потолка — прежнее правило ближайшей старшей"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, case.manifest.jreFor(case.minimum, "arm64-v8a", case.maximum)?.major)
        }
        assertNull("чужая архитектура не подходит", manifest(8).jreFor(8, "x86_64", 8))
    }

    private fun bundle(majors: List<Int>): ByteArray {
        val description = """
            {
              "jres": [${majors.joinToString(",") { """{"major": $it, "path": "jre-$it"}""" }}],
              "bridge": ["bridge/lwjgl-glfw-classes.jar"],
              "rendererDir": "native",
              "rendererLibrary": "libgl4es_114.so"
            }
        """.trimIndent()
        val entries = ArrayList<Pair<String, String>>()
        entries += "millida-runtime.json" to description
        majors.forEach { major ->
            entries += "jre-$major/bin/java" to "elf"
            entries += if (major == 8) {
                "jre-$major/lib/aarch64/server/libjvm.so" to "elf"
            } else {
                "jre-$major/lib/server/libjvm.so" to "elf"
            }
            entries += "jre-$major/release" to "JAVA_VERSION=\"${if (major == 8) "1.8.0_482" else "$major.0.1"}\""
        }
        entries += "bridge/lwjgl-glfw-classes.jar" to "jar"
        entries += "native/libgl4es_114.so" to "elf"
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
}
