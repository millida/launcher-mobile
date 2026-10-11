package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.skins.CatalogSkin
import net.millida.launcher.core.skins.ShowcaseCard
import net.millida.launcher.core.skins.ShowcaseKind
import net.millida.launcher.core.skins.SkinCatalog
import net.millida.launcher.core.skins.SkinShowcase
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

class SkinShowcaseTest {

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

    private fun guest(): MillidaAuth =
        MillidaAuth(OkHttpClient(), MemoryVault(), server.url("/v2").toString().trimEnd('/'))

    private data class ParseCase(val body: String, val names: List<String>, val why: String)

    @Test
    fun `витрина скинов разбирает только карточки с ником`() {
        val cases = listOf(
            ParseCase("""[{"name":"Notch","textureId":"t1","model":"slim","views":12}]""", listOf("Notch"), "обычная карточка"),
            ParseCase("""[{"textureId":"t1"},{"name":"Dream"}]""", listOf("Dream"), "без ника карточку нечем подписать"),
            ParseCase("""[{"name":42},{"name":"jeb_"}]""", listOf("jeb_"), "ник числом — мусор, а не игрок"),
            ParseCase("""[1,"x",null,{"name":"Alex"}]""", listOf("Alex"), "строки массива не того вида пропускаются, а не роняют экран"),
            ParseCase("""{"items":[{"name":"Notch"}]}""", emptyList(), "объект вместо списка — пустая витрина, не падение"),
            ParseCase("""[]""", emptyList(), "пустая витрина — не ошибка"),
        )
        for (case in cases) {
            val parsed = SkinShowcase.parse(Json.parseToJsonElement(case.body))
            assertEquals("ответ ${case.body}: ${case.why}", case.names, parsed.map { it.name })
        }
        assertTrue("null вместо тела — пустая витрина", SkinShowcase.parse(null).isEmpty())
    }

    @Test
    fun `пропущенные поля карточки получают безопасные значения`() {
        val card = SkinShowcase.parse(Json.parseToJsonElement("""[{"name":"Notch","views":"много","model":null}]""")).single()

        assertEquals("нет textureId — пустая строка, скин возьмётся по нику", "", card.textureId)
        assertEquals("model:null не превращается в строку «null»", "", card.model)
        assertEquals("просмотры не числом — ноль, а не исключение", 0L, card.views)

        val counted = SkinShowcase.parse(Json.parseToJsonElement("""[{"name":"Dream","views":9000000000}]""")).single()
        assertEquals("просмотры больше Int не обрезаются", 9_000_000_000L, counted.views)
    }

    private data class UrlCase(val card: ShowcaseCard, val url: String, val why: String)

    @Test
    fun `адрес текстуры витрины не выходит из своего пути`() {
        val api = "https://api.millida.net/v2"
        val cases = listOf(
            UrlCase(ShowcaseCard("Notch", "abc", "classic", 0), "$api/heads/texture/abc?kind=skin", "с textureId берётся сама текстура"),
            UrlCase(ShowcaseCard("Notch", "", "classic", 0), "$api/heads/skin/Notch", "без textureId скин берётся по нику, как на ПК"),
            UrlCase(ShowcaseCard("Mr Smith", "", "classic", 0), "$api/heads/skin/Mr%20Smith", "пробел кодируется %20, а не плюсом"),
            UrlCase(ShowcaseCard("x", "../admin", "classic", 0), "$api/heads/texture/..%2Fadmin?kind=skin", "слеш в id не уводит запрос на чужой адрес"),
            UrlCase(ShowcaseCard("x", "a?kind=cape", "classic", 0), "$api/heads/texture/a%3Fkind%3Dcape?kind=skin", "id не подменяет параметры запроса"),
        )
        for (case in cases) {
            assertEquals("${case.card}: ${case.why}", case.url, SkinShowcase.skinUrl(case.card, api))
        }
        assertEquals(
            "косая черта в конце базы не удваивается",
            "https://h/v2/heads/texture/abc?kind=skin",
            SkinShowcase.skinUrl(ShowcaseCard("n", "abc", "", 0), "https://h/v2/"),
        )
    }

    @Test
    fun `карточка витрины становится карточкой каталога с префиксом ключа`() {
        val skin = SkinShowcase.catalogSkin(ShowcaseCard("Notch", "abc", "classic", 5))

        assertEquals("префикс sc: не даёт ключу совпасть с карточкой каталога по нику", "sc:Notch", skin.key)
        assertEquals("Notch", skin.label)
        assertEquals("Notch", skin.nick)
        assertTrue("адрес — по textureId", skin.url.endsWith("/heads/texture/abc?kind=skin"))
    }

    @Test
    fun `витрина запрашивается гостем с видом и лимитом`() {
        runBlocking {
            server.enqueue(MockResponse().setBody("""[{"name":"Notch","textureId":"t","model":"classic","views":3}]"""))

            val cards = SkinShowcase(guest()).list(ShowcaseKind.NEW)

            val request = server.takeRequest()
            assertEquals("вид и лимит уходят параметрами", "/v2/players/showcase/list?kind=new&limit=${SkinShowcase.DEFAULT_LIMIT}", request.path)
            assertEquals("GET", request.method)
            assertNull("гость не шлёт пустой Authorization", request.getHeader("Authorization"))
            assertEquals(listOf("Notch"), cards.map { it.name })
        }
    }

    @Test
    fun `сбой витрины доходит до экрана текстом сервера`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"Витрина на обслуживании"}"""))

            val failed = runCatching { SkinShowcase(guest()).list(ShowcaseKind.TOP, limit = 5) }.exceptionOrNull()

            assertEquals("игрок видит причину от сервера, а не пустой экран", "Витрина на обслуживании", failed?.message)
        }
    }

    private data class HashCase(val url: String, val hash: String, val why: String)

    @Test
    fun `хеш текстуры вынимается из любого вида ссылки`() {
        val cases = listOf(
            HashCase("https://textures.minecraft.net/texture/abc", "abc", "обычная ссылка Mojang"),
            HashCase("https://textures.minecraft.net/texture/abc/", "abc", "косая черта в конце"),
            HashCase("http://textures.minecraft.net/texture/abc?v=2", "abc", "параметры не входят в хеш"),
            HashCase("abc", "abc", "голый хеш остаётся собой"),
        )
        for (case in cases) assertEquals("${case.url}: ${case.why}", case.hash, SkinCatalog.textureHash(case.url))
    }

    @Test
    fun `плащ аккаунта узнаётся как официальный и не дублируется`() {
        val migrator = SkinCatalog.OFFICIAL_CAPES.first { it.id == "off:migrator" }

        assertEquals(
            "плащ по http от Mojang — тот же «Переселенец»",
            "off:migrator",
            SkinCatalog.officialCapeOf("http://textures.minecraft.net/texture/${migrator.hash}")?.id,
        )
        assertNull("чужая текстура — не официальный плащ", SkinCatalog.officialCapeOf("https://textures.minecraft.net/texture/deadbeef"))
        assertNull("пустой адрес — не плащ", SkinCatalog.officialCapeOf(""))
        assertEquals("адрес плаща собирается на CDN Mojang", "https://textures.minecraft.net/texture/${migrator.hash}", migrator.url)
    }

    @Test
    fun `адреса каталога скинов`() {
        val api = "https://api.millida.net/v2"
        assertEquals("$api/heads/skin/Notch?fallback=none", SkinCatalog.skinUrlStrict("Notch", api))
        assertEquals("размер головы снизу ограничен 8", "$api/heads/avatar/Notch?size=8", SkinCatalog.headUrl("Notch", 1, api))
        assertEquals("размер головы сверху ограничен 512", "$api/heads/avatar/Notch?size=512", SkinCatalog.headUrl("Notch", 4096, api))
        assertEquals(
            "карточка без ссылки и ника показывает Стива, а не пустоту",
            "$api/heads/skin/MHF_Steve",
            SkinCatalog.textureUrl(CatalogSkin(key = "k", label = "l"), api),
        )
        assertEquals(
            "известная ссылка важнее ника",
            "https://textures.minecraft.net/texture/x",
            SkinCatalog.textureUrl(CatalogSkin(key = "k", label = "l", nick = "Notch", url = "https://textures.minecraft.net/texture/x"), api),
        )
        assertEquals("скин по умолчанию — Стив Mojang", SkinCatalog.OFFICIAL_SKINS.first().url, SkinCatalog.DEFAULT_SKIN_URL)
    }

    @Test
    fun `ключи каталога скинов и плащей не повторяются`() {
        val keys = SkinCatalog.GROUPS.flatMap { group -> group.items.map { it.key } }
        val repeated = keys.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("повтор ключа ломает список карточек: $repeated", repeated.isEmpty())

        val capes = SkinCatalog.OFFICIAL_CAPES.map { it.id }
        assertEquals("id плаща — то, что принимает сервер, повтор выдал бы не тот плащ", capes.size, capes.toSet().size)
        assertFalse("в каталоге нет пустых групп", SkinCatalog.GROUPS.any { it.items.isEmpty() })
    }
}
