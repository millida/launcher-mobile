package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.catalog.ModpackLoader
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.packs.PackAccess
import net.millida.launcher.core.packs.PackAccessApi
import net.millida.launcher.core.packs.PackAccessDenied
import net.millida.launcher.core.packs.PackAccessLost
import net.millida.launcher.core.packs.PackAccessWatch
import net.millida.launcher.core.packs.PackKeep
import net.millida.launcher.core.packs.PackLaunchGate
import net.millida.launcher.core.packs.PackLaunchToken
import net.millida.launcher.core.packs.PackOrigin
import net.millida.launcher.core.packs.PackOriginStore
import net.millida.launcher.core.packs.PackSource
import net.millida.launcher.core.packs.PhoneFit
import net.millida.launcher.core.servers.OwnServers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * A paid pack is taken away only by an explicit word of our API, and when it
 * is, the player's worlds leave with them. Losing either way is expensive: a
 * false refusal locks out a paying player, a missed one keeps paid content.
 */
class PackAccessTest {

    private lateinit var root: File

    @Before
    fun start() {
        root = File(System.getProperty("java.io.tmpdir"), "millida-pack-access-test-" + System.nanoTime())
        root.mkdirs()
    }

    @After
    fun stop() {
        root.deleteRecursively()
    }

    private fun json(text: String) = MojangJson.parseToJsonElement(text).jsonObject

    @Test
    fun `только явный отказ API забирает сборку`() {
        val lostDefault = PackAccess.Lost("У этого аккаунта больше нет доступа к сборке")
        val cases = listOf(
            Triple("""{"access":"allow","recheckInSec":900}""", PackAccess.Allowed(900), "доступ есть — играем дальше"),
            Triple("""{"access":"allow","recheckInSec":1}""", PackAccess.Allowed(PackAccess.RECHECK_MIN_SEC), "сервер не заставит долбить себя раз в секунду"),
            Triple("""{"access":"allow","recheckInSec":999999}""", PackAccess.Allowed(PackAccess.RECHECK_MAX_SEC), "и не отложит проверку на неделю"),
            Triple("""{"access":"allow"}""", PackAccess.Allowed(PackAccess.RECHECK_DEFAULT_SEC), "старый сервер без интервала"),
            Triple("""{"access":"revoked","message":"Ключ отозван"}""", PackAccess.Lost("Ключ отозван"), "продавец отозвал ключ"),
            Triple("""{"access":"expired","message":""}""", lostDefault, "срок кончился, текст пустой — свой"),
            Triple("""{"access":"no-access"}""", lostDefault, "доступ сняли вручную"),
            Triple("""{"access":"not-paid"}""", lostDefault, "не оплачено"),
            Triple("""{"access":"need-login"}""", null, "вылет из аккаунта — не повод стирать купленное"),
            Triple("""{"access":"maybe"}""", null, "незнакомое слово — не знаем, ничего не трогаем"),
            Triple("""{"message":"Forbidden"}""", null, "ответ щита без вердикта"),
        )
        cases.forEach { (body, expected, why) -> assertEquals(why, expected, PackAccess.from(json(body))) }
        assertEquals("пустой ответ — не вердикт", null, PackAccess.from(null))
    }

    @Test
    fun `ключ запуска проходит тот же фильтр, что и флаги сборки`() {
        val args = PackLaunchToken.args(json("""{"jvmArgs":["-Dlauncher.signature=SIG","-javaagent:/x.jar","-Dowo.handshake.disable=true"]}"""))
        assertEquals("агент из ответа не доезжает, подпись доезжает", listOf("-Dlauncher.signature=SIG", "-Dowo.handshake.disable=true"), args)
        try {
            PackLaunchToken.args(json("""{"jvmArgs":["-javaagent:/x.jar"]}"""))
            fail("без подписи сборка всё равно не стартует — честнее сказать сразу")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("Играть"))
        }
    }

    @Test
    fun `проверка во время игры идёт, пока жива игра, и останавливается на отказе`() = runBlocking {
        val slept = ArrayList<Long>()
        val answers = ArrayDeque(listOf<PackAccess?>(PackAccess.Allowed(600), null, PackAccess.Lost("Доступ отозван")))
        val reason = PackAccessWatch.watch("arcania", running = { true }, check = { answers.removeFirst() }, sleep = { slept += it })
        assertEquals("отказ возвращается, чтобы лаунчер закрыл игру и убрал сборку", "Доступ отозван", reason)
        assertEquals(
            "первая проверка — через интервал по умолчанию, потом через данный сервером, при незнании — повтор через 5 минут",
            listOf(PackAccess.RECHECK_DEFAULT_SEC * 1000, 600_000L, PackAccess.RECHECK_RETRY_SEC * 1000),
            slept,
        )
        val ended = PackAccessWatch.watch("arcania", running = { false }, check = { fail("игра закрыта — спрашивать незачем"); null }, sleep = {})
        assertEquals("игра закончилась — следить больше не за чем", null, ended)
    }

    private class FakeApi(
        var signedIn: Boolean = true,
        var required: Boolean = false,
        var verdict: PackAccess? = null,
        var token: () -> List<String> = { listOf("-Dlauncher.signature=SIG") },
        var accessFails: Boolean = false,
    ) : PackAccessApi {
        override fun signedIn() = signedIn
        override suspend fun accessRequired(slug: String) = required
        override suspend fun access(slug: String): PackAccess? = if (accessFails) throw IOException("нет сети") else verdict
        override suspend fun launchToken(slug: String) = token()
    }

    private fun build(name: String, source: PackSource, launch: String?): File {
        val dir = File(root, name).also { it.mkdirs() }
        PackOriginStore.write(dir, PackOrigin(source = source.id, id = "arcania"))
        if (launch != null) File(dir, PhoneFit.PACK_LAUNCH_FILE).writeText(launch)
        return dir
    }

    private val tokenSpec = """{"mainClass":"a.B","classPath":["libraries"],"javaMajor":17,"needsToken":true}"""

    @Test
    fun `ворота перед запуском`() = runBlocking {
        val signed = build("signed", PackSource.MILLIDA_CATALOG, tokenSpec)
        val admitted = PackLaunchGate(FakeApi()).admit(signed)
        assertEquals("подпись выдаётся на этот запуск и идёт в игру", listOf("-Dlauncher.signature=SIG"), admitted.tokenArgs)
        assertTrue("сборку по подписи сторожат во время игры", admitted.watch)

        try {
            PackLaunchGate(FakeApi(token = { throw IOException("403") }, verdict = PackAccess.Lost("Отозван"))).admit(signed)
            fail("ключ не выдан и API сказал «доступа нет» — сборку забирают")
        } catch (lost: PackAccessLost) {
            assertEquals("arcania", lost.slug)
        }

        try {
            PackLaunchGate(FakeApi(token = { throw IOException("сеть") }, accessFails = true)).admit(signed)
            fail("без ключа сборка не стартует")
        } catch (lost: PackAccessLost) {
            fail("нет сети — не повод стирать купленную сборку")
        } catch (expected: IOException) {
            assertEquals("сеть", expected.message)
        }

        try {
            PackLaunchGate(FakeApi(token = { throw Unauthorized() })).admit(signed)
            fail("без входа подписи не будет")
        } catch (expected: Unauthorized) {
            assertTrue("ошибка говорит, что делать", expected.message.orEmpty().contains("войди"))
        }

        val plain = build("plain", PackSource.MILLIDA_CATALOG, null)
        assertEquals("обычная сборка каталога без вердикта стартует", "arcania", PackLaunchGate(FakeApi()).admit(plain).slug)
        assertEquals(
            "нет сети — обычная сборка стартует",
            "arcania",
            PackLaunchGate(FakeApi(accessFails = true)).admit(plain).slug,
        )
        try {
            PackLaunchGate(FakeApi(verdict = PackAccess.Lost("Подписка кончилась"))).admit(plain)
            fail("явный отказ закрывает подписную сборку")
        } catch (denied: PackAccessDenied) {
            assertEquals("Подписка кончилась", denied.message)
        }
        try {
            PackLaunchGate(FakeApi(signedIn = false, required = true)).admit(plain)
            fail("гость не открывает подписную сборку")
        } catch (denied: PackAccessDenied) {
            assertTrue(denied.message.orEmpty().contains("Войди"))
        }

        val foreign = build("foreign", PackSource.MODRINTH, tokenSpec)
        val free = PackLaunchGate(FakeApi(verdict = PackAccess.Lost("x"), token = { fail("чужой сборке подпись не нужна"); emptyList() })).admit(foreign)
        assertEquals("сборка не из каталога в API не ходит", "", free.slug)
    }

    private fun touch(dir: File, path: String) = File(dir, path).also { it.parentFile.mkdirs(); it.writeText("x") }

    @Test
    fun `уходят только файлы игрока и возвращаются при новой установке`() {
        val pack = File(root, "pack").also { it.mkdirs() }
        listOf("libraries/a.jar", "mods/paid.jar", "minecraft.jar", "millida-pack-launch.json", "resourcepacks/Arcania.zip", "config/a.json")
            .forEach { touch(pack, it) }
        PackKeep.recordShipped(pack, PackKeep.shipped(pack))
        listOf("saves/Мир/level.dat", "screenshots/1.png", "resourcepacks/Faithful.zip", "options.txt", "journeymap/map.dat")
            .forEach { touch(pack, it) }

        assertEquals(
            "миры, скрины, свои ресурс-паки, настройки и то, что добавил игрок, — его; платное содержимое и файлы лаунчера — нет",
            listOf("journeymap", "options.txt", "resourcepacks/Faithful.zip", "saves", "screenshots"),
            PackKeep.playerEntries(pack).sorted(),
        )

        val keep = PackKeep.keepDir(root, "arcania")
        assertEquals(5, PackKeep.stash(pack, keep))
        assertFalse("мир ушёл из папки сборки", File(pack, "saves").exists())
        assertTrue("платный мод остался на месте до удаления сборки", File(pack, "mods/paid.jar").isFile)

        val fresh = File(root, "fresh").also { touch(it, "resourcepacks/Arcania.zip"); touch(it, "options.txt") }
        val left = PackKeep.restore(fresh, keep)
        assertTrue("мир вернулся", File(fresh, "saves/Мир/level.dat").isFile)
        assertTrue("свой ресурс-пак вернулся рядом с паком сборки", File(fresh, "resourcepacks/Faithful.zip").isFile)
        assertEquals("при совпадении имени побеждает новая версия, копия игрока остаётся в хранении", listOf("options.txt"), left)
    }

    @Test
    fun `слаг не уводит папку хранения`() {
        listOf("../x", "/abs", "", "Arcania").forEach { slug ->
            try {
                PackKeep.keepDir(root, slug)
                fail("«$slug» назвал бы папку, куда переносятся файлы игрока")
            } catch (expected: IOException) {
                assertTrue(true)
            }
        }
    }

    @Test
    fun `свой сервер узнаётся по слагу и по адресу`() {
        val cases = listOf(
            Triple("oneblock-7", "", true) to "карточка рейтинга прячет адрес — узнаём по слагу",
            Triple("", "proxy-1.metalabsmc.net:25567", true) to "набранный адрес",
            Triple("", "PROXY-1.MetalabsMC.net.:25567", true) to "регистр и точка в конце — тот же сервер",
            Triple(null, "proxy-1.metalabsmc.net", false) to "другой порт — другой сервер",
            Triple("oneblock-8", "mc.example.com", false) to "чужой сервер",
            Triple(null, null, false) to "без данных — не свой",
        )
        cases.forEach { (case, why) ->
            assertEquals(why, case.third, OwnServers.of(case.first, case.second) != null)
        }
        assertEquals("сервер ставит свою сборку каталога", "oneblock-metalabs", OwnServers.of("oneblock-7", null)?.pack)
        assertEquals("example.com", OwnServers.canonical("Example.com:25565"))
    }

    @Test
    fun `какие сборки каталога видны на телефоне`() {
        val cases = listOf(
            Triple(listOf("forge"), listOf("1.20.1"), true) to "Forge 1.13+ ставится с 631b787",
            Triple(listOf("forge"), listOf("1.12.2"), false) to "старый Forge отсекается до скачивания",
            Triple(listOf("forge"), listOf("1.12.2", "1.16.5"), true) to "хотя бы одна версия подходит",
            Triple(listOf("NeoForge"), listOf("1.21.1"), true) to "NeoForge ставится, регистр не мешает",
            Triple(listOf("fabric", "forge"), listOf("1.7.10"), true) to "есть вариант на Fabric",
            Triple(listOf("quilt"), emptyList<String>(), true) to "без версий решает архив",
            Triple(emptyList<String>(), listOf("1.12.2"), true) to "загрузчик не назван — решает архив",
            Triple(listOf("liteloader"), listOf("1.12.2"), false) to "незнакомый загрузчик телефон не поставит",
        )
        cases.forEach { (case, why) ->
            assertEquals(why, case.third, ModpackLoader.phoneFits(case.first, case.second))
        }
    }
}
