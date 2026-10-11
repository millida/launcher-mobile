package net.millida.launcher.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.call.CallLogEntry
import net.millida.launcher.core.call.CallLogs
import net.millida.launcher.core.call.CallOutcome
import net.millida.launcher.core.call.CallRules
import net.millida.launcher.core.call.CallSignalKind
import net.millida.launcher.core.call.PeerFlags
import net.millida.launcher.core.call.PeerQuality
import net.millida.launcher.core.call.QualityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallRulesTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private data class QualityCase(val rtt: Long?, val loss: Double, val expected: QualityLevel, val why: String)

    @Test
    fun `границы качества связи строгие, как на десктопе`() {
        val cases = listOf(
            QualityCase(350L, 0.0, QualityLevel.MID, "ровно 350 мс — ещё не «плохая»"),
            QualityCase(351L, 0.0, QualityLevel.BAD, "351 мс — уже плохая"),
            QualityCase(180L, 3.0, QualityLevel.OK, "180 мс и 3% — ещё хорошая"),
            QualityCase(181L, 0.0, QualityLevel.MID, "181 мс — так себе"),
            QualityCase(50L, 8.0, QualityLevel.MID, "8% потерь — ещё не плохая"),
            QualityCase(50L, 8.01, QualityLevel.BAD, "больше 8% — плохая"),
            QualityCase(0L, 0.0, QualityLevel.OK, "нулевая задержка — хорошая, а не «соединяемся»"),
        )
        cases.forEach { case ->
            assertEquals("rtt=${case.rtt} loss=${case.loss}: ${case.why}", case.expected, CallRules.quality(case.rtt, case.loss))
        }
        assertEquals("Плохая связь", QualityLevel.BAD.text)
    }

    @Test
    fun `худшее звено — с самой большой задержкой`() {
        val fast = PeerQuality(40L, 0.0, 32.0)
        val slow = PeerQuality(300L, 1.0, 30.0)
        val unknown = PeerQuality(null, 0.0, 0.0)
        assertEquals("разговор ощущается по худшему собеседнику", slow, CallRules.worst(listOf(fast, unknown, slow)))
        assertNull("без собеседников качества нет", CallRules.worst(emptyList()))
    }

    private data class LossCase(val lost: Long, val received: Long, val expected: Double, val why: String)

    @Test
    fun `доля потерь считается от всех пакетов`() {
        val cases = listOf(
            LossCase(0, 0, 0.0, "пакетов ещё нет — не делим на ноль"),
            LossCase(5, 95, 5.0, "пять из ста"),
            LossCase(10, 0, 100.0, "ничего не дошло"),
            LossCase(0, 1000, 0.0, "без потерь"),
        )
        cases.forEach { case ->
            assertEquals("${case.lost}/${case.received}: ${case.why}", case.expected, CallRules.lossPct(case.lost, case.received), 1e-9)
        }
    }

    @Test
    fun `конец звонка называется по причине`() {
        val cases = listOf(
            CallSignalKind.DECLINE to "Звонок отклонён",
            CallSignalKind.BUSY to "Собеседник уже разговаривает",
            CallSignalKind.CANCEL to "Звонок отменён",
            CallSignalKind.END to "Звонок завершён",
            CallSignalKind.OFFER to "Звонок завершён",
        )
        cases.forEach { (kind, text) -> assertEquals("конверт $kind", text, CallRules.endedText(kind)) }
    }

    @Test
    fun `длительность разговора округляется и переходит в часы`() {
        val cases = listOf(
            599_499L to "9:59",
            599_500L to "10:00",
            3_600_000L to "1:00:00",
            36_000_000L to "10:00:00",
        )
        cases.forEach { (ms, text) -> assertEquals("ms=$ms", text, CallRules.formatTime(ms)) }
    }

    private data class LogCase(val text: String, val expected: CallLogEntry?, val why: String)

    @Test
    fun `итог звонка в чате читается терпимо к форме чисел`() {
        val p = CallLogs.PREFIX
        val cases = listOf(
            LogCase("$p{\"outcome\":\"done\",\"seconds\":\"42\"}", CallLogEntry(CallOutcome.DONE, 42L), "секунды строкой тоже читаются"),
            LogCase("$p{\"outcome\":\"done\",\"seconds\":1.5}", CallLogEntry(CallOutcome.DONE, 2L), "дробные секунды округляются"),
            LogCase("$p{\"outcome\":\"done\",\"seconds\":-5}", CallLogEntry(CallOutcome.DONE, 0L), "отрицательная длительность — ноль"),
            LogCase("$p{\"outcome\":\"failed\",\"seconds\":\"abc\"}", CallLogEntry(CallOutcome.FAILED, 0L), "мусор вместо секунд — ноль, итог сохраняется"),
            LogCase("$p{\"outcome\":\"declined\",\"seconds\":\"Infinity\"}", CallLogEntry(CallOutcome.DECLINED, 0L), "бесконечность не превращается в огромное число"),
            LogCase("$p{\"outcome\":\"DONE\"}", null, "регистр итога фиксирован сервером"),
            LogCase("$p[1,2]", null, "не объект — не итог"),
            LogCase(p, null, "маркер без тела"),
            LogCase("$p{\"seconds\":5}", null, "без итога это не звонок"),
            LogCase("обычное сообщение", null, "без маркера — просто текст"),
        )
        cases.forEach { case -> assertEquals("«${case.text}»: ${case.why}", case.expected, CallLogs.parse(case.text)) }
    }

    @Test
    fun `строка звонка в чате говорит с точки зрения читающего`() {
        val cases = listOf(
            Triple(CallOutcome.DONE, false, "Входящий звонок"),
            Triple(CallOutcome.DECLINED, true, "Звонок отклонён"),
            Triple(CallOutcome.DECLINED, false, "Ты отклонил звонок"),
            Triple(CallOutcome.FAILED, true, "Звонок сорвался"),
            Triple(CallOutcome.FAILED, false, "Звонок сорвался"),
            Triple(CallOutcome.MISSED, true, "Не дозвонился"),
            Triple(CallOutcome.CANCELED, true, "Не дозвонился"),
            Triple(CallOutcome.MISSED, false, "Пропущенный звонок"),
        )
        cases.forEach { (outcome, mine, text) ->
            assertEquals("$outcome mine=$mine", text, CallLogs.title(CallLogEntry(outcome, 0L), mine))
        }
        assertEquals("несостоявшийся звонок предлагает перезвонить", "Перезвонить", CallLogs.subtitle(CallLogEntry(CallOutcome.MISSED, 30L)))
        assertEquals("1:00:01", CallLogs.subtitle(CallLogEntry(CallOutcome.DONE, 3_601L)))
    }

    @Test
    fun `флаги собеседника пишут только известное`() {
        assertEquals("ничего не известно — пустой объект, а не false", "{}", PeerFlags().toJson().toString())
        val flags = PeerFlags.parse(obj("""{"cam":true,"screen":1,"muted":null,"extra":true}"""))
        assertEquals(PeerFlags(muted = null, deafened = null, screen = null, cam = true), flags)
        assertEquals(flags, PeerFlags.parse(flags.toJson()))
    }
}
