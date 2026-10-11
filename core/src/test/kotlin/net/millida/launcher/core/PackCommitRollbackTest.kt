package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.packs.PackContent
import net.millida.launcher.core.packs.PackInstaller
import net.millida.launcher.core.packs.PackPlan
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * A failed install must leave the build exactly as it was. Folders were set
 * aside and put back, but single files at the top of the build were deleted
 * before the new copy landed, so a failure later in the swap lost them.
 */
class PackCommitRollbackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `сбой посреди замены возвращает и папки, и отдельные файлы`() {
        val layout = GameLayout(tmp.newFolder("game")).also { it.ensure() }
        val profiles = ProfileStore(layout)
        val build = Profile(id = "build", name = "Сборка", gameVersion = "1.20.1")
        profiles.upsert(build)
        val directory = profiles.directory(build)

        // Many top-level files, so some of them are swapped before the failing folder whatever order the disk lists them in.
        val names = (1..30).map { "f$it.txt" }
        names.forEach { File(directory, it).writeText("old $it") }
        val player = File(directory, "resourcepacks/player.zip").also { it.parentFile.mkdirs(); it.writeText("игрока") }
        player.setReadable(false)
        assumeFalse("под root права на чтение не действуют — сбой не воспроизвести", player.canRead())

        val tree = tmp.newFolder("tree")
        names.forEach { File(tree, it).writeText("new $it") }
        File(tree, "resourcepacks/pack.zip").also { it.parentFile.mkdirs(); it.writeText("сборки") }

        val installer = PackInstaller(layout, profiles, Downloader(attempts = 1))
        val content = PackContent(PackPlan(name = "Сборка", gameVersion = "1.20.1", loader = LoaderKind.VANILLA), listOf(tree))
        val failure = runCatching { runBlocking { installer.install(content, build) } }.exceptionOrNull()
        player.setReadable(true)

        assertTrue("перенос файла игрока должен был сорваться: $failure", failure is IOException)
        names.forEach { name ->
            assertEquals("файл $name после неудачной установки — прежний", "old $name", File(directory, name).readText())
        }
        assertEquals("файл игрока на месте", "игрока", player.readText())
        assertTrue(
            "отложенные копии не остаются рядом со сборкой",
            directory.list().orEmpty().none { it.contains(".old-") },
        )
    }
}
