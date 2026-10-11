package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.friends.FriendsError
import net.millida.launcher.core.friends.InviteStatus
import net.millida.launcher.core.friends.InviteTier
import net.millida.launcher.core.friends.InviteWait
import net.millida.launcher.core.friends.InvitedFriend
import net.millida.launcher.core.friends.Referrals
import net.millida.launcher.core.friends.ReferralsClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * «Позови друга» reads the same `/launcher/referrals` the desktop reads
 * (src/lib/referrals.ts): the code rules, the progress to the next reward and
 * the status line of an invited friend must say the same on both.
 */
class ReferralsTest {

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

    private fun client(): ReferralsClient {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, "token")
        return ReferralsClient(MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/')))
    }

    private val overview = """
        {"code":"ab12cd","link":"https://millida.net/?ref=AB12CD","deepLink":"millida://invite/AB12CD",
         "qualified":2,"pending":1,"perFriendPlusDays":7,
         "rules":{"playSeconds":7200,"dailyLimit":5},"earned":{"plusDays":14,"chests":1},
         "tiers":[{"friends":5,"plusDays":30,"chest":null,"chestName":null,"reached":false,"granted":false},
                  {"friends":1,"plusDays":0,"chest":"c1","chestName":"Сундук","reached":true,"granted":true}],
         "next":{"friends":5,"left":3},
         "friends":[{"nickname":"Kirpich","status":"pending","wait":"play","playSeconds":3660,"invitedAt":"2026-09-01T00:00:00Z","qualifiedAt":null}],
         "invitedBy":null,"canApply":true}
    """.trimIndent()

    @Test
    fun `код нормализуется как на десктопе`() {
        val cases = listOf(
            "ab12cd" to "AB12CD",
            "  ab-12 cd " to "AB12CD",
            "кириллица" to "",
            "A".repeat(20) to "A".repeat(16),
            "" to "",
        )
        for ((raw, want) in cases) {
            assertEquals("код «$raw»", want, Referrals.normalizeCode(raw))
        }
    }

    @Test
    fun `обзор читается целиком`() = runBlocking {
        server.enqueue(MockResponse().setBody(overview))
        val read = client().overview()
        val request = server.takeRequest()
        assertEquals("/v2/launcher/referrals/me", request.path)
        assertEquals("GET", request.method)
        assertEquals("AB12CD", read.code)
        assertEquals("https://millida.net/?ref=AB12CD", read.link)
        assertEquals(7200L, read.needPlaySeconds)
        assertEquals(14, read.earnedPlusDays)
        assertEquals("лестница упорядочена по числу друзей", listOf(1, 5), read.tiers.map { it.friends })
        assertEquals(3, read.next?.left)
        assertEquals(InviteWait.PLAY, read.friends.single().wait)
        assertTrue(read.canApply)
    }

    @Test
    fun `чужая ссылка не уходит в буфер обмена`() = runBlocking {
        server.enqueue(MockResponse().setBody(overview.replace("https://millida.net/?ref=AB12CD", "https://evil.example/x")))
        assertEquals("ссылка не нашего сайта не копируется — остаётся код", "", client().overview().link)
    }

    @Test
    fun `ввод кода уходит нормализованным и тем же адресом`() = runBlocking {
        server.enqueue(MockResponse().setBody(overview))
        client().apply(" ab-12cd ")
        val request = server.takeRequest()
        assertEquals("/v2/launcher/referrals/apply", request.path)
        assertEquals("POST", request.method)
        assertEquals("{\"code\":\"AB12CD\"}", request.body.readUtf8())
    }

    @Test
    fun `пустой код не уходит на сервер`() = runBlocking {
        try {
            client().apply("---")
            fail("пустой код должен отклоняться до запроса")
        } catch (expected: FriendsError) {
            assertEquals("Введи код друга", expected.message)
        }
        assertEquals("запроса быть не должно", 0, server.requestCount)
    }

    @Test
    fun `отказ сервера доходит текстом`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"message\":\"Такого кода нет — проверь буквы\"}"))
        try {
            client().apply("ZZZ")
            fail("404 должен стать ошибкой")
        } catch (expected: FriendsError) {
            assertEquals("Такого кода нет — проверь буквы", expected.message)
        }
    }

    @Test
    fun `прогресс до следующей награды`() {
        val tiers = listOf(1, 3, 5).map { InviteTier(it, 0, null, null, false, false) }
        val cases = listOf(
            Triple("никого — ноль до первой", 0, 0f),
            Triple("ровно на ступени — ноль до следующей", 1, 0f),
            Triple("половина пути между 1 и 3", 2, 0.5f),
            Triple("прошёл все ступени — полная полоса", 7, 1f),
        )
        for ((why, qualified, want) in cases) {
            assertEquals(why, want, Referrals.tierProgress(qualified, tiers), 0.0001f)
        }
    }

    @Test
    fun `строка статуса приглашённого`() {
        fun invited(status: InviteStatus, wait: InviteWait, seconds: Long = 0) = InvitedFriend("x", status, wait, seconds, "")
        val cases = listOf(
            "засчитан" to (invited(InviteStatus.QUALIFIED, InviteWait.NONE) to "Засчитан"),
            "отклонён" to (invited(InviteStatus.REJECTED, InviteWait.NONE) to "Не засчитан"),
            "ждёт почту" to (invited(InviteStatus.PENDING, InviteWait.EMAIL) to "Ждёт подтверждения почты"),
            "в очереди" to (invited(InviteStatus.PENDING, InviteWait.QUEUE) to "Засчитаем в ближайшие сутки"),
            "играет — часы:минуты из нормы" to (invited(InviteStatus.PENDING, InviteWait.PLAY, 3660) to "Играет 1:01 из 2:00"),
        )
        for ((why, case) in cases) {
            assertEquals(why, case.second, Referrals.friendStatusText(case.first, 7200))
        }
    }

    @Test
    fun `тексты наград`() {
        assertEquals("Сундук + 30 дней PLUS", Referrals.tierRewardText(InviteTier(5, 30, "c", "Сундук", false, false)))
        assertEquals("1 день PLUS", Referrals.tierRewardText(InviteTier(1, 1, null, null, false, false)))
        assertEquals("2 друга", Referrals.friendsText(2))
        assertEquals("11 друзей", Referrals.friendsText(11))
    }
}
