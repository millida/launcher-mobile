package net.millida.launcher.ui.home

import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.VersionManifest
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionPickTest {

    private fun entry(id: String, type: String = "release", releaseTime: String = "", time: String = "") =
        VersionManifest.ManifestEntry(id = id, type = type, url = "https://piston-meta.mojang.com/$id.json", time = time, releaseTime = releaseTime)

    @Test
    fun `старые версии до 1_13`() {
        data class Case(val entry: VersionManifest.ManifestEntry?, val expected: Boolean, val why: String)
        listOf(
            Case(null, false, "без версии предупреждать не о чем"),
            Case(entry("1.12.2"), true, "1.12.2 требует Java 8 и старое окно"),
            Case(entry("1.7.10"), true, "1.7.10 старая, хотя 7 < 13 только по второму числу"),
            Case(entry("1.13"), false, "1.13 уже на новой Java"),
            Case(entry("1.20.1"), false, "современная версия"),
            Case(entry("1.8.9"), true, "1.8.9 старая"),
            Case(entry("18w01a", "snapshot", releaseTime = "2018-01-03T13:00:00+00:00"), true, "снапшот до выхода 1.13 сравнивается по дате"),
            Case(entry("24w14a", "snapshot", releaseTime = "2024-04-03T12:00:00+00:00"), false, "свежий снапшот не старый"),
            Case(entry("b1.7.3", "old_beta", time = "2011-07-08T00:00:00+00:00"), true, "без даты релиза берётся время записи"),
            Case(entry("weird"), false, "без числа и без даты версия не объявляется старой"),
        ).forEach { case ->
            assertEquals("${case.entry?.id}: ${case.why}", case.expected, isLegacyVersion(case.entry))
        }
    }

    @Test
    fun `новый конвейер отрисовки после 1_21_4`() {
        data class Case(val entry: VersionManifest.ManifestEntry?, val expected: Boolean, val why: String)
        listOf(
            Case(null, false, "без версии предупреждения нет"),
            Case(entry("1.21.4"), false, "1.21.4 — последняя на старом конвейере"),
            Case(entry("1.21.5"), true, "1.21.5 рисует через Blaze3D"),
            Case(entry("1.20.1"), false, "старая версия"),
            Case(entry("25w09a", "snapshot", releaseTime = "2025-02-26T12:00:00+00:00"), true, "снапшот после 1.21.4 сравнивается по дате"),
        ).forEach { case ->
            assertEquals("${case.entry?.id}: ${case.why}", case.expected, isNewRenderer(case.entry))
        }
    }

    @Test
    fun `какие загрузчики предлагаются для версии`() {
        data class Case(val loader: LoaderKind, val entry: VersionManifest.ManifestEntry?, val expected: LoaderGate, val why: String)
        listOf(
            Case(LoaderKind.FORGE, entry("1.12.2"), LoaderGate.Unsupported, "старому Forge нужна Java 8, которой нет в пакете"),
            Case(LoaderKind.FORGE, entry("1.20.1"), LoaderGate.Ready, "современный Forge ставится"),
            Case(LoaderKind.FORGE, null, LoaderGate.Ready, "без версии не отказываем заранее"),
            Case(LoaderKind.FABRIC, entry("1.12.2"), LoaderGate.Unsupported, "Fabric meta отвечает 400 ниже 1.14 — создание сборки падало"),
            Case(LoaderKind.FABRIC, entry("1.14"), LoaderGate.Ready, "1.14 — первая версия Fabric"),
            Case(LoaderKind.FABRIC, entry("19w14a", "snapshot", releaseTime = "2019-04-03T12:00:00+00:00"), LoaderGate.Ready, "снапшоты после 18w43b у Fabric есть"),
            Case(LoaderKind.QUILT, entry("1.8.9"), LoaderGate.Unsupported, "Quilt не старше Fabric"),
            Case(LoaderKind.VANILLA, entry("1.7.10"), LoaderGate.Ready, "ваниль доступна всегда"),
            Case(LoaderKind.NEOFORGE, entry("1.21.1"), LoaderGate.Ready, "NeoForge на актуальной ветке"),
            Case(LoaderKind.NEOFORGE, entry("1.20.1"), LoaderGate.Ready, "1.20.1 — первая ветка NeoForge, лежит в артефакте forge"),
            Case(LoaderKind.NEOFORGE, entry("1.19.2"), LoaderGate.Unsupported, "до форка NeoForge сборок нет — раньше предлагался и падал"),
            Case(LoaderKind.NEOFORGE, null, LoaderGate.Ready, "без версии не отказываем заранее"),
            Case(LoaderKind.QUILT, entry("1.20.1"), LoaderGate.Ready, "Quilt доступен"),
        ).forEach { case ->
            assertEquals("${case.loader} ${case.entry?.id}: ${case.why}", case.expected, loaderGate(case.loader, case.entry))
        }
    }

    @Test
    fun `поиск по списку версий`() {
        val all = listOf(
            entry("1.21.4"),
            entry("24w14a", "snapshot"),
            entry("1.20.1"),
            entry("1.20"),
        )
        data class Case(val query: String, val snapshots: Boolean, val expected: List<String>, val why: String)
        listOf(
            Case("", false, listOf("1.21.4", "1.20.1", "1.20"), "без снапшотов видны только релизы"),
            Case("", true, listOf("1.21.4", "24w14a", "1.20.1", "1.20"), "со снапшотами видно всё в исходном порядке"),
            Case("1.20", false, listOf("1.20.1", "1.20"), "поиск по подстроке"),
            Case("  W14A ", true, listOf("24w14a"), "пробелы и регистр запроса не мешают"),
            Case("w14a", false, emptyList(), "снапшот не находится, пока снапшоты выключены"),
        ).forEach { case ->
            assertEquals("«${case.query}», снапшоты=${case.snapshots}: ${case.why}", case.expected, filterVersions(all, case.query, case.snapshots).map { it.id })
        }
    }
}
