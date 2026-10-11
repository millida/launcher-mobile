package net.millida.launcher.core

import net.millida.launcher.core.runtime.HostAbi
import org.junit.Assert.assertEquals
import org.junit.Test

class HostAbiTest {

    @Test
    fun `запуск разрешён только там, где arm64 родной`() {
        data class Case(val abis: List<String>, val allowed: Boolean, val why: String)

        val cases = listOf(
            Case(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), true, "обычный телефон на arm64"),
            Case(listOf("arm64-v8a"), true, "телефон только с 64-битным arm"),
            Case(
                listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a"),
                false,
                "эмулятор с переводом arm: рантайм arm64 падает в dlopen на x86_64 libz.so и __sF",
            ),
            Case(listOf("x86_64", "x86"), false, "x86 без перевода не запустит и само приложение"),
            Case(listOf("armeabi-v7a", "armeabi"), false, "32-битному arm нет Java в пакете"),
        )

        for (case in cases) {
            assertEquals(
                "вердикт для ${case.abis}: ${case.why}",
                case.allowed,
                HostAbi.launchProblem(case.abis) == null,
            )
        }
    }

    @Test
    fun `отчёт называет настоящий процессор, а не переведённый`() {
        assertEquals(
            "эмулятор не должен выдавать себя за arm64 в отчёте тестировщика",
            "x86_64, arm64-v8a через перевод",
            HostAbi.describe(listOf("x86_64", "x86", "arm64-v8a")),
        )
        assertEquals("у телефона отчёт не меняется", "arm64-v8a", HostAbi.describe(listOf("arm64-v8a", "armeabi-v7a")))
    }
}
