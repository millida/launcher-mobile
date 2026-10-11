package net.millida.launcher.core

import net.millida.launcher.core.friends.ChatTarget
import net.millida.launcher.core.friends.OverlayDialog
import net.millida.launcher.core.friends.OverlaySnapshot
import net.millida.launcher.core.friends.OverlayThread
import net.millida.launcher.core.friends.OverlayWire
import net.millida.launcher.core.friends.VoiceNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The friends chat over the game lives in another process than the session,
 * so what crosses between them is pinned: a field lost on the way means an
 * empty panel in the middle of a game, and an id taken on trust from the wire
 * would reach a request path.
 */
class OverlayChatTest {

    @Test
    fun snapshotSurvivesTheTrip() {
        val snapshot = OverlaySnapshot(
            signedIn = true,
            dialogs = listOf(OverlayDialog("dm", "u1", "Steve", unread = 2, online = true, lastMessageAt = 5)),
            thread = OverlayThread("room", "r1", "Клан", loading = true),
        )
        val back = OverlayWire.decode(OverlaySnapshot.serializer(), OverlayWire.encode(OverlaySnapshot.serializer(), snapshot))
        assertEquals("every field must arrive, the panel draws from nothing else", snapshot, back)
    }

    @Test
    fun malformedPayloadIsRefusedNotThrown() {
        val cases = listOf(null to "no payload", "" to "empty payload", "{" to "cut JSON", "[]" to "wrong shape")
        cases.forEach { (text, why) -> assertNull(why, OverlayWire.decode(OverlaySnapshot.serializer(), text)) }
        val newer = OverlayWire.decode(OverlaySnapshot.serializer(), """{"signedIn":false,"addedLater":1}""")
        assertEquals("a field from a newer build is ignored, not fatal", false, newer?.signedIn)
    }

    @Test
    fun targetsFromTheWireAreValidated() {
        val cases = listOf(
            Triple("dm", "u1", ChatTarget.Dm("u1")),
            Triple("room", " r1 ", ChatTarget.Room("r1")),
            Triple("dm", "../admin", null),
            Triple("dm", "a/b", null),
            Triple("dm", "", null),
            Triple("group", "r1", null),
        )
        cases.forEach { (kind, id, expected) -> assertEquals("kind=$kind id=$id", expected, OverlayWire.targetOf(kind, id)) }
    }

    @Test
    fun cardsOnlyForThreadsThatGrew() {
        val first = listOf(OverlayDialog("dm", "u1", "A", unread = 3))
        assertTrue("the first look is a baseline, old unread is not news", OverlayWire.grown(null, first).isEmpty())
        val before = mapOf("dm:u1" to 3, "room:r1" to 0)
        val now = listOf(
            OverlayDialog("dm", "u1", "A", unread = 3),
            OverlayDialog("room", "r1", "Клан", unread = 1),
            OverlayDialog("dm", "u2", "Новый", unread = 4),
        )
        assertEquals(
            "only the room grew; a thread unseen before only sets its baseline",
            listOf("room:r1"),
            OverlayWire.grown(before, now).map { it.key },
        )
    }

    @Test
    fun voiceWaveKeepsLoudParts() {
        val cases = listOf(
            Triple(emptyList<Int>(), 4, listOf(20, 20, 20, 20)),
            Triple(listOf(10, 90), 4, listOf(10, 90)),
            Triple(listOf(10, 90, 5, 5, 70, 0), 3, listOf(90, 5, 70)),
            Triple(listOf(150, -3), 4, listOf(100, 0)),
        )
        cases.forEach { (peaks, count, expected) -> assertEquals("peaks=$peaks", expected, VoiceNotes.downsample(peaks, count)) }
        assertEquals("1:05", VoiceNotes.formatTime(65_000))
        assertEquals("0:00", VoiceNotes.formatTime(-1))
    }
}
