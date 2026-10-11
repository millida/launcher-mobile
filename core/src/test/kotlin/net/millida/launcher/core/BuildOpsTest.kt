package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.catalog.Catalog
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.catalog.CurseForgeCatalog
import net.millida.launcher.core.catalog.ModrinthCatalog
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.profile.BuildMetaStore
import net.millida.launcher.core.profile.BuildOps
import net.millida.launcher.core.profile.PlaytimeStore
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The build screen promises three things a player can check: a migration says
 * out loud what will not come along, a copy is a copy and not a shortcut, and
 * the hours played are not lost. Each case below is one of those promises.
 */
class BuildOpsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var layout: GameLayout
    private lateinit var store: ProfileStore
    private lateinit var ops: BuildOps
    private lateinit var profile: Profile

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = catalogDispatcher()
        server.start()

        layout = GameLayout(temp.newFolder()).also { it.ensure() }
        store = ProfileStore(layout)
        profile = Profile(
            id = "survival",
            name = "Выживание",
            gameVersion = "1.20.1",
            loader = LoaderKind.FABRIC,
            loaderVersion = "0.15.0",
        )
        store.upsert(profile)

        val downloader = Downloader(attempts = 1)
        val catalog = Catalog(
            downloader = downloader,
            modrinth = ModrinthCatalog(downloader, server.url("/launcher/mr/v2").toString().trimEnd('/')),
            curseforge = CurseForgeCatalog(downloader, server.url("/launcher/cf").toString().trimEnd('/')),
            mirrorBase = server.url("/launcher/dl").toString(),
        )
        ops = BuildOps(layout, store, catalog)
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun catalogDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.startsWith("/launcher/cf") -> MockResponse().setBody("""{"data":[]}""")

                path.contains("/project/") && path.contains("/version") -> MockResponse().setBody(
                    """
                    [{"id":"ver1","version_number":"0.6.0","game_versions":["1.21.4"],
                      "loaders":["fabric"],
                      "files":[{"url":"${server.url("/files/sodium-1.21.4.jar")}",
                                "filename":"sodium-1.21.4.jar","primary":true}]}]
                    """.trimIndent()
                )

                path.startsWith("/launcher/mr/v2/search") && path.contains("Sodium") -> MockResponse().setBody(
                    """{"hits":[{"project_id":"AANobbMI","slug":"sodium","title":"Sodium","downloads":10}]}"""
                )

                path.startsWith("/launcher/mr/v2/search") -> MockResponse().setBody("""{"hits":[]}""")

                path.startsWith("/files/") -> MockResponse().setBody("jar-bytes")

                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun modJar(dir: File, fileName: String, modId: String?, title: String = "") {
        val folder = File(dir, ContentKind.MOD.folder).also { it.mkdirs() }
        ZipOutputStream(File(folder, fileName).outputStream()).use { zip ->
            if (modId != null) {
                zip.putNextEntry(ZipEntry("fabric.mod.json"))
                zip.write("""{"id":"$modId","name":"$title","version":"1.0"}""".toByteArray())
                zip.closeEntry()
            } else {
                zip.putNextEntry(ZipEntry("readme.txt"))
                zip.write("собран руками".toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun fillBuild(): File {
        val dir = store.directory(profile)
        modJar(dir, "sodium.jar", "sodium", "Sodium")
        modJar(dir, "oldmod.jar", "oldmod", "Старый мод")
        modJar(dir, "manual.jar", null)
        File(dir, "saves/world").mkdirs()
        File(dir, "saves/world/level.dat").writeText("мир игрока")
        File(dir, "options.txt").writeText("fov:90")
        return dir
    }

    @Test
    fun `план переноса честно показывает, что не переедет`() = runBlocking {
        fillBuild()

        val plan = ops.migratePlan(profile, "1.21.4", LoaderKind.FABRIC)

        data class Case(val fileName: String, val ok: Boolean, val why: String)
        val expected = listOf(
            Case("sodium.jar", true, "у мода есть файл под 1.21.4 — он переедет"),
            Case("oldmod.jar", false, "каталог ничего не знает про мод, это надо сказать вслух"),
        )
        assertEquals("в плане ровно моды с идентификатором", expected.size, plan.items.size)
        expected.forEach { case ->
            val item = plan.items.first { it.fileName == case.fileName }
            assertEquals(case.why, case.ok, item.ok)
        }
        assertEquals("переедет один мод", 1, plan.ready)
        assertEquals("остаётся один мод", 1, plan.missing)
        assertTrue("переезжающие моды идут первыми", plan.items.first().ok)
        assertEquals(
            "мод без описания нельзя искать в каталоге — он в отдельном списке",
            listOf("manual.jar"),
            plan.unlinked,
        )
        assertEquals("имя подсказано с новой версией", "Выживание 1.21.4", plan.suggestedName)
        assertEquals("причина отказа доходит до игрока", "не нашли в каталоге", plan.items.last().note)
    }

    @Test
    fun `перенос не трогает исходную сборку`() = runBlocking {
        val source = fillBuild()

        val result = ops.migrate(profile, "1.21.4", LoaderKind.FABRIC, loaderVersion = "0.16.0")

        assertNotEquals("копия живёт в своей папке", profile.id, result.profile.id)
        assertEquals("переехал один мод", 1, result.moved)
        assertEquals("второй мод назван в отказах", 1, result.failed.size)

        val original = store.load().first { it.id == profile.id }
        assertEquals("версия исходной сборки не менялась", "1.20.1", original.gameVersion)
        assertEquals("загрузчик исходной сборки не менялся", "0.15.0", original.loaderVersion)
        assertTrue("моды исходной сборки на месте", File(source, "mods/sodium.jar").isFile)
        assertTrue("мод, который не переехал, остался у оригинала", File(source, "mods/oldmod.jar").isFile)
        assertEquals("мир оригинала не тронут", "мир игрока", File(source, "saves/world/level.dat").readText())

        val copy = File(layout.profiles, result.profile.id)
        assertEquals("мир переехал", "мир игрока", File(copy, "saves/world/level.dat").readText())
        assertEquals("настройки переехали", "fov:90", File(copy, "options.txt").readText())
        assertTrue("мод под новую версию скачан в копию", File(copy, "mods/sodium-1.21.4.jar").isFile)
        assertFalse("старый файл мода в копию не попал", File(copy, "mods/sodium.jar").isFile)
        assertEquals("копия стоит на новой версии", "1.21.4", result.profile.gameVersion)
        assertEquals("наигранное время у новой сборки своё", 0L, result.profile.playtimeSec)
    }

    @Test
    fun `дубликат не делит файлы с оригиналом`() {
        val source = fillBuild()

        val copy = ops.duplicate(profile)

        assertNotEquals("у копии свой идентификатор", profile.id, copy.id)
        val copyDir = File(layout.profiles, copy.id)
        val copied = File(copyDir, "saves/world/level.dat")
        assertEquals("содержимое скопировано", "мир игрока", copied.readText())

        copied.writeText("испорчено в копии")
        File(copyDir, "mods/sodium.jar").delete()

        assertEquals("правка копии не задела оригинал", "мир игрока", File(source, "saves/world/level.dat").readText())
        assertTrue("удаление мода в копии не задело оригинал", File(source, "mods/sodium.jar").isFile)
        assertEquals("обе сборки в списке", 2, store.load().size)
    }

    @Test
    fun `дубликат без контента берёт только настройки сборки`() {
        fillBuild()

        val copy = ops.duplicate(profile, withContent = false)
        val copyDir = File(layout.profiles, copy.id)

        assertEquals("версия та же", profile.gameVersion, copy.gameVersion)
        assertFalse("моды не копировались", File(copyDir, "mods/sodium.jar").exists())
        assertFalse("миры не копировались", File(copyDir, "saves/world/level.dat").exists())
    }

    @Test
    fun `группа сборки сохраняется и читается`() {
        val meta = BuildMetaStore(layout)

        meta.setGroup(profile.id, "  Приватки  ")
        meta.setGroup("other", "Приватки")
        meta.setIcon(profile.id, "pickaxe")

        assertEquals("группа записана без лишних пробелов", "Приватки", BuildMetaStore(layout).of(profile.id).group)
        assertEquals("иконка пережила перечитывание", "pickaxe", BuildMetaStore(layout).of(profile.id).icon)
        assertEquals("список групп без повторов", listOf("Приватки"), meta.groups())
        assertEquals("состав группы виден", listOf("other", profile.id), meta.inGroup("Приватки"))

        meta.setGroup(profile.id, "")
        assertEquals("пустая группа снимает метку", "", meta.of(profile.id).group)
        assertEquals("иконка при этом остаётся", "pickaxe", meta.of(profile.id).icon)
    }

    @Test
    fun `обложка копируется в папку сборки и снимается`() {
        val picked = temp.newFile("picked.png").apply { writeText("png-bytes") }
        val meta = BuildMetaStore(layout)

        meta.setCover(profile.id, picked)
        val stored = meta.coverFile(profile.id)
        assertTrue("обложка лежит в папке сборки", stored?.isFile == true)
        assertTrue("обложка внутри хранилища профилей", SafePath.isInside(layout.profiles, stored!!))

        picked.delete()
        assertTrue("удаление исходника не ломает обложку", meta.coverFile(profile.id)?.isFile == true)

        meta.clearCover(profile.id)
        assertEquals("после сброса обложки нет", null, BuildMetaStore(layout).coverFile(profile.id))
    }

    @Test
    fun `наигранное копится по кусочкам и переживает перезапись файла`() {
        val clock = longArrayOf(1_000)
        val playtime = PlaytimeStore(layout) { clock[0] }

        playtime.record(profile.id, 60, newSession = true)
        clock[0] = 1_060
        playtime.record(profile.id, 60)
        clock[0] = 1_085
        playtime.record(profile.id, 25)

        val reopened = PlaytimeStore(layout)
        assertEquals("куски одного запуска складываются", 145L, reopened.of(profile.id).seconds)
        assertEquals("один запуск остаётся одним запуском", 1L, reopened.of(profile.id).sessions)
        assertEquals("время последней игры записано", 1_085L, reopened.of(profile.id).lastAt)

        clock[0] = 2_000
        playtime.record(profile.id, 30, newSession = true)
        playtime.record("other", 10, newSession = true)

        val stats = PlaytimeStore(layout).stats()
        assertEquals("общий счёт по всем сборкам", 185L, stats.totalSeconds)
        assertEquals("запусков два у одной сборки и один у другой", 3L, stats.sessions)
        assertEquals("последней играли ту, что запускали позже", "other", stats.lastBuild)
        assertEquals("сборки идут по наигранному", profile.id, stats.builds.first().profileId)

        PlaytimeStore(layout).forget(profile.id)
        assertEquals("удалённая сборка не отдаёт часы новой с тем же именем", 0L, PlaytimeStore(layout).of(profile.id).seconds)
    }

    @Test
    fun `битый файл статистики читается как пустая статистика`() {
        File(layout.root, "playtime.json").writeText("{ это не json")
        File(layout.root, "build-meta.json").writeText("]]")

        val stats = PlaytimeStore(layout).stats()
        assertEquals("битый файл не выдумывает часы", 0L, stats.totalSeconds)
        assertTrue("список сборок пуст", stats.builds.isEmpty())
        assertEquals("группы тоже читаются пустыми", emptyList<String>(), BuildMetaStore(layout).groups())

        PlaytimeStore(layout).record(profile.id, 40, newSession = true)
        assertEquals("поверх битого файла пишется здоровый", 40L, PlaytimeStore(layout).of(profile.id).seconds)
    }
}
