package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.packs.LaunchShape
import net.millida.launcher.core.packs.PackContent
import net.millida.launcher.core.packs.PackInstaller
import net.millida.launcher.core.packs.PackLaunch
import net.millida.launcher.core.packs.PackLaunches
import net.millida.launcher.core.packs.PackPlan
import net.millida.launcher.core.packs.PackSource
import net.millida.launcher.core.packs.PackVersionResolver
import net.millida.launcher.core.packs.PhoneFit
import net.millida.launcher.core.profile.ProfileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The launch description of the OneBlock client 26.10.20 as the archive ships
 * it, installed the way a catalogue install puts it on the phone, and every
 * fact the start reads from it. A build that loses any of these starts plain
 * 1.7.10 on Java 8 instead of the pack.
 */
class OneBlockLaunchSpecTest {

    private lateinit var root: File

    @Before
    fun start() {
        root = File(System.getProperty("java.io.tmpdir"), "millida-oneblock-spec-test-" + System.nanoTime())
        root.mkdirs()
    }

    @After
    fun stop() {
        root.deleteRecursively()
    }

    private val spec = """
        {"formatVersion":1,"slug":"oneblock-metalabs","mainClass":"com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread","vanillaAssets":"1.7.10","assetIndex":"1.7.10",
         "nativesByPlatform":{"windows-x86_64":"natives/windows/x64","windows-arm64":"natives/windows/arm64","linux-x86_64":"natives/linux/x64","linux-arm64":"natives/linux/arm64","macos-x86_64":"natives/macos/x64","macos-arm64":"natives/macos/arm64"},
         "classPath":["libraries","versions/1.7.10ANGELICA/1.7.10ANGELICA.jar"],
         "jvmArgs":["-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader","-Drfb.skipClassLoaderCheck=true","-Dforge.forceNoStencil=true","-Dfml.ignoreInvalidMinecraftCertificates=true","-Dfml.ignorePatchDiscrepancies=true","--enable-native-access=ALL-UNNAMED","--add-opens=java.base/java.io=ALL-UNNAMED","--add-opens=java.desktop/sun.awt=ALL-UNNAMED","--add-opens=java.sql.rowset/javax.sql.rowset.serial=ALL-UNNAMED","--add-opens=jdk.dynalink/jdk.dynalink.beans=ALL-UNNAMED","--add-opens=jdk.naming.dns/com.sun.jndi.dns=ALL-UNNAMED,java.naming"],
         "jvmArgsByPlatform":{"macos":["-XstartOnFirstThread"]},
         "gameArgs":["--username","${'$'}{auth_player_name}","--version","${'$'}{version_name}","--gameDir","${'$'}{game_directory}","--assetsDir","${'$'}{assets_root}","--assetIndex","${'$'}{assets_index_name}","--uuid","${'$'}{auth_uuid}","--accessToken","${'$'}{auth_access_token}","--userProperties","${'$'}{user_properties}","--userType","${'$'}{user_type}","--tweakClass","cpw.mods.fml.common.launcher.FMLTweaker"],
         "javaMajor":21,"minRamMb":4096}
    """.trimIndent()

    private val tree = listOf(
        PhoneFit.PACK_LAUNCH_FILE,
        "libraries/lwjgl3/lwjgl-3.4.2.jar",
        "libraries/lwjgl3/lwjgl-glfw-3.4.2.jar",
        "libraries/lwjgl3/lwjgl-opengl-3.4.2.jar",
        "libraries/lwjgl3/lwjgl-sdl-3.4.2.jar",
        "libraries/com/gtnewhorizons/retrofuturabootstrap/1.0.11/retrofuturabootstrap-1.0.11.jar",
        "libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar",
        "versions/1.7.10ANGELICA/1.7.10ANGELICA.jar",
        "mods/1.7.10/lwjgl3ify-3.0.0.jar",
        "config/lwjgl3ify.cfg",
        "natives/linux/x64/org/lwjgl/sdl/libSDL3.so",
        "natives/windows/x64/org/lwjgl/lwjgl.dll",
        "natives/macos/arm64/org/lwjgl/glfw/libglfw.dylib",
        "natives/jinput-dx8_64.dll",
    )

    private fun unpacked(): File = File(root, "unpacked").also { dir ->
        tree.forEach { path ->
            File(dir, path).also { it.parentFile.mkdirs() }.writeText(if (path == PhoneFit.PACK_LAUNCH_FILE) spec else "x")
        }
    }

    private fun installed(): Pair<File, PackLaunch> = runBlocking {
        val layout = GameLayout(File(root, "game")).also { it.ensure() }
        val profiles = ProfileStore(layout)
        val installer = PackInstaller(
            layout,
            profiles,
            Downloader(attempts = 1),
            PackVersionResolver { _, loader, _ -> throw IOException("загрузчик $loader не ставится для сборки со своим запуском") },
        )
        val plan = PackPlan(
            name = "OneBlock",
            gameVersion = "1.7.10",
            loader = LoaderKind.FORGE,
            source = PackSource.MILLIDA_CATALOG,
            sourceId = "oneblock-metalabs",
        )
        val profile = installer.install(PackContent(plan, trees = listOf(unpacked()), ownLaunch = true))
        val dir = profiles.directory(profile)
        val trusted = PackLaunches.trusted(dir)
        assertNotNull("сборка из каталога с описанием запуска обязана получить свой запуск, иначе стартует голая 1.7.10", trusted)
        dir to trusted!!
    }

    @Test
    fun `описание OneBlock 26-10-20 доходит до запуска целиком`() {
        val source = unpacked()
        assertEquals("архив с описанием в корне — своя сборка", source, PackLaunches.launchRoot(source))
        assertEquals("Java 21 есть в рантайме — сборка готова к запуску", LaunchShape.OWN_READY, PackLaunches.shape(source, legacyRuntime = true))

        val (dir, launch) = installed()
        val classpath = PackLaunches.classpath(dir, launch).map { it.relativeTo(dir).invariantSeparatorsPath }
        val cases = listOf<Triple<String, Any?, Any?>>(
            Triple("слаг из записи установки", "oneblock-metalabs", launch.slug),
            Triple("главный класс RetroFuturaBootstrap, не launchwrapper", "com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread", launch.mainClass),
            Triple("Java из описания, а не Java 8 версии 1.7.10", 21, launch.javaMajor),
            Triple("память не ниже минимума автора", 4096, launch.minRamMb),
            Triple("общие ассеты 1.7.10 качаются по vanillaAssets", "1.7.10", launch.vanillaAssets),
            Triple("индекс ассетов для --assetIndex", "1.7.10", launch.assetIndex),
            Triple("без ключа android нативы — общий путь, телефон берёт свои", "natives", launch.nativesDir),
            Triple("игровых аргументов столько, сколько в описании", 20, launch.gameArgs.size),
            Triple("свой загрузчик классов RFB доезжает до JVM", true, launch.jvmArgs.contains("-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader")),
            Triple("флаг macOS не попадает на телефон", false, launch.jvmArgs.contains("-XstartOnFirstThread")),
            Triple("окно через SDL3: в libraries есть lwjgl-sdl", true, PackLaunches.needsSdl(dir, launch)),
            Triple("ни одного нативного файла, чуждого телефону", null, PhoneFit.ownLaunchProblem(tree, legacyRuntime = true, sdlRuntime = true)),
            Triple(
                "classpath — все jar из libraries по порядку плюс клиент; настольный LWJGL уступает телефонному",
                listOf(
                    "libraries/com/gtnewhorizons/retrofuturabootstrap/1.0.11/retrofuturabootstrap-1.0.11.jar",
                    "libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar",
                    "versions/1.7.10ANGELICA/1.7.10ANGELICA.jar",
                ),
                classpath,
            ),
        )
        cases.forEach { (why, expected, actual) -> assertEquals(why, expected, actual) }
    }
}
