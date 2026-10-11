package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.friends.FriendsClient
import net.millida.launcher.core.friends.FriendsError
import net.millida.launcher.core.friends.VoiceNotes
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Voice notes recorded on the phone travel through the desktop's upload
 * (chatMedia.ts) with a different file inside. Pinned: the server's own format
 * check (voice-format.ts), the envelope the desktop computes, and the body.
 */
class VoiceUploadTest {

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

    private fun client(): FriendsClient {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, "token")
        return FriendsClient(MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/')))
    }

    private fun mp4(brand: String): ByteArray = byteArrayOf(0, 0, 0, 24) + "ftyp$brand".toByteArray(Charsets.US_ASCII) + ByteArray(12)

    @Test
    fun formatCheckMatchesTheServer() {
        val cases = listOf(
            mp4("isom") to true,
            mp4("mp42") to true,
            mp4("M4A ") to true,
            mp4("3gp4") to false,
            "ID3abcdefghijk".toByteArray() to false,
            ByteArray(5) to false,
        )
        cases.forEach { (bytes, accepted) ->
            assertEquals("a file the server refuses must be stopped here with a readable reason", accepted, VoiceNotes.isM4a(bytes))
        }
    }

    @Test
    fun envelopeIsTheDesktopOne() {
        assertTrue("silence has no envelope", VoiceNotes.envelope(emptyList()).isEmpty())
        val env = VoiceNotes.envelope(List(96) { if (it == 10) 0.5f else 0.25f })
        assertEquals("48 points, as the desktop stores", VoiceNotes.PEAKS, env.size)
        assertEquals("the loudest point is 100", 100, env.max())
        assertEquals("the rest scales against it", 50, env[0])
        val short = VoiceNotes.envelope(listOf(0.2f, 0.4f))
        assertEquals("a short take still yields 48 points", VoiceNotes.PEAKS, short.size)
        assertEquals(100, short.last())
    }

    @Test
    fun uploadSendsTheDesktopBody() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.millida.trade/v.m4a","kind":"voice","name":"Голосовое сообщение"}"""))
        val attachment = client().uploadVoice(mp4("isom"), 4_200, listOf(10, 100, 30))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("voice", body["kind"]?.jsonPrimitive?.content)
        assertEquals("4200", body["durationMs"]?.jsonPrimitive?.content)
        assertEquals(3, body["peaks"]?.jsonArray?.size)
        assertEquals("voice", attachment.kind)
        assertEquals(4_200L, attachment.durationMs)
    }

    @Test
    fun refusedFilesNeverLeaveThePhone() = runBlocking {
        val cases = listOf(
            ByteArray(0) to "an empty take",
            mp4("3gp4") to "a format the server refuses",
            ByteArray(VoiceNotes.MAX_BYTES + 1).also { mp4("isom").copyInto(it) } to "a file over 3 MB",
        )
        cases.forEach { (bytes, why) ->
            val failed = runCatching { client().uploadVoice(bytes, 1_000, emptyList()) }.exceptionOrNull()
            assertTrue(why, failed is FriendsError)
        }
        assertEquals("nothing reached the server", 0, server.requestCount)
    }
}
