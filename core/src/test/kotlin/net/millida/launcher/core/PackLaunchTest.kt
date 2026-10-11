package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.packs.LaunchShape
import net.millida.launcher.core.packs.PackContent
import net.millida.launcher.core.packs.PackInstaller
import net.millida.launcher.core.packs.PackJvmArgs
import net.millida.launcher.core.packs.PackLaunches
import net.millida.launcher.core.packs.PackOrigin
import net.millida.launcher.core.packs.PackOriginStore
import net.millida.launcher.core.packs.PackPlan
import net.millida.launcher.core.packs.PackSource
import net.millida.launcher.core.packs.PackVersionChoice
import net.millida.launcher.core.packs.PackVersionResolver
import net.millida.launcher.core.packs.PhoneFit
import net.millida.launcher.core.profile.ProfileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * A pack that brings its own game decides a command line from a file inside an
 * archive someone else prepared. Pinned here: what such a description may name,
 * that only a catalogue install is trusted to run it, and that the class path
 * it expands to stays inside the pack and leaves the desktop window library out.
 */
class PackLaunchTest {

    private lateinit var root: File

    @Before
    fun start() {
        root = File(System.getProperty("java.io.tmpdir"), "millida-pack-launch-test-" + System.nanoTime())
        root.mkdirs()
    }

    @After
    fun stop() {
        root.deleteRecursively()
    }

    /** The description handed to the OneBlock partner, as the desktop test pins it. */
    private fun oneBlock(): String = """
        {
          "formatVersion": 1,
          "slug": "oneblock-metalabs",
          "mainClass": "com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread",
          "vanillaAssets": "1.7.10",
          "assetIndex": "1.7.10",
          "nativesByPlatform": { "windows-x86_64": "natives/windows/x64", "linux-arm64": "natives/linux/arm64" },
          "classPath": ["libraries", "versions/1.7.10ANGELICA/1.7.10ANGELICA.jar"],
          "jvmArgs": [
            "-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader",
            "-Dfml.ignorePatchDiscrepancies=true",
            "--add-opens=java.base/java.lang=ALL-UNNAMED"
          ],
          "jvmArgsByPlatform": { "macos": ["-XstartOnFirstThread"] },
          "gameArgs": ["--username", "${'$'}{auth_player_name}", "--tweakClass", "cpw.mods.fml.common.launcher.FMLTweaker"],
          "javaMajor": 21,
          "needsToken": true
        }
    """.trimIndent()

    private fun json(text: String): JsonObject = MojangJson.parseToJsonElement(text).jsonObject

    private fun changed(change: String): JsonObject {
        val base = json(oneBlock()).toMutableMap()
        json(change).forEach { (key, value) -> base[key] = value }
        return JsonObject(base)
    }

    @Test
    fun `описание OneBlock читается так, как его ждёт сборка`() {
        val parsed = PackLaunches.parse(json(oneBlock()))
        assertNotNull("описание, выданное партнёру, обязано читаться", parsed)
        val spec = parsed!!
        assertEquals("lwjgl3ify запускается только из своего главного класса", "com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread", spec.mainClass)
        assertEquals("сборка просит Java 21, телефон обязан взять её, а не Java версии игры", 21, spec.javaMajor)
        assertEquals("звуки 1.7.10 берутся из общего хранилища", "1.7.10", spec.vanillaAssets)
        assertTrue("подпись нужна на каждый запуск", spec.needsToken)
        assertTrue(
            "свой загрузчик классов RetroFuturaBootstrap обязан доехать, иначе 1.7.10 не стартует",
            spec.jvmArgs.contains("-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader"),
        )
        assertFalse("флаг macOS на телефоне JVM не примет", spec.jvmArgs.contains("-XstartOnFirstThread"))
        assertEquals("без таблицы для телефона нативы берутся из общего пути, а не из чужой платформы", "natives", spec.nativesDir)
    }

    @Test
    fun `описание без формы запуска отклоняется целиком`() {
        val cases = listOf(
            """{"mainClass": ""}""" to "без главного класса запускать нечего",
            """{"mainClass": "net.fabric; rm -rf /"}""" to "главный класс — имя класса, а не строка команды",
            """{"classPath": []}""" to "пустой classpath — сборка без кода",
            """{"classPath": ["../../etc"]}""" to "classpath вне папки сборки",
            """{"classPath": ["/system/lib"]}""" to "абсолютный путь в classpath",
            """{"classPath": ["C:/Windows/a.jar"]}""" to "путь с диском",
            """{"assetsDir": "../../.."}""" to "ассеты вне папки сборки",
            """{"nativesDir": "/usr/lib", "nativesByPlatform": {}}""" to "нативы по абсолютному пути",
            """{"vanillaAssets": "../1.7.10"}""" to "версия ассетов — имя, а не путь",
            """{"gameArgs": ["--demo\n--server"]}""" to "перевод строки склеил бы два аргумента",
            """{"gameArgs": ["--width", 800]}""" to "аргумент не строка",
            """{"gameArgs": [""]}""" to "пустой аргумент",
        )
        cases.forEach { (change, why) -> assertNull(why, PackLaunches.parse(changed(change))) }
        assertNull("не объект — не описание", PackLaunches.parse(MojangJson.parseToJsonElement("[]")))
    }

    @Test
    fun `чужой архив не протащит агент через свои флаги`() {
        val cases = listOf(
            "-javaagent:/sdcard/evil.jar" to false,
            "-agentpath:/data/x.so" to false,
            "-cp" to false,
            "-Djava.library.path=/sdcard" to false,
            "-Djava.system.class.loader=evil.Loader" to false,
            "@/sdcard/args.txt" to false,
            "-XX:OnOutOfMemoryError=sh" to false,
            "-Dlauncher.signature=abc" to true,
            "-XX:+DisableAttachMechanism" to true,
            "-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader" to true,
        )
        cases.forEach { (arg, allowed) ->
            assertEquals("$arg: флаги сборки и ключа запуска идут в каждый старт", allowed, PackJvmArgs.allowed(arg))
        }
    }

    private fun write(dir: File, path: String, text: String = "x"): File =
        File(dir, path).also { it.parentFile.mkdirs(); it.writeText(text) }

    @Test
    fun `classpath собранной на Windows сборки находится без учёта регистра`() {
        val pack = File(root, "cased")
        write(pack, "Libraries/a.jar")
        write(pack, "versions/1.7.10ANGELICA/1.7.10angelica.jar")
        val spec = PackLaunches.parse(json(oneBlock()))!!
        val names = PackLaunches.classpath(pack, spec).map { it.relativeTo(pack).invariantSeparatorsPath.lowercase() }
        assertEquals(
            "на Windows `libraries` и `Libraries` — одна папка, на телефоне нет: сборка не должна падать на «нет файла»",
            listOf("libraries/a.jar", "versions/1.7.10angelica/1.7.10angelica.jar"),
            names,
        )
        assertNull("несуществующий путь не подбирается похожим", PackLaunches.ignoringCase(pack, "missing/a.jar"))
    }

    @Test
    fun `classpath остаётся в сборке, упорядочен и без настольного LWJGL`() {
        val pack = File(root, "pack")
        write(pack, "libraries/b/z.jar")
        write(pack, "libraries/a-b.jar")
        write(pack, "libraries/a/y.jar")
        write(pack, "libraries/lwjgl-3.3.3.jar")
        write(pack, "libraries/lwjgl-opengl-3.3.3-natives-windows.jar")
        write(pack, "libraries/org/lwjgl/lwjgl/3.3.3/core.jar")
        write(pack, "libraries/lwjgl3ify-2.1.jar")
        write(pack, "libraries/readme.txt")
        write(pack, "versions/1.7.10ANGELICA/1.7.10ANGELICA.jar")
        val spec = PackLaunches.parse(json(oneBlock()))!!
        val names = PackLaunches.classpath(pack, spec).map { it.relativeTo(pack).invariantSeparatorsPath }
        assertEquals(
            "папки разворачиваются по сегментам пути: порядок решает, чей класс победит, и должен совпадать на всех устройствах; " +
                "настольный LWJGL уступает телефонному, lwjgl3ify — мод и остаётся",
            listOf(
                "libraries/a/y.jar",
                "libraries/a-b.jar",
                "libraries/b/z.jar",
                "libraries/lwjgl3ify-2.1.jar",
                "versions/1.7.10ANGELICA/1.7.10ANGELICA.jar",
            ),
            names,
        )
    }

    @Test
    fun `нет файла из classpath — понятная ошибка, а не пустой запуск`() {
        val pack = File(root, "half")
        write(pack, "libraries/a.jar")
        val spec = PackLaunches.parse(json(oneBlock()))!!
        try {
            PackLaunches.classpath(pack, spec)
            fail("архив распакован не полностью — запускать нельзя")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("1.7.10ANGELICA.jar"))
        }
    }

    @Test
    fun `описанию верят только у сборки из каталога, слаг берётся из записи установки`() {
        val build = File(root, "build")
        write(build, PhoneFit.PACK_LAUNCH_FILE, oneBlock())
        assertNull("без записи установки — любой mrpack мог положить такой файл", PackLaunches.trusted(build))

        PackOriginStore.write(build, PackOrigin(source = PackSource.MODRINTH.id, id = "oneblock-metalabs"))
        assertNull("сборка с Modrinth не получает свой запуск", PackLaunches.trusted(build))

        PackOriginStore.write(build, PackOrigin(source = PackSource.MILLIDA_CATALOG.id, id = "../admin"))
        assertNull("слаг идёт в адрес запроса и в путь папки — чужая форма отклоняется", PackLaunches.trusted(build))

        PackOriginStore.write(build, PackOrigin(source = PackSource.MILLIDA_CATALOG.id, id = "oneblock-real"))
        assertEquals("слаг из файла сборки не подменяет слаг каталога", "oneblock-real", PackLaunches.trusted(build)?.slug)
    }

    @Test
    fun `форма запуска распакованной сборки`() {
        val modern = File(root, "modern").also { write(it, PhoneFit.PACK_LAUNCH_FILE, oneBlock()) }
        val legacy = File(root, "legacy").also { write(it, PhoneFit.PACK_LAUNCH_FILE, oneBlock().replace("\"javaMajor\": 21", "\"javaMajor\": 8")) }
        val broken = File(root, "broken").also { write(it, PhoneFit.PACK_LAUNCH_FILE, "{ not json") }
        val plain = File(root, "plain").also { write(it, "mods/a.jar") }
        val cases = listOf(
            Triple(modern, false, LaunchShape.OWN_READY) to "Java 21 есть в рантайме телефона",
            Triple(legacy, false, LaunchShape.OWN_LEGACY) to "Java 8 ждёт рантайма",
            Triple(legacy, true, LaunchShape.OWN_READY) to "с рантаймом Java 8 — готово",
            Triple(broken, false, LaunchShape.OWN_BROKEN) to "битый файл не становится обычной сборкой",
            Triple(plain, false, LaunchShape.PLAIN) to "без файла — обычная сборка",
        )
        cases.forEach { (case, why) -> assertEquals(why, case.third, PackLaunches.shape(case.first, case.second)) }
    }

    @Test
    fun `корень описания — архив или его единственная обёртка`() {
        val flat = File(root, "flat").also { write(it, PhoneFit.PACK_LAUNCH_FILE); write(it, "libraries/a.jar") }
        val wrapped = File(root, "wrapped").also { write(it, "Pack/${PhoneFit.PACK_LAUNCH_FILE}") }
        val deep = File(root, "deep").also { write(it, "config/${PhoneFit.PACK_LAUNCH_FILE}"); write(it, "options.txt") }
        assertEquals("одна папка libraries рядом с файлом не уводит корень вглубь", flat, PackLaunches.launchRoot(flat))
        assertEquals(File(wrapped, "Pack"), PackLaunches.launchRoot(wrapped))
        assertNull("файл в подпапке — не описание запуска", PackLaunches.launchRoot(deep))
    }

    private fun installer(layout: GameLayout, profiles: ProfileStore) = PackInstaller(
        layout,
        profiles,
        Downloader(attempts = 1),
        PackVersionResolver { _, loader, _ -> throw IOException("загрузчик $loader не должен ставиться для своего запуска") },
    )

    private fun plan(source: PackSource) = PackPlan(
        name = "OneBlock",
        gameVersion = "1.7.10",
        loader = LoaderKind.FORGE,
        source = source,
        sourceId = "oneblock-metalabs",
    )

    @Test
    fun `сборка со своим запуском ставится без загрузчика и хранит описание`() = runBlocking {
        val layout = GameLayout(File(root, "game")).also { it.ensure() }
        val profiles = ProfileStore(layout)
        val tree = File(root, "tree").also {
            write(it, PhoneFit.PACK_LAUNCH_FILE, oneBlock())
            write(it, "libraries/a.jar")
            write(it, "config/a.cfg")
        }
        val keep = File(layout.root, "pack-keep/oneblock-metalabs").also { write(it, "saves/Мир/level.dat") }
        val profile = installer(layout, profiles).install(
            PackContent(plan(PackSource.MILLIDA_CATALOG), trees = listOf(tree), ownLaunch = true),
        )
        val dir = profiles.directory(profile)
        assertEquals("сборку запускает её описание, а не версия загрузчика", "1.7.10", profile.versionId)
        assertTrue("описание запуска обязано остаться в сборке каталога", File(dir, PhoneFit.PACK_LAUNCH_FILE).isFile)
        assertNotNull("запуск по описанию доверен сборке из каталога", PackLaunches.trusted(dir))
        assertTrue("сохранённый мир вернулся при новой установке", File(dir, "saves/Мир/level.dat").isFile)
        assertFalse("папка хранения опустела и убрана", keep.exists())
    }

    @Test
    fun `чужой архив не приносит описание запуска`() = runBlocking {
        val layout = GameLayout(File(root, "game2")).also { it.ensure() }
        val profiles = ProfileStore(layout)
        val tree = File(root, "mr").also { write(it, PhoneFit.PACK_LAUNCH_FILE, oneBlock()); write(it, "config/a.cfg") }
        val resolver = PackVersionResolver { game, _, _ -> PackVersionChoice("$game-forge") }
        val profile = PackInstaller(layout, profiles, Downloader(attempts = 1), resolver)
            .install(PackContent(plan(PackSource.MODRINTH), trees = listOf(tree), ownLaunch = true))
        assertFalse(
            "флаг своего запуска у не-каталожной сборки ничего не значит: файл убран",
            File(profiles.directory(profile), PhoneFit.PACK_LAUNCH_FILE).exists(),
        )
        assertEquals("обычная сборка ставит загрузчик", "1.7.10-forge", profile.versionId)
    }
}
