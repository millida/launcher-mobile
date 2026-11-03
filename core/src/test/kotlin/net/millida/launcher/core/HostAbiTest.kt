package net.millida.launcher.core

import net.millida.launcher.core.runtime.HostAbi
import net.millida.launcher.core.runtime.RUNTIME_MANIFEST_ARM32_URL
import net.millida.launcher.core.runtime.RUNTIME_MANIFEST_URL
import net.millida.launcher.core.runtime.runtimeManifestUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class HostAbiTest {

    @Test
    fun `запуск разрешён только там, где arm родной`() {
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
            Case(listOf("armeabi-v7a", "armeabi"), true, "телефон с 32-битной системой: свой манифест рантайма со своей Java"),
            Case(listOf("x86", "armeabi-v7a"), false, "32-битный эмулятор с переводом arm — тоже не телефон"),
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
    fun `игра идёт в родной архитектуре телефона, и рантайм берётся под неё`() {
        data class Case(val abis: List<String>, val game: String?, val manifest: String?, val libraries: String?, val why: String)

        val cases = listOf(
            Case(listOf("arm64-v8a", "armeabi-v7a"), "arm64-v8a", RUNTIME_MANIFEST_URL, "lib64",
                "64-битный телефон остаётся на прежнем манифесте: его читают и старые версии приложения"),
            Case(listOf("armeabi-v7a", "armeabi"), "armeabi-v7a", RUNTIME_MANIFEST_ARM32_URL, "lib",
                "32-битная система, даже на 64-битном процессоре: 64-битные библиотеки в её процесс не загрузятся"),
            Case(listOf("x86_64", "arm64-v8a"), null, null, null, "перевод arm на эмуляторе не считается своей архитектурой"),
            Case(emptyList(), null, null, null, "система не назвала ни одной архитектуры"),
        )
        for (case in cases) {
            val game = HostAbi.game(case.abis)
            assertEquals("архитектура игры для ${case.abis}: ${case.why}", case.game, game)
            assertEquals("манифест рантайма: ${case.why}", case.manifest, game?.let(::runtimeManifestUrl))
            assertEquals("системная папка библиотек: ${case.why}", case.libraries, game?.let(HostAbi::systemLibraryFolder))
        }
        assertEquals(
            "32-битный манифест лежит отдельным файлом рядом с основным",
            RUNTIME_MANIFEST_URL.substringBeforeLast('/'),
            RUNTIME_MANIFEST_ARM32_URL.substringBeforeLast('/'),
        )
    }

    @Test
    fun `отчёт называет настоящий процессор, а не переведённый`() {
        assertEquals(
            "эмулятор не должен выдавать себя за arm64 в отчёте тестировщика",
            "x86_64, arm64-v8a через перевод",
            HostAbi.describe(listOf("x86_64", "x86", "arm64-v8a")),
        )
        assertEquals("у телефона отчёт не меняется", "arm64-v8a", HostAbi.describe(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("32-битный телефон назван как есть", "armeabi-v7a", HostAbi.describe(listOf("armeabi-v7a", "armeabi")))
    }
}
