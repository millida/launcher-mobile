package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.catalog.MillidaCatalog
import net.millida.launcher.core.catalog.MillidaFilters
import net.millida.launcher.core.catalog.Pricing
import net.millida.launcher.core.mojang.MojangJson
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class MillidaCatalogParseTest {

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

    private fun catalog(token: String? = null): MillidaCatalog {
        val vault = MemoryVault()
        token?.let { vault.write(MillidaAuth.KEY_ACCESS, it) }
        return MillidaCatalog(MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/')))
    }

    @Test
    fun `витрина разбирается как на сайте, мусорные строки отбрасываются`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"items":[
                  {"slug":"iris","title":"Iris Shaders — скачать мод на Fabric","versions":["1.21.1"," ",""],
                   "loaders":["Fabric"],"pricing":"ONE_TIME","priceKopecks":4900,"launcherOnly":true,"downloads":12},
                  {"slug":"../evil","title":"x"},
                  {"slug":"plain","launcherOnly":"true","pricing":"WEIRD"},
                  "junk"
                ],"page":2,"pages":5,"total":41}
                """.trimIndent()
            )
        )

        val listing = catalog().listing("mods", "", 2)

        assertEquals("/v2/catalog/listing?section=mods&page=2&perPage=20", server.takeRequest().path)
        assertEquals("карточка с путём в slug и не-объект отброшены", listOf("iris", "plain"), listing.items.map { it.slug })
        val iris = listing.items[0]
        assertEquals("SEO-хвост названия срезается", "Iris Shaders", iris.title)
        assertEquals("пустые версии не превращаются в бейджи", listOf("1.21.1"), iris.versions)
        assertEquals("загрузчики приводятся к нижнему регистру", listOf("fabric"), iris.loaders)
        assertEquals(Pricing.ONE_TIME, iris.pricing)
        assertEquals(4900L, iris.priceKopecks)
        assertTrue("платный по цене и типу", iris.paid)
        assertTrue(iris.launcherOnly)
        assertEquals(12L, iris.downloads)

        val plain = listing.items[1]
        assertEquals("без названия показывается slug", "plain", plain.title)
        assertFalse("строка «true» не флаг — иначе бесплатное спряталось бы в лаунчер", plain.launcherOnly)
        assertEquals("незнакомая цена — бесплатно, а не падение", Pricing.FREE, plain.pricing)
        assertFalse(plain.paid)

        assertEquals(2, listing.page)
        assertEquals(5, listing.pages)
        assertEquals(41, listing.total)
    }

    @Test
    fun `страница без счётчиков и ошибки витрины`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"slug":"one"}]}"""))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"Каталог на обслуживании"}"""))

        val bare = catalog().listing("mods", "", 3, MillidaFilters())
        assertEquals("без page — запрошенная", 3, bare.page)
        assertEquals("без pages — последняя и есть текущая", 3, bare.pages)
        assertEquals("без total — сколько пришло", 1, bare.total)

        val wrongShape = runCatching { catalog().listing("mods", "", 1) }
        assertEquals("каталог Millida ответил не тем — попробуй позже", wrongShape.exceptionOrNull()?.message)

        val down = runCatching { catalog().listing("mods", "", 1) }
        assertEquals("текст ошибки сервера доходит до игрока", "Каталог на обслуживании", down.exceptionOrNull()?.message)
    }

    @Test
    fun `карточка материала и его файлы`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"slug":"iris","title":"Iris","sourceUrl":"https://modrinth.com/mod/iris","articleSlug":"iris-guide",
                 "files":[
                   {"id":"f1","version":"1.8","gameVersions":["1.21.1"],"primaryGameVersion":"1.21.1",
                    "loaders":["Fabric","Quilt"],"fileName":"iris.jar","size":1000,"sha1":"abc","mirrored":true,"releasedAt":"2026-01-01"},
                   {"version":"без id"},
                   {"id":"f2","mirrored":"yes","size":"big"}
                 ],
                 "pricing":"SUBSCRIPTION","priceKopecks":null,"icon":"","launcherOnly":false}
                """.trimIndent()
            )
        )

        val item = catalog().item("iris")

        assertEquals("/v2/catalog/items/iris", server.takeRequest().path)
        assertEquals("файл без id не скачать — он выброшен", listOf("f1", "f2"), item.files.map { it.id })
        val (first, second) = item.files
        assertEquals(listOf("fabric", "quilt"), first.loaders)
        assertTrue("файл на нашем зеркале", first.mirrored)
        assertEquals(1000L, first.size)
        assertEquals("abc", first.sha1)
        assertFalse("строка вместо флага не делает файл зеркальным", second.mirrored)
        assertEquals("размер строкой — ноль, а не падение", 0L, second.size)
        assertEquals(Pricing.SUBSCRIPTION, item.pricing)
        assertNull("null в цене — нет цены", item.priceKopecks)
        assertNull("пустая иконка — нет иконки", item.icon)
        assertEquals("iris-guide", item.articleSlug)
    }

    @Test
    fun `адрес материала проверяется до запроса`() = runBlocking {
        data class Case(val slug: String, val valid: Boolean, val why: String)

        val cases = listOf(
            Case("iris", true, "обычный slug"),
            Case("A-b_9", true, "буквы, цифры, дефис и подчёркивание"),
            Case("a" + "b".repeat(127), true, "128 знаков — ещё можно"),
            Case("a" + "b".repeat(128), false, "129 знаков — уже нет"),
            Case("-iris", false, "начинаться с дефиса нельзя"),
            Case("", false, "пустой slug"),
            Case("../admin", false, "выход из пути каталога"),
            Case("iris/../x", false, "слэш внутри"),
            Case("ирис", false, "кириллица в адрес не пускается"),
        )
        for (case in cases) {
            assertEquals("«${case.slug.take(20)}»: ${case.why}", case.valid, MillidaCatalog.catalogSlug(case.slug))
        }

        val refused = runCatching { catalog().item("../admin") }
        assertEquals("некорректный адрес материала: ../admin", refused.exceptionOrNull()?.message)
        assertNull("для чужого slug оффера нет", catalog().premiumOffer("../admin"))
        assertEquals("ни одного запроса с плохим адресом", 0, server.requestCount)
    }

    @Test
    fun `платные сборки берутся только с флагом доступа`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {"slug":"arcania","title":"Arcania","accessRequired":true,"game":"1.20.1","loader":"forge","ownLaunch":true,"downloads":7},
                  {"slug":"free","accessRequired":false},
                  {"slug":"bad slug","accessRequired":true},
                  {"slug":"noflag"},
                  {"slug":"quoted","accessRequired":"true"}
                ]
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))

        val packs = catalog().premiumPacks()

        assertEquals("только сборки с настоящим флагом доступа", listOf("arcania"), packs.map { it.slug })
        val arcania = packs.single()
        assertTrue(arcania.premium)
        assertTrue("платная сборка идёт только через лаунчер", arcania.launcherOnly)
        assertTrue("своя игра и запуск", arcania.ownLaunch)
        assertEquals(listOf("1.20.1"), arcania.versions)
        assertEquals(listOf("forge"), arcania.loaders)

        assertTrue("объект вместо списка — пусто, а не падение", catalog().premiumPacks().isEmpty())
    }

    @Test
    fun `оффер платной сборки`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"id":"","plans":[
                  {"id":"p1","title":"Месяц","priceKopecks":29900,"period":"month"},
                  {"title":"без id"},
                  {"id":"p2","title":"Год","priceKopecks":199000,"period":"year"}
                 ],"acceptsKeys":true,"priceKopecks":9900,"owned":true}
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setResponseCode(404))

        val offer = catalog().premiumOffer("arcania")

        assertEquals("/v2/launcher/premium/arcania", server.takeRequest().path)
        assertEquals("без id оффер называется по slug", "arcania", offer?.id)
        assertEquals("уровень без id не купить", listOf("p1", "p2"), offer?.plans?.map { it.id })
        assertEquals(true, offer?.planChoice)
        assertEquals(true, offer?.acceptsKeys)
        assertEquals("купленное не показывает цену", "", offer?.priceLabel())

        assertNull("сборка без уровней — не ошибка, а отсутствие оффера", catalog().premiumOffer("solo"))
    }

    @Test
    fun `ключ проверяется до отправки и уходит обрезанным`() = runBlocking {
        data class Case(val code: String, val why: String)

        val bad = listOf(
            Case("  short  ", "меньше 8 знаков после обрезки"),
            Case("abcdefgh\u0007", "управляющий символ — признак мусора из буфера"),
            Case("k".repeat(129), "длиннее 128"),
        )
        for (case in bad) {
            val result = runCatching { catalog("token").redeem("arcania", case.code) }
            assertEquals(
                "${case.why}: игрок должен услышать, что ключ скопирован не целиком",
                "ключ не похож на настоящий — проверь, что скопировал его целиком",
                result.exceptionOrNull()?.message,
            )
        }
        assertEquals("плохой ключ не уходит на сервер", 0, server.requestCount)

        server.enqueue(MockResponse().setBody("{}"))
        catalog("token").redeem("arcania", "  ABCD-EFGH-1234 \n")

        val request = server.takeRequest()
        assertEquals("/v2/catalog/packs/arcania/redeem", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        val body = MojangJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("ключ уходит без пробелов и перевода строки", "ABCD-EFGH-1234", body["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `покупка несёт ключ повтора и ожидаемую цену`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"message":"Недостаточно средств"}"""))

        val key = MillidaCatalog.newIdempotencyKey()
        catalog("token").buy("iris", key, 4900L)

        val request = server.takeRequest()
        assertEquals("/v2/catalog/items/iris/buy", request.path)
        val body = MojangJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("повтор после обрыва списывает один раз", key, body["idempotencyKey"]?.jsonPrimitive?.content)
        assertEquals("сервер сверяет цену, которую видел игрок", "4900", body["expectedPriceKopecks"]?.jsonPrimitive?.content)

        val poor = runCatching { catalog("token").buy("iris", key, 4900L) }
        assertEquals("Недостаточно средств", poor.exceptionOrNull()?.message)

        assertNotEquals("каждая покупка получает свой ключ", key, MillidaCatalog.newIdempotencyKey())
        assertEquals(36, key.length)
    }

    @Test
    fun `гость не может покупать`() = runBlocking {
        val result = runCatching { catalog().buy("iris", "k", 100L) }
        assertTrue("без входа покупка отклоняется до сети", result.exceptionOrNull() is IOException)
        assertEquals(0, server.requestCount)
    }
}
