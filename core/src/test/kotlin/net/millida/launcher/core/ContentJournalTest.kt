package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.catalog.Catalog
import net.millida.launcher.core.catalog.CatalogFile
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.packs.PackOriginFile
import net.millida.launcher.core.packs.PackOriginStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Sharing a build, exporting it and syncing it to the other device all answer
 * the same question: where did this file come from. The launcher can only
 * answer it if it wrote the line down when it installed the file, so that is
 * what is pinned here.
 */
class ContentJournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun jar(body: String): Buffer = Buffer().writeUtf8(body)

    @Test
    fun `установка из каталога записывает, откуда взялся файл`() = runBlocking {
        val body = "не настоящий мод, но байты честные"
        server.enqueue(MockResponse().setBody(jar(body)))
        val profileDir = folder.newFolder("build")
        val url = server.url("/data/AANobbMI/versions/1/sodium.jar").toString()

        val catalog = Catalog(Downloader(attempts = 1))
        val version = CatalogVersion(
            id = "abc123",
            projectId = "AANobbMI",
            name = "Sodium 0.6",
            versionNumber = "0.6",
            versionType = "release",
            source = CatalogSource.MODRINTH,
            files = listOf(CatalogFile(filename = "sodium.jar", url = url, hashes = emptyMap(), size = body.length.toLong())),
        )

        catalog.install(version, ContentKind.MOD, profileDir)

        val recorded = PackOriginStore.files(profileDir)
        assertEquals("записана ровно одна строка на один поставленный файл", 1, recorded.size)
        val entry = recorded.first()
        assertEquals("путь записан относительно сборки", "mods/sodium.jar", entry.path)
        assertEquals("проект запомнен — по нему сборка соберётся на другом устройстве", "AANobbMI", entry.projectId)
        assertEquals("версия запомнена", "abc123", entry.versionId)
        assertTrue("адрес запомнен, иначе восстановить нечем", entry.url.isNotEmpty())
        assertTrue("размер взят с диска", entry.size > 0)
    }

    @Test
    fun `сорванная загрузка не оставляет записи о моде, которого нет`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val profileDir = folder.newFolder("build")
        val catalog = Catalog(Downloader(attempts = 1))
        val version = CatalogVersion(
            id = "v1",
            projectId = "PROJ",
            name = "Мод",
            versionNumber = "1.0",
            versionType = "release",
            source = CatalogSource.MODRINTH,
            files = listOf(
                CatalogFile(
                    filename = "mod.jar",
                    url = server.url("/dead.jar").toString(),
                    hashes = emptyMap(),
                    size = 10,
                ),
            ),
        )

        runCatching { catalog.install(version, ContentKind.MOD, profileDir) }

        assertTrue(
            "запись без файла обещала бы восстановление, которого не будет",
            PackOriginStore.files(profileDir).isEmpty(),
        )
    }

    @Test
    fun `повторная установка того же пути не плодит вторую строку`() {
        val profileDir = folder.newFolder("build")
        PackOriginStore.record(profileDir, PackOriginFile(path = "mods/a.jar", url = "https://x/a.jar", versionId = "1"))
        PackOriginStore.record(profileDir, PackOriginFile(path = "mods/a.jar", url = "https://x/a2.jar", versionId = "2"))

        val files = PackOriginStore.files(profileDir)
        assertEquals("один файл — одна строка", 1, files.size)
        assertEquals("строка описывает то, что лежит сейчас", "2", files.first().versionId)
    }

    @Test
    fun `удалённый файл перестаёт попадать в список`() {
        val profileDir = folder.newFolder("build")
        PackOriginStore.record(profileDir, PackOriginFile(path = "mods/a.jar", url = "https://x/a.jar"))
        PackOriginStore.record(profileDir, PackOriginFile(path = "mods/b.jar", url = "https://x/b.jar"))

        PackOriginStore.forget(profileDir, "mods/a.jar")

        val left = PackOriginStore.files(profileDir).map { it.path }
        assertEquals("остался только второй", listOf("mods/b.jar"), left)
    }

    @Test
    fun `битый журнал читается как пустой, а не роняет сборку`() {
        val profileDir = folder.newFolder("build")
        File(profileDir, PackOriginStore.FILE_NAME).writeText("{ это не json")

        assertTrue("сборка должна открываться и без журнала", PackOriginStore.files(profileDir).isEmpty())

        PackOriginStore.record(profileDir, PackOriginFile(path = "mods/a.jar", url = "https://x/a.jar"))
        assertEquals("после записи журнал снова живой", 1, PackOriginStore.files(profileDir).size)
    }
}
