package net.millida.launcher.core

import net.millida.launcher.core.sync.MAX_FILES
import net.millida.launcher.core.sync.MAX_SUMMARY
import net.millida.launcher.core.sync.PACK_FORMAT
import net.millida.launcher.core.sync.PackFile
import net.millida.launcher.core.sync.PackManifest
import net.millida.launcher.core.sync.sanitiseManifest
import net.millida.launcher.core.sync.urlAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CloudManifestGuardTest {

    private val sha = "a".repeat(40)

    private fun file(path: String, url: String = "https://cdn.modrinth.com/data/x/$path", sha1: String = sha) =
        PackFile(path = path, kind = "mod", sha1 = sha1, url = url)

    private data class UrlCase(val url: String, val allowed: Boolean, val why: String)

    @Test
    fun `файлы облачной сборки качаются только с известных хостов по https`() {
        val cases = listOf(
            UrlCase("https://cdn.modrinth.com/data/AANobbMI/versions/1/sodium.jar", true, "Modrinth"),
            UrlCase("https://mediafilez.forgecdn.net/files/1/2/mod.jar", true, "CurseForge"),
            UrlCase("https://cdn.millida.net/packs/x.jar", true, "наш CDN"),
            UrlCase("http://cdn.modrinth.com/data/x.jar", false, "http подменяется по дороге"),
            UrlCase("https://cdn.modrinth.com.evil.com/x.jar", false, "известное имя как префикс чужого домена"),
            UrlCase("https://evil.com/cdn.modrinth.com/x.jar", false, "известное имя в пути, а не в хосте"),
            UrlCase("https://192.168.0.1/x.jar", false, "адрес домашней сети"),
            UrlCase("file:///sdcard/x.jar", false, "локальный файл телефона"),
            UrlCase("not a url", false, "мусор вместо адреса — отказ, а не исключение"),
            UrlCase("", false, "пустой адрес"),
        )
        for (case in cases) {
            assertEquals("urlAllowed(«${case.url}»): ${case.why}", case.allowed, urlAllowed(case.url))
        }
    }

    private data class FileCase(val file: PackFile, val kept: Boolean, val why: String)

    @Test
    fun `в сборку попадают только файлы с адресом, хешем и путём без выхода наружу`() {
        val cases = listOf(
            FileCase(file("mods/sodium.jar"), true, "обычный мод"),
            FileCase(file("mods/upper.jar", sha1 = "ABCDEF0123".repeat(4)), true, "хеш заглавными — тот же хеш"),
            FileCase(file("mods/../../evil.jar"), false, "путь выходит из папки сборки"),
            FileCase(file("mods/evil.jar", url = "https://evil.com/evil.jar"), false, "чужой хост"),
            FileCase(file("mods/short.jar", sha1 = "abc"), false, "короткий хеш ничего не гарантирует"),
            FileCase(file("mods/nohash.jar", sha1 = ""), false, "без хеша исполняемое не ставим"),
            FileCase(file("mods/zz.jar", sha1 = "z".repeat(40)), false, "хеш не из hex"),
            FileCase(file("mods/sha256.jar", sha1 = "a".repeat(64)), false, "sha256 вместо sha1 не сверится"),
        )
        val manifest = PackManifest(name = "Сборка", game = "1.20.1", files = cases.map { it.file })

        val kept = sanitiseManifest(manifest).files.map { it.path }.toSet()

        for (case in cases) {
            assertEquals("${case.file.path} (${case.file.url}, sha1 ${case.file.sha1}): ${case.why}", case.kept, case.file.path in kept)
        }
    }

    @Test
    fun `сборка новее формата или без версии игры не ставится`() {
        val newer = runCatching { sanitiseManifest(PackManifest(formatVersion = PACK_FORMAT + 1, name = "x", game = "1.20.1")) }.exceptionOrNull()
        assertTrue("формат новее — отказ, а не установка наполовину", newer is IOException)
        assertTrue("игроку сказано обновить лаунчер", newer!!.message.orEmpty().contains("обнови лаунчер"))

        val noGame = runCatching { sanitiseManifest(PackManifest(name = "x", game = "  ")) }.exceptionOrNull()
        assertTrue("без версии Minecraft ставить нечего", noGame is IOException)
        assertEquals("В сборке не указана версия Minecraft", noGame!!.message)

        val current = sanitiseManifest(PackManifest(formatVersion = PACK_FORMAT, name = "x", game = "1.20.1"))
        assertEquals("текущий формат проходит", "1.20.1", current.game)
    }

    @Test
    fun `имя и описание очищаются от управляющих символов и обрезаются`() {
        val manifest = PackManifest(
            name = "Сбор\u0000ка\u001b[31m" + "x".repeat(100),
            summary = "строка 1\nстрока 2\t\r" + "y".repeat(MAX_SUMMARY * 2),
            game = "1.20.1",
        )

        val clean = sanitiseManifest(manifest)

        assertTrue("управляющие символы не доходят до экрана: ${clean.name.take(20)}", clean.name.none { it.isISOControl() })
        assertTrue("имя начинается с очищенного названия", clean.name.startsWith("Сборка[31m"))
        assertEquals("имя не длиннее 64 символов", 64, clean.name.length)
        assertTrue("перевод строки в описании сохраняется", clean.summary.startsWith("строка 1\nстрока 2y"))
        assertEquals("описание не длиннее предела", MAX_SUMMARY, clean.summary.length)
    }

    @Test
    fun `слишком длинный список файлов обрезается до предела`() {
        val files = (0..MAX_FILES).map { file("mods/m$it.jar") }

        val clean = sanitiseManifest(PackManifest(name = "x", game = "1.20.1", files = files))

        assertEquals("файлов не больше $MAX_FILES — сборка не вешает телефон", MAX_FILES, clean.files.size)
        assertEquals("порядок сохраняется: обрезается хвост", "mods/m0.jar", clean.files.first().path)
    }
}
