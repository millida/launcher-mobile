package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.cosmetics.CosmeticsCatalog
import net.millida.launcher.core.cosmetics.CosmeticsModStatus
import net.millida.launcher.core.cosmetics.MillidaMod
import net.millida.launcher.core.cosmetics.SkinFix
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.profile.DISABLED_SUFFIX
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileStore
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
import java.security.MessageDigest

/**
 * The cosmetics jar is the only thing that draws skins, capes, hats, wings and
 * emotes in game, and the desktop launcher already picks it by the same rules.
 * A build that gets a different jar here shows the player a different character
 * on the phone than on the computer, so every rule below is pinned.
 */
class CosmeticsModTest {

    @get:Rule
    val temp = TemporaryFolder()

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

    private fun catalogJson(version: String = "1.4.0", baseUrl: String = "", sha256: String = ""): String {
        val sum = if (sha256.isEmpty()) "" else ""","sha256":"$sha256""""
        val base = if (baseUrl.isEmpty()) "" else ""","baseUrl":"$baseUrl""""
        return """
            {"formatVersion":1,"version":"$version"$base,"variants":[
              {"id":"fabric-1.16.5","loaders":["fabric","quilt"],
               "minecraft":{"from":"1.16.5","to":"1.17"},
               "artifact":"millida-mod-fabric-1.16.5-{version}.jar","javaMajor":8},
              {"id":"fabric-1.20.1","loaders":["fabric","quilt"],
               "minecraft":{"from":"1.20","to":"1.20.2"},
               "artifact":"millida-mod-fabric-1.20.1-{version}.jar","javaMajor":17$sum},
              {"id":"forge-1.20.1","loaders":["forge"],
               "minecraft":{"from":"1.20","to":"1.20.2"},
               "artifact":"millida-mod-forge-1.20.1-{version}.jar","javaMajor":17},
              {"id":"fabric-1.21.11","loaders":["fabric","quilt"],
               "minecraft":{"from":"1.21.11","to":"26.1"},
               "artifact":"millida-mod-fabric-1.21.11-{version}.jar","javaMajor":21},
              {"id":"fabric-26.2","loaders":["fabric","quilt"],
               "minecraft":{"from":"26.2","to":null},
               "artifact":"millida-mod-fabric-26.2-{version}.jar","javaMajor":25}
            ]}
        """.trimIndent()
    }

    private fun catalog(version: String = "1.4.0", baseUrl: String = "", sha256: String = "") =
        CosmeticsCatalog.parse(catalogJson(version, baseUrl, sha256))

    @Test
    fun `вариант подбирается по загрузчику и диапазону версий`() {
        val manifest = catalog()
        data class Case(val game: String, val loader: String, val variant: String?, val why: String)

        val cases = listOf(
            Case("1.20.1", "fabric", "fabric-1.20.1", "своя версия и свой загрузчик"),
            Case("1.20.1", "quilt", "fabric-1.20.1", "Quilt читает моды Fabric"),
            Case("1.20.1", "forge", "forge-1.20.1", "у Forge свой вариант в том же диапазоне"),
            Case("1.20", "fabric", "fabric-1.20.1", "нижняя граница входит в диапазон"),
            Case("1.20.2", "fabric", null, "верхняя граница уже не входит"),
            Case("1.19.4", "fabric", null, "версия ниже нижней границы не подходит"),
            Case("1.21.11", "fabric", "fabric-1.21.11", "старая нумерация берёт свой вариант"),
            Case("26.1.2", "fabric", null, "26.1 закрыта сверху и своего варианта тут нет"),
            Case("26.2", "fabric", "fabric-26.2", "открытый сверху диапазон берёт свою версию"),
            Case("26.5", "fabric", "fabric-26.2", "и всё, что новее"),
            Case("1.21.11", "neoforge", null, "чужого загрузчика в каталоге нет"),
        )

        cases.forEach { case ->
            val picked = CosmeticsCatalog.pick(manifest, case.game, case.loader)?.id
            assertEquals(
                "${case.game} + ${case.loader}: ${case.why}",
                case.variant,
                picked,
            )
        }
    }

    @Test
    fun `версии сравниваются числами, а не строками`() {
        val manifest = CosmeticsCatalog.parse(
            """
            {"version":"1.0.0","variants":[
              {"id":"fabric-1.21.9","loaders":["fabric"],
               "minecraft":{"from":"1.21.9","to":"1.21.10"},
               "artifact":"millida-mod-fabric-1.21.9-{version}.jar"}
            ]}
            """.trimIndent()
        )

        assertEquals(
            "1.21.9 берёт свой вариант",
            "fabric-1.21.9",
            CosmeticsCatalog.pick(manifest, "1.21.9", "fabric")?.id,
        )
        assertEquals(
            "1.21.10 новее 1.21.9, хотя строкой меньше — вариант не подходит",
            null,
            CosmeticsCatalog.pick(manifest, "1.21.10", "fabric")?.id,
        )
    }

    @Test
    fun `состояние сборки читается по файлам в папке модов`() {
        val manifest = catalog()
        val fresh = "millida-mod-fabric-1.20.1-1.4.0.jar"
        data class Case(
            val jars: List<String>,
            val loader: String,
            val game: String,
            val status: CosmeticsModStatus,
            val why: String,
        )

        val cases = listOf(
            Case(emptyList(), "fabric", "1.20.1", CosmeticsModStatus.MISSING, "мода нет в папке"),
            Case(listOf(fresh), "fabric", "1.20.1", CosmeticsModStatus.INSTALLED, "стоит нужный вариант"),
            Case(
                listOf("millida-mod-fabric-1.20.1-1.2.0.jar"),
                "fabric", "1.20.1", CosmeticsModStatus.OUTDATED,
                "стоит старая версия того же варианта",
            ),
            Case(
                listOf("millida-mod-fabric-1.16.5-1.4.0.jar"),
                "fabric", "1.20.1", CosmeticsModStatus.OUTDATED,
                "стоит вариант под другую версию игры",
            ),
            Case(
                listOf(fresh + DISABLED_SUFFIX),
                "fabric", "1.20.1", CosmeticsModStatus.DISABLED,
                "файл помечен как выключенный",
            ),
            Case(listOf(fresh), "vanilla", "1.20.1", CosmeticsModStatus.UNSUPPORTED_LOADER, "ванильной сборке мод некуда"),
            Case(emptyList(), "fabric", "1.19.4", CosmeticsModStatus.UNSUPPORTED_VERSION, "версия вне покрытия каталога"),
            Case(listOf("sodium.jar"), "fabric", "1.20.1", CosmeticsModStatus.MISSING, "чужие моды не считаются нашими"),
        )

        cases.forEach { case ->
            val state = CosmeticsCatalog.evaluate("build", case.game, case.loader, manifest, case.jars)
            assertEquals(case.why, case.status, state.status)
        }

        val outdated = CosmeticsCatalog.evaluate(
            "build", "1.20.1", "fabric", manifest, listOf("millida-mod-fabric-1.20.1-1.2.0.jar"),
        )
        assertEquals("для замены известно имя нужного файла", fresh, outdated.availableFile)
        assertEquals("и имя того, что стоит сейчас", "millida-mod-fabric-1.20.1-1.2.0.jar", outdated.installedFile)
    }

    @Test
    fun `каталог без версии мода не выдаёт имя файла`() {
        val manifest = catalog(version = "")
        val state = CosmeticsCatalog.evaluate("build", "1.20.1", "fabric", manifest, emptyList())

        assertEquals("подстановку версии сделать не из чего", CosmeticsModStatus.CATALOG_INCOMPLETE, state.status)
        assertTrue("имени файла нет", state.availableFile.isEmpty())
        assertTrue("причина написана для игрока", state.reason.contains("версию мода"))
    }

    @Test
    fun `диагностика говорит, что чинить и чинится ли это кнопкой`() {
        val manifest = catalog()
        val fresh = "millida-mod-fabric-1.20.1-1.4.0.jar"
        data class Case(
            val jars: List<String>,
            val loader: String,
            val game: String,
            val check: String?,
            val fix: SkinFix,
            val why: String,
        )

        val cases = listOf(
            Case(listOf(fresh), "fabric", "1.20.1", null, SkinFix.NONE, "всё на месте — чинить нечего"),
            Case(emptyList(), "fabric", "1.20.1", "present", SkinFix.INSTALL, "мода нет — ставим кнопкой"),
            Case(listOf(fresh + DISABLED_SUFFIX), "fabric", "1.20.1", "enabled", SkinFix.ENABLE, "мод выключен — включаем кнопкой"),
            Case(
                listOf("millida-mod-fabric-1.20.1-1.2.0.jar"),
                "fabric", "1.20.1", "version", SkinFix.INSTALL,
                "версия не та — заменяем кнопкой",
            ),
            Case(listOf(fresh), "vanilla", "1.20.1", "loader", SkinFix.NONE, "ванильную сборку кнопка не спасёт"),
            Case(emptyList(), "fabric", "1.19.4", "coverage", SkinFix.NONE, "версии в каталоге нет — кнопка не поможет"),
        )

        cases.forEach { case ->
            val state = CosmeticsCatalog.evaluate("build", case.game, case.loader, manifest, case.jars)
            val report = CosmeticsCatalog.diagnose("Моя сборка", state)
            assertEquals(
                "${case.why}: провалившаяся проверка",
                case.check,
                report.checks.firstOrNull { !it.ok }?.id,
            )
            assertEquals("${case.why}: действие кнопки", case.fix, report.fix)
            assertTrue("${case.why}: текст для игрока не пустой", report.summary.length > 10)
            report.checks.forEach {
                assertTrue("каждая проверка объясняет себя словами: ${it.id}", it.detail.length > 10)
            }
        }
    }

    @Test
    fun `установка кладёт файл и убирает старый только после удачной загрузки`() = runBlocking {
        val jar = "cosmetics jar payload".toByteArray()
        serve(catalogJson(baseUrl = server.url("/mod").toString(), sha256 = sha256(jar)), jar)

        val world = world()
        val stale = File(world.mods, "millida-mod-fabric-1.20.1-1.2.0.jar")
        stale.parentFile.mkdirs()
        stale.writeText("старая версия")

        val state = world.mod.install(world.profile)

        assertEquals("после установки сборка в порядке", CosmeticsModStatus.INSTALLED, state.status)
        assertEquals(
            "в папке лежит новый файл",
            "millida-mod-fabric-1.20.1-1.4.0.jar",
            state.installedFile,
        )
        assertFalse("старый файл убран", stale.exists())
        assertEquals(
            "в папке модов остался ровно один наш jar",
            1,
            world.mods.list().orEmpty().count { it.startsWith("millida-mod-") },
        )
    }

    @Test
    fun `сорванная загрузка не оставляет сборку без мода`() = runBlocking {
        val jar = "cosmetics jar payload".toByteArray()
        // The catalogue promises a digest the file does not have: the same thing
        // a truncated CDN answer looks like from here.
        serve(catalogJson(baseUrl = server.url("/mod").toString(), sha256 = sha256("другое".toByteArray())), jar)

        val world = world()
        val stale = File(world.mods, "millida-mod-fabric-1.20.1-1.2.0.jar")
        stale.parentFile.mkdirs()
        stale.writeText("старая версия")

        try {
            world.mod.install(world.profile)
            fail("установка с несовпавшим хешем обязана упасть, а не поставить мусор")
        } catch (expected: IOException) {
            assertTrue("сообщение говорит про файл: ${expected.message}", expected.message.orEmpty().isNotEmpty())
        }

        assertTrue("старый мод на месте — играть всё ещё можно", stale.exists())
        assertEquals("старая версия", stale.readText())
        assertFalse(
            "битого файла в папке нет",
            File(world.mods, "millida-mod-fabric-1.20.1-1.4.0.jar").exists(),
        )
    }

    @Test
    fun `выключение и включение переименовывают файл на месте`() = runBlocking {
        val jar = "cosmetics jar payload".toByteArray()
        serve(catalogJson(baseUrl = server.url("/mod").toString(), sha256 = sha256(jar)), jar)

        val world = world()
        world.mod.install(world.profile)

        val off = world.mod.setEnabled(world.profile, false)
        assertEquals("мод выключен", CosmeticsModStatus.DISABLED, off.status)
        assertTrue(
            "на диске остался тот же файл с пометкой",
            File(world.mods, "millida-mod-fabric-1.20.1-1.4.0.jar$DISABLED_SUFFIX").isFile,
        )

        val on = world.mod.setEnabled(world.profile, true)
        assertEquals("и включается обратно", CosmeticsModStatus.INSTALLED, on.status)

        val removed = world.mod.remove(world.profile)
        assertEquals("после удаления сборка снова без мода", CosmeticsModStatus.MISSING, removed.status)
        assertEquals(
            "наших файлов в папке не осталось",
            0,
            world.mods.list().orEmpty().count { it.startsWith("millida-mod-") },
        )
    }

    private class World(
        val mod: MillidaMod,
        val profile: Profile,
        val mods: File,
    )

    private fun world(): World {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        val store = ProfileStore(layout)
        val profile = Profile(
            id = "build",
            name = "Моя сборка",
            gameVersion = "1.20.1",
            loader = LoaderKind.FABRIC,
        )
        store.upsert(profile)
        val downloader = Downloader(attempts = 1)
        val mod = MillidaMod(layout, store, downloader, server.url("/mod/variants.json").toString())
        return World(mod, profile, File(store.directory(profile), "mods"))
    }

    private fun serve(manifest: String, jar: ByteArray) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().endsWith("variants.json")) {
                    MockResponse().setBody(manifest)
                } else {
                    MockResponse().setBody(okio.Buffer().write(jar))
                }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
