package net.millida.launcher.core

import net.millida.launcher.core.content.MissingMod
import net.millida.launcher.core.content.ModDependencies
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * «Incompatible mods found» from Fabric Loader is the most common failed start
 * of a modded build: a mod is in the folder and the mod it depends on is not.
 * The launcher finds that before the game does. A wrong «missing» would offer
 * to install what is already there, and a dependency taken at its word would
 * install whatever project carries that name, so both are pinned here.
 */
class ModDependenciesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fabricApiProject = "P7dR8mSH"

    private fun jar(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun description(
        id: String,
        name: String = id,
        depends: List<String> = emptyList(),
        provides: List<String> = emptyList(),
        jars: List<String> = emptyList(),
        environment: String = "*",
    ): ByteArray = """
        {
          "schemaVersion": 1,
          "id": "$id",
          "name": "$name",
          "version": "1.0.0",
          "environment": "$environment",
          "provides": [${provides.joinToString { "\"$it\"" }}],
          "depends": {${depends.joinToString { "\"$it\": \"*\"" }}},
          "jars": [${jars.joinToString { "{\"file\": \"$it\"}" }}]
        }
    """.trimIndent().toByteArray()

    private fun mod(id: String, name: String = id, depends: List<String> = emptyList(), provides: List<String> = emptyList()): ByteArray =
        jar("fabric.mod.json" to description(id, name, depends, provides))

    private fun fabricApi(): ByteArray {
        val module = jar("fabric.mod.json" to description("fabric-resource-loader-v0"))
        val deep = jar(
            "META-INF/jars/inner.jar" to jar("fabric.mod.json" to description("fabric-lifecycle-events-v1")),
            "fabric.mod.json" to description("fabric-api-base"),
        )
        return jar(
            "fabric.mod.json" to description(
                "fabric-api",
                "Fabric API",
                provides = listOf("fabric"),
                jars = listOf("META-INF/jars/resource-loader.jar", "META-INF/jars/base.jar"),
            ),
            "META-INF/jars/resource-loader.jar" to module,
            "META-INF/jars/base.jar" to deep,
        )
    }

    private fun mods(vararg files: Pair<String, ByteArray>): File {
        val dir = temp.newFolder()
        files.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        return dir
    }

    @Test
    fun `недостающая зависимость находится по описаниям модов`() {
        data class Case(val dir: File, val expected: List<MissingMod>, val why: String)

        val advancements = mod("betteradvancements", "Better Advancements", depends = listOf("fabric-api", "minecraft", "java", "fabricloader"))
        val culling = mod("entityculling", "EntityCulling", depends = listOf("fabric-api"))
        val cases = listOf(
            Case(
                mods("a.jar" to advancements, "b.jar" to culling),
                listOf(MissingMod("fabric-api", listOf("fabric-api"), listOf("Better Advancements", "EntityCulling"), fabricApiProject)),
                "исходная жалоба: два мода просят Fabric API, а его в сборке нет; игра, Java и загрузчик недостающими не считаются",
            ),
            Case(
                mods("a.jar" to advancements, "fabric-api.jar" to fabricApi()),
                emptyList(),
                "Fabric API на месте — недостающих нет",
            ),
            Case(
                mods(
                    "a.jar" to mod("old", "Old Mod", depends = listOf("fabric")),
                    "b.jar" to mod("res", "Res", depends = listOf("fabric-resource-loader-v0")),
                    "c.jar" to mod("life", "Life", depends = listOf("fabric-lifecycle-events-v1", "fabric-api-base")),
                    "fabric-api.jar" to fabricApi(),
                ),
                emptyList(),
                "старое имя fabric и модули Fabric API даёт сам Fabric API: provides и вложенные jar, в том числе вложенные во вложенные",
            ),
            Case(
                mods(
                    "a.jar" to mod("old", "Old Mod", depends = listOf("fabric")),
                    "b.jar" to mod("res", "Res", depends = listOf("fabric-resource-loader-v0")),
                ),
                listOf(MissingMod("fabric-api", listOf("fabric", "fabric-resource-loader-v0"), listOf("Old Mod", "Res"), fabricApiProject)),
                "модуль Fabric API и его старое имя — один и тот же проект каталога, а не два",
            ),
            Case(
                mods(
                    "iris.jar" to mod("iris", "Iris", depends = listOf("sodium")),
                    "sodium.jar.disabled" to mod("sodium", "Sodium"),
                ),
                listOf(MissingMod("sodium", listOf("sodium"), listOf("Iris"), "AANobbMI")),
                "выключенный мод игре не достаётся — его зависимые остаются без него",
            ),
            Case(
                mods(
                    "server.jar" to jar("fabric.mod.json" to description("srv", "Server Only", depends = listOf("ledger"), environment = "server")),
                    "forge.jar" to jar("META-INF/mods.toml" to "modLoader=\"javafml\"".toByteArray()),
                ),
                emptyList(),
                "мод только для сервера клиент не грузит, а jar без описания Fabric к делу не относится",
            ),
            Case(
                mods("a.jar" to advancements, "broken.jar" to jar("fabric.mod.json" to "{ это не json".toByteArray())),
                emptyList(),
                "описание одного мода не читается — что он даёт, неизвестно: недостающих не выдумываем",
            ),
            Case(
                mods("a.jar" to advancements, "notzip.jar" to "мусор".toByteArray()),
                emptyList(),
                "битый архив в папке — то же: решает сам загрузчик",
            ),
            Case(
                mods("a.jar" to advancements, "huge.jar" to jar("fabric.mod.json" to ByteArray(2 * 1024 * 1024) { ' '.code.toByte() })),
                emptyList(),
                "описание на мегабайты читать не станем: файл чужой и может быть собран, чтобы лаунчер завис",
            ),
            Case(
                mods("user.jar" to mod("user", "User", depends = listOf("yet_another_config_lib_v3", "forgeconfigapiport", "some_lib"))),
                listOf(
                    MissingMod("yacl", listOf("yet_another_config_lib_v3"), listOf("User"), "1eAoo2KR"),
                    MissingMod("forge-config-api-port", listOf("forgeconfigapiport"), listOf("User"), "ohNO6lps"),
                    MissingMod("some_lib", listOf("some_lib"), listOf("User"), null),
                ),
                "известная библиотека получает свой постоянный id проекта; неизвестная называется, но проекта у неё нет — " +
                    "под именем some-lib в каталоге может лежать что угодно",
            ),
            Case(
                mods("long.jar" to mod("long", "N".repeat(300), depends = listOf("sodium"))),
                listOf(MissingMod("sodium", listOf("sodium"), listOf("N".repeat(48)), "AANobbMI")),
                "имя мода — чужой текст: в окно оно попадает обрезанным",
            ),
            Case(
                mods("lines.jar" to mod("lines", "Обнови\\n\\nлаунчер   тут", depends = listOf("sodium"))),
                listOf(MissingMod("sodium", listOf("sodium"), listOf("Обнови лаунчер тут"), "AANobbMI")),
                "переносы строк в имени мода не дают дописать в окно свои абзацы",
            ),
        )
        for (case in cases) {
            assertEquals("недостающие моды: ${case.why}", case.expected, ModDependencies.missing(case.dir))
        }
        assertEquals("пустая или отсутствующая папка модов — ванильная сборка", emptyList<MissingMod>(), ModDependencies.missing(File(temp.root, "none")))
    }
}
