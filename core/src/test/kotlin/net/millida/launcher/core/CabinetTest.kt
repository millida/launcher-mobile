package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.cabinet.CabinetAchievementsClient
import net.millida.launcher.core.cabinet.CabinetNewsClient
import net.millida.launcher.core.cabinet.CabinetWalletClient
import net.millida.launcher.core.cabinet.SignInRequired
import net.millida.launcher.core.cabinet.amountText
import net.millida.launcher.core.cabinet.availableText
import net.millida.launcher.core.cabinet.kopecksAsRubles
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The cabinet tab shows money, awards and posts, so the pins here are about
 * what the player is told, not about shapes for their own sake.
 *
 * | вход                                     | вердикт                                      |
 * | ---------------------------------------- | -------------------------------------------- |
 * | баланс 123456 копеек                     | «1234,56 ₽» — копейки не теряются            |
 * | 100000 / 5 / -1250 копеек                | «1000 ₽» / «0,05 ₽» / «-12,50 ₽»             |
 * | история `{"items":[]}`                   | пустой список, не ошибка                     |
 * | 401 на кошельке                          | SignInRequired «Нужен вход», не «ошибка»     |
 * | 401 на достижениях и новостях            | тот же SignInRequired                        |
 * | новость без logoUrl и без sourceUrl      | карточка есть, картинка и ссылка пустые      |
 * | достижения me с summary                  | прогресс и «получено» разобраны              |
 */
class CabinetTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun auth(): MillidaAuth {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, "test-token")
        return MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/'))
    }

    @Test
    fun `баланс в копейках показывается рублями без потерь`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"walletId":"w1","tenantId":"t1","availableKopecks":123456,
                 "heldKopecks":0,"withdrawableKopecks":100000,"currency":"RUB"}
                """.trimIndent()
            )
        )

        val balance = CabinetWalletClient(auth()).balance()

        assertEquals("копейки разобраны как число", 123456L, balance.availableKopecks)
        assertEquals("копейки доезжают до строки", "1234,56 ₽", balance.availableText())
        assertEquals("ровная сумма без копеек", "1000 ₽", 100000L.kopecksAsRubles())
        assertEquals("пять копеек не округляются в ноль", "0,05 ₽", 5L.kopecksAsRubles())
        assertEquals("ведущий ноль в копейках сохраняется", "1,05 ₽", 105L.kopecksAsRubles())
        assertEquals("списание показывается со знаком", "-12,50 ₽", (-1250L).kopecksAsRubles())

        assertEquals(
            "баланс берётся с того же адреса, что и в лаунчере",
            "/v2/core/wallet/me/display",
            server.takeRequest().path,
        )
    }

    @Test
    fun `пустая история операций не ошибка`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[],"total":0,"offset":0,"limit":50}"""))

        val page = CabinetWalletClient(auth()).history()

        assertTrue("пустая история — это пустой список", page.items.isEmpty())
        assertEquals("всего операций ноль", 0, page.total)
        assertTrue(
            "запрос уходит на историю кошелька",
            server.takeRequest().path.orEmpty().startsWith("/v2/core/wallet/history?"),
        )
    }

    @Test
    fun `операции разбираются со знаком суммы`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"items":[
                  {"id":"o1","at":"2026-09-01T10:00:00.000Z","deltaKopecks":50000,
                   "category":"topup","title":"Пополнение","source":"topup","description":null},
                  {"id":"o2","at":"2026-09-02T10:00:00.000Z","deltaKopecks":-1250,
                   "category":"spend","title":"Покупка","source":"market","description":null,
                   "held":true,"heldUntil":"2026-10-02T10:00:00.000Z"}
                ],"total":2,"offset":0,"limit":50}
                """.trimIndent()
            )
        )

        val page = CabinetWalletClient(auth()).history()

        assertEquals("обе операции на месте", 2, page.items.size)
        assertEquals("поступление со знаком плюс", "+500 ₽", page.items[0].amountText())
        assertTrue("поступление помечено как приход", page.items[0].incoming)
        assertEquals("списание со знаком минус", "-12,50 ₽", page.items[1].amountText())
        assertFalse("списание не приход", page.items[1].incoming)
        assertTrue("удержание переносится", page.items[1].held)
        assertEquals(
            "null в описании не превращается в слово null",
            "",
            page.items[0].description,
        )
    }

    @Test
    fun `истёкшая сессия просит войти, а не сообщает об ошибке`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}"""))

        val verdict = runCatching { CabinetWalletClient(auth()).balance() }.exceptionOrNull()

        assertTrue("401 — это просьба войти, а не сбой", verdict is SignInRequired)
        assertEquals("текст говорит игроку, что делать", "Нужен вход в Millida", verdict?.message)
    }

    @Test
    fun `истёкшая сессия одинаково видна на достижениях и новостях`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody(""))
        val awards = runCatching { CabinetAchievementsClient(auth()).mine() }.exceptionOrNull()
        assertTrue("достижения тоже просят войти", awards is SignInRequired)

        server.enqueue(MockResponse().setResponseCode(401).setBody(""))
        val news = runCatching { CabinetNewsClient(auth()).feed() }.exceptionOrNull()
        assertTrue("лента тоже просит войти", news is SignInRequired)
    }

    @Test
    fun `лента новостей без картинки и ссылки не падает`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"items":[
                  {"id":"n1","text":"Вайп сервера в субботу\nПодробности в канале",
                   "sourceUrl":null,"keywords":["wipe"],"isWipe":true,"wipeAt":null,
                   "publishedAt":"2026-09-10T08:00:00.000Z",
                   "entity":{"kind":"server","name":"Grash","slug":"grash","logoUrl":null,
                             "href":"/rating/servers/grash"}},
                  {"id":"n2","text":"Обновили карту","sourceUrl":"https://t.me/x/1",
                   "keywords":[],"isWipe":false,"wipeAt":null,
                   "publishedAt":"2026-09-09T08:00:00.000Z",
                   "entity":{"kind":"hosting","name":"Millida","slug":"millida",
                             "logoUrl":"https://cdn.millida.net/logo.png",
                             "href":"/rating/hostings/millida"}}
                ],"total":2}
                """.trimIndent()
            )
        )

        val page = CabinetNewsClient(auth()).feed(limit = 20)

        assertEquals("обе новости доехали", 2, page.items.size)
        val first = page.items[0]
        assertEquals("заголовок — первая строка поста", "Вайп сервера в субботу", first.title)
        assertEquals("картинки нет — пустая строка, не слово null", "", first.imageUrl)
        assertEquals(
            "без sourceUrl ссылка ведёт на страницу сервера",
            "https://millida.net/rating/servers/grash",
            first.link,
        )
        assertEquals("дата переносится", "2026-09-10T08:00:00.000Z", first.publishedAt)
        assertTrue("метка вайпа сохранена", first.isWipe)
        assertEquals("теги разобраны", listOf("wipe"), first.keywords)

        val second = page.items[1]
        assertEquals("картинка есть — берётся логотип", "https://cdn.millida.net/logo.png", second.imageUrl)
        assertEquals("своя ссылка важнее нашей страницы", "https://t.me/x/1", second.link)

        assertTrue(
            "лента берётся из общей новостной ручки рейтинга",
            server.takeRequest().path.orEmpty().startsWith("/v2/rating/news?"),
        )
    }

    @Test
    fun `достижения показывают прогресс и полученные`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"items":[
                  {"code":"hours_10","title":"Десять часов","description":"Наиграй 10 часов",
                   "service":"launcher","category":"time","icon":"clock","tier":"bronze",
                   "points":10,"hidden":false,"metric":"hours",
                   "unlockedAt":"2026-08-01T00:00:00.000Z","progress":10,"target":10,
                   "progressUnit":"ч"},
                  {"code":"hours_100","title":"Сто часов","description":"Наиграй 100 часов",
                   "service":"launcher","category":"time","icon":"clock","tier":"silver",
                   "points":30,"hidden":false,"metric":"hours",
                   "unlockedAt":null,"progress":25,"target":100,"progressUnit":"ч"}
                ],"summary":{"unlocked":1,"total":2,"points":10}}
                """.trimIndent()
            )
        )

        val page = CabinetAchievementsClient(auth()).mine()

        assertEquals("сводка берётся у сервера", 1, page.unlocked)
        assertEquals("всего наград", 2, page.total)
        assertEquals("очки считает сервер", 10, page.points)
        assertEquals("полученное отделено", listOf("hours_10"), page.taken.map { it.code })
        assertEquals("незакрытое отделено", listOf("hours_100"), page.pending.map { it.code })

        val pending = page.pending.first()
        assertEquals("прогресс в доле", 0.25f, pending.progressRatio, 0.0001f)
        assertEquals("прогресс строкой с единицей", "25 / 100 ч", pending.progressText)
        assertFalse("не получено", pending.unlocked)
        assertEquals(
            "null в unlockedAt не превращается в слово null",
            "",
            pending.unlockedAt,
        )

        val taken = page.taken.first()
        assertTrue("получено", taken.unlocked)
        assertEquals("полученное всегда на всю шкалу", 1f, taken.progressRatio, 0.0001f)

        assertEquals("свои достижения берутся у /achievements/me", "/v2/achievements/me", server.takeRequest().path)
    }
}
