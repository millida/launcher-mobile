package net.millida.launcher.core

import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.content.ContentSafety
import net.millida.launcher.core.content.SafetyVerdict
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.profile.Profile
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

class ContentSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var layout: GameLayout
    private lateinit var profileDir: File

    private val classMagic = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())

    private fun profile(loader: LoaderKind, game: String = "1.20.1") =
        Profile(id = "build", name = "Сборка", gameVersion = game, loader = loader)

    @Before
    fun start() {
        layout = GameLayout(tmp.newFolder("store")).also { it.ensure() }
        profileDir = layout.profileDir("build").also { it.mkdirs() }
    }

    private fun folder(kind: ContentKind): File = File(profileDir, kind.folder).also { it.mkdirs() }

    private fun archive(target: File, entries: List<Pair<String, ByteArray>>): File {
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (path, body) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return target
    }

    private fun fabricMod(name: String, id: String, title: String, minecraft: String = ">=1.20", classBody: ByteArray = classMagic): File =
        archive(
            File(folder(ContentKind.MOD), name),
            listOf(
                "fabric.mod.json" to """{"id":"$id","name":"$title","depends":{"minecraft":"$minecraft"}}""".toByteArray(),
                "$id/Main.class" to classBody,
            ),
        )

    private fun verdictOf(report: net.millida.launcher.core.content.SafetyReport, name: String): SafetyVerdict =
        report.items.first { it.file.name == name }.verdict

    @Test
    fun `вердикт зависит от сборки, в которую положен мод`() {
        data class Case(val profile: Profile, val want: SafetyVerdict, val why: String)

        fabricMod("sodium.jar", "sodium", "Sodium", minecraft = ">=1.20")
        val cases = listOf(
            Case(profile(LoaderKind.FABRIC), SafetyVerdict.OK, "свой загрузчик и своя версия"),
            Case(profile(LoaderKind.QUILT), SafetyVerdict.OK, "Quilt грузит Fabric-моды — ложная тревога отучила бы читать отчёт"),
            Case(profile(LoaderKind.VANILLA), SafetyVerdict.WRONG_LOADER, "ваниль мод не загрузит"),
            Case(profile(LoaderKind.FORGE), SafetyVerdict.WRONG_LOADER, "Fabric-мод на Forge падает при запуске"),
            Case(profile(LoaderKind.FABRIC, "1.19.4"), SafetyVerdict.WRONG_GAME_VERSION, "мод требует 1.20 и новее"),
        )

        val safety = ContentSafety(layout)
        for (case in cases) {
            val report = safety.scan(case.profile, profileDir)
            val verdict = report.items.single()
            assertEquals(
                "${case.profile.label}: ждали «${case.want.label}», получили «${verdict.verdict.label}». Зачем: ${case.why}",
                case.want,
                verdict.verdict,
            )
        }
    }

    @Test
    fun `чужая версия игры объясняется словами`() {
        fabricMod("old.jar", "old", "Old", minecraft = "1.16.5")

        val verdict = ContentSafety(layout).scan(profile(LoaderKind.FABRIC), profileDir).items.single()

        assertEquals(SafetyVerdict.WRONG_GAME_VERSION, verdict.verdict)
        assertEquals(
            "первая причина называет обе версии, иначе игрок не поймёт, что менять",
            "Мод собран под 1.16.5, а сборка на 1.20.1",
            verdict.reasons.first(),
        )
        assertFalse("чужая версия — не повод для карантина", verdict.quarantinable)
    }

    @Test
    fun `одна сильная находка без других — ещё не обвинение`() {
        data class Case(val name: String, val body: ByteArray, val want: SafetyVerdict, val why: String)

        val cases = listOf(
            Case("one.jar", classMagic + "pastebin.com".toByteArray(), SafetyVerdict.OK, "одиночный признак встречается и у честных модов"),
            Case(
                "two.jar",
                classMagic + "pastebin.com ProcessBuilder".toByteArray(),
                SafetyVerdict.SUSPICIOUS,
                "сильный признак плюс ещё один — уже находка",
            ),
            Case("weak.jar", classMagic + "ProcessBuilder java/awt/Robot".toByteArray(), SafetyVerdict.OK, "слабые признаки сами по себе не находка"),
        )
        cases.forEachIndexed { index, case -> fabricMod(case.name, "mod$index", "Mod $index", classBody = case.body) }

        val report = ContentSafety(layout).scan(profile(LoaderKind.FABRIC), profileDir)
        for (case in cases) {
            assertEquals("${case.name}: ${case.why}", case.want, verdictOf(report, case.name))
        }
        val okWithReason = report.items.first { it.file.name == "one.jar" }
        assertTrue("признак у обычного мода всё равно показан", okWithReason.reasons.isNotEmpty())
    }

    @Test
    fun `отчёт отсортирован от худшего и считает только проблемы`() {
        fabricMod("b-ok.jar", "bok", "B Ok")
        fabricMod("a-ok.jar", "aok", "A Ok")
        fabricMod("old.jar", "old", "Old", minecraft = "1.16.5")
        archive(File(folder(ContentKind.MOD), "plain.jar"), listOf("assets/x.png" to byteArrayOf(1)))
        File(folder(ContentKind.MOD), "corrupt.jar").writeText("не архив")

        val report = ContentSafety(layout).scan(profile(LoaderKind.FABRIC), profileDir)

        assertEquals(
            "сначала то, что надо чинить, потом безобидное; внутри — по алфавиту",
            listOf(SafetyVerdict.BROKEN, SafetyVerdict.WRONG_GAME_VERSION, SafetyVerdict.UNKNOWN, SafetyVerdict.OK, SafetyVerdict.OK),
            report.items.map { it.verdict },
        )
        assertEquals("одинаковые вердикты идут по названию", listOf("A Ok", "B Ok"), report.items.takeLast(2).map { it.file.title })
        assertEquals("проверено пять файлов", 5, report.checked)
        assertEquals("файл без описания — не проблема, проблем две", 2, report.problems)
    }

    @Test
    fun `выключенный файл не попадает в карантин повторно`() {
        File(folder(ContentKind.MOD), "bad.jar.disabled").writeText("не архив")
        File(folder(ContentKind.MOD), "worse.jar").writeText("не архив")

        val safety = ContentSafety(layout)
        val report = safety.scan(profile(LoaderKind.FABRIC), profileDir)

        assertEquals("оба битые", listOf(SafetyVerdict.BROKEN, SafetyVerdict.BROKEN), report.items.map { it.verdict })
        assertEquals(
            "в карантин просится только включённый",
            listOf("worse.jar"),
            report.toQuarantine().map { it.file.name },
        )

        val result = safety.quarantine(profileDir, report.items)
        assertEquals("выключенный пропущен, а не переименован второй раз", listOf("worse.jar"), result.disabled)
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `сбой карантина виден и файл остаётся на месте`() {
        File(folder(ContentKind.MOD), "dup.jar").writeText("не архив")
        File(folder(ContentKind.MOD), "dup.jar.disabled").writeText("старая копия")

        val safety = ContentSafety(layout)
        val report = safety.scan(profile(LoaderKind.FABRIC), profileDir)
        val result = safety.quarantine(profileDir, report.toQuarantine())

        assertTrue("переименовать поверх чужого файла нельзя", result.disabled.isEmpty())
        assertEquals("сбой назван по файлу", listOf("dup.jar"), result.failed.keys.toList())
        assertTrue("причина сбоя не пустая", result.failed.getValue("dup.jar").isNotBlank())
        assertTrue("исходный файл не пропал", File(folder(ContentKind.MOD), "dup.jar").isFile)
        assertEquals("старая копия не затёрта", "старая копия", File(folder(ContentKind.MOD), "dup.jar.disabled").readText())
    }

    @Test
    fun `ресурспак не требует описания мода`() {
        archive(
            File(folder(ContentKind.RESOURCE_PACK), "faithful.zip"),
            listOf("pack.mcmeta" to "{}".toByteArray(), "assets/minecraft/textures/a.png" to byteArrayOf(1)),
        )

        val report = ContentSafety(layout).scan(profile(LoaderKind.VANILLA), profileDir, ContentKind.RESOURCE_PACK)

        assertEquals("ресурспак на ванили — обычное дело", SafetyVerdict.OK, report.items.single().verdict)
        assertEquals(0, report.problems)
    }

    @Test
    fun `сборка вне хранилища не проверяется`() {
        val outside = tmp.newFolder("outside")
        val result = runCatching { ContentSafety(layout).scan(profile(LoaderKind.FABRIC), outside) }
        assertTrue("папка из интерфейса вне хранилища должна быть отклонена", result.exceptionOrNull() is IOException)
    }
}
