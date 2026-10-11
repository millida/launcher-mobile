package net.millida.launcher.core

import net.millida.launcher.core.packs.PackParking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackParkingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private data class Case(
        val before: Map<String, String>,
        val after: Map<String, String>,
        val why: String,
    )

    @Test
    fun `отложенная копия возвращается на место, только если живой копии нет`() {
        val cases = listOf(
            Case(
                mapOf("mods.old-100/a.jar" to "old"),
                mapOf("mods/a.jar" to "old"),
                "процесс убит между двумя переносами — сборка без модов получает старые обратно",
            ),
            Case(
                mapOf("mods/b.jar" to "new", "mods.old-100/a.jar" to "old"),
                mapOf("mods/b.jar" to "new", "mods.old-100/a.jar" to "old"),
                "живая папка есть — новую не подменяем старой",
            ),
            Case(
                mapOf("mods.old-100/a.jar" to "older", "mods.old-200/a.jar" to "newer"),
                mapOf("mods/a.jar" to "newer", "mods.old-100/a.jar" to "older"),
                "из нескольких отложенных берётся последняя",
            ),
            Case(
                mapOf("options.txt.old-100" to "keys"),
                mapOf("options.txt" to "keys"),
                "отложенный файл возвращается так же, как папка",
            ),
            Case(
                mapOf("world.old-save/level.dat" to "x"),
                mapOf("world.old-save/level.dat" to "x"),
                "имя игрока без числовой метки — не наша отложенная копия",
            ),
        )
        cases.forEachIndexed { index, case ->
            val directory = folder.newFolder("build-$index")
            case.before.forEach { (path, text) -> File(directory, path).apply { parentFile.mkdirs() }.writeText(text) }

            PackParking.recover(directory)

            val after = directory.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(directory).path.replace(File.separatorChar, '/') to it.readText() }
            assertEquals("восстановление после обрыва установки: ${case.why}", case.after, after)
        }
    }

    @Test
    fun `имя отложенной копии узнаётся при восстановлении`() {
        val directory = folder.newFolder("named")
        File(directory, PackParking.parkedName("config", 42)).apply { mkdirs() }.resolve("a.toml").writeText("a")

        PackParking.recover(directory)

        assertEquals("копия, отложенная коммитом, должна находиться восстановлением", "a", File(directory, "config/a.toml").readText())
    }
}
