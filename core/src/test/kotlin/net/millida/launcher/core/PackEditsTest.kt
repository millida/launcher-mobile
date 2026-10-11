package net.millida.launcher.core

import net.millida.launcher.core.packs.PackAutoUpdate
import net.millida.launcher.core.packs.PackEdits
import net.millida.launcher.core.packs.PackOrigin
import net.millida.launcher.core.packs.PackOriginStore
import net.millida.launcher.core.packs.PackSource
import net.millida.launcher.core.packs.catalogSlugValid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PackEditsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun put(dir: File, path: String): File =
        File(dir, path).also { it.parentFile.mkdirs(); it.writeText("x") }

    private val catalog = PackOrigin(source = PackSource.MILLIDA_CATALOG.id, id = "arcania", buildId = "b1")

    private data class SlugCase(val slug: String, val valid: Boolean, val why: String)

    @Test
    fun `адрес сборки каталога`() {
        val cases = listOf(
            SlugCase("arcania", true, "обычный адрес"),
            SlugCase("one-block-2", true, "дефис и цифры законны"),
            SlugCase("a".repeat(80), true, "80 знаков — ещё адрес"),
            SlugCase("a".repeat(81), false, "длиннее 80 — уже не адрес, а мусор в пути запроса"),
            SlugCase("", false, "пустой адрес дал бы запрос к списку всех сборок"),
            SlugCase("Arcania", false, "верхний регистр сервер не выдаёт"),
            SlugCase("a_b", false, "подчёркивание не входит в алфавит адреса"),
            SlugCase("../x", false, "адрес становится сегментом пути и именем папки"),
            SlugCase("a/b", false, "слеш уводит запрос на другой ресурс"),
            SlugCase("аркания", false, "кириллица не входит в алфавит адреса"),
        )
        cases.forEach { case -> assertEquals("«${case.slug.take(20)}»: ${case.why}", case.valid, catalogSlugValid(case.slug)) }
    }

    @Test
    fun `какие файлы считаются модами и какие кладёт сам лаунчер`() {
        val mods = listOf(
            "sodium.jar" to true,
            "OptiFine.JAR" to true,
            "legacy.zip" to true,
            "old.litemod" to true,
            "sodium.jar.disabled" to false,
            "notes.txt" to false,
            "jar" to false,
        )
        mods.forEach { (name, expected) ->
            assertEquals("«$name»: выключенный мод и посторонние файлы не делают сборку изменённой", expected, PackEdits.modFile(name))
        }
        val placed = listOf(
            "millida-mod-fabric-1.20.1-0.2.8.jar" to true,
            "Millida-Voice.jar" to true,
            "CustomSkinLoader_Fabric-14.20.jar" to true,
            "sodium.jar" to false,
        )
        placed.forEach { (name, expected) ->
            assertEquals("«$name»: файлы лаунчера меняются при каждом запуске и не считаются правкой игрока", expected, PackEdits.launcherPlaced(name))
        }
    }

    @Test
    fun `скрытые файлы и вложенность глубже одной папки не считаются правкой`() {
        val dir = tmp.newFolder("depth")
        put(dir, "mods/a.jar")
        PackEdits.recordShippedMods(dir)
        put(dir, "mods/.hidden.jar")
        put(dir, "mods/1.7.10/deep/inner.jar")
        assertEquals("служебные и глубоко вложенные файлы игра не грузит как моды", false, PackEdits.modsModified(dir, installedAt = 0))
    }

    @Test
    fun `битый список модов сборки судится по времени установки`() {
        val dir = tmp.newFolder("broken")
        put(dir, "mods/a.jar")
        File(dir, PackEdits.SHIPPED_MODS_FILE).writeText("{не список")
        assertEquals("без читаемого списка и без времени установки сборку считаем изменённой — так не сотрём моды игрока", true, PackEdits.modsModified(dir, installedAt = 0))
    }

    @Test
    fun `сборка не из каталога не обновляется сама`() {
        val dir = tmp.newFolder("foreign")
        val off = PackAutoUpdate(catalog = false, on = false, modified = false, copied = false, chosen = null)
        val cases = listOf(
            null to "без записи об установке сборке неоткуда обновляться",
            PackOrigin(source = PackSource.MODRINTH.id, id = "fabulously") to "сборки Modrinth не обновляются из нашего каталога",
            catalog.copy(id = "../evil") to "адрес из файла записи проверяется так же, как из сети",
        )
        cases.forEach { (origin, why) -> assertEquals(why, off, PackEdits.state(dir, "b1", origin)) }

        val untouched = tmp.newFolder("untouched")
        put(untouched, "mods/a.jar")
        PackEdits.recordShippedMods(untouched)
        val state = PackEdits.state(untouched, "b1", catalog)
        assertEquals("нетронутая сборка каталога обновляется сама", true, state.on && state.catalog)
        assertEquals("переключатель в обычном положении ничего не поясняет", null, state.note)
    }

    @Test
    fun `выбор игрока сохраняется только у сборки каталога`() {
        val none = tmp.newFolder("none")
        try {
            PackEdits.choose(none, "b1", true)
            fail("без записи об установке выбор некуда сохранить")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("не из каталога"))
        }

        val foreign = tmp.newFolder("modrinth")
        PackOriginStore.write(foreign, PackOrigin(source = PackSource.MODRINTH.id, id = "x"))
        try {
            PackEdits.choose(foreign, "b1", true)
            fail("сборке Modrinth нельзя включить обновление из каталога")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("не из каталога"))
        }

        val own = tmp.newFolder("own")
        put(own, "mods/a.jar")
        PackEdits.recordShippedMods(own)
        PackOriginStore.write(own, catalog)
        val saved = PackEdits.choose(own, "b1", false)
        assertEquals("выключение сохранено", false, saved.chosen)
        assertEquals("выключенная игроком сборка не обновляется", false, saved.on)
        assertEquals("ответ записан в файл и переживёт перезапуск", false, PackOriginStore.read(own)?.autoUpdate)
        assertEquals("сборка закреплена за этим профилем", "b1", PackOriginStore.read(own)?.buildId)
    }

    private data class NoteCase(val state: PackAutoUpdate, val note: String?, val why: String)

    @Test
    fun `подпись под переключателем`() {
        fun s(on: Boolean, modified: Boolean, copied: Boolean, chosen: Boolean?) =
            PackAutoUpdate(catalog = true, on = on, modified = modified, copied = copied, chosen = chosen)
        val cases = listOf(
            NoteCase(s(true, true, false, true), "Твои изменения модов заменятся версией автора", "игрок включил обновление поверх своих модов — предупредить"),
            NoteCase(s(false, false, true, null), "Выключено: это копия сборки", "копия выключена сама — объяснить почему"),
            NoteCase(s(false, true, true, null), "Выключено: это копия сборки", "у копии причина «копия» важнее «менял моды»"),
            NoteCase(s(false, true, false, null), "Выключено: ты менял моды", "выключено само из-за правок"),
            NoteCase(s(false, true, false, false), null, "игрок выключил сам — пояснять нечего"),
            NoteCase(s(true, false, false, null), null, "обычное состояние без подписи"),
            NoteCase(s(false, false, false, false), null, "выключено руками без правок"),
        )
        cases.forEach { case -> assertEquals(case.why, case.note, case.state.note) }
    }
}
