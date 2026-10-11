package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.media.LobbyMusic
import net.millida.launcher.core.media.MusicTrack
import net.millida.launcher.core.mojang.VersionResolver
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LobbyMusicTest {

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

    private fun music(): LobbyMusic {
        val downloader = Downloader(client = OkHttpClient(), attempts = 1, allowedHosts = setOf("blocked.invalid"))
        val resolver = VersionResolver(layout, downloader, manifestUrl = server.url("/manifest.json").toString())
        return LobbyMusic(layout, downloader, resolver)
    }

    private fun bytesOf(key: String): ByteArray = "track:$key".toByteArray()

    private fun hashOf(key: String): String = Hashing.sha1(bytesOf(key))

    private fun index(id: String, keys: List<String>, modified: Long): File {
        val objects = keys.joinToString(",") { key ->
            "\"$key\":{\"hash\":\"${hashOf(key)}\",\"size\":${bytesOf(key).size}}"
        }
        val file = layout.assetIndex(id)
        file.writeText("{\"objects\":{$objects}}")
        file.setLastModified(modified)
        return file
    }

    @Test
    fun `треки берутся из индекса на телефоне без сети`() {
        runBlocking {
            index(
                "17",
                listOf(
                    "minecraft/sounds/music/menu/mutation.ogg",
                    "minecraft/sounds/music/game/sweden.ogg",
                    "minecraft/sounds/dig/wood1.ogg",
                ),
                1_000_000L,
            )
            val tracks = music().tracks()
            assertEquals(
                "только музыка, в порядке ключей, как на компьютере",
                listOf("minecraft/sounds/music/game/sweden.ogg", "minecraft/sounds/music/menu/mutation.ogg"),
                tracks.map { it.key },
            )
            assertEquals("Mutation", tracks[1].title)
            assertEquals(hashOf("minecraft/sounds/music/game/sweden.ogg"), tracks[0].hash)
            assertEquals("сеть не трогали", 0, server.requestCount)
        }
    }

    @Test
    fun `битый или немой свежий индекс пропускается`() {
        runBlocking {
            index("old", listOf("minecraft/sounds/music/game/sweden.ogg"), 1_000_000L)
            index("silent", listOf("minecraft/sounds/dig/wood1.ogg"), 2_000_000L)
            val broken = layout.assetIndex("broken")
            broken.writeText("{\"objects\":")
            broken.setLastModified(3_000_000L)
            val tracks = music().tracks()
            assertEquals(
                "испорченный и безмузыкальный индексы не лишают лобби музыки из более старого",
                listOf("minecraft/sounds/music/game/sweden.ogg"),
                tracks.map { it.key },
            )
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `без индекса и без сети — ошибка, а не тишина без причины`() {
        runBlocking {
            val error = runCatching { music().tracks() }.exceptionOrNull()
            assertTrue("ожидалась сетевая ошибка, получено $error", error is IOException)
            assertTrue("пытались взять свежий список версий", server.requestCount > 0)
        }
    }

    @Test
    fun `скачанный трек отдаётся с диска`() {
        runBlocking {
            val key = "minecraft/sounds/music/game/sweden.ogg"
            val track = MusicTrack(key, "Sweden", hashOf(key), bytesOf(key).size.toLong())
            val target = layout.assetObject(track.hash)
            target.parentFile?.mkdirs()
            target.writeBytes(bytesOf(key))
            assertEquals("файл в хранилище объектов игры — тот же, что читает игра", target, music().file(track))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `испорченный трек на диске не играется`() {
        runBlocking {
            val key = "minecraft/sounds/music/game/sweden.ogg"
            val track = MusicTrack(key, "Sweden", hashOf(key), bytesOf(key).size.toLong())
            val target = layout.assetObject(track.hash)
            target.parentFile?.mkdirs()
            target.writeBytes("подмена".toByteArray())
            val error = runCatching { music().file(track) }.exceptionOrNull()
            assertTrue(
                "файл с чужим хешем не отдаётся плееру, а перекачивается; здесь сеть закрыта — ошибка: $error",
                error is IOException,
            )
        }
    }

    @Test
    fun `название трека из имени файла`() {
        val cases = listOf(
            "minecraft/sounds/music/game/nether/nether_wastes/rubedo.ogg" to "Rubedo",
            "minecraft/sounds/music/game/a_familiar_room.ogg" to "A Familiar Room",
            "minecraft/sounds/music/menu/moog_city_2.ogg" to "Moog City 2",
        )
        cases.forEach { (key, title) -> assertEquals(key, title, LobbyMusic.title(key)) }
    }
}
