package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.skins.MinecraftServicesClient
import net.millida.launcher.core.skins.SkinCatalog
import net.millida.launcher.core.skins.SkinImport
import net.millida.launcher.core.skins.TextureError
import net.millida.launcher.core.skins.TextureKind
import net.millida.launcher.core.skins.TextureLibrary
import net.millida.launcher.core.skins.TexturePng
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Everything a texture has to survive before a player sees it: the picture is
 * checked, the link it came from is checked, and the library remembers what the
 * player corrected by hand.
 */
class TextureLibraryTest {

    @get:Rule
    val temp = TemporaryFolder()

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

    private fun png(width: Int, height: Int): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll(listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
        out.addAll(listOf(0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() })
        out.addAll(listOf(0, 0, 0, 13).map { it.toByte() })
        out.addAll("IHDR".map { it.code.toByte() })
        out.addAll(be32(width))
        out.addAll(be32(height))
        out.addAll(listOf(8, 6, 0, 0, 0).map { it.toByte() })
        return out.toByteArray()
    }

    private fun be32(value: Int): List<Byte> = listOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    @Test
    fun `размер PNG решает, примут текстуру или нет`() {
        // (kind, width, height, accepted, why this case is pinned)
        val cases: List<Triple<TextureKind, Pair<Int, Int>, Pair<Boolean, String>>> = listOf(
            Triple(TextureKind.SKIN, 64 to 64, true to "современный скин с двумя слоями"),
            Triple(TextureKind.SKIN, 64 to 32, true to "старый односкойный скин ещё встречается в сети"),
            Triple(TextureKind.SKIN, 128 to 128, true to "HD-скин: сетка кратна ванильной, UV просто масштабируются"),
            Triple(TextureKind.SKIN, 64 to 128, false to "перевёрнутая пропорция рисует фигуру рваной"),
            Triple(TextureKind.SKIN, 32 to 32, false to "меньше базовой сетки — половина UV уходит за край"),
            Triple(TextureKind.SKIN, 100 to 100, false to "некратный размер даёт пиксели разной ширины"),
            Triple(TextureKind.SKIN, 1 to 1, false to "иконка вместо скина"),
            Triple(TextureKind.CAPE, 64 to 32, true to "плащ Mojang — это лист 64x32"),
            Triple(TextureKind.CAPE, 128 to 64, true to "HD-плащ кратен тому же листу"),
            Triple(TextureKind.CAPE, 64 to 64, false to "квадрат — это скин, а не плащ"),
            Triple(TextureKind.CAPE, 22 to 17, false to "старый обрезок плаща не тянется на UV"),
        )

        for ((kind, size, verdict) in cases) {
            val (width, height) = size
            val (expected, why) = verdict
            val result = runCatching { TexturePng.validate(kind, png(width, height)) }
            assertEquals(
                "$kind ${width}x$height: ожидали ${if (expected) "приём" else "отказ"}, " +
                    "получили ${result.exceptionOrNull()?.message ?: "приём"}. Кейс закреплён: $why",
                expected,
                result.isSuccess,
            )
        }
    }

    @Test
    fun `не-PNG и слишком большой файл отклоняются с понятным текстом`() {
        val notPng = "GIF89a".toByteArray()
        val gif = runCatching { TexturePng.validate(TextureKind.SKIN, notPng) }.exceptionOrNull()
        assertTrue("GIF обязан отклоняться: проверяем сигнатуру, а не расширение", gif is TextureError)
        assertTrue("текст ошибки должен объяснять игроку, что нужен PNG", gif!!.message!!.contains("PNG"))

        val empty = runCatching { TexturePng.validate(TextureKind.SKIN, ByteArray(0)) }.exceptionOrNull()
        assertTrue("пустой файл — не текстура", empty is TextureError)

        val huge = png(64, 64) + ByteArray(TexturePng.MAX_BYTES)
        val big = runCatching { TexturePng.validate(TextureKind.SKIN, huge) }.exceptionOrNull()
        assertTrue("файл больше предела отклоняется до отправки на сервер", big is TextureError)
        assertTrue("в тексте должен быть предел в мегабайтах", big!!.message!!.contains("2 МБ"))

        val broken = runCatching { TexturePng.decode("не base64 совсем ***") }.exceptionOrNull()
        assertTrue("битый base64 не должен уронить экран", broken is TextureError)
    }

    @Test
    fun `ссылка принимается только с проверенных хостов`() {
        // (url, accepted, why this case is pinned)
        val cases: List<Triple<String, Boolean, String>> = listOf(
            Triple("https://textures.minecraft.net/texture/abc", true, "CDN Mojang — родной дом скинов"),
            Triple("https://api.millida.net/v2/heads/skin/Notch", true, "импорт по нику идёт через наш рендер"),
            Triple("https://cdn.millida.trade/launcher/capes/1.png", true, "плащи гардероба лежат тут"),
            Triple("https://cdn.modrinth.com/data/x/icon.png", true, "ассеты сборок отдаёт CDN Modrinth"),
            Triple("  https://api.millida.net/v2/heads/skin/Notch  ", true, "вставленная ссылка тащит пробелы"),
            Triple("HTTPS://API.MILLIDA.NET/v2/heads/skin/Notch", true, "хост сравнивается без учёта регистра"),
            Triple("http://api.millida.net/x.png", false, "по http прокси подменит текстуру"),
            Triple("https://evil.example.com/x.png", false, "чужой хост — ровно то, ради чего список и нужен"),
            Triple("https://api.millida.net.evil.com/x.png", false, "трюк с суффиксом: настоящий хост — evil.com"),
            Triple("https://cdn.millida.trade.evil.com/x.png", false, "суффикс .millida.trade не должен совпадать как префикс"),
            Triple("https://127.0.0.1/x.png", false, "петля достаёт сервисы на самом телефоне"),
            Triple("https://localhost:8080/x.png", false, "localhost — та же петля по имени"),
            Triple("https://192.168.1.1/x.png", false, "домашняя сеть: роутер игрока"),
            Triple("https://10.0.0.5/x.png", false, "частный диапазон"),
            Triple("https://169.254.169.254/latest/meta-data", false, "link-local — точка метаданных облака"),
            Triple("https://[::1]/x.png", false, "петля IPv6"),
            Triple("https://[::ffff:127.0.0.1]/x.png", false, "IPv4 внутри IPv6 прячет петлю от наивной проверки"),
            Triple("https://router.local/x.png", false, "имена .local живут внутри сети"),
            Triple("file:///etc/passwd", false, "file: читал бы файлы телефона"),
            Triple("data:image/png;base64,AAAA", false, "data: — это не хост"),
            Triple("https://api.millida.net/\r\nHeader: x", false, "CR/LF — расщепление запроса"),
            Triple("", false, "пустая строка не ссылка"),
        )

        for ((input, expected, why) in cases) {
            val result = runCatching { SkinImport.validateUrl(input) }
            assertEquals(
                "validateUrl(${input.trim()}) вернул ${result.exceptionOrNull()?.message ?: "Ok"}, " +
                    "ожидали ${if (expected) "Ok" else "отказ"}. Кейс закреплён: $why",
                expected,
                result.isSuccess,
            )
        }
    }

    @Test
    fun `каталог Mojang отдаёт непустые записи с рабочим видом ссылок`() {
        assertTrue("официальных скинов должно быть больше одного", SkinCatalog.OFFICIAL_SKINS.size >= 7)
        assertTrue("плащей Mojang в каталоге должно быть больше одного", SkinCatalog.OFFICIAL_CAPES.size >= 12)
        assertTrue("группы каталога не должны быть пустыми", SkinCatalog.GROUPS.all { it.items.isNotEmpty() })

        for (skin in SkinCatalog.OFFICIAL_SKINS) {
            assertTrue("у скина ${skin.key} должно быть имя", skin.label.isNotBlank())
            assertTrue(
                "ссылка на текстуру ${skin.key} должна вести на CDN Mojang: ${skin.url}",
                skin.url.startsWith("https://textures.minecraft.net/texture/"),
            )
            assertEquals(
                "хеш текстуры ${skin.key} — 64 hex-символа, иначе CDN отдаст 404",
                64,
                skin.url.substringAfterLast('/').length,
            )
            assertTrue(
                "ссылка каталога обязана проходить белый список импорта",
                runCatching { SkinImport.validateUrl(skin.url) }.isSuccess,
            )
        }

        for (cape in SkinCatalog.OFFICIAL_CAPES) {
            assertTrue("у плаща ${cape.id} должно быть имя", cape.name.isNotBlank())
            assertTrue(
                "хеш плаща ${cape.id} — 63–64 hex-символа: Mojang отбрасывает ведущий ноль (так у Cobalt)",
                cape.hash.length in 63..64 && cape.hash.all { it in '0'..'9' || it in 'a'..'f' },
            )
            assertTrue(
                "плащ ${cape.id} должен узнаваться по своей же ссылке, иначе каталог задвоится",
                SkinCatalog.officialCapeOf(cape.url)?.id == cape.id,
            )
        }

        val byNick = SkinCatalog.GROUPS.flatMap { it.items }.filter { it.nick.isNotEmpty() }
        assertTrue("карточки по нику должны быть: они переживают смену скина", byNick.isNotEmpty())
        assertTrue(
            "скин по нику берётся с нашего API",
            byNick.all { SkinCatalog.textureUrl(it).startsWith("https://api.millida.net/v2/heads/skin/") },
        )
        assertEquals(
            "ключи каталога должны быть уникальны, иначе список схлопнется при выборе",
            SkinCatalog.GROUPS.flatMap { it.items }.size,
            SkinCatalog.GROUPS.flatMap { it.items }.map { it.key }.toSet().size,
        )
    }

    @Test
    fun `библиотека сохраняет, переименовывает и помнит пометку тонкой модели`() {
        val library = TextureLibrary(temp.newFolder("textures"))
        val saved = library.save(TextureKind.SKIN, "Мой скин.png", png(64, 64), slim = true)
        val entry = saved.first()

        assertEquals("имя файла не должно попасть в карточку вместо названия", "Мой скин", entry.name)
        assertTrue("пометка тонкой модели сохраняется вместе с текстурой", entry.slim)
        assertFalse("пометка ещё не ставилась руками", entry.slimManual)

        val reopened = TextureLibrary(library.dir(TextureKind.SKIN).parentFile).list(TextureKind.SKIN)
        assertEquals("после перезапуска библиотека читается обратно", 1, reopened.size)
        assertTrue("тонкая модель обязана пережить перезапуск", reopened.first().slim)

        val corrected = library.setSlim(TextureKind.SKIN, entry.file, slim = false)
        val manual = corrected.first { it.file == entry.file }
        assertFalse("игрок поправил ширину рук — это и должно остаться", manual.slim)
        assertTrue("ручная правка помечается, чтобы автоопределение её не перетёрло", manual.slimManual)

        val renamed = library.rename(TextureKind.SKIN, entry.file, "Зимний")
        assertEquals("переименование меняет только название", "Зимний", renamed.first().name)
        assertTrue("файл после переименования остаётся тем же", renamed.first().path.isFile)

        val samePicture = library.save(TextureKind.SKIN, "Мой скин", png(64, 64))
        assertEquals("та же картинка не должна плодить записи: имя файла по хешу", 1, samePicture.size)

        val other = library.save(TextureKind.SKIN, "Другой", png(64, 32))
        assertEquals("вторая текстура ложится первой в списке", 2, other.size)
        assertEquals("свежая запись идёт сверху", "Другой", other.first().name)

        val left = library.delete(TextureKind.SKIN, entry.file)
        assertTrue("после удаления записи нет", left.none { it.file == entry.file })
        assertFalse("и файла на диске тоже нет", entry.path.exists())

        val escape = runCatching { library.delete(TextureKind.SKIN, "../../secrets.png") }.exceptionOrNull()
        assertTrue("имя файла с выходом из папки обязано отклоняться", escape is TextureError)
        val parent = runCatching { library.delete(TextureKind.SKIN, "..") }.exceptionOrNull()
        assertTrue("'..' без слэшей — это всё ещё папка выше, а не текстура", parent is TextureError)
        assertEquals("путь из имени не собирается", "etc-passwd", TextureLibrary.fileStem("../../etc/passwd"))
    }

    @Test
    fun `битый PNG не попадает ни в библиотеку, ни на аккаунт`() {
        val library = TextureLibrary(temp.newFolder("textures"))
        val wrong = runCatching { library.save(TextureKind.SKIN, "плохой", png(100, 100)) }.exceptionOrNull()
        assertTrue("текстура неверного размера не должна лечь на диск", wrong is TextureError)
        assertTrue("после отказа библиотека пуста", library.list(TextureKind.SKIN).isEmpty())
    }

    @Test
    fun `импорт по нику берёт скин с нашего API`() = runBlocking {
        val body = Buffer().write(png(64, 64))
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "image/png"))

        val import = SkinImport(apiBase = server.url("/v2").toString().trimEnd('/'))
        val texture = import.byNick("Notch")

        val request = server.takeRequest()
        assertEquals(
            "ник подставляется в путь сервиса скинов, а fallback=none не даёт выдать Стива за найденный скин",
            "/v2/heads/skin/Notch?fallback=none",
            request.path,
        )
        assertEquals("имя импортированной текстуры — это ник", "Notch", texture.name)
        assertEquals("картинка приходит как есть, без пережатия", 64, texture.shape.width)
        assertTrue("64x64 — современный скин со вторым слоем", texture.shape.hasOverlay)

        val bad = runCatching { import.byNick("не ник") }.exceptionOrNull()
        assertTrue("мусор вместо ника не уходит в сеть", bad is TextureError)
    }

    @Test
    fun `ответ больше 2 МБ обрывается, а не читается в память целиком`() = runBlocking {
        val import = SkinImport(apiBase = server.url("/v2").toString().trimEnd('/'))
        val oversized = TexturePng.MAX_BYTES + 1
        val cases = listOf(
            MockResponse().setBody(Buffer().write(ByteArray(oversized))) to "размер известен заранее — отказ до чтения тела",
            MockResponse().setChunkedBody(Buffer().write(ByteArray(oversized)), 64 * 1024) to "размер не объявлен — отказ, как только прочитано больше предела",
        )
        for ((response, why) in cases) {
            server.enqueue(response)
            val failed = runCatching { import.byNick("Notch") }.exceptionOrNull()
            assertTrue("$why: огромный ответ обязан быть отказом, а не OOM", failed is TextureError)
            assertTrue("$why: игрок узнаёт, что файл слишком большой", failed!!.message!!.contains("2 МБ"))
        }
    }

    @Test
    fun `ответ сервиса не-PNG останавливает импорт`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>404</html>").setHeader("Content-Type", "text/html"))
        val import = SkinImport(apiBase = server.url("/v2").toString().trimEnd('/'))
        val failed = runCatching { import.byNick("Notch") }.exceptionOrNull()
        assertTrue("страница вместо картинки — это отказ, а не пустой скин", failed is TextureError)

        server.enqueue(MockResponse().setResponseCode(404))
        val missing = runCatching { import.byNick("Notch") }.exceptionOrNull()
        assertTrue("404 обязан объяснять игроку, что ник не найден", missing is TextureError)
        assertTrue("текст 404 говорит про ник", missing!!.message!!.contains("Ник"))
    }

    @Test
    fun `профиль Microsoft отдаёт активный скин и все плащи аккаунта`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"id":"069a79f4","name":"Notch","skins":[
                   {"state":"INACTIVE","url":"http://textures.minecraft.net/texture/old","variant":"CLASSIC"},
                   {"state":"ACTIVE","url":"http://textures.minecraft.net/texture/new","variant":"SLIM"}],
                 "capes":[
                   {"id":"cape-1","alias":"Migrator","url":"http://textures.minecraft.net/texture/mig","state":"ACTIVE"},
                   {"id":"cape-2","alias":"Vanilla","url":"http://textures.minecraft.net/texture/van","state":"INACTIVE"}]}
                """.trimIndent()
            )
        )

        val services = MinecraftServicesClient(base = server.url("/minecraft/profile").toString())
        val profile = services.profile("token-123")

        assertEquals("токен уходит заголовком, а не в адресе", "Bearer token-123", server.takeRequest().getHeader("Authorization"))
        assertEquals("берём именно активный скин, а не первый в списке", "https://textures.minecraft.net/texture/new", profile.skinUrl)
        assertTrue("вариант SLIM — это тонкие руки", profile.slim)
        assertEquals("ссылки Mojang приводятся к https", "https://textures.minecraft.net/texture/mig", profile.capes.first().url)
        assertEquals("видны все плащи аккаунта, а не только надетый", 2, profile.capes.size)
        assertNotNull("надетый плащ должен определяться", profile.activeCape)
        assertEquals("надет тот, у кого state ACTIVE", "cape-1", profile.activeCape!!.id)
    }

    @Test
    fun `ошибка Mojang доходит до игрока по-русски и с подсказкой, что делать`() = runBlocking {
        val services = MinecraftServicesClient(base = server.url("/minecraft/profile").toString())
        val cases = listOf(
            Triple(MockResponse().setResponseCode(401).setBody("""{"errorMessage":"Invalid token"}"""), "перезайди", "английский текст Mojang игроку ничего не говорит, шаг — перезайти"),
            Triple(MockResponse().setResponseCode(401).setBody(""), "перезайди", "без тела 401 всё равно объясняет, что делать"),
            Triple(MockResponse().setResponseCode(404).setBody("""{"errorMessage":"The server has not found anything matching the request URI"}"""), "Minecraft Java", "404 профиля — на аккаунте нет игры, а не сбой"),
            Triple(MockResponse().setResponseCode(429).setBody(""), "подожд", "частые запросы — просим подождать, а не повторять сразу"),
            Triple(MockResponse().setResponseCode(503).setBody(""), "503", "прочий код виден, чтобы поддержка поняла, что случилось"),
        )
        for ((response, expected, why) in cases) {
            server.enqueue(response)
            val failed = runCatching { services.profile("stale") }.exceptionOrNull()
            assertTrue("ошибку нельзя глотать: $why", failed is TextureError)
            assertTrue("$why — получили «${failed!!.message}»", failed.message!!.contains(expected))
        }
    }

    @Test
    fun `загрузка скина проверяет картинку и подтверждает смену`() = runBlocking {
        val services = MinecraftServicesClient(base = server.url("/minecraft/profile").toString())

        val wrong = runCatching { services.uploadSkin("token", png(100, 100), slim = false) }.exceptionOrNull()
        assertTrue("негодная текстура не должна уходить на аккаунт", wrong is TextureError)
        assertEquals("и запрос в сеть не уходит", 0, server.requestCount)

        server.enqueue(MockResponse().setBody("""{"id":"x","name":"Notch","skins":[],"capes":[]}"""))
        val quiet = runCatching { services.uploadSkin("token", png(64, 64), slim = true) }.exceptionOrNull()
        assertTrue("пустой ответ Mojang — это не успех", quiet is TextureError)
        assertTrue("игроку говорим попробовать ещё раз", quiet!!.message!!.contains("не сменился"))

        server.enqueue(
            MockResponse().setBody(
                """{"id":"x","name":"Notch","skins":[{"state":"ACTIVE","url":"http://textures.minecraft.net/texture/new","variant":"SLIM"}],"capes":[]}"""
            )
        )
        val applied = services.uploadSkin("token", png(64, 64), slim = true)
        assertEquals("после успеха возвращается новый скин", "https://textures.minecraft.net/texture/new", applied.skinUrl)

        server.takeRequest()
        val upload = server.takeRequest()
        val body = upload.body.readUtf8()
        assertTrue("скин уходит файлом, а не ссылкой", body.contains("filename=\"skin.png\""))
        assertTrue("вариант модели рук уходит вместе с файлом", body.contains("slim"))
    }

    @Test
    fun `плащ снимается пустым идентификатором, а сброс скина отдельным вызовом`() = runBlocking {
        val services = MinecraftServicesClient(base = server.url("/minecraft/profile").toString())

        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        services.setCape("token", "cape-1")
        val put = server.takeRequest()
        assertEquals("надеть плащ — это PUT", "PUT", put.method)
        assertEquals("адрес активного плаща один", "/minecraft/profile/capes/active", put.path)
        assertTrue("идентификатор плаща уходит телом", put.body.readUtf8().contains("cape-1"))

        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        services.setCape("token", "")
        assertEquals("снять плащ — это DELETE, иначе плащ остаётся надетым", "DELETE", server.takeRequest().method)

        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        services.resetSkin("token")
        val reset = server.takeRequest()
        assertEquals("сброс скина — DELETE", "DELETE", reset.method)
        assertEquals("у сброса свой адрес, не тот же, что у плаща", "/minecraft/profile/skins/active", reset.path)
    }
}
