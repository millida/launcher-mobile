package net.millida.launcher.core.content

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.profile.ContentFile
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileContent
import java.io.File
import java.io.IOException

/**
 * The verdicts are blunt on purpose. "Неизвестен" is not an accusation - a
 * hand-built jar is legitimately unknown - and saying otherwise would teach the
 * player to skip the whole report.
 */
enum class SafetyVerdict(val label: String, val rank: Int) {
    BROKEN("Не читается", 0),
    SUSPICIOUS("Подозрительный", 1),
    WRONG_LOADER("Другой загрузчик", 2),
    WRONG_GAME_VERSION("Другая версия игры", 3),
    UNKNOWN("Без описания", 4),
    OK("Обычный мод", 5),
}

data class ModVerdict(
    val file: ContentFile,
    val verdict: SafetyVerdict,
    val reasons: List<String>,
    val facts: ContentFacts,
) {
    /** Only these are turned off by "убрать в карантин": the rest are normal builds. */
    val quarantinable: Boolean
        get() = file.enabled && verdict in QUARANTINED

    companion object {
        val QUARANTINED = setOf(SafetyVerdict.BROKEN, SafetyVerdict.SUSPICIOUS, SafetyVerdict.WRONG_LOADER)
    }
}

data class SafetyReport(
    val items: List<ModVerdict>,
    val checked: Int,
    val problems: Int,
) {
    fun toQuarantine(): List<ModVerdict> = items.filter { it.quarantinable }
}

data class QuarantineReport(val disabled: List<String>, val failed: Map<String, String>)

class ContentSafety(
    private val layout: GameLayout,
    private val content: ProfileContent = ProfileContent(layout),
) {

    @Throws(IOException::class)
    fun scan(profile: Profile, profileDir: File, kind: ContentKind = ContentKind.MOD): SafetyReport {
        val folder = contentFolder(layout, profileDir, kind)
        val items = content.list(profileDir, kind)
            .map { judge(profile, folder, it, kind) }
            .sortedWith(compareBy({ it.verdict.rank }, { it.file.title.lowercase() }))
        return SafetyReport(
            items = items,
            checked = items.size,
            problems = items.count { it.verdict.rank <= SafetyVerdict.WRONG_GAME_VERSION.rank },
        )
    }

    /**
     * A suspicious file is turned off, never deleted: the player may well have
     * built it, and a wrong verdict must cost a rename and not the file.
     */
    @Throws(IOException::class)
    fun quarantine(profileDir: File, verdicts: List<ModVerdict>): QuarantineReport {
        val disabled = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        for (verdict in verdicts) {
            if (!verdict.file.enabled) continue
            try {
                content.setEnabled(profileDir, verdict.file, false)
                disabled += verdict.file.name
            } catch (error: IOException) {
                failed[verdict.file.name] = error.message ?: "не удалось отключить файл"
            }
        }
        return QuarantineReport(disabled, failed)
    }

    private fun judge(profile: Profile, folder: File, file: ContentFile, kind: ContentKind): ModVerdict {
        val path = SafePath.join(folder, file.name)
        val facts = ContentInspector.inspect(path)
        val reasons = ArrayList<String>()
        facts.findings.forEach { reasons += it.text }

        if (facts.unreadable.isNotEmpty() || facts.shape == ArchiveShape.NOT_AN_ARCHIVE) {
            return ModVerdict(file, SafetyVerdict.BROKEN, listOf(brokenReason(facts)), facts)
        }

        val strong = facts.strongFindings
        if (strong >= 2 || (strong >= 1 && reasons.size >= 2)) {
            return ModVerdict(file, SafetyVerdict.SUSPICIOUS, reasons, facts)
        }

        if (!facts.runsOn(profile.loader)) {
            reasons.add(0, wrongLoaderReason(profile, facts))
            return ModVerdict(file, SafetyVerdict.WRONG_LOADER, reasons, facts)
        }

        if (facts.fitsGameVersion(profile.gameVersion) == false) {
            reasons.add(0, "Мод собран под ${facts.gameVersionRange}, а сборка на ${profile.gameVersion}")
            return ModVerdict(file, SafetyVerdict.WRONG_GAME_VERSION, reasons, facts)
        }

        if (kind == ContentKind.MOD && !facts.hasModMetadata) {
            val why = if (facts.shape == ArchiveShape.MOD) {
                "В файле нет описания мода — проверь, откуда он"
            } else {
                "Это не мод: загрузчик такой файл не подхватит"
            }
            reasons.add(0, why)
            return ModVerdict(file, SafetyVerdict.UNKNOWN, reasons, facts)
        }

        return ModVerdict(file, SafetyVerdict.OK, reasons, facts)
    }

    private fun brokenReason(facts: ContentFacts): String =
        facts.unreadable.ifBlank { "Файл не открывается как архив" }
            .let { "Файл не годится для сборки: $it" }

    private fun wrongLoaderReason(profile: Profile, facts: ContentFacts): String {
        val built = facts.loaderLabel()
        val wanted = profile.loader.label
        return "Файл собран под $built, а сборка на $wanted"
    }
}
