package net.millida.launcher.core

import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.content.ArchiveShape
import net.millida.launcher.core.content.ContentFacts
import net.millida.launcher.core.content.ContentInspector
import net.millida.launcher.core.loader.LoaderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ContentInspectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val classMagic = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())

    private fun zip(name: String, entries: List<Pair<String, ByteArray>>): File {
        val target = File(tmp.root, name)
        ZipOutputStream(target.outputStream()).use { out ->
            entries.forEach { (path, body) ->
                out.putNextEntry(ZipEntry(path))
                out.write(body)
                out.closeEntry()
            }
        }
        return target
    }

    private fun text(value: String): ByteArray = value.trimIndent().toByteArray()

    private fun classWith(vararg needles: String): ByteArray =
        classMagic + needles.joinToString(" ").toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `описание Fabric читается целиком`() {
        val file = zip(
            "sodium.jar",
            listOf(
                "fabric.mod.json" to text(
                    """{"schemaVersion":1,"id":"sodium","name":"Sodium","version":"0.5.8",
                       "depends":{"minecraft":["1.20.1","1.20.2"]}}"""
                ),
                "me/sodium/Main.class" to classMagic,
            )
        )

        val facts = ContentInspector.inspect(file)

        assertEquals("файл с описанием — мод", ArchiveShape.MOD, facts.shape)
        assertEquals("sodium", facts.modId)
        assertEquals("Sodium", facts.title)
        assertEquals("0.5.8", facts.version)
        assertEquals("загрузчик берётся из имени описания", setOf(LoaderKind.FABRIC), facts.loaders)
        assertEquals(
            "список версий в depends склеивается через «или», иначе мод на 1.20.2 ложно отвергался бы",
            "1.20.1 || 1.20.2",
            facts.gameVersionRange,
        )
        assertTrue("код найден", facts.hasCode)
        assertTrue("описание найдено", facts.hasModMetadata)
        assertTrue("чистый мод не получает находок: ${facts.findings}", facts.findings.isEmpty())
        assertEquals("сборка 1.20.2 подходит по списку версий", true, facts.fitsGameVersion("1.20.2"))
    }

    @Test
    fun `описание Quilt читается из блока quilt_loader`() {
        val file = zip(
            "q.jar",
            listOf(
                "quilt.mod.json" to text(
                    """{"quilt_loader":{"id":"qmod","version":"2.1",
                       "metadata":{"name":"Q Mod"},
                       "depends":[{"id":"quilt_loader"},{"id":"minecraft","versions":">=1.20"}]}}"""
                ),
            )
        )

        val facts = ContentInspector.inspect(file)

        assertEquals("qmod", facts.modId)
        assertEquals("имя берётся из metadata", "Q Mod", facts.title)
        assertEquals("2.1", facts.version)
        assertEquals(setOf(LoaderKind.QUILT), facts.loaders)
        assertEquals("версия игры берётся из зависимости minecraft", ">=1.20", facts.gameVersionRange)
    }

    @Test
    fun `описание Forge и NeoForge читается из toml`() {
        val forge = zip(
            "jei.jar",
            listOf(
                "META-INF/mods.toml" to text(
                    """
                    # comment line
                    modLoader="javafml"
                    loaderVersion="[47,)"
                    [[mods]]
                    modId="jei"
                    displayName="Just Enough Items" # trailing comment
                    version='15.2.0'
                    [[dependencies.jei]]
                    modId="forge"
                    versionRange="[47,)"
                    [[dependencies.jei]]
                    modId="minecraft"
                    versionRange="[1.20.1,1.21)"
                    """
                ),
            )
        )
        val both = zip(
            "both.jar",
            listOf(
                "META-INF/mods.toml" to text(
                    """
                    [[mods]]
                    modId="twin"
                    """
                ),
                "META-INF/neoforge.mods.toml" to text(
                    """
                    [[mods]]
                    modId="twin"
                    displayName="Twin"
                    [[dependencies.twin]]
                    modId="minecraft"
                    versionRange="[1.21,)"
                    [[dependencies.twin]]
                    modId="neoforge"
                    versionRange="[21,)"
                    """
                ),
            )
        )

        val forgeFacts = ContentInspector.inspect(forge)
        assertEquals("jei", forgeFacts.modId)
        assertEquals("кавычки и хвостовой комментарий отрезаются", "Just Enough Items", forgeFacts.title)
        assertEquals("одинарные кавычки тоже понимаются", "15.2.0", forgeFacts.version)
        assertEquals(setOf(LoaderKind.FORGE), forgeFacts.loaders)
        assertEquals(
            "диапазон берётся из блока minecraft, а не из соседнего блока forge",
            "[1.20.1,1.21)",
            forgeFacts.gameVersionRange,
        )

        val twin = ContentInspector.inspect(both)
        assertEquals(
            "jar с двумя описаниями запускается на обоих загрузчиках",
            setOf(LoaderKind.NEOFORGE, LoaderKind.FORGE),
            twin.loaders,
        )
        assertEquals("имя берётся из описания, где оно есть", "Twin", twin.title)
        assertEquals("блок minecraft перед другим блоком тоже находится", "[1.21,)", twin.gameVersionRange)
    }

    @Test
    fun `форма архива определяется по содержимому`() {
        data class Case(val name: String, val entries: List<Pair<String, ByteArray>>, val shape: ArchiveShape, val kind: ContentKind?, val why: String)

        val cases = listOf(
            Case(
                "shader.zip",
                listOf("shaders/composite.fsh" to text("void main(){}")),
                ArchiveShape.SHADER_PACK,
                ContentKind.SHADER,
                "папка shaders — шейдерпак, ему место в shaderpacks",
            ),
            Case(
                "data.zip",
                listOf("pack.mcmeta" to text("{}"), "data/x/recipes/a.json" to text("{}")),
                ArchiveShape.DATA_PACK,
                ContentKind.DATA_PACK,
                "pack.mcmeta с папкой data — датапак, а не ресурспак",
            ),
            Case(
                "res.zip",
                listOf("pack.mcmeta" to text("{}"), "assets/minecraft/textures/a.png" to byteArrayOf(0x89.toByte())),
                ArchiveShape.RESOURCE_PACK,
                ContentKind.RESOURCE_PACK,
                "pack.mcmeta без data — ресурспак",
            ),
            Case(
                "code.jar",
                listOf("a/B.class" to classMagic),
                ArchiveShape.MOD,
                ContentKind.MOD,
                "код без описания всё равно мод по форме — дальше его ловит проверка",
            ),
            Case(
                "readme.zip",
                listOf("readme.txt" to text("hello")),
                ArchiveShape.UNKNOWN,
                null,
                "непонятный архив не раскладывается ни в одну папку",
            ),
        )

        for (case in cases) {
            val facts = ContentInspector.inspect(zip(case.name, case.entries))
            assertEquals("${case.name}: форма. Зачем: ${case.why}", case.shape, facts.shape)
            assertEquals("${case.name}: папка. Зачем: ${case.why}", case.kind, facts.kind())
        }
    }

    @Test
    fun `не архив отвечает причиной, а не исключением`() {
        data class Case(val file: File, val unreadable: String, val why: String)

        val cases = listOf(
            Case(File(tmp.root, "missing.jar"), "файла нет на месте", "удалённый файл не роняет проверку"),
            Case(File(tmp.root, "notes.txt").apply { writeText("x") }, "это не jar и не zip", "чужое расширение не открывается"),
            Case(File(tmp.root, ".jar").apply { writeText("x") }, "это не jar и не zip", "имя из одного расширения — не мод"),
            Case(File(tmp.root, "broken.jar").apply { writeText("не zip") }, "файл не открывается как архив", "битый jar"),
        )

        for (case in cases) {
            val facts = ContentInspector.inspect(case.file)
            assertEquals("${case.file.name}: ${case.why}", ArchiveShape.NOT_AN_ARCHIVE, facts.shape)
            assertEquals("${case.file.name}: причина видна игроку", case.unreadable, facts.unreadable)
            assertNull("${case.file.name}: ни в одну папку", facts.kind())
        }

        val broken = ContentInspector.inspect(File(tmp.root, "broken.jar"))
        assertEquals("битый архив — сильная находка, чтобы попасть в карантин", 1, broken.strongFindings)
    }

    @Test
    fun `выключенный и заглавный jar всё равно проверяются`() {
        val entries = listOf("fabric.mod.json" to text("""{"id":"off","name":"Off"}"""))
        val disabled = zip("off.jar.disabled", entries)
        val upper = zip("UPPER.JAR", entries)

        assertEquals("выключенный мод тоже мод", ArchiveShape.MOD, ContentInspector.inspect(disabled).shape)
        assertEquals("расширение в верхнем регистре не мешает", "off", ContentInspector.inspect(upper).modId)
    }

    @Test
    fun `подозрительные признаки внутри классов`() {
        val file = zip(
            "evil.jar",
            listOf(
                "fabric.mod.json" to text("""{"id":"evil","name":"Evil"}"""),
                "a/Hook.class" to classWith("discord.com/api/webhooks", "java/lang/Runtime"),
                "a/Spawn.class" to classWith("ProcessBuilder"),
                "assets/evil/icon.png" to classMagic + byteArrayOf(1, 2, 3),
                "META-INF/jars/lib.jar" to "PK\u0003\u0004rest".toByteArray(Charsets.ISO_8859_1),
            )
        )

        val facts = ContentInspector.inspect(file)
        val texts = facts.findings.map { it.text }

        assertTrue("вебхук Discord — находка: $texts", "шлёт данные в Discord-вебхук" in texts)
        assertEquals(
            "Runtime и ProcessBuilder — одна причина, а не две: иначе дубль выглядит как вдвое опаснее",
            1,
            texts.count { it == "запускает сторонние процессы" },
        )
        assertTrue("класс под видом картинки — находка: $texts", "В архиве лежит класс под видом ресурса" in texts)
        assertTrue("вложенный jar отмечается: $texts", "Внутри лежит ещё один jar" in texts)
        assertEquals("сильные — вебхук и спрятанный класс", 2, facts.strongFindings)
        assertEquals("слабых две: процессы и вложенный jar", 2, facts.findings.count { !it.strong })
    }

    @Test
    fun `код без описания мода — сильная находка`() {
        val facts = ContentInspector.inspect(zip("noname.jar", listOf("x/Y.class" to classMagic)))

        assertTrue("код есть", facts.hasCode)
        assertFalse("описания нет", facts.hasModMetadata)
        assertEquals("загрузчик такой jar не признает — это сильный признак", 1, facts.strongFindings)
        assertEquals("без названия показывается имя файла", "noname.jar", facts.label)
    }

    @Test
    fun `кривое описание не роняет проверку`() {
        data class Case(val body: String, val why: String)

        val cases = listOf(
            Case("{не json", "битый JSON"),
            Case("""{"name":"Без id"}""", "описание без id не даёт идентификатора"),
            Case("[]", "массив вместо объекта"),
        )
        cases.forEachIndexed { index, case ->
            val facts = ContentInspector.inspect(zip("bad$index.jar", listOf("fabric.mod.json" to case.body.toByteArray())))
            assertEquals("${case.why}: файл всё равно мод по форме", ArchiveShape.MOD, facts.shape)
            assertEquals("${case.why}: id пустой", "", facts.modId)
            assertTrue("${case.why}: загрузчик неизвестен", facts.loaders.isEmpty())
        }

        val nameless = ContentInspector.inspect(zip("nameless.jar", listOf("fabric.mod.json" to text("""{"id":"nameless"}"""))))
        assertEquals("без name название — это id", "nameless", nameless.title)
    }

    @Test
    fun `на каком загрузчике запускается мод`() {
        data class Case(val shape: ArchiveShape, val loaders: Set<LoaderKind>, val build: LoaderKind, val want: Boolean, val why: String)

        val cases = listOf(
            Case(ArchiveShape.RESOURCE_PACK, setOf(LoaderKind.FORGE), LoaderKind.FABRIC, true, "ресурспаку загрузчик безразличен"),
            Case(ArchiveShape.MOD, emptySet(), LoaderKind.FABRIC, true, "неизвестный загрузчик — не повод отказывать"),
            Case(ArchiveShape.MOD, emptySet(), LoaderKind.VANILLA, true, "без описания решать нечем даже на ванили"),
            Case(ArchiveShape.MOD, setOf(LoaderKind.FABRIC), LoaderKind.VANILLA, false, "ваниль модов не грузит"),
            Case(ArchiveShape.MOD, setOf(LoaderKind.FABRIC), LoaderKind.QUILT, true, "Quilt запускает Fabric-моды"),
            Case(ArchiveShape.MOD, setOf(LoaderKind.QUILT), LoaderKind.FABRIC, false, "обратного пути нет: Fabric не грузит Quilt"),
            Case(ArchiveShape.MOD, setOf(LoaderKind.FORGE), LoaderKind.NEOFORGE, false, "Forge-мод на NeoForge падает"),
            Case(ArchiveShape.MOD, setOf(LoaderKind.FORGE, LoaderKind.NEOFORGE), LoaderKind.NEOFORGE, true, "двойной jar подходит"),
        )
        for (case in cases) {
            val facts = ContentFacts(fileName = "x.jar", shape = case.shape, loaders = case.loaders)
            assertEquals("${case.loaders} на ${case.build}: ${case.why}", case.want, facts.runsOn(case.build))
        }

        assertEquals("неизвестный загрузчик", ContentFacts("x.jar", ArchiveShape.MOD).loaderLabel())
        assertEquals(
            "Forge, NeoForge",
            ContentFacts("x.jar", ArchiveShape.MOD, loaders = linkedSetOf(LoaderKind.FORGE, LoaderKind.NEOFORGE)).loaderLabel(),
        )
        assertNull("без диапазона мнения нет", ContentFacts("x.jar", ArchiveShape.MOD).fitsGameVersion("1.20.1"))
    }
}
