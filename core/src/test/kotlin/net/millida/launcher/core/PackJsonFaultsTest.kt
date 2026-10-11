package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.packs.MillidaPacks
import net.millida.launcher.core.packs.PackManifest
import net.millida.launcher.core.packs.PackReader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Every caller of the pack code catches IOException only: a wrong JSON shape
 * that escapes as IllegalArgumentException or SerializationException closes
 * the install screen instead of showing a message.
 */
class PackJsonFaultsTest {

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

    private fun base() = server.url("/api").toString().trimEnd('/')

    private fun archive(name: String, text: String): File {
        val root = tmp.newFolder("pack-" + System.nanoTime())
        File(root, name).writeText(text)
        return root
    }

    private data class Case(val action: suspend () -> Any?, val why: String)

    @Test
    fun `кривой JSON сборки превращается в IOException с понятным текстом`() {
        val cases = listOf(
            Case(
                {
                    PackManifest.parse(
                        MojangJson.parseToJsonElement("""{"game":"1.20.1","files":[1,2]}""").jsonObject,
                    )
                },
                "строка списка файлов не объект — раньше IllegalArgumentException мимо catch (IOException)",
            ),
            Case(
                {
                    PackReader(Downloader(attempts = 1)).readMrpack(
                        archive("modrinth.index.json", """{"dependencies":{"minecraft":"1.20.1"},"files":["x"]}"""),
                    )
                },
                "mrpack с файлом-строкой вместо объекта",
            ),
            Case(
                {
                    PackReader(Downloader(attempts = 1)).readMrpack(
                        archive("modrinth.index.json", """{"dependencies":{"minecraft":{"v":1}},"files":[]}"""),
                    )
                },
                "версия игры объектом вместо строки",
            ),
            Case(
                {
                    server.enqueue(MockResponse().setBody("<html>502</html>"))
                    MillidaPacks(Downloader(attempts = 1), apiBase = base()).catalogPack("good-pack")
                },
                "карточка каталога пришла HTML-страницей щита",
            ),
            Case(
                {
                    server.enqueue(MockResponse().setBody("""{"files":"none"}"""))
                    MillidaPacks(Downloader(attempts = 1), apiBase = base()).catalogPack("good-pack")
                },
                "карточка без клиентского файла остаётся понятной ошибкой, а не падением",
            ),
            Case(
                {
                    server.enqueue(MockResponse().setBody("""{"files":[1]}"""))
                    MillidaPacks(Downloader(attempts = 1), apiBase = base()).catalogPack("good-pack")
                },
                "строка списка файлов карточки не объект",
            ),
        )
        for (case in cases) {
            val error = runCatching { runBlocking { case.action() } }.exceptionOrNull()
            assertNotNull("ожидалась ошибка: ${case.why}", error)
            assertTrue("ошибка должна быть IOException: ${case.why}; было $error", error is IOException)
            assertTrue("у ошибки есть текст для игрока: ${case.why}", !error?.message.isNullOrBlank())
        }
    }
}
