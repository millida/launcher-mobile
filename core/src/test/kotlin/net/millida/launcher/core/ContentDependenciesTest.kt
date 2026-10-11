package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.catalog.Catalog
import net.millida.launcher.core.catalog.CatalogFile
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.catalog.CurseForgeCatalog
import net.millida.launcher.core.catalog.ModrinthCatalog
import net.millida.launcher.core.content.CatalogLookup
import net.millida.launcher.core.content.ContentDependencies
import net.millida.launcher.core.content.DependencyNode
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.profile.Profile
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
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ContentDependenciesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var layout: GameLayout
    private lateinit var profileDir: File

    private val fabric = Profile(id = "build", name = "Сборка", gameVersion = "1.20.1", loader = LoaderKind.FABRIC)

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        layout = GameLayout(tmp.newFolder("store")).also { it.ensure() }
        profileDir = layout.profileDir("build").also { it.mkdirs() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun serve(routes: (String) -> MockResponse?) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes(request.path.orEmpty()) ?: MockResponse().setResponseCode(404)
        }
    }

    private fun dependencies(): ContentDependencies {
        val downloader = Downloader(attempts = 1)
        val base = server.url("/mr/v2").toString().trimEnd('/')
        val catalog = Catalog(
            downloader = downloader,
            modrinth = ModrinthCatalog(downloader, base),
            curseforge = CurseForgeCatalog(downloader, server.url("/cf").toString().trimEnd('/')),
            mirrorBase = server.url("/dl").toString().trimEnd('/'),
        )
        return ContentDependencies(layout, catalog, CatalogLookup(downloader, base))
    }

    private fun version(id: String, type: String = "release", withFile: Boolean = true): String {
        val files = if (withFile) {
            """[{"url":"${server.url("/files/$id.jar")}","filename":"$id.jar","size":123,"primary":true}]"""
        } else {
            "[]"
        }
        return """{"id":"$id","name":"$id","version_number":"$id","version_type":"$type","files":$files}"""
    }

    private fun root(vararg deps: CatalogVersion.Dependency) = CatalogVersion(
        id = "root-v",
        name = "Корень",
        projectId = "ROOT",
        dependencies = deps.toList(),
    )

    private fun installedJar(name: String, id: String, title: String) {
        val folder = File(profileDir, ContentKind.MOD.folder).also { it.mkdirs() }
        ZipOutputStream(File(folder, name).outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write("""{"id":"$id","name":"$title","version":"1.0.0"}""".toByteArray())
            zip.closeEntry()
        }
    }

    @Test
    fun `выбор версии зависимости и честный список того, что не нашлось`() = runBlocking {
        serve { path ->
            when {
                path.startsWith("/mr/v2/project/A/version") -> MockResponse().setBody("[" + version("a-new") + "," + version("a-old") + "]")
                path.startsWith("/mr/v2/project/B/version") -> MockResponse().setBody(
                    "[" + version("b-nofile", withFile = false) + "," + version("b-beta", "beta") + "," + version("b-rel") + "]"
                )
                path == "/mr/v2/project/A" -> MockResponse().setBody("""{"id":"A","slug":"alpha","title":"Alpha"}""")
                path == "/mr/v2/project/B" -> MockResponse().setBody("""{"id":"B","slug":"beta-lib","title":"Beta Lib"}""")
                else -> null
            }
        }

        val plan = dependencies().plan(
            fabric,
            profileDir,
            root(
                CatalogVersion.Dependency(projectId = "A", versionId = "a-old"),
                CatalogVersion.Dependency(projectId = "B"),
                CatalogVersion.Dependency(projectId = "C"),
                CatalogVersion.Dependency(projectId = "D", dependencyType = "optional"),
                CatalogVersion.Dependency(projectId = "E", dependencyType = "embedded"),
                CatalogVersion.Dependency(projectId = null),
                CatalogVersion.Dependency(projectId = "A"),
            ),
        )

        assertEquals("каталог не знает корень — заголовок из имени версии", "Корень", plan.title)
        assertEquals("ставятся только обязательные и только по разу", listOf("A", "B"), plan.missing.map { it.projectId })
        val (alpha, beta) = plan.missing
        assertEquals("закреплённая автором версия уважается, пока подходит сборке", "a-old", alpha.versionId)
        assertEquals("без закрепления — релиз, а не бета; версия без файла пропущена", "b-rel", beta.versionId)
        assertEquals("Alpha", alpha.title)
        assertEquals("размер виден до скачивания", 123L, alpha.sizeBytes)
        assertEquals("a-old.jar", alpha.fileName)
        assertEquals("версия знает свой проект — для записи об источнике", "A", alpha.version.projectId)
        assertEquals(CatalogSource.MODRINTH, alpha.source)

        val unresolved = plan.unresolved.single()
        assertEquals("без названия в каталоге показывается id", "C", unresolved.title)
        assertEquals("Корень", unresolved.requiredBy)
        assertEquals(
            "причина говорит, под что не нашлось версии",
            "нет версии под 1.20.1 и Fabric",
            unresolved.problem,
        )
        assertFalse(plan.truncated)
        assertFalse("есть что ставить — план не пустой", plan.empty)
    }

    @Test
    fun `огромный граф обрезается, а не висит`() = runBlocking {
        serve { null }
        val deps = (0 until 70).map { CatalogVersion.Dependency(projectId = "P$it") }

        val plan = dependencies().plan(fabric, profileDir, root(*deps.toTypedArray()))

        assertTrue("граф больше предела помечается обрезанным — игрок должен это увидеть", plan.truncated)
        assertEquals("предел — 64 узла", 64, plan.missing.size + plan.unresolved.size)
    }

    @Test
    fun `пустой план, когда всё уже стоит`() = runBlocking {
        installedJar("fapi.jar", "fabric-api", "Fabric API")
        serve { path -> if (path == "/mr/v2/project/FAPI") MockResponse().setBody("""{"id":"FAPI","slug":"fabric-api","title":"Fabric API"}""") else null }

        val plan = dependencies().plan(fabric, profileDir, root(CatalogVersion.Dependency(projectId = "FAPI")))

        assertTrue("нечего ставить и нечего объяснять", plan.empty)
        assertEquals(listOf("Fabric API"), plan.satisfied)
    }

    @Test
    fun `установка списка - одна общая зависимость качается один раз, сбой не останавливает остальное`() = runBlocking {
        installedJar("fapi.jar", "fabric-api", "Fabric API")
        serve { path ->
            when {
                path == "/files/lib.jar" -> MockResponse().setBody("lib-bytes")
                path == "/files/other.jar" -> MockResponse().setBody("other-bytes")
                else -> null
            }
        }

        fun node(projectId: String, title: String, file: String) = DependencyNode(
            projectId = projectId,
            source = CatalogSource.MODRINTH,
            title = title,
            versionId = "v",
            versionNumber = "1",
            fileName = file,
            sizeBytes = 0,
            requiredBy = "Корень",
            version = CatalogVersion(
                id = "v-$projectId",
                files = listOf(CatalogFile(url = server.url("/files/$file").toString(), filename = file, primary = true)),
                projectId = projectId,
            ),
        )

        val report = dependencies().install(
            profileDir,
            listOf(
                node("LIB", "Lib", "lib.jar"),
                node("LIB", "Lib", "lib.jar"),
                node("BROKEN", "Broken", "broken.jar"),
                node("fabric-api", "Fabric API", "fapi-new.jar"),
                node("OTHER", "Other", "other.jar"),
            ),
        )

        assertEquals("общая зависимость ставится один раз, уже стоящая пропускается", listOf("Lib", "Other"), report.installed)
        assertEquals("сбой назван по зависимости", listOf("Broken"), report.failed.keys.toList())
        assertTrue("причина сбоя не пустая", report.failed.getValue("Broken").isNotBlank())
        val names = File(profileDir, "mods").listFiles().orEmpty().map { it.name }.filter { it.endsWith(".jar") }.sorted()
        assertEquals("на диске ровно то, что встало", listOf("fapi.jar", "lib.jar", "other.jar"), names)
    }
}
