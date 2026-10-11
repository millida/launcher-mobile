package net.millida.launcher.core

import net.millida.launcher.core.catalog.MillidaFile
import net.millida.launcher.core.catalog.MillidaItem
import net.millida.launcher.core.packs.PackArchive
import net.millida.launcher.core.packs.PackEdits
import net.millida.launcher.core.packs.PackMerge
import net.millida.launcher.core.packs.PackOrigin
import net.millida.launcher.core.packs.PackOriginStore
import net.millida.launcher.core.packs.PackSource
import net.millida.launcher.core.packs.PackUpdateRoute
import net.millida.launcher.core.packs.PackUpdates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * An update replaces the folders a pack owns. What is pinned is which builds
 * are offered one, which update by themselves, and what of the player's own
 * survives: a build the player changed must never be overwritten behind
 * their back, and their resource packs must not vanish with the old version.
 */
class PackUpdatesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun put(dir: File, relative: String, at: Long? = null) {
        val file = File(dir, relative)
        file.parentFile.mkdirs()
        file.writeText("x")
        at?.let { file.setLastModified(it) }
    }

    @Test
    fun `откуда сборка берёт новую версию`() {
        val cases = listOf(
            PackOrigin(source = PackSource.MODRINTH.id, id = "fabulously-optimized") to PackUpdateRoute.MODRINTH,
            PackOrigin(source = PackSource.CURSEFORGE.id, id = "123456") to PackUpdateRoute.CURSEFORGE,
            PackOrigin(source = PackSource.MILLIDA_CATALOG.id, id = "arcania") to PackUpdateRoute.MILLIDA_CATALOG,
            PackOrigin(source = PackSource.MILLIDA_CATALOG.id, id = "../evil") to null,
            PackOrigin(source = PackSource.MILLIDA_MIRROR.id, id = "freshcraft") to PackUpdateRoute.MILLIDA_MIRROR,
            PackOrigin(source = PackSource.FILE.id, id = "freshcraft") to PackUpdateRoute.MILLIDA_MIRROR,
            PackOrigin(source = PackSource.FILE.id, id = "") to null,
            PackOrigin(source = PackSource.MILLIDA_CODE.id, id = "K7M2QRT9") to PackUpdateRoute.MILLIDA_CODE,
        )
        val why = mapOf(
            PackUpdateRoute.MILLIDA_MIRROR to "сборка с зеркала, поставленная раньше «как из файла», тоже обновляется по карточке",
        )
        cases.forEach { (origin, route) ->
            assertEquals(why[route] ?: "источник ${origin.source}/${origin.id}", route, PackUpdates.routeOf(origin))
        }
        assertNull("сборка без записи не обновляется", PackUpdates.routeOf(null))
    }

    @Test
    fun `обновлением считается только другая версия`() {
        val origin = PackOrigin(source = PackSource.MODRINTH.id, id = "fo", versionId = "v1", versionName = "5.0")
        assertNull("та же версия не предлагается", PackUpdates.offer(PackUpdateRoute.MODRINTH, origin, "v1", "5.0"))
        assertNull("неизвестная свежая версия не предлагается", PackUpdates.offer(PackUpdateRoute.MODRINTH, origin, "", ""))
        val offer = PackUpdates.offer(PackUpdateRoute.MODRINTH, origin, "v2", "5.1")
        assertEquals("строка показывает, с какой на какую", "5.0" to "5.1", offer?.from to offer?.to)

        val catalog = PackOrigin(source = PackSource.MILLIDA_CATALOG.id, id = "arcania", versionName = "1.4")
        assertNull("карточка с той же версией — без обновления", PackUpdates.catalogOffer(catalog, " 1.4 "))
        assertNull("карточка без версии — без обновления", PackUpdates.catalogOffer(catalog, ""))
        assertEquals("новая версия карточки предлагается", "1.5", PackUpdates.catalogOffer(catalog, "1.5")?.to)
    }

    @Test
    fun `файл зеркала берётся только настоящий`() {
        val item = MillidaItem(slug = "freshcraft", title = "FreshCraft", articleSlug = "freshcraft")
        val good = MillidaFile(id = "file-1234567", fileName = "fresh.mrpack", sha1 = "a".repeat(40), mirrored = true)
        assertNotNull("mrpack на зеркале ставится телефоном", PackUpdates.mirroredFile(item, good, "FreshCraft"))
        assertNull("не на зеркале — только страница автора", PackUpdates.mirroredFile(item, good.copy(mirrored = false), "FreshCraft"))
        assertNull("jar не сборка", PackUpdates.mirroredFile(item, good.copy(fileName = "fresh.jar"), "FreshCraft"))
        assertNull("без статьи файл не запросить", PackUpdates.mirroredFile(item.copy(articleSlug = ""), good, "FreshCraft"))
    }

    @Test
    fun `правка модов игроком видна`() {
        data class Case(val name: String, val edit: (File) -> Unit, val modified: Boolean, val why: String)
        val cases = listOf(
            Case("untouched", {}, false, "сборка как поставлена идёт за каталогом"),
            Case("added", { put(it, "mods/jei.jar") }, true, "добавленный мод стирался следующим обновлением"),
            Case("removed", { File(it, "mods/angelica.jar").delete() }, true, "удалённый мод вернулся бы с обновлением"),
            Case("disabled", { File(it, "mods/angelica.jar").renameTo(File(it, "mods/angelica.jar.disabled")) }, true, "выключенный мод включился бы обратно"),
            Case("nested", { put(it, "mods/1.7.10/extra.jar") }, true, "сборки 1.7.10 держат моды на уровень ниже"),
            Case("own-mod", { put(it, "mods/millida-mod-fabric-1.20.1-0.1.15.jar") }, false, "наш мод кладётся при запуске, это не правка игрока"),
            Case("config", { put(it, "mods/notes.txt") }, false, "считаются только файлы модов"),
        )
        cases.forEach { case ->
            val dir = temp.newFolder("edits-${case.name}")
            listOf("mods/angelica.jar", "mods/unimixins.jar", "mods/1.7.10/legacy.zip").forEach { put(dir, it) }
            PackEdits.recordShippedMods(dir)
            case.edit(dir)
            assertEquals("${case.name}: ${case.why}", case.modified, PackEdits.modsModified(dir, installedAt = 0))
        }
    }

    @Test
    fun `сборка без списка модов судится по времени установки`() {
        val dir = temp.newFolder("legacy")
        put(dir, "mods/angelica.jar")
        val shipped = File(dir, "mods/angelica.jar").lastModified()
        assertEquals("моды, пришедшие с установкой, — авторские", false, PackEdits.modsModified(dir, installedAt = shipped + 60_000))
        assertEquals("без записи о времени сборка может хранить моды игрока", true, PackEdits.modsModified(dir, installedAt = 0))
    }

    @Test
    fun `выбор игрока сильнее умолчания, копия сама не обновляется`() {
        data class Case(val chosen: Boolean?, val modified: Boolean, val copied: Boolean, val on: Boolean, val why: String)
        val cases = listOf(
            Case(null, false, false, true, "нетронутая сборка идёт за каталогом"),
            Case(null, true, false, false, "изменённая сборка не перезаписывается за спиной игрока"),
            Case(true, true, false, true, "игрок сам включил, зная, что моды заменятся"),
            Case(false, false, false, false, "игрок сам выключил"),
            Case(null, false, true, false, "копия сборки каталога не обновляется сама"),
            Case(true, false, true, false, "включение в оригинале не переезжает в копию"),
        )
        cases.forEach { case -> assertEquals(case.why, case.on, PackEdits.autoUpdateOn(case.chosen, case.modified, case.copied)) }
    }

    @Test
    fun `копия папки узнаётся по чужому номеру сборки`() {
        val dir = temp.newFolder("copy")
        put(dir, "mods/a.jar")
        PackEdits.recordShippedMods(dir)
        val origin = PackOrigin(source = PackSource.MILLIDA_CATALOG.id, id = "arcania", buildId = "original", autoUpdate = true)
        PackOriginStore.write(dir, origin)
        val state = PackEdits.state(dir, "copy-of-original", origin)
        assertEquals("копия выключена, хоть оригинал и включён", false, state.on)
        assertEquals("копия названа копией", true, state.copied)
        val claimed = PackEdits.choose(dir, "copy-of-original", true)
        assertEquals("ответ игрока в копии делает её своей", true, claimed.on && !claimed.copied)
    }

    @Test
    fun `ресурс-паки игрока переживают обновление`() {
        data class Case(val existing: List<String>, val incoming: Set<String>, val shipped: Set<String>, val kept: List<String>, val why: String)
        val cases = listOf(
            Case(listOf("mine.zip"), setOf("pack.zip"), setOf("resourcepacks/pack-old.zip"), listOf("mine.zip"), "добавленный игроком пак остаётся"),
            Case(listOf("pack-old.zip"), setOf("pack.zip"), setOf("resourcepacks/pack-old.zip"), emptyList(), "старый пак сборки уходит вместе с ней"),
            Case(listOf("pack.zip"), setOf("pack.zip"), emptySet(), emptyList(), "при совпадении имени побеждает новая версия"),
            Case(listOf("mine.zip", "pack-old.zip"), emptySet(), emptySet(), listOf("mine.zip", "pack-old.zip"), "без записи прежней сборки ничего не выбрасывается"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.kept, PackMerge.carried("resourcepacks", case.existing, case.incoming, case.shipped))
        }
    }

    @Test
    fun `обёртка архива снимается, папка игры остаётся`() {
        data class Case(val name: String, val files: List<String>, val root: String, val why: String)
        val cases = listOf(
            Case("wrapped", listOf("MyPack/mods/a.jar", "MyPack/config/a.cfg"), "MyPack", "сборка в одной папке разворачивается"),
            Case("mods-only", listOf("mods/a.jar"), "", "одинокая папка mods — это и есть папка игры"),
            Case("flat", listOf("mods/a.jar", "options.txt"), "", "папка игры без обёртки остаётся как есть"),
            Case("macos", listOf("MyPack/mods/a.jar", "__MACOSX/MyPack/._a.jar"), "MyPack", "мусор архиватора не считается второй папкой"),
            Case("double", listOf("Outer/Inner/mods/a.jar", "Outer/Inner/config/b.cfg"), "Outer/Inner", "две обёртки тоже снимаются"),
        )
        cases.forEach { case ->
            val dir = temp.newFolder("root-${case.name}")
            case.files.forEach { put(dir, it) }
            val expected = if (case.root.isEmpty()) dir else File(dir, case.root)
            assertEquals(case.why, expected.canonicalPath, PackArchive.contentRoot(dir).canonicalPath)
        }
    }
}
