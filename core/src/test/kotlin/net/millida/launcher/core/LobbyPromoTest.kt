package net.millida.launcher.core

import kotlinx.serialization.json.Json
import net.millida.launcher.core.hub.LobbyPromo
import net.millida.launcher.core.hub.PromoReady
import net.millida.launcher.core.hub.PromoSlide
import org.junit.Assert.assertEquals
import org.junit.Test

class LobbyPromoTest {

    @Test
    fun `слайды баннера идут в порядке ПК и выпадают без данных`() {
        data class Case(val ready: PromoReady, val expected: List<PromoSlide>, val why: String)
        listOf(
            Case(
                PromoReady(ownEvent = true, arcania = true, invite = true),
                listOf(PromoSlide.DISCORD, PromoSlide.TELEGRAM, PromoSlide.OWN_EVENT, PromoSlide.ARCANIA, PromoSlide.INVITE),
                "всё известно — порядок как на ПК: чаты, свой сервер, Аркания, приглашение",
            ),
            Case(
                PromoReady(ownEvent = false, arcania = false, invite = false),
                listOf(PromoSlide.DISCORD, PromoSlide.TELEGRAM),
                "чаты не ждут сети, поэтому баннер не пустеет даже без каталога",
            ),
            Case(
                PromoReady(ownEvent = false, arcania = true, invite = false),
                listOf(PromoSlide.DISCORD, PromoSlide.TELEGRAM, PromoSlide.ARCANIA),
                "свой сервер не запустится на этом телефоне — его слайд не предлагается",
            ),
        ).forEach { case ->
            assertEquals(case.why, case.expected, LobbyPromo.slides(case.ready))
        }
    }

    @Test
    fun `листание по кругу в обе стороны`() {
        data class Case(val count: Int, val start: Int, val step: Int, val expected: Int, val why: String)
        listOf(
            Case(5, 0, 0, 0, "без шагов стоит первый слайд"),
            Case(5, 3, 2, 0, "за последним снова первый"),
            Case(5, 0, -1, 4, "назад с первого — на последний, а не в минус"),
            Case(5, 2, -12, 0, "много шагов назад не выходят за список"),
            Case(3, 1, Int.MAX_VALUE, 2, "счётчик шагов у края Int не переполняется"),
            Case(0, 4, 7, 0, "пустой список не падает делением на ноль"),
        ).forEach { case ->
            assertEquals(case.why, case.expected, LobbyPromo.at(case.count, case.start, case.step))
        }
    }

    @Test
    fun `первый слайд дня у каждого свой`() {
        data class Case(val count: Int, val day: Long, val salt: Int, val expected: Int, val why: String)
        listOf(
            Case(5, 20_000, 0, 0, "день кратен числу слайдов — первый"),
            Case(5, 20_001, 0, 1, "назавтра начинается со следующего"),
            Case(5, 20_000, 3, 3, "соль игрока сдвигает начало: показы делятся поровну"),
            Case(5, 20_000, -7, 3, "отрицательный хеш не даёт отрицательного номера"),
            Case(0, 20_000, 3, 0, "без слайдов — ноль"),
        ).forEach { case ->
            assertEquals(case.why, case.expected, LobbyPromo.start(case.count, case.day, case.salt))
        }
    }

    @Test
    fun `баннер снимается только явным null`() {
        data class Case(val body: String?, val expected: Boolean, val why: String)
        listOf(
            Case("""{"promo":{"lobby":"MCRU_ANARCHY"}}""", true, "место отдано баннеру"),
            Case("""{"promo":{"lobby":null}}""", false, "API убрал баннер без выпуска приложения"),
            Case("""{"promo":{}}""", true, "поля нет — как в выпуске, баннер стоит"),
            Case("""{"promo":{"lobby":"SOMETHING_NEW"}}""", true, "незнакомое значение не прячет баннер"),
            Case("""{"other":1}""", true, "ответ без promo не ломает лобби"),
            Case("""[]""", true, "мусор вместо объекта — баннер по умолчанию"),
            Case(null, true, "пустой ответ — баннер по умолчанию"),
        ).forEach { case ->
            val body = case.body?.let { Json.parseToJsonElement(it) }
            assertEquals(case.why, case.expected, LobbyPromo.lobbyShown(body))
        }
    }
}
