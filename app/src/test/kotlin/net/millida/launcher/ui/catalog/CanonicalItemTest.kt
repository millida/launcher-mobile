package net.millida.launcher.ui.catalog

import net.millida.launcher.core.catalog.CatalogItem
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.CatalogVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalItemTest {

    @Test
    fun `карточка из каталога Millida узнаёт поставленный файл по id проекта`() {
        data class Case(
            val item: CatalogItem,
            val versions: List<CatalogVersion>,
            val expected: String,
            val why: String,
        )
        val bySlug = CatalogItem(projectId = "makeup-ultra-fast-shaders", slug = "makeup-ultra-fast-shaders")
        val byId = CatalogItem(projectId = "izsIPI7a", slug = "makeup-ultra-fast-shaders")
        val curse = CatalogItem(projectId = "238222", source = CatalogSource.CURSEFORGE)
        listOf(
            Case(bySlug, listOf(CatalogVersion("v1", projectId = "izsIPI7a")), "izsIPI7a",
                "слаг из каталога Millida меняется на id, который установка пишет в запись о файле"),
            Case(byId, listOf(CatalogVersion("v1", projectId = "izsIPI7a")), "izsIPI7a", "id из поиска не трогается"),
            Case(bySlug, emptyList(), "makeup-ultra-fast-shaders", "без версий подменять не на что"),
            Case(bySlug, listOf(CatalogVersion("v1"), CatalogVersion("v2", projectId = "izsIPI7a")), "izsIPI7a",
                "версия без project_id пропускается"),
            Case(curse, listOf(CatalogVersion("1", projectId = "999", source = CatalogSource.CURSEFORGE)), "238222",
                "у CurseForge id числовой и слагом не бывает"),
        ).forEach { case ->
            assertEquals(case.why, case.expected, CatalogModel.canonicalItem(case.item, case.versions).projectId)
        }
    }
}
