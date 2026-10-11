package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.skins.WardrobeClient
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
import java.io.IOException

class WardrobeClientTest {

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

    private fun client(): WardrobeClient {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, "test-token")
        return WardrobeClient(MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/')))
    }

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test
    fun `профиль игры разбирается, а пропуски получают значения по умолчанию`() {
        runBlocking {
            reply(
                """{"uuid":"u1","name":"Steve","model":"SLIM","skinUrl":"https://cdn.millida.trade/s.png",
                   "capeUrl":null,"accountNick":"steve_acc","publicSlug":"steve","nameConflict":true}""",
            )
            reply("""{"uuid":"u2","model":"","nameConflict":"да"}""")
            val wardrobe = client()

            val full = wardrobe.profile()
            val sparse = wardrobe.profile()

            val request = server.takeRequest()
            assertEquals("/v2/launcher/game-profile", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertTrue("модель сравнивается без учёта регистра", full.slim)
            assertEquals("capeUrl:null — пусто, а не строка «null»", "", full.capeUrl)
            assertTrue("конфликт ника доходит до экрана", full.nameConflict)
            assertEquals("пустая модель — классическая", "classic", sparse.model)
            assertFalse("классическая модель — не тонкая", sparse.slim)
            assertFalse("конфликт не числом-логикой — не конфликт, а не падение", sparse.nameConflict)
            assertEquals("нет имени — пустая строка", "", sparse.name)
        }
    }

    @Test
    fun `текстура уходит base64 одним запросом с моделью и именем`() {
        runBlocking {
            reply("""{"skinUrl":"https://cdn.millida.trade/new.png"}""")

            val profile = client().applyTexture("skin", "iVBORw0KGgo=", slim = true, name = "Мой скин")

            val request = server.takeRequest()
            assertEquals("/v2/launcher/game-texture", request.path)
            assertEquals("POST", request.method)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("skin", body["type"]?.jsonPrimitive?.content)
            assertEquals("картинка не перекодируется по дороге", "iVBORw0KGgo=", body["pngBase64"]?.jsonPrimitive?.content)
            assertEquals("true", body["slim"]?.jsonPrimitive?.content)
            assertEquals("Мой скин", body["name"]?.jsonPrimitive?.content)
            assertEquals("сервер не назвал модель — остаётся выбранная игроком", "slim", profile.model)
            assertEquals("https://cdn.millida.trade/new.png", profile.skinUrl)
        }
    }

    @Test
    fun `гардероб пропускает вещи без id и подставляет вид и модель`() {
        runBlocking {
            reply(
                """{"items":[
                    {"id":"w1","kind":"CAPE","name":"Плащ","url":"https://c/1.png"},
                    {"id":"","kind":"skin","name":"Пустой"},
                    {"id":"w2","name":"Скин","url":"https://c/2.png","model":"slim","source":"upload"}
                ]}""",
            )
            reply("""{"total":0}""")
            val wardrobe = client()

            val items = wardrobe.wardrobe()
            val empty = wardrobe.wardrobe()

            assertEquals("вещь без id нельзя ни надеть, ни удалить", listOf("w1", "w2"), items.map { it.id })
            assertTrue("вид сравнивается без учёта регистра", items[0].isCape)
            assertEquals("без вида — это скин", "skin", items[1].kind)
            assertEquals("classic", items[0].model)
            assertEquals("upload", items[1].source)
            assertTrue("ответ без items — пустой гардероб, не ошибка", empty.isEmpty())
        }
    }

    @Test
    fun `надеть и убрать вещь идут на её собственный адрес`() {
        runBlocking {
            reply("{}")
            reply("{}")
            reply("{}")
            val wardrobe = client()

            wardrobe.applyWardrobe("w1")
            wardrobe.removeWardrobe("w1")
            wardrobe.applyCape("c7")

            val apply = server.takeRequest()
            assertEquals("/v2/launcher/wardrobe/w1/apply", apply.path)
            assertEquals("POST", apply.method)
            val remove = server.takeRequest()
            assertEquals("/v2/launcher/wardrobe/w1", remove.path)
            assertEquals("удаление — DELETE, а не POST, иначе сервер наденет вещь", "DELETE", remove.method)
            assertEquals("/v2/launcher/wardrobe/capes/c7/apply", server.takeRequest().path)
        }
    }

    private data class CapesCase(val body: String, val ids: List<String>, val why: String)

    @Test
    fun `каталог плащей принимает и список, и объект с items`() {
        runBlocking {
            val cases = listOf(
                CapesCase("""[{"id":"a"},{"id":""}]""", listOf("a"), "голый список; плащ без id пропадает"),
                CapesCase("""{"items":[{"id":"b"}]}""", listOf("b"), "обёртка items, как у остальных ручек"),
                CapesCase("""{"total":3}""", emptyList(), "объект без items — пустой список, а не падение"),
            )
            val wardrobe = client()
            for (case in cases) {
                reply(case.body)
                assertEquals("${case.body}: ${case.why}", case.ids, wardrobe.capes().map { it.id })
            }
        }
    }

    @Test
    fun `закрытый плащ показывает прогресс, мусор в числах не роняет экран`() {
        runBlocking {
            reply(
                """[{"id":"c1","name":"Ветеран","url":"https://c/1.png","rarity":"EPIC","requirement":"Сыграй 10 часов",
                    "unlocked":false,"progressCurrent":3,"progressTarget":10,"progressUnit":"hours"},
                   {"id":"c2","unlocked":"yes","progressCurrent":"много","progressTarget":null}]""",
            )

            val capes = client().capes()

            val locked = capes[0]
            assertFalse(locked.unlocked)
            assertEquals("прогресс виден, чтобы игрок знал, сколько осталось", 3, locked.progressCurrent)
            assertEquals(10, locked.progressTarget)
            assertEquals("hours", locked.progressUnit)
            assertEquals("Сыграй 10 часов", locked.requirement)
            val broken = capes[1]
            assertFalse("«yes» — не логическое значение, плащ не открывается сам", broken.unlocked)
            assertEquals("число строкой — ноль", 0, broken.progressCurrent)
            assertEquals("null — ноль", 0, broken.progressTarget)
        }
    }

    @Test
    fun `плащи Mojang берутся без сессии и без битых записей`() {
        runBlocking {
            reply(
                """{"items":[{"id":"m1","url":"https://textures.minecraft.net/texture/x"},
                   {"id":"m2","name":"Без картинки"},{"url":"https://textures.minecraft.net/texture/y"},5]}""",
            )

            val capes = client().mojangCapes()

            val request = server.takeRequest()
            assertEquals("/v2/launcher/wardrobe/capes/mojang", request.path)
            assertNull("общий список не привязывается к сессии игрока", request.getHeader("Authorization"))
            assertEquals("без id или картинки плащ не показать", listOf("m1"), capes.map { it.id })
            assertEquals("без имени подписью служит id", "m1", capes.single().name)
            assertTrue("плащи Mojang носят все", capes.single().unlocked)
        }
    }

    @Test
    fun `плащи Mojang при ответе не тем видом пусты`() {
        runBlocking {
            reply("""[{"id":"m1","url":"https://x"}]""")
            assertTrue("список вместо объекта — пусто, а не падение", client().mojangCapes().isEmpty())
        }
    }

    @Test
    fun `награды читаются из списка, а несчитаемые поля не ломают разбор`() {
        runBlocking {
            reply(
                """{"items":[{"code":"PLAY10","title":"10 часов","task":"Сыграй","goal":10,"progress":4,"done":false},
                   {"title":"без кода"},
                   {"code":"FRIEND","unit":"","claimed":true,"capeUrl":"https://c/f.png"}]}""",
            )

            val rewards = client().rewards()

            assertEquals("награда без кода не забирается — её нет", listOf("PLAY10", "FRIEND"), rewards.map { it.code })
            assertEquals(4, rewards[0].progress)
            assertEquals("пустая единица — штуки", "count", rewards[1].unit)
            assertTrue(rewards[1].claimed)
        }
    }

    @Test
    fun `получение награды не выдаёт успех, если плащ не выдан`() {
        runBlocking {
            reply("""{"code":"PLAY10","title":"Ветеран","claimed":true}""")
            reply("""{"code":"PLAY10","title":"Ветеран","claimed":false}""")
            reply("""{"code":"PLAY10","claimed":false}""")
            reply("""{"ok":true}""")
            val wardrobe = client()

            val claimed = wardrobe.claim("PLAY10")
            val refused = runCatching { wardrobe.claim("PLAY10") }.exceptionOrNull()
            val untitled = runCatching { wardrobe.claim("PLAY10") }.exceptionOrNull()
            val empty = runCatching { wardrobe.claim("PLAY10") }.exceptionOrNull()

            assertEquals("/v2/launcher/rewards/PLAY10/claim", server.takeRequest().path)
            assertTrue(claimed.claimed)
            assertTrue("claimed:false — ошибка, иначе игрок увидит «получено» без плаща", refused is IOException)
            assertTrue("в тексте назван плащ", refused!!.message!!.contains("Ветеран"))
            assertTrue("без названия плащ называется кодом", untitled!!.message!!.contains("PLAY10"))
            assertTrue("ответ без награды — тоже ошибка", empty!!.message!!.contains("не вернул награду"))
        }
    }

    @Test
    fun `истёкшая сессия гардероба — это требование входа`() {
        runBlocking {
            reply("""{"message":"Unauthorized"}""", 401)

            val failed = runCatching { client().wardrobe() }.exceptionOrNull()

            assertTrue("401 без refresh — Unauthorized, экран предложит войти, а не «ошибка»", failed is Unauthorized)
        }
    }

    @Test
    fun `ошибка сервера гардероба доходит текстом`() {
        runBlocking {
            reply("""{"message":["Слишком много запросов"]}""", 429)

            val failed = runCatching { client().profile() }.exceptionOrNull()

            assertTrue(failed is IOException)
            assertEquals("первый текст из списка сообщений", "Слишком много запросов", failed!!.message)
        }
    }
}
