package net.millida.launcher.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.millida.launcher.core.call.CallEnvelopes
import net.millida.launcher.core.call.CallEvent
import net.millida.launcher.core.call.CallIds
import net.millida.launcher.core.call.CallLogs
import net.millida.launcher.core.call.CallMode
import net.millida.launcher.core.call.CallOutcome
import net.millida.launcher.core.call.CallRoute
import net.millida.launcher.core.call.CallRouter
import net.millida.launcher.core.call.CallRules
import net.millida.launcher.core.call.CallSignalKind
import net.millida.launcher.core.call.CallStatus
import net.millida.launcher.core.call.EnvelopeLog
import net.millida.launcher.core.call.IceConfigs
import net.millida.launcher.core.call.PeerFlags
import net.millida.launcher.core.call.QualityLevel
import net.millida.launcher.core.call.Ring
import net.millida.launcher.core.call.RingTones
import net.millida.launcher.core.call.politeToward
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone talks to the same call mailbox as the desktop launcher, often to a
 * desktop on the other end. What is pinned here is agreement with that
 * protocol (trade-api friends/calls, launcher src/lib/call): a field read under
 * another name or a kind spelled differently means a call that rings on one
 * side and never on the other.
 */
class CallProtocolTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private data class EnvelopeCase(val json: String, val kind: CallSignalKind?, val why: String)

    @Test
    fun envelopesAreReadOnlyWhenWellFormed() {
        val cases = listOf(
            EnvelopeCase("""{"seq":5,"callId":"abcdef0123","from":"u1","kind":"invite","data":{},"ts":1}""", CallSignalKind.INVITE, "the plain invite the server writes"),
            EnvelopeCase("""{"seq":5,"callId":"abcdef0123","from":"u1","kind":"roster","ts":1}""", CallSignalKind.ROSTER, "server kinds are read even though a client may not send them"),
            EnvelopeCase("""{"seq":"5","callId":"c","from":"u1","kind":"offer","ts":1}""", null, "a seq that is a string is not the server's shape"),
            EnvelopeCase("""{"seq":5,"callId":"c","from":"u1","kind":"teleport","ts":1}""", null, "an unknown kind must not be half-applied"),
            EnvelopeCase("""{"seq":5,"callId":"c","kind":"end","ts":1}""", null, "without a sender the envelope cannot be answered"),
            EnvelopeCase("""{"seq":5,"callId":"c","from":"u1","kind":"end"}""", null, "the time is part of the dedupe key and must be there"),
            EnvelopeCase("""[1,2]""", null, "not an object at all"),
        )
        cases.forEach { case ->
            val parsed = CallEnvelopes.parse(Json.parseToJsonElement(case.json))
            assertEquals(case.why, case.kind, parsed?.kind)
        }
    }

    @Test
    fun envelopeDataFallsBackToEmptyAndCarriesTheRoom() {
        val bare = CallEnvelopes.parse(obj("""{"seq":1,"callId":"abcdefgh","from":"u","kind":"state","data":"x","ts":2}"""))
        assertNotNull("a string in place of data must not drop the envelope, the desktop treats it as {}", bare)
        assertTrue(bare!!.data.isEmpty())
        val room = CallEnvelopes.parse(obj("""{"seq":1,"callId":"abcdefgh","from":"u","kind":"offer","data":{"roomId":"room-0001"},"ts":2}"""))
        assertEquals("the room id rides inside data, that is where the server stamps it", "room-0001", room?.roomId)
    }

    @Test
    fun inboxKeepsTheServerCursor() {
        val inbox = CallEnvelopes.parseInbox(
            obj("""{"now":10,"cursor":42,"events":[{"seq":41,"callId":"abcdefgh","from":"u","kind":"ice","data":{},"ts":3},{"junk":true}]}"""),
        )
        assertEquals("the cursor is what the next poll asks after", 42L, inbox.cursor)
        assertEquals("a junk row is dropped, the good one stays", 1, inbox.events.size)
    }

    @Test
    fun signalBodyMatchesTheServerDto() {
        val body = CallEnvelopes.signalBody("abcdef012345", "peer", CallSignalKind.END, buildJsonObject { put("x", 1) }, seconds = 90, roomId = null)
        assertEquals("end", body["kind"]?.jsonPrimitive?.content)
        assertEquals("seconds is how the server writes the call outcome", "90", body["seconds"]?.jsonPrimitive?.content)
        assertFalse("an absent room must not be sent as null: the DTO validates its length", body.containsKey("roomId"))

        val refused = listOf(
            Triple("short", "peer", CallSignalKind.OFFER) to "callId shorter than 8 is refused by CallSignalDto",
            Triple("abcdef012345", "", CallSignalKind.OFFER) to "an empty peer has nobody to deliver to",
            Triple("abcdef012345", "peer", CallSignalKind.ROSTER) to "roster is a server-only kind",
            Triple("abcdef012345", "peer", CallSignalKind.RING) to "ring is a server-only kind",
        )
        refused.forEach { (input, why) ->
            val failed = runCatching { CallEnvelopes.signalBody(input.first, input.second, input.third) }.isFailure
            assertTrue(why, failed)
        }
    }

    @Test
    fun duplicateEnvelopesAreAdmittedOnce() {
        var clock = 0L
        val log = EnvelopeLog { clock }
        val event = CallEvent(7, "abcdefgh", "u", CallSignalKind.OFFER, JsonObject(emptyMap()), 100)
        assertTrue(log.admit(event))
        assertFalse("an offer applied twice breaks the connection", log.admit(event))
        assertTrue("same seq after a server restart has another time and is new", log.admit(event.copy(ts = 200)))
        clock = 10 * 60_000L
        repeat(300) { log.admit(event.copy(seq = 1_000L + it)) }
        assertFalse("pruning keeps recent keys", log.admit(event.copy(seq = 1_299)))
    }

    private data class RouteCase(
        val kind: CallSignalKind,
        val data: String,
        val callId: String,
        val status: CallStatus,
        val mode: CallMode,
        val current: String,
        val expected: CallRoute,
        val why: String,
    )

    @Test
    fun envelopesGoWhereTheDesktopSendsThem() {
        val cases = listOf(
            RouteCase(CallSignalKind.RING, """{"roomId":"room-0001","title":"Клан"}""", "room-0001", CallStatus.IDLE, CallMode.DM, "", CallRoute.RoomRing("room-0001", "Клан"), "a group ring becomes a card"),
            RouteCase(CallSignalKind.RING, """{}""", "room-0002", CallStatus.IDLE, CallMode.DM, "", CallRoute.RoomRing("room-0002", "группе"), "without data the call id is the room"),
            RouteCase(CallSignalKind.RING, """{}""", "room-0002", CallStatus.ACTIVE, CallMode.DM, "x", CallRoute.Ignore, "a ring during a call stays silent"),
            RouteCase(CallSignalKind.OFFER, """{"roomId":"room-0001"}""", "room-0001", CallStatus.IDLE, CallMode.DM, "", CallRoute.Room("room-0001"), "room envelopes go to the room whatever the line state"),
            RouteCase(CallSignalKind.INVITE, """{}""", "call-0001", CallStatus.IDLE, CallMode.DM, "", CallRoute.Invite(busy = false), "a free line rings"),
            RouteCase(CallSignalKind.INVITE, """{}""", "call-0001", CallStatus.ACTIVE, CallMode.DM, "call-0009", CallRoute.Invite(busy = true), "a busy line answers busy"),
            RouteCase(CallSignalKind.END, """{}""", "call-0001", CallStatus.ACTIVE, CallMode.DM, "call-0009", CallRoute.Ignore, "an envelope of an old call must not end the new one"),
            RouteCase(CallSignalKind.ANSWER, """{}""", "call-0009", CallStatus.CONNECTING, CallMode.DM, "call-0009", CallRoute.Current, "the answer of the call in progress"),
            RouteCase(CallSignalKind.ANSWER, """{}""", "call-0009", CallStatus.IDLE, CallMode.DM, "call-0009", CallRoute.Ignore, "nothing to apply to after hanging up"),
        )
        cases.forEach { case ->
            val event = CallEvent(1, case.callId, "u", case.kind, obj(case.data), 1)
            assertEquals(case.why, case.expected, CallRouter.route(event, case.status, case.mode, case.current))
        }
    }

    @Test
    fun politenessIsTheSameOnBothEnds() {
        assertTrue(politeToward("a1", "b1"))
        assertFalse("the other end must reach the opposite verdict", politeToward("b1", "a1"))
    }

    @Test
    fun callIdsFitTheServerLimits() {
        val id = CallIds.next()
        assertEquals("24 hex chars, the desktop size", 24, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun iceAnswerIsFilteredAndCached() {
        val parsed = IceConfigs.parse(
            obj(
                """{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]},{"urls":"turn:turn.example:3478?transport=udp","username":"u","credential":"p"},{"urls":["javascript:alert(1)","http://x"]}],"ttlSeconds":3600,"relayOnly":true}""",
            ),
        )
        assertNotNull(parsed)
        val (config, cache) = parsed!!
        assertEquals("a non-ICE scheme is dropped, its server with it", 2, config.servers.size)
        assertEquals("u", config.servers[1].username)
        assertTrue("relayOnly is the server's switch, honoured as is", config.relayOnly)
        assertEquals("refreshed five minutes before the login expires", 3300L, cache)
        assertNull("an empty list falls back to STUN", IceConfigs.parse(obj("""{"iceServers":[]}""")))
        assertEquals("a tiny ttl still caches five minutes", 300L, IceConfigs.cacheSeconds(60))
    }

    @Test
    fun peerFlagsIgnoreWrongTypes() {
        val flags = PeerFlags.parse(obj("""{"muted":true,"deafened":"yes","screen":false}"""))
        assertEquals(true, flags.muted)
        assertNull("a string is not a flag", flags.deafened)
        assertEquals("""{"muted":true}""", PeerFlags(muted = true).toJson().toString())
    }

    @Test
    fun qualityThresholdsMatchTheDesktopPanel() {
        val cases = listOf(
            Triple<Long?, Double, QualityLevel>(null, 0.0, QualityLevel.WAIT),
            Triple(400L, 0.0, QualityLevel.BAD),
            Triple(50L, 9.0, QualityLevel.BAD),
            Triple(200L, 0.0, QualityLevel.MID),
            Triple(50L, 4.0, QualityLevel.MID),
            Triple(50L, 1.0, QualityLevel.OK),
        )
        cases.forEach { (rtt, loss, level) -> assertEquals("rtt=$rtt loss=$loss", level, CallRules.quality(rtt, loss)) }
    }

    @Test
    fun callTimeReadsLikeTheDesktop() {
        val cases = mapOf(0L to "0:00", 59_400L to "0:59", 61_000L to "1:01", 3_661_000L to "1:01:01", -5L to "0:00")
        cases.forEach { (ms, text) -> assertEquals("ms=$ms", text, CallRules.formatTime(ms)) }
    }

    @Test
    fun callLogMessagesAreRecognised() {
        val cases = listOf(
            Triple("⟪call⟫{\"outcome\":\"done\",\"seconds\":125.4}", CallOutcome.DONE to 125L, "a finished call keeps its length"),
            Triple("⟪call⟫{\"outcome\":\"missed\"}", CallOutcome.MISSED to 0L, "no seconds means zero"),
            Triple("⟪call⟫{\"outcome\":\"exploded\"}", null, "an unknown outcome is shown as plain text"),
            Triple("⟪call⟫not json", null, "a broken marker is not a call"),
            Triple("привет ⟪call⟫{\"outcome\":\"done\"}", null, "the marker counts only at the start"),
        )
        cases.forEach { (text, expected, why) ->
            val parsed = CallLogs.parse(text)
            assertEquals(why, expected, parsed?.let { it.outcome to it.seconds })
        }
        val done = CallLogs.parse("⟪call⟫{\"outcome\":\"done\",\"seconds\":61}")!!
        assertEquals("Исходящий звонок", CallLogs.title(done, mine = true))
        assertEquals("1:01", CallLogs.subtitle(done))
        assertEquals("Пропущенный звонок", CallLogs.title(CallLogs.parse("⟪call⟫{\"outcome\":\"canceled\"}")!!, mine = false))
    }

    @Test
    fun ringTonesFollowTheSoundVolume() {
        val incoming = RingTones.PATTERNS.getValue(Ring.INCOMING)
        assertEquals("muted launcher sound means a silent ring", 0f, RingTones.level(0, incoming))
        assertEquals(0.5f, RingTones.level(100, incoming))
        val pcm = RingTones.render(incoming, 0.5f, 8_000)
        assertEquals("four notes 0.22 s apart plus the last note", ((3 * 0.22 + 0.24) * 8_000).toInt(), pcm.size)
        assertTrue("the ring is audible", pcm.maxOf { kotlin.math.abs(it.toInt()) } > 1_000)
        assertEquals("no level, no samples", 0, RingTones.render(incoming, 0f, 8_000).size)
        assertEquals("the end tone does not repeat", 0L, RingTones.PATTERNS.getValue(Ring.ENDED).repeatMs)
    }
}
