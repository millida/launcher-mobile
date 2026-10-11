package net.millida.launcher.core

import net.millida.launcher.core.catalog.MillidaCard
import net.millida.launcher.core.catalog.MillidaCatalog
import net.millida.launcher.core.packs.PhoneCard
import net.millida.launcher.core.packs.PhoneFit
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.core.servers.OwnServers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lists on the phone show only what can start there. A false hide loses a pack
 * that plays, a missed one sends the player into «Только на ПК» after a tap.
 */
class PhoneListedTest {

    @After
    fun unwire() = PhoneFit.wire(legacyRuntime = { false }, deviceTotalMb = { 0 })

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `какие карточки список прячет`() {
        data class Case(val card: PhoneCard, val beta: Boolean, val totalMb: Int, val listed: Boolean, val why: String)

        val cases = listOf(
            Case(PhoneCard("fabric", "1.20.1"), false, 4_000, true, "обычная сборка на Fabric видна"),
            Case(PhoneCard("forge", "1.12.2"), false, 4_000, false, "старый Forge без беты не запустится и не показывается"),
            Case(PhoneCard("forge", "1.12.2"), true, 4_000, true, "с бетой старый Forge снова в списке"),
            Case(PhoneCard("forge", "1.7.10", ownLaunch = true), false, 4_000, false, "свой запуск под ПК без беты скрыт"),
            Case(PhoneCard("forge", "1.7.10", ownLaunch = true), true, 4_000, true, "с бетой свой запуск можно пробовать"),
            Case(PhoneCard("fabric", "1.20.1", ownLaunch = true, accessRequired = true), true, 4_000, false, "платный свой запуск скрыт даже с бетой"),
            Case(PhoneCard("neoforge", "1.21.1", sizeBytes = 50 * gb), false, 4_000, false, "клиент больше всей памяти телефона скрыт"),
            Case(PhoneCard("neoforge", "1.21.1", sizeBytes = 50 * gb), false, 0, true, "неизвестная память телефона не прячет"),
            Case(PhoneCard("", ""), false, 4_000, true, "неизвестные загрузчик и версия не прячут"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.listed, PhoneFit.listed(case.card, case.beta, case.totalMb))
        }
    }

    @Test
    fun `строка каталога Millida видна если подходит хоть один вариант`() {
        data class Case(val card: MillidaCard, val listed: Boolean, val why: String)

        val cases = listOf(
            Case(MillidaCard("a", "A"), true, "строка без версий и загрузчиков неизвестна и остаётся"),
            Case(MillidaCard("b", "B", versions = listOf("1.12.2"), loaders = listOf("forge")), false, "только старый Forge скрыт"),
            Case(MillidaCard("c", "C", versions = listOf("1.12.2", "1.20.1"), loaders = listOf("forge")), true, "есть версия, которая идёт на телефоне"),
            Case(MillidaCard("d", "D", versions = listOf("1.12.2"), loaders = listOf("forge", "fabric")), true, "есть загрузчик, который идёт на телефоне"),
            Case(MillidaCard("e", "E", versions = listOf("1.20.1"), loaders = listOf("fabric"), premium = true, ownLaunch = true), false, "платный свой запуск скрыт"),
            Case(MillidaCard("f", "F", versions = listOf("1.20.1"), loaders = listOf("fabric"), premium = true), true, "платная обычная сборка видна"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.listed, MillidaCatalog.phoneListed(case.card, legacyRuntime = false, deviceTotalMb = 4_000))
        }
    }

    @Test
    fun `витрина переносит свой запуск в строку ленты и лента её прячет`() {
        PhoneFit.wire(legacyRuntime = { false }, deviceTotalMb = { 4_000 })
        val page = listOf(
            MillidaCard("arcania", "Arcania", versions = listOf("1.20.1"), loaders = listOf("fabric")),
            MillidaCard("plain", "Plain", versions = listOf("1.20.1"), loaders = listOf("fabric")),
        )
        val premium = listOf(MillidaCard("arcania", "Arcania", premium = true, ownLaunch = true))
        val pinned = MillidaCatalog.pinShowcase(page, premium, "")
        assertTrue("строка ленты получает свой запуск из витрины, иначе телефон её не узнает", pinned.first { it.slug == "arcania" }.ownLaunch)
        assertEquals("в ленте остаётся только то, что идёт на телефоне", listOf("plain"), MillidaCatalog.forPhone(pinned).map { it.slug })
    }

    @Test
    fun `сервер со своей сборкой скрыт если сборка не идёт на телефоне`() {
        data class Case(val beta: Boolean, val shown: List<String>, val why: String)

        val rows = listOf(RatedServer(name = "OneBlock", slug = OwnServers.ONEBLOCK.slug), RatedServer(name = "Other", slug = "other"))
        val cases = listOf(
            Case(false, listOf("other"), "без беты OneBlock не запустится, его карточка ничего не даёт"),
            Case(true, listOf(OwnServers.ONEBLOCK.slug, "other"), "с бетой OneBlock снова предлагается"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.shown, OwnServers.forPhone(rows, legacyRuntime = case.beta, deviceTotalMb = 4_000).map { it.slug })
        }
        val byAddress = listOf(RatedServer(name = "x", ip = OwnServers.ONEBLOCK.address))
        assertTrue("сервер узнаётся и по адресу", OwnServers.forPhone(byAddress, legacyRuntime = false, deviceTotalMb = 4_000).isEmpty())
    }
}
