package net.millida.launcher.core

import net.millida.launcher.core.links.MillidaLink
import net.millida.launcher.core.links.MillidaLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Any web page can open a millida:// address. What is pinned here is that a
 * link either means exactly one of our actions or means nothing at all: a
 * hostile address is dropped whole, never repaired into something else.
 */
class MillidaLinksTest {

    private data class Case(val link: String, val expected: MillidaLink?, val why: String)

    @Test
    fun `адреса, которые понимает лаунчер`() {
        val cases = listOf(
            Case(
                "millida://install/modpacks/lost-souls",
                MillidaLink.Install("modpacks", "lost-souls"),
                "кнопка «Установить» сборки на сайте",
            ),
            Case(
                "millida://install/mods/sodium?version=1.21.4&version=1.21.4&loader=Fabric",
                MillidaLink.Install("mods", "sodium", versions = listOf("1.21.4"), loaders = listOf("fabric")),
                "повтор версии схлопывается, загрузчик приводится к нижнему регистру",
            ),
            Case(
                "millida://install/skins/jeb_?name=%D0%94%D0%B6%D0%B5%D0%B1&model=slim",
                MillidaLink.Install("skins", "jeb_", name = "Джеб", slim = true),
                "скин адресуется ником, подпись и модель рук приходят из ссылки",
            ),
            Case(
                "millida://modpack/freshcraft?name=FreshCraft",
                MillidaLink.Install("modpacks", "freshcraft", name = "FreshCraft", packOnly = true),
                "старая кнопка сборок сайта ведёт в готовую сборку каталога",
            ),
            Case(
                "millida://project/iris?type=modpack",
                MillidaLink.Project("iris", modpack = true),
                "проект Modrinth открывается карточкой",
            ),
            Case(
                "millida://project/iris",
                MillidaLink.Project("iris", modpack = false),
                "без типа это мод, как у лаунчера на ПК",
            ),
            Case(
                "millida://join/mc.example.net:25565?name=Example&licensed=1&version=1.20.1",
                MillidaLink.Join("mc.example.net:25565", "Example", licensed = true, versions = listOf("1.20.1")),
                "вход на сервер с сайта рейтинга",
            ),
            Case(
                "millida://join?addr=play.example.net",
                MillidaLink.Join("play.example.net", "play.example.net", licensed = false, versions = emptyList()),
                "старое написание адреса через параметр",
            ),
            Case("millida://pack/ab23-cd45", MillidaLink.Pack("AB23CD45"), "код сборки, набранный как попало"),
            Case("millida://invite/abc123", MillidaLink.Invite("ABC123"), "реферальная ссылка друга открывает ввод кода"),
            Case("millida://invite?code=ABC123", MillidaLink.Invite("ABC123"), "код приглашения через параметр, как на ПК"),
            Case("MILLIDA://INSTALL/modpacks/arcania", MillidaLink.Install("modpacks", "arcania"), "регистр схемы и действия не важен"),
        )
        cases.forEach { case ->
            assertEquals("«${case.link}»: ${case.why}", case.expected, MillidaLinks.parse(case.link))
        }
    }

    @Test
    fun `враждебные и кривые адреса отклоняются целиком`() {
        val cases = listOf(
            "millida://install/mods/../../etc" to "выход из пути запроса",
            "millida://install/mods/a%2F..%2Fb" to "слэш, спрятанный в кодировке, всё равно слэш",
            "millida://install/mods/sodium/extra" to "лишний сегмент — не наш адрес",
            "millida://install/viruses/sodium" to "раздела нет в списке сайта",
            "millida://install/mods/.hidden" to "slug не может начинаться с точки",
            "millida://install/mods/" + "a".repeat(129) to "slug длиннее того, что выдаёт каталог",
            "millida://install/mods/sod ium" to "пробел в адресе",
            "millida://install/mods/%E0%A4" to "битая кодировка",
            "millida://modpack/a/b" to "у старой кнопки ровно один slug",
            "millida://project/a.b" to "точка в slug проекта",
            "millida://join/evil.example/path" to "у адреса сервера нет пути",
            "millida://join/evil..example" to "пустая метка домена",
            "millida://join/example.net:99999" to "порта больше 65535 не бывает",
            "millida://join/example.net:0" to "нулевой порт",
            "millida://join/[::1]:25565" to "IPv6-литерал игре не передаём",
            "millida://join" to "без адреса заходить некуда",
            "millida://pack/../AB23CD45" to "код не чинится из мусора вокруг него",
            "millida://pack/AB23CD4O" to "O не входит в алфавит кодов",
            "millida://invite/AB-12" to "дефис не входит в код приглашения",
            "millida://invite/" + "A".repeat(17) to "код приглашения длиннее, чем выдаёт сервер",
            "millida://invite" to "без кода приглашать нечем",
            "millida://play/Survival" to "запуск сборки по ссылке на телефоне не поддерживается",
            "millida://user@install/mods/sodium" to "учётка в адресе — признак подмены",
            "millida://install:80/mods/sodium" to "порт у действия не бывает",
            "millida://install/mods/sodium#frag" to "фрагмент не наш",
            "millida:install/mods/sodium" to "непрозрачный адрес без действия",
            "https://millida.net/install/mods/sodium" to "чужая схема",
            "millida://install/mods/" + "a".repeat(3000) to "слишком длинная ссылка",
            "" to "пустая строка",
        )
        cases.forEach { (link, why) ->
            assertNull("«${link.take(80)}» обязан отклониться: $why", MillidaLinks.parse(link))
        }
    }

    @Test
    fun `подпись из ссылки не несёт разметку и переводы строк`() {
        assertEquals("a b i c", MillidaLinks.linkName("a\nb<i>c"))
        assertEquals("x y", MillidaLinks.linkName("x\u0000y"))
        assertEquals(60, MillidaLinks.linkName("я".repeat(200))?.length)
        assertNull("пустая подпись не показывается", MillidaLinks.linkName("  \n "))
    }
}
