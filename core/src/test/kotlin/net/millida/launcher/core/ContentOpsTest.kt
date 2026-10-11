package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.catalog.Catalog
import net.millida.launcher.core.catalog.CatalogFile
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.catalog.CurseForgeCatalog
import net.millida.launcher.core.catalog.ModrinthCatalog
import net.millida.launcher.core.content.CatalogLookup
import net.millida.launcher.core.content.ContentDependencies
import net.millida.launcher.core.content.ContentSafety
import net.millida.launcher.core.content.ContentUpdates
import net.millida.launcher.core.content.LocalContentImport
import net.millida.launcher.core.content.SafetyVerdict
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileContent
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The three operations a build survives or dies by: updating what is installed,
 * pulling in what a mod needs, and refusing a file that would not load. Every
 * case below is written as "вход -> вердикт" with the reason it is pinned.
 */
class ContentOpsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var layout: GameLayout
    private lateinit var profileDir: File

    private val fabricProfile = Profile(
        id = "build",
        name = "Сборка",
        gameVersion = "1.20.1",
        loader = LoaderKind.FABRIC,
    )

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        layout = GameLayout(temp.newFolder()).also { it.ensure() }
        profileDir = layout.profileDir("build").also { it.mkdirs() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    // region инфраструктура

    private fun modrinthBase(): String = server.url("/mr/v2").toString().trimEnd('/')

    private fun catalog(downloader: Downloader): Catalog = Catalog(
        downloader = downloader,
        modrinth = ModrinthCatalog(downloader, modrinthBase()),
        curseforge = CurseForgeCatalog(downloader, server.url("/cf").toString().trimEnd('/')),
        mirrorBase = server.url("/dl").toString().trimEnd('/'),
    )

    private fun lookup(downloader: Downloader) = CatalogLookup(downloader, modrinthBase())

    private fun archive(target: File, entries: List<Pair<String, ByteArray>>): File {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (path, body) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return target
    }

    private fun fabricJar(target: File, id: String, name: String, version: String, minecraft: String = ">=1.20.1"): File =
        archive(
            target,
            listOf(
                "fabric.mod.json" to
                    """{"schemaVersion":1,"id":"$id","name":"$name","version":"$version","depends":{"minecraft":"$minecraft"}}"""
                        .toByteArray(),
                "$id/Main.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
            )
        )

    private fun forgeJar(target: File, id: String, name: String): File = archive(
        target,
        listOf(
            "META-INF/mods.toml" to """
                modLoader="javafml"
                loaderVersion="[47,)"
                [[mods]]
                modId="$id"
                displayName="$name"
                version="15.0.0"
                [[dependencies.$id]]
                modId="minecraft"
                versionRange="[1.20.1,1.21)"
            """.trimIndent().toByteArray(),
            "$id/Main.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
        )
    )

    private fun modsFolder(): File = File(profileDir, ContentKind.MOD.folder).also { it.mkdirs() }

    private fun modFileNames(): List<String> =
        modsFolder().listFiles().orEmpty().map { it.name }.sorted()

    private fun versionJson(
        id: String,
        number: String,
        fileName: String,
        url: String,
        dependencies: String = "",
    ): String = """
        {"id":"$id","name":"$number","version_number":"$number",
         "game_versions":["1.20.1"],"loaders":["fabric"],"version_type":"release",
         "files":[{"url":"$url","filename":"$fileName","size":0,"hashes":{},"primary":true}],
         "dependencies":[$dependencies]}
    """.trimIndent()

    private fun projectJson(id: String, slug: String, title: String): String =
        """{"id":"$id","slug":"$slug","title":"$title"}"""

    private fun serve(routes: (RecordedRequest) -> MockResponse?) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes(request) ?: MockResponse().setResponseCode(404)
        }
    }

    // endregion

    @Test
    fun `jar под Forge не ставится в сборку Fabric без согласия`() = runBlocking {
        data class Case(
            val file: File,
            val consentNeeded: Boolean,
            val why: String,
        )

        val incoming = temp.newFolder("incoming")
        val cases = listOf(
            Case(
                fabricJar(File(incoming, "sodium.jar"), "sodium", "Sodium", "0.5.0"),
                consentNeeded = false,
                why = "мод под тот же загрузчик ставится молча — иначе игрок привыкнет жать «всё равно»",
            ),
            Case(
                forgeJar(File(incoming, "jei.jar"), "jei", "JEI"),
                consentNeeded = true,
                why = "с Forge-модом сборка Fabric не запустится — это ловилось только в игре",
            ),
            Case(
                fabricJar(File(incoming, "old.jar"), "old", "Старый", "1.0.0", minecraft = "1.16.5"),
                consentNeeded = true,
                why = "мод под другую версию игры — предупреждение, а не запрет",
            ),
        )

        val import = LocalContentImport(layout)
        for (case in cases) {
            val candidate = import.identify(case.file, fabricProfile)
            assertTrue("${case.file.name} должен опознаться как файл сборки", candidate.accepted)
            assertEquals(
                "${case.file.name}: согласие должно требоваться=${case.consentNeeded}. Зачем закреплено: ${case.why}",
                case.consentNeeded,
                candidate.needsConsent,
            )
            assertEquals("${case.file.name} — это мод", ContentKind.MOD, candidate.kind)

            if (case.consentNeeded) {
                val refused = runCatching { import.add(profileDir, candidate) }
                assertTrue("${case.file.name} без согласия ставиться не должен", refused.isFailure)
                assertFalse(
                    "отказ не должен оставлять файл в сборке",
                    File(modsFolder(), case.file.name).exists(),
                )
            }
            val added = import.add(profileDir, candidate, consent = true)
            assertTrue("${case.file.name} с согласия ставится", added.isFile)
        }

        assertEquals(
            "в сборке лежат ровно согласованные файлы",
            listOf("jei.jar", "old.jar", "sodium.jar"),
            modFileNames(),
        )
    }

    @Test
    fun `не-jar отклоняется`() = runBlocking {
        val incoming = temp.newFolder("junk")
        val cases = listOf(
            File(incoming, "readme.txt").apply { writeText("просто текст") } to "у файла нет расширения архива",
            File(incoming, "broken.jar").apply { writeText("это не zip") } to "файл с расширением jar, но не архив",
            File(incoming, "empty.zip").apply { writeText("") } to "пустышка не открывается как архив",
        )

        val import = LocalContentImport(layout)
        for ((file, why) in cases) {
            val candidate = import.identify(file, fabricProfile)
            assertFalse("${file.name} принимать нельзя: $why", candidate.accepted)
            assertTrue("${file.name}: отказ должен объяснять причину", candidate.rejection.isNotEmpty())
            val refused = runCatching { import.add(profileDir, candidate, consent = true) }
            assertTrue("${file.name}: даже с согласия не ставится", refused.isFailure)
        }
        assertTrue("в папке модов ничего не появилось", modFileNames().isEmpty())
    }

    @Test
    fun `обновление не удаляет старый файл при провале скачивания`() = runBlocking {
        val old = fabricJar(File(modsFolder(), "sodium-0.5.jar"), "sodium", "Sodium", "0.5.0")
        val newBody = fabricJar(File(temp.newFolder("cdn"), "sodium-0.6.jar"), "sodium", "Sodium", "0.6.0")
        val downloadUrl = server.url("/files/sodium-0.6.jar").toString()

        var downloadFails = true
        serve { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/mr/v2/version_file/") -> MockResponse()
                    .setBody("""{"id":"v-old","project_id":"P1","version_number":"0.5.0"}""")

                path.startsWith("/mr/v2/project/P1/version") -> MockResponse()
                    .setBody("[" + versionJson("v-new", "0.6.0", "sodium-0.6.jar", downloadUrl) + "]")

                path.startsWith("/mr/v2/project/P1") -> MockResponse()
                    .setBody(projectJson("P1", "sodium", "Sodium"))

                path.startsWith("/files/") || path.startsWith("/dl") ->
                    if (downloadFails) {
                        MockResponse().setResponseCode(500)
                    } else {
                        MockResponse().setBody(okio.Buffer().write(newBody.readBytes()))
                    }

                else -> null
            }
        }

        val downloader = Downloader(attempts = 1)
        val updates = ContentUpdates(layout, catalog(downloader), lookup(downloader))

        val plan = updates.plan(fabricProfile, profileDir)
        assertEquals("обновление нашлось ровно одно", 1, plan.candidates.size)
        val candidate = plan.candidates.first()
        assertEquals("видно, с какой версии обновляемся", "0.5.0", candidate.fromVersion)
        assertEquals("видно, на какую версию обновляемся", "0.6.0", candidate.toVersion)

        val failed = updates.apply(profileDir, ContentKind.MOD, candidate)
        assertFalse("провал скачивания должен быть провалом, а не тихим успехом", failed.ok)
        assertTrue("старый мод остаётся на месте — иначе игрок остаётся без мода", old.isFile)
        assertEquals(
            "в папке нет ни огрызков, ни потерь",
            listOf("sodium-0.5.jar"),
            modFileNames(),
        )

        downloadFails = false
        val done = updates.apply(profileDir, ContentKind.MOD, candidate)
        assertTrue("удачное обновление доводится до конца: ${done.error}", done.ok)
        assertEquals(
            "старый файл убирается только после того, как новый лёг на диск",
            listOf("sodium-0.6.jar"),
            modFileNames(),
        )
    }

    @Test
    fun `план зависимостей не зацикливается и не предлагает уже стоящее`() = runBlocking {
        fabricJar(File(modsFolder(), "fabric-api-0.92.jar"), "fabric-api", "Fabric API", "0.92.0")

        serve { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/mr/v2/project/ROOT/version") -> MockResponse().setResponseCode(404)
                path.startsWith("/mr/v2/project/ROOT") -> MockResponse()
                    .setBody(projectJson("ROOT", "root-mod", "Корневой мод"))

                path.startsWith("/mr/v2/project/LIB/version") -> MockResponse().setBody(
                    "[" + versionJson(
                        id = "v-lib",
                        number = "2.0.0",
                        fileName = "lib-2.0.0.jar",
                        url = server.url("/files/lib-2.0.0.jar").toString(),
                        dependencies = """
                            {"project_id":"ROOT","dependency_type":"required"},
                            {"project_id":"FAPI","dependency_type":"required"}
                        """.trimIndent(),
                    ) + "]"
                )

                path.startsWith("/mr/v2/project/LIB") -> MockResponse()
                    .setBody(projectJson("LIB", "some-lib", "Some Lib"))

                path.startsWith("/mr/v2/project/FAPI/version") -> MockResponse().setBody(
                    "[" + versionJson("v-fapi", "0.92.0", "fabric-api-0.92.jar", server.url("/files/f.jar").toString()) + "]"
                )

                path.startsWith("/mr/v2/project/FAPI") -> MockResponse()
                    .setBody(projectJson("FAPI", "fabric-api", "Fabric API"))

                else -> null
            }
        }

        val downloader = Downloader(attempts = 1)
        val deps = ContentDependencies(layout, catalog(downloader), lookup(downloader))

        val root = CatalogVersion(
            id = "v-root",
            name = "1.0.0",
            versionNumber = "1.0.0",
            gameVersions = listOf("1.20.1"),
            loaders = listOf("fabric"),
            files = listOf(CatalogFile(url = "https://example.invalid/root.jar", filename = "root.jar", primary = true)),
            dependencies = listOf(
                CatalogVersion.Dependency(projectId = "LIB", dependencyType = "required"),
                CatalogVersion.Dependency(projectId = "FAPI", dependencyType = "required"),
                CatalogVersion.Dependency(projectId = "NICE", dependencyType = "optional"),
            ),
            projectId = "ROOT",
            source = CatalogSource.MODRINTH,
        )

        val plan = deps.plan(fabricProfile, profileDir, root)

        assertEquals(
            "ставить надо только то, чего в сборке нет",
            listOf("Some Lib"),
            plan.missing.map { it.title },
        )
        assertEquals(
            "уже стоящая зависимость названа удовлетворённой, а не предложена второй раз",
            listOf("Fabric API"),
            plan.satisfied,
        )
        assertTrue(
            "взаимная зависимость не должна раскручиваться в бесконечный список",
            plan.missing.none { it.projectId == "ROOT" },
        )
        assertFalse("граф такого размера обрезать нечего", plan.truncated)
        assertTrue("необязательные зависимости в обязательный список не лезут", plan.unresolved.isEmpty())
        assertEquals("видно, кто затянул зависимость", "Корневой мод", plan.missing.first().requiredBy)
    }

    @Test
    fun `проверка безопасности отличает находку от неизвестного файла`() {
        data class Case(val file: String, val want: SafetyVerdict, val why: String)

        fabricJar(File(modsFolder(), "sodium.jar"), "sodium", "Sodium", "0.5.0")
        forgeJar(File(modsFolder(), "jei.jar"), "jei", "JEI")
        File(modsFolder(), "corrupt.jar").writeText("это не архив")
        archive(
            File(modsFolder(), "loader.jar"),
            listOf(
                "net/evil/Main.class" to
                    ("Êþº¾ defineClass pastebin.com".toByteArray(Charsets.ISO_8859_1)),
            )
        )
        archive(
            File(modsFolder(), "plain.jar"),
            listOf("assets/pack/icon.png" to byteArrayOf(0x89.toByte(), 'P'.code.toByte()))
        )

        val cases = listOf(
            Case("sodium.jar", SafetyVerdict.OK, "мод под свой загрузчик и свою версию — обычный мод"),
            Case("jei.jar", SafetyVerdict.WRONG_LOADER, "реальный баг с десктопа: файл собран под другой загрузчик"),
            Case("corrupt.jar", SafetyVerdict.BROKEN, "битый файл нельзя выдавать за установленный мод"),
            Case("loader.jar", SafetyVerdict.SUSPICIOUS, "два сильных признака — это уже находка"),
            Case("plain.jar", SafetyVerdict.UNKNOWN, "самосбор без описания — не обвинение, просто неизвестен"),
        )

        val safety = ContentSafety(layout)
        val report = safety.scan(fabricProfile, profileDir)
        assertEquals("проверены все файлы", cases.size, report.checked)

        for (case in cases) {
            val verdict = report.items.first { it.file.name == case.file }
            assertEquals(
                "${case.file} должен получить «${case.want.label}», получил «${verdict.verdict.label}». " +
                    "Зачем случай закреплён: ${case.why}",
                case.want,
                verdict.verdict,
            )
            if (case.want != SafetyVerdict.OK) {
                assertTrue("${case.file}: вердикт без объяснения бесполезен", verdict.reasons.isNotEmpty())
            }
        }

        val quarantined = safety.quarantine(profileDir, report.toQuarantine())
        assertEquals(
            "в карантин уезжают только находки, а не весь самосбор",
            listOf("corrupt.jar", "jei.jar", "loader.jar"),
            quarantined.disabled.sorted(),
        )
        assertTrue("карантин отключает переименованием, а не удалением", quarantined.failed.isEmpty())
        assertEquals(
            "ни один файл не пропал с диска",
            listOf("corrupt.jar.disabled", "jei.jar.disabled", "loader.jar.disabled", "plain.jar", "sodium.jar"),
            modFileNames(),
        )

        val after = ProfileContent(layout).list(profileDir, ContentKind.MOD)
        assertEquals("выключенных ровно три", 3, after.count { !it.enabled })
    }

    @Test
    fun `обновление обходит файл, которого нет ни в одном каталоге`() = runBlocking {
        fabricJar(File(modsFolder(), "homemade.jar"), "homemade", "Самосбор", "1.0.0")
        serve { MockResponse().setResponseCode(404) }

        val downloader = Downloader(attempts = 1)
        val plan = ContentUpdates(layout, catalog(downloader), lookup(downloader))
            .plan(fabricProfile, profileDir)

        assertTrue("обновлять нечего", plan.empty)
        assertEquals(
            "неопознанный файл показывается отдельно, а не выдаётся за актуальный",
            listOf("homemade.jar"),
            plan.unrecognised.map { it.name },
        )
    }

    private fun assertUnused(ignored: IOException) = Unit
}
