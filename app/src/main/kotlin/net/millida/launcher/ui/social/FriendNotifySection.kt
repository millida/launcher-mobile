package net.millida.launcher.ui.social

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.core.friends.NotifyKind
import net.millida.launcher.core.friends.NotifyLevel
import net.millida.launcher.core.friends.levelOf
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.LocalUiFeedback
import net.millida.launcher.ui.MSegmented
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.settings.SettingHead
import net.millida.launcher.ui.settings.SettingHint
import net.millida.launcher.ui.settings.SettingsSection

private val ROWS = listOf(
    NotifyKind.MSG to "Личные сообщения",
    NotifyKind.ROOM to "Сообщения в группах",
    NotifyKind.PLAY to "Друг зашёл в игру",
    NotifyKind.ONLINE to "Друг в сети",
    NotifyKind.REQUEST to "Заявки в друзья",
)

private fun NotifyLevel.label(): String = when (this) {
    NotifyLevel.SOUND -> "Со звуком"
    NotifyLevel.SILENT -> "Без звука"
    NotifyLevel.OFF -> "Выкл"
}

/** The desktop "Уведомления" matrix (src/modals/Settings.tsx), one row per kind. */
@Composable
fun FriendNotifySection(model: LauncherViewModel, modifier: Modifier = Modifier) {
    val levels by model.social.notifyLevels.collectAsStateWithLifecycle()
    val feedback = LocalUiFeedback.current
    val context = LocalContext.current
    var systemAllowed by remember { mutableStateOf(model.social.notifySystemAllowed()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { systemAllowed = model.social.notifySystemAllowed() }

    SettingsSection("УВЕДОМЛЕНИЯ", modifier) {
        if (!systemAllowed) {
            SettingHint("Выключены в настройках телефона", color = millida.danger)
            MTextButton(
                "Включить",
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    runCatching { context.startActivity(intent) }
                        .onFailure { model.notice("Открой настройки телефона → Приложения → Millida → Уведомления", isError = true) }
                },
                color = millida.accentText,
            )
            Spacer(Modifier.height(8.dp))
        }
        ROWS.forEachIndexed { index, (kind, title) ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            SettingHead(title)
            Spacer(Modifier.height(6.dp))
            MSegmented(
                options = NotifyLevel.entries,
                selected = levels.levelOf(kind),
                onSelect = { level ->
                    model.social.setNotifyLevel(kind, level)
                    if (level == NotifyLevel.SOUND) feedback.play(UiSoundEvent.Notify)
                },
                label = { it.label() },
            )
        }
    }
}
