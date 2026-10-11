package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.mojang.AssetIndex
import net.millida.launcher.core.mojang.MOJANG_LIBRARIES_URL
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.mojang.VersionManifest
import net.millida.launcher.core.mojang.VersionMeta
import net.millida.launcher.core.mojang.VersionResolver
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MojangModelsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Volatile
    private var manifestAnswer: () -> MockResponse = { MockResponse().setBody("""{"versions":[]}""") }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = manifestAnswer()
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private val manifestBody = """
        {"latest":{"release":"1.21.4","snapshot":null},
         "versions":[
           {"id":"25w01a","type":"snapshot","url":"https://piston-meta.mojang.com/25w01a.json","unknownField":1},
           {"id":"1.21.4","type":"release","url":"https://piston-meta.mojang.com/1.21.4.json","sha1":null},
           {"id":"1.20.1","url":"https://piston-meta.mojang.com/1.20.1.json"}
         ]}
    """.trimIndent()

    private fun layout(): GameLayout = GameLayout(tmp.newFolder("game-" + System.nanoTime())).also { it.ensure() }

    private fun resolver(layout: GameLayout) =
        VersionResolver(layout, Downloader(attempts = 1), manifestUrl = server.url("/manifest.json").toString())

    private fun write(layout: GameLayout, id: String, json: String) {
        layout.versionJson(id).also { it.parentFile.mkdirs(); it.writeText(json) }
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
    fun `список версий Mojang терпит лишние поля и явные null`() {
        val manifest = MojangJson.decodeFromString(VersionManifest.serializer(), manifestBody)
        assertEquals("null в поле со значением по умолчанию не роняет разбор", "", manifest.latest.snapshot)
        assertEquals("1.21.4", manifest.latest.release)
        assertNull("null хеш — это отсутствие хеша", manifest.byId("1.21.4")?.sha1)
        assertEquals("версия без типа считается релизом", listOf("1.21.4", "1.20.1"), manifest.releases().map { it.id })
        assertNull("неизвестная версия не находится", manifest.byId("1.0-fake"))
    }

    @Test
    fun `описание версии и индекс ассетов получают умолчания`() {
        val meta = MojangJson.decodeFromString(VersionMeta.serializer(), """{"id":"x","mainClass":null,"libraries":null}""")
        assertEquals("без главного класса берётся ванильный", "net.minecraft.client.main.Main", meta.mainClass)
        assertTrue("null вместо списка библиотек — пустой список", meta.libraries.isEmpty())
        assertNull(meta.inheritsFrom)

        val index = MojangJson.decodeFromString(
            AssetIndex.serializer(),
            """{"map_to_resources":true,"objects":{"sounds/a.ogg":{"hash":"ab12","size":7}}}""",
        )
        assertTrue("старые версии читают ассеты из папки resources — флаг обязан читаться под своим именем", index.mapToResources)
        assertFalse(index.virtual)
        assertEquals(7L, index.objects.getValue("sounds/a.ogg").size)

        assertTrue(
            "описание без id не превращается в версию с пустым именем",
            runCatching { MojangJson.decodeFromString(VersionMeta.serializer(), """{"mainClass":"a.B"}""") }.isFailure,
        )
    }

    @Test
    fun `список версий кешируется, а мусор из сети не затирает кеш`() {
        val layout = layout()
        manifestAnswer = { MockResponse().setBody(manifestBody) }
        val first = runBlocking { resolver(layout).manifest() }
        assertEquals(3, first.versions.size)
        val cache = File(layout.caches, "version_manifest_v2.json")
        assertTrue("удачный ответ сохраняется для запуска без сети", cache.isFile)

        manifestAnswer = { MockResponse().setBody("<html>Вход в сеть Wi-Fi</html>") }
        val portal = runBlocking { resolver(layout).manifest() }
        assertEquals("страница авторизации Wi-Fi не заменяет список версий", 3, portal.versions.size)
        assertEquals("кеш не перезаписан мусором", manifestBody, cache.readText())

        manifestAnswer = { MockResponse().setResponseCode(500) }
        assertEquals("без сети работает сохранённый список", 3, runBlocking { resolver(layout).manifest() }.versions.size)
    }

    @Test
    fun `список версий запоминается в памяти и обновляется по требованию`() {
        val layout = layout()
        manifestAnswer = { MockResponse().setBody(manifestBody) }
        val resolver = resolver(layout)
        runBlocking { resolver.manifest() }
        runBlocking { resolver.manifest() }
        assertEquals("повторный вопрос не ходит в сеть", 1, server.requestCount)
        runBlocking { resolver.manifest(refresh = true) }
        assertEquals("обновление по требованию идёт в сеть", 2, server.requestCount)
    }

    @Test
    fun `без сети и с битым кешем — понятная ошибка и кеш удалён`() {
        val layout = layout()
        manifestAnswer = { MockResponse().setResponseCode(500) }
        expectIo("без сети и без кеша списку версий взяться неоткуда") { runBlocking { resolver(layout).manifest() } }

        val cache = File(layout.caches, "version_manifest_v2.json").also { it.writeText("{битый") }
        val error = expectIo("битый кеш не выдаётся за список версий") { runBlocking { resolver(layout).manifest() } }
        assertTrue("игроку сказано, что нужен интернет", error.message.orEmpty().contains("нужен интернет"))
        assertFalse("битый кеш удалён, иначе он ломал бы каждый запуск", cache.exists())
    }

    @Test
    fun `описание версии на диске`() {
        val layout = layout()
        expectIo("отсутствующее описание — ошибка, а не пустая версия") { resolver(layout).readLocal("1.20.1") }

        write(layout, "1.20.1", "{битый json")
        val error = expectIo("битое описание не запускается") { resolver(layout).readLocal("1.20.1") }
        assertTrue("игроку сказано, что описание скачается заново", error.message.orEmpty().contains("скачается заново"))
        assertFalse("битое описание удалено, чтобы следующий запуск скачал его", layout.versionJson("1.20.1").exists())
    }

    @Test
    fun `цикл наследования версий обрывается ошибкой`() {
        val layout = layout()
        write(layout, "a", """{"id":"a","inheritsFrom":"b"}""")
        write(layout, "b", """{"id":"b","inheritsFrom":"a"}""")
        val error = expectIo("версии, наследующие друг друга, иначе зациклили бы запуск навсегда") { runBlocking { resolver(layout).resolve("a") } }
        assertTrue(error.message.orEmpty().contains("цикл"))
    }

    @Test
    fun `сборка версии из старого формата и правила библиотек`() {
        val layout = layout()
        write(
            layout,
            "1.8.9",
            """
            {"id":"1.8.9","minecraftArguments":"--username ${'$'}{auth_player_name}  --version ${'$'}{version_name}",
             "releaseTime":"2015-12-03T09:24:39+00:00",
             "libraries":[
               {"name":"com.google.guava:guava:17.0"},
               {"name":"org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209"},
               {"name":"org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209","natives":{"linux":"natives-linux"}},
               {"name":"com.example:mac-only:1.0","rules":[{"action":"allow","os":{"name":"osx"}}]},
               {"name":"com.example:not-mac:1.0","rules":[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]},
               {"name":"com.mojang:jtracy:1.0.37:natives-linux"},
               {"name":"broken-coordinate"}
             ]}
            """.trimIndent(),
        )
        write(
            layout,
            "1.8.9-forge",
            """
            {"id":"1.8.9-forge","inheritsFrom":"1.8.9","mainClass":"net.minecraft.launchwrapper.Launch",
             "libraries":[
               {"name":"com.google.guava:guava:21.0","url":"https://maven.minecraftforge.net/"}
             ]}
            """.trimIndent(),
        )
        val version = runBlocking { resolver(layout).resolve("1.8.9-forge") }

        assertEquals(
            "строка аргументов старого формата режется по пробелам без пустых кусков",
            listOf("--username", "\${auth_player_name}", "--version", "\${version_name}"),
            version.gameArguments,
        )
        assertEquals("версия без описания ассетов берёт legacy", "legacy", version.assetsId)
        assertEquals("версия без указания Java — Java 8", 8, version.javaMajor)
        assertEquals("версия LWJGL запоминается, даже если сама библиотека заменяется телефонной", "2.9.4-nightly-20150209", version.lwjglVersion)
        assertEquals("дата выпуска берётся у ванили", "2015-12-03T09:24:39+00:00", version.releaseTime)
        assertEquals("net.minecraft.launchwrapper.Launch", version.mainClass)

        val byName = version.libraries.associateBy { it.name }
        assertEquals(
            "на телефон идут только библиотеки, разрешённые для Linux, без нативов и битых координат; одна библиотека — одна версия",
            setOf("com.google.guava:guava:21.0", "com.example:not-mac:1.0"),
            byName.keys,
        )
        assertEquals(
            "библиотека загрузчика перекрывает ванильную и берётся из его репозитория",
            "https://maven.minecraftforge.net/com/google/guava/guava/21.0/guava-21.0.jar",
            byName.getValue("com.google.guava:guava:21.0").url,
        )
        assertEquals(
            "без адреса библиотека берётся из репозитория Mojang",
            MOJANG_LIBRARIES_URL + "com/example/not-mac/1.0/not-mac-1.0.jar",
            byName.getValue("com.example:not-mac:1.0").url,
        )
    }

    @Test
    fun `аргументы нового формата с массивом значений и журнал клиента`() {
        val layout = layout()
        write(
            layout,
            "1.20.1",
            """
            {"id":"1.20.1","assets":"5","javaVersion":{"majorVersion":17},
             "arguments":{
               "game":["--demo-off",{"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"},
                       {"rules":[{"action":"allow","os":{"name":"linux"}}],"value":["--a","--b"]}],
               "jvm":[{"rules":[{"action":"allow","os":{"name":"windows"}}],"value":"-XX:HeapDumpPath=x"},"-Dok=1"]
             },
             "logging":{"client":{"argument":"-Dlog4j.configurationFile=${'$'}{path}","type":"log4j2-xml",
                        "file":{"id":"client-1.12.xml","sha1":"aa","size":1,"url":"https://x/c.xml"}}}}
            """.trimIndent(),
        )
        val version = runBlocking { resolver(layout).resolve("1.20.1") }
        assertEquals(
            "флаг демо-режима не ставится, а значения-массивы разворачиваются",
            listOf("--demo-off", "--a", "--b"),
            version.gameArguments,
        )
        assertEquals("флаг только для Windows на телефон не уходит", listOf("-Dok=1"), version.jvmArguments)
        assertEquals("5", version.assetsId)
        assertEquals(17, version.javaMajor)
        assertEquals("настройка журнала клиента доходит до установщика", "client-1.12.xml", version.logging?.file?.id)
    }
}
