package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.ForgeFamilyInstaller
import net.millida.launcher.core.loader.ForgeGameFiles
import net.millida.launcher.core.loader.ForgeInstallProfile
import net.millida.launcher.core.loader.ForgeInstallStep
import net.millida.launcher.core.loader.ForgePlanFile
import net.millida.launcher.core.loader.ForgeProcessorPlan
import net.millida.launcher.core.loader.ForgeProcessorRunner
import net.millida.launcher.core.loader.ForgeVersions
import net.millida.launcher.core.loader.LoaderMirror
import net.millida.launcher.core.mojang.Artifact
import net.millida.launcher.core.mojang.Maven
import net.millida.launcher.core.mojang.MojangJson
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
import java.io.DataInputStream
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Forge 1.13+ и NeoForge дособирают клиент шагами из install_profile.json. Здесь
 * закреплено, как профиль читается (те же правила, что у настольного
 * установщика), что уходит в шаги и что без сверки хеша ничего не запускается.
 */
class ForgeProcessorsTest {

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

    private val library: (String) -> String = { "/libs/" + Maven.pathOf(it) }

    @Test
    fun `аргументы шагов подставляются по правилам установщика`() {
        val values = mapOf(
            "ROOT" to "/game",
            "MINECRAFT_JAR" to "/game/versions/1.20.1/1.20.1.jar",
            "SIDE" to "client",
            "MOJMAPS" to "/libs/client-mappings.txt",
        )

        data class Case(val raw: String, val expected: String, val why: String)

        val cases = listOf(
            Case("{ROOT}", "/game", "папка игры — ключ, который знает сам лаунчер"),
            Case("{MINECRAFT_JAR}", "/game/versions/1.20.1/1.20.1.jar", "клиент ванили, который патчат шаги"),
            Case("--side={SIDE}", "--side=client", "ключ внутри строки тоже подставляется, как у установщика"),
            Case("'{ROOT}'", "{ROOT}", "одинарные кавычки — литерал, подстановки внутри нет"),
            Case("\\{ROOT}", "{ROOT}", "обратная косая экранирует скобку"),
            Case("--task", "--task", "обычный литерал проходит как есть"),
            Case(
                "[net.minecraftforge:forge:1.20.1-47.4.22:client]",
                "/libs/net/minecraftforge/forge/1.20.1-47.4.22/forge-1.20.1-47.4.22-client.jar",
                "координата с классификатором — путь в библиотеках",
            ),
            Case(
                "[net.minecraft:client:1.20.1:mappings@txt]",
                "/libs/net/minecraft/client/1.20.1/client-1.20.1-mappings.txt",
                "расширение после @ в классификаторе: без этого маппинги искались как .jar",
            ),
            Case(
                "[de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412@zip]",
                "/libs/de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412.zip",
                "расширение после @ в версии",
            ),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, ForgeInstallProfile.resolveArgument(case.raw, values, library))
        }

        data class Broken(val raw: String, val why: String)

        val broken = listOf(
            Broken("{MC_SRG}", "неизвестный ключ нельзя пропускать литералом: следующий шаг ищет несуществующий файл"),
            Broken("{ROOT", "незакрытая скобка — испорченный профиль, а не путь"),
            Broken("tail\\", "обрыв после обратной косой"),
        )
        for (case in broken) {
            assertTrue(case.why, runCatching { ForgeInstallProfile.resolveArgument(case.raw, values, library) }.isFailure)
        }
    }

    @Test
    fun `поля data берутся для клиента и различаются по виду`() {
        val data = MojangJson.parseToJsonElement(
            """
            {
              "MAPPINGS": {"client": "[de.oceanlabs.mcp:mcp_config:1.20.1@zip]", "server": "[x:y:1]"},
              "MC_SLIM_SHA": {"client": "'0123456789abcdef0123456789abcdef01234567'", "server": "'s'"},
              "BINPATCH": {"client": "/data/client.lzma", "server": "/data/server.lzma"},
              "PLAIN": {"client": "data/extra.bin", "server": "data/extra.bin"},
              "SERVER_ONLY": {"server": "[x:y:1]"}
            }
            """.trimIndent()
        ) as JsonObject
        val extracted = ArrayList<String>()
        val values = ForgeInstallProfile.dataValues(data, "client", library) { path ->
            extracted += path
            "/work/" + path.removePrefix("/")
        }

        data class Case(val key: String, val expected: String?, val why: String)

        val cases = listOf(
            Case(
                "MAPPINGS",
                "/libs/de/oceanlabs/mcp/mcp_config/1.20.1/mcp_config-1.20.1.zip",
                "[координата] — файл в библиотеках, и берётся значение клиента, а не сервера",
            ),
            Case("MC_SLIM_SHA", "0123456789abcdef0123456789abcdef01234567", "'литерал' без кавычек — это хеш выхода шага"),
            Case("BINPATCH", "/work/data/client.lzma", "путь внутри установщика распаковывается"),
            Case("PLAIN", "/work/data/extra.bin", "у установщика любой не-литерал — путь в его архиве, даже без /"),
            Case("SERVER_ONLY", null, "поле только для сервера клиенту не достаётся"),
        )
        for (case in cases) {
            assertEquals("${case.key}: ${case.why}", case.expected, values[case.key])
        }
        assertEquals(
            "из установщика достаются только клиентские файлы",
            listOf("/data/client.lzma", "data/extra.bin"),
            extracted,
        )
    }

    @Test
    fun `шаг скачивания маппингов узнаётся по задаче`() {
        data class Case(val args: List<String>, val expected: String?, val why: String)

        val cases = listOf(
            Case(
                listOf("--task", "DOWNLOAD_MOJMAPS", "--version", "1.20.1", "--side", "client", "--output", "/m.txt"),
                "/m.txt",
                "маппинги клиента качает лаунчер через своё зеркало",
            ),
            Case(
                listOf("--task", "DOWNLOAD_MOJMAPS", "--side", "server", "--output", "/m.txt"),
                null,
                "серверные маппинги клиенту не нужны — шаг остаётся как есть",
            ),
            Case(listOf("--task", "MCP_DATA", "--output", "/x"), null, "другая задача не трогается"),
            Case(listOf("--task", "DOWNLOAD_MOJMAPS"), null, "без --output подменять нечего"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, ForgeInstallProfile.mappingsDownloadTarget(case.args))
        }
    }

    @Test
    fun `современный Forge собирается шагами и записывает версию только после проверки выхода`() = runBlocking {
        val layout = GameLayout(folder.newFolder()).also { it.ensure() }
        val client = File(folder.newFolder(), "1.20.1.jar").apply { writeText("vanilla") }
        val tool = processorJar()
        val patched = "patched".toByteArray()
        serveInstaller(installer(tool, toolSha = Hashing.sha1(tool), patchedSha = Hashing.sha1(patched)))
        val mappings = "mappings".toByteArray()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(mappings)))

        var seen: ForgeProcessorPlan? = null
        var patchInput: Boolean? = null
        val runner = ForgeProcessorRunner { plan, onStep ->
            seen = plan
            ForgePlanFile.write(plan, File(folder.root, "plan.bin"))
            patchInput = File(plan.jobs.single().args[plan.jobs.single().args.indexOf("--patch") + 1]).isFile
            plan.jobs.forEach { job -> job.outputs.keys.forEach { File(it).apply { parentFile.mkdirs() }.writeBytes(patched) } }
            onStep(plan.jobs.size, plan.jobs.size)
        }
        val game = object : ForgeGameFiles {
            override suspend fun clientJar(gameVersion: String): File = client
            override suspend fun clientMappings(gameVersion: String): Artifact =
                Artifact(sha1 = Hashing.sha1(mappings), size = mappings.size.toLong(), url = server.url("/mappings.txt").toString())
        }
        val steps = ArrayList<ForgeInstallStep>()

        val id = installer(layout, runner, game).install("1.20.1", "47.4.22") { steps += it }

        val plan = seen ?: error("шаги должны уйти в среду Java")
        assertEquals("шаг скачивания маппингов снят: файл скачал лаунчер", 1, plan.jobs.size)
        val mapped = layout.library("net/minecraft/client/1.20.1/client-1.20.1-mappings.txt")
        assertTrue("маппинги лежат там, куда их ждёт следующий шаг", mapped.isFile)

        val job = plan.jobs.single()
        assertEquals("главный класс берётся из манифеста программы шага", "net.example.Patcher", job.mainClass)
        assertEquals(
            "программа шага — библиотека из профиля",
            layout.library("net/minecraftforge/installertools/1.3.0/installertools-1.3.0.jar").canonicalPath,
            job.jar.canonicalPath,
        )
        val args = job.args
        assertEquals("клиент ванили подставлен", client.absolutePath, args[args.indexOf("--input") + 1])
        assertEquals("папка игры подставлена", layout.root.absolutePath, args[args.indexOf("--root") + 1])
        assertEquals("сторона — клиент", "client", args[args.indexOf("--side") + 1])
        assertEquals("маппинги — [координата] из data", mapped.absolutePath, args[args.indexOf("--mappings") + 1])
        assertEquals("patch-файл из установщика был распакован к запуску шага", true, patchInput)

        val output = layout.library("net/minecraftforge/forge/1.20.1-47.4.22/forge-1.20.1-47.4.22-client.jar")
        assertEquals("выход шага сверяется с хешем из профиля", Hashing.sha1(patched), job.outputs[output.absolutePath])

        assertEquals("имя профиля предсказуемо", "1.20.1-forge-47.4.22", id)
        assertTrue("описание версии записано после сборки", layout.versionJson(id).isFile)
        assertTrue("прогресс дошёл до шагов Java", steps.any { it is ForgeInstallStep.Processors && it.done == 1 })

        DataInputStream(File(folder.root, "plan.bin").inputStream()).use { input ->
            assertEquals("формат плана для драйвера", ForgePlanFile.FORMAT, input.readInt())
            assertEquals("в плане один шаг", 1, input.readInt())
            assertEquals("первым идёт путь программы", job.jar.absolutePath, input.readUTF())
            assertEquals("затем главный класс", job.mainClass, input.readUTF())
        }
    }

    @Test
    fun `без сверки хеша программа шага не запускается, а версия не записывается`() = runBlocking {
        data class Case(val toolSha: String?, val producesOutput: Boolean, val expect: String, val why: String)

        val tool = processorJar()
        val patched = "patched".toByteArray()
        val cases = listOf(
            Case(null, true, "контрольной суммы", "исполняемое без хеша из профиля не запускается"),
            Case("0".repeat(40), true, "повреждена", "подменённая программа шага отбрасывается"),
            Case(Hashing.sha1(tool), false, "с ошибкой", "шаг, не собравший выход, не выдаётся за успех"),
        )
        for (case in cases) {
            val layout = GameLayout(folder.newFolder()).also { it.ensure() }
            val client = File(folder.newFolder(), "1.20.1.jar").apply { writeText("vanilla") }
            serveInstaller(installer(tool, toolSha = case.toolSha, patchedSha = Hashing.sha1(patched), withMappings = false))
            var ran = false
            val runner = ForgeProcessorRunner { plan, _ ->
                ran = true
                if (case.producesOutput) {
                    plan.jobs.forEach { job ->
                        job.outputs.keys.forEach { File(it).apply { parentFile.mkdirs() }.writeBytes(patched) }
                    }
                }
            }
            val game = object : ForgeGameFiles {
                override suspend fun clientJar(gameVersion: String): File = client
                override suspend fun clientMappings(gameVersion: String): Artifact? = null
            }

            val failure = runCatching { installer(layout, runner, game).install("1.20.1", "47.4.22") }

            val message = failure.exceptionOrNull()?.message.orEmpty()
            assertTrue("${case.why}: $message", message.contains(case.expect))
            assertFalse("${case.why}: профиль не должен выглядеть установленным", layout.versionJson("1.20.1-forge-47.4.22").exists())
            if (case.toolSha != Hashing.sha1(tool)) assertFalse("${case.why}: среда Java не вызывалась", ran)
        }
    }

    private fun installer(layout: GameLayout, runner: ForgeProcessorRunner, game: ForgeGameFiles): ForgeFamilyInstaller {
        val mirror = LoaderMirror(Downloader(attempts = 1), mirrorBase = server.url("/mirror").toString())
        val versions = ForgeVersions(mirror, mavenBase = server.url("/maven").toString())
        return ForgeFamilyInstaller(layout, Downloader(attempts = 1), versions, mirror, runner, game)
    }

    private fun serveInstaller(installer: ByteArray) {
        server.enqueue(MockResponse().setBody(Hashing.sha1(installer)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(installer)))
    }

    private fun processorJar(): ByteArray {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = "net.example.Patcher"
        }
        val out = ByteArrayOutputStream()
        JarOutputStream(out, manifest).use { it.putNextEntry(ZipEntry("net/example/Patcher.class")); it.write(1) }
        return out.toByteArray()
    }

    private fun installer(tool: ByteArray, toolSha: String?, patchedSha: String, withMappings: Boolean = true): ByteArray {
        val toolPath = "net/minecraftforge/installertools/1.3.0/installertools-1.3.0.jar"
        val shaField = toolSha?.let { ""","sha1": "$it"""" }.orEmpty()
        val mappingsStep = if (withMappings) {
            """
            {
              "jar": "net.minecraftforge:installertools:1.3.0",
              "classpath": [],
              "args": ["--task", "DOWNLOAD_MOJMAPS", "--version", "{MINECRAFT_VERSION}", "--side", "{SIDE}", "--output", "{MOJMAPS}"]
            },
            """.trimIndent()
        } else {
            ""
        }
        return zip(
            "install_profile.json" to """
                {
                  "spec": 1,
                  "json": "/version.json",
                  "libraries": [
                    {"name": "net.minecraftforge:installertools:1.3.0",
                     "downloads": {"artifact": {"path": "$toolPath", "url": ""$shaField, "size": ${tool.size}}}}
                  ],
                  "data": {
                    "MOJMAPS": {"client": "[net.minecraft:client:1.20.1:mappings@txt]", "server": "[net.minecraft:server:1.20.1:mappings@txt]"},
                    "BINPATCH": {"client": "/data/client.lzma", "server": "/data/server.lzma"},
                    "PATCHED": {"client": "[net.minecraftforge:forge:1.20.1-47.4.22:client]", "server": "[x:y:1]"},
                    "PATCHED_SHA": {"client": "'$patchedSha'", "server": "'x'"}
                  },
                  "processors": [
                    $mappingsStep
                    {
                      "sides": ["server"],
                      "jar": "net.minecraftforge:installertools:1.3.0",
                      "args": ["--server-only"]
                    },
                    {
                      "jar": "net.minecraftforge:installertools:1.3.0",
                      "classpath": [],
                      "args": ["--input", "{MINECRAFT_JAR}", "--root", "{ROOT}", "--side", "{SIDE}",
                               "--mappings", "{MOJMAPS}", "--patch", "{BINPATCH}", "--output", "{PATCHED}"],
                      "outputs": {"{PATCHED}": "{PATCHED_SHA}"}
                    }
                  ]
                }
            """.trimIndent().toByteArray(),
            "version.json" to """
                {"id": "1.20.1-forge-47.4.22", "inheritsFrom": "1.20.1", "mainClass": "x", "libraries": [
                  {"name": "net.minecraftforge:forge:1.20.1-47.4.22:client",
                   "downloads": {"artifact": {"path": "net/minecraftforge/forge/1.20.1-47.4.22/forge-1.20.1-47.4.22-client.jar", "url": ""}}}
                ]}
            """.trimIndent().toByteArray(),
            "data/client.lzma" to "patch".toByteArray(),
            "maven/$toolPath" to tool,
        )
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
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
