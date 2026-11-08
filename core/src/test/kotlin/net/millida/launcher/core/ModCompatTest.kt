package net.millida.launcher.core

import net.millida.launcher.core.content.ModCompat
import net.millida.launcher.core.loader.LoaderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModCompatTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun jar(dir: File, file: String, vararg entries: Pair<String, String>): File {
        val target = File(dir, file)
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return target
    }

    private fun fabric(
        dir: File, file: String, id: String, version: String = "1.0.0",
        depends: String = "", breaks: String = "",
    ) = jar(
        dir, file,
        "fabric.mod.json" to """{"schemaVersion":1,"id":"$id","name":"$id","version":"$version",
            "depends":{$depends},"breaks":{$breaks}}""",
    )

    private fun forge(dir: File, file: String, id: String, minecraft: String, extra: String = "") = jar(
        dir, file,
        "META-INF/mods.toml" to """
            modLoader="javafml"
            [[mods]]
            modId="$id"
            version="1.0"
            displayName="$id"
            [[dependencies.$id]]
            modId="minecraft"
            mandatory=true
            versionRange="$minecraft"
            $extra
        """.trimIndent(),
    )

    private fun names(problems: List<ModCompat.Problem>) = problems.map { it.kind to it.name }

    @Test
    fun `мод под другую версию игры выключается, подходящий остаётся`() {
        val dir = temp.newFolder()
        fabric(dir, "old.jar", "old", depends = "\"minecraft\":\">=1.19 <1.20\"")
        fabric(dir, "good.jar", "good", depends = "\"minecraft\":\"~1.20.1\"")
        fabric(dir, "any.jar", "any", depends = "\"minecraft\":\"*\"")
        val problems = ModCompat.problems(dir, "1.20.4", LoaderKind.FABRIC)
        assertEquals(listOf(ModCompat.Kind.GAME to "old"), names(problems))
        assertEquals(listOf(File(dir, "old.jar")), problems.single().disable)
    }

    @Test
    fun `Iris не подходит к старому Sodium`() {
        val dir = temp.newFolder()
        fabric(dir, "sodium.jar", "sodium", version = "0.5.11+mc1.20.4")
        fabric(dir, "iris.jar", "iris", depends = "\"sodium\":\">=0.6.0 <0.7\"")
        fabric(dir, "lithium.jar", "lithium", depends = "\"sodium\":\"*\"")
        val problems = ModCompat.problems(dir, "1.20.4", LoaderKind.FABRIC)
        assertEquals(listOf(ModCompat.Kind.DEPENDENCY to "iris"), names(problems))
    }

    @Test
    fun `два файла одного мода — остаётся новый`() {
        val dir = temp.newFolder()
        fabric(dir, "a-1.2.0.jar", "a", version = "1.2.0")
        fabric(dir, "a-1.10.0.jar", "a", version = "1.10.0")
        val problems = ModCompat.problems(dir, "1.20.4", LoaderKind.FABRIC)
        assertEquals(listOf(File(dir, "a-1.2.0.jar")), problems.single().disable)
        assertEquals(ModCompat.Kind.DUPLICATE, problems.single().kind)
    }

    @Test
    fun `явный разрыв breaks и цепочка зависимых`() {
        val dir = temp.newFolder()
        fabric(dir, "x.jar", "x", breaks = "\"y\":\"<2.0\"")
        fabric(dir, "y.jar", "y", version = "1.0")
        fabric(dir, "z.jar", "z", depends = "\"x\":\"*\"")
        fabric(dir, "fine.jar", "fine", breaks = "\"y\":\">=3\"")
        val problems = ModCompat.problems(dir, "1.20.4", LoaderKind.FABRIC)
        assertEquals(
            listOf(ModCompat.Kind.BREAKS to "x", ModCompat.Kind.CASCADE to "z"),
            names(problems),
        )
    }

    @Test
    fun `мод другого загрузчика выключается, Quilt принимает Fabric`() {
        val dir = temp.newFolder()
        forge(dir, "forgemod.jar", "forgemod", "[1.20,1.21)")
        fabric(dir, "fabricmod.jar", "fabricmod")
        assertEquals(listOf(ModCompat.Kind.LOADER to "forgemod"), names(ModCompat.problems(dir, "1.20.4", LoaderKind.FABRIC)))
        assertEquals(listOf(ModCompat.Kind.LOADER to "forgemod"), names(ModCompat.problems(dir, "1.20.4", LoaderKind.QUILT)))
        assertEquals(listOf(ModCompat.Kind.LOADER to "fabricmod"), names(ModCompat.problems(dir, "1.20.4", LoaderKind.FORGE)))
    }

    @Test
    fun `Forge - диапазон игры в скобках`() {
        val dir = temp.newFolder()
        forge(dir, "ok.jar", "ok", "[1.20,1.21)")
        forge(dir, "late.jar", "late", "[1.21,)")
        val problems = ModCompat.problems(dir, "1.20.1", LoaderKind.FORGE)
        assertEquals(listOf(ModCompat.Kind.GAME to "late"), names(problems))
    }

    @Test
    fun `NeoForge - мод линии 1_21 с диапазоном до 1_21_1 не выключается, мод 1_20 выключается`() {
        val dir = temp.newFolder()
        fun neo(file: String, id: String, minecraft: String) = jar(
            dir, file,
            "META-INF/neoforge.mods.toml" to """
                modLoader="javafml"
                [[mods]]
                modId="$id"
                version="1.0"
                displayName="$id"
                [[dependencies.$id]]
                modId="minecraft"
                type="required"
                versionRange="$minecraft"
            """.trimIndent(),
        )
        neo("cucumber.jar", "cucumber", "[1.21,1.21.1)")
        neo("old.jar", "old", "[1.20,1.21)")
        val problems = ModCompat.problems(dir, "1.21.1", LoaderKind.NEOFORGE)
        assertEquals(listOf(ModCompat.Kind.GAME to "old"), names(problems))
    }

    @Test
    fun `Forge - недостающая библиотека названа, найденная нет`() {
        val dir = temp.newFolder()
        forge(dir, "a.jar", "a", "*", extra = "[[dependencies.a]]\nmodId=\"balm\"\nmandatory=true\nversionRange=\"*\"")
        forge(dir, "b.jar", "b", "*", extra = "[[dependencies.b]]\nmodId=\"glitchcore\"\nmandatory=false\nversionRange=\"*\"")
        forge(dir, "c.jar", "c", "*", extra = "[[dependencies.c]]\nmodId=\"a\"\nmandatory=true\nversionRange=\"*\"")
        val missing = ModCompat.missingForge(dir, LoaderKind.FORGE)
        assertEquals(listOf("balm"), missing.map { it.name })
        assertEquals(listOf("a"), missing.single().requiredBy)
        assertTrue(missing.single().projectId != null)
    }

    @Test
    fun `нечитаемое и пустое — без проблем`() {
        val dir = temp.newFolder()
        File(dir, "broken.jar").writeText("not a zip")
        jar(dir, "plain.jar", "readme.txt" to "hi")
        assertTrue(ModCompat.problems(dir, "1.20.4", LoaderKind.FABRIC).isEmpty())
        assertTrue(ModCompat.problems(dir, "1.20.4", LoaderKind.VANILLA).isEmpty())
    }
}
