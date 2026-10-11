package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.catalog.Catalog
import net.millida.launcher.core.catalog.CatalogFilter
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.catalog.CurseForgeCatalog
import net.millida.launcher.core.catalog.ModrinthCatalog
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Both catalogues are asked through our own proxy, and their answers look
 * nothing alike. The launcher installs from one place, so the shapes have to
 * meet before they reach the screen - that is what is pinned here.
 */
class CatalogSourcesTest {

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

    private fun base(): String = server.url("/launcher/cf").toString().trimEnd('/')

    @Test
    fun `ответ CurseForge превращается в те же карточки, что и Modrinth`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":[
                  {"id":238222,"name":"JEI","slug":"jei","summary":"Просмотр рецептов",
                   "downloadCount":123456789,
                   "logo":{"thumbnailUrl":"https://media.forgecdn.net/jei.png"},
                   "categories":[{"name":"Интерфейс"},{"name":"Утилиты"}]}
                ]}
                """.trimIndent()
            )
        )

        val catalog = CurseForgeCatalog(Downloader(), base())
        val items = catalog.search("jei", ContentKind.MOD, "1.21.4", LoaderKind.FABRIC)

        assertEquals("карточка одна", 1, items.size)
        val item = items.first()
        assertEquals("название переносится", "JEI", item.title)
        assertEquals("описание берётся из summary", "Просмотр рецептов", item.description)
        assertEquals("идентификатор — числовой id проекта", "238222", item.projectId)
        assertEquals("иконка есть", "https://media.forgecdn.net/jei.png", item.iconUrl)
        assertEquals("источник виден в интерфейсе", CatalogSource.CURSEFORGE, item.source)
        assertEquals("загрузки переносятся", 123456789L, item.downloads)

        val request = server.takeRequest()
        assertTrue(
            "запрос идёт в наш прокси, а не напрямую в CurseForge",
            request.path.orEmpty().startsWith("/launcher/cf/v1/mods/search"),
        )
        assertTrue(
            "фильтр по загрузчику доезжает",
            request.path.orEmpty().contains("modLoaderType=4"),
        )
    }

    @Test
    fun `файл CurseForge читается как версия с ссылкой и хешем`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":[
                  {"id":5555,"fileName":"jei-1.21.4.jar","displayName":"JEI 19.0",
                   "downloadUrl":"https://edge.forgecdn.net/files/5/555/jei.jar",
                   "fileLength":2048,"releaseType":1,
                   "gameVersions":["1.21.4","Fabric"],
                   "hashes":[{"value":"abc123","algo":1}]}
                ]}
                """.trimIndent()
            )
        )

        val versions = CurseForgeCatalog(Downloader(), base())
            .versions("238222", "1.21.4", LoaderKind.FABRIC)

        assertEquals("версия одна", 1, versions.size)
        val file = versions.first().primaryFile()
        assertEquals("имя файла на месте", "jei-1.21.4.jar", file?.filename)
        assertEquals("ссылка на месте", "https://edge.forgecdn.net/files/5/555/jei.jar", file?.url)
        assertEquals("хеш подписан как sha1", "abc123", file?.hashes?.get("sha1"))
        assertEquals("размер переносится", 2048L, file?.size)
        assertEquals("источник запомнен для установки", CatalogSource.CURSEFORGE, versions.first().source)
    }

    @Test
    fun `падение одного каталога не оставляет экран пустым`() = runBlocking {
        // Modrinth отвечает ошибкой, CurseForge отдаёт карточку: игрок всё равно
        // видит список, а не пустой экран.
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(
            MockResponse().setBody("""{"data":[{"id":1,"name":"Sodium","summary":"Быстрее"}]}""")
        )

        val downloader = Downloader(attempts = 1)
        val catalog = Catalog(
            downloader = downloader,
            modrinth = ModrinthCatalog(downloader, server.url("/launcher/mr/v2").toString().trimEnd('/')),
            curseforge = CurseForgeCatalog(downloader, base()),
        )

        val items = catalog.search(
            query = "sodium",
            kind = ContentKind.MOD,
            gameVersion = "1.21.4",
            loader = LoaderKind.FABRIC,
        )

        assertEquals("карточка из живого каталога показана", 1, items.size)
        assertEquals("Sodium", items.first().title)
    }

    @Test
    fun `если оба каталога молчат, ошибка доходит до игрока`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        val downloader = Downloader(attempts = 1)
        val catalog = Catalog(
            downloader = downloader,
            modrinth = ModrinthCatalog(downloader, server.url("/launcher/mr/v2").toString().trimEnd('/')),
            curseforge = CurseForgeCatalog(downloader, base()),
        )

        val failure = runCatching {
            catalog.search("sodium", ContentKind.MOD, "1.21.4", LoaderKind.FABRIC)
        }
        assertTrue("молчание обоих каталогов не выдаётся за пустой поиск", failure.isFailure)
    }
}
