package net.millida.launcher.core

import net.millida.launcher.core.media.Screenshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class ScreenshotsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var layout: GameLayout
    private lateinit var shots: Screenshots

    private fun prepare() {
        layout = GameLayout(temp.newFolder()).also { it.ensure() }
        shots = Screenshots(layout)
    }

    private fun png(width: Int, height: Int, pixels: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(be(13))
        out.write("IHDR".toByteArray(Charsets.US_ASCII))
        out.write(be(width))
        out.write(be(height))
        out.write(byteArrayOf(8, 6, 0, 0, 0))
        repeat(pixels) { out.write(it and 0xFF) }
        return out.toByteArray()
    }

    private fun be(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun shot(profileId: String, name: String, bytes: ByteArray, takenAt: Long = 0): File {
        val dir = File(layout.profileDir(profileId), "screenshots").also { it.mkdirs() }
        val file = File(dir, name)
        file.writeBytes(bytes)
        if (takenAt > 0) file.setLastModified(takenAt)
        return file
    }

    @Test
    fun `разрешение читается из настоящего заголовка PNG`() {
        prepare()
        shot("build", "shot.png", png(2560, 1440, pixels = 64))

        val card = shots.describe("build", "shot.png")

        assertEquals("ширина берётся из IHDR, а не из декодированной картинки", 2560, card.width)
        assertEquals("высота берётся из IHDR", 1440, card.height)
        assertEquals("размер файла показывается игроку как есть", 29L + 64, card.sizeBytes)
        assertEquals("сборка запоминается: в режиме «все сборки» иначе не удалить", "build", card.profileId)
        assertTrue("путь нужен app-части для «поделиться»", card.path.endsWith("shot.png"))
    }

    @Test
    fun `битые и чужие файлы не роняют галерею`() {
        prepare()
        shot("build", "truncated.png", png(1920, 1080).copyOf(12))
        shot("build", "fake.png", "GIF89a and then some bytes".toByteArray())
        shot("build", "zero.png", png(0, 0))
        shot("build", "notes.txt", "не скриншот".toByteArray())
        File(layout.profileDir("build"), "screenshots/folder.png").mkdirs()

        val listed = shots.list("build").associateBy { it.name }

        assertEquals(
            "в галерею идут только png-файлы: txt и папка пропускаются",
            setOf("truncated.png", "fake.png", "zero.png"),
            listed.keys,
        )
        for (name in listOf("truncated.png", "fake.png", "zero.png")) {
            val card = listed.getValue(name)
            assertEquals("«$name» не даёт разрешения — выдумывать его нельзя", 0, card.width)
            assertEquals("«$name» не даёт разрешения — выдумывать его нельзя", 0, card.height)
        }
        assertEquals("счётчик считает те же файлы, что и список", 3, shots.count("build"))
    }

    @Test
    fun `пропавшая папка и пустая сборка дают пустой список`() {
        prepare()
        layout.profileDir("empty").mkdirs()

        assertEquals("сборки без папки screenshots — пустой список, а не ошибка", emptyList<Any>(), shots.list("empty"))
        assertEquals(0, shots.count("empty"))
        assertEquals("нет ни одной сборки — тоже пусто", emptyList<Any>(), shots.list())
        assertEquals(0, shots.count())
    }

    @Test
    fun `список идёт от свежих к старым и фильтруется по сборке`() {
        prepare()
        shot("alpha", "old.png", png(800, 600), takenAt = 1_600_000_000_000)
        shot("alpha", "new.png", png(800, 600), takenAt = 1_700_000_000_000)
        shot("beta", "middle.png", png(800, 600), takenAt = 1_650_000_000_000)

        val all = shots.list()

        assertEquals(
            "свежий снимок первый: игрок ищет то, что только что снял",
            listOf("new.png", "middle.png", "old.png"),
            all.map { it.name },
        )
        assertEquals("время снимка — время файла", 1_700_000_000_000, all.first().takenAt)
        assertEquals("в режиме «все сборки» видно обе сборки", listOf("alpha", "beta", "alpha"), all.map { it.profileId })
        assertEquals("фильтр по сборке оставляет только её снимки", listOf("new.png", "old.png"), shots.list("alpha").map { it.name })
        assertEquals("счётчик по всем сборкам", 3, shots.count())
        assertEquals("счётчик по одной сборке", 1, shots.count("beta"))
    }

    @Test
    fun `удаление убирает только выбранный снимок`() {
        prepare()
        shot("build", "keep.png", png(640, 480))
        shot("build", "drop.png", png(640, 480))
        shot("other", "drop.png", png(640, 480))

        assertTrue("удаление обязано подтвердиться", shots.delete("build", "drop.png"))

        assertEquals("соседний снимок остаётся на месте", listOf("keep.png"), shots.list("build").map { it.name })
        assertEquals("одноимённый снимок другой сборки не трогается", 1, shots.count("other"))
        assertFalse("повторное удаление ничего не находит", shots.delete("build", "drop.png"))
    }

    @Test
    fun `переименование сохраняет расширение и не затирает соседа`() {
        prepare()
        shot("build", "shot.png", png(1280, 720))
        shot("build", "занято.png", png(640, 480))

        val renamed = shots.rename("build", "shot.png", "Мой дом")

        assertEquals("расширение дописывается само", "Мой_дом.png", renamed.name)
        assertEquals("карточка пересчитывается по новому файлу", 1280, renamed.width)
        assertFalse("старого файла не остаётся", File(layout.profileDir("build"), "screenshots/shot.png").exists())

        try {
            shots.rename("build", "Мой_дом.png", "занято.png")
            fail("переименование поверх существующего снимка должно отклоняться")
        } catch (expected: IOException) {
            assertTrue("ошибка обязана быть внятной", expected.message!!.isNotBlank())
        }
        assertEquals("чужой снимок остался нетронутым", 640, shots.describe("build", "занято.png").width)
    }

    @Test
    fun `имена из интерфейса проверяются перед обращением к диску`() {
        prepare()
        shot("build", "shot.png", png(320, 240))

        val refused = listOf(
            "../../profiles.json",
            "../shot.png",
            "shot.png/../../x.png",
            "sub/shot.png",
            "level.dat",
            ".png",
            "",
        )
        for (name in refused) {
            try {
                shots.file("build", name)
                fail("«$name» не должно превращаться в путь: по нему идут удаление и выгрузка файла")
            } catch (expected: IOException) {
                assertTrue("ошибка обязана быть внятной", expected.message!!.isNotBlank())
            }
        }

        try {
            shots.list("../..")
            fail("идентификатор сборки с выходом наружу должен отклоняться")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.isNotBlank())
        }

        assertEquals("нормальное имя по-прежнему открывается", 320, shots.describe("build", "shot.png").width)
    }
}
