package net.millida.launcher.core

import kotlinx.coroutines.test.runTest
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.mojang.VersionResolver
import net.millida.launcher.core.packs.PackLaunch
import net.millida.launcher.core.packs.PackLaunches
import net.millida.launcher.core.packs.PhoneBlock
import net.millida.launcher.core.runtime.InstalledSdl
import net.millida.launcher.core.runtime.RuntimeInstaller
import net.millida.launcher.core.runtime.RuntimeManifest
import net.millida.launcher.core.runtime.SdlGraphics
import net.millida.launcher.core.runtime.SdlLaunch
import net.millida.launcher.core.runtime.WindowChoice
import net.millida.launcher.core.runtime.WindowChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The SDL3 path is a beta beside the GLFW one. What is pinned: which packs
 * take it, that every other pack keeps the GLFW path untouched, and that the
 * LWJGL of the component wins over the pack's own and over the GLFW pack's.
 */
class SdlLaunchTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `выбор оконного слоя`() {
        data class Case(val needsSdl: Boolean, val beta: Boolean, val sdl: Boolean, val expected: WindowChoice, val why: String)

        val refused = WindowChoice.Refused(PhoneBlock.SDL_WINDOW)
        val cases = listOf(
            Case(false, false, false, WindowChoice.Glfw, "обычная сборка идёт через GLFW, как раньше"),
            Case(false, true, true, WindowChoice.Glfw, "бета и SDL3 не уводят обычную сборку с проверенного пути"),
            Case(true, true, true, WindowChoice.Sdl, "OneBlock с бетой и SDL3 — путь SDL3"),
            Case(true, false, true, refused, "без беты SDL3 не берётся"),
            Case(true, true, false, refused, "SDL3 нет в манифесте — понятный отказ вместо падения игры"),
            Case(true, false, false, refused, "ни беты, ни SDL3"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, WindowChoices.choose(case.needsSdl, case.beta, case.sdl))
        }
    }

    private fun installedSdl(): InstalledSdl {
        val dir = temp.newFolder("sdl3")
        val jars = listOf("lwjgl-3.4.2.jar", "lwjgl-sdl-3.4.2.jar").map { File(dir, it).apply { writeText("jar") } }
        return InstalledSdl(dir, File(dir, "libSDL3.so"), jars, "3.4.12", "3.4.2")
    }

    @Test
    fun `библиотеки компонента SDL3 идут первыми`() {
        val sdl = installedSdl()
        val game = listOf(File("/pack/libraries/forge.jar"), File("/pack/client.jar"))
        val classpath = SdlLaunch.classpath(sdl, game)
        assertEquals("LWJGL компонента раньше классов игры", sdl.classpath, classpath.take(2))
        assertEquals("классы игры сохраняют свой порядок", game, classpath.drop(2))

        val natives = File("/runtime/native")
        val path = SdlLaunch.libraryPath(sdl, natives).split(':')
        assertEquals(
            "нативы LWJGL компонента находятся раньше LWJGL 3.3 из GLFW-пакета: смесь версий падает на первом вызове",
            listOf(sdl.dir.absolutePath, natives.absolutePath),
            path,
        )
        assertEquals(
            "игра грузит именно ту libSDL3.so, которую приложение уже связало с Android",
            listOf("-Dorg.lwjgl.sdl.libname=${sdl.library.absolutePath}"),
            SdlLaunch.systemProperties(sdl, SdlGraphics.TRANSLATED),
        )
        assertEquals(
            "Angelica 2.2+ без профиля ES отвергает контекст слоя перевода (нет бита core) и пересоздаёт окно, а второе окно на Android не создаётся",
            listOf("-Dorg.lwjgl.sdl.libname=${sdl.library.absolutePath}", "-Dangelica.glProfile=es"),
            SdlLaunch.systemProperties(sdl, SdlGraphics.NATIVE_GLES),
        )
    }

    @Test
    fun `shaderc и spirv-cross берутся из компонента SDL3`() {
        data class Case(val files: List<String>, val graphics: SdlGraphics, val expected: (InstalledSdl) -> List<String>, val why: String)

        fun libname(sdl: InstalledSdl, module: String, file: String) = "-Dorg.lwjgl.$module.libname=${File(sdl.dir, file).absolutePath}"
        fun sdlName(sdl: InstalledSdl) = "-Dorg.lwjgl.sdl.libname=${sdl.library.absolutePath}"

        val cases = listOf(
            Case(
                listOf("libshaderc.so", "libspirv-cross.so"),
                SdlGraphics.NATIVE_GLES,
                { listOf(sdlName(it), libname(it, "shaderc", "libshaderc.so"), libname(it, "spvc", "libspirv-cross.so"), "-Dangelica.glProfile=es") },
                "Angelica в режиме ES переводит шейдеры через shaderc и spirv-cross: обе библиотеки LWJGL 3.4.2 из компонента, иначе NoClassDefFoundError или нет функции",
            ),
            Case(
                listOf("libshaderc.so", "libspirv-cross.so"),
                SdlGraphics.TRANSLATED,
                { listOf(sdlName(it), libname(it, "shaderc", "libshaderc.so"), libname(it, "spvc", "libspirv-cross.so")) },
                "и на слое перевода модули LWJGL 3.4.2 не берут библиотеки рантайма, собранные под другую версию",
            ),
            Case(
                listOf("libshaderc.so"),
                SdlGraphics.TRANSLATED,
                { listOf(sdlName(it), libname(it, "shaderc", "libshaderc.so")) },
                "называется только то, что в компоненте реально лежит",
            ),
            Case(
                emptyList(),
                SdlGraphics.NATIVE_GLES,
                { listOf(sdlName(it), "-Dangelica.glProfile=es") },
                "старый компонент без этих библиотек не получает путей в никуда",
            ),
        )
        cases.forEachIndexed { index, case ->
            val dir = temp.newFolder("sdl3-bundled-$index")
            case.files.forEach { File(dir, it).writeText("so") }
            val sdl = InstalledSdl(dir, File(dir, "libSDL3.so"), emptyList(), "3.4.12", "3.4.2")
            assertEquals(case.why, case.expected(sdl), SdlLaunch.systemProperties(sdl, case.graphics))
            assertEquals(case.why, case.files.toSet(), SdlLaunch.bundledLibraries(sdl).values.map { it.name }.toSet())
        }
        assertEquals(
            "LWJGL 3.4.2 по умолчанию ищет именно эти имена: spvc — libspirv-cross.so, не libspirv-cross-c-shared.so рантайма",
            mapOf("shaderc" to "libshaderc.so", "spvc" to "libspirv-cross.so"),
            SdlLaunch.BUNDLED_LIBRARIES,
        )
    }

    @Test
    fun `загрузчик Vulkan скрыт от игры на пути SDL3`() {
        data class Case(val sdl: Boolean, val expected: String?, val why: String)

        val cases = listOf(
            Case(
                true,
                null,
                "26.3 с найденным загрузчиком Vulkan грузит VMA, а VMA для Android в компоненте нет — игра падает при загрузке библиотек вместо OpenGL",
            ),
            Case(
                false,
                "-Dorg.lwjgl.vulkan.libname=libvulkan.so",
                "путь GLFW остаётся как был: имя задано явно, по умолчанию LWJGL ищет libvulkan.so.1, которой на Android нет",
            ),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, SdlLaunch.vulkanLoader(case.sdl))
        }
    }

    @Test
    fun `окружение SDL3 указывает на рендерер`() {
        val renderer = temp.newFile("libgl4es_114.so")
        val environment = SdlLaunch.environment(renderer, SdlGraphics.TRANSLATED)
        assertEquals("функции GL берутся из слоя перевода, иначе 1.7.10 рисует в голый GLES", renderer.absolutePath, environment[SdlLaunch.HINT_OPENGL_LIBRARY])
        assertEquals("геймпады идут через слой ввода лаунчера, HIDAPI выключен", "0", environment[SdlLaunch.HINT_JOYSTICK_HIDAPI])
        assertFalse(
            "несуществующий рендерер не подставляется: SDL откроет системный GLES и скажет об этом в журнале",
            SdlLaunch.environment(File(temp.root, "missing.so"), SdlGraphics.TRANSLATED).containsKey(SdlLaunch.HINT_OPENGL_LIBRARY),
        )
        val native = SdlLaunch.environment(renderer, SdlGraphics.NATIVE_GLES)
        assertFalse(
            "Angelica в режиме ES рисует на GLES телефона: функции GL из слоя перевода в её контексте ломают состояние",
            native.containsKey(SdlLaunch.HINT_OPENGL_LIBRARY),
        )
        assertEquals("HIDAPI выключен и на прямом GLES", "0", native[SdlLaunch.HINT_JOYSTICK_HIDAPI])
        assertEquals("путь GLFW и слой перевода по-прежнему отдают LWJGL рендерер", renderer.absolutePath, SdlLaunch.openglLibrary(renderer, null))
        assertEquals("слой перевода на пути SDL — тот же рендерер", renderer.absolutePath, SdlLaunch.openglLibrary(renderer, SdlGraphics.TRANSLATED))
        assertEquals(
            "на прямом GLES LWJGL не должен сам открыть Krypton рядом с контекстом SDL",
            SdlLaunch.SYSTEM_GLES,
            SdlLaunch.openglLibrary(renderer, SdlGraphics.NATIVE_GLES),
        )
    }

    @Test
    fun `прослойка GLES подставляется только на прямом GLES`() {
        data class Case(val graphics: SdlGraphics, val shimExists: Boolean, val expected: Boolean, val why: String)

        val cases = listOf(
            Case(SdlGraphics.NATIVE_GLES, true, true, "Angelica в режиме ES грузит текстуры 1.7.10 в BGRA, без прослойки GLES их отвергает и текст рисуется сплошными плашками"),
            Case(SdlGraphics.NATIVE_GLES, false, false, "без файла прослойки SDL не получает путь в никуда и открывает системный GLES"),
            Case(SdlGraphics.TRANSLATED, true, false, "слой перевода сам принимает BGRA, и SDL берёт функции из рендерера, а не из прослойки"),
            Case(SdlGraphics.TRANSLATED, false, false, "слой перевода без прослойки не меняется"),
        )
        cases.forEachIndexed { index, case ->
            val dir = temp.newFolder("native-$index")
            val shim = File(dir, SdlLaunch.GLES_SHIM).apply { if (case.shimExists) writeText("so") }
            val expected = if (case.expected) {
                mapOf(SdlLaunch.HINT_EGL_LIBRARY to shim.absolutePath)
            } else {
                emptyMap()
            }
            assertEquals(case.why, expected, SdlLaunch.glesShim(shim, case.graphics))
        }
        assertEquals(
            "прослойка ставится только как библиотека EGL: с SDL_OPENGL_LIBRARY на ней игра падала на первом вызове GL",
            listOf("SDL_EGL_LIBRARY", "libmillida_gles.so"),
            listOf(SdlLaunch.HINT_EGL_LIBRARY, SdlLaunch.GLES_SHIM),
        )
    }

    @Test
    fun `графика SDL3 по версии Angelica`() {
        data class Case(val file: String, val expected: Boolean, val why: String)

        val cases = listOf(
            Case("angelica-2.2.5.jar", true, "OneBlock: профиль ES есть с 2.2.0"),
            Case("angelica-2.2.0.jar", true, "первая версия с профилем ES и проверкой бита core"),
            Case("Angelica-3.0.1-beta.jar", true, "регистр имени и суффиксы сборки не мешают"),
            Case("angelica-2.1.9.jar", false, "до 2.2 профиля ES нет, свойство проигнорируется, а голый GLES без перевода не нарисует 1.7.10"),
            Case("angelica-2.2.5.jar.disabled", false, "выключенный мод не участвует"),
            Case("AngelicaCompact-1.0.jar", false, "аддон с похожим именем — не сама Angelica"),
            Case("angelica-99999999999.0.jar", false, "переполненный номер не принимается за новую версию"),
            Case("lwjgl3ify-3.0.31-meta.jar", false, "lwjgl3ify сам по себе не меняет графику"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, SdlLaunch.angelicaWithGles(case.file))
        }

        val withAngelica = temp.newFolder()
        touch(withAngelica, "mods/angelica-2.2.5.jar")
        assertEquals("сборка с Angelica 2.2+ идёт на GLES телефона", SdlGraphics.NATIVE_GLES, SdlLaunch.graphics(withAngelica))
        assertEquals("без папки mods остаётся слой перевода", SdlGraphics.TRANSLATED, SdlLaunch.graphics(temp.newFolder()))
    }

    private fun pack(classPath: List<String>, nativesDir: String = "natives") = PackLaunch(
        slug = "oneblock-metalabs",
        mainClass = "com.gtnewhorizons.retrofuturabootstrap.Main",
        assetIndex = "1.7.10",
        assetsDir = "assets",
        nativesDir = nativesDir,
        classPath = classPath,
        jvmArgs = emptyList(),
        gameArgs = emptyList(),
        vanillaAssets = "",
        javaMajor = 21,
        minRamMb = 4096,
        maxRamMb = 0,
        needsToken = false,
    )

    private fun touch(root: File, relative: String) = File(root, relative).apply {
        parentFile.mkdirs()
        writeText("x")
    }

    @Test
    fun `сборка с SDL3 узнаётся по своим файлам`() {
        data class Case(val files: List<String>, val classPath: List<String>, val expected: Boolean, val why: String)

        val cases = listOf(
            Case(
                listOf("libraries/org/lwjgl/lwjgl-sdl/3.4.2/lwjgl-sdl-3.4.2.jar", "libraries/forge.jar"),
                listOf("libraries"),
                true,
                "привязка LWJGL к SDL в списке классов",
            ),
            Case(listOf("libraries/forge.jar", "natives/windows/x64/org/lwjgl/sdl/SDL3.dll"), listOf("libraries"), true, "библиотека SDL3 в нативах"),
            Case(
                listOf("libraries/org/lwjgl/lwjgl-glfw/3.3.3/lwjgl-glfw-3.3.3.jar", "libraries/forge.jar", "mods/lwjgl3ify-3.0.31.jar"),
                listOf("libraries"),
                false,
                "GLFW-сборка остаётся на GLFW, мод lwjgl3ify сам по себе не SDL",
            ),
        )
        cases.forEach { case ->
            val root = temp.newFolder()
            case.files.forEach { touch(root, it) }
            assertEquals(case.why, case.expected, PackLaunches.needsSdl(root, pack(case.classPath)))
        }
    }

    @Test
    fun `библиотеки LWJGL сборки уходят из списка классов, SDL3 замечен до этого`() {
        val root = temp.newFolder()
        touch(root, "libraries/org/lwjgl/lwjgl-sdl/3.4.2/lwjgl-sdl-3.4.2.jar")
        touch(root, "libraries/org/lwjgl/lwjgl/3.4.2/lwjgl-3.4.2.jar")
        val forge = touch(root, "libraries/net/minecraftforge/forge.jar")
        val spec = pack(listOf("libraries"))
        assertTrue(PackLaunches.needsSdl(root, spec))
        assertEquals("LWJGL сборки заменяет компонент телефона", listOf(forge.canonicalFile), PackLaunches.classpath(root, spec).map { it.canonicalFile })
    }

    @Test
    fun `версия игры с окном SDL3 узнаётся по библиотекам`() {
        data class Case(val libraries: List<String>, val expected: Boolean, val why: String)

        val cases = listOf(
            Case(listOf("org.lwjgl:lwjgl:3.4.3", "org.lwjgl:lwjgl-sdl:3.4.3"), true, "26.3 открывает окно через SDL3, путь GLFW роняет её на ClassNotFoundException"),
            Case(listOf("org.lwjgl:lwjgl-sdl:3.4.3:natives-linux"), true, "натив SDL с классификатором — тот же признак"),
            Case(listOf("org.lwjgl:lwjgl-sdl:3.5.0-SNAPSHOT:natives-macos-arm64@jar"), true, "снапшоты 26.4 могут взять сборку LWJGL новее"),
            Case(listOf(" org.lwjgl:lwjgl-sdl:3.4.3 "), true, "пробелы по краям координаты не прячут SDL3"),
            Case(listOf("org.lwjgl:lwjgl:3.3.3", "org.lwjgl:lwjgl-glfw:3.3.3"), false, "1.21 и раньше остаются на проверенном пути GLFW"),
            Case(listOf("org.lwjgl.lwjgl:lwjgl-sdl:2.9.4"), false, "чужая группа — не привязка LWJGL 3 к SDL"),
            Case(listOf("org.lwjgl:lwjgl-sdlx:3.4.3"), false, "похожее имя артефакта не уводит версию на SDL3"),
            Case(listOf("org.lwjgl:lwjgl-sdl"), true, "координата без версии всё ещё называет привязку SDL"),
            Case(emptyList(), false, "версия без библиотек идёт как раньше"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, SdlLaunch.versionNeedsSdl(case.libraries))
        }
    }

    @Test
    fun `Fabric поверх ванили с SDL3 тоже идёт через SDL3`() = runTest {
        val layout = GameLayout(temp.newFolder("game")).also { it.ensure() }
        File(layout.caches, "version_manifest_v2.json").writeText("{}")
        fun version(id: String, json: String) = layout.versionJson(id).apply {
            parentFile.mkdirs()
            writeText(json)
        }
        version(
            "26.3",
            """{"id": "26.3", "javaVersion": {"majorVersion": 25}, "libraries": [
                 {"name": "com.mojang:brigadier:1.3.10", "downloads": {"artifact": {"path": "com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar", "url": "https://example.invalid/b.jar"}}},
                 {"name": "org.lwjgl:lwjgl-sdl:3.4.3", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-sdl/3.4.3/lwjgl-sdl-3.4.3.jar", "url": "https://example.invalid/sdl.jar"}}}
               ]}""",
        )
        version("fabric-26.3", """{"id": "fabric-26.3", "inheritsFrom": "26.3", "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient", "libraries": []}""")
        version("1.21.1", """{"id": "1.21.1", "libraries": [{"name": "org.lwjgl:lwjgl-glfw:3.3.3"}]}""")
        val resolver = VersionResolver(layout, Downloader(attempts = 1), manifestUrl = "https://127.0.0.1:1/never.json")

        val fabric = resolver.resolve("fabric-26.3")
        assertTrue("признак SDL3 наследуется от ванили, иначе Fabric 26.3 падает на старте", fabric.sdlWindow)
        assertFalse(
            "LWJGL версии не попадает в список классов: его даёт компонент SDL3, две сборки LWJGL ломают запуск",
            fabric.libraries.any { it.name.startsWith("org.lwjgl") },
        )
        assertFalse("версия на GLFW остаётся на GLFW", resolver.resolve("1.21.1").sdlWindow)
    }

    @Test
    fun `старый манифест без SDL3 читается, новый говорит о SDL3`() {
        val old = MojangJson.decodeFromString(RuntimeManifest.serializer(), """{"revision": 7, "jre": [], "bridge": [], "renderers": []}""")
        assertTrue("манифест до SDL3 не ломает разбор", old.sdl.isEmpty())

        val withSdl = MojangJson.decodeFromString(
            RuntimeManifest.serializer(),
            """{"revision": 8, "sdl": [{"id": "sdl3", "url": "https://x/sdl3-0123.zip", "sha256": "ab", "sdlVersion": "3.4.12",
               "lwjglVersion": "3.4.2", "classpath": ["lwjgl-3.4.2.jar"], "unknownField": 1}]}""",
        )
        assertEquals("libSDL3.so", withSdl.sdl.single().library)
        assertEquals("3.4.2", withSdl.sdl.single().lwjglVersion)
    }

    @Test
    fun `доступность SDL3 читается без сети`() {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        val installer = RuntimeInstaller(layout, Downloader(attempts = 1), manifestUrl = "https://127.0.0.1:1/never.json")
        assertFalse("нет ни манифеста, ни компонента — SDL3 недоступен", installer.sdlAvailable())

        File(layout.caches, "runtime-manifest.json").writeText("""{"sdl": [{"id": "sdl3", "url": "u", "sha256": "s"}]}""")
        val fresh = RuntimeInstaller(layout, Downloader(attempts = 1), manifestUrl = "https://127.0.0.1:1/never.json")
        assertTrue("последний увиденный манифест называет SDL3", fresh.sdlAvailable())

        File(layout.caches, "runtime-manifest.json").writeText("""{"sdl": []}""")
        val unpacked = File(layout.runtimes, "sdl3").apply { mkdirs() }
        File(unpacked, ".millida-sha256").writeText("s")
        val offline = RuntimeInstaller(layout, Downloader(attempts = 1), manifestUrl = "https://127.0.0.1:1/never.json")
        assertTrue("уже распакованный компонент работает и без манифеста", offline.sdlAvailable())
    }
}
