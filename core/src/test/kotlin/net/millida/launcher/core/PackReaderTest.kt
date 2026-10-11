package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.packs.PackReader
import net.millida.launcher.core.packs.PackSource
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PackReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val routes = HashMap<String, MockResponse>()
    private val asked = ArrayList<String>()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                synchronized(asked) { asked += path }
                return routes[path] ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun reader() = PackReader(Downloader(attempts = 1), server.url("/cf").toString().trimEnd('/'))

    private fun put(dir: File, path: String, text: String = "x"): File =
        File(dir, path).also { it.parentFile.mkdirs(); it.writeText(text) }

    private fun expectIo(why: String, block: () -> Unit): IOException {
        try {
            block()
        } catch (expected: IOException) {
            return expected
        }
        fail(why)
        throw AssertionError(why)
    }

    @Test
    fun `архив без манифеста или с битым манифестом отклоняется понятной ошибкой`() {
        val empty = tmp.newFolder("empty")
        put(empty, "mods/a.jar")
        val none = expectIo("папка без манифеста — не сборка") { runBlocking { reader().read(empty) } }
        assertTrue("игроку подсказано, какой файл выбрать", none.message.orEmpty().contains(".mrpack"))

        val broken = tmp.newFolder("broken")
        put(broken, "modrinth.index.json", "{не json")
        val unreadable = expectIo("битый индекс не должен ронять приложение") { runBlocking { reader().read(broken) } }
        assertTrue("игроку подсказано скачать заново", unreadable.message.orEmpty().contains("скачай сборку заново"))
    }

    @Test
    fun `обёртка архива в одну папку не мешает найти индекс`() {
        val unpacked = tmp.newFolder("wrapped")
        put(unpacked, "My Pack/modrinth.index.json", """{"name":"Внутри","dependencies":{"minecraft":"1.20.1"},"files":[]}""")
        val content = runBlocking { reader().read(unpacked) }
        assertEquals("индекс на уровень ниже — частый вид архива из проводника", "Внутри", content.plan.name)
        assertEquals("файл сборки с диска запоминается как файл", PackSource.FILE, content.plan.source)
    }

    @Test
    fun `mrpack - серверные файлы, хеши и имя по умолчанию`() {
        val root = tmp.newFolder("mrpack")
        put(
            root,
            "modrinth.index.json",
            """
            {"versionId":"3.2","summary":"описание",
             "dependencies":{"minecraft":"1.20.1","fabric-loader":"0.16.9","forge":""},
             "files":[
               {"path":"mods/client.jar","hashes":{"sha1":"aaaa"},"downloads":["https://cdn.modrinth.com/c.jar"],"fileSize":5,"env":{"client":"required","server":"unsupported"}},
               {"path":"mods/server-only.jar","hashes":{"sha1":"bbbb"},"downloads":["https://cdn.modrinth.com/s.jar"],"env":{"client":"unsupported","server":"required"}},
               {"path":"mods/only512.jar","hashes":{"sha512":"cccc"},"downloads":["https://cdn.modrinth.com/o.jar"]},
               {"path":"","downloads":["https://cdn.modrinth.com/e.jar"]}
             ]}
            """.trimIndent(),
        )
        put(root, "client-overrides/options.txt")

        val content = reader().readMrpack(root, nameHint = "Из файла")
        val plan = content.plan
        assertEquals("без имени в индексе берётся имя файла", "Из файла", plan.name)
        assertEquals("пустая зависимость forge не должна перебить Fabric", LoaderKind.FABRIC, plan.loader)
        assertEquals("3.2", plan.versionName)
        assertEquals("описание", plan.summary)
        assertEquals(
            "серверный мод на телефоне не нужен, строка без пути пропускается",
            listOf("mods/client.jar", "mods/only512.jar"),
            plan.files.map { it.path },
        )
        assertEquals(5L, plan.files[0].size)
        assertEquals("без sha1 проверяем по sha512, но проверяем", "cccc", plan.files[1].sha1)
        assertEquals(
            "client-overrides — тоже часть сборки, иначе пропадут клиентские настройки",
            listOf(File(root, "client-overrides").canonicalPath),
            content.trees.map { it.canonicalPath },
        )

        val unnamed = tmp.newFolder("unnamed")
        put(unnamed, "modrinth.index.json", """{"dependencies":{"minecraft":"1.20.1"}}""")
        assertEquals("сборка без имени и без подсказки всё равно названа", "Сборка", reader().readMrpack(unnamed).plan.name)
        assertEquals("без загрузчика — ваниль", LoaderKind.VANILLA, reader().readMrpack(unnamed).plan.loader)
    }

    @Test
    fun `mrpack без версии игры или без ссылки на файл не ставится`() {
        val noGame = tmp.newFolder("no-game")
        put(noGame, "modrinth.index.json", """{"dependencies":{"fabric-loader":"0.16.9"},"files":[]}""")
        expectIo("без версии Minecraft ставить не из чего") { reader().readMrpack(noGame) }

        val noLink = tmp.newFolder("no-link")
        put(noLink, "modrinth.index.json", """{"dependencies":{"minecraft":"1.20.1"},"files":[{"path":"mods/x.jar","downloads":[""]}]}""")
        val error = expectIo("файл без ссылки нельзя молча пропустить: сборка выйдет неполной") { reader().readMrpack(noLink) }
        assertTrue("ошибка называет файл", error.message.orEmpty().contains("mods/x.jar"))
    }

    @Test
    fun `архив Millida копируется целиком и помнит каталог`() {
        val root = tmp.newFolder("millida")
        put(root, "millida.pack.json", """{"title":"Аркания","game":"1.20.1","loader":"forge","loaderVersion":"47.2.0","version":"5"}""")
        val content = reader().readMillida(root)
        assertEquals("Аркания", content.plan.name)
        assertEquals(LoaderKind.FORGE, content.plan.loader)
        assertEquals("номер загрузчика из отдельного поля, если в имени его нет", "47.2.0", content.plan.loaderVersion)
        assertEquals(PackSource.MILLIDA_CATALOG, content.plan.source)
        assertEquals("сборка Millida — сама папка игры, копируется вся", listOf(root), content.trees)
        assertTrue("скачивать нечего: всё уже в архиве", content.plan.files.isEmpty())

        val joined = tmp.newFolder("millida-joined")
        put(joined, "millida.pack.json", """{"game":"1.20.1","loader":"neoforge-21.1.77","loaderVersion":"1"}""")
        val plan = reader().readMillida(joined, nameHint = "Подсказка").plan
        assertEquals("номер в имени загрузчика важнее отдельного поля", "21.1.77", plan.loaderVersion)
        assertEquals("Подсказка", plan.name)

        val noGame = tmp.newFolder("millida-no-game")
        put(noGame, "millida.pack.json", """{"title":"x"}""")
        expectIo("без версии игры сборка не ставится") { reader().readMillida(noGame) }
    }

    private fun cfFile(fileName: String, url: String, sha1: String?, size: Long): MockResponse {
        val hashes = if (sha1 == null) "[]" else """[{"algo":2,"value":"md5md5"},{"algo":1,"value":"$sha1"}]"""
        return MockResponse().setBody("""{"data":{"fileName":"$fileName","downloadUrl":"$url","hashes":$hashes,"fileLength":$size}}""")
    }

    private fun cfManifest(files: String, extra: String = ""): File {
        val root = tmp.newFolder("cf-" + System.nanoTime())
        put(
            root,
            "manifest.json",
            """
            {"name":"Сборка CF","version":"1.4",$extra
             "minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.2.0","primary":false},{"id":"neoforge-47.1.0","primary":true}]},
             "files":[$files]}
            """.trimIndent(),
        )
        return root
    }

    @Test
    fun `CurseForge - файлы спрашиваются у нашего сервера и сводятся к одному виду`() {
        routes["/cf/v1/mods/1/files/10"] = cfFile("jei.jar", "https://edge.forgecdn.net/jei.jar", "f".repeat(40), 99)
        routes["/cf/v1/mods/2/files/20"] = cfFile("../../evil.jar", "", null, 0)
        routes["/cf/v1/mods/2/files/20/download-url"] = MockResponse().setBody("""{"data":"https://edge.forgecdn.net/evil.jar"}""")
        val root = cfManifest(
            """
            {"projectID":1,"fileID":10,"required":true},
            {"projectID":2,"fileID":20},
            {"projectID":3,"fileID":30,"required":false},
            {"fileID":40}
            """.trimIndent(),
            extra = """"overrides":"custom",""",
        )
        put(root, "custom/config/a.toml")

        val content = runBlocking { reader().readCurseForge(root) }
        val plan = content.plan
        assertEquals("главный загрузчик помечен primary, а не первый в списке", LoaderKind.NEOFORGE, plan.loader)
        assertEquals("47.1.0", plan.loaderVersion)
        assertEquals(PackSource.CURSEFORGE, plan.source)
        assertEquals("1.4", plan.versionName)
        assertEquals("необязательный файл, которого нет, пропускается; строка без проекта тоже", 2, plan.files.size)

        val jei = plan.files[0]
        assertEquals("mods/jei.jar", jei.path)
        assertEquals("хеш берётся sha1 (algo 1), а не md5", "f".repeat(40), jei.sha1)
        assertEquals(99L, jei.size)
        assertEquals("1", jei.projectId)
        assertEquals("10", jei.versionId)

        val evil = plan.files[1]
        assertTrue("имя файла от CurseForge не уводит из mods: ${evil.path}", evil.path.startsWith("mods/") && evil.path.count { it == '/' } == 1)
        assertEquals("без прямой ссылки берётся download-url", listOf("https://edge.forgecdn.net/evil.jar"), evil.urls)
        assertNull("без sha1 хеш пустой, а не md5", evil.sha1)

        assertEquals(
            "папка overrides, названная манифестом, копируется в сборку",
            listOf(File(root, "custom").canonicalPath),
            content.trees.map { it.canonicalPath },
        )
        assertTrue("файлы спрашиваются у нашего прокси CurseForge", asked.any { it == "/cf/v1/mods/1/files/10" })
    }

    @Test
    fun `CurseForge - обязательный файл без ответа или с запретом скачивания останавливает установку`() {
        val missing = cfManifest("""{"projectID":5,"fileID":50}""")
        val absent = expectIo("сборка без обязательного мода не запустится") { runBlocking { reader().readCurseForge(missing) } }
        assertTrue("ошибка называет номер файла", absent.message.orEmpty().contains("#50"))

        routes["/cf/v1/mods/6/files/60"] = cfFile("locked.jar", "", null, 0)
        routes["/cf/v1/mods/6/files/60/download-url"] = MockResponse().setBody("""{"data":null}""")
        val locked = expectIo("файл с запретом скачивания нельзя пропустить молча") {
            runBlocking { reader().readCurseForge(cfManifest("""{"projectID":6,"fileID":60}""")) }
        }
        assertTrue("игроку сказано поставить файл вручную", locked.message.orEmpty().contains("вручную"))

        routes["/cf/v1/mods/7/files/70"] = cfFile("", "https://edge.forgecdn.net/x.jar", null, 0)
        val nameless = expectIo("файл без имени некуда положить") {
            runBlocking { reader().readCurseForge(cfManifest("""{"projectID":7,"fileID":70}""")) }
        }
        assertTrue(nameless.message.orEmpty().contains("#70"))
    }

    @Test
    fun `CurseForge - папка overrides из манифеста не уводит за пределы архива`() {
        val root = cfManifest("", extra = """"overrides":"../../outside",""")
        expectIo("имя папки выбрал автор архива — выход из архива скопировал бы чужие файлы") {
            runBlocking { reader().readCurseForge(root) }
        }
    }

    @Test
    fun `CurseForge - манифест без раздела minecraft или без версии игры`() {
        val noSection = tmp.newFolder("cf-no-section")
        put(noSection, "manifest.json", """{"name":"x","files":[]}""")
        expectIo("без раздела minecraft архив повреждён") { runBlocking { reader().readCurseForge(noSection) } }

        val noVersion = tmp.newFolder("cf-no-version")
        put(noVersion, "manifest.json", """{"minecraft":{"version":"","modLoaders":[]},"files":[]}""")
        expectIo("без версии игры ставить не из чего") { runBlocking { reader().readCurseForge(noVersion) } }
    }
}
