package net.millida.launcher.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.packs.PackHostPolicy
import net.millida.launcher.core.packs.PackLoaders
import net.millida.launcher.core.packs.PackManifest
import net.millida.launcher.core.packs.PackManifestFile
import net.millida.launcher.core.packs.PackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class PackManifestTest {

    private val goodUrl = "https://cdn.modrinth.com/data/AANobbMI/versions/1/sodium.jar"
    private val goodSha = "a".repeat(40)

    private fun json(text: String): JsonObject = MojangJson.parseToJsonElement(text).jsonObject

    private fun manifestWith(filesJson: String, extra: String = ""): JsonObject = json(
        """{"formatVersion":1,"name":"Сборка","game":"1.20.1","loader":"fabric"$extra,"files":[$filesJson]}""",
    )

    private fun fileJson(path: String = "mods/sodium.jar", url: String = goodUrl, sha1: String = goodSha, extra: String = ""): String =
        """{"path":"$path","url":"$url","sha1":"$sha1"$extra}"""

    private fun expectIo(why: String, block: () -> Unit): IOException {
        try {
            block()
        } catch (expected: IOException) {
            return expected
        }
        fail(why)
        throw AssertionError(why)
    }

    @Test
    fun `манифест без корня, из будущей версии или без версии игры отклоняется понятной ошибкой`() {
        val missing = expectIo("пустой ответ сервера не должен превращаться в пустую сборку") { PackManifest.parse(null) }
        assertTrue("игроку нужно знать, что делать: «обнови лаунчер»", missing.message.orEmpty().contains("обнови лаунчер"))

        val future = expectIo("формат новее нашего читать нельзя — поля могли поменять смысл") {
            PackManifest.parse(json("""{"formatVersion":2,"game":"1.20.1"}"""))
        }
        assertTrue("ошибка о новой версии подсказывает обновиться", future.message.orEmpty().contains("более новой версии"))

        expectIo("без версии Minecraft сборку не на что ставить") { PackManifest.parse(json("""{"formatVersion":1,"game":""}""")) }
        expectIo("null в поле игры — то же отсутствие версии") { PackManifest.parse(json("""{"game":null}""")) }

        val legacy = PackManifest.parse(json("""{"game":"1.20.1"}"""))
        assertEquals("манифест без номера формата читается как первый", 1, legacy.formatVersion)
    }

    private data class FileCase(val entry: String, val kept: Boolean, val why: String)

    @Test
    fun `строки файлов с чужим адресом, битым хешем или выходом из папки выбрасываются поштучно`() {
        val cases = listOf(
            FileCase(fileJson(), true, "обычный мод с Modrinth"),
            FileCase(fileJson(sha1 = "ABCDEF0123".repeat(4)), true, "хеш в верхнем регистре — тот же хеш"),
            FileCase(fileJson(url = "http://cdn.modrinth.com/sodium.jar"), false, "без https файл можно подменить по пути"),
            FileCase(fileJson(url = "https://evil.example/sodium.jar"), false, "чужой хост мог бы раздать что угодно"),
            FileCase(fileJson(url = "https://127.0.0.1/sodium.jar"), false, "адрес внутренней сети телефона"),
            FileCase(fileJson(sha1 = "a".repeat(39)), false, "короткий хеш не проверяет файл"),
            FileCase(fileJson(sha1 = "g".repeat(40)), false, "не шестнадцатеричный хеш никогда не совпадёт"),
            FileCase(fileJson(sha1 = ""), false, "без хеша файл некому проверить"),
            FileCase(fileJson(path = "../../evil.jar"), false, "выход из папки сборки перезаписал бы чужой файл"),
            FileCase(fileJson(path = "mods/../../evil.jar"), false, "выход из папки, спрятанный в середину пути"),
            FileCase(fileJson(path = ""), false, "файл без пути некуда положить"),
        )
        cases.forEach { case ->
            val parsed = PackManifest.parse(manifestWith(case.entry))
            assertEquals(
                "строка ${case.entry}: ${case.why}",
                if (case.kept) 1 else 0,
                parsed.files.size,
            )
        }

        val mixed = PackManifest.parse(manifestWith(listOf(fileJson(), fileJson(path = "../x.jar"), fileJson(path = "mods/b.jar")).joinToString(",")))
        assertEquals(
            "одна плохая строка не должна стоить игроку остальной сборки",
            listOf("mods/sodium.jar", "mods/b.jar"),
            mixed.files.map { it.path },
        )
    }

    @Test
    fun `поля файла читаются, а пропущенные получают умолчания`() {
        val parsed = PackManifest.parse(
            manifestWith(
                fileJson(extra = ""","kind":"resourcepack","projectId":"P1","versionId":"V1","size":12345""") + "," +
                    fileJson(path = "mods/b.jar", extra = ""","kind":null,"size":"oops""""),
            ),
        )
        val first = parsed.files[0]
        assertEquals("вид файла решает папку и подпись в окне", "resourcepack", first.kind)
        assertEquals("проект нужен, чтобы получатель скачал файл сам из каталога", "P1", first.projectId)
        assertEquals("V1", first.versionId)
        assertEquals("размер показывает объём скачивания до установки", 12345L, first.size)

        val second = parsed.files[1]
        assertEquals("без вида строка считается модом", "mod", second.kind)
        assertEquals("нечисловой размер не роняет разбор, а превращается в ноль", 0L, second.size)
    }

    private data class NameCase(val raw: String, val expected: String, val why: String)

    @Test
    fun `имя и описание чистятся от управляющих символов и обрезаются`() {
        val cases = listOf(
            NameCase("  Выживание  ", "Выживание", "пробелы по краям не часть имени"),
            NameCase("Вы\u0007жи\u001Bвание", "Выживание", "управляющие символы ломают отрисовку и журналы"),
            NameCase("", "Сборка друга", "сборка без имени всё равно должна как-то называться"),
            NameCase("\u0001\u0002", "Сборка друга", "имя из одних управляющих символов — пустое имя"),
            NameCase("я".repeat(100), "я".repeat(64), "длинное имя не помещается в карточку"),
        )
        cases.forEach { case ->
            val raw = JsonPrimitive(case.raw).toString()
            val parsed = PackManifest.parse(json("""{"game":"1.20.1","name":$raw}"""))
            assertEquals("имя «${case.raw.take(20)}»: ${case.why}", case.expected, parsed.name)
        }

        val longSummary = "о".repeat(500)
        val parsed = PackManifest.parse(json("""{"game":"1.20.1","summary":"строка1\nстрока2 $longSummary"}"""))
        assertTrue("перенос строки в описании законен", parsed.summary.startsWith("строка1\nстрока2"))
        assertEquals("описание длиннее 300 знаков обрезается", 300, parsed.summary.length)
    }

    @Test
    fun `иконка с чужого адреса не показывается, загрузчик по умолчанию ваниль`() {
        val foreign = PackManifest.parse(json("""{"game":"1.20.1","icon":"https://evil.example/i.png"}"""))
        assertEquals("иконка с чужого хоста выдала бы адрес телефона постороннему", "", foreign.icon)
        assertEquals("сборка без загрузчика — ванильная", LoaderKind.VANILLA.id, foreign.loader)

        val own = PackManifest.parse(json("""{"game":"1.20.1","icon":"https://cdn.modrinth.com/data/x/icon.png"}"""))
        assertEquals("иконка с каталога остаётся", "https://cdn.modrinth.com/data/x/icon.png", own.icon)
    }

    @Test
    fun `опубликованный манифест читается обратно тем же`() {
        val original = PackManifest(
            name = "Сборка",
            summary = "описание",
            game = "1.20.1",
            loader = "fabric",
            loaderVersion = "0.16.9",
            icon = "https://cdn.modrinth.com/data/x/icon.png",
            files = listOf(
                PackManifestFile(path = "mods/sodium.jar", projectId = "P", versionId = "V", sha1 = goodSha, size = 10, url = goodUrl),
                PackManifestFile(path = "resourcepacks/f.zip", kind = "resourcepack", sha1 = "b".repeat(40), size = 20, url = goodUrl),
            ),
        )
        val back = PackManifest.parse(original.toJson())
        assertEquals("то, что ушло на сервер, получатель обязан прочитать без потерь", original, back)
        assertTrue("раздел настроек присутствует, его ждёт настольный лаунчер", original.toJson().containsKey("settings"))
    }

    @Test
    fun `манифест превращается в план установки по коду`() {
        val manifest = PackManifest(
            name = "Сборка",
            game = "1.20.1",
            loader = "fabric-0.16.5",
            loaderVersion = "0.15.0",
            files = listOf(
                PackManifestFile(path = "mods/a.jar", sha1 = goodSha, size = 7, projectId = "P", versionId = "V", url = goodUrl),
                PackManifestFile(path = "mods/b.jar", url = goodUrl),
            ),
        )
        val plan = manifest.toPlan("AB23CD45")
        assertEquals(LoaderKind.FABRIC, plan.loader)
        assertEquals("номер, записанный вместе с загрузчиком, точнее отдельного поля", "0.16.5", plan.loaderVersion)
        assertEquals("код запоминается, чтобы сборку можно было обновить", "AB23CD45", plan.sourceId)
        assertEquals(PackSource.MILLIDA_CODE, plan.source)
        assertEquals(listOf(goodUrl), plan.files[0].urls)
        assertEquals(goodSha, plan.files[0].sha1)
        assertNull("пустой хеш — это отсутствие хеша, а не хеш пустой строки", plan.files[1].sha1)

        val separate = manifest.copy(loader = "fabric").toPlan("AB23CD45")
        assertEquals("без номера в имени загрузчика берётся отдельное поле", "0.15.0", separate.loaderVersion)

        expectIo("неизвестный загрузчик не превращается молча в ваниль без модов") {
            manifest.copy(loader = "rift").toPlan("AB23CD45")
        }
    }

    private data class LoaderCase(val raw: String, val kind: LoaderKind, val version: String, val why: String)

    @Test
    fun `загрузчик из идентификатора CurseForge`() {
        val cases = listOf(
            LoaderCase("fabric-0.16.5", LoaderKind.FABRIC, "0.16.5", "CurseForge пишет загрузчик и номер одной строкой"),
            LoaderCase("Forge-47.2.0", LoaderKind.FORGE, "47.2.0", "регистр в манифесте не важен"),
            LoaderCase("neoforge-21.1.0-beta", LoaderKind.NEOFORGE, "21.1.0-beta", "дефис внутри номера остаётся частью номера"),
            LoaderCase("quilt", LoaderKind.QUILT, "", "загрузчик без номера — номер выберет установщик"),
            LoaderCase("", LoaderKind.VANILLA, "", "сборка без загрузчика — ваниль"),
            LoaderCase("  ", LoaderKind.VANILLA, "", "пробелы — тоже пустота"),
            LoaderCase("minecraft", LoaderKind.VANILLA, "", "так некоторые сборки называют ваниль"),
        )
        cases.forEach { case ->
            assertEquals("«${case.raw}»: ${case.why}", case.kind to case.version, PackLoaders.fromId(case.raw))
        }
        val unknown = expectIo("неизвестный загрузчик дал бы сборку, где не грузится ни один мод") { PackLoaders.fromId("liteloader-1.12") }
        assertTrue("ошибка называет загрузчик, иначе игроку не понять, что не так", unknown.message.orEmpty().contains("liteloader"))
    }

    @Test
    fun `загрузчик из зависимостей mrpack`() {
        val cases = listOf(
            Triple(mapOf("minecraft" to "1.20.1", "fabric-loader" to "0.16.9"), LoaderKind.FABRIC to "0.16.9", "обычная сборка Fabric"),
            Triple(mapOf("minecraft" to "1.20.1", "quilt-loader" to "0.26", "fabric-loader" to "0.16"), LoaderKind.QUILT to "0.26", "Quilt тянет совместимость с Fabric, но ставится Quilt"),
            Triple(mapOf("forge" to "47.2.0", "neoforge" to "47.1.0"), LoaderKind.NEOFORGE to "47.1.0", "NeoForge важнее Forge для сборок 1.20.1"),
            Triple(mapOf("minecraft" to "1.20.1"), LoaderKind.VANILLA to "", "без загрузчика — ваниль"),
        )
        cases.forEach { (deps, expected, why) -> assertEquals(why, expected, PackLoaders.fromDependencies(deps)) }
    }

    @Test
    fun `проверка адреса называет адрес в ошибке`() {
        val policy = PackHostPolicy()
        policy.check(goodUrl)
        val error = expectIo("посторонний адрес обязан остановить установку") { policy.check("https://evil.example/x.jar") }
        assertTrue("адрес в тексте нужен, чтобы игрок мог сообщить автору сборки", error.message.orEmpty().contains("evil.example"))
    }
}
