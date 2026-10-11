package net.millida.launcher.ui

import net.millida.launcher.HostingModel
import net.millida.launcher.core.hosting.HostingPlan
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.ui.hosting.hostingDaysLabel
import net.millida.launcher.ui.hosting.hostingPlanFacts
import net.millida.launcher.ui.hosting.toolsBytes
import net.millida.launcher.ui.hosting.toolsIsArchive
import net.millida.launcher.ui.hosting.toolsIsText
import net.millida.launcher.ui.hosting.toolsMoment
import net.millida.launcher.ui.hosting.toolsRightsCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HostingFormatTest {

    @Test
    fun `состояние сервера в тон и подпись`() {
        data class Case(val status: String, val tone: HostingTone, val label: String, val why: String)
        listOf(
            Case("RUNNING", HostingTone.On, "Работает", "работающий сервер"),
            Case("online", HostingTone.On, "Работает", "регистр статуса от панели не важен"),
            Case("STARTING", HostingTone.Wait, "Запускается", "переходные состояния мигают ожиданием"),
            Case("INSTALLING", HostingTone.Wait, "Устанавливается", "установка ядра"),
            Case("MIGRATING", HostingTone.Wait, "Переезжает", "переезд между нодами"),
            Case("CRASHED", HostingTone.Bad, "Упал", "падение красным"),
            Case("SUSPENDED", HostingTone.Bad, "Приостановлен", "приостановка за неуплату красным"),
            Case("STOPPED", HostingTone.Off, "Остановлен", "остановлен"),
            Case("SLEEPING", HostingTone.Off, "Спит", "бесплатный сервер спит"),
            Case("ARCHIVED", HostingTone.Off, "В архиве", "архив"),
            Case("", HostingTone.Off, "Нет данных", "пустой статус не выдаёт себя за работающий"),
            Case("SOMETHING_NEW", HostingTone.Off, "Нет данных", "новый статус панели не роняет экран"),
        ).forEach { case ->
            assertEquals("${case.status}: ${case.why}", case.tone, hostingTone(case.status))
            assertEquals("${case.status}: ${case.why}", case.label, hostingStatusLabel(case.status))
        }
        HOSTING_BUSY_STATES.forEach { status ->
            assertEquals("занятое состояние $status обязано показываться ожиданием", HostingTone.Wait, hostingTone(status))
        }
    }

    @Test
    fun `память и деньги тарифа`() {
        data class GbCase(val mb: Int, val expected: String)
        listOf(GbCase(2048, "2"), GbCase(1536, "1,5"), GbCase(512, "0,5"), GbCase(0, "0"), GbCase(6144, "6")).forEach { case ->
            assertEquals("${case.mb} МБ: целые гигабайты без дроби, остальное с русской запятой", case.expected, hostingGb(case.mb))
        }
        data class RubCase(val kopecks: Long, val expected: String, val why: String)
        listOf(
            RubCase(19_900, "199 ₽", "ровные рубли"),
            RubCase(19_950, "200 ₽", "50 копеек округляются вверх"),
            RubCase(19_949, "199 ₽", "49 копеек вниз"),
            RubCase(12_345_600, "123 456 ₽", "разряды отделены пробелом"),
            RubCase(0, "0 ₽", "бесплатный тариф"),
        ).forEach { case ->
            assertEquals("${case.kopecks}: ${case.why}", case.expected, hostingRubles(case.kopecks))
        }
    }

    @Test
    fun `имя и подпись сервера`() {
        assertEquals("Остров", hostingName(HostingServerState(name = "Остров", slug = "island")))
        assertEquals("без имени показывается адресное имя", "island", hostingName(HostingServerState(slug = "island")))
        assertEquals("карточка не бывает безымянной", "Мой сервер", hostingName(HostingServerState()))

        assertEquals("Paper 1.21.4", hostingTag(HostingServerState(core = "Paper", version = "1.21.4")))
        assertEquals("без ядра нет лишнего пробела", "1.21.4", hostingTag(HostingServerState(version = "1.21.4")))
        assertEquals("", hostingTag(HostingServerState()))
    }

    @Test
    fun `даты оплаты и хранения мира`() {
        assertEquals("", hostingDay(""))
        assertEquals("мусор вместо даты не показывается", "", hostingDay("скоро"))
        assertEquals("6 октября 2026", hostingDay("2026-10-06"))
        assertEquals("полная метка с зоной читается так же", "8 марта 2026", hostingDay("2026-03-08T12:00:00Z"))
        assertEquals("метка со смещением читается так же", "8 марта 2026", hostingDay("2026-03-08T12:00:00+03:00"))

        val paid = HostingServerState(planPriceKopecks = 19_900, worldDeleteAt = "2026-11-01")
        val free = HostingServerState(planPriceKopecks = 0, worldDeleteAt = "2026-11-01")
        assertEquals("Оплачен до 1 декабря 2026", hostingPaidNote(paid, "2026-12-01"))
        assertEquals("бесплатному показывается срок хранения мира", "Мир хранится до 1 ноября 2026", hostingPaidNote(free, ""))
        assertNull("платный без даты оплаты не пугает удалением мира", hostingPaidNote(paid, ""))
        assertNull("без дат подписи нет", hostingPaidNote(HostingServerState(), ""))
    }

    @Test
    fun `момент события сегодня показывается часами`() {
        val today = ZonedDateTime.now(ZoneId.systemDefault()).withHour(15).withMinute(7).withSecond(0).withNano(0)

        assertEquals("сегодняшнее событие без даты", "15:07", hostingMoment(today.toOffsetDateTime().toString()))
        assertEquals("", hostingMoment(""))
        assertEquals("", hostingMoment("not a date"))
    }

    @Test
    fun `подтверждение действий над сервером`() {
        assertEquals("Остановить сервер?", hostingConfirmTitle(HostingModel.STOP))
        assertEquals("Перезапустить", hostingConfirmLabel(HostingModel.RESTART))
        assertEquals("Завершить процесс?", hostingConfirmTitle(HostingModel.KILL))
        assertTrue(
            "принудительное завершение обязано предупреждать о потере построек",
            hostingConfirmText(HostingModel.KILL).contains("без сохранения"),
        )
        assertTrue("остановка обещает сохранить мир", hostingConfirmText(HostingModel.STOP).contains("мир сохранится"))
    }

    @Test
    fun `сводка тарифов для витрины`() {
        val free = HostingPlan(code = "free", priceKopecks = 0, ramMb = 1024, maxPlayers = 10)
        val small = HostingPlan(code = "s", priceKopecks = 19_900, ramMb = 2048, maxPlayers = 20, sleeps = false)
        val big = HostingPlan(code = "l", priceKopecks = 49_900, ramMb = 6144, maxPlayers = 100, playersComfort = 40, sleeps = true)

        val facts = hostingPlanFacts(listOf(free, small, big))

        assertEquals(free, facts.free)
        assertEquals("бесплатный не входит в платные", listOf(small, big), facts.paid)
        assertEquals("цена «от» — самый дешёвый платный", 19_900L, facts.minPrice)
        assertEquals("память бесплатного не занижает нижнюю границу", 2048, facts.ramLow)
        assertEquals(6144, facts.ramHigh)
        assertEquals("комфортное число игроков честнее предельного", 40, facts.playersMax)
        assertFalse("если хоть один платный засыпает, «без сна» обещать нельзя", facts.paidAwake)

        assertTrue("все платные без сна", hostingPlanFacts(listOf(small)).paidAwake)
        val empty = hostingPlanFacts(emptyList())
        assertNull(empty.free)
        assertEquals(0L, empty.minPrice)
        assertFalse("без платных тарифов обещать нечего", empty.paidAwake)
    }

    @Test
    fun `склонение дней и прав`() {
        data class Case(val count: Int, val days: String, val rights: String)
        listOf(
            Case(0, "0 дней", "0 прав"),
            Case(1, "1 день", "1 право"),
            Case(3, "3 дня", "3 права"),
            Case(5, "5 дней", "5 прав"),
            Case(11, "11 дней", "11 прав"),
            Case(21, "21 день", "21 право"),
            Case(112, "112 дней", "112 прав"),
        ).forEach { case ->
            assertEquals("${case.count}: склонение срока", case.days, hostingDaysLabel(case.count))
            assertEquals("${case.count}: склонение прав доступа", case.rights, toolsRightsCount(case.count))
        }
    }

    @Test
    fun `файлы сервера, которые открываются редактором и как архив`() {
        data class Case(val name: String, val text: Boolean, val archive: Boolean, val why: String)
        listOf(
            Case("server.properties", true, false, "настройки сервера редактируются"),
            Case("PAPER.YML", true, false, "регистр расширения не важен"),
            Case("latest.log", true, false, "журнал читается"),
            Case("world.zip", false, true, "архив мира распаковывается, а не открывается текстом"),
            Case("backup.tar.gz", false, true, "двойное расширение архива"),
            Case("backup.TGZ", false, true, "короткое имя tar.gz"),
            Case("paper.jar", false, false, "ядро не текст и не архив для распаковки"),
            Case("README", false, false, "без расширения редактор не предлагается"),
        ).forEach { case ->
            assertEquals("${case.name}: ${case.why}", case.text, toolsIsText(case.name))
            assertEquals("${case.name}: ${case.why}", case.archive, toolsIsArchive(case.name))
        }
    }

    @Test
    fun `размеры файлов`() {
        assertEquals("0 Б", toolsBytes(0))
        assertEquals("отрицательный размер от панели не печатается", "0 Б", toolsBytes(-5))
        assertEquals("512 Б", toolsBytes(512))
        assertEquals("2 КБ", toolsBytes(2048))
        assertEquals("пустой снимок называется пустым", "пусто", humanSize(0))
        assertEquals("1023 Б", humanSize(1023))
        assertEquals("килобайты без дробей", "1 КБ", humanSize(1536))
        assertEquals("5.0 МБ", humanSize(5L * 1024 * 1024))
        assertEquals("1.5 ГБ", humanSize(3L * 512 * 1024 * 1024))
    }

    @Test
    fun `время изменения файла`() {
        assertEquals("", toolsMoment(""))
        assertEquals("непонятная метка не печатается", "", toolsMoment("вчера"))
        assertTrue(
            "метка ISO превращается в дату и время",
            Regex("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}").matches(toolsMoment("2026-03-08T12:00:00Z")),
        )
        assertTrue(Regex("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}").matches(toolsMoment("2026-03-08T12:00:00+03:00")))
    }
}
