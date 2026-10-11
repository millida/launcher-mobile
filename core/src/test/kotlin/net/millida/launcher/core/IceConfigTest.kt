package net.millida.launcher.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.call.IceConfigs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IceConfigTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun urlsOf(servers: String): List<String>? =
        IceConfigs.parse(obj("""{"iceServers":$servers}"""))?.first?.servers?.flatMap { it.urls }

    private data class UrlCase(val servers: String, val expected: List<String>?, val why: String)

    @Test
    fun `адреса ретрансляторов проходят только по схеме ICE`() {
        val cases = listOf(
            UrlCase("""[{"urls":" stun:stun.example:3478 "}]""", listOf("stun:stun.example:3478"), "краевые пробелы срезаются"),
            UrlCase("""[{"urls":"TURN:turn.example:3478"}]""", listOf("TURN:turn.example:3478"), "схема без учёта регистра"),
            UrlCase("""[{"urls":["turns:turn.example:5349","stuns:stun.example"]}]""", listOf("turns:turn.example:5349", "stuns:stun.example"), "защищённые схемы тоже ICE"),
            UrlCase("""[{"urls":"turn:turn example"}]""", null, "пробел внутри адреса — не адрес"),
            UrlCase("""[{"urls":"stun:"}]""", null, "схема без хоста ничего не даёт"),
            UrlCase("""[{"urls":"turnstile:x"}]""", null, "похожая на turn схема — чужая"),
            UrlCase("""[{"urls":"https://turn.example"}]""", null, "веб-адрес не ретранслятор"),
            UrlCase("""[{"urls":[1,true,null]}]""", null, "не строки в списке отбрасываются"),
            UrlCase("""[{"urls":{"a":"stun:x"}}]""", null, "объект вместо списка — нет адресов"),
            UrlCase("""[{"urls":1}]""", null, "число вместо строки — нет адресов"),
            UrlCase("""["stun:x", {"urls":"stun:y"}]""", listOf("stun:y"), "строка вместо записи сервера пропускается, остальные остаются"),
            UrlCase("""{}""", null, "объект вместо списка серверов — запасной вариант"),
        )
        cases.forEach { case -> assertEquals("${case.servers}: ${case.why}", case.expected, urlsOf(case.servers)) }
    }

    @Test
    fun `логин и пароль берутся только строками`() {
        val parsed = IceConfigs.parse(obj("""{"iceServers":[{"urls":"turn:t:3478","username":5,"credential":"p"}]}"""))!!
        val server = parsed.first.servers.single()
        assertNull("число вместо логина не становится логином «5»", server.username)
        assertEquals("p", server.credential)
    }

    private data class TtlCase(val body: String, val cache: Long, val relayOnly: Boolean, val why: String)

    @Test
    fun `срок кеша и режим только-ретранслятор читаются осторожно`() {
        val servers = "\"iceServers\":[{\"urls\":\"stun:s\"}]"
        val cases = listOf(
            TtlCase("""{$servers}""", 3_300L, false, "без срока — час минус пять минут"),
            TtlCase("""{$servers,"ttlSeconds":"60"}""", 3_300L, false, "срок строкой не принимается"),
            TtlCase("""{$servers,"ttlSeconds":0}""", 300L, false, "нулевой срок — минимум пять минут, а не опрос на каждый звонок"),
            TtlCase("""{$servers,"ttlSeconds":86400}""", 86_100L, false, "длинный срок тоже обновляется заранее"),
            TtlCase("""{$servers,"relayOnly":"true"}""", 3_300L, false, "строка не включает режим только-ретранслятор"),
            TtlCase("""{$servers,"relayOnly":true}""", 3_300L, true, "флаг сервера соблюдается"),
        )
        cases.forEach { case ->
            val parsed = IceConfigs.parse(obj(case.body))
            assertEquals("${case.body}: ${case.why}", case.cache, parsed?.second)
            assertEquals("${case.body}: ${case.why}", case.relayOnly, parsed?.first?.relayOnly)
        }
    }

    @Test
    fun `запасной вариант — публичные STUN без ограничения на ретранслятор`() {
        assertFalse("без TURN нельзя требовать только ретранслятор — звонок не соединится вовсе", IceConfigs.FALLBACK.relayOnly)
        assertTrue(IceConfigs.FALLBACK.servers.flatMap { it.urls }.all { it.startsWith("stun:") })
        assertNull("ответ без поля iceServers — запасной вариант", IceConfigs.parse(obj("{}")))
        assertEquals(300L, IceConfigs.cacheSeconds(-100L))
        assertEquals(3_300L, IceConfigs.cacheSeconds(null))
    }
}
