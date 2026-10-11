package net.millida.launcher.core

import net.millida.launcher.core.cosmetics.CosmeticsModState
import net.millida.launcher.core.cosmetics.CosmeticsModStatus
import net.millida.launcher.core.cosmetics.LaunchModAction
import net.millida.launcher.core.cosmetics.MillidaModOffer
import org.junit.Assert.assertEquals
import org.junit.Test

class MillidaModLaunchTest {

    private fun state(
        status: CosmeticsModStatus,
        installedFile: String = "",
        availableFile: String = "millida-mod-2.jar",
        enabled: Boolean = true,
    ) = CosmeticsModState(
        profileId = "p",
        gameVersion = "1.21.1",
        loaderId = "fabric",
        status = status,
        installedFile = installedFile,
        installedEnabled = enabled,
        availableFile = availableFile,
    )

    private data class Case(
        val state: CosmeticsModState?,
        val intact: Boolean,
        val declined: Boolean,
        val expected: LaunchModAction,
        val why: String,
    )

    @Test
    fun `при запуске мод обновляется молча, а вопрос задаётся только сборке без мода`() {
        val cases = listOf(
            Case(state(CosmeticsModStatus.MISSING), true, false, LaunchModAction.OFFER,
                "мода нет — спрашиваем, как раньше"),
            Case(state(CosmeticsModStatus.MISSING), true, true, LaunchModAction.NONE,
                "«Без мода» отвечает на вопрос об установке и запоминается"),
            Case(state(CosmeticsModStatus.OUTDATED, installedFile = "millida-mod-1.jar"), true, false, LaunchModAction.UPDATE,
                "старая версия: раньше мод ставился только при первом запуске и больше не обновлялся"),
            Case(state(CosmeticsModStatus.OUTDATED, installedFile = "millida-mod-1.jar"), true, true, LaunchModAction.UPDATE,
                "отказ от установки не значит отказ от обновлений уже стоящего мода"),
            Case(state(CosmeticsModStatus.INSTALLED, installedFile = "millida-mod-2.jar"), false, false, LaunchModAction.UPDATE,
                "файл перезалит под тем же именем — сверка суммы единственное, что это ловит"),
            Case(state(CosmeticsModStatus.INSTALLED, installedFile = "millida-mod-2.jar"), true, false, LaunchModAction.NONE,
                "актуальный и целый мод не трогаем — лишняя загрузка на каждом запуске"),
            Case(state(CosmeticsModStatus.DISABLED, installedFile = "millida-mod-1.jar.disabled", enabled = false), true, false, LaunchModAction.NONE,
                "мод выключен руками — выбор игрока не перебиваем"),
            Case(state(CosmeticsModStatus.UNSUPPORTED_VERSION, installedFile = "millida-mod-1.jar", availableFile = ""), true, false, LaunchModAction.NONE,
                "под версию варианта нет — ставить нечего"),
            Case(null, true, false, LaunchModAction.NONE,
                "каталог не прочитался — запуск идёт как есть"),
        )
        for (case in cases) {
            assertEquals(
                "действие при запуске: ${case.why}",
                case.expected,
                MillidaModOffer.onLaunch(case.state, case.intact, case.declined),
            )
        }
    }

    @Test
    fun `та же цель обновления повторяется не чаще раза в сутки`() {
        data class Due(val lastSha: String?, val lastAt: Long, val sha: String?, val now: Long, val due: Boolean, val why: String)

        val day = MillidaModOffer.UPDATE_RETRY_MS
        val start = 1_000_000_000L
        val cases = listOf(
            Due(null, 0, "aa", start, true, "попыток не было — обновляем"),
            Due("aa", start, "aa", start + day - 1, false, "CDN отдаёт файл не с той суммой — не качаем его на каждом запуске"),
            Due("AA", start, "aa", start + 1, false, "сумма сравнивается без учёта регистра"),
            Due("aa", start, "aa", start + day, true, "сутки прошли — пробуем снова, CDN мог исправиться"),
            Due("aa", start, "bb", start + 1, true, "в каталоге новая сумма — это другая цель, обновляем сразу"),
            Due("aa", start, null, start + 1, true, "у варианта нет суммы — запоминать нечего"),
            Due("aa", start, "aa", start - 1, true, "часы ушли назад — запись не должна блокировать навсегда"),
        )
        for (case in cases) {
            assertEquals(
                "повтор обновления: ${case.why}",
                case.due,
                MillidaModOffer.updateDue(case.lastSha, case.lastAt, case.sha, case.now),
            )
        }

        val broken = state(CosmeticsModStatus.INSTALLED, installedFile = "millida-mod-2.jar")
        assertEquals(
            "недавняя попытка той же цели снимает обновление при запуске",
            LaunchModAction.NONE,
            MillidaModOffer.onLaunch(broken, intact = false, declined = false, updateDue = false),
        )
    }
}
