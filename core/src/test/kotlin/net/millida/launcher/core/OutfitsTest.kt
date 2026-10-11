package net.millida.launcher.core

import net.millida.launcher.core.cosmetics.Look
import net.millida.launcher.core.cosmetics.Outfit
import net.millida.launcher.core.cosmetics.OutfitCape
import net.millida.launcher.core.cosmetics.OutfitSkin
import net.millida.launcher.core.cosmetics.OutfitStore
import net.millida.launcher.core.cosmetics.Outfits
import net.millida.launcher.core.cosmetics.WornCosmetic
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A look is assembled by hand, so losing or mixing one up is losing the player's work. */
class OutfitsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val skin = OutfitSkin("https://t/skin.png", slim = true, wardrobeId = "w1")
    private val wings = WornCosmetic("ANGEL_WINGS~red", "WINGS", "red")
    private val hat = WornCosmetic("TOP_HAT", "HAT")

    private fun outfit(id: String, name: String) = Outfit(id, name, skin, OutfitCape(), emptyList())

    @Test
    fun `имя нового образа`() {
        val cases = listOf(
            emptyList<Outfit>() to "Образ 1",
            listOf(outfit("a", "Образ 1"), outfit("b", "Образ 3")) to "Образ 2",
            listOf(outfit("a", "Мой"), outfit("b", "Образ 1")) to "Образ 2",
        )
        for ((list, expected) in cases) {
            assertEquals("после удаления второго не появляется второй «Образ 3»", expected, Outfits.nextName(list))
        }
    }

    @Test
    fun `переименование`() {
        val list = listOf(outfit("a", "Образ 1"))
        assertEquals("пробелы схлопываются", "Летний вид", Outfits.rename(list, "a", "  Летний   вид ").single().name)
        assertEquals("пустое имя не принимается — безымянную плитку не отличить", "Образ 1", Outfits.rename(list, "a", "   ").single().name)
        assertEquals("длинное имя режется до потолка", Outfits.NAME_MAX, Outfits.rename(list, "a", "x".repeat(40)).single().name.length)
    }

    @Test
    fun `совпадение с надетым`() {
        val saved = Outfit("a", "Образ 1", skin, OutfitCape("https://t/cape.png"), listOf(wings, hat))
        val cases = listOf(
            Look(skin, OutfitCape("https://t/cape.png"), listOf(hat, wings)) to true,
            Look(skin, OutfitCape("https://t/cape.png"), listOf(hat, wings.copy(variant = "blue"))) to false,
            Look(skin.copy(slim = false), OutfitCape("https://t/cape.png"), listOf(hat, wings)) to false,
            Look(skin, OutfitCape(), listOf(hat, wings)) to false,
        )
        for ((look, expected) in cases) {
            assertEquals("порядок вещей не важен, расцветка, руки и плащ — важны: $look", expected, Outfits.same(saved, look))
        }
    }

    @Test
    fun `потолок образов`() {
        var list = emptyList<Outfit>()
        repeat(Outfits.LIMIT + 3) { list = Outfits.add(list, Look(skin, OutfitCape(), listOf(hat)), "id$it") }
        assertEquals("больше двенадцати не сохраняется — решает экран", Outfits.LIMIT, list.size)
    }

    @Test
    fun `образы переживают перезапуск и не смешиваются между аккаунтами`() {
        val store = OutfitStore(folder.root)
        val saved = listOf(Outfit("a", "Образ 1", skin, OutfitCape("https://t/cape.png", "c1"), listOf(wings, hat)))
        store.save("acc-1", saved)
        assertEquals("сохранённое читается тем же", saved, store.load("acc-1"))
        assertEquals("у другого аккаунта своих образов нет", emptyList<Outfit>(), store.load("acc-2"))
        assertEquals("похожий id с опасными знаками не попадает в чужой файл", emptyList<Outfit>(), store.load("../acc-1"))
        assertEquals("файлы лежат только в своей папке", 1, folder.root.listFiles().orEmpty().count { it.name.endsWith(".json") })
    }
}
