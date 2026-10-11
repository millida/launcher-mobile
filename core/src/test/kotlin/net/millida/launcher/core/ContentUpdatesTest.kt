package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.catalog.Catalog
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.catalog.CurseForgeCatalog
import net.millida.launcher.core.catalog.ModrinthCatalog
import net.millida.launcher.core.content.CatalogLookup
import net.millida.launcher.core.content.ContentUpdates
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.packs.PackOriginFile
import net.millida.launcher.core.packs.PackOriginStore
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

class ContentUpdatesTest {

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

    private fun mods(): File = File(profileDir, ContentKind.MOD.folder).also { it.mkdirs() }

    private fun modNames(): List<String> = mods().listFiles().orEmpty().map { it.name }.sorted()

    private fun jar(name: String, id: String, title: String): File {
        val target = File(mods(), name)
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write("""{"id":"$id","name":"$title","version":"1.0.0"}""".toByteArray())
            zip.closeEntry()
        }
        return target
    }

    private fun version(id: String, type: String = "release"): String =
        """{"id":"$id","name":"$id","version_number":"$id","version_type":"$type",
            "files":[{"url":"${server.url("/files/$id.jar")}","filename":"$id.jar","primary":true}]}"""

    private fun updates(): ContentUpdates {
        val downloader = Downloader(attempts = 1)
        val base = server.url("/mr/v2").toString().trimEnd('/')
        val catalog = Catalog(
            downloader = downloader,
            modrinth = ModrinthCatalog(downloader, base),
            curseforge = CurseForgeCatalog(downloader, server.url("/cf").toString().trimEnd('/')),
            mirrorBase = server.url("/dl").toString().trimEnd('/'),
        )
        return ContentUpdates(layout, catalog, CatalogLookup(downloader, base))
    }

    private fun serve(hashes: Map<String, String>, routes: (String) -> MockResponse?) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/mr/v2/version_file/")) {
                    val sha = path.removePrefix("/mr/v2/version_file/").substringBefore('?')
                    return hashes[sha]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                }
                return routes(path) ?: MockResponse().setResponseCode(404)
            }
        }
    }

    private fun sha(file: File): String = Hashing.sha1(file).lowercase()

    @Test
    fun `план делит файлы на свежие, обновляемые и неопознанные`() = runBlocking {
        val fresh = jar("a-1.jar", "a", "Ay")
        val stale = jar("b-1.jar", "b", "Bee")
        val dead = jar("c-1.jar", "c", "Cee")
        jar("homemade.jar", "home", "Самосбор")
        serve(
            mapOf(
                sha(fresh) to """{"id":"a1","project_id":"A","version_number":"1.0"}""",
                sha(stale) to """{"id":"b1","project_id":"B","version_number":""}""",
                sha(dead) to """{"id":"c1","project_id":"C","version_number":"1.0"}""",
            )
        ) { path ->
            when {
                path.startsWith("/mr/v2/project/A/version") -> MockResponse().setBody("[" + version("a1") + "]")
                path.startsWith("/mr/v2/project/B/version") -> MockResponse().setBody("[" + version("b2", "beta") + "," + version("b3") + "]")
                else -> null
            }
        }

        val plan = updates().plan(fabric, profileDir)

        assertEquals(
            "a уже на последней версии; у c каталог не отдал версий — ни то ни другое не выдаётся за обновление",
            listOf("a-1.jar", "c-1.jar"),
            plan.upToDate.map { it.name }.sorted(),
        )
        assertEquals("файл, которого нет в каталоге, назван отдельно", listOf("homemade.jar"), plan.unrecognised.map { it.name })

        val candidate = plan.candidates.single()
        assertEquals("b-1.jar", candidate.file.name)
        assertEquals("релиз выбирается раньше беты, даже если бета первая в списке", "b3", candidate.toVersion)
        assertEquals("пустой номер в каталоге заменяется версией из jar", "1.0.0", candidate.fromVersion)
        assertEquals("каталог не назвал проект — название из jar", "Bee", candidate.title)
        assertEquals("b3.jar", candidate.fileName)
        assertFalse(plan.empty)
    }

    @Test
    fun `выключенный мод после обновления остаётся выключенным`() = runBlocking {
        val old = jar("c-1.jar", "c", "Cee")
        val disabled = File(mods(), "c-1.jar.disabled")
        assertTrue(old.renameTo(disabled))
        PackOriginStore.record(profileDir, PackOriginFile(path = "mods/c-1.jar.disabled", projectId = "C", versionId = "c1"))
        serve(mapOf(sha(disabled) to """{"id":"c1","project_id":"C","version_number":"1.0"}""")) { path ->
            when {
                path.startsWith("/mr/v2/project/C/version") -> MockResponse().setBody("[" + version("c2") + "]")
                path == "/mr/v2/project/C" -> MockResponse().setBody("""{"id":"C","slug":"cee","title":"Cee Mod"}""")
                path == "/files/c2.jar" -> MockResponse().setBody("new-c")
                else -> null
            }
        }

        val client = updates()
        val plan = client.plan(fabric, profileDir)
        val outcome = client.apply(profileDir, ContentKind.MOD, plan.candidates.single())

        assertTrue("обновление прошло: ${outcome.error}", outcome.ok)
        assertEquals("название берётся из каталога", "Cee Mod", outcome.candidate.title)
        assertEquals(
            "обновление — не решение включить мод: новый файл тоже выключен, старый убран",
            listOf("c2.jar.disabled"),
            modNames(),
        )
        assertTrue(
            "запись об ушедшем файле убрана, иначе «поделиться сборкой» предложит призрака",
            PackOriginStore.files(profileDir).none { it.path == "mods/c-1.jar.disabled" },
        )
    }

    @Test
    fun `пакетное обновление переживает один мёртвый файл`() = runBlocking {
        val first = jar("b-1.jar", "b", "Bee")
        val second = jar("d-1.jar", "d", "Dee")
        serve(
            mapOf(
                sha(first) to """{"id":"b1","project_id":"B","version_number":"1.0"}""",
                sha(second) to """{"id":"d1","project_id":"D","version_number":"1.0"}""",
            )
        ) { path ->
            when {
                path.startsWith("/mr/v2/project/B/version") -> MockResponse().setBody("[" + version("b2") + "]")
                path.startsWith("/mr/v2/project/D/version") -> MockResponse().setBody("[" + version("d2") + "]")
                path == "/files/d2.jar" -> MockResponse().setBody("new-d")
                else -> null
            }
        }

        val client = updates()
        val plan = client.plan(fabric, profileDir)
        val outcomes = client.applyAll(profileDir, plan)

        assertEquals("обе попытки отчитались", 2, outcomes.size)
        val broken = outcomes.single { it.candidate.projectId == "B" }
        val done = outcomes.single { it.candidate.projectId == "D" }
        assertFalse("мёртвая ссылка — провал, а не тихий успех", broken.ok)
        assertTrue("причина провала видна", broken.error.isNotBlank())
        assertTrue("второй мод обновился несмотря на первый: ${done.error}", done.ok)
        assertEquals("d2.jar", done.installedName)
        assertEquals(
            "старый b остался, d заменён",
            listOf("b-1.jar", "d2.jar"),
            modNames(),
        )
    }
}
