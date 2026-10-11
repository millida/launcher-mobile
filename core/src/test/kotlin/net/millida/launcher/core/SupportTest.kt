package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.support.BugKind
import net.millida.launcher.core.support.BugReport
import net.millida.launcher.core.support.CrashFile
import net.millida.launcher.core.support.ErrorReport
import net.millida.launcher.core.support.ReportRedaction
import net.millida.launcher.core.support.SupportClient
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
import java.util.Base64

/**
 * Reports leave the phone for our server, so the pins are about what must never
 * leave with them and about a report never being lost on the way.
 *
 * | вход                                              | вердикт                                               |
 * | ------------------------------------------------- | ----------------------------------------------------- |
 * | `Authorization: Bearer abc.def`                   | токен заменён на <redacted>                           |
 * | `--accessToken 123 --uuid 456`                    | оба значения скрыты, ключи остались                   |
 * | `C:\Users\Ivan\AppData`, `/home/ivan/x`           | имя пользователя в пути скрыто                        |
 * | `Setting user: Steve`                             | ник скрыт                                             |
 * | ник Steve в стеке, маска `<nick>`                 | ник скрыт; «Max» (3 буквы) не трогается               |
 * | файл вылета, записанный `CrashFile.format`        | версия, поток, класс ошибки читаются обратно          |
 * | старый файл без шапки                             | стек целиком, имя AndroidCrash, не падение            |
 * | описание бага с журналом больше 4000              | текст игрока цел, журнал держит конец                 |
 * | `POST /errors`                                    | без Authorization, source LAUNCHER, уровень FATAL     |
 * | баг-репорт со входом                              | с Authorization                                       |
 * | баг-репорт, сессия мертва (401)                   | уходит гостем, а не теряется                          |
 * | отзыв, `/launcher/feedback` отвечает 404          | null: отзыв надо слать баг-репортом                   |
 * | второй отзыв после такого 404                     | в ручку, которой нет, не стучится снова               |
 * | баг-репорт, срок токена истёк (ручка без 401)     | токен обновлён до отправки, репорт уходит с сессией   |
 * | `POST /errors` отвечает 429 с message             | исключение с текстом сервера: файл не удалят          |
 */
class SupportTest {

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

    private fun client(signedIn: Boolean): SupportClient {
        val vault = MemoryVault()
        if (signedIn) vault.write(MillidaAuth.KEY_ACCESS, "test-token")
        val base = server.url("/v2").toString().trimEnd('/')
        return SupportClient(MillidaAuth(OkHttpClient(), vault, base))
    }

    @Test
    fun `токены и пути с именем не уходят в отчёт`() {
        val text = "Authorization: Bearer abc.def-ghi\n--accessToken 123 --uuid 456\n" +
            "at C:\\Users\\Ivan\\AppData\\x.jar\nopen /home/ivan/game\nSetting user: Steve"
        val clean = ReportRedaction.redact(text)
        assertFalse("токен Bearer ушёл бы на сервер: $clean", clean.contains("abc.def-ghi"))
        assertFalse("значение --accessToken не скрыто: $clean", clean.contains("123"))
        assertFalse("значение --uuid не скрыто: $clean", clean.contains("456"))
        assertTrue("ключ --accessToken должен остаться для разбора: $clean", clean.contains("--accessToken"))
        assertFalse("имя в пути Windows не скрыто: $clean", clean.contains("Ivan"))
        assertFalse("имя в пути /home не скрыто: $clean", clean.contains("ivan"))
        assertFalse("ник из журнала игры не скрыт: $clean", clean.contains("Steve"))
    }

    @Test
    fun `ник маскируется, короткие значения не трогаются`() {
        val masked = ReportRedaction.mask("Steve joined, Max left", listOf("Steve" to "<nick>", "Max" to "<other>"))
        assertEquals("Ник длиннее трёх букв должен замениться, трёхбуквенный — нет", "<nick> joined, Max left", masked)
    }

    @Test
    fun `файл вылета читается обратно тем же форматом`() {
        val stack = "java.lang.IllegalStateException: boom\n\tat net.millida.X.run(X.kt:10)"
        val text = CrashFile.format("1.6.3", "Google Pixel · Android 14 · arm64-v8a", "25.09.2026 10:00:00", "main", stack)
        val record = CrashFile.parse(text)
        assertEquals("версия сборки, в которой был вылет", "1.6.3", record.version)
        assertEquals("поток вылета", "main", record.thread)
        assertEquals("класс ошибки — ключ группировки на сервере", "java.lang.IllegalStateException", record.name)
        assertEquals("первая строка стека — сообщение", "java.lang.IllegalStateException: boom", record.message)
        assertEquals("стек без шапки", stack, record.stack)
    }

    @Test
    fun `старый файл без шапки не ломает разбор`() {
        val record = CrashFile.parse("что-то пошло не так\nбез стека")
        assertEquals("без класса ошибки имя должно быть запасным", "AndroidCrash", record.name)
        assertEquals("весь текст идёт стеком", "что-то пошло не так\nбез стека", record.stack)
    }

    @Test
    fun `журнал уступает место тексту игрока и держит конец`() {
        val text = "Игра вылетает на загрузке мира"
        val log = (1..2_000).joinToString("\n") { "line $it" }
        val report = BugReport.bug(BugKind.CRASH, text, "Millida Mobile 1.6.3", log)
        assertTrue("описание выше лимита сервера отклонят целиком: ${report.description.length}", report.description.length <= BugReport.DESCRIPTION_MAX)
        assertTrue("текст игрока должен остаться первым", report.description.startsWith(text))
        assertTrue("конец журнала — место поломки — должен остаться", report.description.endsWith("line 2000"))
        assertTrue("заголовок сервер принимает от 6 до 200 символов", report.title.length in 6..BugReport.TITLE_MAX)
    }

    @Test
    fun `вылет уходит в errors без сессии`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val record = CrashFile.parse(CrashFile.format("1.6.3", "Pixel", "now", "main", "java.lang.Error: token=secret123"))
        client(signedIn = true).error(ErrorReport.fromCrash(record, "launcher-mobile@1.6.3"))
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("/v2/errors", request.path)
        assertNull("POST /errors не принимает сессию — токен не должен уходить", request.getHeader("Authorization"))
        assertTrue("источник должен быть LAUNCHER, как у компьютера: $body", body.contains("\"source\":\"LAUNCHER\""))
        assertTrue("вылет приложения — уровень FATAL: $body", body.contains("\"level\":\"FATAL\""))
        assertFalse("секрет из сообщения ушёл на сервер: $body", body.contains("secret123"))
    }

    @Test
    fun `баг-репорт со входом идёт с сессией`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"id\":\"b1\"}"))
        client(signedIn = true).bugReport(BugReport.bug(BugKind.OTHER, "Кнопка не нажимается", "", ""))
        val request = server.takeRequest()
        assertEquals("/v2/bug-reports", request.path)
        assertEquals("репорт вошедшего должен быть его", "Bearer test-token", request.getHeader("Authorization"))
        assertTrue("сервис launcher обязателен", request.body.readUtf8().contains("\"service\":\"launcher\""))
    }

    @Test
    fun `мёртвая сессия не теряет баг-репорт`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("{\"id\":\"b2\"}"))
        client(signedIn = true).bugReport(BugReport.bug(BugKind.AUTH, "Не пускает после обновления", "", ""))
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("/v2/bug-reports", retry.path)
        assertNull("повтор должен уйти гостем", retry.getHeader("Authorization"))
    }

    @Test
    fun `без ручки отзывов отзыв уходит баг-репортом`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("{\"message\":\"Cannot POST /v2/launcher/feedback\",\"error\":\"Not Found\",\"statusCode\":404}"),
        )
        val answer = client(signedIn = true).feedback("Хороший лаунчер, спасибо", 5)
        assertNull("404 значит «ручки нет», а не ошибка отправки", answer)
    }

    @Test
    fun `после 404 ручка отзывов больше не дёргается`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("{\"message\":\"Cannot POST /v2/launcher/feedback\",\"error\":\"Not Found\",\"statusCode\":404}"),
        )
        val support = client(signedIn = true)
        support.feedback("Хороший лаунчер, спасибо", 5)
        val second = support.feedback("И ещё раз спасибо за лаунчер", 4)
        assertNull("без ручки отзыв должен уходить баг-репортом", second)
        assertEquals("каждый отзыв платил бы лишним запросом в ручку, которой нет", 1, server.requestCount)
    }

    @Test
    fun `истёкший токен обновляется до баг-репорта`() = runBlocking {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, jwt(System.currentTimeMillis() / 1000 - 60))
        vault.write(MillidaAuth.KEY_REFRESH, "refresh-1")
        server.enqueue(MockResponse().setBody("{\"accessToken\":\"fresh-token\",\"refreshToken\":\"refresh-2\"}"))
        server.enqueue(MockResponse().setBody("{\"id\":\"b3\"}"))
        val base = server.url("/v2").toString().trimEnd('/')
        SupportClient(MillidaAuth(OkHttpClient(), vault, base))
            .bugReport(BugReport.bug(BugKind.OTHER, "Кнопка не нажимается", "", ""))
        assertEquals("гостевая ручка не отвечает 401, обновлять надо заранее", "/v2/auth/refresh", server.takeRequest().path)
        val report = server.takeRequest()
        assertEquals("/v2/bug-reports", report.path)
        assertEquals("с истёкшим токеном репорт лёг бы гостевым и пропал из «Моих репортов»", "Bearer fresh-token", report.getHeader("Authorization"))
    }

    private fun jwt(expSeconds: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"none\"}".toByteArray())
        val claims = encoder.encodeToString("{\"exp\":$expSeconds}".toByteArray())
        return "$header.$claims.signature"
    }

    @Test
    fun `отказ сервера не выдаётся за успех`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{\"message\":\"Слишком часто\"}"))
        val record = CrashFile.parse("java.lang.Error: x")
        val failure = runCatching { client(signedIn = false).error(ErrorReport.fromCrash(record, "launcher-mobile@1.6.3")) }
        assertTrue("при отказе файл вылета не должен удаляться, значит нужен сбой", failure.isFailure)
        assertEquals("текст сервера должен дойти до игрока", "Слишком часто", failure.exceptionOrNull()?.message)
    }
}
