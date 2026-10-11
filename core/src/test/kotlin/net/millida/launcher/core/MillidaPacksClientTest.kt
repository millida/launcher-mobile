package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.packs.MillidaPacks
import net.millida.launcher.core.packs.PackHostPolicy
import net.millida.launcher.core.packs.PackManifest
import net.millida.launcher.core.packs.PackManifestFile
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class MillidaPacksClientTest {

    private lateinit var server: MockWebServer
    private val routes = HashMap<String, MockResponse>()
    private val asked = ArrayList<String>()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                synchronized(asked) { asked += "${request.method} $path" }
                return routes[path] ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun packs() = MillidaPacks(
        Downloader(attempts = 1),
        null,
        server.url("/v2").toString().trimEnd('/'),
        PackHostPolicy(),
    )

    private fun answer(path: String, body: String) {
        routes[path] = MockResponse().setBody(body)
    }

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
    fun `карточка сборки каталога читается вместе с клиентской частью`() {
        answer(
            "/v2/catalog/packs/arcania",
            """
            {"title":"Аркания","summary":"магия","cover":"https://cdn.millida.net/c.webp","game":"1.20.1",
             "loader":"forge","loaderVersion":"47.2.0","version":"5","accessRequired":true,
             "accessBuyUrl":"  https://millida.net/buy/arcania  ","ownLaunch":true,
             "files":[{"side":"server","id":"srv","size":1},{"side":"client","id":"cli-1","size":123456,"sha512":"abc"}]}
            """.trimIndent(),
        )
        val card = runBlocking { packs().catalogPack("arcania") }
        assertEquals("Аркания", card.title)
        assertEquals("1.20.1", card.gameVersion)
        assertEquals("forge", card.loader)
        assertEquals("47.2.0", card.loaderVersion)
        assertEquals("скачивается клиентская часть, а не серверная", "cli-1", card.clientFileId)
        assertEquals(123456L, card.sizeBytes)
        assertEquals("abc", card.sha512)
        assertTrue("платная сборка помечена до скачивания", card.accessRequired)
        assertEquals("ссылка покупки чистится от пробелов", "https://millida.net/buy/arcania", card.accessBuyUrl)
        assertTrue("свой запуск виден до скачивания", card.ownLaunch)
        assertEquals("карточка для проверки телефона собрана из тех же полей", card.ownLaunch, card.phoneCard.ownLaunch)
    }

    @Test
    fun `карточка без клиента, без имени и с небезопасной ссылкой покупки`() {
        answer("/v2/catalog/packs/server-only", """{"title":"Только сервер","files":[{"side":"server","id":"s"}]}""")
        val serverOnly = expectIo("сборку только для сервера на телефон ставить нечем") { runBlocking { packs().catalogPack("server-only") } }
        assertTrue(serverOnly.message.orEmpty().contains("только на сервер"))

        answer("/v2/catalog/packs/bare", """{"accessBuyUrl":"http://millida.net/buy","files":[{"side":"client","id":"c"}]}""")
        val bare = runBlocking { packs().catalogPack("bare") }
        assertEquals("без названия карточка называется адресом", "bare", bare.title)
        assertEquals("ссылка без https не открывается из лаунчера", "", bare.accessBuyUrl)
        assertFalse("без флага сборка обычная", bare.accessRequired)
    }

    @Test
    fun `кривой адрес сборки не уходит в запрос`() {
        listOf("../admin", "Arcania", "", "a/b").forEach { slug ->
            expectIo("«$slug» стал бы сегментом пути запроса") { runBlocking { packs().catalogPack(slug) } }
            expectIo("«$slug» в запросе доступа") { runBlocking { packs().access(slug) } }
            expectIo("«$slug» в запросе ключа запуска") { runBlocking { packs().launchToken(slug) } }
        }
        assertEquals("ни один кривой адрес не дошёл до сервера", 0, server.requestCount)
    }

    @Test
    fun `сборка по коду читается, плохие строки манифеста отброшены`() {
        val good = "https://cdn.modrinth.com/data/AAA/versions/1/sodium.jar"
        answer(
            "/v2/launcher/packs/AB23CD45",
            """
            {"author":"Steve","installs":42,"updatedAt":"2026-10-01",
             "manifest":{"formatVersion":1,"name":"Сборка друга","game":"1.20.1","loader":"fabric",
               "files":[
                 {"path":"mods/sodium.jar","url":"$good","sha1":"${"a".repeat(40)}","size":1000},
                 {"path":"mods/evil.jar","url":"https://evil.example/e.jar","sha1":"${"b".repeat(40)}","size":5000}
               ]}}
            """.trimIndent(),
        )
        val (view, manifest) = runBlocking { packs().sharedPack("ab23-cd45") }
        assertEquals("код приводится к виду, в котором его выдал сервер", "AB23CD45", view.code)
        assertEquals("Steve", view.author)
        assertEquals(42L, view.installs)
        assertEquals("в окне видны только файлы, которые реально поставятся", 1, view.files)
        assertEquals("размер считается по тем же файлам", 1000L, view.sizeBytes)
        assertEquals(listOf("mods/sodium.jar"), manifest.files.map { it.path })
        assertTrue("запрос ушёл по нормализованному коду", asked.contains("GET /v2/launcher/packs/AB23CD45"))
    }

    @Test
    fun `кривой код и ответ без манифеста`() {
        expectIo("код из семи знаков не бывает") { runBlocking { packs().sharedPack("AB23CD4") } }
        assertEquals("кривой код не уходит в сеть", 0, server.requestCount)

        answer("/v2/launcher/packs/AB23CD45", """{"author":"Steve"}""")
        val error = expectIo("ответ без манифеста — не сборка") { runBlocking { packs().sharedPack("AB23CD45") } }
        assertTrue("игроку сказано обновить лаунчер", error.message.orEmpty().contains("обнови лаунчер"))
    }

    private val versionsBody = """
        [
          {"id":"v3","name":"3.0","version_number":"3.0.0","version_type":"beta","date_published":"2026-10-01",
           "files":[{"filename":"pack.zip","url":"https://cdn.modrinth.com/z.zip"}]},
          {"id":"v2","name":"2.0","version_number":"2.0.0","version_type":"release","changelog":"исправления",
           "files":[{"filename":"pack-2.0.mrpack","url":"https://cdn.modrinth.com/v2.mrpack"}]},
          {"id":"v1","name":"1.0","files":[{"filename":"pack-1.0.mrpack","url":""}]}
        ]
    """.trimIndent()

    @Test
    fun `версии сборки Modrinth — только с файлом mrpack`() {
        answer("/v2/launcher/mr/v2/project/fabulously-optimized/version", versionsBody)
        val versions = runBlocking { packs().modpackVersions("fabulously-optimized") }
        assertEquals(
            "версия без .mrpack или с пустой ссылкой не предлагается: ставить её нечем",
            listOf("v2"),
            versions.map { it.id },
        )
        assertEquals("2.0.0", versions.single().versionNumber)
        assertEquals("release", versions.single().type)
        assertEquals("исправления", versions.single().changelog)
    }

    @Test
    fun `файл версии Modrinth по выбору игрока`() {
        routes["/v2/launcher/mr/v2/project/fo/version"] = MockResponse().setBody(versionsBody)
        val (latest, url) = runBlocking { packs().modpackFile("fo", null) }
        assertEquals("без выбора берётся первая версия, где есть .mrpack", "v2", latest.id)
        assertEquals("https://cdn.modrinth.com/v2.mrpack", url)

        routes["/v2/launcher/mr/v2/project/fo/version"] = MockResponse().setBody(versionsBody)
        expectIo("версия без .mrpack по прямому выбору не ставится") { runBlocking { packs().modpackFile("fo", "v3") } }
        routes["/v2/launcher/mr/v2/project/fo/version"] = MockResponse().setBody(versionsBody)
        val missing = expectIo("неизвестная версия — ошибка, а не другая версия") { runBlocking { packs().modpackFile("fo", "v9") } }
        assertTrue("игроку сказано обновить список", missing.message.orEmpty().contains("обнови список"))
    }

    @Test
    fun `файл узнаётся по хешу, а сбой поиска даёт просто «не узнан»`() {
        val sha1 = "ABCDEF0123".repeat(4)
        answer(
            "/v2/launcher/mr/v2/version_file/$sha1?algorithm=sha1",
            """
            {"id":"ver1","project_id":"proj1","files":[
              {"hashes":{"sha1":"${"0".repeat(40)}"},"url":"https://cdn.modrinth.com/other.jar","size":1},
              {"hashes":{"sha1":"${sha1.lowercase()}","sha512":"s512"},"url":"https://cdn.modrinth.com/mine.jar","size":77}
            ]}
            """.trimIndent(),
        )
        val found = runBlocking { packs().versionByHash(sha1) }
        assertEquals(
            "из версии берётся именно тот файл, чей хеш совпал, регистр хеша не важен",
            PackManifestFile(path = "", kind = "mod", projectId = "proj1", versionId = "ver1", sha1 = sha1, sha512 = "s512", size = 77, url = "https://cdn.modrinth.com/mine.jar"),
            found,
        )

        assertNull("короткий хеш не ищется", runBlocking { packs().versionByHash("abc") })
        assertNull("неизвестный файл — просто не узнан", runBlocking { packs().versionByHash("1".repeat(40)) })
        answer("/v2/launcher/mr/v2/version_file/${"2".repeat(40)}?algorithm=sha1", "<html>")
        assertNull("мусор в ответе — тоже не узнан", runBlocking { packs().versionByHash("2".repeat(40)) })
        assertFalse("короткий хеш не дошёл до сервера", asked.any { it.contains("/version_file/abc") })
    }

    @Test
    fun `без входа в аккаунт личные действия объясняют, что нужно войти`() {
        val api = packs()
        assertFalse(api.signedIn())
        val manifest = PackManifest(name = "x", game = "1.20.1")
        val actions = listOf<Pair<String, () -> Unit>>(
            "мои коды" to fun() { runBlocking { api.myPacks() } },
            "публикация" to fun() { runBlocking { api.publish(manifest) } },
            "снятие кода" to fun() { runBlocking { api.unshare("AB23CD45") } },
            "ссылка на платную сборку" to fun() { runBlocking { api.catalogPackLink("file-1") } },
            "оглавление архива" to fun() { runBlocking { api.catalogPackIndex("file-1") } },
            "доступ к сборке" to fun() { runBlocking { api.access("arcania") } },
        )
        actions.forEach { (name, action) ->
            val error = expectIo("$name без аккаунта невозможно") { action() }
            assertTrue("$name: игроку сказано войти, а не показан код ошибки", error.message.orEmpty().contains("войди в аккаунт"))
        }
        assertEquals("кривой номер файла не спрашивается вовсе", null, runBlocking { api.catalogPackIndex("../x") })
        assertFalse("без аккаунта установка не засчитывается и не падает", runBlocking { api.reportInstalled("AB23CD45") })
        assertEquals("ни одно личное действие не ушло в сеть без аккаунта", 0, server.requestCount)
    }
}
