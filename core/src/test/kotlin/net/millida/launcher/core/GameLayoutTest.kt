package net.millida.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class GameLayoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun relative(layout: GameLayout, file: File): String =
        file.relativeTo(layout.root).invariantSeparatorsPath

    @Test
    fun `файлы игры лежат там, где их ищет запуск`() {
        val layout = GameLayout(tmp.root)
        val cases = listOf(
            layout.versionJson("1.20.1") to "versions/1.20.1/1.20.1.json",
            layout.versionJar("fabric-loader-0.16.5-1.20.1") to "versions/fabric-loader-0.16.5-1.20.1/fabric-loader-0.16.5-1.20.1.jar",
            layout.library("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar") to "libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar",
            layout.assetIndex("17") to "assets/indexes/17.json",
            layout.assetObject("a9993e364706816aba3e25717850c26c9cd0d89d") to "assets/objects/a9/a9993e364706816aba3e25717850c26c9cd0d89d",
            layout.profileDir("build") to "profiles/build",
            layout.logConfig("client-1.12.xml") to "assets/log_configs/client-1.12.xml",
        )
        for ((file, expected) in cases) {
            assertEquals("путь разошёлся с раскладкой Mojang — игра не найдёт файл", expected, relative(layout, file))
        }
    }

    @Test
    fun `ensure создаёт все рабочие папки`() {
        val layout = GameLayout(File(tmp.root, "fresh"))
        layout.ensure()
        val folders = listOf(
            layout.versions, layout.libraries, layout.assetIndexes, layout.assetObjects,
            layout.objects, layout.profiles, layout.runtimes, layout.caches, layout.logs,
        )
        for (folder in folders) {
            assertTrue("папка ${relative(layout, folder)} не создана — первая загрузка упадёт на записи", folder.isDirectory)
        }
        layout.ensure()
        assertTrue("повторный ensure сломал раскладку", layout.versions.isDirectory)
    }

    @Test
    fun `имя конфига логов из манифеста не выводит из папки`() {
        val layout = GameLayout(tmp.root)
        val cases = listOf(
            "../../evil.xml" to "переход вверх из манифеста версии",
            "/etc/log4j2.xml" to "абсолютный путь",
            "C:/evil.xml" to "путь с диском",
        )
        for ((id, why) in cases) {
            try {
                val file = layout.logConfig(id)
                fail("$why: «$id» принят как ${file.path} — подменённый манифест запишет файл куда угодно")
            } catch (expected: IOException) {
                assertTrue("$why: причина не названа", expected.message.orEmpty().isNotEmpty())
            }
        }
    }
}
