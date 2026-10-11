package net.millida.launcher.core

import kotlinx.serialization.json.Json
import net.millida.launcher.core.catalog.MillidaCatalog
import net.millida.launcher.core.catalog.MillidaFilters
import net.millida.launcher.core.catalog.MillidaSort
import net.millida.launcher.core.catalog.NewBuild
import net.millida.launcher.core.catalog.NewBuildPlan
import net.millida.launcher.core.hosting.HostingField
import net.millida.launcher.core.hosting.HostingSettingsSpec
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.rating.RatingSort
import net.millida.launcher.core.rating.ServerArt
import net.millida.launcher.core.rating.ServerFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop parity of the filters and stand-ins the phone now shares with the desktop launcher.
 *
 * | вход                                              | вердикт                                                   |
 * | ------------------------------------------------- | --------------------------------------------------------- |
 * | онлайн «live» / «20» / «» / мусор                 | minOnline 1 / 20 / 0 / 0                                  |
 * | порядок сортировок серверов                       | рейтинг, онлайн, название, новые — как на ПК              |
 * | FNV-1a «» / «a» / «foobar»                        | те же числа, что даёт JavaScript                          |
 * | фильтры каталога пустые / с версией и «Новые»     | без sort / version, category, q, sort по контракту сайта  |
 * | новая сборка из мода / шейдера / пака / карты     | Fabric по порядку / Fabric / ваниль / нет                 |
 * | правило хостинга null / «false» / число строкой   | значение по умолчанию / false / число                     |
 */
class ParityFiltersTest {

    @Test
    fun `онлайн сервера переводится в порог игроков`() {
        val cases = listOf(
            "live" to 1 to "«Сейчас есть игроки» — хотя бы один",
            "20" to 20 to "порог идёт числом",
            "100" to 100 to "порог идёт числом",
            "" to 0 to "«Любой онлайн» не добавляет параметр",
            "много" to 0 to "мусор не должен стать фильтром",
        )
        for ((pair, why) in cases) assertEquals(why, pair.second, ServerFilters.minOnline(pair.first))
    }

    @Test
    fun `сортировки серверов идут в порядке ПК`() {
        assertEquals(
            "порядок и подписи взяты из Servers.tsx",
            listOf("По рейтингу", "По онлайну", "По названию", "Сначала новые"),
            ServerFilters.SORTS.map(ServerFilters::sortLabel),
        )
        assertEquals("рейтинг — первый и значение по умолчанию", RatingSort.RATING, ServerFilters.SORTS.first())
    }

    @Test
    fun `заглушка сервера считает FNV-1a как JavaScript`() {
        assertEquals("пустая строка — смещение FNV", 2166136261L, ServerArt.hashOf(""))
        assertEquals("эталон FNV-1a для «a»", 0xE40C292CL, ServerArt.hashOf("a"))
        assertEquals("эталон FNV-1a для «foobar»", 0xBF9CF968L, ServerArt.hashOf("foobar"))
        val key = "survival-world"
        assertEquals("один сервер — один блок навсегда", ServerArt.block(key), ServerArt.block(key))
        assertTrue("блок только из списка читаемых", ServerArt.block(key) in ServerArt.BLOCKS)
        assertTrue("подложка в пределах палитры", ServerArt.tint(key) in 0 until ServerArt.TINTS)
    }

    @Test
    fun `запрос ленты каталога собирается как на сайте`() {
        assertEquals(
            "без фильтров — «Рекомендуемые», параметр sort не уходит",
            "section=mods&perPage=20",
            MillidaCatalog.listingParams("mods", "", 1, MillidaFilters()),
        )
        assertEquals(
            "версия, категория, поиск и «Новые» — параметрами",
            "section=mods&version=1.21.1&category=magic&q=iris&sort=new&page=2&perPage=20",
            MillidaCatalog.listingParams("mods", " iris ", 2, MillidaFilters(MillidaSort.NEW, "1.21.1", "magic")),
        )
        assertEquals(
            "одна буква не ищет",
            "section=maps&perPage=20",
            MillidaCatalog.listingParams("maps", "x", 1, MillidaFilters()),
        )
        assertFalse("пустые фильтры не считаются выбранными", MillidaFilters().active)
    }

    @Test
    fun `счётчики фильтров читаются из ответа сайта`() {
        val body = Json.parseToJsonElement("""[{"value":"1.21.1","count":12},{"value":"","count":3},{"count":1},{"value":"1.20.1"}]""")
        val facets = MillidaCatalog.facetsOf(body)
        assertEquals("строки без значения отбрасываются", listOf("1.21.1", "1.20.1"), facets.map { it.value })
        assertEquals("нет счётчика — ноль", 0, facets.last().count)
    }

    @Test
    fun `новая сборка берёт версию и загрузчик самой вещи`() {
        val versions = listOf("1.20.1", "1.21.1", "1.21", "24w14a")
        val cases = listOf(
            Triple("mod", listOf("forge", "fabric"), NewBuildPlan("1.21.1", LoaderKind.FABRIC)) to "Fabric первый в порядке ПК",
            Triple("mod", listOf("neoforge", "forge"), NewBuildPlan("1.21.1", LoaderKind.NEOFORGE)) to "NeoForge раньше Forge",
            Triple("mod", emptyList<String>(), null) to "мод без загрузчика — кнопки нет",
            Triple("shader", emptyList<String>(), NewBuildPlan("1.21.1", LoaderKind.FABRIC)) to "шейдеру нужен Iris на Fabric",
            Triple("resourcepack", emptyList<String>(), NewBuildPlan("1.21.1", LoaderKind.VANILLA)) to "паку загрузчик не нужен",
            Triple("world", emptyList<String>(), null) to "карта — не в сборку",
        )
        for ((case, why) in cases) assertEquals(why, case.third, NewBuild.plan(case.first, versions, case.second))
        assertEquals("снапшоты не считаются релизом", null, NewBuild.newestRelease(listOf("24w14a")))
        assertEquals("имя сборки обрезается до 24 знаков", 24, NewBuild.name("x".repeat(40)).length)
    }

    @Test
    fun `правила хостинга читаются с умолчаниями ПК`() {
        val rules = mapOf("pvp" to "false", "mobGriefing" to "null", "viewDistance" to "12", "javaVersion" to "17", "difficulty" to "weird")
        assertFalse("явное false остаётся false", HostingSettingsSpec.toggle(rules, "pvp", true))
        assertTrue("null от службы — значение по умолчанию", HostingSettingsSpec.toggle(rules, "mobGriefing", true))
        val view = HostingField.Number("viewDistance", "Прорисовка", 3, 32, 10)
        assertEquals("число строкой читается", 12, HostingSettingsSpec.number(rules, view))
        assertEquals("вне границ не отправляется", null, HostingSettingsSpec.parseNumber("64", view))
        val java = HostingField.Choice("javaVersion", "Java", HostingSettingsSpec.JAVA, "0")
        assertEquals("версия Java узнаётся", "17", HostingSettingsSpec.choice(rules, java))
        val difficulty = HostingSettingsSpec.main(20).fields.filterIsInstance<HostingField.Choice>().first { it.key == "difficulty" }
        assertEquals("незнакомое значение — по умолчанию", "normal", HostingSettingsSpec.choice(rules, difficulty))
        val millidaAuth = HostingField.Toggle("millidaAuth", "Авторизация Millida", false)
        assertFalse("на лицензионном сервере авторизация Millida скрыта", HostingSettingsSpec.visible(millidaAuth, emptyMap()))
        assertTrue("без лицензии — видна", HostingSettingsSpec.visible(millidaAuth, mapOf("onlineMode" to "false")))
    }
}
