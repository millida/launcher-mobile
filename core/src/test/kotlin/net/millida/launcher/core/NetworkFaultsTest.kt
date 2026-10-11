package net.millida.launcher.core

import net.millida.launcher.core.http.NetworkFaults
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class NetworkFaultsTest {

    private fun wrap(inner: Throwable, times: Int): Throwable {
        var current = inner
        repeat(times) { current = IOException("обёртка $it", current) }
        return current
    }

    @Test
    fun `сбой DNS находится в цепочке причин`() {
        val dns = UnknownHostException("api.millida.net")
        val cases = listOf(
            dns to "сам сбой DNS",
            wrap(dns, 1) to "OkHttp оборачивает его один раз",
            wrap(dns, 7) to "глубоко внутри наших обёрток — последнее, что ещё проверяется",
        )
        for ((error, why) in cases) {
            assertSame("$why: игрок не увидит совет про DNS и VPN", dns, NetworkFaults.unknownHost(error))
        }
    }

    @Test
    fun `другие сбои не выдаются за DNS`() {
        assertNull("нет ошибки — нет DNS-сбоя", NetworkFaults.unknownHost(null))
        assertNull("таймаут — не DNS, совет про VPN был бы ложным", NetworkFaults.unknownHost(IOException("timeout")))
        assertNull(
            "причина глубже восьми обёрток не ищется — защита от бесконечных цепочек",
            NetworkFaults.unknownHost(wrap(UnknownHostException("x"), 8)),
        )
    }

    @Test
    fun `кольцо причин не вешает экран`() {
        val first = IOException("первая")
        val second = IOException("вторая")
        first.initCause(second)
        second.initCause(first)
        assertNull("кольцо причин без DNS-сбоя должно закончиться null, а не зациклиться", NetworkFaults.unknownHost(first))
    }

    @Test
    fun `совет про DNS называет адрес и что делать`() {
        val advice = NetworkFaults.nameLookupAdvice("сервера Millida")
        assertTrue("в совете не назван адрес: $advice", advice.contains("сервера Millida"))
        assertTrue("в совете нет шага про интернет: $advice", advice.contains("Проверь интернет"))
        assertTrue("в совете нет обхода через VPN: $advice", advice.contains("VPN"))
    }
}
