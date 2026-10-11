package net.millida.launcher.core

import net.millida.launcher.core.runtime.PhoneJvmArgs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The launcher puts `-XX:+UseSerialGC` on every phone command line. A pack or
 * a player bringing a second collector, a Graal switch or a module flag on
 * Java 8 stops the machine before the first line of the game, so these are
 * dropped and everything else passes untouched.
 */
class PhoneJvmArgsTest {

    @Test
    fun `флаги, с которыми Java телефона не стартует`() {
        data class Case(val arg: String, val java: Int, val kept: Boolean, val why: String)

        val cases = listOf(
            Case("-XX:+UseG1GC", 21, false, "второй сборщик рядом с SerialGC — отказ машины"),
            Case("-XX:+UseZGC", 21, false, "то же для ZGC"),
            Case("-XX:+ZGenerational", 21, false, "настройка ZGC без ZGC"),
            Case("-XX:+UseConcMarkSweepGC", 21, false, "CMS удалён в Java 14, сборки 1.7.10 его просят"),
            Case("-XX:+CMSIncrementalMode", 8, false, "настройка CMS без CMS"),
            Case("-XX:CMSInitiatingOccupancyFraction=75", 8, false, "числовая настройка CMS"),
            Case("-XX:+EnableJVMCI", 21, false, "в рантайме нет Graal"),
            Case("-XX:+UseJVMCICompiler", 21, false, "то же"),
            Case("-XstartOnFirstThread", 21, false, "флаг macOS"),
            Case("-XX:+AlwaysPreTouch", 21, false, "телефон занял бы всю кучу сразу и закрыл игру"),
            Case("-XX:+UseLargePages", 21, false, "больших страниц телефон не даёт"),
            Case("--add-opens=java.base/java.lang=ALL-UNNAMED", 21, true, "модули нужны lwjgl3ify на Java 21"),
            Case("--add-opens=java.base/java.lang=ALL-UNNAMED", 8, false, "Java 8 не знает модулей и не стартует"),
            Case("-XX:+UseSerialGC", 21, true, "сборщик самого лаунчера остаётся"),
            Case("-XX:+UnlockExperimentalVMOptions", 8, true, "безвредный флаг"),
            Case("-Dfml.ignorePatchDiscrepancies=true", 8, true, "свойства системы не трогаются"),
            Case("-javaagent:/data/authlib.jar=https://api.millida.net", 21, true, "агент входа Millida нужен для скина и сети"),
            Case("-Xmx4096M", 21, true, "память решает другой фильтр"),
            Case("  ", 21, false, "пустой аргумент Java принимает за главный класс"),
        )
        cases.forEach { case ->
            assertEquals("${case.arg} на Java ${case.java}: ${case.why}", case.kept, PhoneJvmArgs.keep(case.arg, case.java))
        }
    }

    @Test
    fun `модульный флаг двумя словами уходит вместе со значением`() {
        assertEquals(
            "на Java 8 значение без флага стало бы главным классом",
            listOf("-Xmx2G", "-Dx=1"),
            PhoneJvmArgs.filter(listOf("-Xmx2G", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-Dx=1"), 8),
        )
        assertEquals(
            "на Java 21 пара остаётся как есть",
            listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED"),
            PhoneJvmArgs.filter(listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED"), 21),
        )
        assertEquals(
            "следующий флаг после выброшенного не теряется",
            listOf("-Dy=2"),
            PhoneJvmArgs.filter(listOf("--add-opens", "-Dy=2"), 8),
        )
    }
}
