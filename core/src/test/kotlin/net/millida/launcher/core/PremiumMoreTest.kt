package net.millida.launcher.core

import net.millida.launcher.core.premium.PremiumMore
import net.millida.launcher.core.premium.PremiumPack
import org.junit.Assert.assertEquals
import org.junit.Test

class PremiumMoreTest {

    private fun pack(slug: String?, title: String = slug.orEmpty(), cover: String? = "c", downloads: Long? = null) =
        PremiumPack(id = slug ?: title, title = title, slug = slug, coverUrl = cover, downloads = downloads)

    @Test
    fun `порядок и отбор как на ПК`() {
        val all = listOf(
            pack("big", downloads = 9_000),
            pack("homestead-ru", "Homestead"),
            pack("arcania", "Arcania"),
            pack("nocover", cover = null, downloads = 99_000),
            pack(null, "Без адреса", downloads = 99_000),
            pack("small", downloads = 10),
        )
        assertEquals(
            "лидеры продаж по порядку ПК, дальше по скачиваниям; без обложки и адреса не показываются",
            listOf("arcania", "homestead-ru", "big"),
            PremiumMore.pick(all, current = null).map { it.slug },
        )
        assertEquals(
            "текущая сборка не советует сама себя",
            listOf("homestead-ru", "big", "small"),
            PremiumMore.pick(all, current = "arcania").map { it.slug },
        )
    }

    @Test
    fun `своя сборка студии — только Arcania`() {
        assertEquals("Arcania своя — без плашки «Полностью на русском»", true, PremiumMore.isOwn(pack("arcania-labs")))
        assertEquals("переводы — не свои", false, PremiumMore.isOwn(pack("homestead")))
    }
}
