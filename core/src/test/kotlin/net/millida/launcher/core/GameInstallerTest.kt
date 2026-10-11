package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.install.GameInstaller
import net.millida.launcher.core.install.InstallStage
import net.millida.launcher.core.mojang.VersionResolver
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
import java.io.File
import java.io.IOException

class GameInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var layout: GameLayout
    private val served = HashMap<String, ByteArray>()

    private val clientBytes = "клиент игры".toByteArray()
    private val libraryBytes = "библиотека".toByteArray()
    private val logBytes = "<Configuration/>".toByteArray()
    private val soundBytes = "звук".toByteArray()

    @Before
    fun start() {
        served["/manifest.json"] = """{"versions":[]}""".toByteArray()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = served[request.path.orEmpty()] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(okio.Buffer().write(body))
            }
        }
        server.start()
        layout = GameLayout(tmp.newFolder("game")).also { it.ensure() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun url(path: String): String = server.url(path).toString()

    private fun installer(): GameInstaller {
        val downloader = Downloader(attempts = 1)
        return GameInstaller(layout, downloader, VersionResolver(layout, downloader, manifestUrl = url("/manifest.json")))
    }

    private fun writeVersion(id: String, json: String) {
        layout.versionJson(id).also { it.parentFile.mkdirs(); it.writeText(json) }
    }

    private fun assetIndex(objectName: String, virtual: Boolean): String {
        val hash = Hashing.sha1(soundBytes)
        layout.assetObject(hash).also { it.parentFile.mkdirs(); it.writeBytes(soundBytes) }
        val body = """{"virtual":$virtual,"objects":{"$objectName":{"hash":"$hash","size":${soundBytes.size}}}}"""
        served["/indexes/legacy.json"] = body.toByteArray()
        return """"assetIndex":{"id":"legacy","sha1":"${Hashing.sha1(body.toByteArray())}","url":"${url("/indexes/legacy.json")}"}"""
    }

    private fun versionJson(
        clientSha1: String = Hashing.sha1(clientBytes),
        log4j: String = "2.8.1",
        loggingSha1: String? = Hashing.sha1(logBytes),
        assets: String = assetIndex("sounds/step.ogg", virtual = true),
    ): String {
        served["/client.jar"] = clientBytes
        served["/lib.jar"] = libraryBytes
        served["/log.xml"] = logBytes
        val loggingHash = loggingSha1?.let { """"sha1":"$it",""" }.orEmpty()
        return """
            {"id":"1.6.4","assets":"legacy",$assets,
             "downloads":{
               "client":{"sha1":"$clientSha1","url":"${url("/client.jar")}"},
               "client_mappings":{"sha1":"${"e".repeat(40)}","size":3,"url":"${url("/mappings.txt")}"}
             },
             "libraries":[
               {"name":"org.apache.logging.log4j:log4j-core:$log4j",
                "downloads":{"artifact":{"path":"org/apache/log4j-core.jar","sha1":"${Hashing.sha1(libraryBytes)}","url":"${url("/lib.jar")}"}}}
             ],
             "logging":{"client":{"argument":"-Dlog4j.configurationFile=${'$'}{path}","type":"log4j2-xml",
               "file":{"id":"client-1.6.xml",$loggingHash"size":${logBytes.size},"url":"${url("/log.xml")}"}}}}
        """.trimIndent()
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
    fun `установка кладёт клиент, библиотеки, журнал и ассеты и сообщает этапы по порядку`() {
        writeVersion("1.6.4", versionJson())
        val stages = ArrayList<InstallStage>()
        val version = runBlocking { installer().install("1.6.4") { synchronized(stages) { stages += it } } }

        assertTrue("клиент скачан", layout.versionJar("1.6.4").isFile)
        assertTrue("библиотека скачана по пути из описания", layout.library("org/apache/log4j-core.jar").isFile)
        assertTrue("старому log4j нужна своя настройка журнала (CVE-2021-44228)", layout.logConfig("client-1.6.xml").isFile)
        assertEquals(
            "старые версии читают ассеты по именам — файл обязан лежать в virtual",
            "звук",
            File(File(File(layout.assets, "virtual"), "legacy"), "sounds/step.ogg").readText(),
        )

        assertEquals("первый этап называет версию", InstallStage.Meta("1.6.4"), stages.first())
        assertEquals("последний этап — готово", InstallStage.Done, stages.last())
        val order = listOf(
            stages.indexOfFirst { it is InstallStage.Client },
            stages.indexOfFirst { it is InstallStage.Libraries },
            stages.indexOfFirst { it is InstallStage.Assets },
        )
        assertTrue("каждый этап сообщён: $order", order.all { it > 0 })
        assertEquals("этапы идут в порядке клиент → библиотеки → ассеты", order.sorted(), order)

        assertEquals("после установки проверка ничего не недосчитывается", emptyList<String>(), installer().verify(version))
    }

    @Test
    fun `проверка называет то, чего не хватает`() {
        writeVersion("1.6.4", versionJson())
        val version = runBlocking { installer().install("1.6.4") }
        layout.library("org/apache/log4j-core.jar").delete()
        layout.versionJar("1.6.4").delete()
        assertEquals(
            "игроку названы клиент и библиотека, которые надо докачать",
            listOf("клиент 1.6.4.jar", "org.apache.logging.log4j:log4j-core:2.8.1"),
            installer().verify(version),
        )
    }

    @Test
    fun `подменённый клиент не принимается`() {
        writeVersion("1.6.4", versionJson(clientSha1 = "0".repeat(40)))
        expectIo("клиент с чужим хешем — не тот файл, что выпустила Mojang") { runBlocking { installer().install("1.6.4") } }
        assertFalse("файл с неверным хешем не остаётся на месте клиента", layout.versionJar("1.6.4").exists())
    }

    @Test
    fun `настройка журнала без хеша не скачивается`() {
        writeVersion("1.6.4", versionJson(loggingSha1 = null))
        val error = expectIo("файл, который решает, что исполняет log4j, без хеша не берём") { runBlocking { installer().install("1.6.4") } }
        assertTrue("ошибка называет файл", error.message.orEmpty().contains("client-1.6.xml"))
    }

    @Test
    fun `новый log4j не тянет настройку журнала`() {
        writeVersion("1.6.4", versionJson(log4j = "2.17.1"))
        runBlocking { installer().install("1.6.4") }
        assertFalse("для log4j 2.17 настройка не нужна и не скачивается", layout.logConfig("client-1.6.xml").exists())
    }

    @Test
    fun `имя ассета из индекса не выводит за папку virtual`() {
        writeVersion("1.6.4", versionJson(assets = assetIndex("../../../escape.txt", virtual = true)))
        expectIo("имя ассета пришло из сети и не должно писать за пределы папки") { runBlocking { installer().install("1.6.4") } }
        assertFalse(File(layout.root, "escape.txt").exists())
    }

    @Test
    fun `описание без клиента — понятная ошибка, клиент отдельно и карта имён`() {
        writeVersion("bare", """{"id":"bare"}""")
        expectIo("версию без клиента нечем запускать") { runBlocking { installer().install("bare") } }
        expectIo("клиент для установщика Forge тоже не из чего взять") { runBlocking { installer().clientJar("bare") } }

        writeVersion("1.6.4", versionJson())
        val jar = runBlocking { installer().clientJar("1.6.4") }
        assertEquals("клиент для установщика Forge лежит там же, где у игры", layout.versionJar("1.6.4"), jar)
        assertTrue(jar.isFile)

        val mappings = runBlocking { installer().clientMappings("1.6.4") }
        assertEquals("карта имён нужна процессорам Forge", url("/mappings.txt"), mappings?.url)
        assertEquals("без карты имён — null, а не ошибка", null, runBlocking { installer().clientMappings("bare") })
    }
}
