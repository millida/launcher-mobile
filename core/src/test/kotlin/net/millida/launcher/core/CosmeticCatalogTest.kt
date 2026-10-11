package net.millida.launcher.core

import kotlinx.serialization.json.Json
import net.millida.launcher.core.cosmetics.CosmeticCatalog
import net.millida.launcher.core.cosmetics.CosmeticSections
import net.millida.launcher.core.cosmetics.CosmeticVariant
import net.millida.launcher.core.cosmetics.WornCosmetic
import net.millida.launcher.core.cosmetics.defaultVariant
import net.millida.launcher.core.cosmetics.splitVariantCode
import net.millida.launcher.core.cosmetics.variantTitles
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone and the desktop dress one account, so a colourway has to be the
 * same card and the same (item, colourway) pair for the service on both.
 */
class CosmeticCatalogTest {

    private val wings = """
        {"items":[
          {"id":"ANGEL_WINGS","name":"Крылья","slot":"WINGS","access":"PURCHASE","priceRubies":300,"rarity":"epic",
           "variants":[{"name":"black","color":"#111111","texture":"t/black.png"},
                       {"name":"red","color":"#ff0000","texture":"t/red.png","price":0},
                       {"name":"gold","title":"Крылья солнца","code":"ANGEL_WINGS~gold","preview":"p/gold.png","price":900,"rarity":"legendary"}]},
          {"id":"TOP_HAT","name":"Цилиндр","slot":"HAT","access":"FREE","variants":[{"name":"white"}]},
          {"id":"","name":"битая","slot":"HAT"},
          {"id":"NO_SLOT","name":"без места"}
        ]}
    """.trimIndent()

    private val catalog = CosmeticCatalog.of(CosmeticCatalog.parseItems(Json.parseToJsonElement(wings)))

    private data class CardCase(val id: String, val name: String, val price: Int?, val rarity: String, val why: String)

    @Test
    fun `расцветки становятся отдельными карточками`() {
        val cases = listOf(
            CardCase("ANGEL_WINGS~black", "Крылья", 300, "epic", "первая расцветка носит имя вещи и её цену"),
            CardCase("ANGEL_WINGS~red", "Крылья заката", null, "epic", "цена 0 у расцветки значит «не продаётся», а не «бесплатно»"),
            CardCase("ANGEL_WINGS~gold", "Крылья солнца", 900, "legendary", "имя, код, цена и ранг службы важнее своих"),
            CardCase("TOP_HAT", "Цилиндр", null, "", "вещь с одной расцветкой остаётся одной карточкой со своим кодом"),
        )
        for (case in cases) {
            val card = catalog.byId(case.id)
            assertEquals("карточка ${case.id} пропала: ${case.why}", case.id, card?.id)
            assertEquals(case.why, case.name, card?.name)
            assertEquals(case.why, case.price, card?.priceRubies)
            assertEquals(case.why, case.rarity, card?.rarity)
        }
        assertEquals("строка без кода или без места не надевается и выпадает", 4, catalog.items.size)
    }

    @Test
    fun `перекраска только там, где нет своей картинки`() {
        assertEquals("красная берёт цвет поверх чёрной базы", "#ff0000" to "#111111", catalog.byId("ANGEL_WINGS~red")!!.let { it.tint to it.tintFrom })
        assertEquals("у золотой своя картинка — перекраска её испортила бы", "" to "p/gold.png", catalog.byId("ANGEL_WINGS~gold")!!.let { it.tint to it.preview })
    }

    private data class WireCase(val card: WornCosmetic, val server: WornCosmetic, val why: String)

    @Test
    fun `пара для службы и карточка переводятся друг в друга`() {
        val cases = listOf(
            WireCase(WornCosmetic("ANGEL_WINGS~red", "WINGS", "red"), WornCosmetic("ANGEL_WINGS", "WINGS", "red"), "служба знает вещь и расцветку"),
            WireCase(WornCosmetic("TOP_HAT", "HAT"), WornCosmetic("TOP_HAT", "HAT"), "вещь без расцветок уходит как есть"),
        )
        for (case in cases) {
            assertEquals(case.why, case.server, catalog.toServer(case.card))
            assertEquals(case.why, case.card, catalog.fromServer(case.server))
        }
        assertEquals(
            "старая служба без расцветки — на фигуре базовая, а не пустота",
            WornCosmetic("ANGEL_WINGS~black", "WINGS", "black"),
            catalog.fromServer(WornCosmetic("ANGEL_WINGS", "WINGS")),
        )
        assertEquals("код с тильдой режется по первой тильде", "A" to "b~c", splitVariantCode("A~b~c"))
    }

    @Test
    fun `права на расцветки`() {
        assertEquals(
            "старая служба без списка расцветок — своя вещь своя во всех цветах",
            listOf("ANGEL_WINGS~black", "ANGEL_WINGS~red", "ANGEL_WINGS~gold", "TOP_HAT"),
            catalog.ownedCodes(listOf("ANGEL_WINGS", "TOP_HAT"), null),
        )
        assertEquals(
            "новая служба перечисляет купленные расцветки — остальные закрыты",
            listOf("ANGEL_WINGS~gold"),
            catalog.ownedCodes(listOf("ANGEL_WINGS"), mapOf("ANGEL_WINGS" to listOf("gold"))),
        )
    }

    @Test
    fun `цвет по умолчанию тот же, что на картинке каталога`() {
        val list = listOf(CosmeticVariant("black"), CosmeticVariant("Default"), CosmeticVariant("white"))
        assertEquals("белая важнее default, как у рендера превью мода", "white", defaultVariant(list)?.name)
        assertEquals("без привычных имён — первая", "black", defaultVariant(listOf(CosmeticVariant("black"), CosmeticVariant("red")))?.name)
    }

    @Test
    fun `имена расцветок уникальны внутри вещи`() {
        val titles = variantTitles("Плащ", listOf("base", "red", "crimson_red", "zzz"))
        assertEquals("у двух красных расцветок разные слова", 4, titles.toSet().size)
        assertEquals("Плащ", titles[0])
        assertEquals("Плащ заката", titles[1])
    }

    @Test
    fun `разделы гардероба по тому, что есть в каталоге`() {
        val sections = CosmeticSections.of(listOf("HAT", "WINGS", "EMOTE", "TAIL"))
        assertEquals("пустые разделы прячутся", listOf("head", "body", "back", "emote"), sections.map { it.key })
        assertEquals("новое место каталога не теряется, а уходит в «Тело · Другое»", listOf("TAIL"), sections.first { it.key == "body" }.chips.single().slots)
        assertEquals("пока каталог грузится, видны все разделы — полоса не прыгает", CosmeticSections.ALL, CosmeticSections.of(emptyList()))
    }
}
