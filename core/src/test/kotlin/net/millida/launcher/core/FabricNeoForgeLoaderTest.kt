package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.loader.LoaderMirror
import net.millida.launcher.core.loader.LoaderVersion
import net.millida.launcher.core.loader.MetaLoaderInstaller
import net.millida.launcher.core.loader.NeoForgeVersions
import net.millida.launcher.core.loader.neoForgeBuilds
import net.millida.launcher.core.loader.neoForgePrefixes
import net.millida.launcher.core.mojang.MojangJson
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class FabricNeoForgeLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val routes = HashMap<String, String>()
    private val asked = ArrayList<String>()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                synchronized(asked) { asked += path }
                val body = routes[path] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(body)
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun layout(): GameLayout = GameLayout(tmp.newFolder("game-" + System.nanoTime())).also { it.ensure() }

    private fun meta(kind: LoaderKind, layout: GameLayout) =
        MetaLoaderInstaller(kind, layout, Downloader(attempts = 1), server.url("/meta").toString().trimEnd('/'))

    @Test
    fun `идентификаторы загрузчиков не меняются`() {
        assertEquals(
            "идентификаторы записаны в сборках, манифестах и карточках каталога — переименование ломает чтение старых данных",
            listOf("vanilla", "fabric", "quilt", "forge", "neoforge"),
            LoaderKind.entries.map { it.id },
        )
    }

    @Test
    fun `список сборок Fabric читается, нестабильность без флага по умолчанию`() {
        routes["/meta/versions/loader/1.20.1"] = """
            [
              {"loader":{"separator":".","build":9,"maven":"net.fabricmc:fabric-loader:0.16.9","version":"0.16.9","stable":true},
               "intermediary":{"version":"1.20.1"}},
              {"loader":{"version":"0.17.0-beta.1","stable":false}},
              {"loader":{"version":"0.15.0"}}
            ]
        """.trimIndent()
        val versions = runBlocking { meta(LoaderKind.FABRIC, layout()).versions("1.20.1") }
        assertEquals(
            "неизвестные поля мета-сервера не мешают; запись без stable не выдаётся за стабильную",
            listOf(LoaderVersion("0.16.9", true), LoaderVersion("0.17.0-beta.1", false), LoaderVersion("0.15.0", false)),
            versions,
        )
    }

    @Test
    fun `установка Fabric и Quilt пишет профиль под нашим именем`() {
        val profile = """{"id":"fabric-loader-0.16.9-1.20.1","inheritsFrom":"1.20.1","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient","libraries":[]}"""
        routes["/meta/versions/loader/1.20.1/0.16.9/profile/json"] = profile
        val layout = layout()
        val id = runBlocking { meta(LoaderKind.FABRIC, layout).install("1.20.1", "0.16.9") }
        assertEquals("имя версии: игра-загрузчик-номер, его ждут профили", "1.20.1-fabric-0.16.9", id)

        val written = MojangJson.parseToJsonElement(layout.versionJson(id).readText()).jsonObject
        assertEquals("id внутри файла совпадает с именем папки, иначе резолвер не найдёт версию", id, written["id"]!!.jsonPrimitive.content)
        assertEquals("остальное описание сохраняется", "1.20.1", written["inheritsFrom"]!!.jsonPrimitive.content)
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", written["mainClass"]!!.jsonPrimitive.content)

        routes["/meta/versions/loader/1.20.1/0.26.0/profile/json"] = profile
        val quilt = runBlocking { meta(LoaderKind.QUILT, layout).install("1.20.1", "0.26.0") }
        assertEquals("Quilt называется своим идентификатором, а не fabric", "1.20.1-quilt-0.26.0", quilt)
        assertTrue(layout.versionJson(quilt).isFile)
    }

    @Test
    fun `неизвестная сборка загрузчика — ошибка, а не пустой профиль`() {
        val layout = layout()
        try {
            runBlocking { meta(LoaderKind.FABRIC, layout).install("1.20.1", "9.9.9") }
            fail("без ответа мета-сервера версия не создаётся")
        } catch (expected: IOException) {
            assertFalse("пустой профиль не записан", layout.versionJson("1.20.1-fabric-9.9.9").exists())
        }
    }

    private data class PrefixCase(val game: String, val prefixes: List<String>, val why: String)

    @Test
    fun `ветка NeoForge по версии игры`() {
        val cases = listOf(
            PrefixCase("1.21.1", listOf("21.1."), "1.A.B превращается в A.B."),
            PrefixCase("1.21", listOf("21.0."), "версия без патча — ветка A.0."),
            PrefixCase("1.20.4", listOf("20.4."), "старые ветки по тому же правилу"),
            PrefixCase("26.2", listOf("26.2.0.", "26.2."), "номера по годам получают патч-элемент, длинный префикс первым"),
            PrefixCase("26.1.2", listOf("26.1.2."), "патч-версия по годам — своя ветка"),
        )
        cases.forEach { case -> assertEquals("${case.game}: ${case.why}", case.prefixes, neoForgePrefixes(case.game)) }
    }

    @Test
    fun `странный ответ со списком NeoForge даёт пустой список`() {
        val cases = listOf(
            "[]" to "массив вместо объекта",
            """{"versions":"21.1.5"}""" to "строка вместо списка",
            """{"other":["21.1.5"]}""" to "нет поля versions",
        )
        cases.forEach { (body, why) -> assertEquals(why, emptyList<String>(), neoForgeBuilds(body, "1.21.1")) }
        assertEquals(
            "null и объекты в списке пропускаются, строки остаются",
            listOf("21.1.5"),
            neoForgeBuilds("""{"versions":[null,{"a":1},"21.1.5"]}""", "1.21.1"),
        )
    }

    @Test
    fun `сборки NeoForge помечаются стабильными и ведут на свой установщик`() {
        routes["/neo"] = """{"versions":["21.1.5","21.1.6-beta"]}"""
        val source = NeoForgeVersions(
            LoaderMirror(Downloader(attempts = 1)),
            versionsUrl = server.url("/neo").toString(),
            mavenBase = "https://maven.neoforged.net/releases/net/neoforged/neoforge",
        )
        assertEquals(
            "бета не выдаётся за стабильную сборку",
            listOf(LoaderVersion("21.1.6-beta", false), LoaderVersion("21.1.5", true)),
            runBlocking { source.builds("1.21.1") },
        )
        val installer = source.installers("1.21.1", "21.1.5").single()
        assertEquals(
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.5/neoforge-21.1.5-installer.jar",
            installer.url,
        )
        assertEquals("папка версии NeoForge не зависит от версии игры", "neoforge-21.1.5", installer.versionId)
    }
}
