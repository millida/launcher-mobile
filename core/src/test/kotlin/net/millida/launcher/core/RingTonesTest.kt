package net.millida.launcher.core

import net.millida.launcher.core.call.Ring
import net.millida.launcher.core.call.RingPattern
import net.millida.launcher.core.call.RingTones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RingTonesTest {

    private data class LevelCase(val volume: Int, val ring: Ring, val expected: Float, val why: String)

    @Test
    fun `громкость звонка следует громкости лаунчера и не выходит за пределы`() {
        val cases = listOf(
            LevelCase(150, Ring.INCOMING, 0.5f, "громкость больше 100% не усиливает звонок сверх десктопного"),
            LevelCase(-10, Ring.INCOMING, 0f, "отрицательная громкость — тишина, а не перевёрнутая волна"),
            LevelCase(50, Ring.INCOMING, 0.25f, "половина громкости — половина звонка"),
            LevelCase(100, Ring.OUTGOING, 0.22f, "гудок тише звонка, как на компьютере"),
            LevelCase(100, Ring.ENDED, 0.28f, "сигнал конца — свой уровень"),
        )
        cases.forEach { case ->
            assertEquals(
                "громкость ${case.volume}, ${case.ring}: ${case.why}",
                case.expected,
                RingTones.level(case.volume, RingTones.PATTERNS.getValue(case.ring)),
                1e-6f,
            )
        }
    }

    @Test
    fun `нечего играть — пустой буфер, а не падение`() {
        val incoming = RingTones.PATTERNS.getValue(Ring.INCOMING)
        assertEquals("без нот", 0, RingTones.render(RingPattern(emptyList(), 0, 1f), 1f, 8_000).size)
        assertEquals("нулевая частота дискретизации", 0, RingTones.render(incoming, 0.5f, 0).size)
        assertEquals("отрицательная частота дискретизации", 0, RingTones.render(incoming, 0.5f, -44_100).size)
        assertEquals("отрицательный уровень", 0, RingTones.render(incoming, -1f, 8_000).size)
    }

    @Test
    fun `две ноты гудка звучат ровно столько, сколько на десктопе`() {
        val pcm = RingTones.render(RingTones.PATTERNS.getValue(Ring.OUTGOING), 0.22f, 8_000)
        assertEquals("вторая нота через 0,22 с плюс 0,24 с её длины", 3_680, pcm.size)
        assertEquals("волна начинается с нуля — без щелчка в динамике", 0, pcm[0].toInt())
        assertTrue(
            "после спада огибающей последней ноты — тишина, иначе звук обрывается щелчком",
            (3_365 until pcm.size).all { pcm[it].toInt() == 0 },
        )
    }

    @Test
    fun `пик звонка не превышает заданный уровень`() {
        val level = 0.5f
        val pcm = RingTones.render(RingTones.PATTERNS.getValue(Ring.INCOMING), level, 16_000)
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue("пик $peak выше уровня — звонок громче настройки игрока", peak <= (level * Short.MAX_VALUE).toInt() + 1)
        assertTrue("пик $peak слишком тихий — звонок не слышно", peak > (level * Short.MAX_VALUE * 0.8f).toInt())
    }

    @Test
    fun `повтор звонков как на десктопе`() {
        assertEquals("входящий повторяется", 2_600L, RingTones.PATTERNS.getValue(Ring.INCOMING).repeatMs)
        assertEquals("гудок повторяется реже", 3_000L, RingTones.PATTERNS.getValue(Ring.OUTGOING).repeatMs)
        assertEquals("у каждого события свой рисунок", Ring.entries.toSet(), RingTones.PATTERNS.keys)
    }
}
