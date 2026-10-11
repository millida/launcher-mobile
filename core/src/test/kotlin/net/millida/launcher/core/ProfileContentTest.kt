package net.millida.launcher.core

import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.profile.ContentFile
import net.millida.launcher.core.profile.ProfileContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProfileContentTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var layout: GameLayout
    private lateinit var profileDir: File
    private lateinit var content: ProfileContent

    private fun prepare() {
        layout = GameLayout(temp.newFolder()).also { it.ensure() }
        profileDir = layout.profileDir("build").also { it.mkdirs() }
        content = ProfileContent(layout)
    }

    private fun archive(kind: ContentKind, name: String, vararg entries: Pair<String, String>): File {
        val folder = File(profileDir, kind.folder).also { it.mkdirs() }
        val target = File(folder, name)
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (path, body) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return target
    }

    @Test
    fun `состояние мода определяется расширением файла`() {
        prepare()
        data class Case(val fileName: String, val enabled: Boolean, val why: String)

        val cases = listOf(
            Case("sodium.jar", true, "обычный jar включён"),
            Case("iris.jar.disabled", false, "суффикс disabled выключает мод"),
            Case("pack.zip", true, "zip тоже считается содержимым"),
            Case("old.zip.disabled", false, "выключенный zip не теряется из списка"),
        )
        cases.forEach { archive(ContentKind.MOD, it.fileName) }
        File(profileDir, "mods/readme.txt").writeText("не архив")

        val listed = content.list(profileDir, ContentKind.MOD)

        assertEquals("посторонние файлы в список не попадают", cases.size, listed.size)
        for (case in cases) {
            val found = listed.first { it.name == case.fileName }
            assertEquals(case.why, case.enabled, found.enabled)
        }
    }

    @Test
    fun `переключение мода переименовывает файл и не теряет его`() {
        prepare()
        val jar = archive(ContentKind.MOD, "sodium.jar")
        val bytes = jar.readBytes()
        val listed = content.list(profileDir, ContentKind.MOD).single()

        val off = content.setEnabled(profileDir, listed, false)

        assertEquals("выключение добавляет суффикс", "sodium.jar.disabled", off.name)
        assertFalse("модель обязана отражать новое состояние", off.enabled)
        assertFalse("файла под старым именем быть не должно", jar.exists())
        val disabled = File(profileDir, "mods/sodium.jar.disabled")
        assertTrue("выключенный файл обязан существовать", disabled.isFile)
        assertArrayEqualsBytes("содержимое мода не должно меняться", bytes, disabled.readBytes())

        val on = content.setEnabled(profileDir, off, true)

        assertEquals("включение снимает суффикс", "sodium.jar", on.name)
        assertTrue("модель обязана отражать новое состояние", on.enabled)
        assertTrue("файл вернулся под исходным именем", File(profileDir, "mods/sodium.jar").isFile)
        assertArrayEqualsBytes(
            "после двух переключений файл обязан быть тем же",
            bytes,
            File(profileDir, "mods/sodium.jar").readBytes(),
        )

        val again = content.setEnabled(profileDir, on, true)
        assertEquals("повторное включение ничего не меняет", on, again)
    }

    @Test
    fun `переключение через занятое имя не удаляет ни один из файлов`() {
        prepare()
        archive(ContentKind.MOD, "sodium.jar", "a" to "новый")
        archive(ContentKind.MOD, "sodium.jar.disabled", "a" to "старый")
        val enabled = content.list(profileDir, ContentKind.MOD).first { it.name == "sodium.jar" }

        try {
            content.setEnabled(profileDir, enabled, false)
            fail("переименование поверх существующего файла должно отклоняться")
        } catch (expected: IOException) {
            assertTrue("ошибка обязана называть занятое имя", expected.message!!.contains("sodium.jar.disabled"))
        }

        assertTrue("включённый файл обязан остаться", File(profileDir, "mods/sodium.jar").isFile)
        assertTrue(
            "выключенный файл обязан остаться",
            File(profileDir, "mods/sodium.jar.disabled").isFile,
        )
    }

    @Test
    fun `метаданные читаются из самого архива`() {
        prepare()
        archive(
            ContentKind.MOD,
            "sodium-fabric.jar",
            "fabric.mod.json" to """
                {"schemaVersion":1,"id":"sodium","name":"Sodium","version":"0.6.0",
                 "environment":"client","depends":{"fabricloader":">=0.15"}}
            """.trimIndent(),
        )
        archive(
            ContentKind.MOD,
            "jei-forge.jar",
            "META-INF/mods.toml" to """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="MIT"

                [[mods]]
                modId="jei"
                version="${'$'}{file.jarVersion}"
                displayName="Just Enough Items"
                description='''Рецепты'''
            """.trimIndent(),
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\nImplementation-Version: 15.2.0.27\r\n\r\n",
        )
        archive(
            ContentKind.MOD,
            "neo-thing.jar",
            "META-INF/neoforge.mods.toml" to """
                [[mods]]
                modId = "neothing"
                displayName = "Neo Thing"
                version = "2.1"
            """.trimIndent(),
        )
        archive(ContentKind.MOD, "unknown-mod-1.2.jar", "data/x.json" to "{}")

        data class Case(val fileName: String, val modId: String, val title: String, val version: String, val why: String)

        val cases = listOf(
            Case("sodium-fabric.jar", "sodium", "Sodium", "0.6.0", "fabric.mod.json даёт id, имя и версию"),
            Case(
                "jei-forge.jar",
                "jei",
                "Just Enough Items",
                "15.2.0.27",
                "подстановка \${file.jarVersion} берётся из манифеста, иначе игрок видит шаблон вместо версии",
            ),
            Case("neo-thing.jar", "neothing", "Neo Thing", "2.1", "neoforge.mods.toml читается так же, как mods.toml"),
            Case("unknown-mod-1.2.jar", "", "unknown-mod-1.2", "", "без описания название берётся из имени файла"),
        )

        val listed = content.list(profileDir, ContentKind.MOD).associateBy { it.name }
        for (case in cases) {
            val found = listed.getValue(case.fileName)
            assertEquals("${case.why}: modId", case.modId, found.modId)
            assertEquals("${case.why}: title", case.title, found.title)
            assertEquals("${case.why}: version", case.version, found.version)
        }
    }

    @Test
    fun `удаление убирает только выбранный файл`() {
        prepare()
        archive(ContentKind.SHADER, "complementary.zip")
        archive(ContentKind.SHADER, "bsl.zip")
        val victim = content.list(profileDir, ContentKind.SHADER).first { it.name == "bsl.zip" }

        assertTrue("удаление обязано подтвердиться", content.delete(profileDir, victim))

        val left = content.list(profileDir, ContentKind.SHADER).map { it.name }
        assertEquals("остальные шейдеры трогать нельзя", listOf("complementary.zip"), left)
        assertFalse("повторное удаление ничего не находит", content.delete(profileDir, victim))
    }

    @Test
    fun `папка сборки вне хранилища профилей отклоняется`() {
        prepare()
        val outside = temp.newFolder("чужая")
        File(outside, "mods").mkdirs()

        try {
            content.list(outside, ContentKind.MOD)
            fail("чтение папки вне хранилища профилей должно отклоняться")
        } catch (expected: IOException) {
            assertTrue("ошибка обязана быть внятной", expected.message!!.isNotBlank())
        }
    }

    @Test
    fun `пустая папка содержимого читается как пустой список`() {
        prepare()
        assertEquals(
            "отсутствие папки mods это не ошибка, а сборка без модов",
            emptyList<ContentFile>(),
            content.list(profileDir, ContentKind.MOD),
        )
    }

    private fun assertArrayEqualsBytes(message: String, expected: ByteArray, actual: ByteArray) {
        assertTrue(message, expected.contentEquals(actual))
    }
}
