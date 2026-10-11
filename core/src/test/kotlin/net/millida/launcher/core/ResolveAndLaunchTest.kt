package net.millida.launcher.core

import kotlinx.coroutines.test.runTest
import net.millida.launcher.core.account.OfflineAuth
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.launch.LaunchRequest
import net.millida.launcher.core.launch.LaunchSpecBuilder
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.VersionResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResolveAndLaunchTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun layoutWith(vararg versions: Pair<String, String>): GameLayout {
        val layout = GameLayout(temp.newFolder("game"))
        layout.ensure()
        versions.forEach { (id, json) ->
            val file = layout.versionJson(id)
            file.parentFile.mkdirs()
            file.writeText(json)
        }
        return layout
    }

    private val vanillaJson = """
        {
          "id": "1.20.1",
          "mainClass": "net.minecraft.client.main.Main",
          "assets": "5",
          "assetIndex": {"id": "5", "sha1": "aaa", "size": 10, "totalSize": 100, "url": "https://example.invalid/5.json"},
          "javaVersion": {"component": "java-runtime-gamma", "majorVersion": 17},
          "downloads": {"client": {"sha1": "bbb", "size": 20, "url": "https://example.invalid/client.jar"}},
          "libraries": [
            {"name": "com.mojang:brigadier:1.0.18",
             "downloads": {"artifact": {"path": "com/mojang/brigadier/1.0.18/brigadier-1.0.18.jar", "sha1": "ccc", "size": 5, "url": "https://example.invalid/brigadier.jar"}}},
            {"name": "org.lwjgl:lwjgl-glfw:3.3.1",
             "downloads": {"artifact": {"path": "org/lwjgl/lwjgl-glfw/3.3.1/lwjgl-glfw-3.3.1.jar", "sha1": "ddd", "size": 5, "url": "https://example.invalid/glfw.jar"}}},
            {"name": "org.lwjgl:lwjgl-glfw:3.3.1",
             "downloads": {"classifiers": {"natives-windows": {"path": "native.jar", "sha1": "eee", "size": 1, "url": "https://example.invalid/native.jar"}}},
             "natives": {"windows": "natives-windows"}}
          ],
          "arguments": {
            "game": ["--username", "${'$'}{auth_player_name}", "--version", "${'$'}{version_name}",
                     {"rules": [{"action": "allow", "os": {"name": "osx"}}], "value": "--macos-only"}],
            "jvm": ["-Djava.library.path=${'$'}{natives_directory}", "-cp", "${'$'}{classpath}"]
          }
        }
    """.trimIndent()

    private val fabricJson = """
        {
          "id": "1.20.1-fabric-0.16.9",
          "inheritsFrom": "1.20.1",
          "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
          "libraries": [
            {"name": "net.fabricmc:fabric-loader:0.16.9", "url": "https://maven.fabricmc.net/"}
          ],
          "arguments": {"jvm": ["-DFabricMcEmu=net.minecraft.client.main.Main"]}
        }
    """.trimIndent()

    @Test
    fun `сборка с загрузчиком наследует ваниль и меняет главный класс`() = runTest {
        val layout = layoutWith("1.20.1" to vanillaJson, "1.20.1-fabric-0.16.9" to fabricJson)
        val resolver = VersionResolver(layout, Downloader())

        val version = resolver.resolve("1.20.1-fabric-0.16.9")

        assertEquals(
            "главный класс должен приехать от загрузчика, иначе игра запустится без модов",
            "net.fabricmc.loader.impl.launch.knot.KnotClient",
            version.mainClass,
        )
        assertEquals("клиентский jar остаётся ванильным", "1.20.1", version.jarVersionId)
        assertEquals("версия Java берётся из ванили", 17, version.javaMajor)

        val names = version.libraries.map { it.name }
        assertTrue("библиотека загрузчика обязана попасть в сборку", names.any { it.startsWith("net.fabricmc:fabric-loader") })
        assertTrue("общая библиотека игры нужна", names.any { it.startsWith("com.mojang:brigadier") })
        assertFalse("настольный lwjgl на телефоне только ломает запуск", names.any { it.contains("lwjgl") })

        val loaderLibrary = version.libraries.first { it.name.startsWith("net.fabricmc:fabric-loader") }
        assertEquals(
            "адрес библиотеки без блока downloads собирается из url репозитория",
            "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar",
            loaderLibrary.url,
        )

        assertTrue(
            "аргумент загрузчика должен добавиться к ванильным",
            version.jvmArguments.contains("-DFabricMcEmu=net.minecraft.client.main.Main"),
        )
        assertFalse(
            "аргумент только для macOS на телефоне недопустим",
            version.gameArguments.contains("--macos-only"),
        )
    }

    @Test
    fun `аргументы запуска подставляют данные сессии`() = runTest {
        val layout = layoutWith("1.20.1" to vanillaJson)
        val resolver = VersionResolver(layout, Downloader())
        val version = resolver.resolve("1.20.1")
        val session = OfflineAuth.session("Steve")
        val gameDir = temp.newFolder("profile")

        val spec = LaunchSpecBuilder(layout).build(
            LaunchRequest(
                version = version,
                session = session,
                gameDir = gameDir,
                memoryMb = 1536,
                windowWidth = 1280,
                windowHeight = 720,
            )
        )

        assertTrue("ник обязан попасть в аргументы", spec.gameArgs.contains("Steve"))
        assertFalse(
            "неподставленных заглушек остаться не должно — игра примет их как ник",
            spec.gameArgs.any { it.contains("\${") } || spec.jvmArgs.any { it.contains("\${") },
        )
        assertTrue("память задаётся ровно так, как посчитал лаунчер", spec.jvmArgs.contains("-Xmx1536M"))
        assertFalse(
            "классpath не передаётся аргументом: его ставит хост виртуальной машины",
            spec.jvmArgs.contains("-cp"),
        )
        assertTrue(
            "путь к папке сборки обязателен, иначе миры уедут в общий каталог",
            spec.gameArgs.contains(gameDir.absolutePath),
        )
        assertEquals(
            "клиентский jar замыкает classpath",
            layout.versionJar("1.20.1").absolutePath,
            spec.classpath.last().absolutePath,
        )
        assertTrue(
            "разделитель classpath на Android — двоеточие",
            spec.classpathString().contains(":"),
        )
    }

    @Test
    fun `оффлайн-ник даёт устойчивый идентификатор третьей версии`() {
        val first = OfflineAuth.offlineUuid("Steve")
        val second = OfflineAuth.offlineUuid("Steve")
        val other = OfflineAuth.offlineUuid("Alex")

        assertEquals("один и тот же ник обязан давать один и тот же идентификатор", first, second)
        assertTrue("разные ники не должны совпадать", first != other)
        assertEquals("версия идентификатора должна быть 3, как у оффлайн-режима игры", '3', first[12])

        data class Case(val nick: String, val valid: Boolean, val why: String)

        val cases = listOf(
            Case("Steve", true, "обычный ник"),
            Case("ab", false, "короче трёх знаков игра не принимает"),
            Case("a".repeat(17), false, "длиннее шестнадцати знаков игра не принимает"),
            Case("Стив", false, "кириллица в нике не проходит проверку сервера"),
            Case("no-dash", false, "дефис в нике недопустим"),
            Case("with_underscore", true, "подчёркивание разрешено"),
        )
        for (case in cases) {
            assertEquals("ник ${case.nick}: ${case.why}", case.valid, OfflineAuth.validNick(case.nick))
        }
    }

    @Test
    fun `план памяти не обещает больше, чем есть у устройства`() {
        data class Case(val total: Int, val available: Int, val mods: Int, val maxHeap: Int, val warned: Boolean)

        val cases = listOf(
            Case(total = 8192, available = 5000, mods = 0, maxHeap = 1536, warned = false),
            Case(total = 8192, available = 5000, mods = 40, maxHeap = 2048, warned = false),
            Case(total = 4096, available = 1600, mods = 40, maxHeap = 512, warned = true),
            Case(total = 3072, available = 900, mods = 0, maxHeap = 512, warned = true),
        )

        for (case in cases) {
            val plan = MemoryTuning.plan(
                DeviceMemory(case.total, case.available),
                LoaderKind.FABRIC,
                case.mods,
            )
            assertTrue(
                "на устройстве с ${case.available} МБ нельзя обещать ${plan.heapMb} МБ",
                plan.heapMb <= case.maxHeap,
            )
            assertEquals(
                "предупреждение о нехватке памяти обязано появляться до запуска, а не после гибели процесса",
                case.warned,
                plan.warning != null,
            )
        }
    }

    @Test
    fun `сборка профиля живёт в своей папке`() {
        val layout = layoutWith()
        val store = net.millida.launcher.core.profile.ProfileStore(layout)
        val profile = net.millida.launcher.core.profile.Profile(
            id = store.newId("Моя сборка"),
            name = "Моя сборка",
            gameVersion = "1.20.1",
            loader = LoaderKind.FABRIC,
            versionId = "1.20.1-fabric-0.16.9",
        )
        store.upsert(profile)

        val restored = store.load().single()
        assertEquals("сборка обязана переживать перезапуск", profile.versionId, restored.versionId)

        val dir = store.directory(profile)
        assertTrue("папка сборки создаётся сразу", dir.isDirectory)
        assertTrue("папка сборки лежит внутри каталога сборок", SafePath.isInside(layout.profiles, dir))

        val second = store.newId("Моя сборка")
        assertTrue("повторное имя не должно занимать ту же папку", second != profile.id)

        assertTrue("удаление убирает сборку из списка", store.remove(profile.id).isEmpty())
        assertTrue("файлы сборки удаление списка не трогает", File(dir.absolutePath).exists())
    }
}
