package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.catalog.Catalog
import net.millida.launcher.core.catalog.CatalogSection
import net.millida.launcher.core.catalog.CurseForgeCatalog
import net.millida.launcher.core.catalog.MillidaCard
import net.millida.launcher.core.catalog.MillidaCatalog
import net.millida.launcher.core.catalog.ModrinthCatalog
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.HttpError
import net.millida.launcher.core.hub.PhoneBlocked
import net.millida.launcher.core.hub.PhoneBlockedCache
import net.millida.launcher.core.premium.Premium
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.premium.PremiumPack
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class PhoneBlockedTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        PhoneBlocked.set(emptySet())
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        PhoneBlocked.set(emptySet())
        server.shutdown()
    }

    private fun parse(json: String) = PhoneBlocked.parse(MojangJson.parseToJsonElement(json))

    @Test
    fun `ответ сервера разбирается в набор id, мусор и пустота дают пустой набор`() {
        assertEquals(
            setOf("rlcraft", "1kvo5zza", "238222"),
            parse("""{"updatedAt":"2026-10-20T10:00:00Z","packs":[{"id":"rlcraft","launches":9,"crashRate":0.86},{"id":" 1KVo5zza "},{"id":"238222"}]}"""),
        )
        assertEquals("пустой список", emptySet<String>(), parse("""{"packs":[]}"""))
        assertEquals("нет packs", emptySet<String>(), parse("""{"updatedAt":"x"}"""))
        assertEquals("не объект", emptySet<String>(), parse("""[1,2]"""))
        assertEquals("null вместо тела", emptySet<String>(), PhoneBlocked.parse(null))
        assertEquals(
            "строки без id, числа и пустые id пропускаются",
            setOf("ok"),
            parse("""{"packs":[1,"x",{"launches":3},{"id":""},{"id":42},{"id":null},{"id":"ok"}]}"""),
        )
    }

    @Test
    fun `правило скрытия сравнивает слаг каталога, id Modrinth и число CurseForge`() {
        PhoneBlocked.set(setOf("rlcraft", "1kvo5zza", "238222"))
        assertTrue(PhoneBlocked.hides("RLCraft"))
        assertTrue(PhoneBlocked.hides("1KVo5zza"))
        assertTrue(PhoneBlocked.hides("238222"))
        assertTrue("достаточно одного совпавшего ключа", PhoneBlocked.hides("other", "rlcraft"))
        assertFalse(PhoneBlocked.hides("oneblock-metalabs"))
        assertFalse(PhoneBlocked.hides(null, ""))
        PhoneBlocked.set(emptySet())
        assertFalse("пустой список ничего не прячет", PhoneBlocked.hides("rlcraft"))
    }

    @Test
    fun `каталог Millida, полки хаба и витрина не предлагают заблокированное`() {
        PhoneBlocked.set(setOf("rlcraft"))
        val cards = listOf(MillidaCard("rlcraft", "RLCraft"), MillidaCard("oneblock-metalabs", "OneBlock"))
        assertEquals(listOf("oneblock-metalabs"), MillidaCatalog.forPhone(cards).map { it.slug })
        val packs = listOf(PremiumPack("rlcraft", "RLCraft", slug = "rlcraft"), PremiumPack("ob", "OneBlock", slug = "oneblock-metalabs"))
        assertEquals(listOf("ob"), Premium.forPhone(packs).map { it.id })
        assertEquals("без списка видны обе", 2, run { PhoneBlocked.set(emptySet()); Premium.forPhone(packs).size })
    }

    @Test
    fun `поиск сборок Modrinth и CurseForge прячет заблокированные, моды не трогает`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/mr/v2/search") -> MockResponse().setBody(
                    """{"hits":[{"project_id":"1KVo5zza","slug":"bad-pack","title":"Bad","downloads":9},{"project_id":"good1","slug":"good","title":"Good","downloads":1}],"total_hits":2}""",
                )
                request.path.orEmpty().startsWith("/cf/v1/mods/search") -> MockResponse().setBody(
                    """{"data":[{"id":238222,"name":"CF Bad","downloadCount":5},{"id":5,"name":"CF Good","downloadCount":4}],"pagination":{"totalCount":2}}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val downloader = Downloader(attempts = 1)
        val catalog = Catalog(
            downloader = downloader,
            modrinth = ModrinthCatalog(downloader, server.url("/mr/v2").toString().trimEnd('/')),
            curseforge = CurseForgeCatalog(downloader, server.url("/cf").toString().trimEnd('/')),
            mirrorBase = server.url("/dl").toString().trimEnd('/'),
        )
        PhoneBlocked.set(setOf("1kvo5zza", "238222"))

        val packs = catalog.searchPage("", CatalogSection.MODPACK, null, emptyList())
        assertEquals(listOf("CF Good", "Good"), packs.items.map { it.title })

        val mods = catalog.searchPage("", CatalogSection.MOD, null, emptyList())
        assertEquals("в модах список не действует", 4, mods.items.size)
    }

    @Test
    fun `обновление раз в час, при сетевой ошибке остаётся прошлый список, 404 снимает скрытие`() = runBlocking {
        val hour = PhoneBlocked.REFRESH_MS
        val old = PhoneBlockedCache(setOf("a"), 1_000)
        assertFalse(PhoneBlocked.due(old, 1_000 + hour - 1))
        assertTrue(PhoneBlocked.due(old, 1_000 + hour))
        assertTrue(PhoneBlocked.due(null, 5))

        var asked = 0
        val fresh = PhoneBlocked.refresh(old, 2_000) { asked++; setOf("b") }
        assertSame("до часа сервер не спрашивают", old, fresh)
        assertEquals(0, asked)

        val later = 1_000 + hour
        assertEquals(setOf("b"), PhoneBlocked.refresh(old, later) { setOf("b") }?.ids)
        assertSame("нет сети — прошлый кэш", old, PhoneBlocked.refresh(old, later) { throw IOException("offline") })
        assertSame("500 — тоже прошлый кэш", old, PhoneBlocked.refresh(old, later) { throw HttpError(500, "u") })
        assertEquals("404 — ничего не прячем", emptySet<String>(), PhoneBlocked.refresh(old, later) { throw HttpError(404, "u") }?.ids)
        assertNull("первый запуск без сети — кэша нет", PhoneBlocked.refresh(null, later) { throw IOException("offline") })
    }

    @Test
    fun `кэш в настройках переживает кодирование`() {
        val ids = setOf("rlcraft", "1kvo5zza", "238222")
        assertEquals(ids, PhoneBlocked.decode(PhoneBlocked.encode(ids)))
        assertEquals(emptySet<String>(), PhoneBlocked.decode(null))
        assertEquals(emptySet<String>(), PhoneBlocked.decode(""))
    }
}
