package net.millida.launcher.core

import net.millida.launcher.core.account.GameSession
import net.millida.launcher.core.account.OfflineAuth
import net.millida.launcher.core.launch.LaunchRequest
import net.millida.launcher.core.launch.LaunchSpec
import net.millida.launcher.core.launch.LaunchSpecBuilder
import net.millida.launcher.core.launch.MillidaModToken
import net.millida.launcher.core.mojang.LoggingConfig
import net.millida.launcher.core.mojang.ResolvedLibrary
import net.millida.launcher.core.mojang.ResolvedVersion
import net.millida.launcher.core.packs.PackLaunch
import net.millida.launcher.core.packs.PackLaunchBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LaunchSpecBuildTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var layout: GameLayout
    private lateinit var gameDir: File

    @Before
    fun prepare() {
        layout = GameLayout(tmp.newFolder("game")).also { it.ensure() }
        gameDir = tmp.newFolder("profile")
    }

    private fun resolved(
        game: List<String> = emptyList(),
        jvm: List<String> = emptyList(),
        libraries: List<ResolvedLibrary> = emptyList(),
        logging: LoggingConfig? = null,
    ) = ResolvedVersion(
        id = "1.12.2",
        jarVersionId = "1.12.2",
        mainClass = "net.minecraft.client.main.Main",
        assetsId = "1.12",
        assetIndex = null,
        clientJar = null,
        libraries = libraries,
        gameArguments = game,
        jvmArguments = jvm,
        javaMajor = 8,
        lwjglVersion = "2.9.4",
        logging = logging,
    )

    private fun request(
        version: ResolvedVersion = resolved(),
        memoryMb: Int = 2048,
        session: GameSession = OfflineAuth.session("Steve"),
        quickPlay: String? = null,
        authlib: File? = null,
        prefetched: String? = null,
        extraJvm: List<String> = emptyList(),
        extraGame: List<String> = emptyList(),
        millidaToken: String? = null,
    ) = LaunchRequest(
        version = version,
        session = session,
        gameDir = gameDir,
        memoryMb = memoryMb,
        windowWidth = 1280,
        windowHeight = 720,
        extraJvmArgs = extraJvm,
        extraGameArgs = extraGame,
        authlibInjector = authlib,
        authlibPrefetched = prefetched,
        quickPlayServer = quickPlay,
        millidaToken = millidaToken,
    )

    private fun build(request: LaunchRequest): LaunchSpec = LaunchSpecBuilder(layout).build(request)

    private fun valueAfter(args: List<String>, flag: String): String? = args.getOrNull(args.indexOf(flag) + 1)?.takeIf { flag in args }

    @Test
    fun `токен мода Millida уходит в свойство машины только вошедшему`() {
        data class Case(val token: String?, val extraJvm: List<String>, val expected: List<String>, val why: String)
        val cases = listOf(
            Case("mod-abc", emptyList(), listOf("-Dmillida.token=mod-abc"), "вошёл в Millida: без токена гардероб в моде пишет «не знает, кто ты»"),
            Case(" mod-abc \n", emptyList(), listOf("-Dmillida.token=mod-abc"), "пробелы из ответа сервера не становятся частью токена"),
            Case(null, emptyList(), emptyList(), "не вошёл: свойство не передаётся вовсе"),
            Case("  ", emptyList(), emptyList(), "пустой токен равен отсутствию входа"),
            Case("mod-abc", listOf("-Dmillida.token=forged"), listOf("-Dmillida.token=mod-abc"), "чужое значение из флагов сборки не перебивает сессию игрока"),
            Case(null, listOf("-Dmillida.token=forged"), emptyList(), "флаги сборки не подсовывают токен тому, кто не вошёл"),
        )
        cases.forEach { case ->
            val jvm = build(request(millidaToken = case.token, extraJvm = case.extraJvm)).jvmArgs
            assertEquals(case.why, case.expected, jvm.filter { it.startsWith("-Dmillida.token") })
        }
    }

    @Test
    fun `токен мода в журнале скрыт, прочие аргументы как есть`() {
        data class Case(val argument: String, val expected: String, val why: String)
        listOf(
            Case("-Dmillida.token=mod-abc", "-Dmillida.token=***", "токен — секрет, журнал запуска уходит в поддержку"),
            Case("-Dmillida.renderer=gl4es", "-Dmillida.renderer=gl4es", "соседние свойства лаунчера нужны для разбора"),
            Case("-Xmx2048M", "-Xmx2048M", "память в журнале нужна для разбора вылетов"),
        ).forEach { case ->
            assertEquals(case.why, case.expected, MillidaModToken.redact(case.argument))
        }
    }

    @Test
    fun `быстрый вход на сервер получает порт по умолчанию`() {
        data class Case(val address: String, val expected: String, val why: String)
        val cases = listOf(
            Case("play.example.net", "play.example.net:25565", "без порта игра ждёт стандартный 25565"),
            Case("play.example.net:25570", "play.example.net:25570", "свой порт сервера сохраняется"),
        )
        cases.forEach { case ->
            val spec = build(request(quickPlay = case.address))
            assertEquals("${case.address}: ${case.why}", case.expected, valueAfter(spec.gameArgs, "--quickPlayMultiplayer"))
        }
        assertFalse("без сервера флаг входа не появляется", build(request()).gameArgs.contains("--quickPlayMultiplayer"))
    }

    @Test
    fun `папка игры передаётся ровно один раз`() {
        val bare = build(request(resolved(game = listOf("--username", "\${auth_player_name}"))))
        assertEquals("старые версии без --gameDir пишут миры в общую папку — флаг добавляется", gameDir.absolutePath, valueAfter(bare.gameArgs, "--gameDir"))

        val declared = build(request(resolved(game = listOf("--gameDir", "\${game_directory}"))))
        assertEquals("объявленный версией флаг не дублируется", 1, declared.gameArgs.count { it == "--gameDir" })
        assertEquals(gameDir.absolutePath, valueAfter(declared.gameArgs, "--gameDir"))
    }

    @Test
    fun `память кучи и порядок своих флагов`() {
        data class Case(val memory: Int, val xms: String, val why: String)
        val cases = listOf(
            Case(2048, "-Xms1024M", "стартовая куча — половина"),
            Case(200, "-Xms128M", "стартовая куча не меньше 128 МБ, иначе игра тормозит на старте"),
            Case(255, "-Xms128M", "половина ниже порога поднимается до порога"),
        )
        cases.forEach { case ->
            val spec = build(request(memoryMb = case.memory))
            assertEquals("${case.memory} МБ: ${case.why}", case.xms, spec.jvmArgs[0])
            assertEquals("потолок кучи — ровно выбранная память", "-Xmx${case.memory}M", spec.jvmArgs[1])
        }

        val spec = build(request(resolved(jvm = listOf("-Dversion=1")), extraJvm = listOf("-Dplayer=1"), extraGame = listOf("--fullscreen"), quickPlay = "a.b"))
        assertTrue(
            "флаги игрока идут после флагов версии и перебивают их",
            spec.jvmArgs.indexOf("-Dplayer=1") > spec.jvmArgs.indexOf("-Dversion=1"),
        )
        assertEquals("аргументы игрока замыкают строку запуска", "--fullscreen", spec.gameArgs.last())
    }

    @Test
    fun `агент входа ставится только при сервере авторизации`() {
        val agent = File(tmp.root, "authlib-injector.jar")
        val offline = build(request(authlib = agent))
        assertFalse("без адреса сервера агенту нечего подменять", offline.jvmArgs.any { it.startsWith("-javaagent") })

        val millida = GameSession(nick = "Steve", uuid = "u", accessToken = "t", yggdrasil = "https://auth.millida.net/api/yggdrasil")
        val signed = build(request(session = millida, authlib = agent))
        assertTrue(
            "агент получает адрес сервера, иначе вход на серверы Millida не работает",
            signed.jvmArgs.contains("-javaagent:${agent.absolutePath}=https://auth.millida.net/api/yggdrasil"),
        )
    }

    @Test
    fun `описание сервера входа идёт агенту, только когда агент есть`() {
        val agent = File(tmp.root, "authlib-injector.jar")
        val millida = GameSession(nick = "Steve", uuid = "u", accessToken = "t", yggdrasil = "https://auth.millida.net/api/yggdrasil")
        val property = "-Dauthlibinjector.yggdrasil.prefetched=e30="
        assertTrue(build(request(session = millida, authlib = agent, prefetched = property)).jvmArgs.contains(property))
        assertFalse("без агента свойство никому не нужно", build(request(session = millida, prefetched = property)).jvmArgs.contains(property))
        assertFalse("и без сервера входа", build(request(authlib = agent, prefetched = property)).jvmArgs.contains(property))
    }

    @Test
    fun `путь к классам целиком и библиотеки до клиента`() {
        val libraries = listOf(
            ResolvedLibrary("com.mojang:brigadier:1.0.18", "com/mojang/brigadier.jar", "https://x/b.jar", null, 0),
            ResolvedLibrary("org.ow2.asm:asm:9.6", "org/ow2/asm.jar", "https://x/a.jar", null, 0),
        )
        val spec = build(request(resolved(libraries = libraries, jvm = listOf("-Dcp=\${classpath}", "-Dkeep=1"))))
        assertEquals(
            "порядок classpath решает, какой класс победит: библиотеки по порядку версии, клиент последним",
            listOf(layout.library("com/mojang/brigadier.jar"), layout.library("org/ow2/asm.jar"), layout.versionJar("1.12.2")).map { it.absolutePath },
            spec.classpath.map { it.absolutePath },
        )
        assertFalse("путь к классам задаёт хост — свойство с ним выбрасывается целиком", spec.jvmArgs.any { it.startsWith("-Dcp=") })
        assertTrue(spec.jvmArgs.contains("-Dkeep=1"))
    }

    @Test
    fun `ванильный jar попадает в список пропуска Forge под своим настоящим именем`() {
        data class Case(val jvm: List<String>, val expected: List<String>, val why: String)

        val forge = resolved().copy(id = "neoforge-21.1.228", jarVersionId = "1.21.1")
        val cases = listOf(
            Case(
                listOf("-DignoreList=client-extra,neoforge-,\${version_name}.jar"),
                listOf("-DignoreList=client-extra,neoforge-,neoforge-21.1.228.jar,1.21.1.jar"),
                "официальный лаунчер кладёт ванильный jar как <id версии>.jar; у нас он 1.21.1.jar — без своего имени в списке " +
                    "он грузится вторым модулем _1._21._1 и игра падает на ResolutionException",
            ),
            Case(
                listOf("-DignoreList=bootstraplauncher,1.21.1.jar"),
                listOf("-DignoreList=bootstraplauncher,1.21.1.jar"),
                "имя уже в списке — второй раз не дописывается",
            ),
            Case(
                listOf("-DignoreList=asm,1.21"),
                listOf("-DignoreList=asm,1.21"),
                "загрузчик сверяет начало имени файла: фильтр 1.21 уже закрывает 1.21.1.jar",
            ),
            Case(
                listOf("-DignoreList=asm,"),
                listOf("-DignoreList=asm,,1.21.1.jar"),
                "пустой фильтр после запятой не считается совпадением",
            ),
            Case(
                listOf("-Dfml.ignoreInvalidMinecraftCertificates=true"),
                listOf("-Dfml.ignoreInvalidMinecraftCertificates=true"),
                "версии без списка пропуска (ваниль, Fabric, Forge до 1.17) не трогаются",
            ),
        )
        for (case in cases) {
            val spec = build(request(forge.copy(jvmArguments = case.jvm)))
            assertEquals("список пропуска: ${case.why}", case.expected, spec.jvmArgs.filter { it in case.expected || it.startsWith("-DignoreList=") })
            assertEquals("сам jar остаётся в пути к классам последним", layout.versionJar("1.21.1").absolutePath, spec.classpath.last().absolutePath)
        }
    }

    @Test
    fun `настройка журнала для старого log4j подставляется, если файл на месте`() {
        val log4j = ResolvedLibrary("org.apache.logging.log4j:log4j-core:2.8.1", "log4j-core.jar", "https://x/l.jar", null, 0)
        val logging = LoggingConfig(
            argument = "-Dlog4j.configurationFile=\${path}",
            file = LoggingConfig.LoggingFile(id = "client-1.12.xml", sha1 = "a".repeat(40), size = 1, url = "https://x/client-1.12.xml"),
            type = "log4j2-xml",
        )
        val old = resolved(libraries = listOf(log4j), logging = logging)

        val missing = build(request(old))
        assertFalse("ссылка на несуществующий файл журнала роняет log4j при старте", missing.jvmArgs.any { it.startsWith("-Dlog4j.configurationFile") })

        val config = layout.logConfig("client-1.12.xml").also { it.parentFile.mkdirs(); it.writeText("<Configuration/>") }
        val present = build(request(old))
        assertTrue(
            "старый log4j без своей настройки исполняет подстановки из чата (CVE-2021-44228)",
            present.jvmArgs.contains("-Dlog4j.configurationFile=${config.absolutePath}"),
        )

        val modern = resolved(libraries = listOf(log4j.copy(name = "org.apache.logging.log4j:log4j-core:2.17.1")), logging = logging)
        assertFalse("новый log4j не нуждается в настройке — она только засоряет журнал", build(request(modern)).jvmArgs.any { it.startsWith("-Dlog4j.configurationFile") })
    }

    private fun packSpec(
        slug: String = "oneblock",
        gameArgs: List<String> = listOf("--version", "\${version_name}", "--assetsDir", "\${assets_root}", "--assetIndex", "\${assets_index_name}"),
        vanillaAssets: String = "",
        assetIndex: String = "pack-assets",
        needsToken: Boolean = false,
    ) = PackLaunch(
        slug = slug,
        mainClass = "com.example.pack.Main",
        assetIndex = assetIndex,
        assetsDir = "assets",
        nativesDir = "natives",
        classPath = listOf("libraries", "client.jar"),
        jvmArgs = listOf("-Dpack=1"),
        gameArgs = gameArgs,
        vanillaAssets = vanillaAssets,
        javaMajor = 21,
        minRamMb = 0,
        maxRamMb = 0,
        needsToken = needsToken,
    )

    private fun packFiles() {
        listOf("libraries/com/a/a-1.0.jar", "libraries/lwjgl-3.3.3.jar", "client.jar").forEach { path ->
            File(gameDir, path).also { it.parentFile.mkdirs(); it.writeText("x") }
        }
    }

    private val packRequest: LaunchRequest
        get() = request(
            resolved(libraries = listOf(ResolvedLibrary("com.mojang:brigadier:1.0.18", "com/mojang/brigadier.jar", "https://x/b.jar", null, 0))),
            quickPlay = "play.example.net",
        )

    @Test
    fun `своя сборка запускается своим классом, путём к классам и папкой ассетов`() {
        packFiles()
        val spec = PackLaunchBuild.build(layout, packRequest, packSpec(), emptyList())

        assertEquals("com.example.pack.Main", spec.mainClass)
        assertEquals(
            "путь к классам — только jar сборки, без ванильных библиотек и без настольного LWJGL",
            listOf("libraries/com/a/a-1.0.jar", "client.jar"),
            spec.classpath.map { it.relativeTo(gameDir).invariantSeparatorsPath },
        )
        assertEquals("версия называется по слагу — так её видно в журналах", "pack-oneblock", valueAfter(spec.gameArgs, "--version"))
        assertEquals(
            "без общих ассетов сборка берёт свою папку ассетов",
            File(gameDir, "assets").absolutePath,
            valueAfter(spec.gameArgs, "--assetsDir"),
        )
        assertEquals("индекс ассетов из описания сборки", "pack-assets", valueAfter(spec.gameArgs, "--assetIndex"))
        assertFalse("сборка сама решает, как входить на сервер — быстрый вход не добавляется", spec.gameArgs.contains("--quickPlayMultiplayer"))
        assertTrue("флаги сборки доходят до JVM", spec.jvmArgs.contains("-Dpack=1"))
    }

    @Test
    fun `сборка с общими ассетами и без своих аргументов`() {
        packFiles()
        val spec = PackLaunchBuild.build(layout, packRequest, packSpec(slug = "", gameArgs = emptyList(), vanillaAssets = "1.7.10", assetIndex = ""), emptyList())
        assertEquals("без слага версия получает общее имя", "millida-pack", valueAfter(spec.gameArgs, "--version"))
        assertEquals("общие ассеты лежат в папке лаунчера", layout.assets.absolutePath, valueAfter(spec.gameArgs, "--assetsDir"))
        assertEquals("без своего индекса берётся индекс версии", "1.12", valueAfter(spec.gameArgs, "--assetIndex"))
        assertEquals("без своих аргументов сборка получает обычные аргументы игры", "Steve", valueAfter(spec.gameArgs, "--username"))
    }

    @Test
    fun `ключ запуска обязателен и проходит фильтр флагов`() {
        packFiles()
        try {
            PackLaunchBuild.build(layout, packRequest, packSpec(needsToken = true), emptyList())
            fail("сборка, которой нужен ключ, без него не стартует — сервер её всё равно не пустит")
        } catch (expected: IOException) {
            assertTrue("игроку сказано вернуться и нажать «Играть»", expected.message.orEmpty().contains("Играть"))
        }

        val spec = PackLaunchBuild.build(
            layout,
            packRequest,
            packSpec(needsToken = true),
            listOf("-Dmillida.launch.token=abc", "-javaagent:/sdcard/evil.jar"),
        )
        assertTrue("ключ доходит до JVM", spec.jvmArgs.contains("-Dmillida.launch.token=abc"))
        assertFalse("агент под видом ключа не проходит", spec.jvmArgs.any { it.startsWith("-javaagent") })
    }

    @Test
    fun `папка natives создаётся до запуска`() {
        val natives = File(layout.runtimes, "natives")
        natives.deleteRecursively()
        assertFalse(natives.exists())
        build(request())
        assertTrue("игра читает её при старте, даже пустую", natives.isDirectory)
    }
}
