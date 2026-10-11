package net.millida.launcher.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.friends.CentrifugeFrame
import net.millida.launcher.core.friends.Realtime
import net.millida.launcher.core.friends.RealtimeGrant
import net.millida.launcher.core.friends.RealtimeTopic
import net.millida.launcher.core.mojang.MojangJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The live socket carries a session token, so where it may go is pinned here;
 * the poll pacing is the desktop's (src/lib/realtimePace.ts, src/lib/pollPace.ts)
 * because a wrong pace multiplied by every phone is load on the server.
 */
class RealtimeTest {

    private fun json(text: String): JsonObject = MojangJson.parseToJsonElement(text).jsonObject

    @Test
    fun `токен уходит только на наш адрес`() {
        data class Case(val why: String, val body: String, val on: Boolean)
        val cases = listOf(
            Case("наш wss без порта", """{"enabled":true,"url":"wss://api.millida.net/connection/websocket","token":"t"}""", true),
            Case("выключено сервером", """{"enabled":false}""", false),
            Case("незашифрованный ws", """{"enabled":true,"url":"ws://api.millida.net/connection/websocket","token":"t"}""", false),
            Case("чужой хост", """{"enabled":true,"url":"wss://api.millida.net.evil.io/c","token":"t"}""", false),
            Case("порт", """{"enabled":true,"url":"wss://api.millida.net:8443/c","token":"t"}""", false),
            Case("логин в адресе", """{"enabled":true,"url":"wss://u:p@api.millida.net/c","token":"t"}""", false),
            Case("пустой токен", """{"enabled":true,"url":"wss://api.millida.net/c","token":""}""", false),
            Case("enabled строкой — не true", """{"enabled":"yes","url":"wss://api.millida.net/c","token":"t"}""", false),
        )
        for (case in cases) {
            val grant = Realtime.readGrant(json(case.body))
            assertEquals(case.why, case.on, grant is RealtimeGrant.On)
        }
    }

    @Test
    fun `тычок принимается только из личного канала`() {
        val friends = json("""{"t":"friends"}""")
        assertEquals(RealtimeTopic.FRIENDS, Realtime.pokeTopic("personal:#u1", friends))
        assertNull("общий канал не наш", Realtime.pokeTopic("site:online", friends))
        assertNull("неизвестная тема", Realtime.pokeTopic("personal:#u1", json("""{"t":"nope"}""")))
        assertNull("без данных", Realtime.pokeTopic("personal:#u1", null))
        assertNull("тема не строкой", Realtime.pokeTopic("personal:#u1", JsonObject(mapOf("t" to JsonPrimitive(1)))))
    }

    @Test
    fun `кадры протокола Centrifugo`() {
        val frames = Realtime.parse(
            listOf(
                """{}""",
                """{"id":1,"connect":{"client":"c","expires":true,"ttl":900,"ping":25,"pong":true}}""",
                """{"push":{"channel":"personal:#u1","pub":{"data":{"t":"presence"}}}}""",
                """{"id":2,"error":{"code":109,"message":"token expired"}}""",
                """{"push":{"disconnect":{"code":3005,"reason":"stale"}}}""",
                """not json""",
            ).joinToString("\n"),
        )
        assertEquals("пустой объект — пинг", CentrifugeFrame.Ping, frames[0])
        assertEquals(CentrifugeFrame.Connected(1, 900, true), frames[1])
        val publication = frames[2] as CentrifugeFrame.Publication
        assertEquals(RealtimeTopic.PRESENCE, Realtime.pokeTopic(publication.channel, publication.data))
        assertEquals(CentrifugeFrame.Failed(2, 109, "token expired"), frames[3])
        assertEquals(CentrifugeFrame.Disconnect(3005, "stale"), frames[4])
        assertEquals("мусорная строка пропускается, остальное не теряется", 5, frames.size)
        assertEquals(
            "без expires токен не обновляем",
            CentrifugeFrame.Connected(3, null, false),
            Realtime.parse("""{"id":3,"connect":{"ttl":900}}""").single(),
        )
    }

    @Test
    fun `команды протокола`() {
        assertEquals("""{"id":1,"connect":{"token":"t","name":"millida-mobile"}}""", Realtime.connectCommand(1, "t", "millida-mobile"))
        assertEquals("""{"id":2,"refresh":{"token":"n"}}""", Realtime.refreshCommand(2, "n"))
    }

    @Test
    fun `токен обновляется заранее`() {
        assertEquals("15 минут — за минуту до конца", 840_000L, Realtime.refreshDelayMs(900))
        assertEquals("короткий срок — за четверть", 15_000L, Realtime.refreshDelayMs(20))
        assertNull("нет срока — не обновляем", Realtime.refreshDelayMs(null))
    }

    @Test
    fun `темп опроса как на десктопе`() {
        data class Case(val why: String, val live: Boolean, val server: Long, val failures: Int, val hidden: Boolean, val waited: Boolean, val want: Long)
        val cases = listOf(
            Case("обычный темп — что сказал сервер", false, 7_000, 0, false, false, 7_000),
            Case("после долгого ответа — почти сразу", false, 7_000, 0, false, true, 1_000),
            Case("в фоне не чаще 30 с", false, 7_000, 0, true, false, 30_000),
            Case("живой сокет растягивает до 2 минут", true, 7_000, 0, false, true, 120_000),
            Case("первая ошибка — 5 с", false, 7_000, 1, false, false, 5_000),
            Case("ошибки копятся, но не дольше минуты", false, 7_000, 9, false, false, 60_000),
        )
        for (case in cases) {
            assertEquals(case.why, case.want, Realtime.pollDelayMs(case.live, case.server, case.failures, case.hidden, case.waited, random = 0.5))
        }
        assertEquals("чужое число не превращает опрос в шторм", 5_000L, Realtime.pollIntervalFrom(10))
        assertEquals("и не останавливает его", 30_000L, Realtime.pollIntervalFrom(10_000_000))
        assertEquals("без ожидания при живом сокете", false, Realtime.pollWait(true))
    }
}
