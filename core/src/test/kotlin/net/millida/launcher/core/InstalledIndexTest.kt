package net.millida.launcher.core

import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.content.InstalledIndex
import net.millida.launcher.core.content.contentFolder
import net.millida.launcher.core.profile.ContentFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class InstalledIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, modId: String = "", title: String = "") = ContentFile(
        name = name,
        kind = ContentKind.MOD,
        sizeBytes = 1,
        enabled = !name.endsWith(".disabled"),
        modId = modId,
        title = title,
        version = "",
    )

    @Test
    fun `одно и то же имя в разных написаниях сводится к одному ключу`() {
        data class Case(val raw: String, val want: String, val why: String)

        val cases = listOf(
            Case("Fabric API", "fabricapi", "так пишет человек"),
            Case("fabric-api", "fabricapi", "так пишет Modrinth в slug"),
            Case("fabric_api", "fabricapi", "так пишет мод в своём id"),
            Case("Мод_1.2", "мод12", "кириллица остаётся буквами, а не выкидывается"),
            Case(" -_- ", "", "из одних знаков ключа не бывает"),
        )
        for (case in cases) {
            assertEquals("«${case.raw}»: ${case.why}", case.want, InstalledIndex.normalize(case.raw))
        }
    }

    @Test
    fun `имя файла без расширения и метки выключения`() {
        data class Case(val raw: String, val want: String, val why: String)

        val cases = listOf(
            Case("sodium-0.5.jar", "sodium-0.5", "расширение срезается"),
            Case("sodium-0.5.jar.disabled", "sodium-0.5", "выключенный мод узнаётся по тому же имени"),
            Case("a.b.zip", "a.b", "срезается только последнее расширение"),
            Case("noext", "noext", "без расширения имя не теряется"),
        )
        for (case in cases) {
            assertEquals("«${case.raw}»: ${case.why}", case.want, InstalledIndex.baseName(case.raw))
        }
    }

    @Test
    fun `зависимость считается стоящей по id, названию или имени файла`() {
        val index = InstalledIndex.of(
            listOf(
                file("fabric-api-0.92.jar", modId = "fabric-api", title = "Fabric API"),
                file("cloth-config.jar.disabled", title = "Cloth Config"),
                file("homemade.jar", title = "Самосбор"),
            )
        )

        data class Case(val names: List<String>, val want: Boolean, val why: String)

        val cases = listOf(
            Case(listOf("fabric_api"), true, "id из jar совпал после нормализации"),
            Case(listOf("P7dR8mSH", "Fabric API"), true, "хватает одного совпавшего имени из нескольких"),
            Case(listOf("cloth config"), true, "название выключенного мода тоже считается — его не ставят второй раз"),
            Case(listOf("homemade"), true, "имя файла без расширения"),
            Case(listOf("fabric"), false, "частичное совпадение не считается: иначе Fabric API закрыл бы любой fabric-мод"),
            Case(listOf("", "  ", "--"), false, "пустые имена ничего не удовлетворяют"),
            Case(emptyList(), false, "без имён ответ — нет"),
        )
        for (case in cases) {
            assertEquals("${case.names}: ${case.why}", case.want, index.has(*case.names.toTypedArray()))
        }
    }

    @Test
    fun `поставленное по ходу запоминается, пустое — нет`() {
        val index = InstalledIndex.of(emptyList())
        assertFalse(index.has("Some Lib"))

        index.remember("LIB", "Some Lib", "")

        assertTrue("после установки зависимость второй раз не ставится", index.has("some-lib"))
        assertTrue("id тоже запомнен", index.has("lib"))
        assertFalse("пустое имя не превращается в совпадение со всем", index.has(""))
    }

    @Test
    fun `папка контента проверяется до любого чтения`() {
        val layout = GameLayout(tmp.newFolder("store")).also { it.ensure() }
        val inside = layout.profileDir("build").also { it.mkdirs() }

        assertEquals(
            "папка модов лежит внутри сборки",
            File(inside, "mods").canonicalPath,
            contentFolder(layout, inside, ContentKind.MOD).canonicalPath,
        )
        assertEquals(
            "шейдеры — в shaderpacks",
            File(inside, "shaderpacks").canonicalPath,
            contentFolder(layout, inside, ContentKind.SHADER).canonicalPath,
        )

        val escapes = listOf(
            File(layout.profiles, "../evil") to "выход через .. из хранилища профилей",
            tmp.newFolder("elsewhere") to "папка вообще вне хранилища",
        )
        for ((dir, why) in escapes) {
            val result = runCatching { contentFolder(layout, dir, ContentKind.MOD) }
            assertTrue("$why — должно быть отклонено", result.exceptionOrNull() is IOException)
        }
    }
}
