package net.millida.launcher.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import net.millida.launcher.core.cosmetics.EmoteShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The lobby ends an emote by the play the stage shows: a rebuilt outfit starts the emote over, and it must play out. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmoteShowTest {

    /** Not a data class on purpose: a rebuilt emote is a new play even when it is the same emote. */
    private class Play(val name: String, val ms: Long) {
        override fun toString(): String = name
    }

    private class Case(
        val why: String,
        val stale: Play?,
        val events: List<Pair<Long, Play?>>,
        val ran: Play?,
        val endsAt: Long,
    )

    @Test
    fun `эмоция в лобби держится, пока идёт показ на сцене`() {
        val previous = Play("прошлая", 3_000)
        val first = Play("первая", 3_000)
        val rebuilt = Play("первая заново", 3_000)
        val cases = listOf(
            Case(
                "счёт идёт с момента, когда эмоция встала на фигуру, а не когда её выбрали",
                stale = null,
                events = listOf(300L to first),
                ran = first,
                endsAt = 3_300,
            ),
            Case(
                "пересборка образа (доехали вещи или руки) запускает эмоцию с начала — её нельзя обрезать по старому счёту",
                stale = null,
                events = listOf(300L to first, 1_300L to rebuilt),
                ran = rebuilt,
                endsAt = 4_300,
            ),
            Case(
                "прошлая эмоция, ещё висящая на сцене, не запускает счёт новой",
                stale = previous,
                events = listOf(500L to first),
                ran = first,
                endsAt = 3_500,
            ),
            Case(
                "эмоция не собралась — лобби не ждёт дольше отведённого",
                stale = null,
                events = emptyList(),
                ran = null,
                endsAt = BUILD_WAIT_MS,
            ),
            Case(
                "образ пропал посреди показа — лобби не зависает и идёт дальше в свой срок",
                stale = null,
                events = listOf(0L to first, 1_000L to null),
                ran = first,
                endsAt = 3_000,
            ),
        )
        for (case in cases) {
            runTest {
                val plays = MutableStateFlow(case.stale)
                launch {
                    var at = 0L
                    for ((time, play) in case.events) {
                        delay(time - at)
                        at = time
                        plays.value = play
                    }
                }
                val ran = EmoteShow.playThrough(plays, case.stale, BUILD_WAIT_MS) { it.ms }
                assertSame(case.why, case.ran, ran)
                assertEquals(case.why, case.endsAt, currentTime)
            }
        }
    }

    private companion object {
        const val BUILD_WAIT_MS = 6_000L
    }
}
