package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.catalog.CatalogFile
import net.millida.launcher.core.catalog.CatalogItem
import net.millida.launcher.core.catalog.CatalogProject
import net.millida.launcher.core.catalog.CatalogSection
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.catalog.CurseForgeCatalog
import net.millida.launcher.core.catalog.ModrinthCatalog
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ModrinthCurseForgeParseTest {

    @get:Rule
    val tmp = TemporaryFolder()

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

    private fun downloader() = Downloader(attempts = 1)

    private fun modrinth() = ModrinthCatalog(downloader(), server.url("/mr/v2").toString().trimEnd('/'))

    private fun curseforge() = CurseForgeCatalog(downloader(), server.url("/cf").toString().trimEnd('/'))

    @Test
    fun `поиск Modrinth собирает фасеты и ссылку на страницу`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"hits":[
                  {"project_id":"AANobbMI","slug":"sodium","title":"Sodium","downloads":5,"source":"bogus"},
                  {"project_id":"X1","slug":"","title":"NoSlug"}
                ],"total_hits":42}
                """.trimIndent()
            )
        )

        val page = modrinth().searchPage("", CatalogSection.MOD, "1.20.1", listOf(LoaderKind.VANILLA, LoaderKind.FABRIC), -5, 20)

        val url = server.takeRequest().requestUrl
        assertEquals(
            "ваниль не попадает в фильтр загрузчиков, иначе поиск модов пуст",
            """[["project_type:mod"],["versions:1.20.1"],["categories:fabric"]]""",
            url?.queryParameter("facets"),
        )
        assertEquals("пустой запрос сортируется по скачиваниям", "downloads", url?.queryParameter("index"))
        assertEquals("отрицательное смещение не уходит на сервер", "0", url?.queryParameter("offset"))
        assertNull("пустой запрос не отправляется", url?.queryParameter("query"))

        assertEquals(listOf("Sodium", "NoSlug"), page.items.map { it.title })
        assertEquals("https://modrinth.com/mod/sodium", page.items[0].pageUrl)
        assertEquals("без slug ссылка строится по id", "https://modrinth.com/mod/X1", page.items[1].pageUrl)
        assertTrue("источник всегда Modrinth, что бы ни пришло в ответе", page.items.all { it.source == CatalogSource.MODRINTH })
        assertEquals(2, page.fetched)
        assertEquals("общее число нужно для кнопки «ещё»", 42, page.total)
    }

    @Test
    fun `шейдеры ищутся без фильтра по загрузчику`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"hits":[]}"""))

        val page = modrinth().searchPage(" complementary ", CatalogSection.SHADER, null, listOf(LoaderKind.FABRIC), 40, 20)

        val url = server.takeRequest().requestUrl
        assertEquals(
            "у шейдеров нет категории fabric — с ней поиск ничего бы не нашёл",
            """[["project_type:shader"]]""",
            url?.queryParameter("facets"),
        )
        assertEquals("запрос с текстом сортируется по смыслу", "relevance", url?.queryParameter("index"))
        assertEquals("40", url?.queryParameter("offset"))
        assertTrue("пустой ответ — пустая страница", page.items.isEmpty())
        assertEquals(0, page.total)
    }

    @Test
    fun `галерея Modrinth - сначала главные кадры, пустые выброшены`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"slug":"","project_type":"resourcepack","body":"# Faithful",
                 "gallery":[
                   {"url":"b","featured":false,"ordering":1},
                   {"url":"a","featured":false,"ordering":0},
                   {"url":"f","featured":true,"ordering":5},
                   {"url":"","featured":true,"ordering":0}
                 ]}
                """.trimIndent()
            )
        )

        val project = modrinth().project("P9")

        assertEquals(CatalogProject("# Faithful", listOf("f", "a", "b"), "https://modrinth.com/resourcepack/P9"), project)
        assertEquals("/mr/v2/project/P9", server.takeRequest().path)
    }

    @Test
    fun `версии Modrinth запрашиваются с фильтром и получают id проекта`() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":"v1","files":[]},{"id":"v2","project_id":"OTHER"}]"""))
        server.enqueue(MockResponse().setBody("[]"))

        val versions = modrinth().versions("P1", "1.20.1", LoaderKind.FABRIC)
        val filtered = server.takeRequest().requestUrl

        assertEquals("""["1.20.1"]""", filtered?.queryParameter("game_versions"))
        assertEquals("""["fabric"]""", filtered?.queryParameter("loaders"))
        assertEquals("/mr/v2/project/P1/version", filtered?.encodedPath)
        assertEquals("пустой project_id заполняется запрошенным", "P1", versions[0].projectId)
        assertEquals("пришедший project_id не перетирается", "OTHER", versions[1].projectId)

        modrinth().versions("P1", "1.20.1", LoaderKind.VANILLA)
        assertNull("у ванили нет загрузчика для фильтра", server.takeRequest().requestUrl?.queryParameter("loaders"))
    }

    @Test
    fun `установка Modrinth не выпускает файл из папки и проверяет хеш`() = runBlocking {
        val profileDir = tmp.newFolder("build")
        server.enqueue(MockResponse().setBody("jar-bytes"))

        val traversal = CatalogVersion(
            id = "v",
            files = listOf(CatalogFile(url = server.url("/files/x.jar").toString(), filename = "../../evil.jar", primary = true)),
        )
        val installed = modrinth().install(traversal, ContentKind.MOD, profileDir)

        assertEquals(
            "имя из каталога с .. не выводит файл из папки модов",
            File(profileDir, "mods").canonicalPath,
            installed.parentFile.canonicalPath,
        )
        assertFalse("в имени не осталось разделителей", installed.name.contains('/'))
        assertEquals("jar-bytes", installed.readText())

        server.enqueue(MockResponse().setBody("other-bytes"))
        val wrongHash = CatalogVersion(
            id = "w",
            files = listOf(
                CatalogFile(
                    url = server.url("/files/y.jar").toString(),
                    filename = "y.jar",
                    hashes = mapOf("sha1" to "0".repeat(40)),
                )
            ),
        )
        val refused = runCatching { modrinth().install(wrongHash, ContentKind.MOD, profileDir) }
        assertTrue("подменённый файл не ставится", refused.exceptionOrNull() is IOException)
        assertFalse("и не остаётся на диске", File(profileDir, "mods/y.jar").exists())

        val empty = runCatching { modrinth().install(CatalogVersion(id = "e"), ContentKind.MOD, profileDir) }
        assertEquals("у версии нет файла для скачивания", empty.exceptionOrNull()?.message)
    }

    @Test
    fun `главный файл версии и загрузчики карточки`() {
        val a = CatalogFile(url = "u", filename = "a.jar")
        val b = CatalogFile(url = "u", filename = "b.jar", primary = true)
        assertEquals("отмеченный главным выигрывает", "b.jar", CatalogVersion(id = "1", files = listOf(a, b)).primaryFile()?.filename)
        assertEquals("без отметки — первый", "a.jar", CatalogVersion(id = "1", files = listOf(a, a.copy(filename = "c.jar"))).primaryFile()?.filename)
        assertNull("без файлов ставить нечего", CatalogVersion(id = "1").primaryFile())

        val modrinthCard = CatalogItem(projectId = "1", categories = listOf("Fabric", "technology", "forge", "fabric"))
        assertEquals("Modrinth прячет загрузчики в категориях", listOf("fabric", "forge"), modrinthCard.loaderIds)
        val curseCard = CatalogItem(projectId = "2", loaders = listOf("NeoForge"), categories = listOf("fabric"), source = CatalogSource.CURSEFORGE)
        assertEquals("явный список загрузчиков главнее категорий", listOf("neoforge"), curseCard.loaderIds)
        assertEquals("один id в двух каталогах — разные карточки", "curseforge:2", curseCard.key)

        val cover = CatalogItem(featuredGallery = "  ", gallery = listOf("", "g1", "g2"))
        assertEquals("пустая обложка заменяется первым кадром", "g1", cover.cover)
        assertNull("без кадров обложки нет", CatalogItem().cover)
    }

    @Test
    fun `файлы CurseForge превращаются в версии`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":[
                  {"id":1,"fileName":"a.jar","displayName":"A 1.0","downloadUrl":"https://edge.forgecdn.net/a.jar",
                   "fileLength":10,"releaseType":2,"gameVersions":["1.20.1","Forge"],
                   "hashes":[{"value":"m5","algo":2},{"value":"","algo":1},{"value":"zz","algo":7}]},
                  {"id":2,"fileName":"b.jar","displayName":"","downloadUrl":null,"releaseType":3,"gameVersions":["1.20.1"]},
                  {"fileName":"noid.jar"},
                  {"id":4,"fileName":"c.jar","displayName":"C","releaseType":9}
                ]}
                """.trimIndent()
            )
        )

        val versions = curseforge().versions("238222", "1.20.1", LoaderKind.FORGE)

        val url = server.takeRequest().requestUrl
        assertEquals("/cf/v1/mods/238222/files", url?.encodedPath)
        assertEquals("Forge в CurseForge — код 1", "1", url?.queryParameter("modLoaderType"))
        assertEquals("1.20.1", url?.queryParameter("gameVersion"))

        assertEquals("файл без id пропускается", listOf("1", "2", "4"), versions.map { it.id })
        val (beta, alpha, release) = versions
        assertEquals("releaseType 2 — бета", "beta", beta.versionType)
        assertEquals("releaseType 3 — альфа", "alpha", alpha.versionType)
        assertEquals("незнакомый тип считается релизом", "release", release.versionType)
        assertEquals("загрузчик берётся из тегов файла", listOf("forge"), beta.loaders)
        assertEquals("без тега — загрузчик запроса", listOf("forge"), alpha.loaders)
        assertEquals("md5 подписывается как md5, пустые и неизвестные хеши выброшены", mapOf("md5" to "m5"), beta.primaryFile()?.hashes)
        assertEquals("без отображаемого имени — имя файла", "b.jar", alpha.name)
        assertEquals("null вместо ссылки — пустая строка, а не «null»", "", alpha.primaryFile()?.url)
        assertEquals(10L, beta.primaryFile()?.size)
        assertTrue("все версии помнят проект и источник", versions.all { it.projectId == "238222" && it.source == CatalogSource.CURSEFORGE })
    }

    @Test
    fun `версии CurseForge для ванили и пустой ответ`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":5,"fileName":"r.zip"}]}"""))
        server.enqueue(MockResponse().setBody("{}"))

        val pack = curseforge().versions("9", "", LoaderKind.VANILLA)
        val url = server.takeRequest().requestUrl
        assertNull("у ванили нет кода загрузчика", url?.queryParameter("modLoaderType"))
        assertNull("пустая версия игры не отправляется", url?.queryParameter("gameVersion"))
        assertEquals("ресурспаку загрузчик не приписывается", emptyList<String>(), pack.single().loaders)

        assertTrue("ответ без data — пустой список", curseforge().versions("9", "1.20.1", LoaderKind.FABRIC).isEmpty())
    }

    @Test
    fun `поиск CurseForge - окно, сортировка и отсев сборок по загрузчику`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":[
                  {"id":1,"name":"ForgePack","latestFilesIndexes":[{"gameVersion":"1.20.1","modLoader":1}]},
                  {"id":2,"name":"FabricPack","summary":null,"logo":{"thumbnailUrl":"","url":"https://media.forgecdn.net/2.png"},
                   "latestFilesIndexes":[{"gameVersion":"1.20.1","modLoader":4},{"gameVersion":"1.20.1","modLoader":4},{"gameVersion":"1.19.2","modLoader":5}]},
                  {"id":3,"name":"Unknown"},
                  {"name":"NoId"}
                ],"pagination":{"totalCount":777}}
                """.trimIndent()
            )
        )

        val page = curseforge().searchPage("", CatalogSection.MODPACK, null, listOf(LoaderKind.FABRIC), 20000, 500)

        val url = server.takeRequest().requestUrl
        assertEquals("страница не больше 50", "50", url?.queryParameter("pageSize"))
        assertEquals("окно CurseForge кончается на 10000", "9950", url?.queryParameter("index"))
        assertEquals("пустой запрос — по скачиваниям", "6", url?.queryParameter("sortField"))
        assertEquals("4471", url?.queryParameter("classId"))
        assertNull("сборки нельзя фильтровать по загрузчику на стороне CurseForge", url?.queryParameter("modLoaderType"))

        assertEquals(
            "Forge-сборка в Fabric-поиске отсеяна до скачивания, сборка без данных оставлена",
            listOf("FabricPack", "Unknown"),
            page.items.map { it.title },
        )
        val fabricPack = page.items.first()
        assertEquals("null в описании — пусто", "", fabricPack.description)
        assertEquals("пустая миниатюра заменяется логотипом", "https://media.forgecdn.net/2.png", fabricPack.iconUrl)
        assertEquals(listOf("fabric", "quilt"), fabricPack.loaders)
        assertEquals(listOf("1.20.1", "1.19.2"), fabricPack.gameVersions)
        assertEquals("modpack", fabricPack.projectType)
        assertEquals("получено считается до отсева — иначе «ещё» сломается", 4, page.fetched)
        assertEquals(777, page.total)
    }

    @Test
    fun `поиск CurseForge без пагинации и без data`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":7,"name":"JEI"}]}"""))
        server.enqueue(MockResponse().setBody("""{"error":"x"}"""))

        val page = curseforge().searchPage("jei", CatalogSection.MOD, "1.20.1", listOf(LoaderKind.FABRIC, LoaderKind.FORGE), 0, 20)
        val url = server.takeRequest().requestUrl
        assertEquals("поиск с текстом — по популярности", "2", url?.queryParameter("sortField"))
        assertEquals("jei", url?.queryParameter("searchFilter"))
        assertNull("два загрузчика одним кодом не передать", url?.queryParameter("modLoaderType"))
        assertEquals("без пагинации всего — сколько пришло", 1, page.total)

        val empty = curseforge().searchPage("x", CatalogSection.MOD, null, emptyList(), 0, 20)
        assertTrue(empty.items.isEmpty())
        assertEquals(0, empty.total)
    }

    @Test
    fun `страница проекта и ссылка на скачивание CurseForge`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"screenshots":[{"url":"","thumbnailUrl":"t1"},{"url":"u2"}],"links":{"websiteUrl":"https://www.curseforge.com/p"}}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(500))

        val project = curseforge().project("P")
        assertEquals(
            "описание не загрузилось — страница всё равно открывается",
            CatalogProject("", listOf("t1", "u2"), "https://www.curseforge.com/p"),
            project,
        )

        server.enqueue(MockResponse().setBody("{}"))
        val missing = runCatching { curseforge().project("Q") }
        assertEquals("CurseForge не нашёл этот проект — обнови список", missing.exceptionOrNull()?.message)

        server.enqueue(MockResponse().setBody("""{"data":"https://edge.forgecdn.net/files/1/2/x.jar"}"""))
        assertEquals("https://edge.forgecdn.net/files/1/2/x.jar", curseforge().downloadUrl("P", "F"))

        server.enqueue(MockResponse().setBody("""{"data":""}"""))
        val hidden = runCatching { curseforge().downloadUrl("P", "F") }
        assertEquals("скрытая ссылка не превращается в скачивание пустого адреса", "файл недоступен для скачивания", hidden.exceptionOrNull()?.message)

        server.takeRequest()
        server.takeRequest()
        server.takeRequest()
        assertEquals("/cf/v1/mods/P/files/F/download-url", server.takeRequest().path)
    }
}
