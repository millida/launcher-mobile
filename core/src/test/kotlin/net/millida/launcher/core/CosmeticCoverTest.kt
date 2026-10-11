package net.millida.launcher.core

import kotlinx.serialization.json.Json
import net.millida.launcher.core.cosmetics.CosmeticCover
import net.millida.launcher.core.cosmetics.CosmeticItem
import net.millida.launcher.core.cosmetics.Pixels
import net.millida.launcher.core.cosmetics.RigBone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** A combination has to look the same on the phone, on the desktop and in the game, so cover rules are the mod's. */
class CosmeticCoverTest {

    private fun bone(name: String, parent: Int = -1, side: String? = null) =
        RigBone(name, parent, null, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), emptyList(), side)

    @Test
    fun `какие кости вещи прячутся`() {
        val bones = listOf(bone("ear_left", side = "left"), bone("ear_right", side = "Right"), bone("tip", parent = 0), bone("hair"))
        val cases = listOf(
            Triple(setOf<String>(), "right", booleanArrayOf(true, false, true, false)) to
                "выбрана правая сторона — левая прячется вместе с дочерними костями",
            Triple(setOf("hair"), null, booleanArrayOf(false, true, false, true)) to
                "без заявленной стороны берётся левая, как в моде, а шляпа прячет волосы",
        )
        for ((input, why) in cases) {
            val (covered, side, expected) = input
            val actual = CosmeticCover.hiddenJoints(bones, covered, side)
            assertArrayEquals(why, expected, actual)
        }
    }

    @Test
    fun `правила прятанья пишутся по коду вещи, а не расцветки`() {
        val hat = CosmeticItem(
            id = "TOP_HAT~red",
            name = "Цилиндр",
            slot = "HAT",
            baseId = "TOP_HAT",
            hides = Json.parseToJsonElement("""{"AFRO":["Hair","bangs"],"OTHER":{"x":true,"y":false}}"""),
        )
        val afro = CosmeticItem(id = "AFRO~black", name = "Афро", slot = "HEAD", baseId = "AFRO")
        assertEquals("шляпа прячет у причёски обе кости, без учёта регистра", setOf("hair", "bangs"), CosmeticCover.hiddenBones(listOf(hat, afro), afro))
        assertEquals("сама себя вещь не прячет", emptySet<String>(), CosmeticCover.hiddenBones(listOf(hat), hat))
        assertEquals("в объектной записи прячется только помеченное true", mapOf("AFRO" to setOf("Hair", "bangs"), "OTHER" to setOf("x")), CosmeticCover.hideRules(hat.hides))
    }

    @Test
    fun `маска под модель рук и сторону`() {
        val item = CosmeticItem(id = "SUIT", name = "Костюм", slot = "FULL_BODY", masks = mapOf("Steve.Left" to "s-left.png", "alex" to "alex.png"))
        assertEquals("у широких рук своя маска любой стороны", "s-left.png", CosmeticCover.maskUrl(item, slim = false, side = null))
        assertEquals("у тонких — своя", "alex.png", CosmeticCover.maskUrl(item, slim = true, side = "left"))
        assertEquals(
            "эмоция без маски не мешает маскам надетого",
            listOf("s-left.png"),
            CosmeticCover.maskUrls(listOf(item), slim = false, playingEmote = CosmeticItem(id = "DAB", name = "Dab", slot = "EMOTE")),
        )
        assertEquals("плащ-вещь убирает обычный плащ", true, CosmeticCover.hidesRegularCape(listOf(CosmeticItem(id = "C", name = "C", slot = "CAPE"))))
    }

    @Test
    fun `маска вырезает скин под одеждой`() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val skin = Pixels(IntArray(64 * 64) { white }, 64, 64)
        val mask = Pixels(IntArray(64 * 64), 64, 64)
        for (y in 0 until 16) for (x in 0 until 32) mask.data[y * 64 + x] = black
        val changed = CosmeticCover.cutSkin(skin, listOf(mask), slim = false)
        assertEquals("маска с тёмными пикселями меняет скин", true, changed)
        assertEquals("тёмный пиксель маски вырезан", 0, skin.data[8 * 64 + 8])
        assertEquals("голова закрыта целиком — с ней уходит и её второй слой", 0, skin.data[8 * 64 + 40])
        assertEquals("тело под маской не закрыто — остаётся", white, skin.data[20 * 64 + 20])
        val clean = Pixels(IntArray(64 * 64) { white }, 64, 64)
        assertEquals("пустая маска ничего не меняет", false, CosmeticCover.cutSkin(clean, listOf(Pixels(IntArray(64 * 64), 64, 64)), slim = false))
    }
}
