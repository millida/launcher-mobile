package net.millida.launcher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.LowMemoryNotice

/** Shown on start while the player has not asked to stop seeing it. */
@Composable
fun LowMemoryNoticeDialog(notice: LowMemoryNotice, onClose: (hideForever: Boolean) -> Unit) {
    var hide by rememberSaveable { mutableStateOf(false) }
    MDialog(
        title = "Мало памяти",
        onDismiss = { onClose(hide) },
        body = {
            Text(notice.text, style = MillidaType.body, color = millida.fgMuted)
            Spacer(Modifier.height(8.dp))
            MSwitchRow(title = "Не показывать снова", hint = null, checked = hide, onChange = { hide = it })
        },
        actions = {
            MPrimaryButton("Понятно", onClick = { onClose(hide) })
        },
    )
}

/** Asked before every start on such a phone: the player decides, the launcher only warns. */
@Composable
fun LowMemoryPlayDialog(notice: LowMemoryNotice, onPlay: () -> Unit, onCancel: () -> Unit) {
    MConfirmDialog(
        title = "Мало памяти",
        text = notice.text,
        confirm = "Всё равно играть",
        onConfirm = onPlay,
        onDismiss = onCancel,
        danger = false,
    )
}
