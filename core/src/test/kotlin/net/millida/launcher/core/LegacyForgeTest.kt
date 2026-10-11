package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.account.OfflineAuth
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.launch.LaunchRequest
import net.millida.launcher.core.launch.Log4jGuard
import net.millida.launcher.core.loader.ForgeFamilyInstaller
import net.millida.launcher.core.loader.ForgeLaunch
import net.millida.launcher.core.loader.ForgeSplash
import net.millida.launcher.core.loader.ForgeVersions
import net.millida.launcher.core.loader.LegacyForgeProfile
import net.millida.launcher.core.loader.LoaderMirror
import net.millida.launcher.core.mojang.Artifact
import net.millida.launcher.core.mojang.LoggingConfig
import net.millida.launcher.core.mojang.MOJANG_LIBRARIES_URL
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.mojang.ResolvedLibrary
import net.millida.launcher.core.mojang.ResolvedVersion
import net.millida.launcher.core.mojang.VersionResolver
import net.millida.launcher.core.runtime.JavaRequirement
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Forge до 1.13 (OneBlock на 1.7.10) ставится из установщика старого формата
 * и запускается через LaunchWrapper на Java 8. Закреплено: что берётся из
 * установщика, с какими суммами, во что превращается описание версии и какая
 * строка запуска из него получается.
 */
class LegacyForgeTest {

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

    private val forgeCoordinate = "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10"
    private val scalaPath = "org/scala-lang/scala-library/2.11.1/scala-library-2.11.1.jar"
    private val scalaBytes = "scala".toByteArray()
    private val universalBytes = "universal".toByteArray()

    private fun profile(): String = """
        {
          "install": {
            "path": "$forgeCoordinate",
            "filePath": "forge-1.7.10-10.13.4.1614-1.7.10-universal.jar",
            "minecraft": "1.7.10"
          },
          "versionInfo": {
            "id": "1.7.10-Forge10.13.4.1614-1.7.10",
            "inheritsFrom": "1.7.10",
            "jar": "1.7.10",
            "type": "release",
            "mainClass": "net.minecraft.launchwrapper.Launch",
            "minecraftArguments": "--username ${'$'}{auth_player_name} --version ${'$'}{version_name} --gameDir ${'$'}{game_directory} --assetsDir ${'$'}{assets_root} --assetIndex ${'$'}{assets_index_name} --uuid ${'$'}{auth_uuid} --accessToken ${'$'}{auth_access_token} --userProperties ${'$'}{user_properties} --userType ${'$'}{user_type} --tweakClass cpw.mods.fml.common.launcher.FMLTweaker",
            "libraries": [
              {"name": "$forgeCoordinate", "url": "http://files.minecraftforge.net/maven/"},
              {"name": "org.scala-lang:scala-library:2.11.1", "url": "http://files.minecraftforge.net/maven/",
               "checksums": ["${"0".repeat(40)}", "${sha1(scalaBytes)}"], "serverreq": true, "clientreq": true},
              {"name": "org.lwjgl.lwjgl:lwjgl:2.9.1"}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `адреса старых профилей переводятся на https и живой Forge maven`() {
        data class Case(val declared: String?, val expected: String, val why: String)

        val cases = listOf(
            Case(null, MOJANG_LIBRARIES_URL, "без адреса библиотека лежит у Mojang"),
            Case("", MOJANG_LIBRARIES_URL, "пустой адрес — тоже Mojang"),
            Case("http://files.minecraftforge.net/maven/", "https://maven.minecraftforge.net/", "старый хост Forge закрыт, http не годится для сумм"),
            Case("https://maven.minecraftforge.net", "https://maven.minecraftforge.net/", "косая черта в конце нужна для склейки пути"),
            Case("http://example.org/repo/", "https://example.org/repo/", "любой http поднимается до https"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, LegacyForgeProfile.repository(case.declared))
        }
    }

    @Test
    fun `список библиотек старого профиля`() {
        val info = MojangJson.parseToJsonElement(profile()).jsonObject["versionInfo"]!!.jsonObject
        val libraries = LegacyForgeProfile.libraries(info, forgeCoordinate)

        assertEquals(
            "основной архив берётся из установщика, настольный lwjgl отсекается",
            listOf("org.scala-lang:scala-library:2.11.1"),
            libraries.map { it.name },
        )
        val scala = libraries.single()
        assertEquals("адрес собран от живого Forge maven", "https://maven.minecraftforge.net/$scalaPath", scala.url)
        assertEquals("принимается любая из перечисленных сумм", 2, scala.checksums.size)
    }

    @Test
    fun `старый Forge ставится из установщика и пишет суммы в описание версии`() = runBlocking {
        val layout = GameLayout(folder.newFolder()).also { it.ensure() }
        val scala = File(layout.libraries, scalaPath).apply { parentFile.mkdirs(); writeBytes(scalaBytes) }

        val installer = zip(
            "install_profile.json" to profile().toByteArray(),
            "forge-1.7.10-10.13.4.1614-1.7.10-universal.jar" to universalBytes,
        )
        server.enqueue(MockResponse().setBody(sha1(installer)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(installer)))

        val versions = ForgeVersions(
            LoaderMirror(Downloader(attempts = 1)),
            promotionsUrl = server.url("/promotions.json").toString(),
            mavenBase = server.url("/maven").toString(),
        )
        val installed = ForgeFamilyInstaller(layout, Downloader(attempts = 1), versions, legacyForge = { true })
            .install("1.7.10", "10.13.4.1614")

        assertEquals("профиль называется как у всех версий Forge", "1.7.10-forge-10.13.4.1614", installed)
        assertTrue("уже скачанная библиотека с верной суммой не качается заново", scala.readBytes().contentEquals(scalaBytes))
        assertEquals("сеть нужна только для установщика и его суммы", 2, server.requestCount)

        val universal = File(layout.libraries, "net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10.jar")
        assertTrue("основной архив разложен по координате из профиля", universal.readBytes().contentEquals(universalBytes))

        val json = MojangJson.parseToJsonElement(layout.versionJson(installed).readText()).jsonObject
        assertEquals("версия наследует ваниль", "1.7.10", json["inheritsFrom"]!!.jsonPrimitive.content)
        assertFalse("ключ jar старого формата не переносится", json.containsKey("jar"))
        val artifacts = json["libraries"]!!.jsonArray.associate { library ->
            val entry = library.jsonObject
            entry["name"]!!.jsonPrimitive.content to entry["downloads"]!!.jsonObject["artifact"]!!.jsonObject
        }
        assertEquals("настольный lwjgl в описание не попадает", setOf(forgeCoordinate, "org.scala-lang:scala-library:2.11.1"), artifacts.keys)
        assertEquals(
            "у библиотеки записана та сумма, с которой файл совпал",
            sha1(scalaBytes),
            artifacts.getValue("org.scala-lang:scala-library:2.11.1")["sha1"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "у основного архива — сумма извлечённого файла, чтобы запуск проверил его снова",
            sha1(universalBytes),
            artifacts.getValue(forgeCoordinate)["sha1"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `строка запуска старого Forge`() = runBlocking {
        val layout = GameLayout(folder.newFolder()).also { it.ensure() }
        val vanilla = """
            {
              "id": "1.7.10",
              "mainClass": "net.minecraft.client.main.Main",
              "assets": "1.7.10",
              "javaVersion": {"component": "jre-legacy", "majorVersion": 8},
              "downloads": {"client": {"sha1": "${"a".repeat(40)}", "size": 1, "url": "https://example.invalid/client.jar"}},
              "minecraftArguments": "--username ${'$'}{auth_player_name} --version ${'$'}{version_name} --gameDir ${'$'}{game_directory} --assetsDir ${'$'}{assets_root} --assetIndex ${'$'}{assets_index_name} --uuid ${'$'}{auth_uuid} --accessToken ${'$'}{auth_access_token} --userProperties ${'$'}{user_properties} --userType ${'$'}{user_type}",
              "logging": {"client": {"argument": "-Dlog4j.configurationFile=${'$'}{path}",
                "file": {"id": "client-1.7.xml", "sha1": "${"b".repeat(40)}", "size": 1, "url": "https://example.invalid/client-1.7.xml"},
                "type": "log4j2-xml"}},
              "libraries": [
                {"name": "org.lwjgl.lwjgl:lwjgl:2.9.1"},
                {"name": "org.apache.logging.log4j:log4j-core:2.0-beta9",
                 "downloads": {"artifact": {"path": "org/apache/logging/log4j/log4j-core/2.0-beta9/log4j-core-2.0-beta9.jar", "sha1": "${"d".repeat(40)}", "size": 1, "url": "https://example.invalid/log4j.jar"}}},
                {"name": "com.google.guava:guava:15.0",
                 "downloads": {"artifact": {"path": "com/google/guava/guava/15.0/guava-15.0.jar", "sha1": "${"c".repeat(40)}", "size": 1, "url": "https://example.invalid/guava.jar"}}}
              ]
            }
        """.trimIndent()
        val info = MojangJson.parseToJsonElement(profile()).jsonObject["versionInfo"]!!.jsonObject
        val forge = LegacyForgeProfile.versionJson(
            info,
            "1.7.10-forge-10.13.4.1614",
            "1.7.10",
            mapOf(
                forgeCoordinate to Artifact(
                    path = "net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10.jar",
                    sha1 = sha1(universalBytes),
                    size = 9,
                    url = LegacyForgeProfile.universalUrl(forgeCoordinate),
                ),
            ),
        )
        listOf("1.7.10" to vanilla, "1.7.10-forge-10.13.4.1614" to MojangJson.encodeToString(JsonObject.serializer(), forge))
            .forEach { (id, text) -> layout.versionJson(id).apply { parentFile.mkdirs(); writeText(text) } }
        File(layout.assets, "log_configs").mkdirs()
        val logConfig = File(File(layout.assets, "log_configs"), "client-1.7.xml").apply { writeText("<Configuration/>") }

        repeat(2) { server.enqueue(MockResponse().setBody("""{"versions": []}""")) }
        val resolver = VersionResolver(layout, Downloader(attempts = 1), manifestUrl = server.url("/manifest.json").toString())
        val version = resolver.resolve("1.7.10-forge-10.13.4.1614")

        assertEquals("Java 8 и потолок 8: LaunchWrapper", JavaRequirement(8, 8), JavaRequirement.of(version.javaMajor, version.mainClass))
        assertEquals("клиент — ванильный 1.7.10", "1.7.10", version.jarVersionId)
        assertTrue(
            "основной архив Forge на пути классов",
            version.libraries.any { it.name == forgeCoordinate && it.sha1 == sha1(universalBytes) },
        )
        assertFalse("настольный lwjgl 2 подменяется слоем совместимости пакета", version.libraries.any { it.name.contains("lwjgl") })

        val spec = ForgeLaunch.build(
            layout,
            LaunchRequest(
                version = version,
                session = OfflineAuth.session("Steve"),
                gameDir = folder.newFolder("profile"),
                memoryMb = 1024,
                windowWidth = 1280,
                windowHeight = 720,
            ),
        )

        assertEquals("главный класс — LaunchWrapper", JavaRequirement.LAUNCHWRAPPER_MAIN, spec.mainClass)
        assertEquals("твикер FML передаётся ровно один раз", 1, spec.gameArgs.count { it == "cpw.mods.fml.common.launcher.FMLTweaker" })
        assertEquals("флаг --version не дублируется", 1, spec.gameArgs.count { it == "--version" })
        assertEquals(
            "в --version имя профиля Forge, а не ванили",
            "1.7.10-forge-10.13.4.1614",
            spec.gameArgs[spec.gameArgs.indexOf("--version") + 1],
        )
        assertFalse("неподставленных заглушек не остаётся", spec.gameArgs.any { it.contains("\${") })
        assertTrue(
            "log4j 2.0-beta9 выполняет подстановки из чата; защиту включает только конфиг Mojang",
            spec.jvmArgs.contains("-Dlog4j.configurationFile=${logConfig.absolutePath}"),
        )
        assertTrue("клиентский jar на пути классов", spec.classpath.last().name == "1.7.10.jar")
    }

    @Test
    fun `конфиг журнала Mojang нужен только старому log4j`() {
        fun version(log4j: String?, withLogging: Boolean = true) = ResolvedVersion(
            id = "x",
            jarVersionId = "x",
            mainClass = "m",
            assetsId = "x",
            assetIndex = null,
            clientJar = null,
            libraries = listOfNotNull(
                log4j?.let {
                    ResolvedLibrary("org.apache.logging.log4j:log4j-core:$it", "p", "u", null, 0)
                },
            ),
            gameArguments = emptyList(),
            jvmArguments = emptyList(),
            javaMajor = 8,
            lwjglVersion = "",
            logging = if (withLogging) {
                LoggingConfig(
                    argument = "-Dlog4j.configurationFile=${'$'}{path}",
                    file = LoggingConfig.LoggingFile(id = "client-1.12.xml"),
                )
            } else {
                null
            },
        )

        data class Case(val log4j: String?, val logging: Boolean, val expected: Boolean, val why: String)

        val cases = listOf(
            Case("2.0-beta9", true, true, "1.7–1.11: подстановки из чата выключает только конфиг"),
            Case("2.8.1", true, true, "1.12–1.16: флага formatMsgNoLookups ещё нет"),
            Case("2.10.0", true, false, "с 2.10 хватает флага, конфиг лишь засоряет журнал XML"),
            Case("2.17.1", true, false, "исправленный log4j новых версий"),
            Case(null, true, false, "без log4j на пути классов конфиг не к чему применять"),
            Case("2.0-beta9", false, false, "версия без конфига — подставить нечего"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, Log4jGuard.needsConfig(version(case.log4j, case.logging)))
        }
    }

    @Test
    fun `экран загрузки Forge выключается и не трогается повторно`() {
        data class Case(val existing: String?, val expected: String?, val why: String)

        val cases = listOf(
            Case(null, "enabled=false\n", "файла нет — Forge включил бы экран по умолчанию"),
            Case("enabled=true\nrendering=true\n", "enabled=false\nrendering=true\n", "остальные настройки не теряются"),
            Case(" enabled = TRUE", "enabled=false", "пробелы и регистр не мешают выключить"),
            Case("enabled=false\n", null, "уже выключено — файл не переписывается"),
            Case("rendering=true\n", "rendering=true\nenabled=false\n", "ключа нет — дописывается"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, ForgeSplash.patched(case.existing))
        }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
