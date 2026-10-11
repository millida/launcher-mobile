package net.millida.launcher.core

import net.millida.launcher.core.hosting.HostingError
import net.millida.launcher.core.hosting.HostingPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostingPathsTest {

    private data class PathCase(val input: String, val expected: String?, val why: String)

    private fun rejected(why: String, input: String, block: () -> String) {
        try {
            val value = block()
            fail("«$input» принят как «$value»: $why")
        } catch (expected: HostingError) {
            assertTrue("«$input»: в отказе не назван исходный ввод — игрок не поймёт, что исправить", expected.message.orEmpty().contains(input))
        }
    }

    @Test
    fun `идентификатор сервера не может стать куском адреса запроса`() {
        assertEquals("пробелы вокруг id из буфера обмена срезаются", "srv-1", HostingPaths.serverId("  srv-1 "))
        assertEquals("обычный uuid проходит как есть", "6f1c0b52-1e1a-4c7e", HostingPaths.serverId("6f1c0b52-1e1a-4c7e"))
        val cases = listOf(
            "srv/../admin" to "слэш уводит запрос на чужой эндпоинт",
            "srv\\1" to "обратный слэш на части прокси равен прямому",
            ".." to "две точки — переход вверх по пути API",
            "srv..1" to "две точки внутри тоже отклоняются, разбирать их безопасность дороже",
        )
        for ((input, why) in cases) rejected(why, input) { HostingPaths.serverId(input) }
        try {
            HostingPaths.serverId("   ")
            fail("пустой id ушёл бы запросом на список всех серверов")
        } catch (expected: HostingError) {
            assertTrue("пустой id не объяснён: ${expected.message}", expected.message.orEmpty().contains("не указан"))
        }
    }

    @Test
    fun `путь на сервере нормализуется внутри папки сервера`() {
        val cases = listOf(
            PathCase("", ".", "пустой путь — корень сервера"),
            PathCase("  ", ".", "пробелы — тоже корень"),
            PathCase(".", ".", "точка — корень"),
            PathCase("server.properties", "server.properties", "файл в корне"),
            PathCase("plugins/Essentials/config.yml", "plugins/Essentials/config.yml", "вложенный путь не меняется"),
            PathCase("plugins\\Essentials\\config.yml", "plugins/Essentials/config.yml", "обратные слэши с Windows-привычки становятся прямыми"),
            PathCase("plugins/", "plugins", "хвостовой слэш не плодит второй ключ папки"),
            PathCase("plugins/./config.yml", "plugins/config.yml", "точка внутри пути схлопывается"),
            PathCase("plugins/../server.properties", "server.properties", "переход вверх внутри папки сервера разрешён"),
            PathCase("plugins/..", ".", "вернуться в корень — тоже корень"),
        )
        for (case in cases) {
            assertEquals("«${case.input}»: ${case.why}", case.expected, HostingPaths.remote(case.input))
        }
    }

    @Test
    fun `путь наружу папки сервера отклоняется до запроса`() {
        val cases = listOf(
            "/etc/passwd" to "абсолютный путь — чужие файлы ноды",
            "\\etc\\passwd" to "абсолютный путь обратными слэшами",
            "../other-server/world" to "соседний сервер на той же ноде",
            "plugins/../../secrets.yml" to "выход наружу через вложенную папку",
            "C:/Windows" to "путь с диском",
        )
        for ((input, why) in cases) rejected(why, input) { HostingPaths.remote(input) }
    }

    @Test
    fun `склейка папки и имени не даёт обойти проверку`() {
        val cases = listOf(
            Triple(".", "server.properties", "server.properties") to "файл в корне без «./» впереди",
            Triple("", "plugins", "plugins") to "пустая папка — корень",
            Triple("plugins", "", "plugins") to "пустое имя — сама папка",
            Triple("plugins", "Essentials/config.yml", "plugins/Essentials/config.yml") to "имя может нести подпапку",
            Triple("plugins\\", "a.jar", "plugins/a.jar") to "обратный слэш в папке",
        )
        for ((case, why) in cases) {
            assertEquals("${case.first} + ${case.second}: $why", case.third, HostingPaths.join(case.first, case.second))
        }
        rejected("имя с выходом наружу", "../../x") { HostingPaths.join("plugins", "../../x") }
        rejected("абсолютное имя", "/x") { HostingPaths.join("plugins", "/x") }
        rejected("папка с выходом наружу", "../x") { HostingPaths.join("../x", "a.txt") }
    }
}
