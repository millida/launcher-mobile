package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.content.CatalogLookup
import net.millida.launcher.core.content.LocalContentImport
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalContentImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var layout: GameLayout
    private lateinit var profileDir: File
    private lateinit var incoming: File

    private val classMagic = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())

    private val fabric = Profile(id = "build", name = "Сборка", gameVersion = "1.20.1", loader = LoaderKind.FABRIC)

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        layout = GameLayout(tmp.newFolder("store")).also { it.ensure() }
        profileDir = layout.profileDir("build").also { it.mkdirs() }
        incoming = tmp.newFolder("incoming")
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun archive(name: String, entries: List<Pair<String, ByteArray>>): File {
        val target = File(incoming, name)
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (path, body) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return target
    }

    private fun fabricJar(name: String, classBody: ByteArray = classMagic): File = archive(
        name,
        listOf(
            "fabric.mod.json" to """{"id":"m","name":"M","depends":{"minecraft":">=1.20"}}""".toByteArray(),
            "m/Main.class" to classBody,
        ),
    )

    private fun importer(lookup: CatalogLookup? = null) = LocalContentImport(layout, lookup)

    @Test
    fun `паки раскладываются по своим папкам без лишних вопросов`() = runBlocking {
        data class Case(val file: File, val kind: ContentKind, val why: String)

        val cases = listOf(
            Case(
                archive("faithful.zip", listOf("pack.mcmeta" to "{}".toByteArray(), "assets/a.png" to byteArrayOf(1))),
                ContentKind.RESOURCE_PACK,
                "ресурспак в папку модов игра не увидит",
            ),
            Case(
                archive("bsl.zip", listOf("shaders/final.fsh" to "void main(){}".toByteArray())),
                ContentKind.SHADER,
                "шейдер нужен в shaderpacks, иначе Iris его не найдёт",
            ),
            Case(
                archive("dp.zip", listOf("pack.mcmeta" to "{}".toByteArray(), "data/x/a.json" to "{}".toByteArray())),
                ContentKind.DATA_PACK,
                "датапак отличается от ресурспака папкой data",
            ),
        )

        val import = importer()
        for (case in cases) {
            val candidate = import.identify(case.file, fabric)
            assertTrue("${case.file.name} принимается", candidate.accepted)
            assertEquals("${case.file.name}: ${case.why}", case.kind, candidate.kind)
            assertFalse("${case.file.name}: паку согласие не нужно", candidate.needsConsent)
            val added = import.add(profileDir, candidate)
            assertEquals("${case.file.name} лёг в ${case.kind.folder}", File(profileDir, case.kind.folder).canonicalPath, added.parentFile.canonicalPath)
            assertTrue(added.isFile)
        }
    }

    @Test
    fun `непонятный архив отклоняется с причиной`() = runBlocking {
        val readme = archive("readme.zip", listOf("readme.txt" to "hello".toByteArray()))

        val candidate = importer().identify(readme, fabric)

        assertFalse("архив без признаков мода или пака не ставится", candidate.accepted)
        assertEquals(
            "Файл readme.zip не годится для сборки: не удалось понять, что это за файл",
            candidate.rejection,
        )
        val refused = runCatching { importer().add(profileDir, candidate, consent = true) }
        assertTrue("согласие не обходит отказ", refused.exceptionOrNull() is IOException)
    }

    @Test
    fun `подозрительный мод ставится только с согласия`() = runBlocking {
        val jar = fabricJar("loader.jar", classMagic + "defineClass pastebin.com".toByteArray())

        val candidate = importer().identify(jar, fabric)

        assertTrue("файл опознан", candidate.accepted)
        assertTrue("два сильных признака требуют согласия", candidate.needsConsent)
        assertTrue(
            "предупреждение называет признаки: ${candidate.warnings}",
            candidate.warnings.single().contains("грузит классы в обход загрузчика модов"),
        )
        val refused = runCatching { importer().add(profileDir, candidate) }
        assertEquals(
            "отказ без согласия показывает то же предупреждение",
            candidate.warnings.first(),
            refused.exceptionOrNull()?.message,
        )
        assertFalse("без согласия в сборке ничего не появилось", File(profileDir, "mods/loader.jar").exists())
    }

    @Test
    fun `Fabric-мод в сборке Quilt ставится молча`() = runBlocking {
        val jar = fabricJar("fapi.jar")
        val quilt = fabric.copy(loader = LoaderKind.QUILT)

        val candidate = importer().identify(jar, quilt)

        assertFalse("Quilt грузит Fabric-моды — лишний вопрос приучает жать «да» не читая", candidate.needsConsent)
        assertTrue(candidate.warnings.isEmpty())
    }

    @Test
    fun `имя файла чистится перед копированием`() = runBlocking {
        val jar = fabricJar("my mod.jar")

        val candidate = importer().identify(jar, fabric)
        val added = importer().add(profileDir, candidate)

        assertEquals("пробел в имени заменяется", "my_mod.jar", candidate.fileName)
        assertEquals("файл лёг под чистым именем", "my_mod.jar", added.name)
        assertEquals("копия совпадает с исходником", Hashing.sha1(jar), Hashing.sha1(added))

        val again = importer().add(profileDir, candidate)
        assertEquals("повторное добавление того же файла не ломается", added.canonicalPath, again.canonicalPath)
    }

    @Test
    fun `ручной выбор папки и пропавший исходник`() = runBlocking {
        val pack = archive("pack.zip", listOf("pack.mcmeta" to "{}".toByteArray()))
        val import = importer()
        val candidate = import.identify(pack, fabric)

        val overridden = import.add(profileDir, candidate, kindOverride = ContentKind.SHADER)
        assertEquals("игрок сам выбрал папку шейдеров", "shaderpacks", overridden.parentFile.name)

        assertTrue(pack.delete())
        val gone = runCatching { import.add(profileDir, candidate) }
        assertEquals(
            "исчезнувший файл называется по имени",
            "файл pack.zip пропал",
            gone.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `в сборку вне хранилища ничего не копируется`() = runBlocking {
        val jar = fabricJar("ok.jar")
        val outside = tmp.newFolder("outside")
        val import = importer()

        val result = runCatching { import.add(outside, import.identify(jar, fabric)) }

        assertTrue("папка вне хранилища отклоняется", result.exceptionOrNull() is IOException)
        assertFalse("в чужой папке не появилось mods", File(outside, "mods").exists())
    }

    @Test
    fun `название из каталога подтягивается по хешу`() = runBlocking {
        val jar = fabricJar("sodium.jar")
        val sha1 = Hashing.sha1(jar).lowercase()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/mr/v2/version_file/$sha1?algorithm=sha1" ->
                        MockResponse().setBody("""{"id":"V1","project_id":"P1","version_number":"0.5.0"}""")
                    path == "/mr/v2/project/P1" ->
                        MockResponse().setBody("""{"id":"P1","slug":"sodium","title":"Sodium"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val lookup = CatalogLookup(Downloader(attempts = 1), server.url("/mr/v2").toString().trimEnd('/'))

        assertEquals("Sodium", importer(lookup).catalogTitle(jar))
        assertNull("без каталога названия нет, и это не ошибка", importer().catalogTitle(jar))
        assertNull("файл, которого каталог не знает, остаётся без названия", importer(lookup).catalogTitle(fabricJar("other.jar", classMagic + byteArrayOf(9))))
    }
}
