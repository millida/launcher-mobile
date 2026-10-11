package net.millida.launcher.core

import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.packs.PackNames
import net.millida.launcher.core.packs.PackOrigin
import net.millida.launcher.core.packs.PackOriginStore
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A pack is listed under its own name and shows the game's version, as on the
 * desktop. What is pinned: the version of the pack joins the name only to
 * tell two builds apart, and old automatic names are fixed without touching
 * names the player typed or the build folder.
 */
class PackNamesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `имя новой сборки`() {
        data class Case(val pack: String, val version: String, val taken: Set<String>, val expected: String, val why: String)

        val cases = listOf(
            Case("OneBlock", "26.10.21", emptySet(), "OneBlock", "версия сборки не попадает в название, версию игры экран показывает сам"),
            Case("  OneBlock ", "26.10.21", emptySet(), "OneBlock", "пробелы из каталога не попадают в список"),
            Case("OneBlock", "26.10.21", setOf("OneBlock"), "OneBlock 26.10.21", "вторая установка отличается версией, как на ПК"),
            Case("OneBlock", "26.10.21", setOf("OneBlock", "OneBlock 26.10.21"), "OneBlock 26.10.21 (2)", "и версия занята — счётчик"),
            Case("OneBlock", "", setOf("OneBlock"), "OneBlock (2)", "без версии сразу счётчик"),
            Case("", "1.0", emptySet(), "Сборка", "пустое имя каталога не даёт пустую строку в списке"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, PackNames.fresh(case.pack, case.version, case.taken))
        }
    }

    @Test
    fun `старое автоматическое имя`() {
        data class Case(val current: String, val origin: PackOrigin?, val taken: Set<String>, val expected: String?, val why: String)

        val oneBlock = PackOrigin(name = "OneBlock", versionName = "26.10.21")
        val cases = listOf(
            Case("OneBlock 26.10.21", oneBlock, emptySet(), "OneBlock", "имя из прежней установки становится именем сборки"),
            Case("Мой OneBlock", oneBlock, emptySet(), null, "имя, которое игрок ввёл сам, не трогается"),
            Case("OneBlock 26.10.21", oneBlock, setOf("OneBlock"), null, "имя занято другой сборкой — оставляем как было, а не плодим одинаковые"),
            Case("OneBlock 26.10.21", null, emptySet(), null, "без записи о сборке не угадываем"),
            Case("OneBlock", PackOrigin(name = "OneBlock", versionName = ""), emptySet(), null, "у сборки без версии нечего убирать"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, PackNames.renamed(case.current, case.origin, case.taken))
        }
    }

    @Test
    fun `переименование при запуске лаунчера не трогает папку`() {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        val store = ProfileStore(layout)
        val installed = Profile(id = "oneblock_26.10.21", name = "OneBlock 26.10.21", gameVersion = "1.7.10", loader = LoaderKind.FORGE)
        val typed = Profile(id = "mine", name = "Мой мир", gameVersion = "1.21.4")
        store.save(listOf(installed, typed))
        PackOriginStore.write(store.directory(installed), PackOrigin(name = "OneBlock", versionName = "26.10.21", buildId = installed.id))

        val migrated = PackNames.migrate(store)

        assertEquals("сборка называется по имени, версию игры экран берёт из gameVersion", "OneBlock", migrated.first().name)
        assertEquals("папка висит на id, а не на имени — миры и моды на месте", installed.id, migrated.first().id)
        assertEquals("чужая сборка без записи не меняется", typed, migrated.last())
        assertEquals("переименование записано, а не только показано", migrated, store.load())
    }
}
