package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.sync.CloudSync
import net.millida.launcher.core.sync.LocalBuilds
import net.millida.launcher.core.sync.PackFile
import net.millida.launcher.core.sync.PackManifest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cloud sync only pays off if the phone and the computer read the same
 * snapshot. Everything pinned here is either a field of that format or a
 * promise the player was given: nothing of theirs is overwritten silently, an
 * empty cloud is not a failure, and a snapshot from a newer launcher says so
 * instead of importing half of itself.
 */
class CloudSyncTest {

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

    private class FakeBuilds(
        var local: MutableList<String> = mutableListOf(),
        private val described: Map<String, PackManifest> = emptyMap(),
    ) : LocalBuilds {
        val installs = mutableListOf<Triple<String, PackManifest, String>>()
        var failOn: String = ""

        override fun names(): List<String> = local.toList()

        override fun describe(name: String): PackManifest? = described[name]

        override fun group(name: String): String = "Выживание"

        override suspend fun install(name: String, manifest: PackManifest, group: String) {
            if (name == failOn) throw java.io.IOException("каталог недоступен")
            installs += Triple(name, manifest, group)
            if (name !in local) local += name
        }
    }

    private fun sync(builds: LocalBuilds, device: String = "Pixel 8"): CloudSync {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, "token")
        val auth = MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/'))
        return CloudSync(auth, builds, deviceName = device)
    }

    /** Byte for byte what the desktop launcher writes to /launcher/sync. */
    private fun desktopSnapshot(): String = """
        {"snapshot":{
          "formatVersion":1,
          "device":"DESKTOP-HOME",
          "profiles":[
            {"name":"Выживание","group":"Дом","manifest":{
              "formatVersion":1,"name":"Выживание","summary":"Домашняя сборка",
              "game":"1.21.4","loader":"fabric","loaderVersion":"0.16.9",
              "icon":"https://cdn.millida.net/icon.png",
              "files":[{"path":"mods/sodium.jar","kind":"mod","projectId":"AANobbMI",
                        "versionId":"v1","sha1":"${"a".repeat(40)}","size":4096,
                        "url":"https://cdn.modrinth.com/data/AANobbMI/versions/v1/sodium.jar"}],
              "settings":{"fov":"90"},
              "servers":[{"name":"Millida","ip":"play.millida.net"}]}},
            {"name":"Техно","group":"","manifest":{
              "formatVersion":1,"name":"Техно","game":"1.20.1","loader":"quilt","files":[]}}
          ],
          "prefs":{"m-density":"compact"},
          "themes":["noir"]
        },"updatedAt":"2026-09-13T10:00:00Z","sizeBytes":2048}
    """.trimIndent()

    @Test
    fun `снимок с компьютера разбирается в сборки`() = runBlocking {
        server.enqueue(MockResponse().setBody(desktopSnapshot()))
        val builds = FakeBuilds()

        val report = sync(builds).pull()

        assertEquals("обе сборки поставлены", listOf("Выживание", "Техно"), report.installed)
        val first = builds.installs.first().second
        assertEquals("версия игры переносится", "1.21.4", first.game)
        assertEquals("загрузчик переносится", "fabric", first.loader)
        assertEquals("версия загрузчика переносится", "0.16.9", first.loaderVersion)
        assertEquals("описание переносится", "Домашняя сборка", first.summary)
        assertEquals("иконка переносится", "https://cdn.millida.net/icon.png", first.icon)
        assertEquals("файл каталога переносится", 1, first.files.size)
        assertEquals("ссылка на файл переносится", "https://cdn.modrinth.com/data/AANobbMI/versions/v1/sodium.jar", first.files[0].url)
        assertEquals("хеш переносится", "a".repeat(40), first.files[0].sha1)
        assertEquals("размер переносится", 4096L, first.files[0].size)
        assertEquals("проект переносится", "AANobbMI", first.files[0].projectId)
        assertEquals("настройки сборки переносятся", "90", first.settings.jsonObject["fov"]?.jsonPrimitive?.content)
        assertEquals("сервер переносится", "play.millida.net", first.servers[0].ip)
        assertEquals("группа с компьютера переносится", "Дом", builds.installs.first().third)
        assertEquals("тема с компьютера отмечена как недостающая", listOf("noir"), report.themesMissing)
    }

    @Test
    fun `снимок более новой версии формата отклоняется с понятным текстом`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"snapshot":{"formatVersion":2,"device":"PC","profiles":[]}}"""))
        val builds = FakeBuilds()

        val failure = runCatching { sync(builds).pull() }

        assertTrue("чужой формат не импортируется", failure.isFailure)
        assertEquals(
            "игроку сказано, что делать",
            "Копию сделали в более новой версии лаунчера — обнови лаунчер",
            failure.exceptionOrNull()?.message,
        )
        assertTrue("ничего не поставлено", builds.installs.isEmpty())
    }

    @Test
    fun `пустое облако — это не ошибка`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val builds = FakeBuilds(mutableListOf("Только тут"))

        val status = sync(builds).status()

        assertTrue("вход в аккаунт виден", status.signedIn)
        assertFalse("в облаке пусто", status.hasRemote)
        assertEquals("местные сборки посчитаны", 1, status.localProfiles)
    }

    @Test
    fun `состояние показывает, чего где не хватает`() = runBlocking {
        server.enqueue(MockResponse().setBody(desktopSnapshot()))
        val builds = FakeBuilds(mutableListOf("Выживание", "Только на телефоне"))

        val status = sync(builds).status()

        assertTrue(status.hasRemote)
        assertEquals("имя компьютера показано", "DESKTOP-HOME", status.device)
        assertEquals("дата с сервера", "2026-09-13T10:00:00Z", status.updatedAt)
        assertEquals("размер с сервера", 2048L, status.sizeBytes)
        assertEquals("в облаке две сборки", 2, status.remoteProfiles)
        assertEquals("нет на телефоне", listOf("Техно"), status.missingHere)
        assertEquals("нет в облаке", listOf("Только на телефоне"), status.missingThere)
    }

    @Test
    fun `выгрузка пишет поля, которые читает компьютер`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(404))
        val builds = FakeBuilds(
            mutableListOf("Выживание"),
            mapOf(
                "Выживание" to PackManifest(
                    name = "Выживание",
                    game = "1.21.4",
                    loader = "fabric",
                    files = listOf(
                        PackFile(
                            path = "mods/sodium.jar",
                            kind = "mod",
                            sha1 = "a".repeat(40),
                            size = 4096,
                            url = "https://cdn.modrinth.com/data/AANobbMI/versions/v1/sodium.jar",
                        ),
                    ),
                ),
            ),
        )

        sync(builds).push()

        val body = MojangJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val snapshot = body["snapshot"]!!.jsonObject
        assertEquals("версия формата та же, что на компьютере", "1", snapshot["formatVersion"]?.jsonPrimitive?.content)
        assertEquals("имя устройства уезжает", "Pixel 8", snapshot["device"]?.jsonPrimitive?.content)
        val profile = snapshot["profiles"]!!.jsonArray[0].jsonObject
        assertEquals("имя сборки", "Выживание", profile["name"]?.jsonPrimitive?.content)
        assertEquals("группа", "Выживание", profile["group"]?.jsonPrimitive?.content)
        val manifest = profile["manifest"]!!.jsonObject
        for (field in listOf("formatVersion", "name", "game", "loader", "files")) {
            assertTrue("поле $field обязательно на стороне компьютера", field in manifest)
        }
        val file = manifest["files"]!!.jsonArray[0].jsonObject
        for (field in listOf("path", "kind", "url", "sha1", "size")) {
            assertTrue("поле файла $field обязательно на стороне компьютера", field in file)
        }
        assertFalse(
            "в облако уезжает описание, а не сами моды",
            (file as JsonObject).keys.any { it.contains("bytes") || it.contains("content") },
        )
    }

    @Test
    fun `слепок больше предела не уезжает и говорит почему`() = runBlocking {
        val fat = PackManifest(
            name = "Толстая",
            game = "1.21.4",
            loader = "fabric",
            files = (0 until 8000).map {
                PackFile(
                    path = "mods/mod$it.jar",
                    kind = "mod",
                    projectId = "project$it",
                    versionId = "version$it",
                    sha1 = "a".repeat(40),
                    size = 4_000_000,
                    url = "https://cdn.modrinth.com/data/AAAAAAAA/versions/$it/mod$it.jar",
                )
            },
        )
        val builds = FakeBuilds(mutableListOf("Толстая"), mapOf("Толстая" to fat))

        val failure = runCatching { sync(builds).push() }

        assertTrue("предел сработал до выгрузки", failure.isFailure)
        val message = failure.exceptionOrNull()?.message.orEmpty()
        assertTrue("в тексте есть вес и предел: $message", message.contains("КБ") && message.contains("1024 КБ"))
        assertEquals("ни одного запроса к серверу не ушло", 0, server.requestCount)
    }

    @Test
    fun `выборочная загрузка берёт только выбранное`() = runBlocking {
        server.enqueue(MockResponse().setBody(desktopSnapshot()))
        val builds = FakeBuilds()

        val report = sync(builds).pull(only = listOf("Техно"))

        assertEquals("поставлено только выбранное", listOf("Техно"), report.installed)
        assertEquals("остальное пропущено с причиной", listOf("Выживание"), report.skipped.map { it.name })
        assertEquals("причина названа", "не выбрана для загрузки", report.skipped.first().reason)
        assertEquals("тронута одна сборка", 1, builds.installs.size)
    }

    @Test
    fun `местная сборка не затирается без спроса`() = runBlocking {
        server.enqueue(MockResponse().setBody(desktopSnapshot()))
        val builds = FakeBuilds(mutableListOf("Выживание"))

        val report = sync(builds).pull()

        assertEquals("чужое имя не переустановлено", listOf("Техно"), report.installed)
        assertTrue("обновлений без спроса нет", report.updated.isEmpty())
        assertEquals("игроку сказано, что сборку не тронули", listOf("Выживание"), report.skipped.map { it.name })
        assertTrue("причина объясняет, как обновить", report.skipped.first().reason.contains("выбери"))
        assertFalse("установка по этому имени не запускалась", builds.installs.any { it.first == "Выживание" })
    }

    @Test
    fun `названная явно сборка обновляется`() = runBlocking {
        server.enqueue(MockResponse().setBody(desktopSnapshot()))
        val builds = FakeBuilds(mutableListOf("Выживание"))

        val report = sync(builds).pull(only = listOf("Выживание"))

        assertEquals("обновление засчитано как обновление", listOf("Выживание"), report.updated)
        assertTrue("новых сборок не появилось", report.installed.isEmpty())
    }

    @Test
    fun `файл с чужого хоста в облачной сборке не устанавливается`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"snapshot":{"formatVersion":1,"device":"PC","profiles":[
                  {"name":"Подмена","manifest":{"formatVersion":1,"name":"Подмена","game":"1.21.4","loader":"fabric",
                   "files":[
                     {"path":"mods/evil.jar","kind":"mod","sha1":"${"b".repeat(40)}","url":"https://evil.example/evil.jar"},
                     {"path":"mods/ok.jar","kind":"mod","sha1":"${"c".repeat(40)}","url":"https://cdn.modrinth.com/data/X/versions/1/ok.jar"}
                   ]}}
                ]}}
                """.trimIndent()
            )
        )
        val builds = FakeBuilds()

        sync(builds).pull()

        val files = builds.installs.first().second.files
        assertEquals("остался только файл из каталога", 1, files.size)
        assertEquals("https://cdn.modrinth.com/data/X/versions/1/ok.jar", files[0].url)
    }

    @Test
    fun `сборка, которая не установилась, попадает в отчёт, а остальные ставятся`() = runBlocking {
        server.enqueue(MockResponse().setBody(desktopSnapshot()))
        val builds = FakeBuilds()
        builds.failOn = "Выживание"

        val report = sync(builds).pull()

        assertEquals("вторая сборка всё равно поставлена", listOf("Техно"), report.installed)
        assertEquals("первая названа в отчёте", 1, report.failed.size)
        assertTrue("в отчёте видно, что именно сломалось", report.failed.first().contains("каталог недоступен"))
    }
}
