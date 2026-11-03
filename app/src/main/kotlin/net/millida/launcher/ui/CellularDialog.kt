package net.millida.launcher.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import net.millida.launcher.core.http.CellularDownload
import net.millida.launcher.core.i18n.tr

/** Asked before a start downloads more than the threshold over a metered network. */
@Composable
fun CellularPlayDialog(name: String, bytes: Long, onDownload: () -> Unit, onWait: () -> Unit, onCancel: () -> Unit) {
    MDialog(
        title = tr("Загрузка по мобильной сети"),
        onDismiss = onCancel,
        body = {
            Text(
                tr("Для «{name}» нужно скачать около {size} МБ. Сейчас телефон в мобильной сети — это может стоить денег.", name, CellularDownload.megabytes(bytes)),
                style = MillidaType.body,
                color = millida.fgMuted,
            )
        },
        actions = {
            MSecondaryButton(tr("Подождать Wi-Fi"), onClick = onWait)
            MPrimaryButton(tr("Скачать"), onClick = onDownload)
        },
    )
}
