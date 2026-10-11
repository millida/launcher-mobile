package net.millida.launcher.core

import net.millida.launcher.core.game.Worlds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorldsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var layout: GameLayout
    private lateinit var profileDir: File
    private lateinit var worlds: Worlds

    private fun prepare() {
        layout = GameLayout(temp.newFolder()).also { it.ensure() }
        profileDir = layout.profileDir("build").also { it.mkdirs() }
        worlds = Worlds(layout)
    }

    private fun nbt(body: DataOutputStream.() -> Unit): ByteArray {
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { it.body() }
        val packed = ByteArrayOutputStream()
        GZIPOutputStream(packed).use { it.write(plain.toByteArray()) }
        return packed.toByteArray()
    }

    private fun DataOutputStream.tag(type: Int, name: String) {
        writeByte(type)
        writeUTF(name)
    }

    private fun DataOutputStream.string(name: String, value: String) {
        tag(8, name)
        writeUTF(value)
    }

    private fun DataOutputStream.int(name: String, value: Int) {
        tag(3, name)
        writeInt(value)
    }

    private fun DataOutputStream.long(name: String, value: Long) {
        tag(4, name)
        writeLong(value)
    }

    private fun DataOutputStream.byte(name: String, value: Int) {
        tag(1, name)
        writeByte(value)
    }

    private fun DataOutputStream.byteArray(name: String, size: Int) {
        tag(7, name)
        writeInt(size)
        repeat(size) { writeByte(it and 0x7f) }
    }

    private fun DataOutputStream.compoundOfCompounds(name: String) {
        tag(9, name)
        writeByte(10)
        writeInt(2)
        repeat(2) {
            string("id", "minecraft:stone")
            writeByte(0)
        }
    }

    private fun DataOutputStream.unknownTag(name: String) {
        writeByte(99)
        writeUTF(name)
    }

    private fun world(folder: String, bytes: ByteArray?, extraSize: Int = 0): File {
        val dir = File(profileDir, "saves/$folder").also { it.mkdirs() }
        if (bytes != null) File(dir, "level.dat").writeBytes(bytes)
        if (extraSize > 0) File(dir, "region/r.0.0.mca").also { it.parentFile.mkdirs() }
            .writeBytes(ByteArray(extraSize))
        return dir
    }

    private fun fullLevel(
        name: String,
        lastPlayed: Long,
        gameMode: Int,
        hardcore: Int,
        version: String,
    ) = nbt {
        tag(10, "")
        tag(10, "Data")
        byteArray("WanderingTraderId", 16)
        compoundOfCompounds("ServerBrands")
        string("LevelName", name)
        long("LastPlayed", lastPlayed)
        int("GameType", gameMode)
        byte("hardcore", hardcore)
        tag(10, "Version")
        string("Name", version)
        int("Id", 3953)
        writeByte(0)
        unknownTag("FutureTag")
    }

    @Test
    fun `карточки миров читаются из level dat`() {
        prepare()
        world("Мир 1", fullLevel("Приключение", 1_700_000_000_000, 0, 1, "1.20.1"), extraSize = 4096)
        world("flat", fullLevel("Плоскость", 1_600_000_000_000, 1, 0, "1.21.4"))

        val listed = worlds.list(profileDir)

        assertEquals("оба мира обязаны попасть в список", 2, listed.size)
        val first = listed.first()
        assertEquals("свежий мир идёт первым", "Мир 1", first.folder)
        assertEquals("имя берётся из LevelName, а не из папки", "Приключение", first.name)
        assertEquals("время последнего входа сохраняется", 1_700_000_000_000, first.lastPlayed)
        assertEquals("режим игры читается", 0, first.gameMode)
        assertTrue("хардкор читается: без него игрок потеряет мир по незнанию", first.hardcore)
        assertEquals("версия игры читается из Version.Name", "1.20.1", first.version)
        assertTrue("размер мира считается по всей папке", first.sizeBytes >= 4096)

        val second = listed[1]
        assertEquals("второй мир по давности входа", "flat", second.folder)
        assertEquals("Плоскость", second.name)
        assertEquals("творческий режим читается", 1, second.gameMode)
        assertFalse("обычный мир не хардкорный", second.hardcore)
        assertEquals("1.21.4", second.version)
    }

    @Test
    fun `незнакомые и битые данные не роняют список`() {
        prepare()
        val unknownFirst = nbt {
            tag(10, "")
            tag(10, "Data")
            unknownTag("FutureTag")
            string("LevelName", "не прочитается")
        }
        world("future", unknownFirst)
        world("garbage", byteArray(64))
        world("empty", null)
        File(profileDir, "saves/файл.txt").writeText("не мир")

        val listed = worlds.list(profileDir).associateBy { it.folder }

        assertEquals("мир без level.dat и посторонний файл в список не идут", 2, listed.size)
        assertEquals(
            "на незнакомом теге разбор останавливается, но мир остаётся видимым",
            "future",
            listed.getValue("future").name,
        )
        assertEquals(
            "битый level.dat не должен прятать мир от игрока",
            "garbage",
            listed.getValue("garbage").name,
        )
    }

    @Test
    fun `удаление мира убирает папку целиком`() {
        prepare()
        world("Мир 1", fullLevel("Приключение", 1L, 0, 0, "1.20.1"), extraSize = 128)
        world("flat", fullLevel("Плоскость", 2L, 0, 0, "1.20.1"))

        assertTrue("удаление обязано подтвердиться", worlds.delete(profileDir, "Мир 1"))

        assertEquals("остаться должен только второй мир", listOf("flat"), worlds.list(profileDir).map { it.folder })
        assertFalse("папка мира удаляется вместе с регионами", File(profileDir, "saves/Мир 1").exists())
        assertFalse("повторное удаление ничего не находит", worlds.delete(profileDir, "Мир 1"))
    }

    @Test
    fun `имя мира с выходом из папки saves отклоняется`() {
        prepare()
        world("flat", fullLevel("Плоскость", 1L, 0, 0, "1.20.1"))

        try {
            worlds.delete(profileDir, "../mods")
            fail("удаление по пути наружу saves должно отклоняться")
        } catch (expected: IOException) {
            assertTrue("ошибка обязана быть внятной", expected.message!!.isNotBlank())
        }
    }

    private fun plainLevel(
        name: String,
        lastPlayed: Long,
        gameMode: Int,
        hardcore: Int,
        version: String,
    ) = nbt {
        tag(10, "")
        tag(10, "Data")
        byteArray("WanderingTraderId", 16)
        compoundOfCompounds("ServerBrands")
        string("LevelName", name)
        long("LastPlayed", lastPlayed)
        int("GameType", gameMode)
        byte("hardcore", hardcore)
        tag(10, "Version")
        string("Name", version)
        int("Id", 3953)
        writeByte(0)
        writeByte(0)
        writeByte(0)
    }

    private fun richLevel(
        name: String,
        difficulty: Int,
        cheats: Int,
        seed: Long,
    ) = nbt {
        tag(10, "")
        tag(10, "Data")
        string("LevelName", name)
        long("LastPlayed", 1_700_000_000_000)
        int("GameType", 2)
        byte("hardcore", 0)
        byte("Difficulty", difficulty)
        byte("allowCommands", cheats)
        tag(10, "WorldGenSettings")
        long("seed", seed)
        writeByte(0)
        tag(10, "Version")
        string("Name", "1.21.1")
        writeByte(0)
        byteArray("WanderingTraderId", 16)
        writeByte(0)
        writeByte(0)
    }

    private fun legacySeedLevel(name: String, seed: Long) = nbt {
        tag(10, "")
        tag(10, "Data")
        string("LevelName", name)
        long("RandomSeed", seed)
        writeByte(0)
        writeByte(0)
    }

    private fun files(dir: File): Set<String> = dir.walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(dir).path.replace(File.separatorChar, '/') }
        .toSortedSet()

    private fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `подробности мира читаются из level dat`() {
        prepare()
        world("peaceful", richLevel("Тихий", difficulty = 0, cheats = 0, seed = 42L))
        world("hard", richLevel("Злой", difficulty = 3, cheats = 1, seed = -7L))
        world("legacy", legacySeedLevel("Старый", 9001L))
        world("garbage", byteArray(64))

        val peaceful = worlds.details(profileDir, "peaceful")!!
        assertEquals("мирная сложность читается", 0, peaceful.difficulty)
        assertFalse("без allowCommands читы выключены", peaceful.cheats)
        assertEquals("сид берётся из WorldGenSettings", "42", peaceful.seed)
        assertEquals("режим приключения читается", 2, peaceful.gameMode)
        assertEquals("версия читается после вложенного компаунда", "1.21.1", peaceful.version)
        assertTrue("целый level.dat считается читаемым", peaceful.readable)

        val hard = worlds.details(profileDir, "hard")!!
        assertEquals("высокая сложность читается", 3, hard.difficulty)
        assertTrue("читы читаются: игрок должен знать про выключенные достижения", hard.cheats)
        assertEquals("отрицательный сид не теряет знак", "-7", hard.seed)

        val legacy = worlds.details(profileDir, "legacy")!!
        assertEquals("сид старых миров лежит в RandomSeed", "9001", legacy.seed)
        assertEquals("сложность неизвестна, а не ноль: ноль означал бы мирную", -1, legacy.difficulty)

        val garbage = worlds.details(profileDir, "garbage")!!
        assertFalse("битый level.dat помечается нечитаемым", garbage.readable)
        assertEquals("нечитаемый мир показывается по имени папки", "garbage", garbage.name)
        assertEquals("сведений о сиде нет", "", garbage.seed)

        assertNull("несуществующего мира нет и в подробностях", worlds.details(profileDir, "нет-такого"))
    }

    @Test
    fun `копия и восстановление возвращают тот же набор файлов`() {
        prepare()
        val dir = world("Мир 1", fullLevel("Приключение", 1_700_000_000_000, 0, 0, "1.20.1"), extraSize = 1024)
        File(dir, "data/raids.dat").also { it.parentFile.mkdirs() }.writeBytes(byteArray(32))
        File(dir, "session.lock").writeBytes(byteArray(4))
        val before = files(dir)

        val backup = worlds.backup(profileDir, "Мир 1")
        assertEquals("копия принадлежит своему миру", "Мир 1", backup.folder)
        assertTrue("копия непустая", backup.sizeBytes > 0)
        assertEquals(
            "копия появляется в списке своего мира",
            listOf(backup.file),
            worlds.backups(profileDir, "Мир 1").map { it.file },
        )
        assertEquals("копия чужого мира не показывается", 0, worlds.backups(profileDir, "flat").size)

        File(dir, "region/r.0.0.mca").delete()
        File(dir, "мусор.txt").writeText("появился после копии")

        val restored = worlds.restoreBackup(profileDir, backup.file)

        assertEquals("мир восстанавливается на своё место", "Мир 1", restored.folder)
        assertEquals(
            "восстановленный мир совпадает по файлам с моментом копии, кроме замка сессии",
            before - "session.lock",
            files(File(profileDir, "saves/Мир 1")),
        )
        assertTrue("удаление копии подтверждается", worlds.deleteBackup(profileDir, backup.file))
        assertEquals("после удаления копий не остаётся", 0, worlds.backups(profileDir).size)
    }

    @Test
    fun `провал восстановления оставляет исходный мир целым`() {
        prepare()
        val dir = world("Мир 1", fullLevel("Приключение", 5L, 0, 0, "1.20.1"), extraSize = 256)
        val before = files(dir)
        val broken = File(profileDir, "backups/Мир 1--1700000000000.zip")
        broken.parentFile.mkdirs()
        broken.writeBytes(zipOf(listOf("Мир 1/readme.txt" to "это не мир".toByteArray())))

        try {
            worlds.restoreBackup(profileDir, broken.name)
            fail("восстановление из архива без level.dat должно отклоняться")
        } catch (expected: IOException) {
            assertTrue("ошибка обязана называть, чего не хватило", expected.message!!.contains("level.dat"))
        }

        assertEquals("мир обязан пережить неудачное восстановление", before, files(dir))
        assertEquals("мир остаётся в списке", listOf("Мир 1"), worlds.list(profileDir).map { it.folder })
        assertEquals(
            "временная распаковка убирается и на пути ошибки",
            emptyList<String>(),
            File(profileDir, ".millida-tmp").listFiles().orEmpty().map { it.name },
        )
    }

    @Test
    fun `импорт архива с выходом из папки отклоняется`() {
        prepare()
        val archive = zipOf(
            listOf(
                "world/level.dat" to fullLevel("Чужой", 1L, 0, 0, "1.20.1"),
                "../mods/evil.jar" to byteArray(8),
            )
        )

        try {
            worlds.importWorld(profileDir, ByteArrayInputStream(archive), "world.zip")
            fail("архив с путём наружу должен отклоняться целиком")
        } catch (expected: IOException) {
            assertTrue("ошибка обязана быть внятной", expected.message!!.isNotBlank())
        }

        assertFalse("ничего не должно появиться вне saves", File(profileDir, "mods/evil.jar").exists())
        assertEquals(
            "испорченный архив не оставляет мира",
            emptyList<String>(),
            worlds.list(profileDir).map { it.folder },
        )
    }

    @Test
    fun `экспорт и импорт переносят мир целиком`() {
        prepare()
        val dir = world("Мир 1", fullLevel("Приключение", 7L, 0, 0, "1.20.1"), extraSize = 512)
        val out = ByteArrayOutputStream()
        worlds.exportWorld(profileDir, "Мир 1", out)

        val imported = worlds.importWorld(profileDir, ByteArrayInputStream(out.toByteArray()), "Мир 1.zip")

        assertEquals("имя из level.dat переживает перенос", "Приключение", imported.name)
        assertTrue("импорт не затирает существующий мир", imported.folder != "Мир 1")
        assertEquals("файлы переносятся целиком", files(dir), files(File(profileDir, "saves/${imported.folder}")))
    }

    @Test
    fun `дубликат не трогает оригинал`() {
        prepare()
        val dir = world("Мир 1", plainLevel("Приключение", 9L, 0, 0, "1.20.1"), extraSize = 64)
        File(dir, "session.lock").writeBytes(byteArray(4))
        val before = files(dir)

        val copy = worlds.duplicate(profileDir, "Мир 1")

        assertTrue("дубликат называется копией", copy.name.contains("копия"))
        assertTrue("дубликат лежит в своей папке", copy.folder != "Мир 1")
        assertEquals("оригинал не изменился", before, files(dir))
        assertEquals("имя оригинала остаётся прежним", "Приключение", worlds.details(profileDir, "Мир 1")!!.name)
        assertFalse(
            "замок сессии в копию не уезжает",
            File(profileDir, "saves/${copy.folder}/session.lock").exists(),
        )
        assertTrue("копия читается как настоящий мир", worlds.details(profileDir, copy.folder)!!.readable)
    }

    @Test
    fun `переименование меняет имя и в level dat и у папки`() {
        prepare()
        world("Мир 1", plainLevel("Приключение", 11L, 1, 1, "1.20.1"), extraSize = 64)

        val renamed = worlds.rename(profileDir, "Мир 1", "  Дом  ")

        assertEquals("название чистится от пробелов", "Дом", renamed.name)
        assertEquals("папка переезжает вслед за названием", "Дом", renamed.folder)
        assertFalse("старой папки не остаётся", File(profileDir, "saves/Мир 1").exists())
        assertTrue("прежний level.dat остаётся запасным", File(profileDir, "saves/Дом/level.dat_old").isFile)

        val again = worlds.details(profileDir, "Дом")!!
        assertEquals("новое имя читается обратно из level.dat", "Дом", again.name)
        assertEquals("режим игры переживает перезапись", 1, again.gameMode)
        assertTrue("хардкор переживает перезапись", again.hardcore)
        assertEquals("версия переживает перезапись", "1.20.1", again.version)
        assertEquals("время последнего входа переживает перезапись", 11L, again.lastPlayed)

        try {
            worlds.rename(profileDir, "Дом", "   ")
            fail("пустое название должно отклоняться")
        } catch (expected: IOException) {
            assertTrue("ошибка обязана быть внятной", expected.message!!.isNotBlank())
        }
    }

    @Test
    fun `переименование мира с незнакомым тегом отклоняется`() {
        prepare()
        val partial = nbt {
            tag(10, "")
            tag(10, "Data")
            string("LevelName", "Половинка")
            unknownTag("FutureTag")
            string("Hidden", "остаток")
        }
        world("future", partial)
        val before = File(profileDir, "saves/future/level.dat").readBytes()

        try {
            worlds.rename(profileDir, "future", "Новое")
            fail("частично прочитанный level.dat переписывать нельзя: остаток мира потеряется")
        } catch (expected: IOException) {
            assertTrue("ошибка объясняет причину", expected.message!!.contains("level.dat"))
        }

        assertTrue("папка мира остаётся на месте", File(profileDir, "saves/future").isDirectory)
        assertTrue(
            "level.dat не тронут",
            before.contentEquals(File(profileDir, "saves/future/level.dat").readBytes()),
        )
    }

    private fun byteArray(size: Int) = ByteArray(size) { (it * 7).toByte() }
}
