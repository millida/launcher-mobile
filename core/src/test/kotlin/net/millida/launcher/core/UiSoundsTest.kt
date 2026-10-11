package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.media.UiSounds
import net.millida.launcher.core.media.UiSoundsResult
import net.millida.launcher.core.mojang.VersionResolver
import okhttp3.OkHttpClient
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

class UiSoundsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var layout: GameLayout

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(500)
        }
        server.start()
        layout = GameLayout(tmp.newFolder()).also { it.ensure() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun sounds(): UiSounds {
        val downloader = Downloader(client = OkHttpClient(), attempts = 1, allowedHosts = setOf("blocked.invalid"))
        val resolver = VersionResolver(layout, downloader, manifestUrl = server.url("/manifest.json").toString())
        return UiSounds(layout, downloader, resolver)
    }

    private fun hashOf(key: String): String = Hashing.sha1("sound:$key".toByteArray())

    private fun storeObject(key: String) {
        val bytes = "sound:$key".toByteArray()
        val target = layout.assetObject(Hashing.sha1(bytes))
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    private fun writeIndex(id: String, entries: Map<String, String>) {
        val objects = entries.entries.joinToString(",") { (key, hash) ->
            "\"minecraft/sounds/$key\":{\"hash\":\"$hash\",\"size\":${("sound:$key").toByteArray().size}}"
        }
        layout.assetIndex(id).writeText("{\"objects\":{$objects}}")
    }

    private val allKeys: List<String> = UiSoundEvent.entries.flatMap { it.variants }.distinct()

    private fun fullIndexWithObjects(id: String = "17") {
        writeIndex(id, allKeys.associateWith { hashOf(it) })
        allKeys.forEach { storeObject(it) }
    }

    private fun stateFile(): File = File(layout.caches, "ui-sounds.json")

    @Test
    fun `звуки из локального индекса готовы без сети`() {
        runBlocking {
            fullIndexWithObjects()
            val result = sounds().prepare()
            assertTrue("всё есть на телефоне — ожидался Ready, получено $result", result is UiSoundsResult.Ready)
            assertEquals("у каждого события есть звук", UiSoundEvent.entries.toSet(), result.files.keys)
            assertEquals(
                "удар по сундуку чередует оба варианта, как в игре",
                listOf(layout.assetObject(hashOf("dig/wood1.ogg")), layout.assetObject(hashOf("dig/wood2.ogg"))),
                result.files[UiSoundEvent.ChestHit],
            )
            assertEquals("за манифестом не ходили", 0, server.requestCount)
            assertTrue("выбор запомнен, чтобы не разбирать индекс на каждом старте", stateFile().isFile)
        }
    }

    @Test
    fun `запомненный выбор работает и без индекса`() {
        runBlocking {
            fullIndexWithObjects()
            sounds().prepare()
            layout.assetIndex("17").delete()
            val again = sounds().prepare()
            assertTrue("второй старт без индекса должен взять запомненное: $again", again is UiSoundsResult.Ready)
            assertEquals("сеть не нужна", 0, server.requestCount)
        }
    }

    @Test
    fun `битый номер звука заменяется запасным`() {
        runBlocking {
            val entries = allKeys.associateWith { hashOf(it) }.toMutableMap()
            entries["note/xylobone.ogg"] = "NOT-A-HASH"
            entries["random/click.ogg"] = hashOf("random/click.ogg")
            writeIndex("17", entries)
            allKeys.forEach { storeObject(it) }
            storeObject("random/click.ogg")

            val result = sounds().prepare()
            assertTrue("$result", result is UiSoundsResult.Ready)
            assertEquals(
                "основной звук с испорченным хешем не скачивается, клик берёт запасной",
                listOf(layout.assetObject(hashOf("random/click.ogg"))),
                result.files[UiSoundEvent.Click],
            )
        }
    }

    @Test
    fun `чужая версия запомненного выбора не используется`() {
        runBlocking {
            fullIndexWithObjects()
            stateFile().parentFile?.mkdirs()
            stateFile().writeText("""{"signature":"old","events":{"click":[{"hash":"${"a".repeat(40)}","size":1}]}}""")
            val result = sounds().prepare()
            assertTrue(
                "выбор старой сборки с другим набором звуков игнорируется, иначе клик указывал бы на чужой файл: $result",
                result is UiSoundsResult.Ready,
            )
            assertEquals(listOf(layout.assetObject(hashOf("note/xylobone.ogg"))), result.files[UiSoundEvent.Click])
        }
    }

    @Test
    fun `испорченный файл выбора не мешает старту`() {
        runBlocking {
            fullIndexWithObjects()
            stateFile().parentFile?.mkdirs()
            stateFile().writeText("{")
            val result = sounds().prepare()
            assertTrue("$result", result is UiSoundsResult.Ready)
            assertFalse("битый файл перезаписан годным", stateFile().readText() == "{")
        }
    }

    @Test
    fun `нескачанный звук отмечается пропавшим, остальные играют`() {
        runBlocking {
            writeIndex("17", allKeys.associateWith { hashOf(it) })
            allKeys.filter { it != "note/bass.ogg" }.forEach { storeObject(it) }
            val result = sounds().prepare()
            assertTrue("один звук не скачался — частичная готовность, а не провал: $result", result is UiSoundsResult.Partial)
            val partial = result as UiSoundsResult.Partial
            assertEquals("пропал только звук ошибки", setOf(UiSoundEvent.Error), partial.missing)
            assertTrue("причина сохраняется для журнала", partial.cause is IOException)
            assertNull(partial.files[UiSoundEvent.Error])
            assertEquals(
                "звук краша берёт своё, а не общий с ошибкой запасной",
                listOf(layout.assetObject(hashOf("note/didgeridoo.ogg"))),
                partial.files[UiSoundEvent.Crash],
            )
        }
    }

    @Test
    fun `неполный индекс без сети даёт частичную готовность`() {
        runBlocking {
            writeIndex("17", mapOf("note/xylobone.ogg" to hashOf("note/xylobone.ogg")))
            storeObject("note/xylobone.ogg")
            val result = sounds().prepare()
            assertTrue("$result", result is UiSoundsResult.Partial)
            val partial = result as UiSoundsResult.Partial
            assertEquals(UiSoundEvent.entries.toSet() - UiSoundEvent.Click, partial.missing)
            assertTrue("недостающее пытались взять из свежего индекса", server.requestCount > 0)
            assertTrue("сбой сети виден как причина: ${partial.cause}", partial.cause is IOException)
        }
    }

    @Test
    fun `без индекса и без сети — провал с причиной`() {
        runBlocking {
            val result = sounds().prepare()
            assertTrue("$result", result is UiSoundsResult.Failed)
            assertTrue("провал не прячется — причина сетевая", (result as UiSoundsResult.Failed).cause is IOException)
            assertTrue("у провала нет файлов", result.files.isEmpty())
        }
    }

    @Test
    fun `идентификаторы событий уникальны и находятся по имени`() {
        val ids = UiSoundEvent.entries.map { it.id }
        assertEquals("id — ключ запомненного выбора, повтор склеит два события", ids.size, ids.toSet().size)
        assertEquals(UiSoundEvent.ChestEpic, UiSoundEvent.byId("chest_epic"))
        assertNull("неизвестный id из старого файла пропускается", UiSoundEvent.byId("teleport"))
        assertTrue("громкость в пределах 0..1", UiSoundEvent.entries.all { it.gain > 0f && it.gain <= 1f })
        assertTrue("у каждого события есть основной звук", UiSoundEvent.entries.all { it.variants.isNotEmpty() })
    }
}
