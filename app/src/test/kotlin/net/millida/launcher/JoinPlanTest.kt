package net.millida.launcher

import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.profile.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinPlanTest {

    private fun build(id: String, version: String, loader: LoaderKind = LoaderKind.VANILLA) =
        Profile(id = id, name = id, gameVersion = version, loader = loader)

    @Test
    fun `мажорная линия версии`() {
        data class Case(val input: String, val expected: String, val why: String)
        listOf(
            Case("1.21.4", "1.21", "рейтинг называет сервер линией, сборка несёт полный релиз"),
            Case("1.21", "1.21", "линия остаётся линией"),
            Case(" 1.20.1 ", "1.20", "пробелы из ввода владельца сервера не ломают сравнение"),
            Case("1", "1", "короткая строка возвращается как есть"),
        ).forEach { case ->
            assertEquals("${case.input}: ${case.why}", case.expected, versionMajor(case.input))
        }
    }

    @Test
    fun `подходит ли сборка серверу`() {
        data class Case(val build: String, val wanted: List<String>, val expected: Boolean, val why: String)
        listOf(
            Case("1.21.4", emptyList(), true, "сервер без версий не возражает ни против чего"),
            Case("", listOf("1.21"), false, "сборка без версии не может подойти серверу с версией"),
            Case("1.21.4", listOf("1.21"), true, "патч входит в мажорную линию сервера"),
            Case("1.21", listOf("1.21.4"), true, "линия сборки совпадает с линией сервера"),
            Case("1.20.1", listOf("1.21", "1.19"), false, "чужая линия не подходит"),
            Case("1.20.1", listOf("1.21", "1.20.1"), true, "любая из версий сервера даёт допуск"),
        ).forEach { case ->
            assertEquals("${case.build} vs ${case.wanted}: ${case.why}", case.expected, versionFits(case.build, case.wanted))
        }
    }

    @Test
    fun `известные версии берутся только из числовых релизов вместе с линиями`() {
        val known = knownVersions(listOf("1.21.4", "24w14a", " 1.8.9 ", "1.20.1-rc1"))

        assertEquals(
            "снапшоты и пре-релизы не числовые и не должны считаться релизом; линии добавляются для мажорных версий рейтинга",
            setOf("1.21.4", "1.21", "1.8.9", "1.8"),
            known,
        )
    }

    @Test
    fun `версии сервера очищаются от опечаток владельца`() {
        val known = setOf("1.21", "1.21.4", "1.20.1", "1.20")
        data class Case(val reported: List<String>, val known: Set<String>, val expected: List<String>, val why: String)
        listOf(
            Case(listOf("1.21", "1.25", "abc", "1.21"), known, listOf("1.21"), "несуществующая 1.25, мусор и повтор выкинуты"),
            Case(listOf("1.21", "1.25"), emptySet(), listOf("1.21", "1.25"), "пока список Mojang неизвестен, каждая числовая версия сохраняется"),
            Case(listOf(" 1.20.1 "), known, listOf("1.20.1"), "пробелы вокруг версии не делают её опечаткой"),
            Case(emptyList(), known, emptyList(), "пустой список остаётся пустым"),
        ).forEach { case ->
            assertEquals("${case.reported}: ${case.why}", case.expected, serverVersions(case.reported, case.known))
        }
    }

    @Test
    fun `версия из ответа пинга`() {
        val known = setOf("1.20.1", "1.20", "1.16.5", "1.16")
        data class Case(val reported: String, val known: Set<String>, val expected: List<String>, val why: String)
        listOf(
            Case("Paper 1.20.1", known, listOf("1.20.1"), "имя ядра перед версией не мешает её найти"),
            Case("1.16.5", known, listOf("1.16.5"), "голая версия берётся как есть"),
            Case("1.8.x-1.21.x", known, emptyList(), "диапазон прокси пускает любой клиент — возражать нечему"),
            Case("Velocity 1.7.2-1.21.4", known, emptyList(), "диапазон без подстановок тоже не сужает выбор"),
            Case("", known, emptyList(), "пустой ответ ничего не требует"),
            Case("BungeeCord", known, emptyList(), "текст без версии ничего не требует"),
            Case("Paper 1.29", known, emptyList(), "выдуманная версия не превращается в требование"),
            Case("Paper 1.29", emptySet(), listOf("1.29"), "пока список релизов неизвестен, версия пинга сохраняется"),
        ).forEach { case ->
            assertEquals("«${case.reported}»: ${case.why}", case.expected, pingVersions(case.reported, case.known))
        }
    }

    @Test
    fun `на сервер заходят только чистые сборки`() {
        val builds = listOf(
            build("v", "1.21.4", LoaderKind.VANILLA),
            build("f", "1.21.4", LoaderKind.FABRIC),
            build("q", "1.21.4", LoaderKind.QUILT),
            build("fo", "1.20.1", LoaderKind.FORGE),
            build("n", "1.21.1", LoaderKind.NEOFORGE),
        )

        assertEquals(
            "Forge, NeoForge и Quilt — модпаки, которых сервер не ждёт; ваниль и Fabric заходят",
            listOf("v", "f"),
            joinableBuilds(builds).map { it.id },
        )
    }

    @Test
    fun `выбор сборки под сервер предпочитает точную версию`() {
        val builds = listOf(build("a", "1.21.1"), build("b", "1.21.4"), build("c", "1.20.1"))

        assertNull("без версий сервера выбирать нечего", pickBuildForServer(builds, emptyList()))
        assertEquals(
            "точное совпадение важнее совпадения линии, даже если сборка дальше в списке",
            "b",
            pickBuildForServer(builds, listOf("1.21.4"))?.id,
        )
        assertEquals("без точного совпадения берётся первая сборка той же линии", "a", pickBuildForServer(builds, listOf("1.21"))?.id)
        assertNull("сборки чужих линий не подходят", pickBuildForServer(builds, listOf("1.19")))
    }

    @Test
    fun `выбор версии для новой сборки под сервер`() {
        val available = listOf("1.21.4", "1.21.3", "1.20.1", "1.16.5")
        data class Case(val wanted: List<String>, val expected: String, val why: String)
        listOf(
            Case(listOf("1.21"), "1.21.4", "линия разрешается в свежий патч, список идёт от новых к старым"),
            Case(listOf("1.20.1"), "1.20.1", "точная версия берётся как есть"),
            Case(listOf("1.21.2", "1.20.1"), "1.20.1", "точное совпадение любой версии сервера важнее близкого патча первой"),
            Case(listOf("1.21.2"), "1.21.4", "без точного совпадения берётся свежий патч той же линии"),
            Case(listOf("1.19"), "", "чужой линии в списке нет — пустая строка, а не случайная версия"),
            Case(emptyList(), "", "сервер без версий ничего не выбирает"),
        ).forEach { case ->
            assertEquals("${case.wanted}: ${case.why}", case.expected, pickVersionForServer(available, case.wanted))
        }
    }

    @Test
    fun `план входа на сервер`() {
        val old = build("old", "1.20.1")
        val fresh = build("fresh", "1.21.4")
        val builds = listOf(old, fresh)

        assertEquals(
            "без сборок предлагается создать первую версию сервера",
            JoinPlan.Create("1.21.4"),
            joinPlan(emptyList(), null, listOf("1.21.4", "1.20.1")),
        )
        assertEquals("без сборок и версий создаётся сборка без версии", JoinPlan.Create(""), joinPlan(emptyList(), null, emptyList()))
        assertEquals("сервер не назвал версию — играем выбранной, но предупреждаем", JoinPlan.Unknown(old), joinPlan(builds, "old", emptyList()))
        assertEquals("выбранная сборка подходит — сразу запуск", JoinPlan.Launch(fresh), joinPlan(builds, "fresh", listOf("1.21")))
        assertEquals("подходит другая сборка — предлагается переключиться", JoinPlan.Switch(fresh), joinPlan(builds, "old", listOf("1.21.4")))
        assertEquals("ни одна не подходит — несовпадение с выбранной", JoinPlan.Mismatch(old), joinPlan(builds, "old", listOf("1.8.9")))
        assertEquals(
            "неизвестный выбор не роняет план: берётся первая сборка",
            JoinPlan.Launch(old),
            joinPlan(builds, "gone", listOf("1.20")),
        )
        assertTrue("план всегда опирается на одну из сборок игрока", joinPlan(builds, null, listOf("1.8")) is JoinPlan.Mismatch)
    }
}
