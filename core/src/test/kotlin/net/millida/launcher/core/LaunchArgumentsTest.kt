package net.millida.launcher.core

import net.millida.launcher.core.account.GameSession
import net.millida.launcher.core.account.OfflineAuth
import net.millida.launcher.core.launch.LaunchRequest
import net.millida.launcher.core.launch.LaunchSpecBuilder
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.ResolvedVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LaunchArgumentsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun version(game: List<String>, jvm: List<String>) = ResolvedVersion(
        id = "1.20.1",
        jarVersionId = "1.20.1",
        mainClass = "net.minecraft.client.main.Main",
        assetsId = "5",
        assetIndex = null,
        clientJar = null,
        libraries = emptyList(),
        gameArguments = game,
        jvmArguments = jvm,
        javaMajor = 17,
        lwjglVersion = "3.3.3",
    )

    private fun build(
        game: List<String>,
        jvm: List<String> = emptyList(),
        session: GameSession = OfflineAuth.session("Steve"),
    ) = LaunchSpecBuilder(GameLayout(temp.newFolder()).also { it.ensure() }).build(
        LaunchRequest(
            version = version(game, jvm),
            session = session,
            gameDir = temp.newFolder(),
            memoryMb = 1024,
            windowWidth = 1600,
            windowHeight = 720,
        )
    )

    @Test
    fun `флаг без значения выбрасывается вместе со значением`() {
        val spec = build(
            listOf(
                "--username", "\${auth_player_name}",
                "--uuid", "\${auth_uuid}",
                "--accessToken", "\${auth_access_token}",
                "--clientId", "\${clientid}",
                "--xuid", "\${auth_xuid}",
                "--userType", "\${user_type}",
            )
        )

        assertFalse(
            "флаг --clientId остался без значения: игра прочитает как значение следующий флаг",
            spec.gameArgs.contains("--clientId"),
        )
        assertFalse("флаг --xuid без значения так же опасен", spec.gameArgs.contains("--xuid"))

        val tokenIndex = spec.gameArgs.indexOf("--accessToken")
        assertTrue("токен нужен даже в оффлайне: игра требует непустое значение", tokenIndex >= 0)
        assertEquals(
            "у оффлайн-аккаунта вместо пустоты подставляется заглушка",
            "0",
            spec.gameArgs[tokenIndex + 1],
        )

        val userTypeIndex = spec.gameArgs.indexOf("--userType")
        assertEquals("тип аккаунта обязан доехать", "legacy", spec.gameArgs[userTypeIndex + 1])

        val nameIndex = spec.gameArgs.indexOf("--username")
        assertEquals("ник обязан доехать", "Steve", spec.gameArgs[nameIndex + 1])
    }

    @Test
    fun `путь к классам не уходит аргументом виртуальной машины`() {
        val spec = build(
            game = emptyList(),
            jvm = listOf("-cp", "\${classpath}", "-Dtest=1", "-Djava.library.path=\${natives_directory}"),
        )

        assertFalse("флаг -cp ставит хост, а не описание версии", spec.jvmArgs.contains("-cp"))
        assertFalse(
            "путь к классам не должен остаться отдельным аргументом — иначе он читается как главный класс",
            spec.jvmArgs.any { it.contains("versions") && !it.startsWith("-") },
        )
        assertTrue("прочие аргументы версии сохраняются", spec.jvmArgs.contains("-Dtest=1"))
        assertFalse(
            "путь к нативам задаёт слой запуска: вторая копия того же свойства перебила бы первую",
            spec.jvmArgs.any { it.startsWith("-Djava.library.path") },
        )
        assertEquals("память задаёт лаунчер", 1, spec.jvmArgs.count { it == "-Xmx1024M" })
    }

    @Test
    fun `лицензионная сессия отдаёт живой токен`() {
        val session = GameSession(nick = "Alex", uuid = "u", accessToken = "live-token", xuid = "42")
        val spec = build(
            listOf("--accessToken", "\${auth_access_token}", "--xuid", "\${auth_xuid}", "--userType", "\${user_type}"),
            session = session,
        )

        assertEquals("live-token", spec.gameArgs[spec.gameArgs.indexOf("--accessToken") + 1])
        assertEquals("42", spec.gameArgs[spec.gameArgs.indexOf("--xuid") + 1])
        assertEquals("msa", spec.gameArgs[spec.gameArgs.indexOf("--userType") + 1])
    }

    @Test
    fun `при нехватке памяти запуск запрещён`() {
        val tight = MemoryTuning.plan(DeviceMemory(3072, 1000), LoaderKind.FABRIC, 10)
        assertFalse("на 1000 МБ свободной памяти запускать нельзя", tight.canLaunch)
        assertTrue("причина обязана быть текстом для человека", !tight.warning.isNullOrBlank())

        val roomy = MemoryTuning.plan(DeviceMemory(8192, 4800), LoaderKind.FABRIC, 10)
        assertTrue("на 4800 МБ свободной памяти запуск разрешён", roomy.canLaunch)
    }
}
