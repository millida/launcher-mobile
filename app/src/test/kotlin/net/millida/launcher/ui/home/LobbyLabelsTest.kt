package net.millida.launcher.ui.home

import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.profile.BuildMeta
import net.millida.launcher.core.profile.Profile
import org.junit.Assert.assertEquals
import org.junit.Test

class LobbyLabelsTest {

    private fun build(id: String, playtime: Long = 0, loader: LoaderKind = LoaderKind.VANILLA, version: String = "1.21.4") =
        Profile(id = id, name = id, gameVersion = version, loader = loader, playtimeSec = playtime)

    @Test
    fun `наигранное время на карточке сборки`() {
        data class Case(val seconds: Long, val hours: String?, val played: String, val why: String)
        listOf(
            Case(0, null, "меньше минуты", "новая сборка не хвастается нулём часов"),
            Case(59, null, "меньше минуты", "секунды не показываются"),
            Case(60, null, "1 мин", "первая минута"),
            Case(3599, null, "59 мин", "до часа плашка часов не нужна"),
            Case(3600, "1 ч", "1 ч 0 мин", "ровный час"),
            Case(3660, "1 ч", "1 ч 1 мин", "час с минутой"),
            Case(7300, "2 ч", "2 ч 1 мин", "часы округляются вниз"),
        ).forEach { case ->
            assertEquals("${case.seconds}: ${case.why}", case.hours, hoursLabel(case.seconds))
            assertEquals("${case.seconds}: ${case.why}", case.played, playedLabel(case.seconds))
        }
    }

    @Test
    fun `время игры берётся из большего источника`() {
        val profile = build("a", playtime = 100)

        assertEquals("журнал лобби новее профиля", 500L, playedSeconds(profile, LobbyState(playtime = mapOf("a" to 500L))))
        assertEquals("профиль не теряет время, если журнал отстал", 100L, playedSeconds(profile, LobbyState(playtime = mapOf("a" to 50L))))
        assertEquals("без записи в журнале остаётся время профиля", 100L, playedSeconds(profile, LobbyState()))
    }

    @Test
    fun `строка загрузчика сборки`() {
        assertEquals("Fabric · 1.20.1", loaderLine(build("f", loader = LoaderKind.FABRIC, version = "1.20.1")))
        assertEquals("Ваниль · 1.21.4", loaderLine(build("v")))
    }

    @Test
    fun `число полок со сборками на главной`() {
        val profiles = listOf(build("a"), build("b"), build("c"), build("d"))
        data class Case(val profiles: List<Profile>, val metas: Map<String, BuildMeta>, val expected: Int, val why: String)
        listOf(
            Case(emptyList(), emptyMap(), 0, "без сборок полок нет совсем"),
            Case(profiles, emptyMap(), 1, "без групп одна полка «Мои сборки»"),
            Case(
                profiles,
                mapOf("a" to BuildMeta(group = "PvP"), "b" to BuildMeta(group = " pvp "), "c" to BuildMeta(group = "Tech")),
                3,
                "группы сравниваются без регистра и пробелов: PvP и pvp — одна полка",
            ),
            Case(profiles, mapOf("a" to BuildMeta(group = "  ")), 1, "пустая группа не создаёт полку"),
        ).forEach { case ->
            assertEquals(
                "${case.why} — от числа зависит прокрутка тура по лобби",
                case.expected,
                buildShelfCount(case.profiles, LobbyState(metas = case.metas)),
            )
        }
    }

    @Test
    fun `тон карточки сборки стабилен`() {
        assertEquals("цвет сборки не должен прыгать между запусками", buildTone("my-build"), buildTone("my-build"))
        assertEquals("служебная строка с hashCode Int.MIN_VALUE", Int.MIN_VALUE, "polygenelubricants".hashCode())
        listOf("", "polygenelubricants", "сборка").forEach { id ->
            assertEquals("id «$id» с отрицательным хешем не должен давать отрицательный индекс палитры", buildTone(id), buildTone(id))
        }
    }
}
