package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.cosmetics.CosmeticsClient
import net.millida.launcher.core.cosmetics.WornCosmetic
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

class CosmeticsClientTest {

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

    private fun client(): CosmeticsClient {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, "test-token")
        return CosmeticsClient(MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/')))
    }

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test
    fun `права на вещи берут только строковые коды`() {
        runBlocking {
            reply("""{"items":["HALO","WINGS",5,null,{"code":"X"}],"variants":{"HALO":["gold",1,"ruby"],"WINGS":"all"}}""")
            reply("""{"items":[]}""")
            val cosmetics = client()

            val owned = cosmetics.owned()
            val bare = cosmetics.owned()

            assertEquals("/v2/cosmetics/owned", server.takeRequest().path)
            assertEquals("число, null и объект в списке прав — не вещи", listOf("HALO", "WINGS"), owned.codes)
            assertEquals("расцветки — только строки", listOf("gold", "ruby"), owned.variants?.get("HALO"))
            assertEquals("строка вместо списка расцветок — пустой список, а не падение", emptyList<String>(), owned.variants?.get("WINGS"))
            assertTrue(bare.codes.isEmpty())
            assertNull("нет поля variants — null: старая служба не говорит о расцветках", bare.variants)
        }
    }

    @Test
    fun `надетое ищется по uuid без учёта дефисов`() {
        runBlocking {
            reply(
                """{"players":{"0123456789abcdef0123456789abcdef":[
                    {"id":"HALO","slot":"head","variant":"gold"},
                    {"id":"WINGS","slot":""},
                    {"slot":"back"},
                    "мусор",
                    {"id":"CAPE","slot":"back"}
                ]}}""",
            )

            val worn = client().equipped("01234567-89ab-cdef-0123-456789abcdef")

            val request = server.takeRequest()
            assertEquals("/v2/cosmetics/equipped", request.path)
            assertEquals("POST", request.method)
            val players = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["players"]?.jsonArray
            assertEquals("спрашивается только свой uuid", listOf("01234567-89ab-cdef-0123-456789abcdef"), players?.map { it.jsonPrimitive.content })
            assertEquals(
                "без id или слота вещь не надеть; ключ без дефисов — тот же игрок",
                listOf(WornCosmetic("HALO", "head", "gold"), WornCosmetic("CAPE", "back", "")),
                worn,
            )
        }
    }

    private data class EquippedCase(val body: String, val why: String)

    @Test
    fun `надетое чужого или пустого ответа — пустой список`() {
        runBlocking {
            val cases = listOf(
                EquippedCase("""{"players":{"ffff":[{"id":"HALO","slot":"head"}]}}""", "в ответе другой игрок — его вещи не наши"),
                EquippedCase("""{"ok":true}""", "нет players — ничего не надето"),
                EquippedCase("""{"players":{"abc":"HALO"}}""", "не список у игрока — пусто"),
            )
            val cosmetics = client()
            for (case in cases) {
                reply(case.body)
                assertTrue("${case.body}: ${case.why}", cosmetics.equipped("abc").isEmpty())
            }
        }
    }

    @Test
    fun `пустой uuid не уходит в сеть, а список вместо объекта — ошибка`() {
        runBlocking {
            val cosmetics = client()
            assertTrue("без uuid спрашивать некого", cosmetics.equipped("  ").isEmpty())
            assertEquals("пустой uuid не тратит запрос", 0, server.requestCount)

            reply("""[]""")
            val failed = runCatching { cosmetics.equipped("abc") }.exceptionOrNull()
            assertTrue("ответ не того вида — понятная ошибка", failed is IOException)
            assertTrue(failed!!.message!!.contains("обнови гардероб"))
        }
    }

    @Test
    fun `набор уходит целиком, расцветка только когда выбрана`() {
        runBlocking {
            reply("""{"ok":true}""")

            client().wear(listOf(WornCosmetic("HALO", "head", "gold"), WornCosmetic("CAPE", "back")))

            val request = server.takeRequest()
            assertEquals("/v2/cosmetics/wardrobe", request.path)
            val items = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["items"] as JsonArray
            assertEquals("сервер перезаписывает набор — отправлено всё надетое", 2, items.size)
            assertEquals("gold", items[0].jsonObject["variant"]?.jsonPrimitive?.content)
            assertFalse("пустая расцветка не отправляется как \"\"", items[1].jsonObject.containsKey("variant"))
        }
    }

    @Test
    fun `пустой набор снимает всё, а отказ сервера не выдаётся за успех`() {
        runBlocking {
            reply("""{}""")
            reply("""{"ok":false}""")
            val cosmetics = client()

            cosmetics.wear(emptyList())
            val refused = runCatching { cosmetics.wear(listOf(WornCosmetic("HALO", "head"))) }.exceptionOrNull()

            val items = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["items"] as JsonArray
            assertTrue("снять всё — пустой список, а не отсутствие поля", items.isEmpty())
            assertTrue("ok:false — ошибка, иначе гардероб покажет надетым то, чего нет", refused is IOException)
        }
    }

    @Test
    fun `модель вещи берётся по закодированному id`() {
        runBlocking {
            reply("""{"geometry":{"format_version":"1.12.0"},"geometrySlim":null,"animations":{"a":1}}""")

            val model = client().model("hat 1/x")

            assertEquals(
                "пробел и слеш в id не уводят запрос на другой путь",
                "/v2/cosmetics/models/hat%201%2Fx",
                server.takeRequest().path,
            )
            assertEquals("1.12.0", model.geometry.jsonObject["format_version"]?.jsonPrimitive?.content)
            assertNull("geometrySlim:null — тонкой модели нет", model.geometrySlim)
            assertTrue(model.animations != null)
        }
    }

    @Test
    fun `вещь без модели объясняет, что делать`() {
        runBlocking {
            reply("""{"geometry":null}""")
            reply("""{"animations":{}}""")
            val cosmetics = client()

            val nullGeometry = runCatching { cosmetics.model("HALO") }.exceptionOrNull()
            val missing = runCatching { cosmetics.model("HALO") }.exceptionOrNull()

            for (error in listOf(nullGeometry, missing)) {
                assertTrue("без геометрии рисовать нечего — ошибка, а не пустая фигура: $error", error is IOException)
                assertEquals("у вещи нет модели — обнови гардероб", error!!.message)
            }
        }
    }
}
