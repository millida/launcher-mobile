package net.millida.launcher.core

import kotlinx.serialization.json.contentOrNull
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.HttpError
import net.millida.launcher.core.http.RemoteFile
import net.millida.launcher.core.packs.PhoneBlock
import net.millida.launcher.core.packs.PhoneUnsupported
import net.millida.launcher.core.telemetry.EventData
import net.millida.launcher.core.telemetry.FailureKind
import net.millida.launcher.core.telemetry.TelemetryClient
import net.millida.launcher.core.telemetry.TelemetryCore
import net.millida.launcher.core.telemetry.TelemetryDevice
import net.millida.launcher.core.telemetry.TelemetryPrivacy
import net.millida.launcher.core.telemetry.TelemetryStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

class TelemetryKindTest {

    @get:Rule
    val tmp = TemporaryFolder()

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

    private data class Case(val error: Throwable, val kind: String, val why: String)

    @Test
    fun `класс сбоя берётся из всей цепочки причин и отказов лаунчера`() {
        val cases = listOf(
            Case(IllegalStateException("Свободно всего 1634 МБ — игру закроет система. Закрой другие приложения и попробуй снова."),
                "refused_memory", "отказ по памяти — решение лаунчера, а не сбой; раньше уходил в other"),
            Case(PhoneUnsupported(PhoneBlock.OLD_FORGE, "Сборка", "1.12.2"),
                "refused_device", "сборка не для телефона — отказ по устройству"),
            Case(IllegalStateException("«Сборка» не поместится в память этого телефона. Играй в неё через Millida Launcher на компьютере"),
                "refused_device", "тот же отказ текстом, когда исключение пересоздано экраном запуска"),
            Case(IllegalStateException("GL4ES 1.1.4 не запустит 1.21.5: GL4ES тянет версии только до 1.21.4. Подходящего рендерера в пакете рантайма нет — обнови пакет рантайма."),
                "refused_renderer", "без своего правила отказ рендерера попадал в graphics, как настоящий сбой драйвера"),
            Case(IOException("не удалось скачать", HttpError(502, "https://api.millida.net/x")),
                "server", "502 лежит причиной под нашей фразой — это сервер, а не other"),
            Case(IOException("запрос не прошёл", HttpError(401, "https://api.millida.net/x")),
                "auth", "401 — вход"),
            Case(IOException("профиль не получен", Unauthorized()),
                "auth", "сессия Millida недействительна"),
            Case(IOException("каталог не открылся", UnknownHostException("api.millida.net")),
                "network", "DNS не ответил — сеть"),
            Case(IOException("каталог не открылся", ConnectException("Failed to connect to /1.2.3.4:443")),
                "network", "соединение не установилось — сеть"),
            Case(IOException("Software caused connection abort"),
                "network", "Android рвёт сокет при смене сети"),
            Case(IllegalStateException("Вход Millida истёк — войди заново"),
                "auth", "текст экрана про вход"),
            Case(IllegalStateException("что-то странное"), "other", "неизвестное остаётся other"),
        )
        for (case in cases) {
            assertEquals("${case.error}: ${case.why}", case.kind, FailureKind.of(case.error))
        }
    }

    private fun core(store: TelemetryStore, clock: () -> Long) = TelemetryCore(
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

    @Test
    fun `сбои, различающиеся только числами, пишутся одним событием`() {
        var now = 0L
        val store = TelemetryStore(tmp.newFolder("telemetry"))
        val core = core(store) { now }
        core.trackFailure("launch", IllegalStateException("Свободно всего 1634 МБ — игру закроет система."))
        now += 1_000
        core.trackFailure("launch", IllegalStateException("Свободно всего 1590 МБ — игру закроет система."))
        assertEquals("мегабайты в тексте раздували журнал одним и тем же отказом", 1, store.size())
        core.trackFailure("launch", IllegalStateException("другая причина"))
        assertEquals("другой текст — другое событие", 2, store.size())
    }

    @Test
    fun `вид материала не затирает класс сбоя`() {
        val store = TelemetryStore(tmp.newFolder("telemetry-kind"))
        val core = core(store) { 0L }
        core.trackFailure(
            "content_install",
            IOException("каталог не открылся", UnknownHostException("api.millida.net")),
            EventData().put("contentKind", "mod").put("kind", "mod").build(),
        )
        val data = store.peek().single().data
        assertEquals("kind — класс сбоя, его не перебивает поле от вызывающего", "network", data["kind"]?.contentOrNull)
        assertEquals("вид материала уходит своим полем", "mod", data["contentKind"]?.contentOrNull)
    }

    @Test
    fun `сообщение о неудачной загрузке начинается с причины`() {
        server.enqueue(MockResponse().setResponseCode(503))
        val target = tmp.newFile("x.jar").also { it.delete() }
        val url = server.url("/very/long/path/" + "a".repeat(150) + ".jar?token=secret").toString()
        val error = runCatching {
            runBlocking { Downloader(attempts = 1).fetch(RemoteFile(url = url, target = target)) }
        }.exceptionOrNull()
        val code = TelemetryPrivacy.errorCode(error?.message)
        assertTrue("в первые 120 символов попадает причина, а не адрес: $code", code.startsWith("сервер ответил 503"))
        assertFalse("подпись из адреса не уходит в текст ошибки целиком: ${error?.message}", error?.message.orEmpty().endsWith("token=secret"))
    }

    @Test
    fun `причина сбоя берётся из отчёта о падении или корня последнего стека, мимо шума`() {
        data class Case(val log: List<String>, val cause: String?, val why: String)

        val cases = listOf(
            Case(
                listOf(
                    "[Krypton] glGetError() always return GL_NOERROR",
                    "[millida] Error: рендерер открыт",
                    "java.lang.IllegalStateException: boom",
                    "\tat a.b(C.java:1)",
                ),
                "java.lang.IllegalStateException: boom",
                "строка Krypton про glGetError и наши строки millida — шум, а не причина",
            ),
            Case(
                listOf(
                    "java.lang.RuntimeException: early warning",
                    "\tat a.b",
                    "[12:00] [main/INFO]: still running",
                    "java.lang.RuntimeException: wrapper",
                    "\tat c.d",
                    "Caused by: java.lang.NullPointerException: inner",
                    "\tat e.f",
                    "\t... 3 more",
                ),
                "Caused by: java.lang.NullPointerException: inner",
                "игра пережила ранний стек, причина — самый глубокий Caused by последнего",
            ),
            Case(
                listOf(
                    "---- Minecraft Crash Report ----",
                    "// Who set us up the TNT?",
                    "",
                    "Time: 2026-10-06",
                    "Description: Rendering overlay",
                    "",
                    "java.lang.NullPointerException: screen",
                    "\tat g.h",
                    "",
                    "[12:05] java.lang.IllegalArgumentException: later",
                    "\tat i.j",
                ),
                "Description: Rendering overlay — java.lang.NullPointerException: screen",
                "отчёт Minecraft называет сбой точнее хвоста лога сессии",
            ),
            Case(
                listOf("---- Minecraft Crash Report ----", "Description: Initializing game"),
                "Description: Initializing game",
                "отчёт без стека всё равно даёт описание",
            ),
            Case(
                listOf("[12:00] [main/WARN]: Error loading pack", "[12:01] [main/INFO]: FATAL shutdown"),
                "[12:01] [main/INFO]: FATAL shutdown",
                "без стека берётся последняя строка с признаком сбоя",
            ),
            Case(
                listOf("[Krypton] glGetError() always return GL_NOERROR", "[millida] java.lang.Exception: наше"),
                null,
                "в логе только шум — причины нет",
            ),
        )
        for (case in cases) {
            assertEquals("причина сбоя: ${case.why}", case.cause, FailureKind.cause(case.log.joinToString("\n")))
        }
    }

    @Test
    fun `графикой считается только настоящая ошибка графики, а не упоминание`() {
        data class Case(val log: String, val kind: String, val why: String)

        val cases = listOf(
            Case("[millida] рендерер Krypton, GLES 3.2, OpenGL 4.6 через ng_gl4es и zink", "other", "упоминание GLES и OpenGL в логе не делает сбой графическим"),
            Case("[Krypton] glGetError() always return GL_NOERROR", "other", "строка Krypton про glGetError не ошибка"),
            Case("eglCreateWindowSurface failed: EGL_BAD_NATIVE_WINDOW", "graphics", "EGL_BAD — настоящая ошибка EGL"),
            Case("org.lwjgl.opengl.OpenGLException: OpenGL error 1282", "graphics", "исключение OpenGL"),
            Case("GL error GL_INVALID_OPERATION in glDrawArrays", "graphics", "ошибка GL"),
            Case("Failed to create the OpenGL context", "graphics", "контекст не создался"),
            Case("GLFW error 65543: EGL: Failed to create context", "graphics", "ошибка GLFW"),
            Case("Vulkan error VK_ERROR_DEVICE_LOST", "graphics", "ошибка Vulkan"),
            Case("MESA: error: zink: could not create screen", "graphics", "строка ошибки zink"),
            Case("LIBGL: gl4es error while compiling shader", "graphics", "строка ошибки gl4es"),
        )
        for (case in cases) {
            assertEquals("класс сбоя: ${case.why}", case.kind, FailureKind.of(case.log))
        }
    }

    @Test
    fun `конфликтом модов считается только сбой Mixin, а не его упоминание`() {
        data class Case(val log: String, val kind: String, val why: String)

        val cases = listOf(
            Case("[main/INFO]: Loaded configuration file for Mixin", "other", "любая Fabric-сборка пишет про Mixin при загрузке — это не конфликт"),
            Case("org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical error", "mod_conflict", "сбой трансформера Mixin"),
            Case("org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException via InvalidMixinException", "mod_conflict", "битый mixin мода"),
            Case("net.fabricmc.loader.impl.FormattedException: Incompatible mods found!", "mod_conflict", "Fabric сам называет несовместимость"),
        )
        for (case in cases) {
            assertEquals("класс сбоя: ${case.why}", case.kind, FailureKind.of(case.log))
        }
    }
}
