package net.millida.launcher.core

import net.millida.launcher.core.mojang.HostProfile
import net.millida.launcher.core.mojang.Maven
import net.millida.launcher.core.mojang.OsRule
import net.millida.launcher.core.mojang.Rule
import net.millida.launcher.core.mojang.Rules
import net.millida.launcher.core.mojang.VersionResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {

    private val android = HostProfile.ANDROID_ARM64

    @Test
    fun `правила ос решают судьбу библиотеки`() {
        data class Case(val rules: List<Rule>, val expected: Boolean, val why: String)

        val cases = listOf(
            Case(emptyList(), true, "без правил библиотека нужна всегда"),
            Case(
                listOf(Rule(action = "allow", os = OsRule(name = "windows"))),
                false,
                "разрешение только для windows не даёт права телефону",
            ),
            Case(
                listOf(Rule(action = "allow", os = OsRule(name = "linux"))),
                true,
                "телефон представляется linux, значит правило подходит",
            ),
            Case(
                listOf(Rule(action = "allow"), Rule(action = "disallow", os = OsRule(name = "linux"))),
                false,
                "запрет ниже по списку перебивает общее разрешение",
            ),
            Case(
                listOf(Rule(action = "allow", os = OsRule(name = "linux", arch = "x86"))),
                false,
                "разрядность x86 на arm64 не подходит",
            ),
            Case(
                listOf(Rule(action = "allow", os = OsRule(name = "linux", arch = "aarch64"))),
                true,
                "arm64 совпадает с профилем устройства",
            ),
            Case(
                listOf(Rule(action = "allow", features = mapOf("is_demo_user" to true))),
                false,
                "требование демо-режима не выполняется",
            ),
        )

        for (case in cases) {
            assertEquals(
                "правила ${case.rules} дали не тот вердикт: ${case.why}",
                case.expected,
                Rules.allowed(case.rules, android),
            )
        }
    }

    @Test
    fun `координата превращается в путь maven`() {
        data class Case(val name: String, val path: String)

        val cases = listOf(
            Case("com.google.guava:guava:32.1.2-jre", "com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar"),
            Case("net.fabricmc:tiny-mappings-parser:0.3.0", "net/fabricmc/tiny-mappings-parser/0.3.0/tiny-mappings-parser-0.3.0.jar"),
            Case("net.minecraftforge:forge:1.20.1-47.2.0:universal", "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar"),
            Case("org.ow2.asm:asm:9.6@jar", "org/ow2/asm/asm/9.6/asm-9.6.jar"),
        )

        for (case in cases) {
            assertEquals("координата ${case.name} разобрана неверно", case.path, Maven.pathOf(case.name))
        }
    }

    @Test
    fun `оконная библиотека берётся своя, остальное игры остаётся`() {
        val dropped = listOf(
            "org.lwjgl:lwjgl:3.4.1",
            "org.lwjgl:lwjgl-glfw:3.4.1",
            "org.lwjgl:lwjgl-opengl:3.4.1",
            "org.lwjgl:lwjgl-shaderc:3.4.1",
            "net.java.jinput:jinput:2.0.5",
        )
        val kept = listOf(
            "com.mojang:brigadier:1.0.18",
            "com.mojang:jtracy:1.0.37",
            "com.mojang:text2speech:1.19.12",
            "com.google.code.gson:gson:2.10.1",
            "net.fabricmc:fabric-loader:0.16.9",
        )

        dropped.forEach {
            assertTrue("$it подменяется своей сборкой под телефон", VersionResolver.isDesktopOnly(it))
        }
        kept.forEach {
            assertFalse("$it — классы, нужные игре и на телефоне", VersionResolver.isDesktopOnly(it))
        }
    }

    @Test
    fun `нативы с классификатором не подменяют классы`() {
        data class Case(val name: String, val key: String, val classifier: String, val why: String)

        val cases = listOf(
            Case(
                "com.mojang:jtracy:1.0.37",
                "com.mojang:jtracy",
                "",
                "обычный jar с классами",
            ),
            Case(
                "com.mojang:jtracy:1.0.37:natives-linux",
                "com.mojang:jtracy:natives-linux",
                "natives-linux",
                "с 26.x нативы это отдельная запись, и её ключ обязан отличаться, иначе она затрёт классы",
            ),
            Case(
                "org.lwjgl:lwjgl:3.4.1:unsafe",
                "org.lwjgl:lwjgl:unsafe",
                "unsafe",
                "прочие классификаторы тоже часть имени",
            ),
            Case(
                "org.ow2.asm:asm:9.6@jar",
                "org.ow2.asm:asm",
                "",
                "расширение через собачку не путается с классификатором",
            ),
        )

        for (case in cases) {
            assertEquals(
                "ключ склейки для ${case.name}: ${case.why}",
                case.key,
                Maven.coordinateWithoutVersion(case.name),
            )
            assertEquals("классификатор ${case.name}", case.classifier, Maven.classifierOf(case.name))
        }
    }
}
