package net.millida.launcher.core

import net.millida.launcher.core.friends.BridgeTarget
import net.millida.launcher.core.friends.BridgeText
import net.millida.launcher.core.friends.ChatAttachment
import net.millida.launcher.core.friends.ChatMessage
import net.millida.launcher.core.friends.ChatTarget
import net.millida.launcher.core.friends.Dialog
import net.millida.launcher.core.friends.DialogKind
import net.millida.launcher.core.friends.OverlayCall
import net.millida.launcher.core.friends.OverlayDialog
import net.millida.launcher.core.friends.OverlayLine
import net.millida.launcher.core.friends.OverlayWire
import net.millida.launcher.core.friends.VoiceNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWireTest {

    @Test
    fun `диалог для оверлея несёт вид и все поля строки списка`() {
        val room = OverlayWire.dialog(
            Dialog(DialogKind.ROOM, "r1", "Клан", subtitle = "Вася: го", unread = 3, lastMessageAt = 77L, online = true),
        )
        assertEquals(
            "группа уходит в игру как room, иначе ответ из игры попадёт в личку",
            OverlayDialog("room", "r1", "Клан", "Вася: го", 3, true, 77L),
            room,
        )
        assertEquals("room:r1", room.key)
        assertEquals("личка уходит как dm", "dm", OverlayWire.dialog(Dialog(DialogKind.DM, "u1", "Steve")).kind)
    }

    @Test
    fun `удалённое сообщение не показывает ни текст, ни вложение`() {
        val voice = ChatAttachment(url = "https://cdn/v.m4a", kind = "voice", durationMs = 3_000L)
        val alive = OverlayWire.line(ChatMessage("m1", "привет", me = true, ts = 5L, fromNick = "Steve", attachment = voice))
        assertEquals(OverlayLine("m1", "привет", me = true, ts = 5L, fromNick = "Steve", attachment = "voice", deleted = false), alive)

        val deleted = OverlayWire.line(ChatMessage("m2", "секрет", deleted = true, attachment = voice))
        assertEquals("текст удалённого сообщения не должен утечь в игру", "", deleted.text)
        assertNull("вложение удалённого сообщения не показывается", deleted.attachment)
        assertTrue("строка помечена удалённой, чтобы игра нарисовала заглушку", deleted.deleted)
    }

    @Test
    fun `цель переписки проходит туда и обратно без потерь`() {
        val targets = listOf(ChatTarget.Dm("u1"), ChatTarget.Room("r1"))
        targets.forEach { target ->
            assertEquals(
                "цель $target, вернувшаяся из игры, должна совпасть — иначе ответ уйдёт не туда",
                target,
                OverlayWire.targetOf(OverlayWire.kindOf(target), OverlayWire.idOf(target)),
            )
        }
        assertEquals(OverlayWire.KIND_ROOM, OverlayWire.kindOf(ChatTarget.Room("r1")))
        assertEquals("u1", OverlayWire.idOf(ChatTarget.Dm("u1")))
    }

    @Test
    fun `сообщения моста читаются с умолчаниями старой сборки`() {
        val call = OverlayWire.decode(OverlayCall.serializer(), """{"status":"active","mode":"dm","title":"Steve"}""")
        assertEquals("без поля speaker громкая связь включена, как по умолчанию", true, call?.speaker)
        assertEquals(false, call?.muted)
        val target = OverlayWire.decode(BridgeTarget.serializer(), """{"kind":"room","id":"r1"}""")
        assertEquals("без заголовка — пустой, а не отказ", BridgeTarget("room", "r1", ""), target)
        assertNull("без обязательного текста сообщение не принимается", OverlayWire.decode(BridgeText.serializer(), """{}"""))
        assertNull("текст объектом — чужой формат", OverlayWire.decode(BridgeText.serializer(), """{"text":{}}"""))
        val sent = OverlayWire.encode(OverlayCall.serializer(), OverlayCall("active", "dm", "Steve"))
        assertTrue("умолчания пишутся явно, чтобы старая сборка игры их не угадывала: $sent", sent.contains("\"speaker\":true"))
    }

    private data class BarCase(val level: Float, val expected: Float, val why: String)

    @Test
    fun `живая полоска голоса растягивает тихую речь и не выходит за край`() {
        val cases = listOf(
            BarCase(0f, 0f, "тишина — пустая полоска"),
            BarCase(-0.5f, 0f, "отрицательный уровень с микрофона не ломает корень"),
            BarCase(0.25f, 0.675f, "тихая речь растягивается корнем"),
            BarCase(0.81f, 1f, "громкая речь упирается в край"),
            BarCase(4f, 1f, "перегруз не рисует полоску длиннее экрана"),
        )
        cases.forEach { case ->
            assertEquals("уровень ${case.level}: ${case.why}", case.expected, VoiceNotes.liveBar(case.level), 0.0001f)
        }
    }

    @Test
    fun `пределы голосовых совпадают с десктопом и сервером`() {
        assertEquals("сервер отказывает выше 3 МБ", 3 * 1024 * 1024, VoiceNotes.MAX_BYTES)
        assertEquals("запись останавливается на двух минутах, как на компьютере", 120_000L, VoiceNotes.MAX_MS)
        assertTrue("короткий тык не считается сообщением", VoiceNotes.MIN_MS in 1L until 1_000L)
        assertEquals("2:00", VoiceNotes.formatTime(VoiceNotes.MAX_MS))
        assertEquals("полсекунды округляются вверх, как у плеера", "0:01", VoiceNotes.formatTime(500))
    }
}
