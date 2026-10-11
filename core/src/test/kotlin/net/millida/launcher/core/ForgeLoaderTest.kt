package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.GameSession
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.launch.LaunchRequest
import net.millida.launcher.core.loader.ForgeFamilyInstaller
import net.millida.launcher.core.loader.ForgeInstallProfile
import net.millida.launcher.core.loader.ForgeLaunch
import net.millida.launcher.core.loader.ForgeVersions
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.loader.LoaderMirror
import net.millida.launcher.core.loader.NeoForgeVersions
import net.millida.launcher.core.loader.forgeBuilds
import net.millida.launcher.core.loader.forgeGameUnsupported
import net.millida.launcher.core.loader.forgeInstallers
import net.millida.launcher.core.loader.forgePromoted
import net.millida.launcher.core.loader.neoForgeBuilds
import net.millida.launcher.core.loader.neoForgePrerelease
import net.millida.launcher.core.mojang.Artifact
import net.millida.launcher.core.mojang.ResolvedLibrary
import net.millida.launcher.core.mojang.ResolvedVersion
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Forge и NeoForge ставятся одним кодом и различаются только списками сборок.
 * Здесь закреплено всё, на чём это ломалось: разбор номеров, выбор
 * рекомендованной сборки, запасной путь через наш сервер и граница между тем,
 * что телефон делает сам, и тем, что требует Java.
 */
class ForgeLoaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun layout(): GameLayout = GameLayout(folder.newFolder()).also { it.ensure() }

    @Test
    fun `номера сборок Forge чистятся от версии игры`() {
        val metadata = """
            {
              "1.20.1": ["1.20.1-47.0.0", "1.20.1-47.4.22"],
              "1.7.10": ["1.7.10-10.13.4.1566-1.7.10", "1.7.10-10.13.4.1614-1.7.10"]
            }
        """.trimIndent()

        data class Case(val version: String, val builds: List<String>, val why: String)

        val cases = listOf(
            Case("1.20.1", listOf("47.4.22", "47.0.0"), "список идёт от новых к старым и без префикса версии игры"),
            Case("1.7.10", listOf("10.13.4.1614", "10.13.4.1566"), "хвост «-<версия игры>» у старых сборок не номер"),
            Case("1.21.4", emptyList(), "у версии без сборок список пустой, а не с чужими номерами"),
        )
        for (case in cases) {
            assertEquals("${case.version}: ${case.why}", case.builds, forgeBuilds(metadata, case.version))
        }

        val installers = forgeInstallers("1.7.10", "10.13.4.1614")
        assertEquals("оба имени установщика пробуются: до 1.9.4 существует только второе", 2, installers.size)
        assertTrue(
            "первое имя — обычное forge-<mc>-<build>-installer.jar",
            installers[0].url.endsWith("/forge-1.7.10-10.13.4.1614-installer.jar"),
        )
        assertTrue(
            "второе имя повторяет версию игры в хвосте",
            installers[1].url.endsWith("/forge-1.7.10-10.13.4.1614-1.7.10-installer.jar"),
        )
        assertEquals(
            "папка профиля не зависит от того, каким именем скачался установщик",
            listOf("1.7.10-forge-10.13.4.1614", "1.7.10-forge-10.13.4.1614"),
            installers.map { it.versionId },
        )
    }

    @Test
    fun `сборки NeoForge берутся по ветке версии игры и сортируются числами`() {
        val list = """
            {"versions": [
              "20.4.1", "21.0.167", "21.1.9", "21.1.248", "21.1.100", "21.1.0-beta",
              "26.1.0.7", "26.1.2.9", "26.1.2.109", "26.2.0.9", "26.2.0.83", "26.2.0.12-beta", "26.3.0.1"
            ]}
        """.trimIndent()

        data class Case(val version: String, val builds: List<String>, val why: String)

        val cases = listOf(
            Case(
                "1.21.1",
                listOf("21.1.248", "21.1.100", "21.1.9", "21.1.0-beta"),
                "сортировка строкой поставила бы 21.1.9 выше 21.1.248",
            ),
            Case("1.21", listOf("21.0.167"), "версия игры без третьего числа означает ветку x.0.y"),
            Case(
                "26.2",
                listOf("26.2.0.83", "26.2.0.12-beta", "26.2.0.9"),
                "номера игры по годам: ветка 26.2.0.x, а не «отбросить 1.» и пустой список",
            ),
            Case("26.1.2", listOf("26.1.2.109", "26.1.2.9"), "патч-версия берёт свою ветку, а не соседнюю 26.1.0.x"),
            Case("26.1", listOf("26.1.0.7"), "26.1 не подхватывает сборки версии 26.1.2"),
            Case("26.4", emptyList(), "версия без сборок не берёт соседнюю ветку"),
        )
        for (case in cases) {
            assertEquals("${case.version}: ${case.why}", case.builds, neoForgeBuilds(list, case.version))
        }

        assertTrue("квалификатор в номере — нестабильная сборка", neoForgePrerelease("26.2.0.12-beta"))
        assertFalse("релизный номер состоит только из цифр", neoForgePrerelease("26.2.0.83"))
    }

    @Test
    fun `рекомендованная сборка Forge берётся из промо, NeoForge — первый релиз`() = runBlocking {
        val promotions = """
            {"promos": {
              "1.20.1-recommended": "47.2.0",
              "1.20.1-latest": "47.4.22",
              "1.21.4-latest": "54.1.0"
            }}
        """.trimIndent()

        assertEquals("рекомендованная важнее последней", "47.2.0", forgePromoted(promotions, "1.20.1"))
        assertEquals("без рекомендованной берётся последняя", "54.1.0", forgePromoted(promotions, "1.21.4"))
        assertEquals("чужой версии промо не достаётся", null, forgePromoted(promotions, "1.16.5"))

        server.enqueue(
            MockResponse().setBody("""{"versions": ["21.1.0-beta", "21.1.248", "21.1.100"]}""")
        )
        val neoforge = NeoForgeVersions(
            LoaderMirror(Downloader(attempts = 1)),
            versionsUrl = server.url("/neoforge").toString(),
        )
        assertEquals(
            "рекомендованная у NeoForge — самый свежий релиз, а не бета",
            "21.1.248",
            neoforge.recommended("1.21.1"),
        )

        server.enqueue(MockResponse().setBody("""{"versions": ["21.5.0-beta", "21.5.1-beta"]}"""))
        assertEquals(
            "ветка, где были только беты, всё равно должна ставиться",
            "21.5.1-beta",
            neoforge.recommended("1.21.5"),
        )
    }

    @Test
    fun `список Forge доезжает через наш сервер, когда прямой источник молчит`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"1.20.1": ["1.20.1-47.4.22"]}"""))

        val direct = server.url("/direct/maven-metadata.json").toString()
        val versions = ForgeVersions(
            LoaderMirror(Downloader(attempts = 1), mirrorBase = server.url("/launcher/dl").toString()),
            metadataUrl = direct,
        )
        val builds = versions.builds("1.20.1")

        assertEquals("список пришёл со второго адреса", listOf("47.4.22"), builds.map { it.version })
        assertTrue("сначала пробуется прямой адрес", server.takeRequest().path!!.startsWith("/direct/"))
        val mirrored = server.takeRequest().path!!
        assertTrue("запасной адрес — наш сервер", mirrored.startsWith("/launcher/dl?url="))
        assertTrue("адрес источника передаётся в параметре", mirrored.contains("maven-metadata.json"))
    }

    @Test
    fun `отсутствующее имя установщика не уводит в зеркало`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val mirror = LoaderMirror(Downloader(attempts = 1), mirrorBase = server.url("/launcher/dl").toString())
        val failure = runCatching { mirror.text(server.url("/direct/absent.json").toString()) }

        assertTrue("404 — это ответ «такого файла нет», а не обрыв связи", failure.isFailure)
        assertEquals("зеркало не должно получать второй запрос за тем же 404", 1, server.requestCount)
    }

    @Test
    fun `Forge до 1_13 явно не поддерживается и не трогает сеть`() = runBlocking {
        data class Case(val version: String, val unsupported: Boolean, val why: String)

        val cases = listOf(
            Case("1.7.10", true, "старый Forge требует Java 8, её нет в пакете рантайма"),
            Case("1.12.2", true, "последняя версия до перехода на процессоры установщика"),
            Case("1.13.2", false, "с 1.13 установщик работает через processors — это ставится"),
            Case("1.20.1", false, "обычная современная версия"),
            Case("26.1", false, "номера по годам не путаются со старыми 1.x"),
            Case("24w14a", false, "снимок без номера не объявляется старым"),
        )
        for (case in cases) {
            assertEquals("${case.version}: ${case.why}", case.unsupported, forgeGameUnsupported(case.version))
        }

        val layout = layout()
        val versions = ForgeVersions(
            LoaderMirror(Downloader(attempts = 1)),
            promotionsUrl = server.url("/promotions.json").toString(),
            mavenBase = server.url("/maven").toString(),
        )
        val failure = runCatching {
            ForgeFamilyInstaller(layout, Downloader(attempts = 1), versions).install("1.12.2", "")
        }
        val message = failure.exceptionOrNull()?.message.orEmpty()
        assertTrue("игроку прямо сказано, что версия не поддерживается: $message", message.contains("не поддерживается"))
        assertEquals("отказ до скачивания: установщик старого Forge не нужен", 0, server.requestCount)
    }

    @Test
    fun `современный Forge без среды Java не притворяется установленным`() = runBlocking {
        val layout = layout()
        val versions = ForgeVersions(
            LoaderMirror(Downloader(attempts = 1)),
            promotionsUrl = server.url("/promotions.json").toString(),
            mavenBase = server.url("/maven").toString(),
        )
        server.enqueue(MockResponse().setBody("""{"promos": {"1.20.1-recommended": "47.4.22"}}"""))
        serveInstaller(modernInstaller())

        val failure = runCatching {
            ForgeFamilyInstaller(layout, Downloader(attempts = 1), versions).install("1.20.1", "")
        }

        val message = failure.exceptionOrNull()?.message.orEmpty()
        assertTrue("установка обязана остановиться, а не сделать вид, что прошла", failure.isFailure)
        assertTrue("игроку сказано, чего не хватает: $message", message.contains("Java"))
        assertFalse(
            "недособранный профиль не должен выглядеть установленным",
            layout.versionJson("1.20.1-forge-47.4.22").exists(),
        )
    }

    @Test
    fun `шаги установщика читаются целиком, а неизвестный ключ не проходит дальше`() {
        val values = mapOf("MINECRAFT_JAR" to "/game/1.20.1.jar", "SIDE" to "client")
        val resolved = ForgeInstallProfile.resolveArguments(
            listOf("--input", "{MINECRAFT_JAR}", "--task", "MCP", "--lib", "[net.minecraft:client:1.20.1]"),
            values,
        ) { "/libs/" + it.replace(':', '/') }

        assertEquals(
            "значения подставляются, литералы остаются как есть",
            listOf("--input", "/game/1.20.1.jar", "--task", "MCP", "--lib", "/libs/net.minecraft/client/1.20.1"),
            resolved,
        )

        val unknown = runCatching {
            ForgeInstallProfile.resolveArguments(listOf("{MC_SRG}"), values) { it }
        }
        assertTrue(
            "неизвестный ключ нельзя пропускать литералом: следующий шаг будет искать несуществующий файл",
            unknown.isFailure,
        )
    }

    @Test
    fun `аргументы старого загрузчика вытесняют ванильные, а твикеры копятся`() {
        val merged = ForgeLaunch.mergeGameArguments(
            listOf(
                "--username", "\${auth_player_name}",
                "--version", "1.7.10",
                "--assetIndex", "1.7.10",
                "--username", "\${auth_player_name}",
                "--version", "1.7.10-Forge10.13.4.1614",
                "--assetIndex", "1.7.10",
                "--tweakClass", "cpw.mods.fml.common.launcher.FMLTweaker",
                "--tweakClass", "optifine.OptiFineForgeTweaker",
            )
        )

        assertEquals(
            "у флага остаётся значение загрузчика, а порядок — прежний",
            listOf(
                "--username", "\${auth_player_name}",
                "--version", "1.7.10-Forge10.13.4.1614",
                "--assetIndex", "1.7.10",
                "--tweakClass", "cpw.mods.fml.common.launcher.FMLTweaker",
                "--tweakClass", "optifine.OptiFineForgeTweaker",
            ),
            merged,
        )
        assertEquals("флаг --version встречается один раз", 1, merged.count { it == "--version" })
    }

    @Test
    fun `запуск Forge собирает классовый путь и подставляет папку библиотек`() {
        val layout = layout()
        val version = ResolvedVersion(
            id = "1.20.1-forge-47.4.22",
            jarVersionId = "1.20.1",
            mainClass = "cpw.mods.bootstraplauncher.BootstrapLauncher",
            assetsId = "5",
            assetIndex = null,
            clientJar = Artifact(path = "client.jar", sha1 = null, size = 0, url = ""),
            libraries = listOf(
                ResolvedLibrary(
                    name = "net.minecraftforge:fmlloader:1.20.1-47.4.22",
                    path = "net/minecraftforge/fmlloader/1.20.1-47.4.22/fmlloader-1.20.1-47.4.22.jar",
                    url = "",
                    sha1 = null,
                    size = 0,
                ),
                ResolvedLibrary(
                    name = "cpw.mods:bootstraplauncher:1.1.2",
                    path = "cpw/mods/bootstraplauncher/1.1.2/bootstraplauncher-1.1.2.jar",
                    url = "",
                    sha1 = null,
                    size = 0,
                ),
            ),
            gameArguments = listOf("--launchTarget", "forgeclient"),
            jvmArguments = listOf(
                "-DlibraryDirectory=\${library_directory}",
                "-p",
                "fmlcore.jar\${classpath_separator}javafmllanguage.jar",
                "-cp",
                "\${classpath}",
            ),
            javaMajor = 17,
            lwjglVersion = "3.3.1",
        )

        val spec = ForgeLaunch.build(
            layout,
            LaunchRequest(
                version = version,
                session = GameSession(nick = "Steve", uuid = "u", accessToken = ""),
                gameDir = folder.newFolder(),
                memoryMb = 2048,
                windowWidth = 1280,
                windowHeight = 720,
            ),
        )

        assertEquals(
            "порядок классового пути идёт из описания версии, клиент игры — последним",
            listOf(
                layout.library("net/minecraftforge/fmlloader/1.20.1-47.4.22/fmlloader-1.20.1-47.4.22.jar"),
                layout.library("cpw/mods/bootstraplauncher/1.1.2/bootstraplauncher-1.1.2.jar"),
                layout.versionJar("1.20.1"),
            ),
            spec.classpath,
        )
        assertTrue(
            "папка библиотек подставлена: без неё загрузчик не найдёт свои модули",
            spec.jvmArgs.contains("-DlibraryDirectory=${layout.libraries.absolutePath}"),
        )
        assertTrue(
            "разделитель путей подставлен",
            spec.jvmArgs.contains("fmlcore.jar:javafmllanguage.jar"),
        )
        assertFalse(
            "классовый путь передаёт сама среда запуска, флаг из описания версии сюда не идёт",
            spec.jvmArgs.contains("-cp"),
        )
        assertTrue("аргументы игры на месте", spec.gameArgs.containsAll(listOf("--launchTarget", "forgeclient")))
    }

    private fun serveInstaller(installer: ByteArray) {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(installer)
            .joinToString("") { "%02x".format(it) }
        server.enqueue(MockResponse().setBody(sha1))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(installer)))
    }

    private fun modernInstaller(): ByteArray = zip(
        "install_profile.json" to """
            {
              "spec": 1,
              "version": "1.20.1-forge-47.4.22",
              "json": "/version.json",
              "libraries": [],
              "data": {"BINPATCH": {"client": "/data/client.lzma", "server": "/data/server.lzma"}},
              "processors": [
                {
                  "sides": ["client"],
                  "jar": "net.minecraftforge:installertools:1.3.0",
                  "classpath": [],
                  "args": ["--task", "MCP", "--input", "{MINECRAFT_JAR}", "--patch", "{BINPATCH}"]
                }
              ]
            }
        """.trimIndent().toByteArray(),
        "version.json" to """
            {"id": "1.20.1-forge-47.4.22", "inheritsFrom": "1.20.1", "mainClass": "x", "libraries": []}
        """.trimIndent().toByteArray(),
        "data/client.lzma" to "patch".toByteArray(),
    )

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
