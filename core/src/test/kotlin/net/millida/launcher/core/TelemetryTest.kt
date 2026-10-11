package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.telemetry.BeatState
import net.millida.launcher.core.telemetry.DeviceFacts
import net.millida.launcher.core.telemetry.EventData
import net.millida.launcher.core.telemetry.FailureKind
import net.millida.launcher.core.telemetry.LaunchFacts
import net.millida.launcher.core.telemetry.OpenGame
import net.millida.launcher.core.telemetry.TelemetryClient
import net.millida.launcher.core.telemetry.TelemetryCore
import net.millida.launcher.core.telemetry.TelemetryDevice
import net.millida.launcher.core.telemetry.TelemetryEvent
import net.millida.launcher.core.telemetry.TelemetryPrivacy
import net.millida.launcher.core.telemetry.TelemetryStore
import net.millida.launcher.core.telemetry.TelemetryType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The phone reports into the desktop's collector and dashboard, so the pins are
 * about the values landing in the desktop's fields and shapes, and about
 * nothing leaving the phone that the desktop would not send.
 *
 * | вход                                           | вердикт                                                    |
 * | ---------------------------------------------- | ---------------------------------------------------------- |
 * | Android 14, API 34                             | «Android 14 (API 34)», не голое «14» рядом с Windows       |
 * | SoC QTI SM8550 (Android 12+)                   | «Qualcomm SM8550»                                          |
 * | Android 11: hardware qcom, board kalama        | «qcom kalama»: чип различим и без SOC_MODEL                |
 * | /sys/.../cpu/possible «0-3,4-7»                | 8 ядер; мусор — null, а не 0                               |
 * | экран 1080×2400 в портрете                     | «2400x1080@420», как в ландшафте                           |
 * | GL Qualcomm + «Adreno (TM) 740»                | вендор спереди; «ARM» + «Mali-G78 ARM» — без повтора        |
 * | имя своей сборки                               | custom:<FNV-1a> тот же, что у ПК; каталог — catalog:slug   |
 * | пути /data/user/0/…, /storage/emulated/0/…     | вырезаны вместе с токенами                                 |
 * | очередь 205 событий                            | хранит последние 200                                       |
 * | удаление отправленного                         | события, пришедшие во время отправки, остаются             |
 * | статистика выключена                           | очередь и открытая игра стёрты, новое не пишется           |
 * | батч на сервер                                 | os android, поля телефона, без themePack-мусора            |
 * | сервер ответил 500                             | очередь цела до следующей попытки                          |
 */
class TelemetryTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("telemetry").toFile()
    }

    @After
    fun stop() {
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun `версия системы не путается с версиями Windows`() {
        data class Case(val release: String?, val sdk: Int, val want: String, val why: String)
        listOf(
            Case("14", 34, "Android 14 (API 34)", "обычный телефон"),
            Case(" 8.0.0 ", 26, "Android 8.0.0 (API 26)", "минимальная поддерживаемая версия, пробелы срезаны"),
            Case(null, 30, "Android ? (API 30)", "прошивка без RELEASE всё равно даёт уровень API"),
        ).forEach { case ->
            assertEquals(case.why, case.want, DeviceFacts.osVersion(case.release, case.sdk))
        }
    }

    @Test
    fun `чип называется читаемо на любой версии Android`() {
        data class Case(val maker: String?, val model: String?, val hardware: String?, val board: String?, val want: String?, val why: String)
        listOf(
            Case("QTI", "SM8550", "qcom", "kalama", "Qualcomm SM8550", "Android 12+: QTI — это Qualcomm"),
            Case("Mediatek", "MT6789", "mt6789", "mt6789", "MediaTek MT6789", "регистр производителя приводится"),
            Case("Samsung", "Exynos 2200", "s5e9925", "s5e9925", "Samsung Exynos 2200", "вендор ставится перед моделью"),
            Case("Samsung", "Samsung Exynos", "x", "x", "Samsung Exynos", "вендор в начале модели не повторяется"),
            Case(null, null, "qcom", "kalama", "qcom kalama", "до Android 12 — кодовые имена платформы"),
            Case(null, null, "mt6789", "MT6789", "mt6789", "одинаковые имена не повторяются"),
            Case("unknown", "unknown", "unknown", "", null, "прошивка без сведений — пусто, а не «unknown»"),
        ).forEach { case ->
            assertEquals(case.why, case.want, DeviceFacts.chip(case.maker, case.model, case.hardware, case.board))
        }
        assertEquals("платформа берёт плату, если она есть", "kalama", DeviceFacts.platform("qcom", "kalama"))
        assertEquals("иначе — hardware", "mt6789", DeviceFacts.platform("mt6789", "unknown"))
    }

    @Test
    fun `ядра считаются по списку possible`() {
        data class Case(val text: String?, val want: Int?, val why: String)
        listOf(
            Case("0-7\n", 8, "обычный восьмиядерник"),
            Case("0-3,4-7", 8, "кластеры через запятую"),
            Case("0", 1, "одно ядро"),
            Case("0-3,6", 5, "дыра в нумерации"),
            Case("7-0", null, "перевёрнутый диапазон — не число ядер"),
            Case("abc", null, "мусор не превращается в 0 ядер"),
            Case("", null, "пустой файл"),
            Case(null, null, "файл не прочитался"),
        ).forEach { case ->
            assertEquals(case.why, case.want, DeviceFacts.cpuCount(case.text))
        }
    }

    @Test
    fun `экран и видеочип в форме полей ПК`() {
        assertEquals("портрет", "2400x1080@420", DeviceFacts.screen(1080, 2400, 420))
        assertEquals("ландшафт — тот же телефон", "2400x1080@420", DeviceFacts.screen(2400, 1080, 420))
        assertEquals("без плотности", "1280x720", DeviceFacts.screen(720, 1280, 0))
        assertNull("нулевой размер не отправляется", DeviceFacts.screen(0, 0, 420))

        data class Case(val vendor: String?, val renderer: String?, val want: String?, val why: String)
        listOf(
            Case("Qualcomm", "Adreno (TM) 740", "Qualcomm Adreno (TM) 740", "вендор спереди"),
            Case("ARM", "Mali-G78 MC14 ARM", "Mali-G78 MC14 ARM", "вендор уже в имени"),
            Case(null, "PowerVR Rogue GE8320", "PowerVR Rogue GE8320", "без вендора — только рендерер"),
            Case("Qualcomm", null, "Qualcomm", "без рендерера — хотя бы вендор"),
            Case(null, null, null, "контекст не создался"),
            Case("Imagination", "x".repeat(200), "Imagination " + "x".repeat(84), "длина колонки 96"),
        ).forEach { case ->
            assertEquals(case.why, case.want, DeviceFacts.gpu(case.vendor, case.renderer))
        }
        assertEquals("системная тема телефона — auto у ПК", "auto", DeviceFacts.themeMode("system"))
        assertNull("неизвестная тема не отправляется", DeviceFacts.themeMode("neon"))
    }

    @Test
    fun `отпечаток сборки совпадает с лаунчером на ПК`() {
        data class Case(val text: String, val want: String, val why: String)
        listOf(
            Case("a", "e40c292c", "FNV-1a 32 из src/lib/telemetryPrivacy.ts"),
            Case("Моя сборка", "e04419b2", "кириллица считается по UTF-16, как charCodeAt"),
            Case("Survival 1.20", "4029bb7c", "латиница с точкой"),
            Case("", "811c9dc5", "пустая строка — смещение FNV"),
        ).forEach { case ->
            assertEquals(case.why, case.want, TelemetryPrivacy.shortHash(case.text))
        }
        assertEquals("сборка каталога — по слагу", "catalog:fabulously", TelemetryPrivacy.buildTag("Моя", "fabulously"))
        assertEquals("своя — по отпечатку", "custom:e04419b2", TelemetryPrivacy.buildTag("Моя сборка"))
        assertNull("без сборки — ничего", TelemetryPrivacy.buildTag(""))
    }

    @Test
    fun `пути телефона и токены не уходят`() {
        data class Case(val text: String, val want: String, val why: String)
        listOf(
            Case("/data/user/0/net.millida.launcher/files/game/x.jar", "<app>/files/game/x.jar", "папка приложения"),
            Case("/data/data/net.millida.launcher/cache", "<app>/cache", "старое написание папки приложения"),
            Case("/storage/emulated/0/Download/pack.mrpack", "<storage>/Download/pack.mrpack", "общая память"),
            Case("Authorization: Bearer abc.def", "Authorization: Bearer <redacted>", "токен"),
        ).forEach { case ->
            assertEquals(case.why, case.want, TelemetryPrivacy.scrub(case.text))
        }
        assertEquals("пустая ошибка всё равно с кодом", "unknown", TelemetryPrivacy.errorCode(null))
        val data = EventData().put("cause", "at /storage/emulated/0/x").put("empty", " ").put("n", 3).build()
        assertEquals("строки в событии чистятся сразу, пустые не пишутся", setOf("cause", "n"), data.keys)
        assertEquals("<storage>/x", "at <storage>/x", data["cause"]?.contentOrNull)
    }

    @Test
    fun `класс сбоя называет причину`() {
        data class Case(val text: String?, val want: String, val why: String)
        listOf(
            Case("java.lang.OutOfMemoryError: Java heap space", "out_of_memory", "куча кончилась"),
            Case("Could not reserve enough space for 4194304KB object heap", "out_of_memory", "JVM не взяла память"),
            Case("dlopen failed: library \"libgl4es.so\" not found", "native_crash", "рантайм не загрузился"),
            Case("Unable to resolve host \"api.millida.net\"", "network", "нет сети"),
            Case("Failed to fetch metadata from yggdrasil", "auth", "вход Millida"),
            Case("No space left on device", "disk_full", "память телефона"),
            Case("что-то странное", "other", "неизвестное — other, не пусто"),
            Case(null, "unknown", "текста нет"),
        ).forEach { case ->
            assertEquals(case.why, case.want, FailureKind.of(case.text))
        }
        val log = "[12:00] [main/INFO]: Loading\n[12:01] java.lang.IllegalStateException: at /data/user/0/net.millida.launcher/x\n\tat a.b"
        assertEquals(
            "причина — исключение последнего стека, без путей",
            "[12:01] java.lang.IllegalStateException: at <app>/x",
            FailureKind.cause(log),
        )
    }

    @Test
    fun `пак каталога и модпак разводятся как на ПК`() {
        data class Case(val source: String?, val id: String?, val want: Pair<String?, String?>, val why: String)
        listOf(
            Case("millida-catalog", "fo", "fo" to null, "каталог Millida — pack"),
            Case("millida-mirror", "fo", "fo" to null, "зеркало каталога — тоже pack"),
            Case("modrinth", "aof", null to "aof", "Modrinth — modpack"),
            Case("file", "x", null to null, "файл игрока — личное, без имени"),
            Case("modrinth", " ", null to null, "пустой id"),
        ).forEach { case ->
            assertEquals(case.why, case.want, LaunchFacts.origin(case.source, case.id))
        }
        val data = LaunchFacts("custom:1", "1.20.1", "fabric", renderer = "gl4es", layer = "glfw", java = 0, heapMb = 2048).data()
        assertFalse("Java 0 — неизвестно, не отправляется", data.containsKey("java"))
        assertEquals("память игре уходит числом", 2048, data["heapMb"]?.contentOrNull?.toInt())
    }

    @Test
    fun `очередь держит 200 последних и убирает только отправленное`() {
        val store = TelemetryStore(dir)
        repeat(205) { store.add(event("app_start", at = it.toLong())) }
        assertEquals("потолок очереди как у ПК", TelemetryStore.MAX_QUEUE, store.size())
        assertEquals("старые выпадают первыми", 5L, store.peek(1).first().at)

        val sent = store.peek(100)
        store.add(event("game_launch", at = 9_999))
        store.remove(sent)
        assertEquals("пришедшее во время отправки осталось", 101, store.size())
        assertTrue("и это именно оно", store.peek(200).any { it.at == 9_999L })
    }

    @Test
    fun `выключатель стирает очередь и не пускает новое`() {
        val store = TelemetryStore(dir)
        store.add(event("app_start"))
        store.openGame(OpenGame(1, 1, LaunchFacts("b", "1.20", "fabric")))
        store.setEnabled(false)
        assertFalse(store.enabled)
        assertEquals("очередь стёрта", 0, store.size())
        assertNull("открытая игра стёрта", store.peekGame())
        store.add(event("app_start"))
        assertEquals("новое не пишется", 0, store.size())
        store.setEnabled(true)
        assertTrue("включается обратно", store.enabled)
    }

    @Test
    fun `id установки один и случайный`() {
        var made = 0
        val store = TelemetryStore(dir) { made += 1; "11111111-2222-3333-4444-555555555555" }
        val first = store.installId()
        assertEquals("второй вызов читает файл", first, TelemetryStore(dir) { "other-id-000" }.installId())
        assertEquals("id создаётся один раз", 1, made)
    }

    @Test
    fun `открытая игра забирается один раз`() {
        val store = TelemetryStore(dir)
        val facts = LaunchFacts("custom:1", "1.20.1", "fabric", renderer = "gl4es", layer = "glfw", java = 17, heapMb = 2048, mods = 3)
        store.openGame(OpenGame(100, 100, facts))
        store.touchGame(500)
        val game = store.takeGame()
        assertNotNull(game)
        assertEquals("последний признак жизни сохранён", 500L, game?.aliveAt)
        assertEquals("факты запуска переживают запись", facts, game?.facts)
        assertNull("второй процесс уже ничего не найдёт", store.takeGame())
    }

    @Test
    fun `батч уходит в форме ПК, а провал оставляет очередь`() {
        val store = TelemetryStore(dir)
        val core = core(store)
        core.track(TelemetryType.APP_START, durationMs = 1_200)
        core.track(TelemetryType.GAME_LAUNCH, LaunchFacts("custom:1", "1.20.1", "fabric", layer = "sdl3").data(), 900, true)

        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse("сервер упал — не отправлено", runBlocking { core.flush() })
        assertEquals("очередь цела", 2, store.size())

        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(runBlocking { core.flush() })
        assertEquals("отправленное убрано", 0, store.size())

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/launcher/telemetry", request.path)
        val body = MojangJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        val device = body["device"]!!.jsonObject
        assertEquals("android", device["os"]!!.jsonPrimitive.content)
        assertEquals("SM-S918B", device["deviceModel"]!!.jsonPrimitive.content)
        assertEquals("kalama", device["soc"]!!.jsonPrimitive.content)
        assertFalse("нулевые счётчики железа не отправляются, чтобы не затереть известное", device.containsKey("cpuCores"))
        val events = body["events"] as JsonArray
        assertEquals(listOf("app_start", "game_launch"), events.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("sdl3", events[1].jsonObject["data"]!!.jsonObject["layer"]!!.jsonPrimitive.content)
    }

    @Test
    fun `удар не чаще раза в 40 секунд для того же состояния`() {
        var now = 1_000_000L
        val core = core(TelemetryStore(dir)) { now }
        assertNotNull("первый удар", core.beat(BeatState.IDLE))
        now += 10_000
        assertNull("то же состояние через 10 с — рано", core.beat(BeatState.IDLE))
        val playing = core.beat(BeatState.PLAYING, build = "custom:1", mc = "1.20.1", server = "mc.example.net")
        assertNotNull("смена состояния уходит сразу", playing)
        assertEquals("playing", (playing!!["status"] as JsonPrimitive).content)
        assertEquals("mc.example.net", (playing["server"] as JsonPrimitive).content)
        val idle = core.beat(BeatState.IDLE, build = "custom:1", force = true)
        assertFalse("в лобби сборка не называется", (idle as JsonObject).containsKey("build"))
    }

    @Test
    fun `сбой одного места пишется раз в минуту, отмена не сбой`() {
        var now = 0L
        val store = TelemetryStore(dir)
        val core = core(store) { now }
        core.trackFailure("content_install", java.io.IOException("timeout"))
        now += 10_000
        core.trackFailure("content_install", java.io.IOException("timeout"))
        assertEquals("повтор через 10 с не пишется", 1, store.size())
        assertNull(core.trackFailure("content_install", kotlinx.coroutines.CancellationException("отмена")))
        assertEquals("отмена не пишется", 1, store.size())
    }

    private fun core(store: TelemetryStore, clock: () -> Long = System::currentTimeMillis) = TelemetryCore(
        store = store,
        client = TelemetryClient(MillidaAuth(OkHttpClient(), MemoryVault(), server.url("/").toString().trimEnd('/'))),
        device = {
            TelemetryDevice(
                installId = "inst-1",
                sessionId = "sess-1",
                appVersion = "2.0.40",
                cpuCores = 0,
                deviceVendor = "samsung",
                deviceModel = "SM-S918B",
                soc = "kalama",
            )
        },
        appVersion = "2.0.40",
        clock = clock,
    )

    private fun event(type: String, at: Long = 1) = TelemetryEvent(type = type, at = at, appVersion = "2.0.40")
}
