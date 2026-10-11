package net.millida.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelIconsTest {

    @Test
    fun `все значки собираются без ошибки`() {
        assertTrue(
            "MIcons собирается при первом обращении: кривая сетка или чужой цвет уронят приложение на старте",
            MIcons.box.side > 0,
        )
    }

    @Test
    fun `палитра пиксельных значков совпадает с ключами`() {
        assertEquals(
            "каждому ключу цвета нужен свой цвет, иначе значок закрасится соседним",
            PxArt.PALETTE_KEYS.length,
            PxArt.palette.size,
        )
        assertEquals("ключи палитры не повторяются", PxArt.PALETTE_KEYS.length, PxArt.PALETTE_KEYS.toSet().size)
    }

    @Test
    fun `значки сборок с компьютера`() {
        PxArt.byId.forEach { (id, cells) ->
            val icon = MIcons.byDesktopId(id)
            assertNotNull("значок $id выбран на ПК и обязан открываться на телефоне", icon)
            assertEquals("значок $id — квадратная сетка", cells.length, icon!!.side * icon.side)
            assertEquals("в каждой строке run из четырёх полей", 0, icon.runs.size % 4)
        }
        assertNull("незнакомый значок не падает, а уступает значку загрузчика", MIcons.byDesktopId("no-such-icon"))
        val first = PxArt.byId.keys.firstOrNull()
        if (first != null) {
            assertSame("значок кэшируется, а не собирается на каждый кадр", MIcons.byDesktopId(first), MIcons.byDesktopId(first))
        }
    }
}
