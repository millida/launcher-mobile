package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.catalog.Catalog
import net.millida.launcher.core.catalog.CatalogFile
import net.millida.launcher.core.catalog.CatalogFilter
import net.millida.launcher.core.catalog.CatalogItem
import net.millida.launcher.core.catalog.CatalogSection
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.catalog.CurseForgeCatalog
import net.millida.launcher.core.catalog.ModrinthCatalog
import net.millida.launcher.core.catalog.mergeKey
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.packs.PackOriginStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URLEncoder
import java.util.Collections

class CatalogMergeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val paths: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun serve(routes: (String) -> MockResponse?) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += path
                return routes(path) ?: MockResponse().setResponseCode(404)
            }
        }
    }

    private fun catalog(): Catalog {
        val downloader = Downloader(attempts = 1)
        return Catalog(
            downloader = downloader,
            modrinth = ModrinthCatalog(downloader, server.url("/mr/v2").toString().trimEnd('/')),
            curseforge = CurseForgeCatalog(downloader, server.url("/cf").toString().trimEnd('/')),
            mirrorBase = server.url("/dl").toString().trimEnd('/'),
        )
    }

    private fun bothCatalogues(modrinthTotal: Int) = serve { path ->
        when {
            path.startsWith("/mr/v2/search") -> MockResponse().setBody(
                """
                {"hits":[
                  {"project_id":"m1","slug":"sodium","title":"Sodium","downloads":100},
                  {"project_id":"m2","slug":"sodium-extra","title":"Sodium Extra","downloads":50},
                  {"project_id":"m3","slug":"lithium","title":"Lithium","downloads":1000}
                ],"total_hits":$modrinthTotal}
                """.trimIndent()
            )
            path.startsWith("/cf/v1/mods/search") -> MockResponse().setBody(
                """{"data":[{"id":11,"name":"Sodium (Fabric)","downloadCount":999}],"pagination":{"totalCount":1}}"""
            )
            else -> null
        }
    }

    @Test
    fun `один проект из двух каталогов сводится к одному ключу`() {
        data class Case(val item: CatalogItem, val want: String, val why: String)

        val cases = listOf(
            Case(CatalogItem(title = "Sodium"), "sodium", "базовое имя"),
            Case(CatalogItem(title = "Sodium (Fabric)"), "sodium", "так тот же мод называется на CurseForge"),
            Case(CatalogItem(title = "Sodium [Forge Edition]"), "sodium", "пометка в скобках не делает проект другим"),
            Case(CatalogItem(title = "Iris Shaders Fabric"), "irisshaders", "слово загрузчика вне скобок тоже срезается"),
            Case(CatalogItem(title = "Just Enough Items"), "justenoughitems", "JEI и полное имя — разные ключи, их не склеить по названию"),
            Case(CatalogItem(title = "Карта Мира"), "картамира", "кириллица остаётся в ключе"),
            Case(CatalogItem(title = "Fabric Mod", slug = "fm"), "fm", "название из одних служебных слов — ключ по slug"),
            Case(CatalogItem(projectId = "7", source = CatalogSource.CURSEFORGE), "curseforge:7", "без названия и slug — ключ карточки"),
        )
        for (case in cases) {
            assertEquals("«${case.item.title}»: ${case.why}", case.want, case.item.mergeKey)
        }
    }

    @Test
    fun `двойник из второго каталога не показывается дважды`() = runBlocking {
        bothCatalogues(modrinthTotal = 3)

        val page = catalog().searchPage("sodium", CatalogSection.MOD, "1.20.1", listOf(LoaderKind.FABRIC))

        assertEquals(
            "Sodium из CurseForge склеен с Modrinth; точное совпадение названия выше популярного Lithium",
            listOf("Sodium", "Sodium Extra", "Lithium"),
            page.items.map { it.title },
        )
        assertEquals("оставлена карточка первого каталога", CatalogSource.MODRINTH, page.items.first().source)
        assertFalse("оба каталога отдали всё — кнопки «ещё» нет", page.more)
    }

    @Test
    fun `пустой запрос сортируется по скачиваниям, а «ещё» видно по остатку`() = runBlocking {
        bothCatalogues(modrinthTotal = 100)

        val page = catalog().searchPage("", CatalogSection.MOD, null, emptyList())

        assertEquals(listOf("Lithium", "Sodium", "Sodium Extra"), page.items.map { it.title })
        assertTrue("у Modrinth осталось 97 карточек — кнопка «ещё» нужна", page.more)
    }

    @Test
    fun `фильтр каталога спрашивает только выбранный`() = runBlocking {
        bothCatalogues(modrinthTotal = 3)

        val onlyModrinth = catalog().searchPage("", CatalogSection.MOD, null, emptyList(), CatalogFilter.MODRINTH)
        assertTrue("CurseForge не спрашивали", paths.none { it.startsWith("/cf/") })
        assertEquals(3, onlyModrinth.items.size)

        paths.clear()
        val onlyCurse = catalog().searchPage("", CatalogSection.MOD, null, emptyList(), CatalogFilter.CURSEFORGE)
        assertTrue("Modrinth не спрашивали", paths.none { it.startsWith("/mr/") })
        assertEquals(listOf("Sodium (Fabric)"), onlyCurse.items.map { it.title })
    }

    @Test
    fun `карты не ищутся в чужих каталогах`() = runBlocking {
        bothCatalogues(modrinthTotal = 3)

        val page = catalog().searchPage("sky", CatalogSection.WORLD, null, emptyList())

        assertTrue(page.items.isEmpty())
        assertFalse(page.more)
        assertTrue("карты — только каталог Millida, запросов наружу нет", paths.isEmpty())
        assertEquals("у полки модов есть папка", ContentKind.MOD, CatalogSection.of(ContentKind.MOD).kind)
        assertFalse(CatalogSection.WORLD.foreign)
    }

    @Test
    fun `заблокированная ссылка скачивается через зеркало и запоминается`() = runBlocking {
        val original = server.url("/files/a.jar").toString()
        serve { path ->
            when {
                path.startsWith("/files/a.jar") -> MockResponse().setResponseCode(500)
                path.startsWith("/dl") -> MockResponse().setBody("mirrored")
                else -> null
            }
        }
        val profileDir = tmp.newFolder("build")
        val version = CatalogVersion(
            id = "v1",
            files = listOf(CatalogFile(url = original, filename = "a.jar", primary = true)),
            projectId = "P1",
        )

        val installed = catalog().install(version, ContentKind.MOD, profileDir)

        assertEquals("mirrored", installed.readText())
        assertTrue(
            "зеркало получает исходный адрес целиком: $paths",
            paths.contains("/dl?url=" + URLEncoder.encode(original, "UTF-8")),
        )
        val record = PackOriginStore.files(profileDir).single()
        assertEquals("запись о файле идёт относительно сборки", "mods/a.jar", record.path)
        assertEquals("P1", record.projectId)
        assertEquals("v1", record.versionId)
    }

    @Test
    fun `скрытая ссылка CurseForge запрашивается перед скачиванием`() = runBlocking {
        val direct = server.url("/files/b.jar").toString()
        serve { path ->
            when (path) {
                "/cf/v1/mods/P/files/F/download-url" -> MockResponse().setBody("""{"data":"$direct"}""")
                "/files/b.jar" -> MockResponse().setBody("cf-bytes")
                else -> null
            }
        }
        val profileDir = tmp.newFolder("cf")
        val version = CatalogVersion(
            id = "F",
            files = listOf(CatalogFile(url = "", filename = "b.jar", primary = true)),
            projectId = "P",
            source = CatalogSource.CURSEFORGE,
        )

        val installed = catalog().install(version, ContentKind.MOD, profileDir)

        assertEquals("cf-bytes", installed.readText())
        assertEquals("ссылка в записи — настоящая, а не пустая", direct, PackOriginStore.files(profileDir).single().url)
    }

    @Test
    fun `свободный фильтр версий не шлёт пустых условий`() = runBlocking {
        serve { path -> if (path.startsWith("/mr/v2/project/P1/version")) MockResponse().setBody("[]") else null }

        catalog().versionsFor(CatalogItem(projectId = "P1"), null, null)
        catalog().versionsFor(CatalogItem(projectId = "P1"), "1.20.1", LoaderKind.VANILLA)

        assertEquals(
            "без версии и загрузчика — все версии; ваниль не превращается в фильтр",
            listOf("/mr/v2/project/P1/version", "/mr/v2/project/P1/version?game_versions=%5B%221.20.1%22%5D"),
            paths.toList(),
        )
    }
}
