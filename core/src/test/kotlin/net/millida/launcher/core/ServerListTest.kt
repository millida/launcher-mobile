package net.millida.launcher.core

import net.millida.launcher.core.servers.ServerEntry
import net.millida.launcher.core.servers.ServerList
import net.millida.launcher.core.servers.ServersDat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ServerListTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private data class Setup(val layout: GameLayout, val profile: File, val servers: ServerList)

    private data class CleanCase(val name: String, val address: String, val expectedName: String, val expectedAddress: String, val why: String)

    private fun setup(): Setup {
        val layout = GameLayout(tmp.newFolder()).also { it.ensure() }
        val profile = layout.profileDir("build").also { it.mkdirs() }
        return Setup(layout, profile, ServerList(layout))
    }

    @Test
    fun `имя и адрес чистятся перед записью`() {
        val cases = listOf(
            CleanCase("  Мой сервер  ", " play.example.net ", "Мой сервер", "play.example.net", "пробелы по краям — следствие вставки из буфера"),
            CleanCase("", "play.example.net", "play.example.net", "play.example.net", "пустое имя заменяется адресом, иначе строка в игре безымянная"),
            CleanCase("   ", "a.net", "a.net", "a.net", "имя из пробелов — тоже пустое"),
            CleanCase("Имя\u0007\u0001", "a.net", "Имя", "a.net", "управляющие символы ломают отрисовку списка в игре"),
            CleanCase("Сервер", "play. example\t.net", "Сервер", "play.example.net", "пробелы внутри адреса не бывают в DNS-имени"),
            CleanCase("x".repeat(200), "a.net", "x".repeat(120), "a.net", "имя режется до 120 знаков"),
        )
        for (case in cases) {
            val s = setup()
            val added = s.servers.add(s.profile, case.name, case.address).single()
            assertEquals("имя «${case.name}»: ${case.why}", case.expectedName, added.name)
            assertEquals("адрес «${case.address}»: ${case.why}", case.expectedAddress, added.address)
        }
    }

    @Test
    fun `слишком длинный адрес отклоняется до записи`() {
        val s = setup()
        try {
            s.servers.add(s.profile, "Длинный", "a".repeat(256))
            fail("адрес длиннее 255 знаков попал в servers.dat")
        } catch (expected: IOException) {
            assertTrue("причина не названа: ${expected.message}", expected.message.orEmpty().contains("длинный"))
        }
        assertFalse("отказ всё равно создал файл", File(s.profile, ServersDat.FILE_NAME).exists())
        assertEquals("ровно 255 знаков — ещё допустимый адрес", 1, s.servers.add(s.profile, "", "b".repeat(255)).size)
    }

    @Test
    fun `сервер наверх и правка адреса`() {
        val s = setup()
        s.servers.add(s.profile, "Первый", "one.example.net")
        val top = s.servers.add(s.profile, "Второй", "two.example.net", onTop = true)
        assertEquals(
            "onTop не поставил сервер первым — игрок не найдёт свежедобавленный",
            listOf("two.example.net", "one.example.net"),
            top.map { it.address },
        )

        val edited = s.servers.edit(s.profile, "ONE.example.net ", "", "  three.example.net  ")
        assertEquals("правка нашла строку не по адресу без учёта регистра", "three.example.net", edited[1].address)
        assertEquals("пустое имя при правке становится новым адресом", "three.example.net", edited[1].name)
        assertEquals("правка задела соседнюю строку", "Второй", edited[0].name)
        assertEquals("правка не дошла до диска", edited, s.servers.list(s.profile))

        try {
            s.servers.edit(s.profile, "two.example.net", "Второй", "   ")
            fail("правка стёрла адрес — в игре останется мёртвая строка")
        } catch (expected: IOException) {
            assertEquals("отказанная правка всё же изменила файл", edited, s.servers.list(s.profile))
        }
    }

    @Test
    fun `действие над неизвестным адресом ничего не пишет`() {
        val s = setup()
        val file = File(s.profile, ServersDat.FILE_NAME)
        assertTrue("удаление из пустого списка вернуло строки", s.servers.remove(s.profile, "ghost.net").isEmpty())
        assertTrue("закрепление несуществующего вернуло строки", s.servers.pin(s.profile, "ghost.net").isEmpty())
        assertTrue("переименование несуществующего вернуло строки", s.servers.rename(s.profile, "ghost.net", "X").isEmpty())
        assertFalse("ничего не поменялось, а servers.dat создан", file.exists())

        s.servers.add(s.profile, "A", "a.net")
        s.servers.add(s.profile, "B", "b.net")
        val before = file.lastModified()
        val cases = listOf(
            Triple(-1, 0, "отрицательный индекс от жеста перетаскивания"),
            Triple(0, 2, "индекс за концом списка"),
            Triple(1, 1, "перенос на то же место"),
        )
        for ((from, to, why) in cases) {
            assertEquals("перенос $from→$to: $why", listOf("a.net", "b.net"), s.servers.move(s.profile, from, to).map { it.address })
        }
        assertEquals("пустые действия перезаписали файл", before, file.lastModified())
        assertEquals("закрепление первого не меняет порядок", listOf("a.net", "b.net"), s.servers.pin(s.profile, "a.net").map { it.address })
    }

    @Test
    fun `список больше предела обрезается, а добавление сверх него отказывает`() {
        val s = setup()
        val many = (0 until 5000).map { ServerEntry("S$it", "s$it.example.net") }
        val saved = s.servers.save(s.profile, many)
        assertEquals("сохранено больше предела в 4096 строк", 4096, saved.size)
        assertEquals("обрезка взяла не первые строки", "s0.example.net", saved.first().address)
        try {
            s.servers.add(s.profile, "Лишний", "extra.example.net")
            fail("сервер добавлен сверх предела")
        } catch (expected: IOException) {
            assertTrue("игроку не сказано, что удалить лишние: ${expected.message}", expected.message.orEmpty().contains("удалите"))
        }
    }

    @Test
    fun `папка сборки вне хранилища профилей отклоняется`() {
        val s = setup()
        val outside = tmp.newFolder("elsewhere")
        val cases = listOf(
            outside to "чужая папка на диске",
            File(s.layout.profiles, "../../elsewhere") to "выход из profiles через ..",
        )
        for ((dir, why) in cases) {
            try {
                s.servers.add(dir, "X", "x.net")
                fail("$why: servers.dat записан вне хранилища")
            } catch (expected: IOException) {
                assertFalse("$why: файл всё же появился", File(outside, ServersDat.FILE_NAME).exists())
            }
        }
    }
}
