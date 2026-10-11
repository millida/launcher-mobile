package net.millida.launcher.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.hosting.HostingDetails
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.core.hosting.asArray
import net.millida.launcher.core.hosting.child
import net.millida.launcher.core.hosting.flag
import net.millida.launcher.core.hosting.int
import net.millida.launcher.core.hosting.long
import net.millida.launcher.core.hosting.real
import net.millida.launcher.core.hosting.str
import net.millida.launcher.core.hosting.strings
import net.millida.launcher.core.hosting.toServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostingModelsTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private data class StatusCase(val status: String, val running: Boolean, val crashed: Boolean, val why: String)

    @Test
    fun `поля ответа читаются, даже когда служба прислала их не тем типом`() {
        val o = obj(
            """
            {"s":"text","n":null,"num":42,"numStr":"42","frac":"12.9","big":"5000000000","bad":"много",
             "t":true,"tStr":"true","f":"false","yes":"yes","obj":{"a":1},"arr":["a",{"x":1},7]}
            """.trimIndent(),
        )
        assertEquals("строка читается как есть", "text", o.str("s"))
        assertEquals("null не печатается словом null", "", o.str("n"))
        assertEquals("отсутствующее поле — пустая строка", "", o.str("missing"))
        assertEquals("объект вместо строки не превращается в JSON-текст на экране", "", o.str("obj"))
        assertEquals("число читается строкой без кавычек", "42", o.str("num"))

        assertEquals("число из строки", 42, o.int("numStr"))
        assertEquals("дробное отбрасывает хвост, а не обнуляется", 12, o.int("frac"))
        assertEquals("мусор берёт запасное значение", 7, o.int("bad", 7))
        assertEquals("null берёт запасное значение", 3, o.int("n", 3))
        assertEquals("цена больше Int не переполняется", 5_000_000_000L, o.long("big"))
        assertEquals("дробь читается", 12.9, o.real("frac"), 1e-9)
        assertEquals("мусор в дроби — запасное значение", 1.5, o.real("bad", 1.5), 1e-9)

        assertTrue("булево true", o.flag("t"))
        assertTrue("строка «true»", o.flag("tStr"))
        assertFalse("строка «false» при запасном true", o.flag("f", true))
        assertTrue("«yes» не булево — запасное значение", o.flag("yes", true))
        assertFalse("отсутствующий флаг — запасное false", o.flag("missing"))

        assertEquals("в списке строк объект пропускается", listOf("a", "7"), o.strings("arr"))
        assertEquals("не массив — пустой список", emptyList<String>(), o.strings("s"))
        assertEquals("вложенный объект находится", 1, o.child("obj")?.int("a"))
        assertNull("строка не объект", o.child("s"))
    }

    @Test
    fun `список приходит голым массивом или в обёртке`() {
        val cases = listOf(
            Triple("""[{"id":"1"},{"id":"2"}]""", listOf("1", "2"), "голый массив"),
            Triple("""{"items":[{"id":"1"}]}""", listOf("1"), "обёртка items"),
            Triple("""{"servers":[{"id":"s"}],"items":[{"id":"i"}]}""", listOf("s"), "поле, названное первым, важнее"),
            Triple("""{"data":[{"id":"1"}]}""", emptyList<String>(), "незнакомая обёртка — пустой список, а не падение"),
            Triple("""[{"id":"1"},"мусор",5,null]""", listOf("1"), "не-объекты в массиве пропускаются"),
            Triple("\"строка\"", emptyList<String>(), "строка вместо списка"),
            Triple("{}", emptyList<String>(), "пустой объект"),
        )
        for ((json, expected, why) in cases) {
            val ids = Json.parseToJsonElement(json).asArray("servers", "items").map { it.str("id") }
            assertEquals("$json: $why", expected, ids)
        }
    }

    @Test
    fun `карточка сервера собирается из старых и новых имён полей`() {
        val state = obj(
            """
            {"id":"srv-1","name":"Выживание","slug":"surv","status":"RUNNING","ip":"node.example:25571",
             "plan":"Старт","planRamMb":"4096","ramMb":1700.0,"maxPlayers":null,"planMaxPlayers":"20",
             "playersOnline":"3","planPriceKopecks":"29900","worldDeleteAt":null,"pendingRestart":["motd","pvp"]}
            """.trimIndent(),
        ).toServerState()

        assertEquals("адрес берётся из старого поля ip", "node.example:25571", state.address)
        assertEquals("название тарифа из старого поля plan", "Старт", state.planName)
        assertEquals("память тарифа строкой", 4096, state.planRamMb)
        assertEquals("память дробным числом", 1700, state.ramMb)
        assertEquals("null в слотах — ноль, а не падение", 0, state.maxPlayers)
        assertEquals("онлайн строкой", 3, state.playersOnline)
        assertEquals("цена в копейках строкой", 29900L, state.planPriceKopecks)
        assertEquals("null в дате удаления мира — пусто", "", state.worldDeleteAt)
        assertEquals("ждущие перезапуска настройки", listOf("motd", "pvp"), state.pendingRestart)

        val fresh = obj("""{"address":"new.example","ip":"old.example","planName":"Про","plan":"Старт"}""").toServerState()
        assertEquals("новое поле address важнее старого ip", "new.example", fresh.address)
        assertEquals("новое поле planName важнее старого plan", "Про", fresh.planName)

        val empty = obj("{}").toServerState()
        assertEquals("пустой ответ — пустая карточка, а не исключение", HostingServerState(), empty)
    }

    @Test
    fun `состояние и бесплатность сервера`() {
        val cases = listOf(
            StatusCase("RUNNING", true, false, "работает"),
            StatusCase("CRASHED", false, true, "упал — экран покажет причину падения"),
            StatusCase("STOPPED", false, false, "остановлен"),
            StatusCase("running", false, false, "служба шлёт заглавными; строчные — не наш статус"),
            StatusCase("", false, false, "нет статуса — не работает"),
        )
        for (case in cases) {
            val state = HostingServerState(status = case.status)
            assertEquals("«${case.status}» running: ${case.why}", case.running, state.running)
            assertEquals("«${case.status}» crashed: ${case.why}", case.crashed, state.crashed)
        }
        assertTrue("нулевая цена — бесплатный", HostingServerState(planPriceKopecks = 0).free)
        assertTrue("отрицательная цена от скидки — тоже бесплатный, а не платный", HostingServerState(planPriceKopecks = -100).free)
        assertFalse("копейка — платный: бесплатному прячут платные функции", HostingServerState(planPriceKopecks = 1).free)
    }

    @Test
    fun `права на сервер у владельца и у приглашённого`() {
        assertTrue("владелец может всё, даже без списка прав", HostingDetails(owner = true).can("console"))
        val guest = HostingDetails(owner = false, permissions = listOf("console", "files"))
        assertTrue("выданное право работает", guest.can("console"))
        assertFalse("невыданное право закрыто — иначе гость остановит чужой сервер", guest.can("control"))
        assertFalse("регистр права не угадывается", guest.can("Console"))
        assertFalse("без прав приглашённый ничего не может", HostingDetails(owner = false).can("files"))
    }
}
