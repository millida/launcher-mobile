package net.millida.launcher.core

import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProfileStoreSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun layout(): GameLayout = GameLayout(tmp.newFolder("game-" + System.nanoTime())).also { it.ensure() }

    private fun profile(id: String) = Profile(id = id, name = id, gameVersion = "1.20.1")

    private data class IdCase(val listed: List<String>, val folders: List<String>, val expected: String, val why: String)

    @Test
    fun `новая сборка не въезжает в чужую папку`() {
        val cases = listOf(
            IdCase(emptyList(), emptyList(), "моя_сборка", "пусто — имя как есть"),
            IdCase(listOf("моя_сборка"), emptyList(), "моя_сборка-2", "занято списком"),
            IdCase(emptyList(), listOf("моя_сборка"), "моя_сборка-2",
                "папка без записи в списке — миры и моды потерянной сборки, новая сборка смешалась бы с ними"),
            IdCase(emptyList(), listOf("Моя_Сборка"), "моя_сборка-2",
                "память телефона не различает регистр: папка с заглавными — та же папка"),
            IdCase(listOf("моя_сборка"), listOf("моя_сборка-2"), "моя_сборка-3", "занятость складывается из списка и папок"),
        )
        for (case in cases) {
            val layout = layout()
            val store = ProfileStore(layout)
            case.listed.forEach { store.upsert(profile(it)) }
            case.folders.forEach { File(layout.profiles, it).mkdirs() }
            assertEquals("newId: ${case.why}", case.expected, store.newId("Моя сборка"))
        }
    }

    private data class BrokenCase(val backup: Boolean, val expectedAfterUpsert: List<String>, val why: String)

    @Test
    fun `повреждённый список не теряет сборки при следующем сохранении`() {
        val cases = listOf(
            BrokenCase(true, listOf("a", "b", "c"),
                "есть копия последнего сохранения: раньше upsert после порчи писал список из одной сборки"),
            BrokenCase(false, listOf("c"), "копии нет (файл от старой версии) — сохраняем, что есть, испорченный лежит рядом"),
        )
        for (case in cases) {
            val layout = layout()
            val store = ProfileStore(layout)
            store.upsert(profile("a"))
            store.upsert(profile("b"))
            if (!case.backup) File(layout.root, "profiles.json.bak").delete()
            File(layout.root, "profiles.json").writeText("{обрыв записи")

            val after = store.upsert(profile("c")).map { it.id }

            assertEquals("список после порчи: ${case.why}", case.expectedAfterUpsert, after)
            assertTrue(
                "испорченный файл сохраняется для разбора: ${case.why}",
                File(layout.root, "profiles.json.broken").readText().contains("обрыв"),
            )
        }
    }

    @Test
    fun `параллельные сохранения не теряют сборки`() {
        val store = ProfileStore(layout())
        val pool = Executors.newFixedThreadPool(8)
        val ids = (1..40).map { "p$it" }
        ids.forEach { id -> pool.execute { store.upsert(profile(id)) } }
        pool.shutdown()
        assertTrue("сохранения не уложились во время", pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(
            "без замка два upsert читали один и тот же список, и второй затирал сборку первого",
            ids.toSet(),
            store.load().map { it.id }.toSet(),
        )
    }

    private data class BackupCase(val backupWritable: Boolean, val reported: Int, val why: String)

    @Test
    fun `сбой записи резервной копии не роняет сохранение списка`() {
        val cases = listOf(
            BackupCase(true, 0, "копия пишется — сообщать не о чем"),
            BackupCase(false, 1, "копия не легла, а список уже записан — сохранение успешно, сбой уходит в журнал"),
        )
        for (case in cases) {
            val layout = layout()
            val failures = ArrayList<Exception>()
            val store = ProfileStore(layout) { failures += it }
            if (!case.backupWritable) File(layout.root, "profiles.json.bak.new").mkdirs()

            val saved = runCatching { store.upsert(profile("a")) }

            assertTrue("upsert не должен падать: ${case.why}: ${saved.exceptionOrNull()}", saved.isSuccess)
            assertEquals("список записан: ${case.why}", listOf("a"), ProfileStore(layout).load().map { it.id })
            assertEquals("сбой копии передан в журнал: ${case.why}", case.reported, failures.size)
        }
    }
}
