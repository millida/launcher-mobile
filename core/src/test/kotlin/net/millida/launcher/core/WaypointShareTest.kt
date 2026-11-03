package net.millida.launcher.core

import net.millida.launcher.core.friends.SharedMark
import net.millida.launcher.core.friends.WaypointShare
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors WaypointShareTest in millida-mod and the desktop's waypointShare.test.ts:
 * the launcher must show a card for exactly the lines the game would save,
 * and plain text for everything else.
 */
class WaypointShareTest {

    private val p = WaypointShare.PREFIX
    private val ok = " «База» 120 64 -340 · nether · mc.example.net"

    @Test
    fun `метка читается ровно там, где её сохранит игра`() {
        data class Case(val text: String?, val card: Boolean, val why: String)
        val cases = listOf(
            Case(p + ok, true, "эталонная строка читается, поэтому каждый отказ ниже — из-за своей правки"),
            Case("смотри $p$ok", false, "маркер считается только в начале, как у приглашения"),
            Case(p, false, "маркер без метки"),
            Case("$p$ok и ещё", false, "слова в хвосте — не метка"),
            Case("$p$ok\n", false, "перенос строки в хвосте — тоже не метка"),
            Case("$p «База» 30000001 64 -340 · nether · mc.example.net", false, "за границей мира"),
            Case("$p «База» 30000000 64 -30000000 · nether · mc.example.net", true, "сама граница мира — внутри"),
            Case("$p «База» 120 5000 -340 · nether · mc.example.net", false, "выше высоты, которую хранит метка"),
            Case("$p «База» 120 -4096 -340 · nether · mc.example.net", true, "самая низкая высота метки"),
            Case("$p «База» 1e5 64 -340 · nether · mc.example.net", false, "только простые целые"),
            Case("$p «База» +120 64 -340 · nether · mc.example.net", false, "только простые целые"),
            Case("$p «База» 1.5 64 -340 · nether · mc.example.net", false, "только координаты блока"),
            Case("$p «База» 999999999 64 0 · nether · mc.example.net", false, "слишком много цифр"),
            Case("$p «» 120 64 -340 · nether · mc.example.net", false, "у метки есть имя"),
            Case("$p «§cКрасный» 1 2 3 · nether · mc.example.net", false, "код цвета перекрасил бы чат игры и подписи"),
            Case("$p «a\u0007b» 1 2 3 · nether · mc.example.net", false, "управляющие символы отклоняются"),
            Case("$p «a​b» 1 2 3 · nether · mc.example.net", false, "невидимые символы отклоняются"),
            Case("$p «a  b» 1 2 3 · nether · mc.example.net", false, "игра схлопывает двойные пробелы, имя бы не совпало"),
            Case("$p « База» 1 2 3 · nether · mc.example.net", false, "игра обрезает имя, обитое пробелом не совпало бы"),
            Case("$p «Точка 1» 1 2 3 · nether · mc.example.net", true, "один пробел внутри — часть имени"),
            Case("$p «12345678901234567890123456789012» 1 2 3 · nether · mc.example.net", true, "ровно самое длинное имя"),
            Case("$p «123456789012345678901234567890123» 1 2 3 · nether · mc.example.net", false, "длиннее, чем может быть имя"),
            Case("$p «База» 1 2 3 · Nether · mc.example.net", false, "измерения строчными"),
            Case("$p «База» 1 2 3 · evil · mc.example.net", false, "голое слово — только одно из трёх ванильных"),
            Case("$p «Портал» 5 70 5 · twilightforest:twilight_forest · mc.example.net", true, "модовое измерение — полным id"),
            Case("$p «База» 1 2 3 · nether · bad<host>", false, "адрес по тем же правилам, что ключ списка"),
            Case("$p «База» 1 2 3 · nether · mc.example.net:99999", false, "нет такого порта"),
            Case("$p «База» 1 2 3 · nether · mc.example.net:0", false, "нет такого порта"),
            Case("$p «База» 1 2 3 · nether · a..b", false, "пустая метка домена — не хост"),
            Case("$p «База» 1 2 3 · nether · [::1]:25565", false, "второе двоеточие — не порт"),
            Case("$p «База» 1 2 3 · nether", false, "без адреса некуда сохранять"),
            Case("$p «${"a".repeat(5000)}» 1 2 3 · nether · mc.example.net", false, "огромное имя отсекает ограниченный шаблон"),
            Case(null, false, "сообщение без текста"),
        )
        for (case in cases) {
            assertEquals(case.why, case.card, WaypointShare.parse(case.text) != null)
        }
    }

    @Test
    fun `карточка показывает то, что сохранит игра`() {
        assertEquals(
            "поля метки и полный id измерения",
            SharedMark("База", 120, 64, -341, "minecraft:the_nether", "mc.example.net"),
            WaypointShare.parse("$p «База» 120 64 -341 · nether · mc.example.net"),
        )
    }

    @Test
    fun `порт и написание адреса как у игры`() {
        assertEquals("другой порт едет с адресом", "play.example.net:25566", WaypointShare.parse("$p «Шахта» 0 -59 0 · overworld · play.example.net:25566")?.address)
        assertEquals("порт по умолчанию не пишется, как его набирает игрок", "play.example.net", WaypointShare.parse("$p «Шахта» 0 -59 0 · overworld · play.example.net:25565")?.address)
        assertEquals("адрес строчными", "mc.example.net", WaypointShare.parse("$p «Дом» 1 2 3 · end · MC.Example.NET")?.address)
        assertEquals("кириллический домен — пуникодом", "xn--e1afmkfd.xn--p1ai", WaypointShare.parse("$p «Дом» 1 2 3 · end · пример.рф")?.address)
    }

    @Test
    fun `координаты копируются так, как ждёт команда игры`() {
        val mark = WaypointShare.parse("$p «База» 120 -0 -341 · nether · mc.example.net")
        assertEquals("отрицательный ноль не должен попасть в буфер как «-0»", "120 0 -341", mark?.let(WaypointShare::coords))
    }

    @Test
    fun `ванильные измерения словами, модовые своим id`() {
        assertEquals("незер словом", "Незер", WaypointShare.dimensionLabel("minecraft:the_nether"))
        assertEquals("модовое как есть", "twilightforest:twilight_forest", WaypointShare.dimensionLabel("twilightforest:twilight_forest"))
        assertEquals("список чатов называет метку, а не сырую строку", "Метка «База»", WaypointShare.parse(p + ok)?.let(WaypointShare::preview))
    }
}
