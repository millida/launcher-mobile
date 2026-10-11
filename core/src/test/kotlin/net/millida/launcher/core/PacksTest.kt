package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.packs.PackArchive
import net.millida.launcher.core.packs.PackCodes
import net.millida.launcher.core.packs.PackHostPolicy
import net.millida.launcher.core.packs.PackInstaller
import net.millida.launcher.core.packs.PackReader
import net.millida.launcher.core.packs.PackSource
import net.millida.launcher.core.packs.Packs
import net.millida.launcher.core.packs.MillidaPacks
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A modpack is a file list somebody else wrote: paths, addresses and digests all
 * arrive from outside. What is pinned here is that every one of them is checked
 * before a byte lands in a build, and that a failed install leaves the build the
 * player already had exactly as it was.
 */
class PacksTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var layout: GameLayout
    private lateinit var profiles: ProfileStore
    private val served = HashMap<String, ByteArray>()
    private val missing = HashSet<String>()

    @Before
    fun start() {
        root = File(System.getProperty("java.io.tmpdir"), "millida-packs-test-" + System.nanoTime())
        root.mkdirs()
        layout = GameLayout(root)
        layout.ensure()
        profiles = ProfileStore(layout)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                if (path in missing) return MockResponse().setResponseCode(404)
                val body = served[path] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(okio.Buffer().write(body))
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
        root.deleteRecursively()
    }

    // The mock server speaks plain HTTP; production keeps the shipped policy,
    // which is what the host table below pins.
    private fun policy() = PackHostPolicy(setOf(server.url("/").host), requireHttps = false)

    private fun downloader() = Downloader(attempts = 1)

    private fun packs(): Packs {
        val downloader = downloader()
        val base = server.url("/v2").toString().trimEnd('/')
        return Packs(
            layout = layout,
            profiles = profiles,
            downloader = downloader,
            api = MillidaPacks(downloader, null, base, policy()),
            installer = PackInstaller(layout, profiles, downloader, hosts = policy()),
            reader = PackReader(downloader, "$base/launcher/cf"),
            hosts = policy(),
            curseForgeBase = "$base/launcher/cf",
        )
    }

    private fun url(path: String): String = server.url(path).toString()

    private fun serve(path: String, body: String) {
        served[path] = body.toByteArray()
    }

    private fun serve(path: String, body: ByteArray) {
        served[path] = body
    }

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val file = File(root, name)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (entryName, bytes) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun mrpackIndex(files: String, name: String = "Тестовая сборка"): ByteArray = """
        {"formatVersion":1,"game":"minecraft","versionId":"1.0.0","name":"$name",
         "files":[$files],
         "dependencies":{"minecraft":"1.21.4","fabric-loader":"0.16.9"}}
    """.trimIndent().toByteArray()

    private fun indexEntry(path: String, url: String, sha1: String, size: Long): String =
        """{"path":"$path","hashes":{"sha1":"$sha1"},"downloads":["$url"],"fileSize":$size}"""

    private fun existingProfile(id: String): Profile {
        val profile = Profile(id = id, name = id, gameVersion = "1.21.4", loader = LoaderKind.FABRIC)
        profiles.upsert(profile)
        val directory = profiles.directory(profile)
        File(directory, "mods").mkdirs()
        File(directory, "mods/old.jar").writeText("старый мод")
        File(directory, "saves/world").mkdirs()
        File(directory, "saves/world/level.dat").writeText("мир игрока")
        return profile
    }

    /**
     * path -> verdict. An entry name inside the archive is the only thing
     * standing between a pack and any file on the phone, so the table is the
     * shape of the attack, not of the format.
     */
    @Test
    fun `имена внутри архива, которые обязаны отклоняться`() {
        val cases = listOf(
            Triple("overrides/config/ok.txt", true, "обычный файл сборки"),
            Triple("overrides/../../evil.txt", false, "выход из папки сборки перезаписал бы чужой файл"),
            Triple("../evil.txt", false, "то же самое на один уровень выше"),
            Triple("/etc/passwd", false, "абсолютный путь в архиве не бывает законным"),
        )
        for ((name, allowed, why) in cases) {
            val target = File(root, "unpack-" + System.nanoTime())
            val archive = zip("probe-${System.nanoTime()}.zip", mapOf(name to "x".toByteArray()))
            val result = runCatching { PackArchive.unpack(archive, target) }
            assertEquals(
                "«$name» должен ${if (allowed) "распаковаться" else "отклониться"}. Зачем случай закреплён: $why",
                allowed,
                result.isSuccess,
            )
            target.deleteRecursively()
        }
    }

    /** url -> verdict. A digest proves the bytes; it cannot stop a wrong address. */
    @Test
    fun `адреса, с которых сборке разрешено качать файлы`() {
        val hosts = PackHostPolicy()
        val cases = listOf(
            "https://cdn.modrinth.com/data/AAA/sodium.jar" to true,
            "https://mediafilez.forgecdn.net/files/1/2/jei.jar" to true,
            "https://garage.millida.net/millida-packs/pack.zip?X-Amz-Signature=x" to true,
            "http://cdn.modrinth.com/sodium.jar" to false,
            "https://cdn.modrinth.com.evil.example/sodium.jar" to false,
            "https://evil.example/cdn.modrinth.com/sodium.jar" to false,
            "https://127.0.0.1/sodium.jar" to false,
            "file:///C:/Windows/System32/x.dll" to false,
            "" to false,
        )
        for ((address, allowed) in cases) {
            assertEquals(
                "«$address» обязан ${if (allowed) "проходить" else "отклоняться"}: по этому адресу лаунчер скачает файл и положит его в сборку",
                allowed,
                hosts.allows(address),
            )
        }
    }

    /** code -> verdict. The code is typed by hand and lands in a request path. */
    @Test
    fun `коды сборок переживают ручной ввод`() {
        assertEquals("AB23CD45", PackCodes.normalize("ab23-cd45"))
        assertEquals("AB23CD45", PackCodes.normalize("https://millida.net/p/AB23CD45"))
        assertTrue(PackCodes.valid("AB23-CD45"))
        assertFalse("короткий код не должен уходить в запрос", PackCodes.valid("AB23CD4"))
        assertFalse("O и 0 путают, поэтому O в алфавит не входит", PackCodes.valid("AB23CD4O"))
        assertFalse("код становится сегментом пути запроса", PackCodes.valid("../secrets"))
    }

    @Test
    fun `mrpack с выходом из папки в overrides не устанавливается`() = runBlocking {
        val archive = zip(
            "evil.mrpack",
            mapOf(
                "modrinth.index.json" to mrpackIndex(""),
                "overrides/../../evil.txt" to "перезапись".toByteArray(),
            ),
        )
        val error = runCatching { packs().installFile(archive) }.exceptionOrNull()
        assertNotNull("сборка с выходом за папку обязана остановить установку", error)
        assertTrue(
            "в тексте ошибки должно быть видно, что дело в пути: ${error?.message}",
            error?.message.orEmpty().contains("путь"),
        )
        assertTrue("сборка не должна была появиться", profiles.load().isEmpty())
        assertFalse("файл за пределами папки создавать нельзя", File(root, "evil.txt").exists())
    }

    @Test
    fun `файл с неверным хешем не принимается`() = runBlocking {
        serve("/files/sodium.jar", "настоящие байты мода")
        val wrongSha1 = "0".repeat(40)
        val archive = zip(
            "bad-hash.mrpack",
            mapOf(
                "modrinth.index.json" to mrpackIndex(
                    indexEntry("mods/sodium.jar", url("/files/sodium.jar"), wrongSha1, 0),
                ),
            ),
        )
        val error = runCatching { packs().installFile(archive) }.exceptionOrNull()
        assertNotNull("файл с чужим хешем принимать нельзя — это подменённый мод", error)
        assertTrue(
            "ошибка должна называть файл: ${error?.message}",
            error?.message.orEmpty().contains("mods/sodium.jar"),
        )
        assertTrue("сборка не должна была появиться", profiles.load().isEmpty())
    }

    @Test
    fun `пак с чужого хоста не качается`() = runBlocking {
        val archive = zip(
            "foreign.mrpack",
            mapOf(
                "modrinth.index.json" to mrpackIndex(
                    indexEntry("mods/sodium.jar", "https://evil.example/sodium.jar", "a".repeat(40), 10),
                ),
            ),
        )
        val error = runCatching { packs().installFile(archive) }.exceptionOrNull()
        assertNotNull("адрес вне списка обязан остановить установку", error)
        assertTrue(
            "ошибка должна назвать адрес: ${error?.message}",
            error?.message.orEmpty().contains("evil.example"),
        )
        assertEquals("к чужому хосту лаунчер даже не обращается", 0, server.requestCount)
        assertTrue("сборка не должна была появиться", profiles.load().isEmpty())
    }

    /**
     * Both catalogues describe a build in files that look nothing alike. Below
     * the reader there is one installer, so the two descriptions have to arrive
     * as the same list of files.
     */
    @Test
    fun `манифест CurseForge превращается в тот же список файлов, что и mrpack`() = runBlocking {
        val sha1 = "b".repeat(40)
        val address = url("/files/jei.jar")
        serve(
            "/v2/launcher/cf/v1/mods/238222/files/5555",
            """
            {"data":{"id":5555,"fileName":"jei.jar","downloadUrl":"$address",
             "fileLength":2048,"hashes":[{"value":"$sha1","algo":1}]}}
            """.trimIndent(),
        )

        val reader = PackReader(downloader(), server.url("/v2/launcher/cf").toString().trimEnd('/'))

        val mrpackRoot = File(root, "mr-root").also { it.mkdirs() }
        File(mrpackRoot, "modrinth.index.json")
            .writeBytes(mrpackIndex(indexEntry("mods/jei.jar", address, sha1, 2048)))
        val fromMrpack = reader.readMrpack(mrpackRoot)

        val cfRoot = File(root, "cf-root").also { it.mkdirs() }
        File(cfRoot, "manifest.json").writeText(
            """
            {"name":"Тестовая сборка","version":"1.0.0",
             "minecraft":{"version":"1.21.4","modLoaders":[{"id":"fabric-0.16.9","primary":true}]},
             "files":[{"projectID":238222,"fileID":5555,"required":true}]}
            """.trimIndent(),
        )
        val fromCurseForge = reader.readCurseForge(cfRoot)

        fun shape(plan: net.millida.launcher.core.packs.PackPlan) =
            plan.files.map { listOf(it.path, it.urls, it.sha1, it.size) }

        assertEquals(
            "список файлов обязан совпадать: ниже читателя установщик один",
            shape(fromMrpack.plan),
            shape(fromCurseForge.plan),
        )
        assertEquals("версия игры читается одинаково", fromMrpack.plan.gameVersion, fromCurseForge.plan.gameVersion)
        assertEquals("загрузчик читается одинаково", fromMrpack.plan.loader, fromCurseForge.plan.loader)
        assertEquals("версия загрузчика читается одинаково", "0.16.9", fromCurseForge.plan.loaderVersion)
    }

    @Test
    fun `провал установки не сносит уже установленную сборку`() = runBlocking {
        val profile = existingProfile("моя-сборка")
        val directory = profiles.directory(profile)
        serve("/files/first.jar", "первый мод")
        missing += "/files/second.jar"

        val sha1 = Hashing.sha1("первый мод".toByteArray())
        val archive = zip(
            "half-broken.mrpack",
            mapOf(
                "modrinth.index.json" to mrpackIndex(
                    indexEntry("mods/first.jar", url("/files/first.jar"), sha1, 0) + "," +
                        indexEntry("mods/second.jar", url("/files/second.jar"), "c".repeat(40), 0),
                ),
                "overrides/config/pack.cfg" to "конфиг сборки".toByteArray(),
            ),
        )

        val error = runCatching { packs().installFile(archive, target = profile) }.exceptionOrNull()
        assertNotNull("сборка, которая не докачалась, не должна считаться установленной", error)
        assertTrue(
            "старый мод обязан остаться на месте: иначе игрок остаётся без сборки после неудачного обновления",
            File(directory, "mods/old.jar").isFile,
        )
        assertEquals("старый мод не должен быть тронут", "старый мод", File(directory, "mods/old.jar").readText())
        assertFalse("наполовину скачанная сборка не должна попасть в папку", File(directory, "mods/first.jar").exists())
        assertFalse("конфиги неудачной сборки тоже не должны попасть", File(directory, "config/pack.cfg").exists())
        assertTrue("мир игрока обязан пережить неудачу", File(directory, "saves/world/level.dat").isFile)
    }

    @Test
    fun `удачное обновление меняет моды и не трогает миры игрока`() = runBlocking {
        val profile = existingProfile("обновляемая")
        val directory = profiles.directory(profile)
        serve("/files/new.jar", "новый мод")

        val sha1 = Hashing.sha1("новый мод".toByteArray())
        val archive = zip(
            "update.mrpack",
            mapOf(
                "modrinth.index.json" to mrpackIndex(
                    indexEntry("mods/new.jar", url("/files/new.jar"), sha1, 0),
                    name = "Обновляемая",
                ),
                "overrides/config/pack.cfg" to "конфиг сборки".toByteArray(),
            ),
        )

        packs().installFile(archive, target = profile)

        assertTrue("новый мод на месте", File(directory, "mods/new.jar").isFile)
        assertFalse("мод прошлой версии сборки не должен остаться", File(directory, "mods/old.jar").exists())
        assertEquals("конфиг сборки доехал", "конфиг сборки", File(directory, "config/pack.cfg").readText())
        assertTrue("мир игрока остаётся на месте", File(directory, "saves/world/level.dat").isFile)
        val origin = packs().info(directory)
        assertEquals("сборка запомнила, откуда она", PackSource.FILE.id, origin?.source)
        assertEquals("и какие файлы в неё скачаны", 1, origin?.files?.size)
    }
}
