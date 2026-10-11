package net.millida.launcher.core

import net.millida.launcher.core.catalog.MillidaFile
import net.millida.launcher.core.catalog.MillidaFiles
import net.millida.launcher.core.catalog.MillidaKind
import net.millida.launcher.core.loader.LoaderKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A file of the Millida catalogue lands in a game folder, so where it may come
 * from, how it is proven and which of an item's files a build gets are pinned
 * here: a wrong pick gives a mod that crashes the game, a loose host check
 * lets an answer aim the phone anywhere.
 */
class MillidaFilesTest {

    private fun file(id: String, versions: List<String>, loaders: List<String>, released: String) =
        MillidaFile(id = id, gameVersions = versions, loaders = loaders, releasedAt = released)

    private val oldFabric = file("file-old-fabric", listOf("1.20.1"), listOf("fabric"), "2026-01-01T00:00:00Z")
    private val newFabric = file("file-new-fabric", listOf("1.21.1"), listOf("fabric"), "2026-06-01T00:00:00Z")
    private val newForge = file("file-new-forge", listOf("1.20.1"), listOf("forge"), "2026-07-01T00:00:00Z")
    private val any = file("file-any-version", emptyList(), emptyList(), "2026-02-01T00:00:00Z")

    private data class PickCase(
        val files: List<MillidaFile>,
        val game: String?,
        val loader: LoaderKind?,
        val kind: MillidaKind,
        val expectedId: String?,
        val fits: Boolean,
        val why: String,
    )

    @Test
    fun `какой файл материала получает сборка`() {
        val cases = listOf(
            PickCase(listOf(oldFabric, newFabric, newForge), "1.20.1", LoaderKind.FABRIC, MillidaKind.MOD, oldFabric.id, true, "версия и загрузчик сборки важнее свежести"),
            PickCase(listOf(oldFabric, newFabric), "1.21.1", LoaderKind.QUILT, MillidaKind.MOD, newFabric.id, true, "Quilt грузит моды Fabric"),
            PickCase(listOf(newForge), "1.20.1", LoaderKind.FABRIC, MillidaKind.MOD, newForge.id, false, "чужой загрузчик ставится только после вопроса"),
            PickCase(listOf(newForge, oldFabric), "1.20.1", LoaderKind.FABRIC, MillidaKind.RESOURCE_PACK, newForge.id, true, "у ресурс-пака загрузчик сборки не решает"),
            PickCase(listOf(newFabric), "1.12.2", LoaderKind.FABRIC, MillidaKind.MOD, newFabric.id, false, "нет файла под версию — самый свежий под загрузчик и вопрос"),
            PickCase(listOf(any, oldFabric), "1.19.4", LoaderKind.FABRIC, MillidaKind.WORLD, any.id, true, "файл без версий подходит любой сборке"),
            PickCase(listOf(newFabric, oldFabric), null, null, MillidaKind.MOD, newFabric.id, true, "без сборки берётся самый свежий"),
            PickCase(emptyList(), "1.20.1", LoaderKind.FABRIC, MillidaKind.MOD, null, false, "у материала без файлов ставить нечего"),
        )
        cases.forEach { case ->
            val pick = MillidaFiles.pickFile(case.files, case.game, case.loader, case.kind)
            assertEquals(case.why, case.expectedId, pick?.file?.id)
            if (pick != null) assertEquals(case.why, case.fits, pick.fits)
        }
    }

    @Test
    fun `откуда можно скачать файл каталога`() {
        val cases = listOf(
            "https://garage.millida.net/a.jar?X-Amz=1" to true,
            "https://cdn.millida.trade/catalog/x.zip" to true,
            "https://api.millida.net/v2/catalog/files/abcdefghij/download" to true,
            "https://cdn.modrinth.com/data/x.jar" to true,
            "http://garage.millida.net/a.jar" to false,
            "https://millida.net.evil.io/a.jar" to false,
            "https://evilmillida.net/a.jar" to false,
            "https://user@garage.millida.net/a.jar" to false,
            "file:///etc/passwd" to false,
            "" to false,
        )
        cases.forEach { (url, allowed) ->
            assertEquals("адрес $url: подпись ссылки не мешает направить запись в игру куда угодно", allowed, MillidaFiles.fileUrlAllowed(url))
        }
    }

    @Test
    fun `идентификатор файла по правилу сервера`() {
        val cases = listOf(
            "cm1abcdefghij" to true,
            "short" to false,
            "../../etc/passwd00" to false,
            "a".repeat(41) to false,
            "кириллица-имя-файла" to false,
        )
        cases.forEach { (id, valid) -> assertEquals("id $id попадает в путь запроса", valid, MillidaFiles.fileIdValid(id)) }
    }

    @Test
    fun `без контрольной суммы файл не качается`() {
        val sha512 = "a".repeat(128)
        val sha1 = "B".repeat(40)
        assertEquals("sha512 ссылки свежее sha1 карточки", sha512, MillidaFiles.digest(sha512, sha1))
        assertEquals("без sha512 доказывает sha1 карточки", sha1.lowercase(), MillidaFiles.digest("", sha1))
        assertEquals("обрезанная сумма не сумма", null, MillidaFiles.digest("a".repeat(127), "b".repeat(39)))
        assertEquals("без сумм ставить нельзя", null, MillidaFiles.digest(null, null))
    }

    @Test
    fun `раздел сайта ведёт в свою папку`() {
        val cases = listOf(
            "mods" to MillidaKind.MOD,
            "texture-packs" to MillidaKind.RESOURCE_PACK,
            "shaders" to MillidaKind.SHADER,
            "data-packs" to MillidaKind.DATA_PACK,
            "maps" to MillidaKind.WORLD,
            "modpacks" to null,
            "plugins" to null,
        )
        cases.forEach { (section, kind) -> assertEquals("раздел $section", kind, MillidaKind.ofSection(section)) }
        assertEquals("карта уходит в saves, а не в папку контента", null, MillidaKind.WORLD.content)
    }
}
