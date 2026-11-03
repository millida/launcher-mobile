package net.millida.launcher.core

import net.millida.launcher.core.runtime.JavaHomeLayout
import net.millida.launcher.core.runtime.JavaRequirement
import net.millida.launcher.core.runtime.ModJava
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
            Case(8, JavaRequirement.MODLAUNCHER_MAIN, JavaRequirement(8, 8, strict = false),
                "Forge 1.13–1.16.5: ядро модов идёт на Nashorn, которого нет с Java 15; потолок нестрогий — " +
                    "без сети игра стартует на Java из пакета, как раньше, а не отказывает"),
            Case(17, "cpw.mods.bootstraplauncher.BootstrapLauncher", JavaRequirement(17, null), "Forge с 1.17 запускается иначе и просит 17: потолка нет"),
            Case(21, JavaRequirement.MODLAUNCHER_MAIN, JavaRequirement(21, null), "сборка на ModLauncher, сама просящая новую Java, не ограничивается"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, JavaRequirement.of(case.major, case.main))
        }
        assertEquals(
            "LaunchWrapper на Java 9+ не запускается вовсе: его потолок строгий, замены нет",
            true,
            JavaRequirement.of(8, wrapper).strict,
        )
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
        assertEquals("машина Java 8 под arm64 — в server", File(legacy, "lib/aarch64/server"), JavaHomeLayout.jvmDir(legacy))
        assertEquals("у Java 9+ — в lib/server", File(modern, "lib/server"), JavaHomeLayout.jvmDir(modern))
    }

    @Test
    fun `у 32-битной Java 8 машина лежит в client`() {
        val narrow = temp.newFolder("jre-8-arm")
        File(narrow, "lib/aarch32/client").mkdirs()
        File(narrow, "lib/aarch32/client/libjvm.so").writeText("elf")
        val empty = temp.newFolder("jre-empty")

        assertEquals("сборки Java 8 под 32-битный ARM идут без серверного компилятора", "lib/aarch32", JavaHomeLayout.nativeDirName(narrow))
        assertEquals(
            "путь библиотек должен вести в client, иначе libjvm.so не находится",
            File(narrow, "lib/aarch32/client"),
            JavaHomeLayout.jvmDir(narrow),
        )
        assertEquals("без машины ответ прежний — lib/server", File(empty, "lib/server"), JavaHomeLayout.jvmDir(empty))
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

    private fun classBytes(major: Int): ByteArray =
        byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, (major shr 8).toByte(), major.toByte(), 0, 0)

    private fun jar(dir: File, name: String, vararg entries: Pair<String, ByteArray>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, bytes) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    // Версии файлов классов: 52 — Java 8, 61 — 17, 65 — 21, 69 — 25.
    @Test
    fun `Java для модов читается из заголовков классов`() {
        val mods = temp.newFolder("mods")
        assertEquals("пустая папка — ответа нет", null, ModJava.required(mods))
        jar(mods, "old.jar", "a/A.class" to classBytes(52))
        jar(mods, "new.jar", "META-INF/MANIFEST.MF" to ByteArray(1), "b/B.class" to classBytes(65), "META-INF/versions/25/b/B.class" to classBytes(69))
        jar(mods, "broken.jar", "c/C.class" to ByteArray(3))
        File(mods, "notes.txt").writeText("x")
        assertEquals("самый новый мод, классы META-INF/versions не считаются", 21, ModJava.required(mods))
        assertEquals(8, ModJava.of(File(mods, "old.jar")))
        assertEquals("битый класс не ломает чтение", null, ModJava.of(File(mods, "broken.jar")))
    }

    @Test
    fun `требование растёт под моды, но не выше потолка старого Forge`() {
        data class Case(val base: JavaRequirement, val mods: Int?, val expected: JavaRequirement, val why: String)

        val cases = listOf(
            Case(JavaRequirement(17), 21, JavaRequirement(21), "игра на 17, моды под 21 — берём 21"),
            Case(JavaRequirement(17), 17, JavaRequirement(17), "моды под ту же Java — без изменений"),
            Case(JavaRequirement(21), 8, JavaRequirement(21), "старые моды на новой игре не понижают Java"),
            Case(JavaRequirement(17), null, JavaRequirement(17), "классов не прочитали — как было"),
            Case(JavaRequirement(8, 8), 17, JavaRequirement(8, 8), "потолок LaunchWrapper держится: на Java 9+ он не запускается вовсе"),
            Case(JavaRequirement(8), 17, JavaRequirement(17), "1.7.10 без потолка поднимается под моды"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, case.base.withMods(case.mods))
        }
        assertEquals("из пакета 8/21/25 моды под 17 получают 21", 21, JavaRequirement(8).withMods(17).pick(listOf(8, 21, 25)))
    }
}
