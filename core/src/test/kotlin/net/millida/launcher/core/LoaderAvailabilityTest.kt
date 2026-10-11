package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.loader.LoaderMirror
import net.millida.launcher.core.loader.LoaderVersion
import net.millida.launcher.core.loader.MetaLoaderInstaller
import net.millida.launcher.core.loader.NeoForgeVersions
import net.millida.launcher.core.loader.neoForgeLegacyBuilds
import net.millida.launcher.core.loader.neoForgePrerelease
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class LoaderAvailabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val routes = HashMap<String, MockResponse>()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.path.orEmpty()] ?: MockResponse().setResponseCode(404)
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private data class MetaCase(val code: Int, val empty: Boolean, val why: String)

    @Test
    fun `версия без сборок загрузчика даёт пустой список, а сбой сервера — ошибку`() {
        val cases = listOf(
            MetaCase(400, true, "Fabric meta отвечает 400 на 1.12.2 — это «сборок нет», а не поломка"),
            MetaCase(404, true, "неизвестная версия игры — тоже «сборок нет»"),
            MetaCase(500, false, "сервер упал — игрок должен видеть сбой, а не «загрузчик не выходил»"),
        )
        val layout = GameLayout(tmp.newFolder("game")).also { it.ensure() }
        for (case in cases) {
            routes["/meta/versions/loader/1.12.2"] = MockResponse().setResponseCode(case.code)
            val installer = MetaLoaderInstaller(
                LoaderKind.FABRIC,
                layout,
                Downloader(attempts = 1),
                server.url("/meta").toString().trimEnd('/'),
            )
            val result = runCatching { runBlocking { installer.versions("1.12.2") } }
            if (case.empty) {
                assertEquals("ответ ${case.code}: ${case.why}", emptyList<LoaderVersion>(), result.getOrNull())
            } else {
                assertTrue("ответ ${case.code}: ${case.why}; было $result", result.exceptionOrNull() is IOException)
            }
        }
    }

    private data class LegacyCase(val game: String, val expected: List<String>, val why: String)

    @Test
    fun `NeoForge для 1_20_1 берётся из артефакта forge`() {
        val list = """{"versions":["1.20.1-47.1.3","1.20.1-47.1.106","1.20.1-47.1.79","47.1.0","1.20.2-20.2.3-beta"]}"""
        val cases = listOf(
            LegacyCase("1.20.1", listOf("1.20.1-47.1.106", "1.20.1-47.1.79", "1.20.1-47.1.3"),
                "сортировка числами: строкой 47.1.79 оказалась бы выше 47.1.106"),
            LegacyCase("1.21.1", emptyList(), "старый артефакт отвечает только за 1.20.1"),
        )
        for (case in cases) {
            assertEquals("${case.game}: ${case.why}", case.expected, neoForgeLegacyBuilds(list, case.game))
        }
        assertEquals("номер ветки 1.20.1 через дефис — не бета", false, neoForgePrerelease("1.20.1-47.1.106"))
        assertEquals("бета новой ветки остаётся бетой", true, neoForgePrerelease("21.1.0-beta"))
    }

    @Test
    fun `сборки NeoForge 1_20_1 спрашиваются у старого артефакта и ставятся его установщиком`() {
        routes["/forge"] = MockResponse().setBody("""{"versions":["1.20.1-47.1.106","1.20.1-47.1.3"]}""")
        val source = NeoForgeVersions(
            LoaderMirror(Downloader(attempts = 1)),
            versionsUrl = server.url("/neoforge").toString(),
            legacyVersionsUrl = server.url("/forge").toString(),
            legacyMavenBase = "https://maven.neoforged.net/releases/net/neoforged/forge",
        )
        assertEquals(
            "раньше спрашивался только артефакт neoforge, и для 1.20.1 список был пуст",
            listOf(LoaderVersion("1.20.1-47.1.106", true), LoaderVersion("1.20.1-47.1.3", true)),
            runBlocking { source.builds("1.20.1") },
        )

        data class InstallerCase(val build: String, val url: String, val id: String, val why: String)
        listOf(
            InstallerCase(
                "1.20.1-47.1.106",
                "https://maven.neoforged.net/releases/net/neoforged/forge/1.20.1-47.1.106/forge-1.20.1-47.1.106-installer.jar",
                "neoforge-1.20.1-47.1.106",
                "сборка из списка",
            ),
            InstallerCase(
                "47.1.106",
                "https://maven.neoforged.net/releases/net/neoforged/forge/1.20.1-47.1.106/forge-1.20.1-47.1.106-installer.jar",
                "neoforge-1.20.1-47.1.106",
                "манифест CurseForge пишет только номер ветки — версия игры дописывается",
            ),
        ).forEach { case ->
            val ref = source.installers("1.20.1", case.build).single()
            assertEquals("адрес установщика: ${case.why}", case.url, ref.url)
            assertEquals("папка версии: ${case.why}", case.id, ref.versionId)
        }
    }
}
