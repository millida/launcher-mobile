package net.millida.launcher.core

import net.millida.launcher.core.logs.CrashHints
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashHintsTest {

    private fun fabricLog(vararg details: String): String = buildString {
        appendLine("[19:42:44] [main/INFO]: Loading Minecraft 1.21.11 with Fabric Loader 0.19.5")
        appendLine("[19:42:45] [main/ERROR]: Incompatible mods found!")
        appendLine("net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!")
        appendLine("More details:")
        details.forEach { appendLine("\t - $it") }
        appendLine("\tat net.fabricmc.loader.impl.FormattedException.ofLocalized(FormattedException.java:51)")
    }

    @Test
    fun `отказ загрузчика из-за модов объясняется тем, что поставить`() {
        data class Case(val log: String, val hint: String?, val why: String)

        val cases = listOf(
            Case(
                fabricLog("Mod 'Iris' (iris) 1.10.7+mc1.21.11 requires any 0.8.x version of sodium, which is missing!"),
                "Сборке не хватает мода sodium: его требует «Iris». Поставь его в каталоге и запусти снова",
                "одна недостающая зависимость называется вместе с модом, который её просит",
            ),
            Case(
                fabricLog(
                    "Mod 'Better Advancements' (betteradvancements) 0.6.0.78 requires any version of fabric-api, which is missing!",
                    "Mod 'EntityCulling' (entityculling) 1.11.3 requires any version of fabric-api, which is missing!",
                ),
                "Сборке не хватает мода fabric-api: его требует «Better Advancements». Поставь его в каталоге и запусти снова",
                "одна и та же зависимость у нескольких модов называется один раз",
            ),
            Case(
                fabricLog(
                    "Mod 'Sounds' (sounds) 2.4.23+edge requires any version between 1.0.26+edge+1.21.11 (inclusive) and 1.1- (exclusive) of mru, which is missing!",
                    "Mod 'Combat Roll' (combat_roll) 3.0.1+1.21.11 requires any version of cloth-config, which is missing!",
                    "Mod 'A' (a) 1 requires any version of b, which is missing!",
                    "Mod 'C' (c) 1 requires any version of d, which is missing!",
                ),
                "Сборке не хватает модов: mru, cloth-config, b и ещё 1. Поставь их в каталоге и запусти снова",
                "длинный список не занимает весь экран",
            ),
            Case(
                fabricLog("Mod 'Sodium' (sodium) 0.8.14 requires version 1.21.11 of 'Minecraft' (minecraft), but only the wrong version is present: 1.21.4!"),
                "Моды сборки не подходят друг другу или этой версии игры — что именно, написано в логе",
                "несовместимость без недостающего мода не выдаётся за «не хватает мода»",
            ),
            Case(
                fabricLog("Mod 'X' (x) 1 requires any version of https://example.com/get.apk, which is missing!"),
                "Моды сборки не подходят друг другу или этой версии игры — что именно, написано в логе",
                "журнал пишут и моды: в подсказку попадает только то, что похоже на id мода, а не адрес из чужой строки",
            ),
            Case(
                "java.lang.NullPointerException: mod 'x' requires nothing, which is missing!",
                null,
                "без отказа загрузчика подсказки нет: обычный вылет не объясняется зависимостями",
            ),
        )
        for (case in cases) {
            assertEquals("подсказка: ${case.why}", case.hint, CrashHints.missingMods(case.log))
        }
    }
}
