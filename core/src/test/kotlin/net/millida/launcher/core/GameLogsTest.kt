package net.millida.launcher.core

import net.millida.launcher.core.logs.GameLogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPOutputStream

class GameLogsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var layout: GameLayout
    private lateinit var profileDir: File
    private lateinit var logs: GameLogs

    private fun prepare() {
        layout = GameLayout(temp.newFolder()).also { it.ensure() }
        profileDir = layout.profileDir("build").also { it.mkdirs() }
        logs = GameLogs(layout)
    }

    private fun logFile(name: String, text: String, modified: Long): File {
        val folder = File(profileDir, "logs").also { it.mkdirs() }
        val target = File(folder, name)
        if (name.endsWith(".gz")) {
            GZIPOutputStream(target.outputStream()).use { it.write(text.toByteArray()) }
        } else {
            target.writeText(text)
        }
        target.setLastModified(modified)
        return target
    }

    @Test
    fun `список логов начинается с latest и дальше идёт по свежести`() {
        prepare()
        logFile("2026-09-01-1.log.gz", "старый", 1_000_000)
        logFile("latest.log", "текущий", 2_000_000)
        logFile("debug.log", "отладка", 3_000_000)
        File(profileDir, "logs/options.bin").writeBytes(byteArrayOf(1, 2, 3))

        val listed = logs.files(profileDir).map { it.name }

        assertEquals(
            "latest.log всегда первый, остальные свежие сверху, посторонние файлы отброшены",
            listOf("latest.log", "debug.log", "2026-09-01-1.log.gz"),
            listed,
        )
    }

    @Test
    fun `хвост лога обрезается по числу строк`() {
        prepare()
        val total = 1000
        val body = (1..total).joinToString("\n") { "строка $it" }
        val file = logFile("latest.log", body, 1_000)

        data class Case(val maxLines: Int, val expected: Int, val firstLine: String, val why: String)

        val cases = listOf(
            Case(400, 400, "строка 601", "по умолчанию отдаётся 400 последних строк"),
            Case(1, 1, "строка 1000", "одна строка это самая последняя"),
            Case(5000, total, "строка 1", "запрос больше лога отдаёт весь лог"),
        )

        for (case in cases) {
            val lines = logs.tail(file, case.maxLines).lines()
            assertEquals("${case.why}: число строк", case.expected, lines.size)
            assertEquals("${case.why}: первая строка", case.firstLine, lines.first())
            assertEquals("${case.why}: последняя строка", "строка $total", lines.last())
        }

        assertEquals(
            "значение по умолчанию обязано остаться 400: больше телефон не покажет",
            logs.tail(file, 400),
            logs.tail(file),
        )
    }

    @Test
    fun `хвост читается и из сжатого лога`() {
        prepare()
        val file = logFile("2026-09-01-1.log.gz", (1..50).joinToString("\n") { "строка $it" }, 1_000)

        val lines = logs.tail(file, 10).lines()

        assertEquals("из архива тоже отдаётся хвост", 10, lines.size)
        assertEquals("строка 41", lines.first())
        assertEquals("строка 50", lines.last())
    }

    @Test
    fun `огромный лог не читается целиком`() {
        prepare()
        val heavy = StringBuilder()
        val line = "x".repeat(1024)
        repeat(4096) { heavy.append(line).append('\n') }
        val file = logFile("latest.log", heavy.toString(), 1_000)

        val lines = logs.tail(file, 20).lines()

        assertTrue("файл обязан быть больше двухмегабайтного окна чтения", file.length() > 2L * 1024 * 1024)
        assertEquals("хвост читается и у большого лога", 20, lines.size)
        assertTrue(
            "обрезанная по байтам первая строка не должна попасть в хвост",
            lines.all { it.length == line.length },
        )
    }

    @Test
    fun `отчёты о падении отдаются свежими первыми`() {
        prepare()
        val folder = File(profileDir, "crash-reports").also { it.mkdirs() }
        File(folder, "crash-2026-09-01.txt").also { it.writeText("падение"); it.setLastModified(1_000) }
        File(folder, "crash-2026-09-05.txt").also { it.writeText("падение"); it.setLastModified(9_000) }
        File(folder, "notes.md").writeText("не отчёт")

        val listed = logs.crashReports(profileDir).map { it.name }

        assertEquals(
            "отчёты сортируются по свежести, посторонние файлы отброшены",
            listOf("crash-2026-09-05.txt", "crash-2026-09-01.txt"),
            listed,
        )
    }

    @Test
    fun `нет папки логов - пустой список`() {
        prepare()
        assertEquals("сборка без запусков это не ошибка", emptyList<File>(), logs.files(profileDir))
        assertEquals("", logs.tail(File(profileDir, "logs/latest.log")))
    }

    @Test
    fun `строки лаунчера из начала сессии попадают в отправляемый журнал`() {
        prepare()
        val session = File(temp.newFolder(), "session.log")
        session.writeText(
            "[millida] session start\n" +
                "[millida] signal 34 was at the default disposition after the virtual machine started, made harmless\n" +
                "game line\n".repeat(5_000) +
                "[millida] late line\n",
        )
        val picked = logs.launcherLines(session).lines()
        assertEquals("ремонт сигнала пишется в начале сессии и иначе уходит из хвоста", 3, picked.size)
        assertTrue("строки игры не подмешиваются", picked.all { it.startsWith("[millida]") })
    }

    @Test
    fun `подпись запуска сборки не уходит в отправляемый журнал`() {
        prepare()
        val session = File(temp.newFolder(), "session.log")
        session.writeText(
            "[millida] jvm option -Dmillida.pack.signature=SECRET123\n" +
                "[millida] jvm option -javaagent:/data/authlib.jar=https://auth.example/SECRET123\n" +
                "[millida] jvm option -Xmx4096M\n",
        )
        val picked = logs.launcherLines(session)
        data class Case(val expected: String, val why: String)
        val cases = listOf(
            Case("[millida] jvm option -Dmillida.pack.signature=<hidden>", "имя свойства видно, значение — подпись от сервера"),
            Case("[millida] jvm option -javaagent:/data/authlib.jar=<hidden>", "аргументы агента могут нести адрес с ключом"),
            Case("[millida] jvm option -Xmx4096M", "память секретом не является и нужна для разбора"),
        )
        for (case in cases) {
            assertTrue(case.why, picked.lines().contains(case.expected))
        }
        assertTrue("ни одного куска подписи в журнале", !picked.contains("SECRET123"))
    }

    @Test
    fun `подпись запуска не уходит и через хвост короткой сессии`() {
        prepare()
        val session = File(temp.newFolder(), "session.log")
        session.writeText("[millida] jvm option -Dmillida.pack.signature=SECRET123\ngame line\n")
        val tail = logs.tail(session)
        assertTrue("короткая сессия целиком попадает в хвост — значение опции там тоже скрыто", !tail.contains("SECRET123"))
        assertTrue("имя опции остаётся для разбора", tail.contains("-Dmillida.pack.signature=<hidden>"))
    }

    @Test
    fun `голые строки аргументов готового слоя тоже без значений`() {
        prepare()
        val session = File(temp.newFolder(), "session.log")
        session.writeText(
            "JVM Args\n" +
                "-Dmillida.pack.signature=SECRET123\n" +
                "  -javaagent:/data/authlib.jar=https://auth.example/SECRET123\n" +
                "-Xmx4096M\n",
        )
        val tail = logs.tail(session)
        assertTrue("готовый слой пишет опции без пометки лаунчера — подпись всё равно скрыта", !tail.contains("SECRET123"))
        assertTrue("имя свойства остаётся", tail.contains("-Dmillida.pack.signature=<hidden>"))
        assertTrue("память видна для разбора", tail.contains("-Xmx4096M"))
    }
}
