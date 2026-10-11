package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.catalog.ModpackLoader
import net.millida.launcher.core.loader.LoaderInstaller
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.loader.LoaderVersion
import net.millida.launcher.core.packs.PackVersionChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ModpackLoaderTest {

    private class FakeInstaller(
        override val kind: LoaderKind,
        private val available: List<LoaderVersion>,
    ) : LoaderInstaller {
        val asked = ArrayList<String>()
        val installed = ArrayList<Pair<String, String>>()

        override suspend fun versions(gameVersion: String): List<LoaderVersion> {
            asked += gameVersion
            return available
        }

        override suspend fun install(gameVersion: String, loaderVersion: String): String {
            installed += gameVersion to loaderVersion
            return "$gameVersion-${kind.id}-$loaderVersion"
        }
    }

    @Test
    fun `ванильная сборка запускается по версии игры без установщика`() = runBlocking {
        var asked = false
        val loader = ModpackLoader { asked = true; null }

        val choice = loader.resolve("1.20.1", LoaderKind.VANILLA, "")

        assertEquals(PackVersionChoice("1.20.1", ""), choice)
        assertTrue("установщик для ванили не нужен и не спрашивается", !asked)
    }

    @Test
    fun `версия загрузчика выбирается стабильная`() = runBlocking {
        data class Case(val available: List<LoaderVersion>, val want: String, val why: String)

        val cases = listOf(
            Case(
                listOf(LoaderVersion("0.17.0-beta", stable = false), LoaderVersion("0.16.9"), LoaderVersion("0.16.8")),
                "0.16.9",
                "бета первой в списке не должна попадать в сборку игрока",
            ),
            Case(
                listOf(LoaderVersion("47.1.0-pre", stable = false), LoaderVersion("47.0.0-pre", stable = false)),
                "47.1.0-pre",
                "без стабильных берётся самая свежая, а не отказ",
            ),
        )
        for (case in cases) {
            val installer = FakeInstaller(LoaderKind.FABRIC, case.available)
            val choice = ModpackLoader { installer }.resolve("1.20.1", LoaderKind.FABRIC, "")
            assertEquals(case.why, PackVersionChoice("1.20.1-fabric-${case.want}", case.want), choice)
            assertEquals("загрузчик ставится до переноса сборки", listOf("1.20.1" to case.want), installer.installed)
        }
    }

    @Test
    fun `названная сборкой версия загрузчика не подменяется`() = runBlocking {
        val installer = FakeInstaller(LoaderKind.NEOFORGE, listOf(LoaderVersion("21.1.100")))

        val choice = ModpackLoader { installer }.resolve("1.21.1", LoaderKind.NEOFORGE, "21.1.77")

        assertEquals(PackVersionChoice("1.21.1-neoforge-21.1.77", "21.1.77"), choice)
        assertTrue("список версий не запрашивается, когда версия задана", installer.asked.isEmpty())
    }

    @Test
    fun `понятный отказ, когда загрузчик не ставится`() = runBlocking {
        data class Case(val installer: FakeInstaller?, val loader: LoaderKind, val message: String, val why: String)

        val cases = listOf(
            Case(
                null,
                LoaderKind.FORGE,
                "Forge на телефоне пока не ставится — выбери сборку на другом загрузчике",
                "без установщика сборка не должна молча запуститься ванилью",
            ),
            Case(
                FakeInstaller(LoaderKind.QUILT, emptyList()),
                LoaderKind.QUILT,
                "для 1.20.1 нет версий Quilt — выбери другую версию сборки",
                "пустой список версий — подсказка, что делать дальше",
            ),
        )
        for (case in cases) {
            val result = runCatching { ModpackLoader { case.installer }.resolve("1.20.1", case.loader, "") }
            val error = result.exceptionOrNull()
            assertTrue("${case.why}: ждали IOException, получили $error", error is IOException)
            assertEquals(case.why, case.message, error?.message)
        }
    }
}
