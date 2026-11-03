package net.millida.launcher.core

import net.millida.launcher.core.content.ModVersionRange
import org.junit.Assert.assertEquals
import org.junit.Test

class ModVersionRangeTest {

    private fun check(range: String, version: String, want: Boolean?) =
        assertEquals("«$range» для «$version»", want, ModVersionRange.satisfies(range, version))

    @Test
    fun `Fabric - условия через пробел должны выполняться все`() {
        check(">=1.20 <1.21", "1.20.1", true)
        check(">=1.20 <1.21", "1.21", false)
        check(">=1.20 <1.21", "1.19.4", false)
        check(">= 1.20 < 1.21", "1.20.4", true)
        check(">=1.21.9", "1.21.10", true)
    }

    @Test
    fun `Fabric - тильда, крышка, маска, звёздочка, или`() {
        check("~1.20.1", "1.20.4", true)
        check("~1.20.1", "1.21", false)
        check("~1.20", "1.20.6", true)
        check("~1", "1.99", true)
        check("^1.20", "1.21.1", true)
        check("^1.20", "2.0", false)
        check("1.20.x", "1.20.4", true)
        check("1.20.x", "1.21", false)
        check("*", "26.3", true)
        check("1.19.2 || >=1.20 <1.20.2", "1.20.1", true)
        check("1.19.2 || 1.20.1", "1.20.4", false)
        check("1.20.1", "1.20.1", true)
        check("1.20.1", "1.20.4", false)
    }

    @Test
    fun `Forge и NeoForge - интервалы в скобках`() {
        check("[1.20,1.21)", "1.20.4", true)
        check("[1.20,1.21)", "1.21", false)
        check("(1.20,)", "1.20", false)
        check("[1.20.1]", "1.20.1", true)
        check("[1.20.1]", "1.20.2", false)
        check("[1.19,1.20),[1.20.2,)", "1.20.1", false)
        check("[1.19,1.20),[1.20.2,)", "1.21", true)
        check("[47,)", "47.1.3", true)
    }

    @Test
    fun `версии мода с хвостами сравниваются числами`() {
        check(">=0.6.0", "0.6.0-beta.2+mc1.21.1", true)
        check(">=0.6.0 <0.7", "0.5.11+mc1.21", false)
        check(">=0.6.0 <0.7", "0.6.3", true)
    }

    @Test
    fun `непонятное не превращается в отказ`() {
        check("", "1.20", null)
        check(">=", "1.20", null)
        check("latest", "1.20", null)
        check(">=1.20 latest", "1.21", null)
        check("<1.20 latest", "1.21", false)
        check(">=1.20", "snapshot", null)
    }
}
