package net.millida.launcher.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.packs.PackArchive
import net.millida.launcher.core.packs.PackExport
import net.millida.launcher.core.packs.PackManifestFile
import net.millida.launcher.core.packs.PackReader
import net.millida.launcher.core.profile.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class PackExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val catalogUrl = "https://cdn.modrinth.com/data/AANobbMI/versions/1/sodium.jar"

    private val fabric = Profile(
        id = "p1",
        name = "Моя сборка",
        gameVersion = "1.20.1",
        loader = LoaderKind.FABRIC,
        loaderVersion = "0.16.9",
    )

    private fun put(dir: File, path: String, text: String = path): File =
        File(dir, path).also { it.parentFile.mkdirs(); it.writeText(text) }

    private fun entries(archive: File): Map<String, String> = ZipFile(archive).use { zip ->
        zip.entries().toList().associate { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes().decodeToString() } }
    }

    private fun index(archive: File): JsonObject =
        MojangJson.parseToJsonElement(entries(archive).getValue("modrinth.index.json")).jsonObject

    private fun buildDir(): File {
        val dir = tmp.newFolder("profile")
        put(dir, "mods/sodium.jar", "байты sodium")
        put(dir, "mods/hand-made.jar")
        put(dir, "mods/broken.jar.disabled")
        put(dir, "mods/millida-mod-fabric-0.2.8.jar")
        put(dir, "mods/foreign.jar")
        put(dir, "mods/short-hash.jar")
        put(dir, "config/sodium-options.json", "{\"quality\":1}")
        put(dir, "resourcepacks/Faithful.zip")
        put(dir, "saves/Мир/level.dat")
        put(dir, "options.txt")
        put(dir, "logs/latest.log")
        return dir
    }

    @Test
    fun `файлы каталога уходят ссылками, остальное лежит в overrides`() {
        val dir = buildDir()
        val sodiumSha1 = Hashing.sha1(File(dir, "mods/sodium.jar"))
        val known = mapOf(
            "mods/sodium.jar" to PackManifestFile(path = "", sha1 = sodiumSha1, url = catalogUrl),
            "mods/foreign.jar" to PackManifestFile(path = "", sha1 = "c".repeat(40), url = "https://evil.example/foreign.jar"),
            "mods/short-hash.jar" to PackManifestFile(path = "", sha1 = "c".repeat(12), url = catalogUrl),
            "mods/gone.jar" to PackManifestFile(path = "", sha1 = "d".repeat(40), url = catalogUrl),
        )
        val out = PackExport.exportMrpack(fabric, dir, File(tmp.root, "export/pack.mrpack"), known = known)

        val names = entries(out).keys
        val expectedOverrides = setOf(
            "overrides/mods/hand-made.jar",
            "overrides/mods/foreign.jar",
            "overrides/mods/short-hash.jar",
            "overrides/config/sodium-options.json",
            "overrides/resourcepacks/Faithful.zip",
        )
        assertEquals(
            "в архив идут только общие папки сборки; выключенные моды, наши jar, миры, настройки и журналы остаются на телефоне",
            expectedOverrides + "modrinth.index.json",
            names,
        )
        assertFalse("файл, отданный ссылкой, не дублируется внутри архива", "overrides/mods/sodium.jar" in names)
        assertFalse("временный файл не остаётся рядом с готовым", File(tmp.root, "export/pack.mrpack.part").exists())

        val files = index(out)["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            "ссылкой уходит только файл, который есть на диске, с разрешённого адреса и с полным хешем",
            listOf("mods/sodium.jar"),
            files.map { it["path"]!!.jsonPrimitive.content },
        )
        val sodium = files.single()
        val hashes = sodium["hashes"]!!.jsonObject
        assertEquals(sodiumSha1, hashes["sha1"]!!.jsonPrimitive.content)
        assertEquals(
            "sha512 считается сам, если каталог его не дал: Modrinth требует оба хеша",
            Hashing.sha512(File(dir, "mods/sodium.jar")),
            hashes["sha512"]!!.jsonPrimitive.content,
        )
        assertEquals("размер берётся с диска, если каталог его не сказал", File(dir, "mods/sodium.jar").length(), sodium["fileSize"]!!.jsonPrimitive.long)
        assertEquals(listOf(catalogUrl), (sodium["downloads"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun `заголовок индекса и зависимости по загрузчику`() {
        data class Case(val profile: Profile, val dependency: Pair<String, String>?, val why: String)
        val cases = listOf(
            Case(fabric, "fabric-loader" to "0.16.9", "Fabric пишется ключом fabric-loader"),
            Case(fabric.copy(loader = LoaderKind.QUILT, loaderVersion = ""), "quilt-loader" to "*", "без номера — любая версия загрузчика"),
            Case(fabric.copy(loader = LoaderKind.FORGE, loaderVersion = "47.2.0"), "forge" to "47.2.0", "Forge пишется ключом forge"),
            Case(fabric.copy(loader = LoaderKind.NEOFORGE, loaderVersion = "21.1.77"), "neoforge" to "21.1.77", "NeoForge пишется своим ключом"),
            Case(fabric.copy(loader = LoaderKind.VANILLA, loaderVersion = ""), null, "ванильная сборка не называет загрузчик"),
        )
        cases.forEachIndexed { number, case ->
            val dir = tmp.newFolder("deps-$number")
            val out = PackExport.exportMrpack(case.profile, dir, File(dir.parentFile, "deps-$number.mrpack"))
            val dependencies = index(out)["dependencies"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            val expected = buildMap<String, String> {
                put("minecraft", "1.20.1")
                case.dependency?.let { put(it.first, it.second) }
            }
            assertEquals("${case.profile.loader}: ${case.why}", expected, dependencies)
        }

        val dir = tmp.newFolder("header")
        val plain = index(PackExport.exportMrpack(fabric, dir, File(tmp.root, "plain.mrpack")))
        assertEquals("без своего имени сборка называется как у игрока", "Моя сборка", plain["name"]!!.jsonPrimitive.content)
        assertEquals("без номера версии ставится 1.0.0: Modrinth не примет пустой", "1.0.0", plain["versionId"]!!.jsonPrimitive.content)
        assertEquals("minecraft", plain["game"]!!.jsonPrimitive.content)

        val named = index(PackExport.exportMrpack(fabric, dir, File(tmp.root, "named.mrpack"), name = "Выживание", version = "2.1", summary = "для друзей"))
        assertEquals("Выживание", named["name"]!!.jsonPrimitive.content)
        assertEquals("2.1", named["versionId"]!!.jsonPrimitive.content)
        assertEquals("для друзей", named["summary"]!!.jsonPrimitive.content)
    }

    @Test
    fun `повторная выгрузка заменяет старый файл`() {
        val dir = tmp.newFolder("again")
        put(dir, "mods/a.jar")
        val out = File(tmp.root, "again.mrpack")
        out.writeText("старый мусор, не архив")
        PackExport.exportMrpack(fabric, dir, out)
        assertTrue("старый файл заменён настоящим архивом", entries(out).containsKey("overrides/mods/a.jar"))
    }

    @Test
    fun `выгруженная сборка ставится обратно тем же лаунчером`() {
        val dir = buildDir()
        val known = mapOf(
            "mods/sodium.jar" to PackManifestFile(path = "", sha1 = Hashing.sha1(File(dir, "mods/sodium.jar")), url = catalogUrl),
        )
        val out = PackExport.exportMrpack(fabric, dir, File(tmp.root, "round.mrpack"), known = known)
        val unpacked = PackArchive.unpack(out, tmp.newFolder("unpacked"))

        val content = PackReader(Downloader(attempts = 1)).readMrpack(PackArchive.root(unpacked))
        assertEquals("1.20.1", content.plan.gameVersion)
        assertEquals("загрузчик доезжает до получателя", LoaderKind.FABRIC, content.plan.loader)
        assertEquals("0.16.9", content.plan.loaderVersion)
        assertEquals("Моя сборка", content.plan.name)
        assertEquals("файл каталога качается по ссылке", listOf("mods/sodium.jar"), content.plan.files.map { it.path })
        assertTrue(
            "ручной мод приезжает из overrides, иначе сборка друга запустится без него",
            content.trees.any { File(it, "mods/hand-made.jar").isFile },
        )
    }
}
