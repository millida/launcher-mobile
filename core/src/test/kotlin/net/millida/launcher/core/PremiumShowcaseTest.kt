package net.millida.launcher.core

import net.millida.launcher.core.catalog.MillidaCard
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.premium.PackSource
import net.millida.launcher.core.premium.PackView
import net.millida.launcher.core.premium.Premium
import net.millida.launcher.core.premium.PremiumAction
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.core.premium.PremiumPeriod
import net.millida.launcher.core.premium.PremiumSubscription
import net.millida.launcher.core.premium.PremiumTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class PremiumShowcaseTest {

    private val utc = ZoneOffset.UTC
    private val month = PremiumTier("all", "Все сборки", 29_900, PremiumPeriod.MONTH, listOf("Все сборки"))
    private val base = PremiumPack(id = "arcania", title = "Arcania", slug = "arcania")

    /** Non-breaking spaces of the Russian number format would make every expected string unreadable. */
    private fun plain(text: String?): String? = text?.replace(' ', ' ')?.replace(' ', ' ')

    @Test
    fun `витрина разбирается и не выдумывает поля, которых нет в ответе`() {
        val body = MojangJson.parseToJsonElement(
            """
            {
              "subscription": {"active": true, "planId": "all", "paidUntil": "2026-10-12T00:00:00Z", "manageUrl": "https://millida.net/profile"},
              "plans": [{"id": "all", "title": "Все сборки", "priceKopecks": 29900, "period": "month", "items": ["Все сборки", ""]}],
              "featuredId": "second",
              "packs": [
                {"id": "first", "title": "Первая", "modsCount": 291, "mcVersion": "1.20.1", "loader": "Forge", "inSubscription": true},
                {"id": "second", "title": "Вторая", "priceKopecks": "100", "online": null},
                {"title": "без id — не карточка"}
              ]
            }
            """,
        )
        val shown = Premium.showcase(body)

        assertEquals("карточка без id отброшена", listOf("first", "second"), shown.packs.map { it.id })
        assertEquals("герой — тот, кого назвал сервер", "second", shown.featured?.id)
        assertEquals("остальные — без героя", listOf("first"), shown.rest.map { it.id })
        assertEquals("уровень — оформленный игроком", "all", shown.plan?.id)
        assertEquals("пустые строки состава выкинуты", listOf("Все сборки"), shown.plans.single().items)
        assertNull("цена строкой не принимается за число", shown.packs[1].priceKopecks)
        assertNull("онлайн null — плитки онлайна нет", shown.packs[1].online)
        assertEquals("291 модов · 1.20.1 · Forge", plain(Premium.facts(shown.packs[0])))
    }

    @Test
    fun `страница сборки ложится поверх карточки, а не стирает её`() {
        val card = base.copy(coverUrl = "https://cdn/cover.jpg", inSubscription = true, downloads = 500)
        val body = MojangJson.parseToJsonElement(
            """{"id": "arcania", "modsCount": 300, "owned": true, "screenshots": ["https://a", "https://b"],
               "patches": [{"date": "2026-09-01", "text": "Новые боссы"}, {"text": ""}], "partner": {"name": "Arcania Labs"}}""",
        )
        val detail = Premium.detail(body, card)

        assertEquals("обложка карточки осталась", "https://cdn/cover.jpg", detail.pack.coverUrl)
        assertEquals("число модов пришло со страницы", 300, detail.pack.modsCount)
        assertTrue("покупка со страницы видна", detail.pack.owned)
        assertTrue("подписка карточки не стёрта отсутствием поля", detail.pack.inSubscription)
        assertEquals("скачивания карточки сохранены", 500L, detail.pack.downloads)
        assertEquals("пустой патч отброшен", 1, detail.patches.size)
        assertEquals("Arcania Labs", detail.partnerName)
    }

    /** Each row pins one branch of the desktop `BuyButton`; the order of checks is the contract. */
    @Test
    fun `главная кнопка сборки — та же, что на компьютере`() {
        val active = PremiumSubscription(active = true)
        val cases = listOf(
            Triple(base.copy(source = PackSource.CATALOG), null to null, PremiumAction.Install("arcania")) to
                "сборка каталога ставится путём каталога, без цены",
            Triple(base.copy(source = PackSource.CATALOG, slug = null), null to null, PremiumAction.None) to
                "сборка каталога без слага — без кнопки, как на компьютере",
            Triple(base.copy(owned = true, priceKopecks = 100), null to null, PremiumAction.Install("arcania")) to
                "купленная ставится, а не продаётся второй раз",
            Triple(base.copy(owned = true, slug = null), null to null, PremiumAction.Soon) to
                "купленная без слага — «Скоро», не Modrinth",
            Triple(base.copy(inSubscription = true), month to active, PremiumAction.Install("arcania")) to
                "активная подписка открывает сборку из подписки",
            Triple(base.copy(inSubscription = true, priceKopecks = 50_000), month to null, PremiumAction.Subscribe(month)) to
                "подписка важнее разовой цены",
            Triple(base.copy(inSubscription = true, priceKopecks = 50_000), null to null, PremiumAction.Buy(50_000)) to
                "без уровня подписки остаётся разовая покупка",
            Triple(base.copy(priceKopecks = 0), null to null, PremiumAction.None) to
                "нулевая цена — не покупка",
        )
        cases.forEach { (case, why) ->
            val (pack, context, expected) = case
            assertEquals(why, expected, Premium.action(pack, context.first, context.second))
        }
    }

    @Test
    fun `цена рядом с кнопкой честно говорит срок и разовую покупку`() {
        val cases = listOf(
            Triple(base.copy(inSubscription = true, priceKopecks = 99_900), month, "299 ₽/мес|или 999 ₽ разово") to
                "подписка и разовая цена — разными строками",
            Triple(base.copy(priceKopecks = 15_050), null, "150,50 ₽|Покупка навсегда") to
                "разовая покупка помечена как навсегда, копейки не теряются",
            Triple(base.copy(owned = true), month, "Доступ открыт") to
                "купленной сборке цена не нужна",
        )
        cases.forEach { (case, why) ->
            val (pack, plan, expected) = case
            val line = Premium.priceLine(pack, plan, null, utc)
            assertEquals(why, expected, plain(line?.let { (listOf(it.main) + it.notes).joinToString("|") }))
        }
        assertNull("нечего продать — строки цены нет", Premium.priceLine(base, null, null, utc))
        val until = Premium.priceLine(base.copy(inSubscription = true), month, PremiumSubscription(true, paidUntil = "2026-10-12T00:00:00Z"), utc)
        assertEquals("срок подписки назван датой", listOf("Подписка до 12 октября"), until?.notes)
    }

    @Test
    fun `отмена подписки не прячется`() {
        val cases = listOf(
            null to null,
            PremiumSubscription(active = false) to "Отмена в любой момент",
            PremiumSubscription(active = true, paidUntil = "2026-10-12T10:00:00Z") to "Списание каждый месяц, следующее 12 октября",
            PremiumSubscription(active = true, canceled = true, paidUntil = "2026-10-12") to "Продление выключено, доступ до 12 октября",
            PremiumSubscription(active = true, paidUntil = "сломанная дата") to "Списание каждый месяц",
        )
        cases.forEach { (sub, expected) -> assertEquals("подписка $sub", expected, Premium.cancelNote(sub, utc)) }
    }

    @Test
    fun `витрина без адреса подписок показывает платные сборки каталога`() {
        val catalog = listOf(
            MillidaCard("arcania", "Arcania", summary = "Магия", cover = "https://c", versions = listOf("1.20.1"), loaders = listOf("forge"), premium = true, downloads = 10),
            MillidaCard("free", "Бесплатная"),
        )
        val shown = Premium.combine(null, catalog)
        assertEquals("бесплатная сборка не попадает на платную витрину", listOf("arcania"), shown.packs.map { it.id })
        val pack = shown.packs.single()
        assertEquals(PackSource.CATALOG, pack.source)
        assertEquals("загрузчик — по-человечески", "Forge", pack.loader)
        assertTrue("каталог ставит сборку сам", Premium.viaCatalog(pack))

        val premium = Premium.showcase(MojangJson.parseToJsonElement("""{"packs": [{"id": "p", "title": "P"}]}"""))
        assertEquals("ответ подписок с сборками важнее каталога", listOf("p"), Premium.combine(premium, catalog).packs.map { it.id })
    }

    @Test
    fun `эксклюзивы хаба — одна карточка на сборку и Arcania первой`() {
        val showcase = listOf(PremiumPack("other", "Другая", slug = "other"), PremiumPack("arc", "Arcania", slug = "arcania"))
        val catalog = Premium.catalogPacks(
            MojangJson.parseToJsonElement(
                """[
                  {"slug": "arcania", "title": "Arcania", "accessRequired": true},
                  {"slug": "paid", "title": "Платная", "accessRequired": true, "downloads": 5},
                  {"slug": "free", "title": "Бесплатная", "downloads": 900, "loader": "fabric"},
                  {"slug": "free2", "title": "Ещё", "downloads": 1000},
                  {"slug": "../evil", "title": "Плохой слаг"}
                ]""",
            ),
        )
        assertEquals("плохой слаг не становится карточкой", 4, catalog.size)
        assertEquals(
            "Arcania первой, дубль из каталога отброшен, платная каталога добавлена",
            listOf("arcania", "other", "paid"),
            Premium.exclusives(showcase, catalog).map { it.slug },
        )
        assertEquals("бесплатные — по скачиваниям", listOf("free2", "free"), Premium.freePacks(catalog).map { it.slug })
        assertEquals("Fabric", catalog.first { it.slug == "free" }.loader)
    }

    @Test
    fun `плитки страницы — только живые числа`() {
        val pack = base.copy(modsCount = 0, mcVersion = "1.20.1", online = 0, downloads = 0, author = " ")
        val tiles = Premium.tiles(pack, "2026-09-30T12:00:00Z", utc).map { it.label to it.value }
        assertEquals(
            "ноль модов и скачиваний не показываем, онлайн ноль — показываем, пустой автор — нет",
            listOf("Версия" to "1.20.1", "Сейчас играют" to "0", "Обновлено" to "30 сентября"),
            tiles,
        )
    }

    @Test
    fun `карточка каталога для страницы берёт только https`() {
        val view = PackView.parse(
            MojangJson.parseToJsonElement(
                """{"slug": "s", "cover": "https://c/1.jpg", "banner": "http://insecure/b.jpg", "video": "javascript:alert(1)",
                   "gallery": ["https://c/1.jpg", "https://c/2.jpg", "https://user@evil/3.jpg", "https://c/2.jpg"],
                   "files": [{"side": "server", "size": 9}, {"side": "client", "size": 1234}]}""",
            ),
            "s",
        )
        assertNull("http-баннер отброшен", view.banner)
        assertNull("не-https видео не откроется", view.video)
        assertEquals("арт — обложка, раз баннер не прошёл", "https://c/1.jpg", view.art)
        assertEquals("кадры без арта, без повторов и без userinfo", listOf("https://c/2.jpg"), view.frames)
        assertEquals(1234L, view.clientBytes)
        assertFalse(view.accessRequired)
    }

    /**
     * The phone lists only packs it can start; a link still opens the page,
     * and there the button gives way to the reason. The beta switch is not
     * wired in core tests, so it reads as off.
     */
    @Test
    fun `витрина телефона не предлагает то, что не запустится`() {
        val catalog = Premium.catalogPacks(
            MojangJson.parseToJsonElement(
                """[
                  {"slug": "arcania", "title": "Arcania", "game": "1.20.1", "loader": "fabric", "accessRequired": true, "protectedContent": true},
                  {"slug": "oneblock-metalabs", "title": "OneBlock", "game": "1.7.10", "loader": "forge", "protectedContent": true, "downloads": 5000},
                  {"slug": "deceasedcraft", "title": "DeceasedCraft", "game": "1.20.1", "loader": "forge", "accessRequired": true},
                  {"slug": "tlou", "title": "TLOU", "game": "1.20.1", "loader": "forge", "downloads": 10},
                  {"slug": "old", "title": "Old", "game": "1.12.2", "loader": "forge", "downloads": 9000}
                ]""",
            ),
        )
        assertEquals("платный свой запуск скрыт с витрины", listOf("deceasedcraft"), Premium.exclusives(emptyList(), catalog).map { it.slug })
        assertEquals("OneBlock и старый Forge скрыты без беты", listOf("tlou"), Premium.freePacks(catalog).map { it.slug })

        val arcania = catalog.first { it.slug == "arcania" }
        val action = Premium.action(arcania, null, null)
        assertTrue("вместо «Установить» — причина: $action", action is PremiumAction.PcOnly && action.reason.contains("компьютере"))
        assertEquals("кнопка остаётся у того, что запустится", PremiumAction.Install("tlou"), Premium.action(catalog.first { it.slug == "tlou" }, null, null))
    }

    @Test
    fun `страница сборки узнаёт свой запуск и размер из карточки каталога`() {
        val listed = PremiumPack("x", "X", slug = "x", source = PackSource.CATALOG)
        val view = PackView(slug = "x", game = "1.7.10", loader = "forge", clientBytes = 900, ownLaunch = true)
        val merged = Premium.withView(listed, view)
        assertTrue("флаг карточки доезжает до кнопки", merged.ownLaunch)
        assertEquals(900L, merged.sizeBytes)
        assertEquals("1.7.10", merged.mcVersion)
        assertEquals("загрузчик — по-человечески", "Forge", merged.loader)
        assertEquals("без карточки страница остаётся как есть", listed, Premium.withView(listed, null))
    }
}
