package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.rubies.CreatorClient
import net.millida.launcher.core.rubies.PackQuest
import net.millida.launcher.core.rubies.QuestState
import net.millida.launcher.core.rubies.RubiesClient
import net.millida.launcher.core.rubies.WeeklyAny
import net.millida.launcher.core.rubies.WelcomeGift
import net.millida.launcher.core.rubies.RubyItem
import net.millida.launcher.core.rubies.creatorErrorText
import net.millida.launcher.core.rubies.isMissingRoute
import net.millida.launcher.core.rubies.normalizeCreatorCode
import net.millida.launcher.core.rubies.questProgress
import net.millida.launcher.core.rubies.questState
import net.millida.launcher.core.rubies.questsOf
import net.millida.launcher.core.rubies.weeklyOf
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Desktop parity of the shop economy: what the phone reads from the service and
 * what leaves it when rubies are spent.
 *
 * | вход                                                  | вердикт                                                     |
 * | ----------------------------------------------------- | ----------------------------------------------------------- |
 * | /rubies/weekly со ступенями                           | путь недели (звёзды, докупка)                               |
 * | /rubies/weekly с часами и выбором                     | посылка недели                                              |
 * | /rubies/weekly чужой формы                            | ничего не рисуем — блок не падает                           |
 * | задание сборки без вещи                               | строка выброшена                                            |
 * | задание: забрано / есть / готово / играть             | состояние кнопки как на ПК                                  |
 * | подарок новичку: выдан / повтор / без вещи            | праздник только на свежую выдачу                            |
 * | 404 без текста / «Cannot POST» / «Код не найден»      | нет адреса → «скоро», есть служба → «Код не найден»/текст   |
 * | покупка рентгена, докупка фрагментов, звёзд, задание  | ровно один POST с телом ПК, путь закодирован                |
 */
class RubiesEconomyTest {

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
        vault.write(MillidaAuth.KEY_ACCESS, "token")
        return MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/'))
    }

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `форма недельного ответа выбирает блок`() {
        val cases = listOf(
            Triple(
                """{"weekKey":40,"resetsAt":"2026-10-05T21:00:00Z","stars":8,"need":10,"prize":{"code":"CAPE","name":"Плащ"},
                   "prizePrice":900,"steps":[{"at":3,"reward":{"kind":"SHARDS","amount":20},"claimed":true},
                   {"at":10,"reward":{"kind":"ITEM","item":{"code":"CAPE","name":"Плащ"}},"claimed":false}],
                   "tasks":[{"code":"hours","title":"Играй","stars":5,"earned":3,"progress":3.5,"target":5,"unit":"ч"}],
                   "plus":false,"plusHoursBoost":2,"boost":{"available":true,"missing":2,"pricePerStar":60,"price":120}}""",
                "path",
                "путь со ступенями — новый блок с докупкой звёзд",
            ),
            Triple(
                """{"hours":1.5,"need":3,"ready":false,"choices":null,"claimedAt":null,"resetsAt":"2026-10-05T21:00:00Z"}""",
                "parcel",
                "служба сейчас отдаёт посылку — старый блок не должен пропасть",
            ),
            Triple("""{"hours":"1","need":3,"resetsAt":"x"}""", "unknown", "часы строкой — чужая форма, а не посылка"),
            Triple("""{"hours":1,"need":0,"resetsAt":"x"}""", "unknown", "порог ноль — делить не на что"),
            Triple("""[]""", "unknown", "список вместо объекта не роняет магазин"),
        )
        for ((body, expected, why) in cases) {
            val kind = when (weeklyOf(json(body))) {
                is WeeklyAny.Path -> "path"
                is WeeklyAny.Parcel -> "parcel"
                WeeklyAny.Unknown -> "unknown"
            }
            assertEquals(why, expected, kind)
        }
        val path = (weeklyOf(json(cases[0].first)) as WeeklyAny.Path).path
        assertEquals("вещь недели — ступень ITEM", 10, path.prizeStep?.at)
        assertFalse("8 из 10 — путь не пройден", path.done)
        assertEquals("докупка — ровно недостающие звёзды", 2, path.boost?.missing)
    }

    @Test
    fun `задания сборок без вещи выбрасываются`() {
        val quests = questsOf(
            json(
                """{"quests":[{"code":"a","title":"A","item":{"code":"HALO","name":"Нимб"},"minutes":10,"needMinutes":60},
                   {"code":"b","title":"B","item":null},{"code":"c","title":"C"},"мусор"]}""",
            ),
        )
        assertEquals("остаётся только задание с вещью — ПК делает так же", listOf("a"), quests.map { it.code })
        assertTrue("ответ без quests — пусто, а не ошибка", questsOf(json("""{"x":1}""")).isEmpty())
    }

    @Test
    fun `состояние задания как на ПК`() {
        val base = PackQuest(code = "q", minutes = 20, needMinutes = 60, item = RubyItem(code = "HALO"))
        val cases = listOf(
            Triple(base.copy(claimed = true, owned = true, done = true), QuestState.Claimed, "забранное важнее «есть»"),
            Triple(base.copy(owned = true, done = true), QuestState.Owned, "вещь уже есть — забирать нечего"),
            Triple(base.copy(done = true), QuestState.Claim, "выполнено — кнопка «Забрать»"),
            Triple(base, QuestState.Play(40), "осталось 40 минут"),
            Triple(base.copy(minutes = 60), QuestState.Play(1), "не done, но минуты вышли — минимум одна, не ноль"),
            Triple(base.copy(minutes = -5), QuestState.Play(60), "отрицательные минуты службы не уменьшают остаток"),
        )
        for ((quest, expected, why) in cases) assertEquals(why, expected, questState(quest))
        assertEquals("без порога полоса полная", 1f, questProgress(base.copy(needMinutes = 0)))
        assertEquals("полоса не вылезает за край", 1f, questProgress(base.copy(minutes = 90)))
    }

    @Test
    fun `подарок новичку празднуется только при свежей выдаче`() {
        val halo = RubyItem(code = "HALO", name = "Нимб")
        val cases = listOf(
            Triple(WelcomeGift(item = halo, claimed = true, granted = true), true, "выдан сейчас"),
            Triple(WelcomeGift(item = halo, claimed = true, granted = null), true, "старая служба без granted — как ПК, показываем"),
            Triple(WelcomeGift(item = halo, claimed = true, granted = false), false, "повтор ничего не выдал — праздник был бы враньём"),
            Triple(WelcomeGift(item = null, claimed = true, granted = true), false, "без вещи показать нечего"),
        )
        for ((gift, expected, why) in cases) assertEquals(why, expected, gift.fresh)
    }

    @Test
    fun `тексты кода автора различают нет адреса и нет кода`() {
        val cases = listOf(
            Triple("сервер ответил 404", false, CreatorClient.SOON),
            Triple("Cannot POST /v2/launcher/creator-code", false, CreatorClient.SOON),
            Triple("сервер ответил 404", true, "Код не найден"),
            Triple("Код не найден", true, "Код не найден"),
            Triple("Код не найден", false, "Код не найден"),
            Triple("Свой код вводить нельзя", true, "Свой код вводить нельзя"),
        )
        for ((raw, live, expected) in cases) {
            assertEquals("«$raw», служба жива: $live", expected, creatorErrorText(raw, live))
        }
        assertTrue("голый Not Found — нет адреса", isMissingRoute("Not Found"))
        assertFalse("текст службы — не отсутствие адреса", isMissingRoute("Код не найден"))
        assertEquals("код приводится к верхнему регистру", "MILLI", normalizeCreatorCode("  milli "))
        assertNull("пустой код не уходит", normalizeCreatorCode("   "))
        assertNull("длиннее 32 — не уходит", normalizeCreatorCode("A".repeat(33)))
    }

    @Test
    fun `рентген-кейс покупается одним POST с id`() = runBlocking {
        reply("""{"balance":40,"item":{"code":"WINGS~gold","name":"Крылья","rarity":"EPIC"},"chestId":"x1"}""")

        val bought = RubiesClient(auth()).buyXray("offer 1")

        val request = server.takeRequest()
        assertEquals("/v2/rubies/xray/buy", request.path)
        assertEquals("POST", request.method)
        assertEquals("кейс называется своим id", "offer 1", Json.parseToJsonElement(request.body.readUtf8()).jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("баланс после покупки", 40L, bought.balance)
        assertEquals("вещь из ответа, а не из витрины", "WINGS~gold", bought.item.code)
        assertEquals("ровно один запрос — повтора покупки нет", 1, server.requestCount)
    }

    @Test
    fun `докупка фрагментов и звёзд уходят с телом ПК`() = runBlocking {
        reply("""{"balance":10,"price":90,"granted":[{"code":"HALO~gold","name":"Нимб"}]}""")
        reply("""{"path":{"stars":10,"need":10,"steps":[],"tasks":[]},"balance":5}""")
        val client = RubiesClient(auth())

        val done = client.completeFragments("HALO~gold")
        val boosted = client.boostWeekly(2)

        val fragments = server.takeRequest()
        assertEquals("/v2/rubies/fragments/complete", fragments.path)
        assertTrue("вещь-расцветка кодом", fragments.body.readUtf8().contains("\"code\":\"HALO~gold\""))
        assertEquals("HALO~gold", done.granted.single().code)
        val boost = server.takeRequest()
        assertEquals("/v2/rubies/weekly/boost", boost.path)
        assertEquals("звёзды числом", "2", Json.parseToJsonElement(boost.body.readUtf8()).jsonObject["stars"]?.jsonPrimitive?.content)
        assertTrue("путь после докупки пройден", boosted.path.done)
    }

    @Test
    fun `задание сборки забирается по закодированному коду`() = runBlocking {
        reply("""{"quest":{"code":"pack a","claimed":true,"item":{"code":"HALO"}},"item":{"code":"HALO","name":"Нимб"}}""")

        val claim = RubiesClient(auth()).claimQuest("pack a")

        assertEquals("/v2/rubies/quests/pack%20a/claim", server.takeRequest().path)
        assertTrue(claim.quest.claimed)
        assertEquals("задание выдаёт вещь целиком, а не фрагменты", "HALO", claim.item?.code)
    }

    @Test
    fun `код автора без адреса на службе — баннер в режиме скоро`() = runBlocking {
        reply("""{"statusCode":404,"message":"Cannot GET /v2/launcher/creator-code","error":"Not Found"}""", 404)
        reply("""{"code":null}""")
        reply("""{"code":"MILLI","name":"Милли","since":"2026-09-24T10:00:00Z","bonus":{"chestId":"c1","tier":"RARE"}}""")
        val creators = CreatorClient(auth())

        val missing = creators.load()
        val empty = creators.load()
        val answer = creators.set(" milli ", live = true)

        assertFalse("нет адреса — служба не жива, ввод ответит «скоро»", missing.live)
        assertTrue("служба ответила — жива", empty.live)
        assertNull("code:null — никого не поддерживает", empty.current)
        server.takeRequest()
        server.takeRequest()
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertTrue("код уходит обрезанным и заглавным", post.body.readUtf8().contains("\"code\":\"MILLI\""))
        assertEquals("сундук за первый код", "RARE", answer.bonus?.tier)
    }
}
