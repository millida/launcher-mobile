package net.millida.launcher.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.cabinet.availableText
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.portal.Privacy
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MInfoRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSwitchRow
import net.millida.launcher.ui.openLinkOrNotice

/** The desktop's profile switches (Settings.tsx privacyRows), titled as on millida.net. */
private val PRIVACY_ROWS: List<Pair<String, String>> get() = listOf(
    "showActivity" to tr("Игровая активность"),
    "showServers" to tr("Серверы"),
    "showPlaytime" to tr("Часы в игре"),
    "showFriends" to tr("Список друзей"),
    "showAchievements" to tr("Достижения"),
    "showMarket" to tr("Маркет"),
)

private fun Privacy.flag(key: String): Boolean = when (key) {
    "showActivity" -> showActivity
    "showServers" -> showServers
    "showPlaytime" -> showPlaytime
    "showFriends" -> showFriends
    "showAchievements" -> showAchievements
    "showMarket" -> showMarket
    else -> false
}

/**
 * The desktop's «Аккаунт» tab: the wallet with its top-up, what the profile on
 * millida.net shows, who may call to a duo emote, and the usage statistics.
 * Discord activity has no phone counterpart and is left out.
 */
@Composable
internal fun AccountSettingsSection(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val support by model.support.state.collectAsStateWithLifecycle()
    val signedIn = state.millidaSignedIn
    LaunchedEffect(signedIn) {
        if (signedIn && state.wallet == null) model.loadCabinet()
        if (signedIn && state.privacy == null) model.loadAccountDetails()
    }

    SettingsSection(tr("АККАУНТ"), modifier) {
        if (signedIn) {
            MInfoRow(tr("Баланс"), state.wallet?.availableText() ?: "—", mono = true)
            Spacer(Modifier.height(6.dp))
            MSecondaryButton(
                tr("Пополнить"),
                onClick = { model.openLinkOrNotice(context, model.topUpUrl()) },
                icon = MIcons.externalLink,
                size = MButtonSize.Small,
            )
            Spacer(Modifier.height(10.dp))
            MDivider()
        }
        Spacer(Modifier.height(6.dp))
        SettingHead(tr("Видно в профиле на millida.net"))
        val privacy = state.privacy
        when {
            !signedIn -> {
                SettingHint(tr("Нужен аккаунт Millida"))
                Spacer(Modifier.height(8.dp))
                MPrimaryButton(tr("Войти"), onClick = model::loginMillida, icon = MIcons.login, size = MButtonSize.Small)
            }
            privacy == null -> SettingHint(tr("Загружаем…"))
            else -> PRIVACY_ROWS.forEach { (key, title) ->
                MSwitchRow(title, null, privacy.flag(key)) { model.togglePrivacy(key, it) }
            }
        }
        if (signedIn && privacy != null) {
            Spacer(Modifier.height(10.dp))
            MDivider()
            Spacer(Modifier.height(6.dp))
            SettingHead(tr("Эмоции вдвоём · кто может звать"))
            Spacer(Modifier.height(8.dp))
            MButtonRow {
                Privacy.DUO_INVITES.forEach { (value, label) ->
                    MChip(text = label, selected = privacy.duoInvites == value, onClick = { model.setDuoInvites(value) })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        MDivider()
        MSwitchRow(
            tr("Статистика использования"),
            tr("Версия, железо, вылеты, нажатия. Без паролей и файлов"),
            support.anonymousStats,
            model.support::setAnonymousStats,
        )
    }
}
