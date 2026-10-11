package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.portal.AccountDetails
import net.millida.launcher.core.portal.PortalClient
import net.millida.launcher.core.portal.asRubles
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PortalFlagsTest {

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

    private fun portal(): PortalClient {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, "test-token")
        return PortalClient(MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/')))
    }

    private fun reply(body: String) {
        server.enqueue(MockResponse().setBody(body))
    }

    private data class FlagCase(val read: (AccountDetails) -> Boolean, val expected: Boolean, val why: String)

    @Test
    fun `поле, которого нет в ответе, не читается как да`() {
        val cases = listOf(
            FlagCase({ it.banned }, false, "без поля banned аккаунт показывался заблокированным"),
            FlagCase({ it.emailVerified }, false, "почта не подтверждена, пока сервер не сказал обратного"),
            FlagCase({ it.twoFactorEnabled }, false, "без поля 2FA не считается включённой"),
            FlagCase({ it.discordLinked }, false, "Discord не привязан, пока не сказано"),
            FlagCase({ it.telegramLinked }, false, "Telegram не привязан, пока не сказано"),
            FlagCase({ it.nicknameCanChange }, true, "смена ника по умолчанию разрешена — запрет приходит явно"),
        )
        runBlocking {
            val client = portal()
            for (case in cases) {
                reply("""{"nickname":"Steve"}""")
                assertEquals(case.why, case.expected, case.read(client.details()))
            }
        }
    }

    private data class FreeCase(val body: String, val free: Boolean, val why: String)

    @Test
    fun `занятый ник не выдаётся за свободный`() {
        val cases = listOf(
            FreeCase("""{"available":false}""", false, "сервер сказал «занят», а отсутствующие free и status читались как да"),
            FreeCase("""{"available":true}""", true, "свободен"),
            FreeCase("""{}""", false, "пустой ответ — не повод тратить смену ника"),
        )
        runBlocking {
            val client = portal()
            for (case in cases) {
                reply(case.body)
                assertEquals("${case.body}: ${case.why}", case.free, client.nicknameFree("Steve"))
            }
        }
    }

    private data class RublesCase(val kopecks: Long, val text: String, val why: String)

    @Test
    fun `отрицательная сумма пишется со знаком один раз`() {
        val cases = listOf(
            RublesCase(-1250, "-12,50 ₽", "раньше выходило «-12,-50 ₽»: остаток от деления тоже отрицательный"),
            RublesCase(-100, "-1 ₽", "ровный долг без копеек"),
            RublesCase(-5, "-0,05 ₽", "меньше рубля: целая часть 0 теряла знак"),
            RublesCase(1250, "12,50 ₽", "положительные не меняются"),
        )
        for (case in cases) assertEquals("${case.kopecks} коп.: ${case.why}", case.text, case.kopecks.asRubles())
    }
}
