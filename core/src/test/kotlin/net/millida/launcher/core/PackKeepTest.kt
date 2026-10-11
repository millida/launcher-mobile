package net.millida.launcher.core

import net.millida.launcher.core.packs.PackKeep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackKeepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun put(dir: File, path: String, text: String = path): File =
        File(dir, path).also { it.parentFile.mkdirs(); it.writeText(text) }

    @Test
    fun `без записи о файлах сборки уходят только известные папки игрока`() {
        val pack = tmp.newFolder("legacy")
        listOf(
            "saves/Мир/level.dat", "screenshots/1.png", "schematics/house.schem", "resourcepacks/Pack.zip",
            "shaderpacks/BSL.zip", "options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat",
            "journeymap/map.dat", "config/a.toml", "mods/paid.jar", "libraries/x.jar", "logs/latest.log",
            "millida-pack.json",
        ).forEach { put(pack, it) }

        assertNull("сборка, поставленная до появления записи, записи не имеет", PackKeep.shippedEntries(pack))
        assertEquals(
            "без записи неизвестная папка может быть платной частью сборки — её не уносим; миры и настройки уносим всегда",
            listOf(
                "options.txt", "optionsof.txt", "optionsshaders.txt", "resourcepacks/Pack.zip", "saves",
                "schematics", "screenshots", "servers.dat", "shaderpacks/BSL.zip",
            ),
            PackKeep.playerEntries(pack).sorted(),
        )
    }

    @Test
    fun `платное содержимое не уходит, даже если сборка его не записала`() {
        val pack = tmp.newFolder("recorded")
        put(pack, "config/a.toml")
        put(pack, "options.txt")
        PackKeep.recordShipped(pack, PackKeep.shipped(pack))
        listOf("mods/extra.jar", "libraries/y.jar", "assets/z.json", "natives/lib.so", "crash-reports/c.txt", "millida-cosmetics.json", "notes.txt")
            .forEach { put(pack, it) }

        assertEquals(
            "моды, библиотеки и файлы лаунчера никогда не считаются вещами игрока; настройки — всегда его, даже если пришли со сборкой",
            listOf("notes.txt", "options.txt"),
            PackKeep.playerEntries(pack).sorted(),
        )
    }

    @Test
    fun `битая запись о файлах сборки считается отсутствующей`() {
        val pack = tmp.newFolder("broken")
        put(pack, "journeymap/map.dat")
        put(pack, "saves/w/level.dat")
        File(pack, PackKeep.SHIPPED_FILE).writeText("[не json")
        assertNull("битая запись не превращается в пустой список, иначе всё в папке сочли бы вещами игрока", PackKeep.shippedEntries(pack))
        assertEquals(listOf("saves"), PackKeep.playerEntries(pack))
    }

    @Test
    fun `повторное сохранение не затирает то, что уже лежит в хранении`() {
        val keep = PackKeep.keepDir(tmp.root, "arcania")
        put(keep, "saves/Старый мир/level.dat", "старый")

        val pack = tmp.newFolder("pack")
        put(pack, "saves/Новый мир/level.dat", "новый")
        assertEquals(1, PackKeep.stash(pack, keep))

        val kept = keep.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals("в хранении две копии папки миров: прежняя и новая под другим именем", 2, kept.size)
        assertTrue("прежний мир на месте", File(keep, "saves/Старый мир/level.dat").isFile)
        val renamed = kept.single { it != "saves" }
        assertTrue("новая копия названа по образцу «saves (время)»: $renamed", renamed.startsWith("saves ("))
        assertEquals("новый", File(keep, "$renamed/Новый мир/level.dat").readText())
        assertFalse("из сборки мир ушёл", File(pack, "saves").exists())
    }

    @Test
    fun `возврат без хранения ничего не делает, после полного возврата хранение исчезает`() {
        val fresh = tmp.newFolder("fresh")
        assertEquals("нечего возвращать — нечего и оставлять", emptyList<String>(), PackKeep.restore(fresh, File(tmp.root, "pack-keep/none")))

        val keep = PackKeep.keepDir(tmp.root, "oneblock")
        put(keep, "saves/w/level.dat")
        put(keep, "shaderpacks/BSL.zip")
        val left = PackKeep.restore(fresh, keep)
        assertEquals("без совпадений возвращается всё", emptyList<String>(), left)
        assertTrue(File(fresh, "saves/w/level.dat").isFile)
        assertTrue("шейдер вернулся в папку шейдеров, а не заменил её", File(fresh, "shaderpacks/BSL.zip").isFile)
        assertFalse("пустое хранение убрано, чтобы не копить мусор", keep.exists())
    }

    @Test
    fun `папка хранения лежит внутри общей папки хранения`() {
        val keep = PackKeep.keepDir(tmp.root, "one-block-2")
        assertEquals(File(File(tmp.root, "pack-keep"), "one-block-2").canonicalPath, keep.canonicalPath)
    }
}
